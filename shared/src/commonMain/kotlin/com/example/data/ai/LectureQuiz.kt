package com.example.data.ai

import com.example.data.model.QuizQuestion
import com.example.data.model.StudyTopic

/**
 * The built-in (no model) way to turn lecture files into study topics and real questions. It reads
 * slide and page titles to find what a file teaches, skips course administration, and writes
 * questions from definitions, key terms, true/false swaps and bullet lists, with wrong options taken
 * from the same material so they're plausible.
 */
object LectureQuiz {

  /** A topic found in a file: its title, notes, and the pages it spans (1-based). */
  data class FoundTopic(val title: String, val notes: String, val pages: List<Int>)

  // --- Course administration isn't study material ---

  private val ADMIN = Regex(
    "(?i)\\b(grading|grade[sd]?|ects|credits?|attendance|office hours?|contact|e-?mail|syllabus|course (info|information|overview|outline|schedule)|" +
      "exam (rules|regulations|dates?|registration)|deadline|submission|canvas|blackboard|moodle|lecturer|teaching assistant|" +
      "evaluation|assessment|learning outcomes?|prerequisites?|timetable|room|announcement|welcome|practical information|" +
      "info for students|studiegids|evaluatie|beoordeling|lesrooster|praktische informatie|aanwezigheid)\\b"
  )

  /** Whether a page (or a model's topic) is about how the course runs rather than what it teaches. */
  fun isAdministrative(title: String, text: String): Boolean {
    if (ADMIN.containsMatchIn(title)) return true
    val words = text.split(Regex("\\s+")).size.coerceAtLeast(1)
    val hits = ADMIN.findAll(text).count()
    // Many admin words for its length, and little else: a logistics page.
    return hits >= 3 && hits * 25 >= words
  }

  // --- Topics from pages ---

  /**
   * Topics in a file from its pages (or slides): consecutive pages under the same heading form one
   * topic; title, contents and admin pages are dropped. Very long topics are split.
   */
  fun topicsFromPages(pages: List<String>, fileTitle: String, only: Set<Int>? = null): List<FoundTopic> {
    data class Run(var title: String, val text: StringBuilder, val pages: MutableList<Int>)
    val runs = mutableListOf<Run>()
    withoutBoilerplate(pages).forEachIndexed { i, raw ->
      if (only != null && (i + 1) !in only) return@forEachIndexed
      val page = raw.trim()
      if (page.length < 40) return@forEachIndexed
      val heading = headingOf(page) ?: fileTitle
      if (isAdministrative(heading, page)) return@forEachIndexed
      if (Regex("(?i)^(contents|table of contents|agenda|outline|overview|questions\\??|thank you|references|bibliography)$").matches(heading)) return@forEachIndexed
      val last = runs.lastOrNull()
      if (last != null && similar(last.title, heading) && last.text.length < 6000) {
        last.text.append("\n\n").append(page)
        last.pages += i + 1
      } else {
        runs += Run(heading, StringBuilder(page), mutableListOf(i + 1))
      }
    }
    return runs.map { FoundTopic(it.title.take(80), it.text.toString().take(12_000), it.pages) }
  }

  /**
   * Pages without the lines repeated on many of them: running headers and footers such as a date and
   * the lecturers' names, or the course name with a page number. Compared without digits, so "...3"
   * and "...4" count as the same line.
   */
  fun withoutBoilerplate(pages: List<String>): List<String> {
    if (pages.size < 4) return pages
    fun key(line: String) = line.trim().lowercase().replace(Regex("[0-9]+"), "#").replace(Regex("\\s+"), " ")
    val seen = HashMap<String, Int>()
    pages.forEach { p -> p.lines().map(::key).filter { it.length >= 3 }.toSet().forEach { seen[it] = (seen[it] ?: 0) + 1 } }
    val repeated = seen.filter { (_, n) -> n >= 3 && n * 10 >= pages.size * 3 }.keys
    if (repeated.isEmpty()) return pages
    return pages.map { p -> p.lines().filter { key(it) !in repeated }.joinToString("\n") }
  }

