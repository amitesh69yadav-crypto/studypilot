package com.example.data.ai

import android.content.Context
import android.util.Log
import com.example.data.ai.llm.LlmBackend
import com.example.data.ai.llm.LlmRequest
import com.example.data.ai.llm.LlmTurn
import com.example.data.ai.llm.OllamaClient
import com.example.data.ai.llm.PhoneModels
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
  AUTO("Auto", "Your PC when it's reachable, otherwise the phone model, otherwise built-in answers"),
  PC("PC", "Only the model on your PC (Ollama)"),
  PHONE("Phone", "Only the model downloaded to this phone"),
  BUILT_IN("Built-in", "Built-in notes, maths and code solvers, and Wikipedia. No model needed")
}

/**
 * Everything the app asks AI for. Runs on Ollama (PC) or LiteRT-LM (phone), and falls back to
 * [LocalAiEngine]'s built-in answers when no model is available.
 */
class StudyAi(context: Context?) {

  val pc = OllamaClient()
  /** Null in plain JVM tests, where there is no Android context. */
  val phone: PhoneModels? = context?.let { PhoneModels(it) }

  @Volatile var source: AiSource = AiSource.AUTO

  /** The model that will answer next, or null for built-in answers. */
  suspend fun backend(): LlmBackend? = when (source) {
    AiSource.AUTO -> pc.backendOrNull() ?: phone?.backendOrNull()
    AiSource.PC -> pc.backendOrNull()
    AiSource.PHONE -> phone?.backendOrNull()
    AiSource.BUILT_IN -> null
  }

  /** Runs a one-off prompt on the active model. Null when no model is available or it fails. */
  suspend fun prompt(prompt: String, system: String = DEFAULT_SYSTEM_PROMPT, temperature: Double = 0.5, json: Boolean = false): String? {
    val backend = backend() ?: return null
    return try {
      backend.generate(LlmRequest(system, listOf(LlmTurn(true, prompt)), temperature, json)).takeIf { it.isNotBlank() }
    } catch (e: Exception) {
      Log.w(TAG, "${backend.label} failed", e)
      null
    }
  }

  // --- Tutor chat ---

  suspend fun chat(
    history: List<ChatMessage>,
    systemInstruction: String = DEFAULT_SYSTEM_PROMPT,
    subjectContext: String? = null,
    onPartial: ((String) -> Unit)? = null
  ): ChatResponse {
    val lastUserMsg = history.lastOrNull { it.role == "user" }?.text.orEmpty()

    suspend fun builtIn(note: String? = null): ChatResponse {
      val local = LocalAiEngine.answerQuestion(userQuery = lastUserMsg, history = history, subjectContext = subjectContext)
      return if (note == null) local else local.copy(text = "_${note}_\n\n${local.text}")
    }

    val backend = backend()
    if (backend == null) {
      val note = when (source) {
        AiSource.PC -> "Your PC isn't reachable, so this is a built-in answer."
        AiSource.PHONE -> "No phone model is downloaded yet, so this is a built-in answer."
        else -> null
      }
      return builtIn(note)
    }

    val system = "$systemInstruction $PLAIN_MATH_RULE"
    val text = try {
      backend.generate(
        LlmRequest(system, toTurns(history), temperature = 0.6, maxOutputTokens = 1200),
        onPartial?.let { show -> { partial: String -> show(PlainMath.convert(partial)) } }
      ).let(PlainMath::convert)
    } catch (e: Exception) {
      Log.w(TAG, "${backend.label} failed", e)
      return builtIn("${backend.label} didn't answer (${e.message}), so this is a built-in answer.")
    }
    if (text.isBlank()) return builtIn("${backend.label} returned an empty answer, so this is a built-in answer.")

    val (cleanText, topics) = AcademicPedagogySynthesizer.extractAndCleanRelatedTopics(rawContent = text, fallbackQuery = lastUserMsg)
    return ChatResponse(text = cleanText, modelName = backend.label, relatedTopics = topics)
  }

  /**
   * Models expect the conversation to start with the user and alternate. Drops greetings and
   * error bubbles and merges consecutive messages from the same side.
   */
  internal fun toTurns(history: List<ChatMessage>, maxMessages: Int = 12): List<LlmTurn> {
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
    return prompt(prompt, temperature = 0.4) ?: LocalAiEngine.summarizeMaterial(title, rawContent)
  }

