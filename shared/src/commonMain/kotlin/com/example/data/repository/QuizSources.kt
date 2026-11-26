package com.example.data.repository

import com.example.data.local.StudyPilotDao
import com.example.data.model.ExtractedConcept
import com.example.data.model.ImportedChat
import com.example.data.model.StudyTopic
import com.example.data.model.TutorMessageEntity
import com.example.data.model.TutorSession
import com.example.platform.KeyValueStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Makes everything the student keeps quizzable. Quizzes are built from topics, so each AI Tutor
 * conversation, each imported chat nothing was extracted from, and each extracted concept without a
 * topic gets one. These topics carry a key in [StudyTopic.uploadedFileName] ("tutor:...", "chat:...",
 * "concept:...") so they're refreshed when the source changes and removed when it's deleted. A
 * topic the student deletes stays deleted.
 *
 * The phone and the PC can both create the topic for the same source before they sync; the copy
 * with the smallest sync id survives on both, so they settle on the same one.
 */
class QuizSources(
  private val dao: StudyPilotDao,
  private val prefs: KeyValueStore?,
  /** Sync ids of study_topics rows, for picking the same duplicate on every device. */
  private val stableIds: suspend (List<Long>) -> Map<Long, String>
) {
  private val lock = Mutex()

  suspend fun refresh(userId: String) = lock.withLock {
    val topics = dao.getTopicsForUser(userId).first()
    val concepts = dao.getExtractedConceptsForUser(userId).first()
    val dismissed = dismissedKeys(userId)

    val wanted = LinkedHashMap<String, StudyTopic>()
    for (session in dao.getTutorSessionsForUser(userId).first()) {
      val notes = tutorNotes(dao.getMessagesForSessionSync(session.id)) ?: continue
      wanted[tutorKey(session)] = topicFor(userId, session, notes)
    }
    val chatsWithConcepts = concepts.mapTo(HashSet()) { it.sourceChatId }
    for (chat in dao.getImportedChatsForUser(userId).first()) {
      if (chat.id in chatsWithConcepts) continue // its concepts are quizzed instead
      val notes = chat.rawConversation.trim().takeIf { it.length >= 100 }?.take(MAX_NOTES) ?: continue
      wanted[chatKey(chat)] = topicFor(userId, chat, notes)
    }
    val ownTitles = topics.filterNot { isAutoKey(it.uploadedFileName) }.mapTo(HashSet()) { it.title.lowercase(Locale.ROOT) }
    for (concept in concepts) {
      if (concept.conceptName.lowercase(Locale.ROOT) in ownTitles) continue
      wanted[conceptKey(concept)] = topicFor(userId, concept)
    }
    wanted.keys.removeAll(dismissed)

    val existing = topics.filter { isAutoKey(it.uploadedFileName) }.groupBy { it.uploadedFileName!! }
    val duplicated = existing.values.filter { it.size > 1 }.flatten().map { it.id }
    val uids = if (duplicated.isEmpty()) emptyMap() else stableIds(duplicated)

    for ((key, group) in existing) {
      val keep = group.minWith(compareBy<StudyTopic>({ uids[it.id] ?: "￿" }, { it.id }))
      group.filter { it.id != keep.id }.forEach { remove(it.id) }
      val target = wanted[key]
      when {
        target == null -> remove(keep.id) // the conversation, chat or concept is gone
        target.descriptionNotes != keep.descriptionNotes || target.title != keep.title || target.subjectName != keep.subjectName -> {
          dao.updateTopic(keep.copy(title = target.title, subjectId = target.subjectId, subjectName = target.subjectName, descriptionNotes = target.descriptionNotes))
          dao.deleteQuestionsForTopic(keep.id) // questions about the old content
        }
      }
    }
    val toInsert = wanted.filterKeys { it !in existing }.values.toList()
    if (toInsert.isNotEmpty()) dao.insertTopics(toInsert)
  }

  /** Call when the student deletes [topic], so a topic made from a source isn't made again. */
  fun onTopicDeleted(topic: StudyTopic) {
    val key = topic.uploadedFileName?.takeIf { isAutoKey(it) } ?: return
    val store = prefs ?: return
    val keys = dismissedKeys(topic.userId) + key
    store.edit { putString(dismissedPref(topic.userId), keys.joinToString("\n")) }
  }

  private suspend fun remove(topicId: Long) {
    dao.deleteQuestionsForTopic(topicId)
    dao.deleteTopicById(topicId)
  }

  private fun dismissedKeys(userId: String): Set<String> =
    prefs?.getString(dismissedPref(userId), null)?.split("\n")?.filter { it.isNotBlank() }?.toSet().orEmpty()

  private fun dismissedPref(userId: String) = "dismissed_quiz_sources_$userId"

  private fun topicFor(userId: String, session: TutorSession, notes: String): StudyTopic {
    val day = day(session.createdAt)
    return StudyTopic(
      userId = userId,
      subjectId = session.subjectId ?: 0L,
      subjectName = session.subjectName,
      title = session.title.ifBlank { "Tutor conversation" },
      dateStudied = day,
      descriptionNotes = notes,
      sourceType = TUTOR_CHAT,
      uploadedFileName = tutorKey(session),
      nextRevisionDate = day,
      aiSummary = "From an AI Tutor conversation"
    )
  }

  private fun topicFor(userId: String, chat: ImportedChat, notes: String): StudyTopic {
    val day = day(chat.importedAt)
    return StudyTopic(
      userId = userId,
      subjectId = chat.subjectId ?: 0L,
      subjectName = chat.subjectName,
      title = chat.title,
      dateStudied = day,
      descriptionNotes = notes,
      sourceType = "IMPORTED_CHAT",
      uploadedFileName = chatKey(chat),
      nextRevisionDate = day,
      aiSummary = "From an imported ${chat.aiPlatform} chat"
    )
  }

  private fun topicFor(userId: String, c: ExtractedConcept): StudyTopic {
    val day = day(c.extractedAt)
    return StudyTopic(
      userId = userId,
      subjectId = c.subjectId ?: 0L,
      subjectName = c.subjectName,
      title = c.conceptName,
      dateStudied = day,
      descriptionNotes = conceptNotes(c),
      sourceType = "IMPORTED_CHAT",
      uploadedFileName = conceptKey(c),
      nextRevisionDate = day,
      aiSummary = "Extracted from ${c.sourceChatTitle}"
    )
  }

  private fun day(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))

  companion object {
    const val TUTOR_CHAT = "TUTOR_CHAT"
    private const val MAX_NOTES = 4000

    fun isAutoKey(value: String?): Boolean =
      value != null && (value.startsWith("tutor:") || value.startsWith("chat:") || value.startsWith("concept:"))

    private fun tutorKey(s: TutorSession) = "tutor:${s.createdAt}"
    private fun chatKey(c: ImportedChat) = "chat:${c.importedAt}"
    private fun conceptKey(c: ExtractedConcept) = "concept:${c.extractedAt}:${c.conceptName}"

    /** The questions and answers of a tutor conversation, or null if it has no real answer yet. */
    fun tutorNotes(messages: List<TutorMessageEntity>): String? {
      val usable = messages.filterNot { it.isError || it.text.isBlank() }
      if (usable.none { it.role != "user" && it.text.trim().length >= 80 }) return null
      return buildString {
        for (m in usable) {
          if (m.role == "user") append("Q: ").append(m.text.trim().take(300)).append('\n')
          else append("A: ").append(m.text.trim().take(900)).append("\n\n")
        }
      }.trim().take(MAX_NOTES)
    }

    fun conceptNotes(c: ExtractedConcept): String = buildString {
      append(c.definition)
      if (c.technicalDetails.isNotBlank()) append("\n\nTechnical: ${c.technicalDetails}")
      if (c.formulaOrCode.isNotBlank()) append("\n\n```\n${c.formulaOrCode}\n```")
      if (c.keyFacts.isNotBlank()) append("\n\nKey facts: ${c.keyFacts}")
      if (c.misconceptionsAndCorrections.isNotBlank()) append("\n\nExam trap: ${c.misconceptionsAndCorrections}")
    }.take(MAX_NOTES)
  }
}
