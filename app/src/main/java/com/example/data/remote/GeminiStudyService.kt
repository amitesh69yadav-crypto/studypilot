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

class GeminiStudyService {

  var customGeminiKey: String = ""
  var preferredProviderMode: String = "AUTO" // "AUTO", "GEMINI", "LOCAL"

  private val client = OkHttpClient.Builder()
    .connectionPool(okhttp3.ConnectionPool(5, 2, TimeUnit.MINUTES))
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(6, TimeUnit.SECONDS)
    .build()

  @Volatile
  private var lastWorkingModel: String? = null

  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  fun normalizeProviderMode(mode: String): String {
    return when (mode.uppercase()) {
      "GEMINI" -> "GEMINI"
      "LOCAL" -> "LOCAL"
      else -> "AUTO"
    }
  }

  fun getGeminiApiKey(): String {
    val buildKey = try {
      BuildConfig.GEMINI_API_KEY
    } catch (_: Exception) {
      try { BuildConfig.ENV_GEMINI_API_KEY } catch (_: Exception) { "" }
    }
    val rawKey = if (customGeminiKey.isNotBlank()) customGeminiKey else buildKey
    val sanitized = rawKey.trim().removeSurrounding("\"", "\"")
    return if (sanitized.isNotBlank() && sanitized != "MY_GEMINI_API_KEY") sanitized else ""
  }

  fun isGeminiConfigured(): Boolean = getGeminiApiKey().isNotBlank()

  fun getActiveProviderDescription(): String {
    return when (normalizeProviderMode(preferredProviderMode)) {
      "GEMINI" -> if (isGeminiConfigured()) "Google Gemini AI (Primary Cloud)" else "Gemini (Key Not Configured -> Local AI Fallback)"
      "LOCAL" -> "Local Academic AI / Ollama (100% On-Device)"
      else -> {
        if (isGeminiConfigured()) "Auto Fallback: Google Gemini → Local AI"
        else "Local Academic AI Engine (Ready)"
      }
    }
  }

