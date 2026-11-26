package com.example.data.ai.rag

import com.example.data.model.ExtractedConcept
import com.example.data.model.GroundingSource
import com.example.data.model.ImportedChat
import com.example.data.model.StudyTopic
import com.example.data.model.TutorMessageEntity
import com.example.data.model.TutorSession
import kotlin.math.ln

/** A short piece of the student's own material, with where it came from. */
data class KnowledgePassage(
  val text: String,
  /** e.g. "Imported chat: OS scheduling" */
  val sourceTitle: String,
  /** The share link for an imported chat; blank for notes and tutor chats. */
  val url: String = "",
  val score: Double = 0.0
) {
  companion object {
    /**
     * Added after the student's question when passages were found: answer from them, cite them as
     * [1], [2], and say plainly when they don't cover the question.
     */
    fun groundingInstruction(passages: List<KnowledgePassage>): String {
      if (passages.isEmpty()) return ""
      return buildString {
        append("\n\n---\nFrom my own study material (numbered):\n")
        passages.forEachIndexed { i, p -> append("\n[${i + 1}] (${p.sourceTitle})\n${p.text}\n") }
        append("\nAnswer my question using this material where it covers it, and cite it inline like [1]. ")
        append("Where it doesn't cover something, say so briefly and answer from general knowledge, carefully; don't invent citations.")
      }
    }

    /**
     * The passages the answer used, as sources to show under it: the ones it cites as [n], or, when
     * a model doesn't write citations, the ones much of whose wording the answer repeats.
     */
    fun citedSources(answer: String, passages: List<KnowledgePassage>): List<GroundingSource> {
      val cited = Regex("""\[(\d{1,2})]""").findAll(answer).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
      val used = if (cited.isNotEmpty()) {
        passages.filterIndexed { i, _ -> (i + 1) in cited }
      } else {
        val answerTerms = KnowledgeIndex.terms(answer).toSet()
        passages.filter { p ->
          val terms = KnowledgeIndex.terms(p.text).toSet()
          terms.size >= 5 && terms.count { it in answerTerms }.toDouble() / terms.size >= 0.4
        }.take(3)
      }
      return used.map { GroundingSource(it.sourceTitle, it.url) }.distinctBy { it.title }
    }
  }
}

/**
 * Keyword search (BM25) over everything the student keeps: imported chats, tutor conversations,
 * topic notes and extracted concepts, cut into passages of a few hundred words. Runs on the device,
 * so it costs nothing and works offline; the matching passages go to the model with the question.
 */
class KnowledgeIndex private constructor(private val passages: List<KnowledgePassage>) {

  private val docTerms: List<Map<String, Int>> = passages.map { p -> terms(p.sourceTitle + " " + p.text).groupingBy { it }.eachCount() }
  private val lengths = docTerms.map { it.values.sum().coerceAtLeast(1) }
  private val avgLength = if (lengths.isEmpty()) 1.0 else lengths.average()
  private val docFreq: Map<String, Int> = HashMap<String, Int>().also { df -> docTerms.forEach { d -> d.keys.forEach { df[it] = (df[it] ?: 0) + 1 } } }

  val size: Int get() = passages.size