  /** A page's heading: its first short line that reads like a title. */
  fun headingOf(page: String): String? = page.lines().map { it.trim().trim('•', '-', '*', '·').trim() }
    .firstOrNull { line -> line.length in 3..80 && line.any { it.isLetter() } && !line.endsWith('.') && !Regex("^\\d+(/\\d+)?$").matches(line) }

  private fun similar(a: String, b: String): Boolean {
    fun norm(s: String) = s.lowercase().replace(Regex("\\(?(cont(inued|'d)?|\\d+/\\d+|part \\d+)\\)?"), "").replace(Regex("[^a-z0-9 ]"), " ").trim()
    val x = norm(a); val y = norm(b)
    return x == y || (x.length > 6 && (y.startsWith(x) || x.startsWith(y)))
  }

  // --- Questions ---

  /** A term and what the material says it is. */
  data class Definition(val term: String, val meaning: String)

  private val DEFINES = listOf(
    Regex("^([A-Z][\\w\\- /()]{1,50}?)\\s+(?:is|are)\\s+(?:a|an|the)\\s+(.{12,220}?)[.;](?:\\s|$)"),
    Regex("^([A-Z][\\w\\- /()]{1,50}?)\\s+(?:means|refers to|is defined as|stands for)\\s+(.{6,220}?)[.;](?:\\s|$)"),
    Regex("^([A-Z][\\w\\- /()]{1,40}?)\\s*[:–—]\\s+(.{12,220})$")
  )

  /** Definitions written as "X is a ...", "X refers to ...", "X: ..." (one per line or sentence). */
  fun definitions(text: String): List<Definition> {
    val units = text.lines().flatMap { line -> line.split(Regex("(?<=[.!?])\\s+(?=[A-Z])")) }
      .map { it.trim().trim('•', '-', '*', '·').trim() }.filter { it.length in 15..300 }
    return units.mapNotNull { u ->
      DEFINES.firstNotNullOfOrNull { r -> r.find(u)?.let { Definition(it.groupValues[1].trim(), it.groupValues[2].trim().trimEnd('.', ';')) } }
    }.filter { d ->
      d.term.split(' ').size <= 6 && !d.term.lowercase().let { it in setOf("this", "it", "there", "that", "which", "these", "the following") } &&
        d.meaning.split(' ').size >= 3
    }.distinctBy { it.term.lowercase() }
  }

  /** Terms that matter in [text]: acronyms and capitalised or repeated multi-word terms. */
  fun keyTerms(text: String): List<String> {
    val acronyms = Regex("\\b[A-Z][A-Z0-9]{1,7}s?\\b").findAll(text).map { it.value.trimEnd('s') }
      .filter { it.length >= 2 && it !in setOf("I", "A", "OK", "PDF", "THE", "AND", "OR", "NOT", "IT") }
    val phrases = Regex("\\b([A-Z][a-z]+(?:[ -][A-Z][a-z]+){1,3})\\b").findAll(text).map { it.value }
    return (acronyms + phrases).groupingBy { it }.eachCount()
      .filter { (term, n) -> n >= 2 || term.all { c -> c.isUpperCase() || c.isDigit() } }
      .entries.sortedByDescending { it.value }.map { it.key }.take(40)
  }

  /** Bullet lists under a lead-in line ("Advantages of TCP:"), as (lead-in, items). */
  fun lists(text: String): List<Pair<String, List<String>>> {
    val out = mutableListOf<Pair<String, List<String>>>()
    val lines = text.lines().map { it.trim() }
    var i = 0
    while (i < lines.size) {
      val lead = lines[i]
      // The app's own note labels ("Key points:", "From the file (p. 3):") aren't lists from the material.
      val ownLabel = lead.startsWith("Key points") || lead.startsWith("From the file")
      if (lead.endsWith(':') && lead.length in 6..90 && !ownLabel) {
        val items = mutableListOf<String>()
        var j = i + 1
        while (j < lines.size && lines[j].isNotBlank() && lines[j].length in 3..140 && !lines[j].endsWith(':')) {
          items += lines[j].trim('•', '-', '*', '·', ' ')
          j++
        }
        if (items.size >= 2) out += lead.trimEnd(':') to items.filter { it.isNotBlank() }
        i = j
      } else i++
    }
    return out
  }

