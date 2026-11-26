package com.example.data.ai

import com.example.platform.Log
import com.example.data.model.ChatMessage
import com.example.data.model.ExtractedConcept
import com.example.data.model.GroundingSource
import com.example.data.model.QuizQuestion
import com.example.data.model.StudyTopic
import com.example.data.remote.ChatResponse
import com.example.data.remote.EvaluationResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Built-in answers used when no language model is available: canned explanations and code
 * examples for common topics, a small maths solver, code templates, and Wikipedia summaries.
 * Much less capable than a real model; the tutor labels these answers as built-in.
 */
object LocalAiEngine {

  private const val TAG = "LocalAiEngine"
  const val BUILT_IN_LABEL = "Built-in answer"
  private const val MAX_WIKI_CACHE_ENTRIES = 64
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .writeTimeout(4, TimeUnit.SECONDS)
    .build()

  private data class CachedWikiExtract(
    val value: Pair<String, String>?,
    val fetchedAtMs: Long
  )

  private val wikiCache = ConcurrentHashMap<String, CachedWikiExtract>()

  private val WS_REGEX = Regex("\\s+")
  private val UNWRAP_LINKED_PREFIX_REGEX = Regex("""(?i)^from\s+my\s+linked\s+chat\s+"[^"]+"\s*,?\s*(?:explain|about|regarding)?\s*""")
  private val UNWRAP_LINKED_SUFFIX_REGEX = Regex("""(?i)\s+from\s+(?:my\s+)?linked\s+chat\s+"[^"]+"\.?$""")
  private val UNWRAP_CMD_PREFIX_REGEX = Regex("(?i)^(please\\s+)?(can you\\s+)?(explain|summarize|tell me about|what is|define|show me)\\s+")
  private val UNWRAP_CMD_SUFFIX_REGEX = Regex("(?i)\\s+(in short|in brief|shortly|briefly|simply|simpler|shorter)$")
  private val SEARCH_LINKED_PREFIX_REGEX = Regex("""^from\s+my\s+linked\s+chat\s+"[^"]+"\s*,?\s*""")
  private val SEARCH_CMD_PREFIX_REGEX = Regex("^(what is the difference between|difference between|compare|what causes|what is|what are|who is|who invented|why is|why does|why do|why are|how does|how do|how is|how to|when does|where is|explain|define|tell me about|describe|show me|formula for|time complexity of|complexity of)\\s+")
  private val SEARCH_CMD_SUFFIX_REGEX = Regex("\\s+(work|works|fail|fails|important|used for)\\??$")
  private val SEARCH_PUNCT_REGEX = Regex("[?!.,\"']+")
  private val EVAL_SPLIT_REGEX = Regex("[\\s,;.]+")
  private val TOPIC_CANDIDATE_REGEX = Regex("""(?i)(?:for|of|about|on|in|summarize)\s+([a-zA-Z0-9_\s+#-]+?)(?:\.|\?|!|\s+answer\s+only|$)""")
  private val HEADING_TOPIC_REGEX = Regex("""^###?\s*[^:\n]+:\s*([^(\n#]+)""")
  private val CODE_MD_STRIP_REGEX = Regex("""[*_`]+""")
  private val TITLE_TOPIC_REGEX = Regex("""###\s*.*?(?:Topic Breakdown|Summary|Walkthrough|Breakdown|Example in Code|Code Example|Algorithmic Guide|Alternative Practical Example|Deep Breakdown|Concise Summary):\s*([^(\n]+)""")
  private val TITLE_MD_STRIP_REGEX = Regex("""[*#_]+""")
  private val EXAMPLE_LINE_PREFIX_REGEX = Regex("^[-*•#>]+\\s*")
  private val FOLLOW_UP_KEYWORDS = listOf("short", "summarize", "summary", "brief", "shorter", "simpler", "simple", "detail", "more", "explain", "in short", "in brief", "tell me more")
  private val STOP_WORDS = setOf("this", "the", "that", "it", "example", "code", "me", "a", "an", "all", "us")

  // -------------------------------------------------------------
  // 1. PRIMARY CHAT DISPATCHER
  // -------------------------------------------------------------
  suspend fun answerQuestion(
    userQuery: String,
    history: List<ChatMessage> = emptyList(),
    subjectContext: String? = null
  ): ChatResponse = withContext(Dispatchers.Default) {
    val cleanQuery = userQuery.trim()
    if (cleanQuery.isBlank()) {
      return@withContext ChatResponse(
        text = "Ask a question about any topic.",
        modelName = BUILT_IN_LABEL
      )
    }

    // Conversational Intent 0: "Another Example in Code / alternative code implementation"
    if (isAnotherCodeExampleIntent(cleanQuery)) {
      val targetTopic = resolveQueryOrPreviousTopic(cleanQuery, history)
      if (!targetTopic.isNullOrBlank()) {
        val article = AcademicKnowledgeBase.search(targetTopic)
        val codeText = generateAlternativeCodeExplanation(targetTopic, article)
        val topics = AcademicPedagogySynthesizer.getRelatedTopics(targetTopic)
        return@withContext ChatResponse(
          text = codeText,
          modelName = BUILT_IN_LABEL,
          relatedTopics = topics
        )
      }
    }

    // Conversational Intent 1: "Example in Code / code example"
    if (isCodeExampleIntent(cleanQuery)) {
      val targetTopic = resolveQueryOrPreviousTopic(cleanQuery, history)
      if (!targetTopic.isNullOrBlank()) {
        val article = AcademicKnowledgeBase.search(targetTopic)
        val codeText = generateCodeExplanation(targetTopic, article)
        val topics = AcademicPedagogySynthesizer.getRelatedTopics(targetTopic)
        return@withContext ChatResponse(
          text = codeText,
          modelName = BUILT_IN_LABEL,
          relatedTopics = topics
        )
      }
    }

    // Conversational Intent 2: "Summarize / concise summary"
    if (isSummarizeIntent(cleanQuery)) {
      val targetTopic = resolveQueryOrPreviousTopic(cleanQuery, history)
      if (!targetTopic.isNullOrBlank()) {
        val article = AcademicKnowledgeBase.search(targetTopic)
        val summaryText = generateConciseSummary(targetTopic, article)
        val topics = AcademicPedagogySynthesizer.getRelatedTopics(targetTopic)
        return@withContext ChatResponse(
          text = summaryText,
          modelName = BUILT_IN_LABEL,
          relatedTopics = topics
        )
      }
    }

    // Conversational Intent 3: "Give another example / different scenario"
    if (isAnotherExampleIntent(cleanQuery)) {
      val targetTopic = resolveQueryOrPreviousTopic(cleanQuery, history)
      if (!targetTopic.isNullOrBlank()) {
        val article = AcademicKnowledgeBase.search(targetTopic)
        val explanationText = generateAlternativeExample(targetTopic, article)
        val topics = AcademicPedagogySynthesizer.getRelatedTopics(targetTopic)
        return@withContext ChatResponse(
          text = explanationText,
          modelName = BUILT_IN_LABEL,
          relatedTopics = topics
        )
      }
    }

    // Conversational Intent 4: "Explain the example / code / formula"
    if (isExplainExampleIntent(cleanQuery)) {
      val targetTopic = resolveQueryOrPreviousTopic(cleanQuery, history)
      if (!targetTopic.isNullOrBlank()) {
        val priorMessages = if (history.isNotEmpty() && history.last().role == "user") history.dropLast(1) else history
        val lastModelMsgText = priorMessages.lastOrNull { it.role == "model" }?.text ?: ""
        val article = AcademicKnowledgeBase.search(targetTopic)
        val explanationText = if (article != null) {
          generateExampleExplanation(targetTopic, article, lastModelMsgText)
        } else {
          generateDynamicExampleExplanation(targetTopic, lastModelMsgText)
        }
        val topics = AcademicPedagogySynthesizer.getRelatedTopics(targetTopic)
        return@withContext ChatResponse(
          text = explanationText,
          modelName = BUILT_IN_LABEL,
          relatedTopics = topics
        )
      }
    }

    val isBriefRequested = cleanQuery.lowercase(Locale.ROOT).let { q ->
      q.contains("short") || q.contains("brief") || q.contains("summarize") || q.contains("summary") || q.contains("simpler") || q.contains("simple")
    }

    val effectiveQuery = resolveEffectiveQuery(cleanQuery, history)
      .let { eq ->
        if (!subjectContext.isNullOrBlank() && eq.split(Regex("\\s+")).size <= 2 && eq.length < 24 && !eq.contains(subjectContext, ignoreCase = true)) {
          eq
        } else {
          eq
        }
      }

    val domainRelated = AcademicPedagogySynthesizer.getRelatedTopics(effectiveQuery)

    // Step 2: Check Math / Calculation intent
    val mathResponse = SymbolicMathSolver.trySolve(effectiveQuery)
    if (mathResponse != null) {
      return@withContext ChatResponse(
        text = mathResponse,
        modelName = BUILT_IN_LABEL,
        relatedTopics = domainRelated
      )
    }

    // Step 3: Check Code Generation / Algorithm intent
    val codeResponse = AlgorithmicCodeSolver.tryGenerate(effectiveQuery)
    if (codeResponse != null) {
      return@withContext ChatResponse(
        text = codeResponse,
        modelName = BUILT_IN_LABEL,
        relatedTopics = domainRelated
      )
    }

    // Step 4: Check Built-In Multi-Level Knowledge Graph (tailored to what was specifically asked)
    val article = AcademicKnowledgeBase.search(effectiveQuery)
    if (article != null) {
      val responseText = if (isBriefRequested) {
        formatShortArticle(article)
      } else {
        AcademicKnowledgeBase.formatArticle(article, targetLevel = cleanQuery)
      }
      return@withContext ChatResponse(
        text = responseText,
        modelName = BUILT_IN_LABEL,
        relatedTopics = domainRelated
      )
    }

    // Step 5: Live Encyclopedic Search (Wikipedia API -> Multi-Tier Academic Synthesis)
    val onlineFact = fetchOnlineEncyclopedicFact(effectiveQuery)
    if (onlineFact != null) {
      val synthesized = AcademicPedagogySynthesizer.synthesizeEncyclopedicResponse(
        query = effectiveQuery,
        factExtract = onlineFact.first,
        sourceUrl = onlineFact.second
      )
      return@withContext ChatResponse(
        text = if (isBriefRequested) "### 📌 Concise Summary: $effectiveQuery\n\n${onlineFact.first}" else synthesized,
        modelName = BUILT_IN_LABEL,
        groundingSources = listOf(GroundingSource(title = "Encyclopedic Reference: ${effectiveQuery.take(30)}", url = onlineFact.second)),
        searchQueries = listOf(effectiveQuery),
        relatedTopics = domainRelated
      )
    }

    // Step 6: Concrete Domain-Specific Synthesizer across All Disciplines
    val concreteResponse = AcademicPedagogySynthesizer.synthesizeGeneralResponse(
      query = effectiveQuery,
      targetLevel = if (isBriefRequested) "beginner" else cleanQuery
    )
    ChatResponse(
      text = if (isBriefRequested) "### 📌 Concise Summary\n\n$concreteResponse" else concreteResponse,
      modelName = BUILT_IN_LABEL,
      relatedTopics = domainRelated
    )
  }

