package com.example.data.ai.llm

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/** Models that run on the phone with LiteRT-LM. Both are Apache-2.0 and download without an account. */
enum class PhoneModel(
  val displayName: String,
  val fileName: String,
  val url: String,
  val sizeBytes: Long,
  val minRamGb: Int,
  val summary: String
) {
  GEMMA_4_E2B(
    displayName = "Gemma 4 E2B",
    fileName = "gemma-4-E2B-it.litertlm",
    url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
    sizeBytes = 2_588_147_712L,
    minRamGb = 6,
    summary = "Best answers. For phones with 6 GB of RAM or more."
  ),
  QWEN_3_5_0_8B(
    displayName = "Qwen 3.5 0.8B",
    fileName = "Qwen3.5-0.8B_int8.litertlm",
    url = "https://huggingface.co/litert-community/Qwen3.5-0.8B/resolve/main/Qwen3.5-0.8B_int8.litertlm",
    sizeBytes = 964_657_152L,
    minRamGb = 3,
    summary = "Smaller and quicker, for older phones. Weaker at maths and long answers."
  )
}

sealed class DownloadState {
  object Idle : DownloadState()
  data class Running(val model: PhoneModel, val bytes: Long) : DownloadState() {
    val fraction: Float get() = (bytes.toDouble() / model.sizeBytes).toFloat().coerceIn(0f, 1f)
  }
  data class Failed(val model: PhoneModel, val reason: String) : DownloadState()
}

/** Downloads, stores and runs the on-device model. */
class PhoneModels(context: Context) {

  private val appContext = context.applicationContext
  private val dir = File(appContext.filesDir, "models").apply { mkdirs() }
  private val prefs = appContext.getSharedPreferences("studypilot_ai_config", Context.MODE_PRIVATE)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

  private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
  val download: StateFlow<DownloadState> = _download.asStateFlow()

  private val _installed = MutableStateFlow(scanInstalled())
  val installed: StateFlow<Set<PhoneModel>> = _installed.asStateFlow()

  private val _selected = MutableStateFlow(
    prefs.getString(KEY_SELECTED, null)?.let { name -> PhoneModel.entries.firstOrNull { it.name == name } }
  )
  /** The model the user picked; it is used once downloaded. */
  val selected: StateFlow<PhoneModel?> = _selected.asStateFlow()

  private var downloadJob: Job? = null

  private val engineLock = Mutex()
  private var engine: Engine? = null
  private var engineModel: PhoneModel? = null

  fun file(model: PhoneModel) = File(dir, model.fileName)
  private fun partFile(model: PhoneModel) = File(dir, model.fileName + ".part")

  private fun scanInstalled() = PhoneModel.entries.filter { file(it).length() == it.sizeBytes }.toSet()

  /** Total RAM in whole GB, to warn before downloading a model the phone can't run. */
  fun deviceRamGb(): Int {
    val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    return Math.round(info.totalMem / 1_073_741_824.0).toInt()
  }

  fun select(model: PhoneModel) {
    _selected.value = model
    prefs.edit().putString(KEY_SELECTED, model.name).apply()
  }

  /** The model to answer with: the selected one if downloaded, otherwise any downloaded model. */
  fun activeModel(): PhoneModel? = _selected.value?.takeIf { it in _installed.value } ?: _installed.value.firstOrNull()

