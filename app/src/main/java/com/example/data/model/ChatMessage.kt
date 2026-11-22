package com.example.data.model

data class ChatMessage(
  val id: String = java.util.UUID.randomUUID().toString(),
  val role: String, // "user" or "model"
  val text: String,
  val timestamp: Long = System.currentTimeMillis(),
  val modelName: String = "",
  val searchGroundingUsed: Boolean = false,
  val groundingSources: List<GroundingSource> = emptyList(),
  val searchQueries: List<String> = emptyList(),
  val isError: Boolean = false,
  val relatedTopics: List<String> = emptyList()
)

data class GroundingSource(
  val title: String,
  val url: String
)

enum class TutorModel(
  val modelId: String,
  val displayName: String,
  val description: String,
  val iconBadge: String
) {
  FLASH(
    modelId = "gemini-3.8-flash",
    displayName = "Gemini 3.8 Flash",
    description = "Default. Fast, supports Google Search",
    iconBadge = "Flash"
  ),
  FLASH_LITE(
    modelId = "gemini-3.5-flash-lite",
    displayName = "Gemini 3.5 Flash-Lite",
    description = "Quickest, for short definitions and formulas",
    iconBadge = "Lite"
  ),
  PRO(
    modelId = "gemini-3.1-pro-preview",
    displayName = "Gemini 3.1 Pro",
    description = "Slower, better for proofs and multi-step problems",
    iconBadge = "Pro"
  ),
  LOCAL_AI(
    modelId = "local-ai-academic",
    displayName = "Offline",
    description = "No internet needed. Uses Ollama if running, otherwise built-in notes and Wikipedia",
    iconBadge = "Offline"
  )
}