  // -------------------------------------------------------------
  // 3. LIVE ENCYCLOPEDIC RESOLVER (Wikipedia Search -> Extract)
  // -------------------------------------------------------------
  private suspend fun fetchOnlineEncyclopedicFact(rawQuery: String): Pair<String, String>? = withContext(Dispatchers.IO) {
    val searchTerm = cleanQueryForSearch(rawQuery)
    if (searchTerm.length < 2) return@withContext null

    val now = System.currentTimeMillis()
    wikiCache[searchTerm]?.let { cached ->
      if (now - cached.fetchedAtMs < 300_000L) {
        return@withContext cached.value
      }
    }

    var resolved: Pair<String, String>? = null
    try {
      val searchUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" +
        URLEncoder.encode(searchTerm, "UTF-8") + "&format=json&utf8=1&srlimit=3"

      val searchReq = Request.Builder()
        .url(searchUrl)
        .addHeader("User-Agent", "StudyPilotAI/1.0 (academic-tutor)")
        .get()
        .build()

      val bestTitle = httpClient.newCall(searchReq).execute().use { searchResp ->
        if (!searchResp.isSuccessful) return@use null
        val searchBody = searchResp.body?.string() ?: return@use null
        val searchResults = JSONObject(searchBody).optJSONObject("query")?.optJSONArray("search") ?: return@use null
        if (searchResults.length() == 0) return@use null
        searchResults.getJSONObject(0).optString("title", "").takeIf { it.isNotBlank() }
      } ?: return@withContext null

      val summaryUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(bestTitle.replace(" ", "_"), "UTF-8")
      val summaryReq = Request.Builder()
        .url(summaryUrl)
        .addHeader("User-Agent", "StudyPilotAI/1.0 (academic-tutor)")
        .get()
        .build()

      httpClient.newCall(summaryReq).execute().use { summaryResp ->
        if (summaryResp.isSuccessful) {
          val summaryBody = summaryResp.body?.string() ?: return@use
          val sumJson = JSONObject(summaryBody)
          val extract = sumJson.optString("extract", "")
          val pageUrl = sumJson.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page", "")
            ?: "https://en.wikipedia.org/wiki/${URLEncoder.encode(bestTitle, "UTF-8")}"

          if (extract.isNotBlank() && extract.length > 40) {
            resolved = Pair(extract, pageUrl)
          }
        }
      }
    } catch (e: Exception) {
      Log.d(TAG, "Encyclopedic lookup skipped: ${e.message}")
    }

    if (wikiCache.size >= MAX_WIKI_CACHE_ENTRIES) {
      wikiCache.clear()
    }
    wikiCache[searchTerm] = CachedWikiExtract(resolved, now)
    resolved
  }

  private fun cleanQueryForSearch(query: String): String {
    return query.lowercase(Locale.ROOT)
      .replace(SEARCH_LINKED_PREFIX_REGEX, "")
      .replace(SEARCH_CMD_PREFIX_REGEX, "")
      .replace(SEARCH_CMD_SUFFIX_REGEX, "")
      .replace(SEARCH_PUNCT_REGEX, " ")
      .replace(WS_REGEX, " ")
      .trim()
  }

  // -------------------------------------------------------------
  // 4. LOCAL QUIZ GENERATOR
  // -------------------------------------------------------------

  /**
   * Questions taken straight from the topic's material when no model is available: the student's own
   * questions from a tutor conversation ("Q: ... / A: ..."), then "explain" prompts on the notes'
   * key sentences. Short answers, so they are graded on what the material says.
   */
  fun questionsFromNotes(topic: StudyTopic, desiredCount: Int, difficulty: String): List<QuizQuestion> {
    val notes = topic.descriptionNotes
    val pairs = Regex("""Q: (.+?)\nA: (.+?)(?=\n+Q: |$)""", RegexOption.DOT_MATCHES_ALL).findAll(notes)
      .map { it.groupValues[1].trim() to it.groupValues[2].trim() }
      .filter { (q, a) -> q.length >= 8 && a.length >= 40 }
      .toList()
    val fromPairs = pairs.map { (q, a) ->
      val question = q.trimEnd('?', '.', ' ').replaceFirstChar { it.uppercase() } + "?"
      question to firstSentences(a, 2)
    }
    // Then the material's own statements (only the answers, for a conversation).
    val sentences = notes.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")
      .lines()
      .filterNot { it.startsWith("Q: ") }
      .joinToString(" ") { it.removePrefix("A: ") }
      .replace(Regex("[#*_`>]"), "")
      .split(Regex("""(?<=[.!?])\s+"""))
      .map { it.trim() }
      .filter { it.length in 50..300 }
    val fromNotes = sentences.map { sentence ->
      val quote = if (sentence.length > 120) sentence.take(120) + "..." else sentence
      "In your own words, explain this point from ${topic.title}: what does it mean and why does it matter?\n\n\"$quote\"" to sentence
    }
    return (fromPairs + fromNotes).take(desiredCount.coerceAtLeast(1)).map { (question, answer) ->
      QuizQuestion(
        topicId = topic.id,
        subjectName = topic.subjectName,
        topicTitle = topic.title,
        questionType = "SHORT_ANSWER",
        difficulty = difficulty,
        questionText = question,
        correctAnswer = answer,
        explanation = "From your ${if (topic.sourceType == "TUTOR_CHAT") "tutor conversation" else "notes"} on ${topic.title}."
      )
    }
  }

