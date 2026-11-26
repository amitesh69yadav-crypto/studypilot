package com.example.data.ai.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to Ollama running on the user's computer (https://ollama.com).
 * The phone reaches it over Wi-Fi, so Ollama must listen on the network (OLLAMA_HOST=0.0.0.0).
 */
class OllamaClient(
  private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS) // a big model on a busy PC can pause between tokens
    .build(),
  private val probeHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(1500, TimeUnit.MILLISECONDS)
    .readTimeout(3, TimeUnit.SECONDS)
    .build()
) {

  /** e.g. "http://192.168.1.60:11434". Blank means not set up. */
  @Volatile var baseUrl: String = ""
    set(value) {
      field = value.trim().trimEnd('/').let { if (it.isNotBlank() && !it.startsWith("http")) "http://$it" else it }
        .let { if (it.isNotBlank() && !Regex(":\\d+$").containsMatchIn(it.substringAfter("://"))) "$it:$DEFAULT_PORT" else it }
      probe = null
    }

  /** Model to use; blank picks the first chat model installed. */
  @Volatile var preferredModel: String = ""

  data class Status(val reachable: Boolean, val models: List<String>, val error: String? = null)

  private class CachedProbe(val url: String, val status: Status, val at: Long)

  @Volatile private var probe: CachedProbe? = null

  /** Whether the PC answers, and which models it has. Cached briefly so each question doesn't re-probe. */
  suspend fun status(forceRefresh: Boolean = false): Status = withContext(Dispatchers.IO) {
    val url = baseUrl
    if (url.isBlank()) return@withContext Status(false, emptyList(), "No PC address set")
    probe?.let { if (!forceRefresh && it.url == url && System.currentTimeMillis() - it.at < PROBE_TTL_MS) return@withContext it.status }
    val status = fetchModels(url)
    probe = CachedProbe(url, status, System.currentTimeMillis())
    status
  }

  private fun fetchModels(url: String): Status = try {
    probeHttp.newCall(Request.Builder().url("$url/api/tags").build()).execute().use { resp ->
      if (!resp.isSuccessful) return Status(false, emptyList(), "HTTP ${resp.code}")
      val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("models") ?: JSONArray()
      Status(true, (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) })
    }
  } catch (e: Exception) {
    Status(false, emptyList(), e.message ?: e.javaClass.simpleName)
  }

  /** The model that will be used, or null if the PC can't be reached or has no chat model. */
  suspend fun chosenModel(): String? {
    val status = status()
    if (!status.reachable) return null
    val chatModels = status.models.filterNot { it.contains("embed", ignoreCase = true) }
    val wanted = preferredModel.trim()
    return chatModels.firstOrNull { it.equals(wanted, true) || (wanted.isNotBlank() && it.startsWith("$wanted:", true)) }
      ?: chatModels.firstOrNull()
  }

  suspend fun backendOrNull(): LlmBackend? {
    val model = chosenModel() ?: return null
    return Backend(model)
  }

  private inner class Backend(private val model: String) : LlmBackend {
    override val label = "PC · $model"

    override suspend fun generate(request: LlmRequest, onPartial: ((String) -> Unit)?): String =
      withContext(Dispatchers.IO) {
        try {
          chat(model, request, onPartial, sendThink = true)
        } catch (e: LlmException) {
          // Older Ollama versions and some models reject the "think" flag; retry without it.
          if (e.message?.contains("think", ignoreCase = true) == true) chat(model, request, onPartial, sendThink = false)
          else throw e
        }
      }
  }

  private fun chat(model: String, request: LlmRequest, onPartial: ((String) -> Unit)?, sendThink: Boolean): String {
    val messages = JSONArray().apply {
      if (request.system.isNotBlank()) put(JSONObject().put("role", "system").put("content", request.system))
      request.turns.forEach { put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("content", it.text)) }
    }
    val body = JSONObject().apply {
      put("model", model)
      put("messages", messages)
      put("stream", onPartial != null)
      if (sendThink) put("think", false)
      if (request.json) put("format", "json")
      put("keep_alive", "15m")
      put("options", JSONObject().put("temperature", request.temperature).put("num_predict", request.maxOutputTokens).put("num_ctx", 8192))
    }.toString()

    val call = http.newCall(
      Request.Builder().url("$baseUrl/api/chat").post(body.toRequestBody(JSON)).build()
    )
    try {
      call.execute().use { resp ->
        if (!resp.isSuccessful) {
          val err = resp.body?.string().orEmpty()
          throw LlmException("Ollama HTTP ${resp.code}: ${err.take(200)}")
        }
        val source = resp.body?.source() ?: throw LlmException("Empty reply from Ollama")
        if (onPartial == null) {
          return stripThinking(JSONObject(source.readUtf8()).optJSONObject("message")?.optString("content").orEmpty())
        }
        val text = StringBuilder()
        while (!source.exhausted()) {
          val line = source.readUtf8Line()?.takeIf { it.isNotBlank() } ?: continue
          val chunk = JSONObject(line)
          chunk.optString("error").takeIf { it.isNotBlank() }?.let { throw LlmException("Ollama: $it") }
          text.append(chunk.optJSONObject("message")?.optString("content").orEmpty())
          onPartial(stripThinking(text.toString()))
          if (chunk.optBoolean("done")) break
        }
        return stripThinking(text.toString())
      }
    } catch (e: LlmException) {
      throw e
    } catch (e: Exception) {
      probe = null // force a fresh reachability check next time
      throw LlmException("Lost connection to the PC: ${e.message}", e)
    }
  }

  /**
   * Looks for Ollama on this device, the Wi-Fi network [subnetPrefix] ("192.168.1"), and an emulator's host.
   * Returns its base URL, or null if nothing answers.
   */
  suspend fun findOnNetwork(subnetPrefix: String?): String? = withContext(Dispatchers.IO) {
    val candidates = mutableListOf("http://10.0.2.2:$DEFAULT_PORT", "http://127.0.0.1:$DEFAULT_PORT")
    subnetPrefix?.let { prefix ->
      (1..254).mapTo(candidates) { "http://$prefix.$it:$DEFAULT_PORT" }
    }
    val quick = OkHttpClient.Builder()
      .connectTimeout(400, TimeUnit.MILLISECONDS)
      .readTimeout(1500, TimeUnit.MILLISECONDS)
      .build()
    val limit = Semaphore(48)
    coroutineScope {
      candidates.map { url ->
        async {
          limit.withPermit {
            try {
              quick.newCall(Request.Builder().url("$url/api/tags").build()).execute().use { resp ->
                if (resp.isSuccessful && resp.body?.string().orEmpty().contains("models")) url else null
              }
            } catch (_: Exception) {
              null
            }
          }
        }
      }.awaitAll().firstOrNull { it != null }
    }
  }

  companion object {
    const val DEFAULT_PORT = 11434
    private const val PROBE_TTL_MS = 20_000L
    private val JSON = "application/json; charset=utf-8".toMediaType()
  }
}
