package com.example.data.remote

import com.example.data.model.GroundingSource

data class ChatResponse(
  val text: String,
  val modelName: String,
  val groundingSources: List<GroundingSource> = emptyList(),
  val searchQueries: List<String> = emptyList(),
  val isError: Boolean = false,
  val relatedTopics: List<String> = emptyList()
)

data class EvaluationResult(
  val isCorrect: Boolean,
  val feedback: String
)