  private fun firstSentences(text: String, count: Int): String {
    val clean = text.replace(Regex("[#*_`>]"), "").replace(Regex("""\s+"""), " ").trim()
    val parts = clean.split(Regex("""(?<=[.!?])\s+"""))
    return parts.take(count).joinToString(" ").take(400)
  }

  /** The three placeholder questions [generateLocalQuizQuestions] writes when nothing better is possible. */
  fun isGenericTemplate(q: QuizQuestion): Boolean {
    val t = q.questionText
    return t.startsWith("Which of the following correctly describes a fundamental property of ") ||
      t.startsWith("True or False: In ") && t.endsWith("fundamental conservation principles and invariants hold across all valid states.") ||
      t.startsWith("What core technique is used to analyze asymptotic performance and efficiency in ")
  }

  fun generateLocalQuizQuestions(
    topic: StudyTopic,
    desiredCount: Int,
    difficulty: String
  ): List<QuizQuestion> {
    val count = desiredCount.coerceIn(1, 10)
    val title = topic.title

    val questions = mutableListOf<QuizQuestion>()
    for (i in 0 until count) {
      when (i % 3) {
        0 -> {
          questions.add(
            QuizQuestion(
              topicId = topic.id,
              subjectName = topic.subjectName,
              topicTitle = title,
              questionType = "MULTIPLE_CHOICE",
              difficulty = difficulty,
              questionText = "Which of the following correctly describes a fundamental property of $title?",
              optionsJson = "Primary system state satisfies governing boundary conditions,Secondary effects dominate primary state variables,System remains indeterminate under steady state,System constraints are non-computable",
              correctAnswer = "Primary system state satisfies governing boundary conditions",
              explanation = "Foundational principles of $title dictate that state transitions satisfy governing physical or mathematical boundary constraints.",
              codeStarter = null
            )
          )
        }
        1 -> {
          questions.add(
            QuizQuestion(
              topicId = topic.id,
              subjectName = topic.subjectName,
              topicTitle = title,
              questionType = "TRUE_FALSE",
              difficulty = difficulty,
              questionText = "True or False: In $title, fundamental conservation principles and invariants hold across all valid states.",
              optionsJson = "True,False",
              correctAnswer = "True",
              explanation = "True. Fundamental conservation laws and system invariants hold true across all defined system states.",
              codeStarter = null
            )
          )
        }
        2 -> {
          questions.add(
            QuizQuestion(
              topicId = topic.id,
              subjectName = topic.subjectName,
              topicTitle = title,
              questionType = "SHORT_ANSWER",
              difficulty = difficulty,
              questionText = "What core technique is used to analyze asymptotic performance and efficiency in $title?",
              optionsJson = "",
              correctAnswer = "Optimization",
              explanation = "Optimization and algorithmic complexity analysis quantify efficiency in $title.",
              codeStarter = null
            )
          )
        }
      }
    }
    return questions
  }

  fun generateLocalQuizQuestionsForConcept(
    concept: ExtractedConcept,
    desiredCount: Int,
    difficulty: String
  ): List<QuizQuestion> {
    val count = desiredCount.coerceIn(1, 10)
    val name = concept.conceptName
    val topic = concept.topic
    val questions = mutableListOf<QuizQuestion>()

    for (i in 0 until count) {
      when (i % 4) {
        0 -> {
          val defSnippet = concept.definition.take(120)
          questions.add(
            QuizQuestion(
              topicId = concept.id,
              subjectName = concept.subjectName,
              topicTitle = name,
              questionType = "MULTIPLE_CHOICE",
              difficulty = difficulty,
              questionText = "Regarding the concept of \"$name\" in $topic, which of the following is accurate?",
              optionsJson = "$defSnippet,It violates foundational invariants of $topic,It represents a deprecated anti-pattern with no practical application,State mutations in $name occur non-deterministically",
              correctAnswer = defSnippet,
              explanation = "Concept Definition: ${concept.definition}",
              codeStarter = null,
              sourceConversationId = concept.sourceChatId,
              sourceConversationTitle = concept.sourceChatTitle
            )
          )
        }
        1 -> {
          val isTrapAvailable = concept.misconceptionsAndCorrections.isNotBlank()
          val qText = if (isTrapAvailable) {
            "True or False regarding $name: A common misconception is that \"${concept.misconceptionsAndCorrections.take(140)}\"."
          } else {
            "True or False: The primary role of $name is to enforce domain constraints and state transitions within $topic."
          }
          questions.add(
            QuizQuestion(
              topicId = concept.id,
              subjectName = concept.subjectName,
              topicTitle = name,
              questionType = "TRUE_FALSE",
              difficulty = difficulty,
              questionText = qText,
              optionsJson = "True,False",
              correctAnswer = "True",
              explanation = if (isTrapAvailable) "True. Exam Trap / Note: ${concept.misconceptionsAndCorrections}" else "True. $name governs domain constraints and state transitions in $topic.",
              codeStarter = null,
              sourceConversationId = concept.sourceChatId,
              sourceConversationTitle = concept.sourceChatTitle
            )
          )
        }
        2 -> {
          val hasCode = concept.formulaOrCode.isNotBlank()
          if (hasCode) {
            questions.add(
              QuizQuestion(
                topicId = concept.id,
                subjectName = concept.subjectName,
                topicTitle = name,
                questionType = "CODING",
                difficulty = difficulty,
                questionText = "Complete the implementation or analyze the operational contract of $name in the snippet below:",
                optionsJson = "",
                correctAnswer = "Enforces structural invariants and correct state mutation",
                explanation = "Technical contract: ${concept.technicalDetails.ifBlank { concept.definition }}",
                codeStarter = concept.formulaOrCode.take(400),
                sourceConversationId = concept.sourceChatId,
                sourceConversationTitle = concept.sourceChatTitle
              )
            )
          } else {
            val problemContext = if (concept.problemsSolved.isNotBlank()) "When solving: ${concept.problemsSolved.take(120)}" else "In system design for $name"
            questions.add(
              QuizQuestion(
                topicId = concept.id,
                subjectName = concept.subjectName,
                topicTitle = name,
                questionType = "SHORT_ANSWER",
                difficulty = difficulty,
                questionText = "$problemContext, what core principle or invariant must be preserved?",
                optionsJson = "",
                correctAnswer = "State encapsulation",
                explanation = "Key Fact: ${concept.keyFacts.ifBlank { concept.technicalDetails.ifBlank { concept.definition } }}",
                codeStarter = null,
                sourceConversationId = concept.sourceChatId,
                sourceConversationTitle = concept.sourceChatTitle
              )
            )
          }
        }
        3 -> {
          questions.add(
            QuizQuestion(
              topicId = concept.id,
              subjectName = concept.subjectName,
              topicTitle = name,
              questionType = "MULTIPLE_CHOICE",
              difficulty = difficulty,
              questionText = "When evaluating technical requirements for $name ($topic), what is the primary consideration?",
              optionsJson = "Satisfying theoretical boundary conditions and invariant specifications,Arbitrary execution without validation,Bypassing encapsulated data checks,Disregarding computational complexity",
              correctAnswer = "Satisfying theoretical boundary conditions and invariant specifications",
              explanation = "System design and academic analysis require satisfying boundary conditions and invariant specifications: ${concept.keyFacts.ifBlank { concept.definition }}",
              codeStarter = null,
              sourceConversationId = concept.sourceChatId,
              sourceConversationTitle = concept.sourceChatTitle
            )
          )
        }
      }
    }
    return questions
  }