  private fun q(topic: StudyTopic, type: String, text: String, options: List<String>, answer: String, why: String, difficulty: String) = QuizQuestion(
    topicId = topic.id,
    subjectName = topic.subjectName,
    topicTitle = topic.title,
    questionType = type,
    difficulty = difficulty,
    questionText = text,
    optionsJson = if (options.isEmpty()) "" else StudyAi.encodeOptions(options),
    correctAnswer = answer,
    explanation = why
  )

  private fun clip(s: String, max: Int = 140) = if (s.length <= max) s else s.take(max).substringBeforeLast(' ') + "…"

  /**
   * Questions on [topic] from its notes: what a term means, which term fits a description, filling in
   * a key term, true/false swaps and list membership. [pool] is other material from the same course
   * (definitions and terms) for plausible wrong options.
   */
  fun questions(topic: StudyTopic, desired: Int, difficulty: String, pool: List<Definition> = emptyList()): List<QuizQuestion> {
    val text = topic.descriptionNotes
    val defs = definitions(text)
    val allDefs = (defs + pool).distinctBy { it.term.lowercase() }
    val terms = (keyTerms(text) + allDefs.map { it.term }).distinct()
    val out = mutableListOf<QuizQuestion>()

    for (d in defs.shuffled()) {
      val wrongMeanings = allDefs.filter { it.term != d.term }.map { clip(it.meaning) }.distinct().shuffled().take(3)
      if (wrongMeanings.size == 3) {
        out += q(topic, "MULTIPLE_CHOICE", "What is ${d.term}?", (wrongMeanings + clip(d.meaning)).shuffled(), clip(d.meaning),
          "${d.term}: ${d.meaning}.", difficulty)
      }
      val wrongTerms = terms.filter { !it.equals(d.term, true) && !d.meaning.contains(it, true) }.shuffled().take(3)
      if (wrongTerms.size == 3) {
        out += q(topic, "MULTIPLE_CHOICE", "Which term is described here: \"${clip(d.meaning, 180)}\"?", (wrongTerms + d.term).shuffled(), d.term,
          "${d.term}: ${d.meaning}.", difficulty)
      }
      // True/false: the real definition, or one with the meaning of another term.
      val other = allDefs.filter { it.term != d.term }.randomOrNull()
      if (other != null && kotlin.random.Random.nextBoolean()) {
        out += q(topic, "TRUE_FALSE", "True or false: ${d.term} is ${clip(other.meaning, 160)}.", listOf("True", "False"), "False",
          "False. ${d.term} is ${d.meaning}; the statement describes ${other.term}.", difficulty)
      } else {
        out += q(topic, "TRUE_FALSE", "True or false: ${d.term} is ${clip(d.meaning, 160)}.", listOf("True", "False"), "True",
          "True: ${d.term} is ${d.meaning}.", difficulty)
      }
    }

    // Fill in a key term in a sentence that uses it.
    val sentences = text.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim().trim('•', '-', '*', '·').trim() }.filter { it.length in 40..240 }
    for (s in sentences.shuffled()) {
      val term = terms.firstOrNull { t -> Regex("\\b${Regex.escape(t)}\\b").containsMatchIn(s) } ?: continue
      val wrong = terms.filter { it != term && !s.contains(it) && it.length in (term.length / 3)..(term.length * 3 + 4) }.shuffled().take(3)
      if (wrong.size < 3) continue
      val blanked = s.replaceFirst(Regex("\\b${Regex.escape(term)}\\b"), "_____")
      out += q(topic, "MULTIPLE_CHOICE", "Fill in the blank: $blanked", (wrong + term).shuffled(), term, s, difficulty)
    }

    // Which item belongs to a list.
    val lists = lists(text)
    val otherItems = lists.flatMap { it.second }
    for ((lead, items) in lists) {
      val right = items.random()
      val wrong = (otherItems - items.toSet()).shuffled().take(3)
      if (wrong.size == 3) {
        out += q(topic, "MULTIPLE_CHOICE", "Which of these belongs under \"$lead\"?", (wrong + right).map { clip(it, 120) }.shuffled(), clip(right, 120),
          "$lead: ${items.joinToString("; ")}.", difficulty)
      }
    }

    return out.distinctBy { LocalAiEngine.questionKey(it) }.shuffled().take(desired.coerceAtLeast(1))
  }
}