  // --- Quiz generation ---

  private fun questionFormatSpec(difficulty: String) = """
    Reply with a JSON array only. Each item has:
    - "questionType": "MULTIPLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER" or "CODING"
    - "difficulty": "$difficulty"
    - "questionText": string
    - "optionsJson": for MULTIPLE_CHOICE a JSON array of the 4 option texts (the full text, not letters);
      ["True", "False"] for TRUE_FALSE; otherwise []
    - "correctAnswer": for choice questions, the full text of the correct option; otherwise a short model answer
    Write maths as plain text (λ, x², ≤), not LaTeX.
    - "explanation": why the answer is correct
    - "codeStarter": starter code for CODING, otherwise null
  """.trimIndent()

  suspend fun generateQuestionsForTopic(topic: StudyTopic, desiredCount: Int = 3, difficulty: String = "MEDIUM"): List<QuizQuestion> {
    val prompt = """
      Write exactly $desiredCount revision questions on "${topic.title}" (${topic.subjectName}), difficulty $difficulty.
      Base them on these student notes:
      "${topic.descriptionNotes.take(1500)}"

      ${questionFormatSpec(difficulty)}
    """.trimIndent()
    return prompt(prompt, QUIZ_SYSTEM_PROMPT, 0.3, json = true)
      ?.let { parseQuestions(it, difficulty) { q -> q.copy(topicId = topic.id, subjectName = topic.subjectName, topicTitle = topic.title) } }
      ?.takeIf { it.isNotEmpty() }
      ?.take(desiredCount)
      ?: LocalAiEngine.generateLocalQuizQuestions(topic, desiredCount, difficulty)
  }

  suspend fun generateQuestionsForConcept(concept: ExtractedConcept, desiredCount: Int = 2, difficulty: String = "MEDIUM"): List<QuizQuestion> {
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
      Write exactly $desiredCount exam questions that test understanding and application of this concept,
      not recall of its name.

      $context
      ${questionFormatSpec(difficulty)}
    """.trimIndent()
    return prompt(prompt, QUIZ_SYSTEM_PROMPT, 0.3, json = true)
      ?.let {
        parseQuestions(it, difficulty) { q ->
          q.copy(
            topicId = concept.id,
            subjectName = concept.subjectName,
            topicTitle = concept.conceptName,
            sourceConversationId = concept.sourceChatId,
            sourceConversationTitle = concept.sourceChatTitle
          )
        }
      }
      ?.takeIf { it.isNotEmpty() }
      ?.take(desiredCount)
      ?: LocalAiEngine.generateLocalQuizQuestionsForConcept(concept, desiredCount, difficulty)
  }

  internal fun parseQuestions(raw: String, difficulty: String, attach: (QuizQuestion) -> QuizQuestion): List<QuizQuestion> = try {
    val json = extractJson(raw) ?: "[]"
    // Small models sometimes wrap the array: {"questions": [...]}
    val array = if (json.startsWith("{")) {
      JSONObject(json).let { obj -> obj.keys().asSequence().mapNotNull { obj.optJSONArray(it) }.firstOrNull() ?: JSONArray().put(obj) }
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
    return QuizQuestion(
      topicId = 0L,
      subjectName = "",
      topicTitle = "",
      questionType = type,
      difficulty = obj.optString("difficulty", difficulty),
      questionText = text,
      // "|" when an option contains a comma; the quiz screen accepts either separator.
      optionsJson = options.joinToString(if (options.any { ',' in it }) "|" else ","),
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
    prompt(prompt, "You grade student answers fairly and briefly.", 0.1, json = true)?.let { raw ->
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
    private val OPTION_LINE = Regex("""(?m)^\s*([A-Da-d])[).:]\s+(.+)$""")
    const val DEFAULT_SYSTEM_PROMPT = "You are StudyPilot, a tutor for university students. Be accurate and direct."
    private const val PLAIN_MATH_RULE = "Write maths as plain text with Unicode symbols (for example Av = λv, x², √2, ≤), never LaTeX."
    private const val QUIZ_SYSTEM_PROMPT = "You write university exam questions. You reply with JSON only."
  }
}