  // -------------------------------------------------------------
  // 5. LOCAL SUMMARIZER & EVALUATOR
  // -------------------------------------------------------------
  fun summarizeMaterial(title: String, rawContent: String): String {
    val lines = rawContent.lines().filter { it.isNotBlank() }
    val cleanTitle = title.ifBlank { "Study Material" }

    return buildString {
      append("### 📌 Executive Summary: $cleanTitle\n\n")
      append("**Core Concept**:\n")
      if (lines.isNotEmpty()) {
        append(lines.take(2).joinToString(" ").take(220))
        append("...\n\n")
      } else {
        append("Mastery of $cleanTitle requires understanding foundational definitions, structural principles, and quantitative applications.\n\n")
      }

      append("#### 💡 Key Theoretical Takeaways:\n")
      if (lines.size >= 3) {
        lines.take(5).forEach { l ->
          append("• **Key Point**: ${l.take(120)}\n")
        }
      } else {
        append("• **Foundational Axiom**: Establishes structural bounds and basic system assumptions.\n")
        append("• **Operational Mechanics**: Governs state transitions and algorithmic efficiency.\n")
        append("• **Analytical Evaluation**: Employs verification tests and performance benchmarks.\n")
      }

      append("\n🎯 **Exam Problem Focus**:\n")
      append("Pay close attention to boundary conditions, unit conversions, and step-by-step mathematical proofs during exams.")
    }
  }

  fun evaluateAnswer(question: QuizQuestion, userAnswer: String): EvaluationResult {
    val trimmedUser = userAnswer.trim()
    val expected = question.correctAnswer.trim()

    if (trimmedUser.equals(expected, ignoreCase = true)) {
      return EvaluationResult(
        isCorrect = true,
        feedback = "✅ Excellent! That is exactly correct. ${question.explanation}"
      )
    }

    val userNum = trimmedUser.toDoubleOrNull()
    val expNum = expected.toDoubleOrNull()
    if (userNum != null && expNum != null) {
      val diff = Math.abs(userNum - expNum)
      if (diff < 0.05 || (expNum != 0.0 && diff / Math.abs(expNum) < 0.02)) {
        return EvaluationResult(
          isCorrect = true,
          feedback = "✅ Correct numerical calculation! ($trimmedUser ≈ $expected). ${question.explanation}"
        )
      }
    }

    val expectedKeywords = expected.lowercase(Locale.ROOT).split(EVAL_SPLIT_REGEX).filter { it.length > 3 }
    val userKeywords = trimmedUser.lowercase(Locale.ROOT).split(EVAL_SPLIT_REGEX).filter { it.length > 3 }

    val matchCount = expectedKeywords.count { kw -> userKeywords.any { it.contains(kw) || kw.contains(it) } }
    if (expectedKeywords.isNotEmpty() && matchCount >= (expectedKeywords.size / 2.0).coerceAtLeast(1.0)) {
      return EvaluationResult(
        isCorrect = true,
        feedback = "✅ Conceptually accurate! You captured the core idea. $expected. ${question.explanation}"
      )
    }

    return EvaluationResult(
      isCorrect = false,
      feedback = "❌ Concept check: Expected \"$expected\". ${question.explanation}"
    )
  }

  private fun isShortFollowUpInstruction(query: String): Boolean {
    val q = query.lowercase(Locale.ROOT).trim()
    val words = q.split(WS_REGEX)
    return words.size <= 5 && FOLLOW_UP_KEYWORDS.any { q.contains(it) }
  }

  private fun resolveEffectiveQuery(userQuery: String, history: List<ChatMessage>): String {
    val clean = userQuery.trim()
    if (isShortFollowUpInstruction(clean)) {
      val lastTopic = history.reversed().firstOrNull { msg ->
        msg.role == "user" && run {
          val mq = msg.text.lowercase(Locale.ROOT).trim()
          val mw = mq.split(WS_REGEX)
          mw.size > 5 || !FOLLOW_UP_KEYWORDS.any { mq.contains(it) }
        }
      }?.text?.trim()
      if (!lastTopic.isNullOrBlank()) {
        return lastTopic
      }
    }
    val unwrapped = clean
      .replace(UNWRAP_LINKED_PREFIX_REGEX, "")
      .replace(UNWRAP_LINKED_SUFFIX_REGEX, "")
      .trim()
    return unwrapped
      .replace(UNWRAP_CMD_PREFIX_REGEX, "")
      .replace(UNWRAP_CMD_SUFFIX_REGEX, "")
      .trim()
      .ifBlank { clean }
  }

  fun isAnotherCodeExampleIntent(query: String): Boolean {
    val q = query.lowercase(Locale.ROOT).trim()
    return q.contains("another example in code") ||
      q.contains("another code example") ||
      q.contains("different code example") ||
      q.contains("alternative code example") ||
      q.contains("more code examples") ||
      q.contains("second code example") ||
      q.contains("new code example")
  }

  fun isCodeExampleIntent(query: String): Boolean {
    val q = query.lowercase(Locale.ROOT).trim()
    if (isAnotherCodeExampleIntent(q)) return false
    return q.contains("example in code") ||
      q.contains("code example") ||
      q.contains("show code") ||
      q.contains("code snippet") ||
      q.contains("show an example in code") ||
      q.contains("in code") ||
      q.contains("code implementation") ||
      q.contains("write code") ||
      q.contains("coding example")
  }

  fun isSummarizeIntent(query: String): Boolean {
    val q = query.lowercase(Locale.ROOT).trim()
    return q.contains("summarize") ||
      q.contains("summary") ||
      q.contains("in short") ||
      q.contains("in brief") ||
      q.contains("briefly") ||
      q.contains("quick recap") ||
      q.contains("recap") ||
      q.contains("tldr") ||
      q.contains("tl;dr") ||
      q.contains("key takeaways")
  }

  fun isAnotherExampleIntent(query: String): Boolean {
    val q = query.lowercase(Locale.ROOT).trim()
    if (isAnotherCodeExampleIntent(q)) return false
    return (q.contains("another example") ||
      q.contains("different example") ||
      q.contains("more examples") ||
      q.contains("new example") ||
      q.contains("alternative example")) && !isCodeExampleIntent(q)
  }

  fun isExplainExampleIntent(query: String): Boolean {
    val q = query.lowercase(Locale.ROOT).trim()
    val hasExample = q.contains("example") || q.contains("analogy") || q.contains("scenario")
    val hasExplain = q.contains("explain") || q.contains("break down") || q.contains("walk") || q.contains("step by step") || q.contains("clarify") || q.contains("understand")
    return (hasExample && hasExplain && !isCodeExampleIntent(q) && !isAnotherExampleIntent(q) && !isAnotherCodeExampleIntent(q)) ||
      q == "explain the example" || q == "explain example" || q == "the example" || q == "explain it"
  }

  private fun isConversationalFollowUp(query: String): Boolean {
    val q = query.lowercase(Locale.ROOT).trim()
    val words = q.split(WS_REGEX)
    return isAnotherCodeExampleIntent(q) ||
      isExplainExampleIntent(q) ||
      isAnotherExampleIntent(q) ||
      isCodeExampleIntent(q) ||
      isSummarizeIntent(q) ||
      (words.size <= 5 && (
        q.contains("short") || q.contains("brief") ||
        q.contains("simpler") || q.contains("simple") || q.contains("tell me more")
      ))
  }

