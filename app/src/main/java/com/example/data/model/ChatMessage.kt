package com.example.data.model

data class ChatMessage(
  val id: String = java.util.UUID.randomUUID().toString(),
  val role: String, // "user" or "model"
  val text: String,
  val timestamp: Long = System.currentTimeMillis(),
  val modelName: String = "gemini-2.5-flash",
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
    description = "Primary Cloud AI with Google Search grounding",
    iconBadge = "⚡ Flash"
  ),
  FLASH_LITE(
    modelId = "gemini-3.1-flash-lite-preview",
    displayName = "Flash-Lite 3.1",
    description = "Fast low-latency definitions and formulas",
    iconBadge = "🚀 Fast"
  ),
  PRO(
    modelId = "gemini-3.1-pro-preview",
    displayName = "Gemini 3.1 Pro",
    description = "Deep mathematical proofs, research & complex reasoning",
    iconBadge = "🧠 Pro"
  ),
  LOCAL_AI(
    modelId = "local-ai-academic",
    displayName = "Local Llama / Academic AI",
    description = "On-device Llama 3 & Deep Academic Engine (Zero vague answers)",
    iconBadge = "🦙 Local Llama"
  )
}
