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
 * A free cloud model through an OpenAI-compatible API: Groq by default (no card needed), or any
 * provider with the same API, e.g. OpenRouter's ":free" models. The user brings their own key.
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
  @Volatile var baseUrl: String = DEFAULT_BASE_URL
    set(value) { field = value.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL } }
  @Volatile var apiKey: String = ""
  /** Blank picks [DEFAULT_MODELS] in order, or the first one the key can use. */
  @Volatile var model: String = ""

  /** Requests a day the free plan allows (Groq's free plan is around 1,000 for most models). */
  @Volatile var dailyBudget: Int = 1000

  @Volatile private var pausedUntil = 0L
  @Volatile private var usedDay = ""
  @Volatile private var usedToday = 0
  @Volatile private var models: List<String>? = null

  val isConfigured: Boolean get() = apiKey.isNotBlank()

  /** Requests sent today (UTC, when the free limits reset). */
  val requestsToday: Int get() = if (usedDay == today()) usedToday else 0

  data class Status(val ok: Boolean, val models: List<String>, val error: String? = null)

  /** Checks the key by listing the models it can use. */
  suspend fun status(): Status = withContext(Dispatchers.IO) {
    if (!isConfigured) return@withContext Status(false, emptyList(), "No API key")
    try {
      http.newCall(Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer $apiKey").build()).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) return@withContext Status(false, emptyList(), errorText(resp.code, body))
        val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
        val ids = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }
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
    val available = models
    return DEFAULT_MODELS.firstOrNull { available == null || it in available } ?: available?.firstOrNull() ?: DEFAULT_MODELS.first()
  }

  /**
   * The cloud model, or null when it isn't set up, is paused after a rate limit, or (for
   * [background] work) most of today's budget is used.
   */
  fun backendOrNull(background: Boolean = false): LlmBackend? {
    if (!isConfigured) return null
    if (System.currentTimeMillis() < pausedUntil) return null
    if (background && requestsToday >= dailyBudget * BACKGROUND_SHARE) return null
    return Backend(chosenModel())
  }

  private inner class Backend(private val model: String) : LlmBackend {
    override val label = "Cloud · ${model.substringAfterLast('/')}"

    override suspend fun generate(request: LlmRequest, onPartial: ((String) -> Unit)?): String =
      withContext(Dispatchers.IO) { complete(model, request, onPartial) }
  }

  private fun complete(model: String, request: LlmRequest, onPartial: ((String) -> Unit)?): String {
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
    countRequest()
    val call = http.newCall(
      Request.Builder().url("$baseUrl/chat/completions")
        .header("Authorization", "Bearer $apiKey")
        .post(body.toRequestBody(JSON))
        .build()
    )
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
        return stripThinking(json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty())
      }
      // Server-sent events: "data: {...}" lines, then "data: [DONE]".
      val out = StringBuilder()
      val reader = source.charStream().buffered()
      while (true) {
        val line = reader.readLine() ?: break
        if (!line.startsWith("data:")) continue
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") break
        val delta = runCatching {
          JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
        }.getOrDefault("")
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
      401 -> "The API key was rejected"
      429 -> "The free limit is used up for now"
      else -> message ?: "HTTP $code"
    }
  }

  private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

  companion object {
    const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"
    /** Tried in order when no model is chosen: large, fast, on Groq's free plan. */
    val DEFAULT_MODELS = listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.1-8b-instant")
    private const val BACKGROUND_SHARE = 0.7
    private val NOT_CHAT = listOf("whisper", "tts", "guard", "embed", "orpheus", "playai")
    private val JSON = "application/json; charset=utf-8".toMediaType()
  }
}