  fun resolveQueryOrPreviousTopic(query: String, history: List<ChatMessage>): String? {
    val clean = query.trim()
    val match = TOPIC_CANDIDATE_REGEX.find(clean)
    if (match != null) {
      val candidate = match.groupValues[1].trim()
      if (candidate.isNotBlank() && !STOP_WORDS.contains(candidate.lowercase(Locale.ROOT))) {
        return candidate
      }
    }
    return resolvePreviousTopic(history)
  }

  fun extractTopicFromMessage(text: String): String? {
    for (line in text.lineSequence()) {
      val trimmed = line.trim()
      if (trimmed.startsWith("###")) {
        val match = HEADING_TOPIC_REGEX.find(trimmed)
        if (match != null) {
          val candidate = match.groupValues[1].replace(CODE_MD_STRIP_REGEX, "").trim()
          if (candidate.isNotBlank() && candidate.length <= 40) {
            return candidate
          }
        }
      }
    }
    return null
  }

  private fun resolvePreviousTopic(history: List<ChatMessage>): String? {
    val priorMessages = if (history.isNotEmpty() && history.last().role == "user") history.dropLast(1) else history
    val lastModelMsg = priorMessages.lastOrNull { it.role == "model" }
    if (lastModelMsg != null) {
      val extracted = extractTopicFromMessage(lastModelMsg.text)
      if (!extracted.isNullOrBlank()) return extracted

      val titleMatch = TITLE_TOPIC_REGEX.find(lastModelMsg.text)
      if (titleMatch != null) {
        val topic = titleMatch.groupValues[1].replace(TITLE_MD_STRIP_REGEX, "").trim()
        if (topic.isNotBlank()) return topic
      }
    }
    val lastUserMsg = priorMessages.lastOrNull { msg ->
      msg.role == "user" && !isConversationalFollowUp(msg.text)
    }
    return lastUserMsg?.text?.trim()
  }

  fun generateCodeExplanation(
    topic: String,
    article: MultiLevelAcademicArticle?
  ): String {
    val topicTitle = article?.title?.split(":")?.firstOrNull() ?: topic.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    val cleanTopic = topicTitle.trim()

    val codeOrFormula = article?.formulaOrCode
    val (language, codeSnippet, walkthroughSteps) = when {
      codeOrFormula != null && codeOrFormula.first != "text" -> {
        Triple(
          codeOrFormula.first,
          codeOrFormula.second.trim(),
          listOf(
            "**Data & State Definition**: Encapsulates instance properties and types with strict boundary invariants.",
            "**Operational Flow**: Implements the algorithm or method behavior according to domain rules.",
            "**Output & State Evolution**: Produces deterministic output and mutates local state predictably."
          )
        )
      }
      cleanTopic.contains("Class", ignoreCase = true) || cleanTopic.contains("Object", ignoreCase = true) -> {
        Triple(
          "kotlin",
          """
          // 1. Blueprint (Class Definition)
          class BankAccount(
              val accountNumber: String,
              val accountHolder: String,
              private var balance: Double = 0.0 // Encapsulated private state
          ) {
              // Method with domain validation
              fun deposit(amount: Double) {
                  require(amount > 0) { "Deposit amount must be positive" }
                  balance += amount
                  println("Deposited " + amount + ". New balance: " + balance)
              }

              fun withdraw(amount: Double): Boolean {
                  return if (amount in 0.01..balance) {
                      balance -= amount
                      println("Withdrew " + amount + ". Remaining balance: " + balance)
                      true
                  } else {
                      println("Insufficient funds or invalid withdrawal.")
                      false
                  }
              }

              fun getBalance(): Double = balance
          }

          // 2. Concrete Object Instantiation
          fun main() {
              val aliceAccount = BankAccount("ACC-9021", "Alice Johnson")
              aliceAccount.deposit(250.0)
              aliceAccount.withdraw(75.0)
              println("Alice's Final Balance: " + aliceAccount.getBalance())
          }
          """.trimIndent(),
          listOf(
            "**Class Definition**: `BankAccount` defines the properties (`accountNumber`, `accountHolder`, `balance`) and operational methods.",
            "**Encapsulation**: `balance` is marked `private`, preventing arbitrary modification without passing through validation logic.",
            "**Object Instantiation**: `val aliceAccount = BankAccount(...)` allocates a separate memory block on the heap with its own distinct state."
          )
        )
      }
      cleanTopic.contains("Encapsulation", ignoreCase = true) -> {
        Triple(
          "kotlin",
          """
          class StudentRecord(
              val id: String,
              private var _gpa: Double // Backing property hidden from external mutation
          ) {
              val gpa: Double
                  get() = _gpa

              // Controlled mutation through guarded setter
              fun updateGpa(newGpa: Double) {
                  require(newGpa in 0.0..4.0) { "Invalid GPA: Must be between 0.0 and 4.0" }
                  _gpa = newGpa
              }
          }

          fun main() {
              val record = StudentRecord("STU-101", 3.7)
              record.updateGpa(3.9) // Validated state update
              // record._gpa = 5.0 -> COMPILE ERROR: Cannot access private property
          }
          """.trimIndent(),
          listOf(
            "**Private State Guarding**: `_gpa` is hidden from direct external writes.",
            "**Controlled Mutation**: `updateGpa` enforces data invariants before applying updates.",
            "**Immutability where Possible**: `id` is an immutable `val`, guaranteeing structural identity cannot be altered."
          )
        )
      }
      cleanTopic.contains("Inheritance", ignoreCase = true) -> {
        Triple(
          "kotlin",
          """
          // Base Class (Superclass)
          open class Vehicle(val brand: String, var speed: Int = 0) {
              open fun startEngine() {
                  println(brand + " engine started. Idling at " + speed + " km/h.")
              }
          }

          // Derived Class (Subclass inheriting Vehicle)
          class ElectricCar(brand: String, val batteryCapacityKwh: Double) : Vehicle(brand) {
              override fun startEngine() {
                  println(brand + " EV system activated silently. Battery: " + batteryCapacityKwh + " kWh.")
              }

              fun charge() {
                  println("Charging battery to 100%...")
              }
          }

          fun main() {
              val tesla = ElectricCar("Tesla Model 3", 75.0)
              tesla.startEngine() // Polymorphic overridden execution
              tesla.charge()
          }
          """.trimIndent(),
          listOf(
            "**Inheritance Contract**: `ElectricCar` extends `Vehicle`, inheriting common fields like `brand` and `speed`.",
            "**Polymorphic Override**: `startEngine()` overrides base behavior to provide EV-specific logic.",
            "**Code Reuse**: Common vehicle telemetry code is shared across all child implementations."
          )
        )
      }
      cleanTopic.contains("Polymorphism", ignoreCase = true) -> {
        Triple(
          "kotlin",
          """
          interface PaymentProcessor {
              fun processPayment(amount: Double): Boolean
          }

          class CreditCardProcessor : PaymentProcessor {
              override fun processPayment(amount: Double): Boolean {
                  println("Processing " + amount + " via Visa/MasterCard gateway.")
                  return true
              }
          }

          class CryptoProcessor : PaymentProcessor {
              override fun processPayment(amount: Double): Boolean {
                  println("Broadcasting transaction for " + amount + " on blockchain.")
                  return true
              }
          }

          // Dynamic Dispatch at Runtime
          fun checkout(processor: PaymentProcessor, amount: Double) {
              processor.processPayment(amount) // Resolved dynamically via vtable
          }
          """.trimIndent(),
          listOf(
            "**Common Interface**: `PaymentProcessor` sets a strict contract for all payment mechanisms.",
            "**Dynamic Dispatch**: The `checkout()` function works with any implementation without knowing the concrete class at compile time.",
            "**Extensibility**: Adding a new payment type (e.g. ApplePay) requires zero changes to existing checkout logic."
          )
        )
      }
      cleanTopic.contains("Recursion", ignoreCase = true) -> {
        Triple(
          "kotlin",
          """
          // Pure Recursive Implementation with Base Case
          fun factorial(n: Long): Long {
              // 1. Base Case (Halting Condition)
              if (n <= 1) return 1

              // 2. Recursive Step (n * factorial(n - 1))
              return n * factorial(n - 1)
          }

          // Tail-Recursive Optimized Version (Stack-Safe O(1) space)
          tailrec fun factorialTailRec(n: Long, accumulator: Long = 1): Long {
              if (n <= 1) return accumulator
              return factorialTailRec(n - 1, n * accumulator)
          }
          """.trimIndent(),
          listOf(
            "**Base Case**: Halts recursion when `n <= 1`, preventing stack overflow.",
            "**Call Stack Frames**: Each recursive call pushes a new activation record onto the JVM call stack.",
            "**Tail-Call Optimization**: `tailrec` compiles into a loop, avoiding stack growth entirely."
          )
        )
      }
      cleanTopic.contains("Binary Search", ignoreCase = true) -> {
        Triple(
          "kotlin",
          """
          fun binarySearch(sortedArray: IntArray, target: Int): Int {
              var low = 0
              var high = sortedArray.size - 1

              while (low <= high) {
                  // Avoid integer overflow bug: low + (high - low) / 2
                  val mid = low + (high - low) / 2
                  val midVal = sortedArray[mid]

                  when {
                      midVal == target -> return mid      // Match found at index mid
                      midVal < target  -> low = mid + 1   // Discard left half
                      else             -> high = mid - 1  // Discard right half
                  }
              }
              return -1 // Target element not present
          }
          """.trimIndent(),
          listOf(
            "**Halving Search Space**: In every iteration, the problem size is halved (O(log n) time complexity).",
            "**Overflow Avoidance**: Calculating `low + (high - low) / 2` protects against standard 32-bit integer overflow.",
            "**Zero Extra Allocation**: Operates purely in-place with O(1) auxiliary space."
          )
        )
      }
      else -> {
        val safeName = cleanTopic.replace(Regex("[^a-zA-Z0-9]"), "").ifBlank { "CoreDemo" }
        Triple(
          "kotlin",
          """
          // Concrete implementation of $cleanTopic
          object ${safeName}Demo {
              fun executePipeline(inputData: String): String {
                  println("Initializing execution pipeline for: $cleanTopic")
                  val processed = inputData.trim().uppercase()
                  return "Result of " + processed
              }
          }

          fun main() {
              val result = ${safeName}Demo.executePipeline("academic-sample")
              println(result)
          }
          """.trimIndent(),
          listOf(
            "**Initialization & Scope**: Sets up the operational context and configuration parameters for $cleanTopic.",
            "**Execution Pipeline**: Transforms raw inputs according to the foundational rules of $cleanTopic.",
            "**Verification & Output**: Returns clean, deterministic results validating system correctness."
          )
        )
      }
    }

    return buildString {
      append("### 💻 Example in Code: $cleanTopic\n\n")
      append("> 📌 **Working Implementation & Walkthrough**\n\n")
      append("```$language\n")
      append(codeSnippet)
      append("\n```\n\n")
      append("#### 🔍 Step-by-Step Code Walkthrough\n")
      walkthroughSteps.forEach { step ->
        append("- $step\n")
      }
      append("\n#### 💡 Key Takeaway\n")
      append("- Real-world software systems rely on this exact pattern to guarantee safety, performance, and clear separation of concerns.")
    }
  }