  /** The best [limit] passages for [query]; nothing when no passage shares a meaningful word with it. */
  fun search(query: String, limit: Int = 5): List<KnowledgePassage> {
    val queryTerms = terms(query).distinct()
    if (queryTerms.isEmpty() || passages.isEmpty()) return emptyList()
    val n = passages.size
    val scored = passages.indices.map { i ->
      val d = docTerms[i]
      var score = 0.0
      for (t in queryTerms) {
        val tf = d[t] ?: continue
        val df = docFreq[t] ?: continue
        val idf = ln(1.0 + (n - df + 0.5) / (df + 0.5))
        score += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * lengths[i] / avgLength))
      }
      i to score
    }.filter { it.second > 0 }.sortedByDescending { it.second }
    val best = scored.firstOrNull()?.second ?: return emptyList()
    // Keep passages close to the best match, so a stray shared word doesn't pull in noise.
    return scored.filter { it.second >= best * 0.35 }.take(limit).map { (i, s) -> passages[i].copy(score = s) }
  }

  companion object {
    private const val K1 = 1.2
    private const val B = 0.75
    private const val PASSAGE_CHARS = 900

    fun build(
      chats: List<ImportedChat>,
      tutorSessions: List<Pair<TutorSession, List<TutorMessageEntity>>>,
      topics: List<StudyTopic>,
      concepts: List<ExtractedConcept>
    ): KnowledgeIndex {
      val out = ArrayList<KnowledgePassage>()
      for (chat in chats) {
        chunk(chat.rawConversation).forEach { out += KnowledgePassage(it, "Imported chat: ${chat.title}", chat.sourceUrl) }
      }
      for ((session, messages) in tutorSessions) {
        val text = messages.filterNot { it.isError || it.text.isBlank() }
          .joinToString("\n") { (if (it.role == "user") "Student: " else "Tutor: ") + it.text.trim() }
        chunk(text).forEach { out += KnowledgePassage(it, "Tutor chat: ${session.title}") }
      }
      // Topics made from tutor chats or imports repeat what is indexed above.
      for (topic in topics.filter { it.sourceType == "MANUAL" || it.sourceType == "PASTED" || it.sourceType == "UPLOAD" }) {
        chunk(topic.title + ". " + topic.descriptionNotes).forEach { out += KnowledgePassage(it, "Notes: ${topic.title}") }
      }
      for (c in concepts) {
        val text = buildString {
          append(c.conceptName).append(": ").append(c.definition)
          if (c.technicalDetails.isNotBlank()) append(" ").append(c.technicalDetails)
          if (c.keyFacts.isNotBlank()) append(" Key facts: ").append(c.keyFacts)
          if (c.misconceptionsAndCorrections.isNotBlank()) append(" Common mistake: ").append(c.misconceptionsAndCorrections)
        }
        out += KnowledgePassage(text.take(PASSAGE_CHARS * 2), "Concept: ${c.conceptName} (${c.sourceChatTitle})")
      }
      return KnowledgeIndex(out)
    }

    /** Passages of about [PASSAGE_CHARS], split at paragraph or sentence ends, overlapping a little. */
    fun chunk(text: String): List<String> {
      val clean = text.replace(Regex("""[ \t]+"""), " ").trim()
      if (clean.length < 40) return emptyList()
      if (clean.length <= PASSAGE_CHARS) return listOf(clean)
      val pieces = clean.split(Regex("""(?<=\n\n)|(?<=[.!?])\s+""")).filter { it.isNotBlank() }
      val chunks = ArrayList<String>()
      val current = StringBuilder()
      for (piece in pieces) {
        if (current.length + piece.length > PASSAGE_CHARS && current.isNotEmpty()) {
          chunks += current.toString().trim()
          // Carry the last sentence over so an idea split across chunks is found from either side.
          val tail = current.toString().trim().split(Regex("""(?<=[.!?])\s+""")).lastOrNull().orEmpty()
          current.clear()
          if (tail.length < PASSAGE_CHARS / 3) current.append(tail).append(' ')
        }
        current.append(piece.trim()).append(' ')
      }
      if (current.isNotBlank()) chunks += current.toString().trim()
      return chunks.map { it.take(PASSAGE_CHARS * 2) }
    }

    private val STOP = setOf(
      "the", "and", "for", "are", "was", "with", "that", "this", "what", "which", "how", "why", "when", "where", "who",
      "you", "your", "can", "does", "did", "its", "it's", "from", "into", "about", "explain", "tell", "give", "please",
      "show", "use", "used", "using", "there", "their", "they", "them", "then", "than", "also", "just", "like", "will",
      "would", "should", "could", "have", "has", "had", "not", "but", "all", "any", "some", "more", "most", "very",
      "one", "two", "student", "tutor", "answer", "question", "example", "me", "is", "in", "of", "to", "a", "an", "on"
    )

    /** Lower-case words of 2+ letters, minus filler, with a plural "s" dropped so "heaps" finds "heap". */
    fun terms(text: String): List<String> =
      text.lowercase().split(Regex("[^a-z0-9+#]+"))
        .filter { it.length >= 2 && it !in STOP }
        .map { if (it.length > 4 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }
  }
}
