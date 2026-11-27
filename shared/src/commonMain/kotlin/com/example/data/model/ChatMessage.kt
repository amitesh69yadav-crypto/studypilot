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
  val relatedTopics: List<String> = emptyList(),
  /** The tutor's opening line, which gets no follow-up buttons since there's nothing to follow up. */
  val isWelcome: Boolean = false
)

data class GroundingSource(
  val title: String,
  val url: String
)