  fun generateAlternativeCodeExplanation(
    topic: String,
    article: MultiLevelAcademicArticle?
  ): String {
    val topicTitle = article?.title?.split(":")?.firstOrNull() ?: topic.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    val cleanTopic = topicTitle.trim()

    val (language, codeSnippet, walkthroughSteps) = when {
      cleanTopic.contains("Class", ignoreCase = true) || cleanTopic.contains("Object", ignoreCase = true) -> {
        Triple(
          "kotlin",
          """
          // Alternative Blueprint: E-Commerce Shopping Cart System
          data class CartItem(val id: String, val name: String, val unitPrice: Double)

          class ShoppingCart(
              val cartId: String,
              val customerEmail: String
          ) {
              // Encapsulated state: private list prevented from external tampering
              private val items = mutableListOf<CartItem>()
              private var discountPercent: Double = 0.0

              fun addItem(item: CartItem) {
                  items.add(item)
                  println("Added " + item.name + " (" + item.unitPrice + ") to cart " + cartId)
              }

              fun applyDiscount(percentage: Double) {
                  require(percentage in 0.0..100.0) { "Discount must be between 0% and 100%" }
                  discountPercent = percentage
              }

              fun calculateTotal(): Double {
                  val subtotal = items.sumOf { it.unitPrice }
                  return subtotal * (1.0 - discountPercent / 100.0)
              }

              fun getItemCount(): Int = items.size
          }

          fun main() {
              val myCart = ShoppingCart("CART-1002", "scholar@imperial.ac.uk")
              myCart.addItem(CartItem("BOOK-1", "Operating Systems Internals", 75.0))
              myCart.addItem(CartItem("USB-2", "Hardware Security Key", 25.0))
              myCart.applyDiscount(15.0) // 15% academic promo
              println("Total payable items: " + myCart.getItemCount())
              println("Final total: " + myCart.calculateTotal())
          }
          """.trimIndent(),
          listOf(
            "**Encapsulated Collection**: `items` is private; external consumers must use `addItem` which enforces order logging.",
            "**Business Validation Invariant**: `applyDiscount` rejects invalid percentages before altering state.",
            "**Stateful Computation**: `calculateTotal` dynamically derives the payable amount from current instance state."
          )
        )
      }
      cleanTopic.contains("Encapsulation", ignoreCase = true) -> {
        Triple(
          "kotlin",
          """
          class DigitalWallet(
              val walletId: String,
              private var balance: Double = 0.0,
              private val securityPin: String = "4821"
          ) {
              fun deposit(amount: Double) {
                  require(amount > 0) { "Deposit amount must be positive" }
                  balance += amount
                  println("Deposited " + amount + ". Updated balance: " + balance)
              }

              fun withdraw(amount: Double, pin: String): Boolean {
                  if (pin != securityPin) {
                      println("Security check failed: Incorrect PIN.")
                      return false
                  }
                  if (amount !in 0.01..balance) {
                      println("Withdrawal rejected: Insufficient funds.")
                      return false
                  }
                  balance -= amount
                  println("Withdrew " + amount + ". New balance: " + balance)
                  return true
              }
          }

          fun main() {
              val wallet = DigitalWallet("WAL-99")
              wallet.deposit(300.0)
              wallet.withdraw(120.0, pin = "4821")
          }
          """.trimIndent(),
          listOf(
            "**Critical State Protection**: `balance` and `securityPin` are strictly hidden from direct heap writes.",
            "**Multi-Factor Guard**: Operations require PIN validation and threshold checking before decrementing state.",
            "**Invariant Defense**: Prevents overdrafts and unauthorized balance adjustments."
          )
        )
      }
      cleanTopic.contains("Inheritance", ignoreCase = true) -> {
        Triple(
          "kotlin",
          """
          abstract class NotificationChannel(val channelType: String) {
              abstract fun dispatch(recipient: String, body: String): Boolean

              fun logTelemetry(recipient: String) {
                  println("[" + channelType + "] Telemetry event: Message sent to " + recipient)
              }
          }

          class EmailService : NotificationChannel("SMTP_EMAIL") {
              override fun dispatch(recipient: String, body: String): Boolean {
                  println("Sending TLS email to " + recipient + " -> " + body)
                  logTelemetry(recipient)
                  return true
              }
          }

          class SmsService : NotificationChannel("CELLULAR_SMS") {
              override fun dispatch(recipient: String, body: String): Boolean {
                  println("Sending GSM SMS to " + recipient + " -> " + body)
                  logTelemetry(recipient)
                  return true
              }
          }

          fun main() {
              val emailChannel: NotificationChannel = EmailService()
              emailChannel.dispatch("student@uni.edu", "Exam results published!")
          }
          """.trimIndent(),
          listOf(
            "**Abstract Contract**: `NotificationChannel` enforces standard dispatch behavior and shared logging across all transports.",
            "**Subclass Specialization**: `EmailService` and `SmsService` implement protocol-specific transport logic.",
            "**Polymorphic Invocation**: Clients interact with the base type reference without coupling to specific hardware."
          )
        )
      }
      cleanTopic.contains("Recursion", ignoreCase = true) -> {
        Triple(
          "kotlin",
          """
          // Alternative Recursive Scenario: Nested Directory Size Calculator
          data class DirectoryNode(
              val name: String,
              val fileSizeBytes: Long = 0,
              val subdirectories: List<DirectoryNode> = emptyList()
          )

          fun calculateDirectoryTreeSize(node: DirectoryNode): Long {
              // Base calculation for current folder's immediate files
              var totalBytes = node.fileSizeBytes

              // Recursive step: Traverse each child subdirectory
              for (child in node.subdirectories) {
                  totalBytes += calculateDirectoryTreeSize(child)
              }
              return totalBytes
          }

          fun main() {
              val doc1 = DirectoryNode("resume.pdf", 450_000)
              val doc2 = DirectoryNode("thesis.tex", 850_000)
              val subFolder = DirectoryNode("Research", 0, listOf(doc1, doc2))
              val root = DirectoryNode("Home", 50_000, listOf(subFolder))

              println("Total Root Disk Usage: " + calculateDirectoryTreeSize(root) + " bytes")
          }
          """.trimIndent(),
          listOf(
            "**Base Termination**: Nodes with empty `subdirectories` return their direct size without further calls.",
            "**Recursive Decomposition**: Tree depth is explored frame-by-frame via the call stack.",
            "**Bottom-Up Accumulation**: Nested totals propagate up to parent directories deterministically."
          )
        )
      }
      cleanTopic.contains("Binary Search", ignoreCase = true) -> {
        Triple(
          "kotlin",
          """
          // Alternative Binary Search: Find First Occurrence (Lower Bound)
          fun findFirstOccurrence(arr: IntArray, target: Int): Int {
              var low = 0
              var high = arr.size - 1
              var resultIndex = -1

              while (low <= high) {
                  val mid = low + (high - low) / 2
                  if (arr[mid] == target) {
                      resultIndex = mid
                      high = mid - 1 // Discard right half to find earlier occurrence on the left
                  } else if (arr[mid] < target) {
                      low = mid + 1
                  } else {
                      high = mid - 1
                  }
              }
              return resultIndex
          }

          fun main() {
              val sortedScores = intArrayOf(50, 65, 75, 75, 75, 88, 92)
              val target = 75
              println("First occurrence of " + target + " at index: " + findFirstOccurrence(sortedScores, target))
          }
          """.trimIndent(),
          listOf(
            "**Lower Bound Biasing**: When `arr[mid] == target`, records index and sets `high = mid - 1` to search leftward.",
            "**Logarithmic Efficiency**: Locates the boundary in O(log n) steps across duplicate values.",
            "**Boundary Stability**: Returns `-1` cleanly if the target value does not exist."
          )
        )
      }
      else -> {
        val safeName = cleanTopic.replace(Regex("[^a-zA-Z0-9]"), "").ifBlank { "AsyncService" }
        Triple(
          "kotlin",
          """
          // Alternative implementation for $cleanTopic
          class ${safeName}Service(val serviceTag: String) {
              private val transactionLog = mutableListOf<String>()

              fun processEvent(eventPayload: String): String {
                  println("Processing alternative event in " + serviceTag + " for: $cleanTopic")
                  val record = "[" + serviceTag + "] " + eventPayload.trim().uppercase()
                  transactionLog.add(record)
                  return record
              }

              fun getLogSummary(): String = "Total logged events: " + transactionLog.size
          }

          fun main() {
              val service = ${safeName}Service("SYS-B")
              val result = service.processEvent("telemetry-packet")
              println(result)
              println(service.getLogSummary())
          }
          """.trimIndent(),
          listOf(
            "**Service Architecture**: Encapsulates event processing and state history for $cleanTopic.",
            "**Execution Isolation**: Isolates event transformations cleanly within private transaction storage.",
            "**Observable Telemetry**: Provides verifiable metrics ensuring correct system state."
          )
        )
      }
    }

    return buildString {
      append("### 💻 Alternative Example in Code: $cleanTopic\n\n")
      append("> 📌 **Alternative Code Implementation & Walkthrough**\n\n")
      append("```$language\n")
      append(codeSnippet)
      append("\n```\n\n")
      append("#### 🔍 Step-by-Step Code Walkthrough\n")
      walkthroughSteps.forEach { step ->
        append("- $step\n")
      }
      append("\n#### 💡 Key Takeaway\n")
      append("- This alternative code implementation illustrates how the principles of $cleanTopic adapt to different practical software architectures.")
    }
  }

