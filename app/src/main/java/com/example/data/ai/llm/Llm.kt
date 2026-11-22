package com.example.data.ai.llm

/** One message in a conversation sent to a language model. */
data class LlmTurn(val fromUser: Boolean, val text: String)

data class LlmRequest(
  val system: String,
  /** Oldest first; the last turn is the user's message to answer. */
  val turns: List<LlmTurn>,
  val temperature: Double = 0.7,
  /** Ask for a JSON reply (quiz generation, grading, extraction). */
  val json: Boolean = false,
  val maxOutputTokens: Int = 1024
)

/** A model that can answer: Ollama on the user's PC, or a model running on the phone. */
interface LlmBackend {
  /** Short name shown under answers, e.g. "PC · qwen3:8b". */
  val label: String

  /** Returns the full reply. [onPartial] receives the text so far while it streams. */
  suspend fun generate(request: LlmRequest, onPartial: ((String) -> Unit)? = null): String
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

private val THINK_BLOCK = Regex("(?s)<think>.*?(</think>|$)")

/** Reasoning models wrap their scratch work in <think> tags; users only need the answer. */
fun stripThinking(text: String): String = THINK_BLOCK.replace(text, "").trim()

/** Pulls the first JSON array or object out of a reply that may include prose or code fences. */
fun extractJson(text: String): String? {
  val clean = stripThinking(text).removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
  val start = clean.indexOfFirst { it == '[' || it == '{' }
  if (start < 0) return null
  val close = if (clean[start] == '[') ']' else '}'
  val end = clean.lastIndexOf(close)
  return if (end > start) clean.substring(start, end + 1) else null
}