  suspend fun testConnection(provider: String, apiKeyToTest: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    when (provider.uppercase()) {
      "LOCAL" -> {
        val (ok, msg) = LocalAiEngine.testOllamaConnection(LocalAiEngine.localOllamaUrl)
        return@withContext if (ok) Pair(true, msg) else Pair(true, "✅ Built-in Local Academic AI is active and ready.")
      }
      "GEMINI" -> {
        val key = apiKeyToTest.trim().removeSurrounding("\"", "\"")
        if (key.isBlank()) return@withContext Pair(false, "API key is empty. Please enter a valid Gemini API key.")
        try {
          val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
              put(JSONObject().apply {
                put("parts", JSONArray().apply {
                  put(JSONObject().put("text", "Respond in 3 words: Gemini active ready."))
                })
              })
            })
          }
          val modelsToTry = listOf(
            "gemini-3.8-flash",
            "gemini-3.5-flash",
            "gemini-3.1-flash-lite-preview",
            "gemini-flash-latest",
            "gemini-2.5-flash"
          )
          var lastErr = ""
          for (m in modelsToTry) {
            val request = Request.Builder()
              .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$key")
              .post(jsonBody.toString().toRequestBody(jsonMediaType))
              .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.isSuccessful && body.contains("candidates")) {
              return@withContext Pair(true, "✅ Gemini API verified ($m)! Connection successful.")
            } else {
              lastErr = "($m HTTP ${response.code}): ${body.take(120)}"
            }
          }
          Pair(false, "❌ Gemini API Error: $lastErr")
        } catch (e: Exception) {
          Pair(false, "❌ Exception connecting to Gemini: ${e.message}")
        }
      }
      else -> Pair(false, "Only Google Gemini and Local AI are supported.")
    }
  }

  // --- Gemini Call Helper ---
  private suspend fun callGeminiPromptApi(prompt: String, systemInstruction: String = ""): String? = withContext(Dispatchers.IO) {
    val geminiKey = getGeminiApiKey()
    if (geminiKey.isBlank()) return@withContext null

    val modelsToTry = listOfNotNull(
      lastWorkingModel,
      "gemini-2.5-flash",
      "gemini-flash-latest",
      "gemini-3.1-flash-lite-preview",
      "gemini-3.8-flash",
      "gemini-3.5-flash"
    ).distinct()

    val jsonBodyStr = JSONObject().apply {
      put("contents", JSONArray().apply {
        put(JSONObject().apply {
          put("parts", JSONArray().apply {
            if (systemInstruction.isNotBlank()) {
              put(JSONObject().put("text", "$systemInstruction\n\n$prompt"))
            } else {
              put(JSONObject().put("text", prompt))
            }
          })
        })
      })
    }.toString()

    for (m in modelsToTry) {
      try {
        val request = Request.Builder()
          .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$geminiKey")
          .post(jsonBodyStr.toRequestBody(jsonMediaType))
          .build()

        client.newCall(request).execute().use { response ->
          val respBody = response.body?.string() ?: ""
          if (response.isSuccessful) {
            val jsonResp = JSONObject(respBody)
            val text = jsonResp.optJSONArray("candidates")
              ?.optJSONObject(0)
              ?.optJSONObject("content")
              ?.optJSONArray("parts")
              ?.optJSONObject(0)
              ?.optString("text")

            if (!text.isNullOrBlank()) {
              lastWorkingModel = m
              return@withContext text.trim()
            }
          } else if (response.code == 401 || response.code == 403 ||
            (response.code == 400 && respBody.contains("API_KEY_INVALID", ignoreCase = true))
          ) {
            // Invalid / unauthorized key: fail fast without wasting retries across fallback models
            return@withContext null
          }
        }
      } catch (e: Exception) {
        Log.w("GeminiStudyService", "Gemini $m call failed: ${e.message}")
      }
    }
    null
  }

  // --- Gemini / Local Prompt Dispatcher ---
  private suspend fun dispatchPromptApiCall(
    prompt: String,
    systemInstruction: String = "You are StudyPilot AI, an expert academic tutor for university students.",
    @Suppress("UNUSED_PARAMETER") temperature: Double = 0.7
  ): String? = withContext(Dispatchers.IO) {
    if (normalizeProviderMode(preferredProviderMode) == "LOCAL") {
      return@withContext null
    }
    callGeminiPromptApi(prompt, systemInstruction)?.takeIf { it.isNotBlank() }
  }

  // --- Summarization ---
  suspend fun summarizeMaterial(title: String, rawContent: String): String = withContext(Dispatchers.IO) {
    if (normalizeProviderMode(preferredProviderMode) == "LOCAL") {
      return@withContext LocalAiEngine.summarizeMaterial(title, rawContent)
    }

    val prompt = """
      You are StudyPilot AI, an expert academic tutor for university students.
      Summarize the following study material on "$title" concisely.
      Provide:
      1. Core Concept (2-3 sentences)
      2. 3-4 Key Takeaways / Definitions
      3. 1 Typical Exam Problem / Concept Focus
      Keep response focused, clear, and under 250 words.

      Study Material:
      $rawContent
    """.trimIndent()

    try {
      val apiResult = dispatchPromptApiCall(prompt)
      if (!apiResult.isNullOrBlank()) {
        return@withContext apiResult
      }
    } catch (e: Exception) {
      Log.w("GeminiStudyService", "Cloud summarization exception: ${e.message}")
    }

    LocalAiEngine.summarizeMaterial(title, rawContent)
  }

  // --- Question Generation ---
  suspend fun generateQuestionsForTopic(
    topic: StudyTopic,
    desiredCount: Int = 3,
    difficulty: String = "MEDIUM"
  ): List<QuizQuestion> = withContext(Dispatchers.IO) {
    if (normalizeProviderMode(preferredProviderMode) == "LOCAL") {
      return@withContext LocalAiEngine.generateLocalQuizQuestions(topic, desiredCount, difficulty)
    }

    val prompt = """
      Generate exactly $desiredCount revision questions on the topic "${topic.title}" (${topic.subjectName}) with difficulty $difficulty.
      Based on these student notes:
      "${topic.descriptionNotes.take(1500)}"

      Format output as a raw valid JSON array of objects with keys:
      - "questionType": one of "MULTIPLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER", "CODING"
      - "difficulty": "$difficulty"
      - "questionText": string
      - "optionsJson": comma-separated options for MULTIPLE_CHOICE (4 options) or TRUE_FALSE ("True,False"), otherwise empty string
      - "correctAnswer": string (must match one option or be concise expected answer)
      - "explanation": string explaining why it is correct
      - "codeStarter": string with boilerplate code for CODING, or null for other types

      Do not include markdown ticks (```json), return raw JSON only.
    """.trimIndent()

    try {
      val apiResult = dispatchPromptApiCall(
        prompt,
        systemInstruction = "You are an expert exam item writer. Respond ONLY with a valid raw JSON array.",
        temperature = 0.3
      )
      if (!apiResult.isNullOrBlank()) {
        val parsed = parseQuestionsJson(apiResult, topic, difficulty)
        if (parsed.isNotEmpty()) return@withContext parsed
      }
    } catch (e: Exception) {
      Log.w("GeminiStudyService", "Cloud quiz generation failed: ${e.message}")
    }

    LocalAiEngine.generateLocalQuizQuestions(topic, desiredCount, difficulty)
  }

  private fun parseQuestionsJson(
    rawJson: String,
    topic: StudyTopic,
    difficulty: String
  ): List<QuizQuestion> {
    val list = mutableListOf<QuizQuestion>()
    try {
      val cleanJson = rawJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
      val jsonArray = JSONArray(cleanJson)
      for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        list.add(
          QuizQuestion(
            topicId = topic.id,
            subjectName = topic.subjectName,
            topicTitle = topic.title,
            questionType = obj.optString("questionType", "MULTIPLE_CHOICE"),
            difficulty = obj.optString("difficulty", difficulty),
            questionText = obj.optString("questionText", "Practice Problem ${i + 1}"),
            optionsJson = obj.optString("optionsJson", ""),
            correctAnswer = obj.optString("correctAnswer", ""),
            explanation = obj.optString("explanation", "Review course notes for details."),
            codeStarter = if (obj.has("codeStarter") && !obj.isNull("codeStarter")) obj.getString("codeStarter") else null
          )
        )
      }
    } catch (e: Exception) {
      Log.w("GeminiStudyService", "Failed to parse questions JSON: ${e.message}")
    }
    return list
  }

  suspend fun executeAiPrompt(
    prompt: String,
    systemInstruction: String = "You are StudyPilot AI, an expert academic tutor for university students.",
    temperature: Double = 0.5
  ): String? {
    return dispatchPromptApiCall(prompt, systemInstruction, temperature)
  }

  suspend fun generateQuestionsForConcept(
    concept: ExtractedConcept,
    desiredCount: Int = 2,
    difficulty: String = "MEDIUM"
  ): List<QuizQuestion> = withContext(Dispatchers.IO) {
    if (normalizeProviderMode(preferredProviderMode) == "LOCAL") {
      return@withContext LocalAiEngine.generateLocalQuizQuestionsForConcept(concept, desiredCount, difficulty)
    }

    val contextDetails = buildString {
      append("Concept: ${concept.conceptName} (Topic: ${concept.topic} - Subtopic: ${concept.subtopic})\n")
      append("Academic Definition: ${concept.definition}\n")
      if (concept.technicalDetails.isNotBlank()) append("Technical Details: ${concept.technicalDetails}\n")
      if (concept.formulaOrCode.isNotBlank()) append("Formulas / Code Example:\n${concept.formulaOrCode}\n")
      if (concept.problemsSolved.isNotBlank()) append("Problems Solved in Chat: ${concept.problemsSolved}\n")
      if (concept.misconceptionsAndCorrections.isNotBlank()) append("Exam Traps & Misconceptions: ${concept.misconceptionsAndCorrections}\n")
      if (concept.keyFacts.isNotBlank()) append("Key Invariants: ${concept.keyFacts}\n")
    }

    val prompt = """
      Generate exactly $desiredCount high-quality academic exam questions testing deep conceptual understanding of the following concept extracted from an AI learning conversation.
      Do NOT generate shallow trivia questions about word definitions or titles. Test conceptual understanding, practical application, problem-solving, and technical reasoning!

      $contextDetails

      Format output as a raw valid JSON array of objects with keys:
      - "questionType": one of "MULTIPLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER", "CODING"
      - "difficulty": "$difficulty"
      - "questionText": string
      - "optionsJson": comma-separated options for MULTIPLE_CHOICE (4 options) or TRUE_FALSE ("True,False"), otherwise empty string
      - "correctAnswer": string (must match one option or be concise expected answer)
      - "explanation": string explaining the deep reasoning, why alternatives are wrong, or citing the concept
      - "codeStarter": string with boilerplate code for CODING, or null for other types

      Do not include markdown ticks (```json), return raw JSON only.
    """.trimIndent()

    try {
      val apiResult = dispatchPromptApiCall(
        prompt,
        systemInstruction = "You are an expert university exam writer testing conceptual mastery and practical problem solving. Output raw JSON array only.",
        temperature = 0.3
      )
      if (!apiResult.isNullOrBlank()) {
        val parsed = parseConceptQuestionsJson(apiResult, concept, difficulty)
        if (parsed.isNotEmpty()) return@withContext parsed
      }
    } catch (e: Exception) {
      Log.w("GeminiStudyService", "Concept quiz generation failed: ${e.message}")
    }

    LocalAiEngine.generateLocalQuizQuestionsForConcept(concept, desiredCount, difficulty)
  }

  private fun parseConceptQuestionsJson(
    rawJson: String,
    concept: ExtractedConcept,
    difficulty: String
  ): List<QuizQuestion> {
    val list = mutableListOf<QuizQuestion>()
    try {
      val cleanJson = rawJson.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
      val jsonArray = JSONArray(cleanJson)
      for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        list.add(
          QuizQuestion(
            topicId = concept.id,
            subjectName = concept.subjectName,
            topicTitle = concept.conceptName,
            questionType = obj.optString("questionType", "MULTIPLE_CHOICE"),
            difficulty = obj.optString("difficulty", difficulty),
            questionText = obj.optString("questionText", "Practice Problem ${i + 1}"),
            optionsJson = obj.optString("optionsJson", ""),
            correctAnswer = obj.optString("correctAnswer", ""),
            explanation = obj.optString("explanation", "Review extracted AI conversation concepts for details."),
            codeStarter = if (obj.has("codeStarter") && !obj.isNull("codeStarter")) obj.getString("codeStarter") else null,
            sourceConversationId = concept.sourceChatId,
            sourceConversationTitle = concept.sourceChatTitle
          )
        )
      }
    } catch (e: Exception) {
      Log.w("GeminiStudyService", "Failed to parse concept questions JSON: ${e.message}")
    }
    return list
  }

  // --- Answer Evaluation ---
  suspend fun evaluateAnswer(
    question: QuizQuestion,
    userAnswer: String
  ): EvaluationResult = withContext(Dispatchers.IO) {
    if (normalizeProviderMode(preferredProviderMode) == "LOCAL") {
      return@withContext LocalAiEngine.evaluateAnswer(question, userAnswer)
    }

    val directMatch = userAnswer.trim().equals(question.correctAnswer.trim(), ignoreCase = true)
    val prompt = """
      You are an automated academic evaluator.
      Question: "${question.questionText}"
      Expected Answer: "${question.correctAnswer}"
      Explanation Context: "${question.explanation}"
      Student Answer: "$userAnswer"

      Evaluate the student answer for conceptual accuracy.
      Format output strictly as JSON object with keys:
      - "isCorrect": boolean
      - "feedback": string (concise 1-2 sentence feedback explaining why it's right or wrong)

      Raw JSON only, no markdown ticks.
    """.trimIndent()

    try {
      val apiResult = dispatchPromptApiCall(
        prompt,
        systemInstruction = "You are an automated academic evaluator. Output raw JSON only.",
        temperature = 0.2
      )
      if (!apiResult.isNullOrBlank()) {
        val cleanJson = apiResult.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val evalObj = JSONObject(cleanJson)
        return@withContext EvaluationResult(
          isCorrect = evalObj.optBoolean("isCorrect", directMatch),
          feedback = evalObj.optString("feedback", question.explanation)
        )
      }
    } catch (e: Exception) {
      Log.w("GeminiStudyService", "Cloud evaluation failed: ${e.message}")
    }

    LocalAiEngine.evaluateAnswer(question, userAnswer)
  }

  // --- Interactive Tutor Chat (Gemini AI -> Local AI ONLY) ---
  suspend fun chatWithGemini(
    history: List<ChatMessage>,
    model: TutorModel = TutorModel.FLASH,
    useGoogleSearch: Boolean = false,
    systemInstruction: String = "You are StudyPilot AI Tutor. Answer directly what is asked without hallucinating facts or substituting unrelated topics.",
    subjectContext: String? = null
  ): ChatResponse = withContext(Dispatchers.IO) {
    val lastUserMsg = history.lastOrNull { it.role == "user" }?.text ?: "Hello"

    if (model == TutorModel.LOCAL_AI || normalizeProviderMode(preferredProviderMode) == "LOCAL") {
      return@withContext LocalAiEngine.answerQuestion(
        userQuery = lastUserMsg,
        history = history,
        model = model,
        useGoogleSearch = useGoogleSearch,
        subjectContext = subjectContext
      )
    }

    // Try Google Gemini Primary
    val geminiKey = getGeminiApiKey()
    val primaryModelId = model.modelId
    val modelsToTry = if (useGoogleSearch) {
      listOfNotNull(lastWorkingModel, "gemini-2.5-flash", "gemini-flash-latest", "gemini-3.8-flash", "gemini-3.5-flash")
    } else {
      val explicitNonDefault = if (primaryModelId != TutorModel.FLASH.modelId) primaryModelId else null
      listOfNotNull(
        explicitNonDefault,
        lastWorkingModel,
        "gemini-2.5-flash",
        "gemini-flash-latest",
        "gemini-3.1-flash-lite-preview",
        primaryModelId,
        "gemini-3.8-flash",
        "gemini-3.5-flash"
      )
    }.distinct().filter { it != "local-ai-academic" }

    if (geminiKey.isNotBlank()) {
      val contentsArray = JSONArray()
      val recentHistory = history.takeLast(12)
      for (msg in recentHistory) {
        val partObj = JSONObject().put("text", msg.text)
        val contentObj = JSONObject().apply {
          put("role", if (msg.role == "model" || msg.role == "assistant") "model" else "user")
          put("parts", JSONArray().put(partObj))
        }
        contentsArray.put(contentObj)
      }

      val jsonBodyStr = JSONObject().apply {
        put("contents", contentsArray)
        put("systemInstruction", JSONObject().apply {
          put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
        })

        if (useGoogleSearch) {
          put("tools", JSONArray().apply {
            put(JSONObject().put("googleSearch", JSONObject()))
          })
        }
      }.toString()

      for (targetModel in modelsToTry) {
        try {
          val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent?key=$geminiKey")
            .post(jsonBodyStr.toRequestBody(jsonMediaType))
            .build()

          client.newCall(request).execute().use { response ->
            val respBody = response.body?.string() ?: ""
            if (response.isSuccessful) {
              val jsonResp = JSONObject(respBody)
              val candidate = jsonResp.optJSONArray("candidates")?.optJSONObject(0)
              val text = candidate?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")

              val groundingSources = mutableListOf<GroundingSource>()
              val searchQueries = mutableListOf<String>()

              val groundingMeta = candidate?.optJSONObject("groundingMetadata")
              if (groundingMeta != null) {
                val webQueries = groundingMeta.optJSONArray("webSearchQueries")
                if (webQueries != null) {
                  for (i in 0 until webQueries.length()) {
                    searchQueries.add(webQueries.getString(i))
                  }
                }

                val chunks = groundingMeta.optJSONArray("groundingChunks")
                if (chunks != null) {
                  for (i in 0 until chunks.length()) {
                    val chunk = chunks.getJSONObject(i)
                    val web = chunk.optJSONObject("web")
                    if (web != null) {
                      val uri = web.optString("uri", "")
                      val title = web.optString("title", "Web Source")
                      if (uri.isNotBlank()) {
                        groundingSources.add(GroundingSource(title = title, url = uri))
                      }
                    }
                  }
                }
              }

              if (!text.isNullOrBlank()) {
                lastWorkingModel = targetModel
                val (cleanText, extractedTopics) = com.example.data.ai.AcademicPedagogySynthesizer.extractAndCleanRelatedTopics(
                  rawContent = text.trim(),
                  fallbackQuery = lastUserMsg
                )
                return@withContext ChatResponse(
                  text = cleanText,
                  modelName = "$targetModel (Gemini AI)",
                  groundingSources = groundingSources,
                  searchQueries = searchQueries,
                  isError = false,
                  relatedTopics = extractedTopics
                )
              }
            } else if (response.code == 401 || response.code == 403 ||
              (response.code == 400 && respBody.contains("API_KEY_INVALID", ignoreCase = true))
            ) {
              return@withContext LocalAiEngine.answerQuestion(
                userQuery = lastUserMsg,
                history = history,
                model = model,
                useGoogleSearch = useGoogleSearch,
                subjectContext = subjectContext
              )
            }
          }
        } catch (e: Exception) {
          Log.w("GeminiStudyService", "Gemini chat $targetModel failed: ${e.message}")
        }
      }
    }

    // Reliable Local AI Fallback: ALWAYS produces a verified, direct academic response
    LocalAiEngine.answerQuestion(
      userQuery = lastUserMsg,
      history = history,
      model = model,
      useGoogleSearch = useGoogleSearch,
      subjectContext = subjectContext
    )
  }
}
