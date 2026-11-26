package com.example.data.ai

import com.example.platform.Log
import com.example.data.ai.llm.CloudClient
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
  AUTO("Auto", "The cloud model when it's set up, then your PC, then the phone model, then built-in answers"),
  CLOUD("Cloud", "Only the free cloud model (needs an API key)"),
  PC("PC", "Only the model on your PC (Ollama)"),
  PHONE("Phone", "Only the model downloaded to this phone"),
  BUILT_IN("Built-in", "Built-in notes, maths and code solvers, and Wikipedia. No model needed")
}

/**
 * What a request is for. Answers to the student (tutor replies, quiz questions) get the best model;
 * background work (summaries, extraction, grading) leaves the cloud's daily budget to them.
 */
enum class AiTask { ANSWER, BACKGROUND }

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

  /** Models to try for [task], best first; the next one is used when one fails or hits its limit. */
  suspend fun backends(task: AiTask = AiTask.ANSWER): List<LlmBackend> = when (source) {
    AiSource.AUTO -> listOfNotNull(cloud.backendOrNull(background = task == AiTask.BACKGROUND), pc.backendOrNull(), phone?.backendOrNull())
    AiSource.CLOUD -> listOfNotNull(cloud.backendOrNull())
    AiSource.PC -> listOfNotNull(pc.backendOrNull())
    AiSource.PHONE -> listOfNotNull(phone?.backendOrNull())
    AiSource.BUILT_IN -> emptyList()
  }

  /** The model that will answer next, or null for built-in answers. */
  suspend fun backend(task: AiTask = AiTask.ANSWER): LlmBackend? = backends(task).firstOrNull()

  /** Runs a one-off prompt, trying each model in turn. Null when none is available or all fail. */
  suspend fun prompt(
    prompt: String,
    system: String = DEFAULT_SYSTEM_PROMPT,
    temperature: Double = 0.5,
    json: Boolean = false,
    task: AiTask = AiTask.ANSWER
  ): String? {
    for (backend in backends(task)) {
      try {
        backend.generate(LlmRequest(system, listOf(LlmTurn(true, prompt)), temperature, json)).takeIf { it.isNotBlank() }?.let { return it }
      } catch (e: Exception) {
        Log.w(TAG, "${backend.label} failed", e)
      }
    }
    return null
  }

  // --- Tutor chat ---

  suspend fun chat(
    history: List<ChatMessage>,
    systemInstruction: String = DEFAULT_SYSTEM_PROMPT,
    subjectContext: String? = null,
    onPartial: ((String) -> Unit)? = null,
    /** Passages from the student's own chats and notes that match the question (RAG). */
    knowledge: List<KnowledgePassage> = emptyList()
  ): ChatResponse {
    val lastUserMsg = history.lastOrNull { it.role == "user" }?.text.orEmpty()

    suspend fun builtIn(note: String? = null): ChatResponse {
      val local = LocalAiEngine.answerQuestion(userQuery = lastUserMsg, history = history, subjectContext = subjectContext)
      return if (note == null) local else local.copy(text = "_${note}_\n\n${local.text}")
    }

    val chain = backends(AiTask.ANSWER)
    if (chain.isEmpty()) {
      val note = when (source) {
        AiSource.CLOUD -> if (cloud.isConfigured) "The cloud model isn't available right now (free limit), so this is a built-in answer." else "No cloud API key is set, so this is a built-in answer."
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
      else t.dropLast(1) + last.copy(text = last.text + KnowledgePassage.groundingInstruction(knowledge))
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
      Summarize these study notes on "$title" in under 250 words:
      1. The core idea in 2-3 sentences.
      2. 3-4 key definitions or takeaways.
      3. One typical exam question on it.

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
      "${topic.descriptionNotes.take(3000)}"

      ${freshnessNote(avoid)}

      ${questionFormatSpec(difficulty, types)}
    """.trimIndent()
    val raw = prompt(prompt, QUIZ_SYSTEM_PROMPT, if (avoid.isEmpty()) 0.4 else 0.7, json = true) ?: return emptyList()
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
    val raw = prompt(prompt, QUIZ_SYSTEM_PROMPT, if (avoid.isEmpty()) 0.4 else 0.7, json = true) ?: return emptyList()
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