  /** Starts (or resumes) downloading [model]. Progress is in [download]. */
  fun startDownload(model: PhoneModel) {
    if (_download.value is DownloadState.Running) return
    select(model)
    downloadJob = scope.launch {
      val part = partFile(model)
      try {
        val needed = model.sizeBytes - part.length() + SPACE_MARGIN_BYTES
        if (StatFs(dir.path).availableBytes < needed) {
          _download.value = DownloadState.Failed(model, "Not enough free space. ${formatSize(needed)} needed.")
          return@launch
        }
        var done = part.length()
        _download.value = DownloadState.Running(model, done)
        val request = Request.Builder().url(model.url).apply { if (done > 0) header("Range", "bytes=$done-") }.build()
        http.newCall(request).execute().use { resp ->
          if (resp.code == 200 && done > 0) done = 0 // server ignored the range: start over
          if (!resp.isSuccessful) throw LlmException("Download failed (HTTP ${resp.code})")
          val body = resp.body ?: throw LlmException("Empty download")
          FileOutputStream(part, done > 0).use { out ->
            body.byteStream().use { input ->
              val buffer = ByteArray(256 * 1024)
              var lastUpdate = 0L
              while (isActive) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                done += n
                if (done - lastUpdate > 4_000_000L) {
                  lastUpdate = done
                  _download.value = DownloadState.Running(model, done)
                }
              }
            }
          }
        }
        if (!isActive) return@launch
        if (part.length() != model.sizeBytes) throw LlmException("Download incomplete. Try again to resume.")
        if (!part.renameTo(file(model))) throw LlmException("Couldn't save the model file.")
        _installed.value = scanInstalled()
        _download.value = DownloadState.Idle
      } catch (e: Exception) {
        if (isActive) {
          Log.w(TAG, "Model download failed", e)
          _download.value = DownloadState.Failed(model, e.message ?: "Download failed. Try again to resume.")
        }
      }
    }
  }

  /** Stops the download; the partial file is kept so the next start resumes. */
  fun cancelDownload() {
    downloadJob?.cancel()
    downloadJob = null
    _download.value = DownloadState.Idle
  }

  suspend fun delete(model: PhoneModel) {
    engineLock.withLock {
      if (engineModel == model) closeEngine()
    }
    file(model).delete()
    partFile(model).delete()
    _installed.value = scanInstalled()
  }

  fun backendOrNull(): LlmBackend? {
    val model = activeModel() ?: return null
    return object : LlmBackend {
      override val label = "Phone · ${model.displayName}"
      override suspend fun generate(request: LlmRequest, onPartial: ((String) -> Unit)?) = run(model, request, onPartial)
    }
  }

  private suspend fun run(model: PhoneModel, request: LlmRequest, onPartial: ((String) -> Unit)?): String =
    withContext(Dispatchers.Default) {
      // One request at a time: the engine holds the model in memory and isn't safe to share.
      engineLock.withLock {
        val engine = loadEngine(model)
        val turns = fitToContext(request.turns)
        val config = ConversationConfig(
          systemInstruction = Contents.of(
            if (request.json) "${request.system}\nReply with valid JSON only, no other text." else request.system
          ),
          initialMessages = turns.dropLast(1).map { if (it.fromUser) Message.user(it.text) else Message.model(it.text) },
          samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = request.temperature),
          thinkingConfig = ThinkingConfig(enableThinking = false)
        )
        engine.createConversation(config).use { conversation ->
          val text = StringBuilder()
          conversation.sendMessageAsync(Message.user(turns.last().text)).collect { message ->
            val chunk = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            // Chunks are normally deltas; tolerate a runtime that sends the whole text so far.
            if (text.isNotEmpty() && chunk.startsWith(text)) text.setLength(0)
            text.append(chunk)
            onPartial?.invoke(stripThinking(text.toString()))
          }
          stripThinking(text.toString())
        }
      }
    }

  /** Keeps the conversation within the model's context: recent turns only, very long text trimmed. */
  private fun fitToContext(turns: List<LlmTurn>): List<LlmTurn> {
    val recent = turns.takeLast(MAX_TURNS).toMutableList()
    while (recent.size > 1 && recent.sumOf { it.text.length } > MAX_PROMPT_CHARS) recent.removeAt(0)
    val last = recent.last()
    if (last.text.length > MAX_PROMPT_CHARS) recent[recent.lastIndex] = last.copy(text = last.text.take(MAX_PROMPT_CHARS))
    return recent
  }

  private fun loadEngine(model: PhoneModel): Engine {
    engine?.let { if (engineModel == model) return it }
    closeEngine()
    val path = file(model).absolutePath
    val loaded = try {
      Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), maxNumTokens = CONTEXT_TOKENS, cacheDir = appContext.cacheDir.path))
        .also { it.initialize() }
    } catch (e: Exception) {
      Log.i(TAG, "GPU backend unavailable, using CPU", e)
      Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), maxNumTokens = CONTEXT_TOKENS, cacheDir = appContext.cacheDir.path))
        .also { it.initialize() }
    }
    engine = loaded
    engineModel = model
    return loaded
  }

  private fun closeEngine() {
    try {
      engine?.close()
    } catch (e: Exception) {
      Log.w(TAG, "Engine close failed", e)
    }
    engine = null
    engineModel = null
  }

  companion object {
    private const val TAG = "PhoneModels"
    private const val KEY_SELECTED = "phone_model"
    private const val SPACE_MARGIN_BYTES = 300_000_000L
    private const val CONTEXT_TOKENS = 4096
    private const val MAX_TURNS = 8
    private const val MAX_PROMPT_CHARS = 9_000 // roughly 2,500 tokens, leaving room for the reply

    fun formatSize(bytes: Long): String =
      if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"
  }
}
