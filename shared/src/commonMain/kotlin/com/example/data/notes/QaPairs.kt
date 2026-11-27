package com.example.data.notes

/** One question and its answer, from a tutor chat or an imported conversation. */
data class QaPair(val question: String, val answer: String)

/** A conversation shown as Q&A in Notes: where it came from and its pairs. */
data class QaThread(
  val title: String,
  val subjectName: String,
  val source: String,
  val at: Long,
  val pairs: List<QaPair>,
  val url: String = ""
)

object QaPairs {
  /**
   * Pairs each question with the answer that follows it. [turns] are (fromUser, text) in order; a
   * question with no answer yet is kept with an empty answer, and answers with no question are dropped.
   */
  fun fromTurns(turns: List<Pair<Boolean, String>>): List<QaPair> {
    val out = mutableListOf<QaPair>()
    var question: String? = null
    val answer = StringBuilder()
    fun flush() {
      question?.let { out += QaPair(it.trim(), answer.toString().trim()) }
      question = null
      answer.clear()
    }
    for ((fromUser, text) in turns) {
      if (text.isBlank()) continue
      if (fromUser) {
        flush()
        question = text
      } else if (question != null) {
        if (answer.isNotEmpty()) answer.append("\n\n")
        answer.append(text)
      }
    }
    flush()
    return out
  }

  private val speaker = Regex("""^\s*(User|You|Me|Human|Question|Q|Assistant|ChatGPT|Claude|Gemini|DeepSeek|AI|Bot|Answer|A)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)
  private val userNames = setOf("user", "you", "me", "human", "question", "q")

  /** Splits an imported conversation ("User: … / Assistant: …") into question-and-answer pairs. */
  fun fromConversation(raw: String): List<QaPair> {
    val turns = mutableListOf<Pair<Boolean, StringBuilder>>()
    for (line in raw.lines()) {
      val m = speaker.find(line)
      if (m != null) {
        turns += (m.groupValues[1].lowercase() in userNames) to StringBuilder(m.groupValues[2])
      } else if (turns.isNotEmpty()) {
        turns.last().second.append('\n').append(line)
      }
    }
    return fromTurns(turns.map { it.first to it.second.toString() })
  }
}
