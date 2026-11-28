package com.example.data.ai

import com.example.platform.Log
import com.example.data.ai.llm.CloudClient
import com.example.data.ai.llm.CloudProvider
import com.example.data.ai.llm.LlmBackend
import com.example.data.ai.rag.KnowledgePassage
import com.example.data.ai.llm.LlmRequest
import com.example.data.ai.llm.LlmTurn
import com.example.data.ai.llm.OllamaClient
import com.example.data.ai.llm.OnDeviceModels
import com.example.data.ai.llm.PlainMath
import com.example.data.ai.llm.extractJson
import com.example.data.model.ChatMessage
import com.example.data.model.ExtractedConcept
import com.example.data.model.QuizQuestion
import com.example.data.model.StudyTopic
import com.example.data.remote.ChatResponse
import com.example.data.remote.EvaluationResult
import org.json.JSONArray
import org.json.JSONObject

/** Where answers come from. */
enum class AiSource(val label: String, val description: String) {
  AUTO("Auto", "Your cloud chatbot when a key is set, then your PC, then the phone model, then built-in answers"),
  CLOUD("Cloud", "Only the cloud chatbot from your API key (Settings > API keys)"),
  PC("PC", "Only the model on your PC (Ollama)"),
  PHONE("Phone", "Only the model downloaded to this phone"),
  BUILT_IN("Built-in", "Built-in notes, maths and code solvers, and Wikipedia. No model needed")
}

/**
 * What a request is for. Answers to the student (tutor replies, quiz questions) get the best model;
 * background work (summaries, extraction, grading) leaves the cloud's daily budget to them.
 */
enum class AiTask { ANSWER, BACKGROUND }

/** The jobs API keys are picked for, each from the keys best at it. */
enum class AiJob(val label: String) {
  CHAT("Tutor chat"),
  QUIZ("Quizzes"),
  READ("Reading documents")
}

/**
 * Everything the app asks AI for. Runs on Ollama (PC) or LiteRT-LM (phone), and falls back to
 * [LocalAiEngine]'s built-in answers when no model is available.
 */
