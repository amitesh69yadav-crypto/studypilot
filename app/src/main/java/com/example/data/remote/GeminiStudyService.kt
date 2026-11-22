package com.example.data.remote

import android.util.Log
import com.example.BuildConfig
import com.example.data.ai.LocalAiEngine
import com.example.data.model.ChatMessage
import com.example.data.model.ExtractedConcept
import com.example.data.model.GroundingSource
import com.example.data.model.QuizQuestion
import com.example.data.model.StudyTopic
import com.example.data.model.TutorModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ChatResponse(
  val text: String,
  val modelName: String,
  val groundingSources: List<GroundingSource> = emptyList(),
  val searchQueries: List<String> = emptyList(),
  val isError: Boolean = false,
  val relatedTopics: List<String> = emptyList()
)

data class EvaluationResult(
  val isCorrect: Boolean,
  val feedback: String
)

/**
 * Talks to the Gemini REST API and falls back to [LocalAiEngine] (offline templates, Wikipedia,
 * optional Ollama) when no key is set, the provider mode is LOCAL, or Gemini can't be reached.
 */
class GeminiStudyService {

  var customGeminiKey: String = ""
  var preferredProviderMode: String = "AUTO" // AUTO, GEMINI, LOCAL

  private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS) // long answers and search grounding regularly take >15 s
    .writeTimeout(10, TimeUnit.SECONDS)
    .callTimeout(90, TimeUnit.SECONDS)
    .build()

  @Volatile
  private var lastWorkingModel: String? = null

  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  fun normalizeProviderMode(mode: String): String = when (mode.uppercase()) {
    "GEMINI" -> "GEMINI"
    "LOCAL" -> "LOCAL"
    else -> "AUTO"
  }

  private val useLocalOnly: Boolean get() = normalizeProviderMode(preferredProviderMode) == "LOCAL"

  fun getGeminiApiKey(): String {
    val rawKey = customGeminiKey.ifBlank { BuildConfig.GEMINI_API_KEY.ifBlank { BuildConfig.ENV_GEMINI_API_KEY } }
    val sanitized = rawKey.trim().removeSurrounding("\"")
    return if (sanitized.isNotBlank() && sanitized != "MY_GEMINI_API_KEY") sanitized else ""
  }

  fun isGeminiConfigured(): Boolean = getGeminiApiKey().isNotBlank()

  fun getActiveProviderDescription(): String = when (normalizeProviderMode(preferredProviderMode)) {
    "GEMINI" -> if (isGeminiConfigured()) "Gemini" else "Gemini (no API key, using offline answers)"
    "LOCAL" -> "Offline answers / Ollama"
    else -> if (isGeminiConfigured()) "Gemini, offline answers as fallback" else "Offline answers (add a Gemini key for AI)"
  }

  // --- Low-level Gemini call ---

  private class GeminiResult(
    val text: String?,
    val model: String?,
    val sources: List<GroundingSource> = emptyList(),
    val queries: List<String> = emptyList(),
    val error: String? = null
  )

  private fun candidateModels(preferred: String?): List<String> =
    listOfNotNull(preferred, lastWorkingModel, TutorModel.FLASH.modelId, "gemini-flash-latest", "gemini-2.5-flash")
      .filter { it != TutorModel.LOCAL_AI.modelId }
      .distinct()

  /**
   * Tries each model in turn until one answers. Stops early on an invalid key, since every model
   * would fail the same way.
   */
  private fun generate(
    contents: JSONArray,
    systemInstruction: String,
    temperature: Double,
    jsonOutput: Boolean = false,
    googleSearch: Boolean = false,
    preferredModel: String? = null,
    apiKey: String = getGeminiApiKey()
  ): GeminiResult {
    if (apiKey.isBlank()) return GeminiResult(null, null, error = "no Gemini API key")

    val body = JSONObject().apply {
      put("contents", contents)
      if (systemInstruction.isNotBlank()) {
        put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
      }
      put("generationConfig", JSONObject().apply {
        put("temperature", temperature)
        if (jsonOutput) put("responseMimeType", "application/json")
      })
      if (googleSearch) put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
    }.toString()

    var lastError = "no response"
    for (model in candidateModels(preferredModel)) {
      val request = Request.Builder()
        .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        .header("x-goog-api-key", apiKey)
        .post(body.toRequestBody(jsonMediaType))
        .build()
      try {
        client.newCall(request).execute().use { response ->
          val respBody = response.body?.string().orEmpty()
          if (response.isSuccessful) {
            val candidate = JSONObject(respBody).optJSONArray("candidates")?.optJSONObject(0)
            val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
            val text = buildString {
              for (i in 0 until (parts?.length() ?: 0)) append(parts!!.optJSONObject(i)?.optString("text").orEmpty())
            }.trim()
            if (text.isNotBlank()) {
              lastWorkingModel = model
              return GeminiResult(text, model, parseSources(candidate), parseQueries(candidate))
            }
            lastError = "$model returned an empty answer"
          } else {
            lastError = "$model HTTP ${response.code}"
            if (response.code == 401 || response.code == 403 ||
              (response.code == 400 && respBody.contains("API_KEY_INVALID", ignoreCase = true))
            ) {
              return GeminiResult(null, null, error = "the Gemini API key was rejected")
            }
          }
        }
      } catch (e: Exception) {
        lastError = "$model: ${e.javaClass.simpleName}"
        Log.w(TAG, "Gemini $model call failed", e)
      }
    }
    return GeminiResult(null, null, error = lastError)
  }

  private fun parseSources(candidate: JSONObject?): List<GroundingSource> {
    val chunks = candidate?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks") ?: return emptyList()
    return (0 until chunks.length()).mapNotNull { i ->
      val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: return@mapNotNull null
      val uri = web.optString("uri")
      if (uri.isBlank()) null else GroundingSource(title = web.optString("title", "Web source"), url = uri)
    }
  }

  private fun parseQueries(candidate: JSONObject?): List<String> {
    val queries = candidate?.optJSONObject("groundingMetadata")?.optJSONArray("webSearchQueries") ?: return emptyList()
    return (0 until queries.length()).map { queries.optString(it) }.filter { it.isNotBlank() }
  }

  private fun singlePrompt(prompt: String) =
    JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt))))

  /**
   * Gemini expects the conversation to start with the user and alternate roles. Drops greetings
   * and error bubbles and merges consecutive messages from the same side.
   */
  internal fun buildChatContents(history: List<ChatMessage>, maxMessages: Int = 12): JSONArray {
    val turns = mutableListOf<Pair<String, StringBuilder>>()
    for (msg in history.filter { !it.isError && it.text.isNotBlank() }.takeLast(maxMessages)) {
      val role = if (msg.role == "model" || msg.role == "assistant") "model" else "user"
      if (turns.isEmpty() && role == "model") continue
      if (turns.lastOrNull()?.first == role) turns.last().second.append("\n\n").append(msg.text)
      else turns.add(role to StringBuilder(msg.text))
    }
    return JSONArray().apply {
      turns.forEach { (role, text) ->
        put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text.toString()))))
      }
    }
  }

  // --- Connection test ---

  suspend fun testConnection(provider: String, apiKeyToTest: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    when (provider.uppercase()) {
      "LOCAL" -> LocalAiEngine.testOllamaConnection(LocalAiEngine.localOllamaUrl)
      "GEMINI" -> {
        val key = apiKeyToTest.trim().removeSurrounding("\"")
        if (key.isBlank()) return@withContext false to "Enter a Gemini API key first."
        val result = generate(singlePrompt("Reply with the word OK."), "", 0.0, apiKey = key)
        if (result.text != null) true to "Gemini is working (${result.model})."
        else false to "Gemini test failed: ${result.error}."
      }
      else -> false to "Unknown provider: $provider"
    }
  }

  // --- Prompt helpers used by summaries, quiz generation, chat import ---

  private suspend fun promptGemini(
    prompt: String,
    systemInstruction: String,
    temperature: Double,
    jsonOutput: Boolean = false
  ): String? = withContext(Dispatchers.IO) {
    if (useLocalOnly) return@withContext null
    generate(singlePrompt(prompt), systemInstruction, temperature, jsonOutput).text
  }

  suspend fun executeAiPrompt(
    prompt: String,
    systemInstruction: String = DEFAULT_SYSTEM_PROMPT,
    temperature: Double = 0.5
  ): String? = promptGemini(prompt, systemInstruction, temperature)

  suspend fun summarizeMaterial(title: String, rawContent: String): String = withContext(Dispatchers.IO) {
    val prompt = """
      Summarize these study notes on "$title" in under 250 words:
      1. The core idea in 2-3 sentences.
      2. 3-4 key definitions or takeaways.
      3. One typical exam question on it.

      Notes:
      $rawContent
    """.trimIndent()
    promptGemini(prompt, DEFAULT_SYSTEM_PROMPT, 0.4) ?: LocalAiEngine.summarizeMaterial(title, rawContent)
  }

  // --- Quiz generation ---

  private fun questionFormatSpec(difficulty: String) = """
    Return a JSON array. Each item has:
    - "questionType": "MULTIPLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER" or "CODING"
    - "difficulty": "$difficulty"
    - "questionText": string
    - "optionsJson": 4 comma-separated options for MULTIPLE_CHOICE, "True,False" for TRUE_FALSE, otherwise ""
    - "correctAnswer": for choice questions, exactly one of the options; otherwise a short model answer
    - "explanation": why the answer is correct
    - "codeStarter": starter code for CODING, otherwise null
  """.trimIndent()

  suspend fun generateQuestionsForTopic(
    topic: StudyTopic,
    desiredCount: Int = 3,
    difficulty: String = "MEDIUM"
  ): List<QuizQuestion> = withContext(Dispatchers.IO) {
    val prompt = """
      Write exactly $desiredCount revision questions on "${topic.title}" (${topic.subjectName}), difficulty $difficulty.
      Base them on these student notes:
      "${topic.descriptionNotes.take(1500)}"

      ${questionFormatSpec(difficulty)}
    """.trimIndent()
    promptGemini(prompt, "You write university exam questions.", 0.3, jsonOutput = true)
      ?.let { parseQuestions(it, difficulty) { q -> q.copy(topicId = topic.id, subjectName = topic.subjectName, topicTitle = topic.title) } }
      ?.takeIf { it.isNotEmpty() }
      ?: LocalAiEngine.generateLocalQuizQuestions(topic, desiredCount, difficulty)
  }

  suspend fun generateQuestionsForConcept(
    concept: ExtractedConcept,
    desiredCount: Int = 2,
    difficulty: String = "MEDIUM"
  ): List<QuizQuestion> = withContext(Dispatchers.IO) {
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
    promptGemini(prompt, "You write university exam questions.", 0.3, jsonOutput = true)
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
      ?: LocalAiEngine.generateLocalQuizQuestionsForConcept(concept, desiredCount, difficulty)
  }

  private fun parseQuestions(raw: String, difficulty: String, attach: (QuizQuestion) -> QuizQuestion): List<QuizQuestion> = try {
    val array = JSONArray(raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
    (0 until array.length()).mapNotNull { i ->
      val obj = array.optJSONObject(i) ?: return@mapNotNull null
      val text = obj.optString("questionText")
      val answer = obj.optString("correctAnswer")
      if (text.isBlank() || answer.isBlank()) return@mapNotNull null
      attach(
        QuizQuestion(
          topicId = 0L,
          subjectName = "",
          topicTitle = "",
          questionType = obj.optString("questionType", "SHORT_ANSWER"),
          difficulty = obj.optString("difficulty", difficulty),
          questionText = text,
          optionsJson = obj.optString("optionsJson"),
          correctAnswer = answer,
          explanation = obj.optString("explanation"),
          codeStarter = obj.optString("codeStarter").takeIf { !obj.isNull("codeStarter") && it.isNotBlank() }
        )
      )
    }
  } catch (e: Exception) {
    Log.w(TAG, "Could not parse generated questions", e)
    emptyList()
  }

  // --- Answer evaluation ---

  suspend fun evaluateAnswer(question: QuizQuestion, userAnswer: String): EvaluationResult = withContext(Dispatchers.IO) {
    // Choice questions have one right answer; no need for a network round trip per question.
    if (useLocalOnly || question.questionType == "MULTIPLE_CHOICE" || question.questionType == "TRUE_FALSE" || userAnswer.isBlank()) {
      return@withContext LocalAiEngine.evaluateAnswer(question, userAnswer)
    }
    val prompt = """
      Question: "${question.questionText}"
      Model answer: "${question.correctAnswer}"
      Context: "${question.explanation}"
      Student answer: "$userAnswer"

      Is the student's answer conceptually correct? Return JSON: {"isCorrect": boolean, "feedback": "1-2 sentences"}
    """.trimIndent()
    try {
      promptGemini(prompt, "You grade student answers fairly and briefly.", 0.1, jsonOutput = true)?.let { raw ->
        val obj = JSONObject(raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
        if (obj.has("isCorrect")) {
          return@withContext EvaluationResult(obj.getBoolean("isCorrect"), obj.optString("feedback", question.explanation))
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Could not parse evaluation", e)
    }
    LocalAiEngine.evaluateAnswer(question, userAnswer)
  }

  // --- Tutor chat ---

  suspend fun chatWithGemini(
    history: List<ChatMessage>,
    model: TutorModel = TutorModel.FLASH,
    useGoogleSearch: Boolean = false,
    systemInstruction: String = DEFAULT_SYSTEM_PROMPT,
    subjectContext: String? = null
  ): ChatResponse = withContext(Dispatchers.IO) {
    val lastUserMsg = history.lastOrNull { it.role == "user" }?.text.orEmpty()

    suspend fun offline(note: String? = null): ChatResponse {
      val local = LocalAiEngine.answerQuestion(
        userQuery = lastUserMsg,
        history = history,
        model = model,
        useGoogleSearch = useGoogleSearch,
        subjectContext = subjectContext
      )
      return if (note == null) local else local.copy(text = "_${note}_\n\n${local.text}")
    }

    if (model == TutorModel.LOCAL_AI || useLocalOnly) return@withContext offline()
    if (!isGeminiConfigured()) return@withContext offline()

    val result = generate(
      contents = buildChatContents(history),
      systemInstruction = systemInstruction,
      temperature = 0.7,
      googleSearch = useGoogleSearch,
      preferredModel = model.modelId
    )
    val text = result.text ?: return@withContext offline("Gemini didn't answer (${result.error}), so this is an offline answer.")

    val (cleanText, topics) = com.example.data.ai.AcademicPedagogySynthesizer.extractAndCleanRelatedTopics(
      rawContent = text,
      fallbackQuery = lastUserMsg
    )
    ChatResponse(
      text = cleanText,
      modelName = result.model ?: model.modelId,
      groundingSources = result.sources,
      searchQueries = result.queries,
      relatedTopics = topics
    )
  }

  companion object {
    private const val TAG = "GeminiStudyService"
    const val DEFAULT_SYSTEM_PROMPT = "You are StudyPilot, a tutor for university students. Be accurate and direct."
  }
}
