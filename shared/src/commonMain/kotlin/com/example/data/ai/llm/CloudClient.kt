package com.example.data.ai.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
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
      provider == CloudProvider.GEMINI && geminiBaseUrlForTests != null -> geminiBaseUrlForTests!! + "/openai"
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

  /** How long the provider asked to wait after a rate limit (0 when it can be used now). */
  fun pausedForMs(): Long = (pausedUntil - System.currentTimeMillis()).coerceAtLeast(0L)

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

  /** Models the provider refused ("does not exist", retired); skipped from then on. */
  private val refused = java.util.Collections.synchronizedSet(mutableSetOf<String>())

  /** Models the provider said are overloaded ("high demand"), until when; others are used meanwhile. */
  private val busyUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

  private fun usable(id: String) = id !in refused && (busyUntil[id] ?: 0L) < System.currentTimeMillis()

  fun chosenModel(): String {
    model.trim().takeIf { it.isNotBlank() && usable(it) }?.let { return it }
    val preferred = provider.preferredModels.filter { usable(it) }
    val available = models?.filter { usable(it) }
    return preferred.firstOrNull { available == null || it in available }
      ?: available?.let { newestFlash(it) }
      ?: available?.firstOrNull() ?: preferred.firstOrNull().orEmpty()
  }

  /**
   * Gemini retires models often (and new keys can't use older ones): the newest "gemini-N-flash" the
   * key lists (a stable one before a preview of the same version). Null for other providers or none.
   */
  private fun newestFlash(available: List<String>): String? {
    if (provider != CloudProvider.GEMINI) return null
    fun version(id: String) = Regex("""gemini-(\d+(?:\.\d+)?)-flash""").find(id)?.groupValues?.get(1)?.toDoubleOrNull()
    val flash = available.filter { Regex("""^gemini-\d+(\.\d+)?-flash(-preview(-[\w-]+)?)?$""").matches(it) }
    return flash.sortedWith(compareByDescending<String> { version(it) ?: 0.0 }.thenBy { it.contains("preview") }).firstOrNull()
  }

  /** Providers retire models; learn which ones this key can use before the first request. */
  private suspend fun ensureModels() {
    if (models == null && isConfigured) runCatching { status() }
  }

  private fun isOverloaded(message: String?): Boolean =
    message != null && Regex("(?i)high demand|overloaded|try again later|HTTP 50[23]").containsMatchIn(message)

  private fun isModelGone(message: String?): Boolean =
    message != null && Regex("(?i)model.*(does not exist|not found|decommissioned|deprecated|no longer (supported|available)|not available|not supported for|do not have access)|model_not_found|Daily limit reached for this model").containsMatchIn(message)

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

  inner class Backend(private val model: String) : LlmBackend {
    override val label = "Cloud · ${model.substringAfterLast('/')}"
    /** The key this model runs on (for its rate-limit pause). */
    val client: CloudClient get() = this@CloudClient

    override suspend fun generate(request: LlmRequest, onPartial: ((String) -> Unit)?): String = withContext(Dispatchers.IO) {
      ensureModels()
      // The model picked before the list was known may not be offered; use one that is.
      withWorkingModel(chosenModel().ifBlank { model }) { complete(it, request, onPartial) }
    }
  }

  /**
   * Runs [block] with [first], and when the provider says that model is gone (retired, "no longer
   * available to new users", not offered to this key) remembers that and tries the next one, a few times.
   */
  private suspend fun withWorkingModel(first: String, block: (String) -> String): String {
    var current = first
    var tries = 0
    while (true) {
      try {
        return block(current)
      } catch (e: Exception) {
        val timedOut = e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException
        val busy = timedOut || isOverloaded(e.message)
        if (!(busy || isModelGone(e.message)) || ++tries > 4) throw e
        if (busy) {
          // Overloaded for now: another of the key's models meanwhile, or this one again shortly.
          busyUntil[current] = System.currentTimeMillis() + 3 * 60_000L
        } else {
          refused += current
          models = null
          ensureModels()
        }
        val next = chosenModel()
        if (next.isBlank() || next in refused) throw e
        if (next == current) {
          // Only this model: after a timeout, waiting minutes more for it isn't worth it.
          if (timedOut) throw e
          kotlinx.coroutines.delay(10_000L * tries)
        }
        current = next
      }
    }
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

  // --- Whole PDFs, for the providers that read them (Gemini, Claude) ---

  /** Lets tests point Gemini's own API at a local fake. */
  @Volatile internal var geminiBaseUrlForTests: String? = null

  private val geminiBase: String
    get() = (geminiBaseUrlForTests ?: provider.baseUrl.removeSuffix("/openai")).trim().trimEnd('/')

  /** A whole document takes a while to read; allow it. */
  private val slowHttp: OkHttpClient by lazy { http.newBuilder().readTimeout(5, TimeUnit.MINUTES).writeTimeout(2, TimeUnit.MINUTES).build() }

  /** Whether this key can read a PDF of [bytes] itself (Gemini takes up to 50 MB, Claude about 24 MB). */
  fun canReadPdf(bytes: Int): Boolean = isConfigured && provider.readsPdf &&
    bytes <= if (provider == CloudProvider.GEMINI) 50_000_000 else 24_000_000

  /** A PDF ready to send. Gemini gets a big file uploaded once, so several requests can share it. */
  class PdfDoc internal constructor(internal val bytes: ByteArray, internal val geminiPart: JSONObject?)

  /** Gets [pdf] ready for [readPdf] (uploads it to Gemini's Files API when it's big). */
  suspend fun preparePdf(pdf: ByteArray, fileName: String): PdfDoc = withContext(Dispatchers.IO) {
    if (!canReadPdf(pdf.size)) throw LlmException("${provider.label} can't read this PDF")
    if (provider != CloudProvider.GEMINI) return@withContext PdfDoc(pdf, null)
    PdfDoc(
      pdf,
      if (pdf.size <= GEMINI_INLINE_BYTES) {
        JSONObject().put("inline_data", JSONObject().put("mime_type", "application/pdf").put("data", pdf.toByteString().base64()))
      } else {
        JSONObject().put("file_data", JSONObject().put("mime_type", "application/pdf").put("file_uri", geminiUpload(pdf, fileName)))
      }
    )
  }

  /**
   * Sends a PDF with [prompt] to Gemini or Claude, which read the pages themselves (layout, tables,
   * scanned pages, formulas), and returns the reply.
   */
  suspend fun readPdf(doc: PdfDoc, system: String, prompt: String, maxTokens: Int = 8_000): String = withContext(Dispatchers.IO) {
    if (System.currentTimeMillis() < pausedUntil) throw LlmException("The free limit is used up for now")
    if (model.isBlank()) ensureModels()
    withWorkingModel(chosenModel()) { m ->
      if (provider == CloudProvider.GEMINI) geminiPdf(m, doc.geminiPart!!, system, prompt, maxTokens)
      else anthropicPdf(m, doc.bytes, system, prompt, maxTokens)
    }
  }

  /** Little thinking: reading notes off a page doesn't need it, and it makes replies much slower. */
  private fun geminiThinking(model: String): JSONObject? = when {
    Regex("gemini-2\\.").containsMatchIn(model) -> JSONObject().put("thinkingBudget", 512)
    model.startsWith("gemini") -> JSONObject().put("thinkingLevel", "low")
    else -> null
  }

  private fun geminiPdf(model: String, document: JSONObject, system: String, prompt: String, maxTokens: Int): String {
    fun body(thinking: JSONObject?) = JSONObject()
      .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
      .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(document).put(JSONObject().put("text", prompt)))))
      .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", maxTokens).apply { thinking?.let { put("thinkingConfig", it) } })
    fun send(thinking: JSONObject?) = call(
      Request.Builder().url("$geminiBase/models/${model.removePrefix("models/")}:generateContent")
        .header("x-goog-api-key", apiKey).post(body(thinking).toString().toRequestBody(JSON)).build()
    ).first
    val thinking = geminiThinking(model)
    val reply = try {
      send(thinking)
    } catch (e: LlmException) {
      // A model that doesn't take this thinking setting: ask again without it.
      if (thinking == null || e.message?.contains("thinking", ignoreCase = true) != true) throw e
      send(null)
    }
    val parts = JSONObject(reply).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
    return stripThinking((0 until parts.length()).mapNotNull { parts.optJSONObject(it) }.filterNot { it.optBoolean("thought") }.joinToString("") { it.optString("text") })
  }

  /** Gemini's Files API: start a resumable upload, send the bytes, wait until the file is ready. */
  private fun geminiUpload(pdf: ByteArray, fileName: String): String {
    val start = call(
      Request.Builder().url(geminiBase.substringBefore("/v1beta") + "/upload/v1beta/files")
        .header("x-goog-api-key", apiKey)
        .header("X-Goog-Upload-Protocol", "resumable")
        .header("X-Goog-Upload-Command", "start")
        .header("X-Goog-Upload-Header-Content-Length", pdf.size.toString())
        .header("X-Goog-Upload-Header-Content-Type", "application/pdf")
        .post(JSONObject().put("file", JSONObject().put("display_name", fileName.take(100))).toString().toRequestBody(JSON))
        .build()
    )
    val uploadUrl = start.second["x-goog-upload-url"] ?: throw LlmException("Gemini didn't accept the upload")
    val done = call(
      Request.Builder().url(uploadUrl).header("X-Goog-Upload-Offset", "0").header("X-Goog-Upload-Command", "upload, finalize")
        .post(pdf.toRequestBody("application/pdf".toMediaType())).build()
    )
    var file = JSONObject(done.first).optJSONObject("file") ?: throw LlmException("Gemini didn't keep the file")
    for (i in 0 until 60) {
      if (file.optString("state", "ACTIVE") != "PROCESSING") break
      Thread.sleep(2_000)
      file = JSONObject(call(Request.Builder().url("$geminiBase/${file.optString("name")}").header("x-goog-api-key", apiKey).get().build()).first)
    }
    if (file.optString("state") == "FAILED") throw LlmException("Gemini couldn't read the file")
    return file.optString("uri").ifBlank { throw LlmException("Gemini didn't keep the file") }
  }

  private fun anthropicPdf(model: String, pdf: ByteArray, system: String, prompt: String, maxTokens: Int): String {
    val content = JSONArray()
      .put(JSONObject().put("type", "document").put("source", JSONObject().put("type", "base64").put("media_type", "application/pdf").put("data", pdf.toByteString().base64())))
      .put(JSONObject().put("type", "text").put("text", prompt))
    val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("temperature", 0.2).put("system", system)
      .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
    val reply = call(authorized(Request.Builder().url("$baseUrl/messages")).post(body.toString().toRequestBody(JSON)).build()).first
    val parts = JSONObject(reply).optJSONArray("content") ?: JSONArray()
    return stripThinking((0 until parts.length()).joinToString("") { parts.optJSONObject(it)?.optString("text").orEmpty() })
  }

  /** Sends [request] on the slow client; the reply body and headers, or the provider's reason. */
  private fun call(request: Request): Pair<String, okhttp3.Headers> {
    countRequest()
    slowHttp.newCall(request).execute().use { resp ->
      val text = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) {
        rateLimited(resp.code, resp.header("retry-after"), text)
        throw LlmException(errorText(resp.code, text))
      }
      return text to resp.headers
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
        rateLimited(resp.code, resp.header("retry-after"), text)
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

  /**
   * A 429: when one model's daily allowance is spent (free plans count per model), that model is
   * dropped for now and the next one is used; otherwise the key pauses as long as the provider asks.
   */
  private fun rateLimited(code: Int, retryAfter: String?, body: String) {
    if (code != 429) return
    if (Regex("(?i)per ?day|daily|RPD|TPD").containsMatchIn(body)) throw LlmException("Daily limit reached for this model")
    val wait = retryAfter?.toDoubleOrNull()?.times(1000)?.toLong() ?: 60_000L
    pausedUntil = System.currentTimeMillis() + wait.coerceIn(5_000L, 6 * 3_600_000L)
  }

  private fun errorText(code: Int, body: String): String {
    // Most send {"error": {...}}; Gemini's OpenAI-style API wraps it in a list.
    val message = runCatching {
      val json = if (body.trimStart().startsWith("[")) JSONArray(body).optJSONObject(0) else JSONObject(body)
      json?.optJSONObject("error")?.optString("message")
    }.getOrNull()?.takeIf { it.isNotBlank() }
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
    /** Gemini takes a request up to 20 MB; base64 makes the file a third bigger. */
    private const val GEMINI_INLINE_BYTES = 14_000_000
    private val NOT_CHAT = listOf("whisper", "tts", "guard", "embed", "orpheus", "playai")
    private val JSON = "application/json; charset=utf-8".toMediaType()
  }
}
