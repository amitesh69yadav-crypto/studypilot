package com.example.data.ai.llm

import kotlinx.coroutines.flow.StateFlow

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

/** Models that run on this device: download them, pick one, and get a backend to answer with. */
interface OnDeviceModels {
  val download: StateFlow<DownloadState>
  val installed: StateFlow<Set<PhoneModel>>
  /** The model the user picked; it is used once downloaded. */
  val selected: StateFlow<PhoneModel?>

  /** Total RAM in whole GB, to warn before downloading a model the device can't run. */
  fun deviceRamGb(): Int
  fun select(model: PhoneModel)
  fun activeModel(): PhoneModel?
  fun startDownload(model: PhoneModel)
  fun cancelDownload()
  suspend fun delete(model: PhoneModel)
  fun backendOrNull(): LlmBackend?

  companion object {
    fun formatSize(bytes: Long): String =
      if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"
  }
}