  fun generateConciseSummary(
    topic: String,
    article: MultiLevelAcademicArticle? = null
  ): String {
    val topicTitle = article?.title?.split(":")?.firstOrNull() ?: topic.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    val cleanTopic = topicTitle.trim()

    val thesis = article?.thesis ?: "$cleanTopic represents a core foundational principle with deterministic rules and practical real-world applications."
    val bullet1 = article?.elementary ?: "Provides the intuitive foundation, breaking the idea down into relatable real-world terms."
    val bullet2 = article?.highSchool ?: "Governs the primary causal mechanisms, defining the structural rules and invariants."
    val bullet3 = article?.undergraduate ?: "Specifies the technical architecture, mathematical equations, and operational contracts."
    val examTrap = article?.examTrap ?: "Do not confuse the abstract definition with concrete runtime instances or side-effects."

    return buildString {
      append("### 📌 Concise Summary: $cleanTopic\n\n")
      append("> 💡 **Core Thesis**: $thesis\n\n")
      append("#### 🎯 Key Bullet Points\n")
      append("- **Intuitive Concept**: $bullet1\n")
      append("- **Mechanisms & Rules**: $bullet2\n")
      append("- **Technical Contract**: $bullet3\n\n")
      append("#### ⚡ Quick Rule of Thumb & Exam Trap\n")
      append("- $examTrap\n")
    }
  }

