package com.example.data.ai.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * A cloud chatbot the user brings their own key for: Groq by default (free, no card), or any of
 * [CloudProvider] (OpenAI-compatible ones, and Anthropic's own API). No key is built into the app.
 *
 * Free plans cap requests per minute and per day. A rate-limit reply pauses the cloud until the
 * provider says to retry, and background work stops using it once most of the day's budget is spent,
 * so answers to the student's questions keep getting the cloud.
 */
class CloudClient(
  private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()
) {
  @Volatile var provider: CloudProvider = CloudProvider.GROQ
    set(value) {
      field = value
      models = null
      pausedUntil = 0L
      dailyBudget = value.dailyBudget
    }
  /** Only used for [CloudProvider.CUSTOM]; the others have their own address. */
  @Volatile var customBaseUrl: String = ""
  /** Lets tests point the Anthropic adapter at a local fake. */
  @Volatile internal var anthropicBaseUrlForTests: String? = null
  val baseUrl: String
    get() = when {
      provider == CloudProvider.CUSTOM -> customBaseUrl
      provider.anthropic && anthropicBaseUrlForTests != null -> anthropicBaseUrlForTests!!
      else -> provider.baseUrl
    }.trim().trimEnd('/')
  @Volatile var apiKey: String = ""
  /** Blank picks [DEFAULT_MODELS] in order, or the first one the key can use. */
  @Volatile var model: String = ""

  /** Requests a day the free plan allows (Groq's free plan is around 1,000 for most models). */
  @Volatile var dailyBudget: Int = 1000

  @Volatile private var pausedUntil = 0L
  @Volatile private var usedDay = ""
  @Volatile private var usedToday = 0
  @Volatile private var models: List<String>? = null

  val isConfigured: Boolean get() = apiKey.isNotBlank() && baseUrl.startsWith("http")

  /** Requests sent today (UTC, when the free limits reset). */
  val requestsToday: Int get() = if (usedDay == today()) usedToday else 0

  data class Status(val ok: Boolean, val models: List<String>, val error: String? = null)

  /** Checks the key by listing the models it can use. */
  suspend fun status(): Status = withContext(Dispatchers.IO) {
    if (!isConfigured) return@withContext Status(false, emptyList(), "No API key")
    try {
      http.newCall(authorized(Request.Builder().url("$baseUrl/models")).build()).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) return@withContext Status(false, emptyList(), errorText(resp.code, body))
        val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
        val ids = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank)?.removePrefix("models/") }
          .filterNot { id -> NOT_CHAT.any { id.contains(it, ignoreCase = true) } }
        models = ids
        Status(true, ids)
      }
    } catch (e: Exception) {
      Status(false, emptyList(), e.message ?: e.javaClass.simpleName)
    }
  }

  fun chosenModel(): String {
    model.trim().takeIf { it.isNotBlank() }?.let { return it }
    val preferred = provider.preferredModels
    val available = models
    return preferred.firstOrNull { available == null || it in available } ?: available?.firstOrNull() ?: preferred.firstOrNull().orEmpty()
  }

  /** Anthropic wants its own headers; everyone else takes a bearer token. */
  private fun authorized(builder: Request.Builder): Request.Builder =
    if (provider.anthropic) builder.header("x-api-key", apiKey).header("anthropic-version", ANTHROPIC_VERSION)
    else builder.header("Authorization", "Bearer $apiKey")

  /**
   * The cloud model, or null when it isn't set up, is paused after a rate limit, or (for
   * [background] work) most of today's budget is used.
   */
  fun backendOrNull(background: Boolean = false): LlmBackend? {
    if (!isConfigured || chosenModel().isBlank()) return null
    if (System.currentTimeMillis() < pausedUntil) return null
    if (background && requestsToday >= dailyBudget * BACKGROUND_SHARE) return null
    return Backend(chosenModel())
  }

  private inner class Backend(private val model: String) : LlmBackend {
    override val label = "Cloud · ${model.substringAfterLast('/')}"

    override suspend fun generate(request: LlmRequest, onPartial: ((String) -> Unit)?): String =
      withContext(Dispatchers.IO) { complete(model, request, onPartial) }
  }

  private fun complete(model: String, request: LlmRequest, onPartial: ((String) -> Unit)?): String =
    if (provider.anthropic) completeAnthropic(model, request, onPartial) else completeOpenAi(model, request, onPartial)

  /** Anthropic's Messages API: the system prompt is separate, and replies stream as typed events. */
  private fun completeAnthropic(model: String, request: LlmRequest, onPartial: ((String) -> Unit)?): String {
    val messages = JSONArray().apply {
      request.turns.forEach { put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text)) }
    }
    val body = JSONObject().apply {
      put("model", model)
      put("max_tokens", request.maxOutputTokens)
      put("temperature", request.temperature)
      if (request.system.isNotBlank()) put("system", request.system + if (request.json) "\nReply with JSON only." else "")
      else if (request.json) put("system", "Reply with JSON only.")
      put("messages", messages)
      put("stream", onPartial != null)
    }.toString()
    return send("$baseUrl/messages", body, onPartial) { event ->
      val json = JSONObject(event)
      if (json.optString("type") == "content_block_delta") json.optJSONObject("delta")?.optString("text").orEmpty() else ""
    }.ifEmpty { "" }
  }

  private fun completeOpenAi(model: String, request: LlmRequest, onPartial: ((String) -> Unit)?): String {
    val messages = JSONArray().apply {
      if (request.system.isNotBlank()) put(JSONObject().put("role", "system").put("content", request.system))
      request.turns.forEach { put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text)) }
    }
    val body = JSONObject().apply {
      put("model", model)
      put("messages", messages)
      put("temperature", request.temperature)
      put("max_tokens", request.maxOutputTokens)
      put("stream", onPartial != null)
      if (request.json) put("response_format", JSONObject().put("type", "json_object"))
    }.toString()
    return send("$baseUrl/chat/completions", body, onPartial) { event ->
      JSONObject(event).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
    }
  }

  /**
   * Posts [body]; returns the whole reply, or with [onPartial] reads server-sent events, taking the
   * text of each with [deltaOf].
   */
  private fun send(url: String, body: String, onPartial: ((String) -> Unit)?, deltaOf: (String) -> String): String {
    countRequest()
    val call = http.newCall(authorized(Request.Builder().url(url)).post(body.toRequestBody(JSON)).build())
    call.execute().use { resp ->
      if (!resp.isSuccessful) {
        val text = resp.body?.string().orEmpty()
        if (resp.code == 429) {
          val wait = resp.header("retry-after")?.toDoubleOrNull()?.times(1000)?.toLong() ?: 60_000L
          pausedUntil = System.currentTimeMillis() + wait.coerceIn(5_000L, 6 * 3_600_000L)
        }
        throw LlmException(errorText(resp.code, text))
      }
      val source = resp.body ?: throw LlmException("Empty reply")
      if (onPartial == null) {
        val json = JSONObject(source.string())
        val text = if (provider.anthropic) {
          val parts = json.optJSONArray("content") ?: JSONArray()
          (0 until parts.length()).joinToString("") { parts.optJSONObject(it)?.optString("text").orEmpty() }
        } else {
          json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        }
        return stripThinking(text)
      }
      // Server-sent events: "data: {...}" lines, then "data: [DONE]".
      val out = StringBuilder()
      val reader = source.charStream().buffered()
      while (true) {
        val line = reader.readLine() ?: break
        if (!line.startsWith("data:")) continue
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") break
        val delta = runCatching { deltaOf(data) }.getOrDefault("")
        if (delta.isNotEmpty()) {
          out.append(delta)
          onPartial(stripThinking(out.toString()))
        }
      }
      return stripThinking(out.toString())
    }
  }

  private fun countRequest() = synchronized(this) {
    val day = today()
    if (day != usedDay) {
      usedDay = day
      usedToday = 0
    }
    usedToday++
  }

  private fun errorText(code: Int, body: String): String {
    val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()?.takeIf { it.isNotBlank() }
    return when (code) {
      401, 403 -> "The API key was rejected"
      429 -> "The free limit is used up for now"
      else -> message ?: "HTTP $code"
    }
  }

  private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

  companion object {
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val BACKGROUND_SHARE = 0.7
    private val NOT_CHAT = listOf("whisper", "tts", "guard", "embed", "orpheus", "playai")
    private val JSON = "application/json; charset=utf-8".toMediaType()
  }
}