class StudyAi(
  /** Models running on this device, or null where there are none (desktop, plain JVM tests). */
  val phone: OnDeviceModels? = null
) {

  val pc = OllamaClient()
  val cloud = CloudClient()

  @Volatile var source: AiSource = AiSource.AUTO

  // --- Several API keys, each used for what it does best ---

  /** A saved API key (besides the one picked in Settings, which is [cloud]). */
  data class CloudKey(val provider: CloudProvider, val apiKey: String, val model: String = "", val baseUrl: String = "")

  private val otherKeys = java.util.concurrent.ConcurrentHashMap<CloudProvider, CloudClient>()

  /** The student's other saved keys; a client is kept per provider, so its rate-limit pause holds. */
  fun setOtherKeys(keys: List<CloudKey>) {
    val wanted = keys.filter { it.apiKey.isNotBlank() && it.provider != cloud.provider }.associateBy { it.provider }
    otherKeys.keys.retainAll(wanted.keys)
    wanted.values.forEach { k ->
      val client = otherKeys.getOrPut(k.provider) { CloudClient().also { it.provider = k.provider } }
      client.apiKey = k.apiKey
      client.model = k.model
      client.customBaseUrl = k.baseUrl
    }
  }

  /** The client for [provider]'s key, if one is saved. */
  fun keyFor(provider: CloudProvider): CloudClient? =
    if (cloud.provider == provider && cloud.isConfigured) cloud else otherKeys[provider]?.takeIf { it.isConfigured }

  /** A provider the student picked for a job in Settings; jobs not in here are "Auto". */
  @Volatile var jobProvider: Map<AiJob, CloudProvider> = emptyMap()

  val hasCloudKey: Boolean get() = cloud.isConfigured || otherKeys.values.any { it.isConfigured }

  /**
   * The keys to use for [job], best first. Tutor chat starts with the key picked in Settings;
   * quizzes with the fastest (Groq); reading documents with one that reads PDFs itself (Gemini,
   * then Claude). A provider picked for the job comes before all of them.
   */
  fun cloudsFor(job: AiJob): List<CloudClient> {
    val all = (listOf(cloud) + otherKeys.values).filter { it.isConfigured }.distinctBy { it.provider }
    val auto = when (job) {
      AiJob.CHAT -> all.sortedWith(compareBy({ it !== cloud }, { it.provider.speedRank }))
      AiJob.QUIZ -> all.sortedBy { it.provider.speedRank }
      AiJob.READ -> all.sortedWith(compareBy({ !it.provider.readsPdf }, { it.provider.speedRank }))
    }
    val picked = jobProvider[job] ?: return auto
    return auto.sortedBy { it.provider != picked }
  }

  /** Which job each saved key does first, for Settings ("Groq: Quizzes"). */
  fun jobsByKey(): Map<CloudProvider, List<AiJob>> =
    AiJob.entries.mapNotNull { job -> cloudsFor(job).firstOrNull()?.let { it.provider to job } }
      .groupBy({ it.first }, { it.second })

  /** Models to try for [task], best first; the next one is used when one fails or hits its limit. */
  suspend fun backends(task: AiTask = AiTask.ANSWER): List<LlmBackend> = when (source) {
    AiSource.AUTO -> cloudsFor(AiJob.CHAT).mapNotNull { it.backendOrNull(background = task == AiTask.BACKGROUND) } + listOfNotNull(pc.backendOrNull(), phone?.backendOrNull())
    AiSource.CLOUD -> cloudsFor(AiJob.CHAT).mapNotNull { it.backendOrNull() }
    AiSource.PC -> listOfNotNull(pc.backendOrNull())
    AiSource.PHONE -> listOfNotNull(phone?.backendOrNull())
    AiSource.BUILT_IN -> emptyList()
  }

  /** The model that will answer next, or null for built-in answers. */
  suspend fun backend(task: AiTask = AiTask.ANSWER): LlmBackend? = backends(task).firstOrNull()

  /**
   * Models for quiz work: the cloud chatbot first whenever a key is set (it's fast and writes the best
   * questions), whatever this device's AI setting, then the usual order.
   */
  suspend fun quizBackends(job: AiJob = AiJob.QUIZ): List<LlmBackend> =
    if (!quizCloudFirst) backends(AiTask.ANSWER)
    else (cloudsFor(job).mapNotNull { it.backendOrNull() } + backends(AiTask.ANSWER)).distinctBy { it.label }

  /** Quizzes and document reading use the API key's model first; off uses the AI setting's order. */
  @Volatile var quizCloudFirst: Boolean = true

  /** Runs a one-off prompt, trying each model in turn. Null when none is available or all fail. */
  suspend fun prompt(
    prompt: String,
    system: String = DEFAULT_SYSTEM_PROMPT,
    temperature: Double = 0.5,
    json: Boolean = false,
    task: AiTask = AiTask.ANSWER,
    /** Quiz work: the cloud key first, see [quizBackends]. */
    preferCloud: Boolean = false,
    /** Room for the answer; quiz work writes long JSON, which is useless when cut off. */
    maxTokens: Int = if (preferCloud) 4096 else 1024,
    /** With [preferCloud]: which job's keys go first. */
    job: AiJob = AiJob.QUIZ
  ): String? {
    val chain = if (preferCloud) quizBackends(job) else backends(task)
    for ((index, backend) in chain.withIndex()) {
      // Quiz work runs in the background, so a free plan's "slow down" is waited out (up to three
      // times) rather than handing the work to a weaker model.
      var tries = if (preferCloud && backend.label.startsWith("Cloud")) 4 else 1
      while (tries-- > 0) try {
        backend.generate(LlmRequest(system, listOf(LlmTurn(true, prompt)), temperature, json, maxOutputTokens = maxTokens)).takeIf { it.isNotBlank() }?.let { return it }
        break
      } catch (e: Exception) {
        val pause = (backend as? CloudClient.Backend)?.client?.pausedForMs() ?: 0L
        // Another saved key is free: use it rather than waiting.
        val anotherKey = chain.drop(index + 1).any { it is CloudClient.Backend && it.client.pausedForMs() == 0L }
        if (tries > 0 && !anotherKey && backend.label.startsWith("Cloud") && pause in 1..90_000) {
          Log.w(TAG, "${backend.label} asked to wait ${pause / 1000}s; waiting")
          kotlinx.coroutines.delay(pause + 500)
          continue
        }
        tries = 0
        // Some providers' strict JSON mode rejects an answer it would give as plain text; the JSON is
        // pulled out of plain replies anyway, so ask once more without it.
        if (json && Regex("(?i)validate json|json_validate|failed_generation").containsMatchIn(e.message.orEmpty())) {
          val plain = runCatching { backend.generate(LlmRequest(system, listOf(LlmTurn(true, prompt)), temperature, false, maxOutputTokens = maxTokens)) }.getOrNull()
          if (!plain.isNullOrBlank()) return plain
        }
        Log.w(TAG, "${backend.label} failed", e)
        lastError = "${backend.label}: ${e.message ?: e.javaClass.simpleName}"
      }
    }
    return null
  }

  /** Why the last prompt got no answer from a model ("Cloud · llama…: 429 …"), for status lines. */
  @Volatile var lastError: String? = null

  // --- Tutor chat ---

  suspend fun chat(
    history: List<ChatMessage>,
    systemInstruction: String = DEFAULT_SYSTEM_PROMPT,
    subjectContext: String? = null,
    onPartial: ((String) -> Unit)? = null,
    /** Passages from the student's own chats and notes that match the question (RAG). */
    knowledge: List<KnowledgePassage> = emptyList(),
    /** "College notes": answer only from [knowledge], citing it. */
    strict: Boolean = false
  ): ChatResponse {
    val lastUserMsg = history.lastOrNull { it.role == "user" }?.text.orEmpty()

    suspend fun builtIn(note: String? = null): ChatResponse {
      val local = LocalAiEngine.answerQuestion(userQuery = lastUserMsg, history = history, subjectContext = subjectContext)
      return if (note == null) local else local.copy(text = "_${note}_\n\n${local.text}")
    }

    val chain = backends(AiTask.ANSWER)
    if (chain.isEmpty()) {
      val note = when (source) {
        AiSource.CLOUD -> if (hasCloudKey) "The cloud model isn't available right now (free limit), so this is a built-in answer." else "No cloud API key is set, so this is a built-in answer."
        AiSource.PC -> "Your PC isn't reachable, so this is a built-in answer."
        AiSource.PHONE -> "No phone model is downloaded yet, so this is a built-in answer."
        else -> null
      }
      return builtIn(note)
    }

    val system = "$systemInstruction $PLAIN_MATH_RULE"
    // The student's matching material goes right after their question, where models heed it most.
    val turns = withLengthHint(toTurns(history)).let { t ->
      val last = t.lastOrNull()
      if (knowledge.isEmpty() || last == null || !last.fromUser) t
      else t.dropLast(1) + last.copy(text = last.text + if (strict) KnowledgePassage.strictInstruction(knowledge) else KnowledgePassage.groundingInstruction(knowledge))
    }
    var lastError: String? = null
    for (backend in chain) {
      val text = try {
        backend.generate(
          LlmRequest(system, turns, temperature = 0.6, maxOutputTokens = 2048),
          onPartial?.let { show -> { partial: String -> show(PlainMath.convert(partial)) } }
        ).let(PlainMath::convert)
      } catch (e: Exception) {
        Log.w(TAG, "${backend.label} failed", e)
        lastError = "${backend.label} didn't answer (${e.message})"
        continue
      }
      if (text.isBlank()) {
        lastError = "${backend.label} returned an empty answer"
        continue
      }
      val (cleanText, topics) = AcademicPedagogySynthesizer.extractAndCleanRelatedTopics(rawContent = text, fallbackQuery = lastUserMsg)
      return ChatResponse(
        text = cleanText,
        modelName = backend.label,
        relatedTopics = topics,
        groundingSources = KnowledgePassage.citedSources(cleanText, knowledge)
      )
    }
    return builtIn("$lastError, so this is a built-in answer.")
  }

  /**
   * Answers are full explanations by default. When the latest message asks for brevity, tell the
   * model explicitly on that message only, so the next question gets a full answer again.
   */
  fun withLengthHint(turns: List<LlmTurn>): List<LlmTurn> {
    val last = turns.lastOrNull() ?: return turns
    if (!last.fromUser || !wantsBrief(last.text)) return turns
    return turns.dropLast(1) + last.copy(text = last.text + "\n\n(Answer briefly: 2 to 4 sentences or a few bullet points, no headings.)")
  }

  fun wantsBrief(text: String): Boolean = BRIEF.containsMatchIn(text.lowercase())

  /**
   * Models expect the conversation to start with the user and alternate. Drops greetings and
   * error bubbles and merges consecutive messages from the same side.
   */
  fun toTurns(history: List<ChatMessage>, maxMessages: Int = 12): List<LlmTurn> {
    val turns = mutableListOf<LlmTurn>()
    for (msg in history.filter { !it.isError && it.text.isNotBlank() }.takeLast(maxMessages)) {
      val fromUser = msg.role != "model" && msg.role != "assistant"
      if (turns.isEmpty() && !fromUser) continue
      val last = turns.lastOrNull()
      if (last != null && last.fromUser == fromUser) turns[turns.lastIndex] = last.copy(text = last.text + "\n\n" + msg.text)
      else turns.add(LlmTurn(fromUser, msg.text))
    }
    return turns
  }

  // --- Summaries ---

  suspend fun summarizeMaterial(title: String, rawContent: String): String {
    val prompt = """
      Summarize these study notes on "$title" in under 200 words, about what the notes actually say.
      Use exactly this Markdown shape and no other headings, numbering or labels:

      **In short:** two or three plain sentences with the core idea.

      **Key points**
      - three to five short points, one line each

      **Likely exam question:** one question.

      Notes:
      $rawContent
    """.trimIndent()
    return prompt(prompt, temperature = 0.4, task = AiTask.BACKGROUND) ?: LocalAiEngine.summarizeMaterial(title, rawContent)
  }

  // --- Quiz generation ---

  private fun questionFormatSpec(difficulty: String, types: Set<String>): String {
    if (types == setOf(MCQ)) {
      return """
        Reply with JSON only, in the form {"questions": [ ... ]}. Every question is multiple choice. Each has:
        - "questionType": "MULTIPLE_CHOICE"
        - "difficulty": "$difficulty"
        - "questionText": the question
        - "optionsJson": a JSON array of exactly 4 option texts (the full text, not letters). One is correct;
          the other three are plausible to someone who half-knows the topic and about as long. No "all of the above".
        - "correctAnswer": the full text of the correct option, exactly as written in optionsJson
        - "explanation": why it is correct and the others aren't
        Write maths as plain text (λ, x², ≤), not LaTeX.
      """.trimIndent()
    }
    val allowed = types.ifEmpty { setOf(MCQ, "TRUE_FALSE", "SHORT_ANSWER", "CODING") }
    return """
      Reply with JSON only, in the form {"questions": [ ... ]}. Each question has:
      - "questionType": one of ${allowed.joinToString { "\"$it\"" }}
      - "difficulty": "$difficulty"
      - "questionText": string
      - "optionsJson": for MULTIPLE_CHOICE a JSON array of the 4 option texts (the full text, not letters);
        ["True", "False"] for TRUE_FALSE; otherwise []
      - "correctAnswer": for choice questions, the full text of the correct option; otherwise a short model answer
      Write maths as plain text (λ, x², ≤), not LaTeX.
      - "explanation": why the answer is correct
      - "codeStarter": starter code for CODING, otherwise null
    """.trimIndent()
  }

  /** Tells the model what the student already has, and points it at a different angle each time. */
  private fun freshnessNote(avoid: List<String>): String = buildString {
    if (avoid.isNotEmpty()) {
      append("The student has already been asked these; write new questions on other facts or from other angles, not rewordings:\n")
      avoid.take(25).forEach { append("- ").append(it.replace('\n', ' ').take(140)).append('\n') }
    }
    append("This time focus on ${QUESTION_ANGLES.shuffled().take(2).joinToString(" and ")}.")
  }

  /**
   * Free cloud plans allow only so much text a minute; after a "slow down" the key pauses for a
   * little while. Long quiz work waits that out (up to [maxMs]) instead of dropping to a weaker model.
   */
  suspend fun waitForCloud(maxMs: Long = 90_000) {
    val keys = cloudsFor(AiJob.QUIZ)
    if (keys.isEmpty() || keys.any { it.pausedForMs() == 0L }) return
    val wait = keys.minOf { it.pausedForMs() }
    if (wait in 1..maxMs) kotlinx.coroutines.delay(wait + 500)
  }

  /** The complete {...} objects with a "title" in a JSON reply that may be cut off at the end. */
  internal fun completeObjects(raw: String): List<JSONObject> {
    val start = raw.indexOf('[').takeIf { it >= 0 } ?: return emptyList()
    val out = mutableListOf<JSONObject>()
    var depth = 0
    var from = -1
    var inString = false
    var escaped = false
    for (i in start + 1 until raw.length) {
      val c = raw[i]
      if (inString) {
        if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
        continue
      }
      when (c) {
        '"' -> inString = true
        '{' -> { if (depth == 0) from = i; depth++ }
        '}' -> {
          depth--
          if (depth == 0 && from >= 0) {
            runCatching { JSONObject(raw.substring(from, i + 1)) }.getOrNull()?.takeIf { it.has("title") }?.let { out += it }
            from = -1
          }
        }
      }
    }
    return out
  }

  private val FILE_SYSTEM_PROMPT = "You turn lecture material into clear, accurate study notes. You only use what the material says."

  /** A topic a model found in a course file. */
  data class FileTopic(val title: String, val summary: String, val keyPoints: List<String>, val pages: List<Int>)

  /**
   * The study topics a part of a lecture file teaches, with notes for each, found by a model (cloud
   * first). Course administration is left out. Null when no model answered; then the built-in
   * [LectureQuiz.topicsFromPages] is used.
   */
  suspend fun topicsInFile(
    fileTitle: String,
    subject: String,
    section: String
  ): List<FileTopic>? {
    val request = """
      This is part of the lecture file "$fileTitle" from the course "$subject". Page numbers are marked [Page n].

      $section

      List the subject-matter topics this material actually teaches: concepts, methods, algorithms,
      protocols, definitions, formulas, results. Leave out course administration (schedules, grading,
      exams and deadlines, contact details, attendance, assignment logistics, announcements, welcome text).
      For each topic give:
      - "title": a short name (2-6 words)
      - "summary": 3-6 sentences explaining it as study notes, in plain language, from this material
      - "keyPoints": 3-8 short facts, definitions or formulas stated in the material
      - "pages": the page numbers where it is covered
      Reply only with JSON: {"topics": [{"title": "...", "summary": "...", "keyPoints": ["..."], "pages": [1]}]}
      If it teaches nothing (only administration), reply {"topics": []}.
    """.trimIndent()
    lastError = null
    if (quizBackends(AiJob.READ).isEmpty()) lastError = "no AI model is set up (add a Groq, Gemini or other key in Settings > AI)"
    // Plain text: some providers' strict JSON mode rejects long answers; the JSON is read from the reply.
    val raw = prompt(request, FILE_SYSTEM_PROMPT, 0.2, json = false, preferCloud = true, job = AiJob.READ) ?: return null
    return fileTopics(raw)
  }

  /** Whether a saved key can read this PDF itself (Gemini or Claude). */
  fun canReadPdf(bytes: Int): Boolean = cloudsFor(AiJob.READ).any { it.canReadPdf(bytes) }

  /**
   * The study topics a whole PDF teaches, read by Gemini or Claude from the file itself (layout,
   * tables, formulas and scanned pages, not just the text). Null when no key could read it; then
   * the text is read part by part with [topicsInFile].
   */
  suspend fun topicsInPdf(fileTitle: String, subject: String, pdf: ByteArray): List<FileTopic>? {
    val request = """
      This PDF is the course file "$fileTitle" from the course "$subject". Read all of it, pictures,
      tables and formulas included. Running headers, footers and page numbers are not topics.

      List the subject-matter topics it actually teaches or practises: concepts, methods, algorithms,
      data structures, proofs, definitions, formulas, results. For an exercise sheet, a topic is what
      the exercises practise. Leave out course administration (schedules, grading, exams and deadlines,
      contact details, attendance, assignment logistics, announcements, welcome text).
      Give between 3 and 25 topics, in the order of the file. For each topic give:
      - "title": a short name (2-6 words)
      - "summary": 4-8 sentences explaining it as study notes, in plain language, from this file
      - "keyPoints": 4-10 short facts, definitions, formulas (in plain text, e.g. O(n log n)) or worked results from the file
      - "pages": the PDF page numbers where it is covered (the first page is 1)
      Reply only with JSON: {"topics": [{"title": "...", "summary": "...", "keyPoints": ["..."], "pages": [1]}]}
      If it teaches nothing (only administration), reply {"topics": []}.
    """.trimIndent()
    for (key in cloudsFor(AiJob.READ).filter { it.canReadPdf(pdf.size) }) {
      val raw = runCatching { key.readPdf(pdf, fileTitle, FILE_SYSTEM_PROMPT, request) }
        .onFailure { lastError = "${key.provider.label}: ${it.message}"; Log.w(TAG, "${key.provider.label} couldn't read $fileTitle", it) }
        .getOrNull()?.takeIf { it.isNotBlank() } ?: continue
      fileTopics(raw)?.let { return it }
    }
    return null
  }

  /** The topics in a model's {"topics": [...]} answer (also when it was cut off), or null. */
  private fun fileTopics(raw: String): List<FileTopic>? {
    return runCatching {
      // A reply cut off mid-way still holds the topics before the cut: keep each complete one.
      val objects = runCatching { JSONObject(extractJson(raw) ?: raw).optJSONArray("topics")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } }
        .getOrNull() ?: completeObjects(raw)
      if (objects.isEmpty() && !raw.contains("\"topics\"")) error("no topics in the reply")
      objects.mapNotNull { t ->
        val title = t.optString("title").trim().take(80)
        val summary = PlainMath.convert(t.optString("summary")).trim()
        if (title.isBlank() || summary.length < 20 || LectureQuiz.isAdministrative(title, summary)) return@mapNotNull null
        val points = t.optJSONArray("keyPoints")?.let { a -> (0 until a.length()).map { PlainMath.convert(a.optString(it)).trim() }.filter { it.isNotBlank() } }.orEmpty()
        val pages = t.optJSONArray("pages")?.let { a -> (0 until a.length()).mapNotNull { a.optInt(it, -1).takeIf { n -> n > 0 } } }.orEmpty()
        FileTopic(title, summary, points, pages)
      }
    }.onFailure {
      lastError = "couldn't read the model's answer (${it.message})"
      Log.w(TAG, "Unreadable topics answer: ${raw.take(300)}", it)
    }.getOrNull()
  }

  /**
   * About 3,000 characters of [notes] to write questions from. Long material (a whole lecture file)
   * gives a different stretch each time, starting at a paragraph, so quizzes cover all of it.
   */
  private fun notesExcerpt(notes: String): String {
    if (notes.length <= 3000) return notes
    val start = kotlin.random.Random.nextInt(0, notes.length - 2500)
    val from = notes.lastIndexOf('\n', start).let { if (it < 0) 0 else it + 1 }
    return notes.substring(from, minOf(notes.length, from + 3000))
  }

  /**
   * New questions on [topic] from the model, or empty when no model answered. Only [types] are kept;
   * [avoid] lists questions the student already has.
   */
  suspend fun modelQuestionsForTopic(
    topic: StudyTopic,
    count: Int,
    difficulty: String,
    types: Set<String> = setOf(MCQ),
    avoid: List<String> = emptyList()
  ): List<QuizQuestion> {
    val prompt = """
      Write exactly $count revision questions on "${topic.title}" (${topic.subjectName}), difficulty $difficulty.
      Base them only on this material (the student's notes, or a conversation they had while studying):
      "${notesExcerpt(topic.descriptionNotes)}"

      ${freshnessNote(avoid)}

      ${questionFormatSpec(difficulty, types)}
    """.trimIndent()
    val raw = prompt(prompt, QUIZ_SYSTEM_PROMPT, if (avoid.isEmpty()) 0.4 else 0.7, json = true, preferCloud = true) ?: return emptyList()
    return parseQuestions(raw, difficulty) { q -> q.copy(topicId = topic.id, subjectName = topic.subjectName, topicTitle = topic.title) }
      .filter { types.isEmpty() || it.questionType in types }
      .take(count)
  }

  /** Like [modelQuestionsForTopic], from an extracted concept's details. */
  suspend fun modelQuestionsForConcept(
    concept: ExtractedConcept,
    count: Int,
    difficulty: String,
    types: Set<String> = setOf(MCQ),
    avoid: List<String> = emptyList()
  ): List<QuizQuestion> {
    val context = buildString {
      append("Concept: ${concept.conceptName} (topic: ${concept.topic}, subtopic: ${concept.subtopic})\n")
      append("Definition: ${concept.definition}\n")
      if (concept.technicalDetails.isNotBlank()) append("Details: ${concept.technicalDetails}\n")
      if (concept.formulaOrCode.isNotBlank()) append("Formula / code:\n${concept.formulaOrCode}\n")
      if (concept.problemsSolved.isNotBlank()) append("Problems worked through: ${concept.problemsSolved}\n")
      if (concept.misconceptionsAndCorrections.isNotBlank()) append("Common mistakes: ${concept.misconceptionsAndCorrections}\n")
      if (concept.keyFacts.isNotBlank()) append("Key facts: ${concept.keyFacts}\n")
    }
    val prompt = """
      Write exactly $count exam questions that test understanding and application of this concept,
      not recall of its name.

      $context
      ${freshnessNote(avoid)}

      ${questionFormatSpec(difficulty, types)}
    """.trimIndent()
    val raw = prompt(prompt, QUIZ_SYSTEM_PROMPT, if (avoid.isEmpty()) 0.4 else 0.7, json = true, preferCloud = true) ?: return emptyList()
    return parseQuestions(raw, difficulty) { q ->
      q.copy(
        topicId = concept.id,
        subjectName = concept.subjectName,
        topicTitle = concept.conceptName,
        sourceConversationId = concept.sourceChatId,
        sourceConversationTitle = concept.sourceChatTitle
      )
    }.filter { types.isEmpty() || it.questionType in types }.take(count)
  }

  suspend fun generateQuestionsForTopic(topic: StudyTopic, desiredCount: Int = 3, difficulty: String = "MEDIUM"): List<QuizQuestion> =
    modelQuestionsForTopic(topic, desiredCount, difficulty, types = emptySet()).takeIf { it.isNotEmpty() }
      ?: LocalAiEngine.questionsFromNotes(topic, desiredCount, difficulty).takeIf { it.isNotEmpty() }
      ?: LocalAiEngine.generateLocalQuizQuestions(topic, desiredCount, difficulty)

  suspend fun generateQuestionsForConcept(concept: ExtractedConcept, desiredCount: Int = 2, difficulty: String = "MEDIUM"): List<QuizQuestion> =
    modelQuestionsForConcept(concept, desiredCount, difficulty, types = emptySet()).takeIf { it.isNotEmpty() }
      ?: LocalAiEngine.generateLocalQuizQuestionsForConcept(concept, desiredCount, difficulty)

  fun parseQuestions(raw: String, difficulty: String, attach: (QuizQuestion) -> QuizQuestion): List<QuizQuestion> = try {
    val json = extractJson(raw) ?: "[]"
    // Replies come as {"questions": [...]}, a bare array, or (from Ollama's JSON mode, which only
    // allows objects) a single question object. Only a list of objects is a list of questions;
    // "optionsJson" is a list too, but of strings.
    val array = if (json.startsWith("{")) {
      JSONObject(json).let { obj ->
        obj.keys().asSequence().mapNotNull { obj.optJSONArray(it) }.firstOrNull { it.length() > 0 && it.optJSONObject(0) != null }
          ?: JSONArray().put(obj)
      }
    } else JSONArray(json)
    (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { toQuestion(it, difficulty) }?.let(attach) }
  } catch (e: Exception) {
    Log.w(TAG, "Could not parse generated questions", e)
    emptyList()
  }

  private fun toQuestion(obj: JSONObject, difficulty: String): QuizQuestion? {
    var text = PlainMath.convert(obj.optString("questionText")).trim()
    var answer = PlainMath.convert(obj.optString("correctAnswer")).trim()
    if (text.isBlank() || answer.isBlank()) return null
    var type = obj.optString("questionType", "SHORT_ANSWER").uppercase()
      .takeIf { it in setOf("MULTIPLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER", "CODING") } ?: "SHORT_ANSWER"
    var options = when (val rawOptions = obj.opt("optionsJson")) {
      is JSONArray -> (0 until rawOptions.length()).map { rawOptions.optString(it) }
      is String -> rawOptions.split(if ('|' in rawOptions) "|" else ",")
      else -> emptyList()
    }.map { PlainMath.convert(it).trim() }.filter { it.isNotBlank() }

    // Some models list "A) ... B) ..." inside the question and give only letters as options.
    if (options.isNotEmpty() && options.all { it.length == 1 && it[0].isLetter() }) {
      val listed = OPTION_LINE.findAll(text).associate { it.groupValues[1].uppercase() to it.groupValues[2].trim() }
      if (listed.size >= 2) {
        answer = listed[answer.trim().take(1).uppercase()] ?: answer
        options = listed.values.toList()
        text = OPTION_LINE.replace(text, "").trim()
      }
    }

    // A choice question whose answer isn't one of its options can't be graded; keep it as short answer.
    if (type == "MULTIPLE_CHOICE" && options.none { it.equals(answer, ignoreCase = true) }) type = "SHORT_ANSWER"
    if (type == "SHORT_ANSWER" || type == "CODING") options = emptyList()
    // Models tend to put the right answer first; mix the choices once, when the question is made.
    if (type == "MULTIPLE_CHOICE") options = options.shuffled()
    return QuizQuestion(
      topicId = 0L,
      subjectName = "",
      topicTitle = "",
      questionType = type,
      difficulty = obj.optString("difficulty", difficulty),
      questionText = text,
      // "|" when an option contains a comma; the quiz screen accepts either separator.
      optionsJson = encodeOptions(options),
      correctAnswer = answer,
      explanation = PlainMath.convert(obj.optString("explanation")),
      codeStarter = obj.optString("codeStarter").takeIf { !obj.isNull("codeStarter") && it.isNotBlank() }
    )
  }

  // --- Grading ---

  suspend fun evaluateAnswer(question: QuizQuestion, userAnswer: String): EvaluationResult {
    // Choice questions have one right answer; grade them on the device without a model.
    if (question.questionType == "MULTIPLE_CHOICE" || question.questionType == "TRUE_FALSE" || userAnswer.isBlank()) {
      return LocalAiEngine.evaluateAnswer(question, userAnswer)
    }
    val prompt = """
      Question: "${question.questionText}"
      Model answer: "${question.correctAnswer}"
      Context: "${question.explanation}"
      Student answer: "$userAnswer"

      Is the student's answer conceptually correct? Reply with JSON only: {"isCorrect": true or false, "feedback": "1-2 sentences to the student, addressed as you"}
    """.trimIndent()
    prompt(prompt, "You grade student answers fairly and briefly.", 0.1, json = true, task = AiTask.BACKGROUND)?.let { raw ->
      try {
        val obj = JSONObject(extractJson(raw) ?: raw)
        if (obj.has("isCorrect")) return EvaluationResult(obj.getBoolean("isCorrect"), obj.optString("feedback", question.explanation))
      } catch (e: Exception) {
        Log.w(TAG, "Could not parse grading", e)
      }
    }
    return LocalAiEngine.evaluateAnswer(question, userAnswer)
  }

  companion object {
    private const val TAG = "StudyAi"
    const val MCQ = "MULTIPLE_CHOICE"

    /** Different things to ask about, so repeated quizzes on one topic don't converge on the same questions. */
    private val QUESTION_ANGLES = listOf(
      "definitions and key terms",
      "how it works step by step",
      "applying it to a new example",
      "comparisons and differences",
      "common mistakes and misconceptions",
      "trade-offs, cost or complexity",
      "why it matters and when to use it",
      "predicting what happens in a specific case"
    )

    /** Options are stored joined by "," (or "|" when an option has a comma); the quiz screen reads either. */
    fun encodeOptions(options: List<String>): String = options.joinToString(if (options.any { ',' in it }) "|" else ",")
    private val BRIEF = Regex(
      """\b(in short|short answer|keep it short|briefly|in brief|brief|in a nutshell|one sentence|two sentences|one line|one-liner|tl;?dr|quick(ly)? summary|summari[sz]e|summary)\b"""
    )
    private val OPTION_LINE = Regex("""(?m)^\s*([A-Da-d])[).:]\s+(.+)$""")
    const val DEFAULT_SYSTEM_PROMPT = "You are StudyPilot, a tutor for university students. Be accurate and direct."
    private const val PLAIN_MATH_RULE = "Write maths as plain text with Unicode symbols (for example Av = λv, x², √2, ≤), never LaTeX."
    private const val QUIZ_SYSTEM_PROMPT = "You write university exam questions. You reply with JSON only."
  }
}