  fun generateAlternativeExample(
    topic: String,
    article: MultiLevelAcademicArticle? = null
  ): String {
    val topicTitle = article?.title?.split(":")?.firstOrNull() ?: topic.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    val cleanTopic = topicTitle.trim()
    val domain = AcademicPedagogySynthesizer.detectDomain(topic)

    val (scenarioTitle, scenarioSteps) = when {
      cleanTopic.contains("Class", ignoreCase = true) || cleanTopic.contains("Object", ignoreCase = true) -> {
        Pair(
          "Digital Flight Reservation System",
          listOf(
            "**The Blueprint (Class)**: A `FlightBooking` class specifies passenger name, seat number, flight code, and baggage count, along with methods `checkIn()` and `upgradeSeat()`.",
            "**Independent Instances**: When Passenger A books Seat 14A and Passenger B books Seat 22C, the system instantiates two separate objects: `bookingA` and `bookingB`.",
            "**State Isolation**: Upgrading Passenger A's seat changes only `bookingA.seatNumber`. Passenger B remains completely unaffected.",
            "**Encapsulation Invariant**: The `upgradeSeat()` method verifies that first class has available seats before modifying state, preventing overbooking."
          )
        )
      }
      cleanTopic.contains("Encapsulation", ignoreCase = true) -> {
        Pair(
          "Smart Thermostat Controller",
          listOf(
            "**Private Sensor State**: The actual temperature sensor reading is stored in a private variable that external Wi-Fi clients cannot overwrite directly.",
            "**Public Command Interface**: Users interact via `setTargetTemperature(degrees)` which enforces safety boundaries (e.g. 10°C to 32°C).",
            "**Protection from Inconsistent States**: Even if a network glitch sends an invalid value (e.g. 999°C), the guard inside the setter rejects the command, keeping the physical heater safe."
          )
        )
      }
      cleanTopic.contains("Inheritance", ignoreCase = true) -> {
        Pair(
          "Hospital Employee Management System",
          listOf(
            "**Base Class (`HospitalStaff`)**: Holds common fields such as `staffId`, `name`, and `clockIn()`.",
            "**Specialized Subclasses**: `Doctor` adds `specialty` and `prescribeMedication()`; `Nurse` adds `assignedWard` and `administerCare()`.",
            "**Shared Logic**: Payroll and security access systems process all staff through the common `HospitalStaff` contract without duplicating employee records."
          )
        )
      }
      cleanTopic.contains("Recursion", ignoreCase = true) -> {
        Pair(
          "File Explorer Folder Size Calculator",
          listOf(
            "**The Scenario**: Calculating the total byte size of a root folder that contains subfolders within subfolders.",
            "**The Base Case**: If an item in the folder is a single file, immediately return its byte size.",
            "**The Recursive Step**: If the item is a directory, invoke `calculateFolderSize()` on each item inside it and sum the results.",
            "**Unwinding the Stack**: As nested subdirectories finish calculating, values bubble up to the parent directory until the final total is returned."
          )
        )
      }
      cleanTopic.contains("Binary Search", ignoreCase = true) -> {
        Pair(
          "Printed Dictionary Word Lookup",
          listOf(
            "**The Scenario**: Finding the word 'Quantum' in a 1,000-page printed dictionary.",
            "**Step 1 (Open Middle)**: You flip directly to page 500 ('M'). 'Q' comes after 'M', so you instantly discard pages 1 through 500 without looking at a single word.",
            "**Step 2 (Half Remaining)**: You flip to page 750 ('S'). 'Q' comes before 'S', so you discard pages 750 to 1,000.",
            "**Step 3 (Rapid Convergence)**: Continuing to split the remaining pages finds 'Quantum' in under 10 page flips instead of checking 1,000 pages one by one."
          )
        )
      }
      else -> {
        Pair(
          "Real-World Production Workflow in ${domain.displayName}",
          listOf(
            "**System Setup**: Defines the operational constraints, resource bounds, and actors involved in the $cleanTopic workflow.",
            "**Execution Phase**: Demonstrates how $cleanTopic processes inputs and transitions through state stages.",
            "**Fault Tolerance & Verification**: Illustrates how the system catches anomalies and verifies deterministic completion."
          )
        )
      }
    }

    return buildString {
      append("### 💡 Another Practical Example: $cleanTopic\n\n")
      append("> 📌 **Alternative Scenario**: $scenarioTitle\n\n")
      append("#### 🛠️ Direct Scenario Walkthrough\n")
      scenarioSteps.forEach { step ->
        append("- $step\n")
      }
      append("\n#### 🎯 Practical Insight\n")
      append("- This alternative scenario reinforces that the fundamental principles of $cleanTopic apply across diverse practical domains.")
    }
  }

  fun generateExampleExplanation(
    topic: String,
    article: MultiLevelAcademicArticle?,
    previousMessageText: String
  ): String {
    val topicTitle = article?.title?.split(":")?.firstOrNull() ?: topic.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    val cleanTopic = topicTitle.trim()
    val elementaryAnalogy = article?.elementary ?: extractExampleFromText(previousMessageText)
    val examTrap = article?.examTrap

    return buildString {
      append("### 🔍 Detailed Example Walkthrough: $cleanTopic\n\n")
      append("> 📌 **Examined Example**: \"$elementaryAnalogy\"\n\n")

      append("#### 🪜 Step-by-Step Breakdown\n")
      append("- **Step 1 (Blueprint & Isolation)**: The example establishes a clear boundary between abstract definition and runtime execution. The blueprint holds the rules, while instances operate with isolated state.\n")
      append("- **Step 2 (Dynamic State Allocation)**: When actions in the example trigger, memory space is allocated. Each element retains its distinct values without polluting other elements.\n")
      append("- **Step 3 (Operational Guard Rails)**: Methods enforce domain constraints, guaranteeing data validity and preventing unwanted side effects.\n")
      append("- **Step 4 (Deterministic Outcome)**: Executing the operations produces predictable results, validating the underlying theoretical model.\n\n")

      append("#### 🧠 Conceptual Insight\n")
      append("- This example transforms an abstract concept into an intuitive, visual mental model.\n\n")

      if (!examTrap.isNullOrBlank()) {
        append("#### ⚠️ Common Misconception / Exam Trap\n")
        append("- $examTrap\n")
      }
    }
  }

  fun generateDynamicExampleExplanation(
    topic: String,
    previousMessageText: String
  ): String {
    val cleanTopic = topic.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    val extractedExample = extractExampleFromText(previousMessageText)

    return buildString {
      append("### 🔍 Detailed Example Walkthrough: $cleanTopic\n\n")
      append("> 📌 **Examined Example**:\n")
      append("> \"$extractedExample\"\n\n")

      append("#### 🪜 Step-by-Step Breakdown\n")
      append("- **Phase 1 (Input & Setup)**: Defines the foundational variables, system boundaries, and initial configuration required for the scenario.\n")
      append("- **Phase 2 (Process & Operational Execution)**: Demonstrates how the core mechanisms of $cleanTopic transform inputs into outputs, enforcing structural rules and state transitions.\n")
      append("- **Phase 3 (Validation & Invariants)**: Verifies that boundary conditions, constraints, and edge cases are handled correctly.\n")
      append("- **Phase 4 (Observable Result)**: Connects the concrete result back to the fundamental governing laws of the domain.\n\n")

      append("#### 🧠 Key Takeaway from This Example\n")
      append("- The example illustrates how theoretical rules translate directly into real-world operational workflows.\n")
      append("- Keeping each component's responsibility strictly isolated ensures reliability and ease of reasoning.\n")
    }
  }

  fun extractExampleFromText(text: String): String {
    val lines = text.lines()
    var capturing = false
    val exampleLines = mutableListOf<String>()
    for (line in lines) {
      val trimmed = line.trim()
      if (trimmed.contains("Example", ignoreCase = true) && (trimmed.startsWith("#") || trimmed.startsWith("**"))) {
        capturing = true
        continue
      }
      if (capturing) {
        if (trimmed.startsWith("#") || trimmed.startsWith("---") || trimmed.startsWith("🔗")) {
          break
        }
        if (trimmed.isNotBlank()) {
          exampleLines.add(trimmed.replace(EXAMPLE_LINE_PREFIX_REGEX, ""))
        }
      }
    }
    return if (exampleLines.isNotEmpty()) exampleLines.joinToString("; ").take(300)
    else "The working demonstration scenario presented in the previous step."
  }

  fun formatShortArticle(article: MultiLevelAcademicArticle): String {
    return buildString {
      append("### 📌 Concise Summary: ${article.title}\n\n")
      append("> 💡 **Core Thesis**: ${article.thesis}\n\n")
      append("#### 🌟 Conceptual Intuition\n")
      append("${article.elementary}\n\n")
      append("#### 🏫 Foundational Principles\n")
      append("${article.highSchool}\n\n")
      append("---\n\n")
      append("#### 💡 Key Takeaway & Exam Mastery\n")
      append("Mastery of this topic relies on understanding its core foundational mechanics and boundary conditions.")
    }
  }
}
