package com.example.data.repository

import com.example.data.ai.LocalAiEngine
import com.example.data.ai.ChatKnowledgeExtractor
import com.example.data.local.StudyPilotDao
import com.example.data.model.ExtractedConcept
import com.example.data.model.ImportedChat
import com.example.data.model.QuizAttempt
import com.example.data.model.QuizConfig
import com.example.data.model.QuizQuestion
import com.example.data.model.StudyTask
import com.example.data.model.StudyTopic
import com.example.data.model.Subject
import com.example.data.model.TimetableSession
import com.example.data.model.TutorMessageEntity
import com.example.data.model.TutorSession
import com.example.data.remote.EvaluationResult
import com.example.data.ai.StudyAi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.example.platform.CloudStore
import com.example.platform.KeyValueStore

private fun Map<String, Any?>.str(key: String): String? = this[key] as? String
private fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean


class StudyPilotRepository(
  private val dao: StudyPilotDao,
  /** Remembers which profiles already got (or deleted) sample data. Null in tests. */
  private val prefs: KeyValueStore? = null,
  /** Cloud backup for Google-signed-in users; [CloudStore.None] when unavailable. */
  private val cloud: CloudStore = CloudStore.None,
  val ai: StudyAi = StudyAi(),
  /** Sync ids of study_topics rows; see [QuizSources]. */
  stableTopicIds: suspend (List<Long>) -> Map<Long, String> = { emptyMap() }
) {

  /** Turns tutor conversations, imported chats and concepts into quiz topics. */
  val quizSources = QuizSources(dao, prefs, stableTopicIds)

  fun markSampleDataDeleted(userId: String) {
    val targetUserId = userId.ifBlank { "guest_user" }
    prefs?.edit {
      putBoolean("sample_data_deleted_$targetUserId", true)
      putBoolean("has_seeded_sample_data_$targetUserId", true)
    }
  }

  suspend fun deleteAllSampleData(userId: String) {
    val targetUserId = userId.ifBlank { "guest_user" }
    markSampleDataDeleted(targetUserId)
    deleteUserData(targetUserId)
  }

  private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
  private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

  fun getTodayDateString(): String = dateFormat.format(Date())

  fun getSubjectsForUser(userId: String): Flow<List<Subject>> = dao.getSubjectsForUser(userId)
  fun getSessionsForUser(userId: String): Flow<List<TimetableSession>> = dao.getSessionsForUser(userId)
  fun getTopicsForUser(userId: String): Flow<List<StudyTopic>> = dao.getTopicsForUser(userId)
  fun getTasksForUser(userId: String): Flow<List<StudyTask>> = dao.getTasksForUser(userId)
  fun getQuizAttemptsForUser(userId: String): Flow<List<QuizAttempt>> = dao.getQuizAttemptsForUser(userId)
  fun getTutorSessionsForUser(userId: String): Flow<List<TutorSession>> = dao.getTutorSessionsForUser(userId)
  fun getScheduleFormatsForUser(userId: String): Flow<List<com.example.data.model.ScheduleFormat>> = dao.getScheduleFormatsForUser(userId)

  /**
   * Cloud sync only applies to the cloud user who is signed in on this device (Google sign-in).
   * Firestore rules allow each user to touch only users/{their uid}, so anything else would be rejected.
   */
  fun isCloudUser(userId: String): Boolean = userId.isNotBlank() && cloud.currentUserId() == userId

  private fun syncToFirestore(path: String, data: Map<String, Any?>) {
    if (!isCloudUser(path.removePrefix("users/").substringBefore("/"))) return
    cloud.set(path, data)
  }

  suspend fun getSessionById(id: Long): TimetableSession? = dao.getSessionById(id)

  fun saveUserProfile(user: com.example.data.model.UserProfile) {
    syncToFirestore(
      "users/${user.id}",
      mapOf(
        "id" to user.id,
        "name" to user.name,
        "email" to user.email,
        "role" to user.role,
        "university" to user.university,
        "studentId" to user.studentId,
        "avatarInitials" to user.avatarInitials,
        "authProvider" to user.authProvider,
        "lastLoginTime" to user.lastLoginTime
      )
    )
  }

  private fun deleteFromFirestore(path: String) {
    if (!isCloudUser(path.removePrefix("users/").substringBefore("/"))) return
    cloud.delete(path)
  }

  suspend fun deleteUserData(userId: String) {
    dao.deleteQuestionsForUser(userId)
    dao.deleteSubjectsForUser(userId)
    dao.deleteSessionsForUser(userId)
    dao.deleteTopicsForUser(userId)
    dao.deleteTasksForUser(userId)
    dao.deleteQuizAttemptsForUser(userId)
    dao.clearTutorHistoryForUser(userId)
    dao.deleteScheduleFormatsForUser(userId)
    dao.deleteExtractedConceptsForUser(userId)
    dao.deleteImportedChatsForUser(userId)
    if (!isCloudUser(userId)) return
    cloud.delete("users/$userId")
    for (collection in CLOUD_COLLECTIONS) cloud.deleteCollection("users/$userId/$collection")
  }

  /** Copies cloud subjects, sessions and tasks this device doesn't have yet. Returns false if the cloud was unreachable. */
  suspend fun pullUserDataFromFirestore(userId: String): Boolean {
    if (!isCloudUser(userId)) return false
    return try {
      coroutineScope {
        val subjectsDeferred = async { cloud.list("users/$userId/subjects") }
        val sessionsDeferred = async { cloud.list("users/$userId/sessions") }
        val tasksDeferred = async { cloud.list("users/$userId/tasks") }
        val localSubjectsDeferred = async { dao.getSubjectsForUser(userId).first() }
        val localSessionsDeferred = async { dao.getSessionsListForUser(userId) }
        val localTasksDeferred = async { dao.getTasksForUser(userId).first() }

        val subjectsSnap = subjectsDeferred.await()
        if (subjectsSnap.isNotEmpty()) {
          val currentCodes = localSubjectsDeferred.await().mapTo(HashSet()) { it.code }
          val newSubjects = subjectsSnap.mapNotNull { doc ->
            val name = doc.str("name") ?: ""
            val code = doc.str("code") ?: ""
            val color = doc.str("colorHex") ?: "#4F6D7A"
            if (name.isNotBlank() && !currentCodes.contains(code)) {
              Subject(userId = userId, name = name, code = code, colorHex = color)
            } else null
          }
          if (newSubjects.isNotEmpty()) dao.insertSubjects(newSubjects)
        }

        val sessionsSnap = sessionsDeferred.await()
        if (sessionsSnap.isNotEmpty()) {
          val currentKeys = localSessionsDeferred.await().mapTo(HashSet()) { "${it.date}_${it.startTime}_${it.subjectName}" }
          val newSessions = sessionsSnap.mapNotNull { doc ->
            val date = doc.str("date") ?: ""
            val startTime = doc.str("startTime") ?: ""
            val subjectName = doc.str("subjectName") ?: ""
            val key = "${date}_${startTime}_${subjectName}"
            if (date.isNotBlank() && !currentKeys.contains(key)) {
              TimetableSession(
                userId = userId,
                subjectId = doc.str("subjectId")?.toLongOrNull() ?: 0L,
                subjectName = subjectName,
                topic = doc.str("topic") ?: "",
                date = date,
                startTime = startTime,
                endTime = doc.str("endTime") ?: "",
                location = doc.str("location") ?: "",
                notes = doc.str("notes") ?: "",
                recurringDays = doc.str("recurringDays") ?: "",
                isAlarmEnabled = doc.bool("isAlarmEnabled") ?: doc.bool("alarmEnabled") ?: true
              )
            } else null
          }
          if (newSessions.isNotEmpty()) dao.insertSessions(newSessions)
        }

        val tasksSnap = tasksDeferred.await()
        if (tasksSnap.isNotEmpty()) {
          val currentTitles = localTasksDeferred.await().mapTo(HashSet()) { it.title }
          val todayStr = getTodayDateString()
          val newTasks = tasksSnap.mapNotNull { doc ->
            val title = doc.str("title") ?: ""
            if (title.isNotBlank() && !currentTitles.contains(title)) {
              StudyTask(
                userId = userId,
                subjectName = doc.str("subjectName") ?: "General Studies",
                title = title,
                dueDate = doc.str("dueDate") ?: todayStr,
                type = doc.str("type") ?: doc.str("taskType") ?: "ASSIGNMENT",
                isCompleted = doc.bool("isCompleted") ?: false,
                priority = doc.str("priority") ?: "MEDIUM"
              )
            } else null
          }
          if (newTasks.isNotEmpty()) dao.insertTasks(newTasks)
        }
      }
      true
    } catch (e: Exception) {
      com.example.platform.Log.w(TAG, "Cloud pull failed", e)
      false
    }
  }

  // --- CRUD Operations ---
  suspend fun addSubject(name: String, code: String, colorHex: String, userId: String = ""): Long {
    val id = dao.insertSubject(Subject(userId = userId, name = name, code = code, colorHex = colorHex))
    if (userId.isNotBlank()) {
      syncToFirestore("users/$userId/subjects/$id", mapOf(
        "id" to id.toString(),
        "userId" to userId,
        "name" to name,
        "code" to code,
        "colorHex" to colorHex,
        "createdAt" to System.currentTimeMillis()
      ))
    }
    return id
  }

  suspend fun deleteSubject(subject: Subject) {
    dao.deleteSubject(subject)
    if (subject.userId.isNotBlank()) {
      deleteFromFirestore("users/${subject.userId}/subjects/${subject.id}")
      markSampleDataDeleted(subject.userId)
    }
  }

  private fun sessionDocument(session: TimetableSession, id: Long) = mapOf(
    "id" to id.toString(),
    "userId" to session.userId,
    "subjectId" to session.subjectId.toString(),
    "subjectName" to session.subjectName,
    "topic" to session.topic,
    "date" to session.date,
    "startTime" to session.startTime,
    "endTime" to session.endTime,
    "location" to session.location,
    "notes" to session.notes,
    "recurringDays" to session.recurringDays,
    "status" to session.status,
    "isAlarmEnabled" to session.isAlarmEnabled
  )

  suspend fun addSession(session: TimetableSession): Long {
    val id = dao.insertSession(session)
    syncToFirestore("users/${session.userId}/sessions/$id", sessionDocument(session, id))
    return id
  }

  suspend fun updateSession(session: TimetableSession) {
    dao.updateSession(session)
    syncToFirestore("users/${session.userId}/sessions/${session.id}", sessionDocument(session, session.id))
  }

  suspend fun deleteSession(id: Long, userId: String = "") {
    dao.deleteSessionById(id)
    if (userId.isNotBlank()) {
      deleteFromFirestore("users/$userId/sessions/$id")
      markSampleDataDeleted(userId)
    }
  }

  suspend fun addSessions(sessions: List<TimetableSession>) {
    if (sessions.isEmpty()) return
    dao.insertSessions(sessions)
  }

  suspend fun clearSessionsForDate(date: String, userId: String = "") {
    if (userId.isNotBlank()) {
      dao.deleteSessionsForDateAndUser(userId, date)
    } else {
      dao.deleteSessionsForDate(date)
    }
  }

  suspend fun replaceSessionsForDate(date: String, sessions: List<TimetableSession>, userId: String = "") {
    if (userId.isNotBlank()) {
      dao.replaceSessionsForDateAndUser(userId, date, sessions)
    } else {
      dao.replaceSessionsForDate(date, sessions)
    }
  }

  suspend fun addScheduleFormat(format: com.example.data.model.ScheduleFormat): Long {
    return dao.insertScheduleFormat(format)
  }

  suspend fun deleteScheduleFormat(id: Long) {
    dao.deleteScheduleFormatById(id)
  }

  suspend fun addTopic(topic: StudyTopic): Long {
    val id = dao.insertTopic(topic)
    if (topic.userId.isNotBlank()) {
      syncToFirestore("users/${topic.userId}/topics/$id", mapOf(
        "id" to id.toString(),
        "userId" to topic.userId,
        "subjectId" to topic.subjectId.toString(),
        "subjectName" to topic.subjectName,
        "title" to topic.title,
        "dateStudied" to topic.dateStudied,
        "masteryLevel" to topic.masteryLevel,
        "descriptionNotes" to topic.descriptionNotes
      ))
    }
    return id
  }

  suspend fun updateTopic(topic: StudyTopic) {
    dao.updateTopic(topic)
    if (topic.userId.isNotBlank()) {
      syncToFirestore("users/${topic.userId}/topics/${topic.id}", mapOf(
        "id" to topic.id.toString(),
        "userId" to topic.userId,
        "subjectId" to topic.subjectId.toString(),
        "subjectName" to topic.subjectName,
        "title" to topic.title,
        "dateStudied" to topic.dateStudied,
        "masteryLevel" to topic.masteryLevel,
        "descriptionNotes" to topic.descriptionNotes
      ))
    }
  }

  suspend fun deleteTopic(id: Long, userId: String = "") {
    dao.getTopicById(id)?.let { quizSources.onTopicDeleted(it) }
    dao.deleteTopicById(id)
    if (userId.isNotBlank()) {
      deleteFromFirestore("users/$userId/topics/$id")
      markSampleDataDeleted(userId)
    }
  }

  suspend fun addTask(task: StudyTask): Long {
    val id = dao.insertTask(task)
    if (task.userId.isNotBlank()) {
      syncToFirestore("users/${task.userId}/tasks/$id", mapOf(
        "id" to id.toString(),
        "userId" to task.userId,
        "title" to task.title,
        "subjectName" to task.subjectName,
        "dueDate" to task.dueDate,
        "isCompleted" to task.isCompleted,
        "priority" to task.priority
      ))
    }
    return id
  }

  suspend fun updateTask(task: StudyTask) {
    dao.updateTask(task)
    if (task.userId.isNotBlank()) {
      syncToFirestore("users/${task.userId}/tasks/${task.id}", mapOf(
        "id" to task.id.toString(),
        "userId" to task.userId,
        "title" to task.title,
        "subjectName" to task.subjectName,
        "dueDate" to task.dueDate,
        "isCompleted" to task.isCompleted,
        "priority" to task.priority
      ))
    }
  }

  suspend fun deleteTask(id: Long, userId: String = "") {
    dao.deleteTaskById(id)
    if (userId.isNotBlank()) {
      deleteFromFirestore("users/$userId/tasks/$id")
      markSampleDataDeleted(userId)
    }
  }

  // --- AI Summarization & Chat ---
  suspend fun summarizeMaterial(title: String, rawContent: String): String {
    return ai.summarizeMaterial(title, rawContent)
  }

  suspend fun chatWithTutor(
    history: List<com.example.data.model.ChatMessage>,
    systemInstruction: String,
    subjectContext: String? = null,
    onPartial: ((String) -> Unit)? = null
  ): com.example.data.remote.ChatResponse {
    return ai.chat(history, systemInstruction, subjectContext, onPartial)
  }

  // --- AI Tutor History & Persistence ---
  fun getTutorSessionsForSubject(subjectId: Long): Flow<List<TutorSession>> {
    return dao.getTutorSessionsForSubject(subjectId)
  }

  suspend fun getTutorSessionById(id: Long): TutorSession? {
    return dao.getTutorSessionById(id)
  }

  suspend fun getMessagesForSessionSync(sessionId: Long): List<TutorMessageEntity> {
    return dao.getMessagesForSessionSync(sessionId)
  }

  fun getMessagesForSession(sessionId: Long): Flow<List<TutorMessageEntity>> {
    return dao.getMessagesForSession(sessionId)
  }

  suspend fun createTutorSession(
    subjectId: Long?,
    subjectName: String,
    title: String,
    previewSnippet: String,
    modelUsed: String,
    searchGroundingUsed: Boolean,
    userId: String = ""
  ): Long {
    val session = TutorSession(
      userId = userId,
      subjectId = subjectId,
      subjectName = subjectName,
      title = title,
      previewSnippet = previewSnippet,
      modelUsed = modelUsed,
      searchGroundingUsed = searchGroundingUsed,
      messageCount = 0,
      createdAt = System.currentTimeMillis(),
      lastUpdatedAt = System.currentTimeMillis()
    )
    val id = dao.insertTutorSession(session)
    if (userId.isNotBlank()) {
      syncToFirestore("users/$userId/tutorSessions/$id", mapOf(
        "id" to id.toString(),
        "userId" to userId,
        "title" to title,
        "subjectName" to subjectName,
        "createdAt" to System.currentTimeMillis()
      ))
    }
    return id
  }

  suspend fun saveTutorMessage(
    sessionId: Long,
    role: String,
    text: String,
    modelName: String,
    searchGroundingUsed: Boolean,
    groundingSourcesJson: String = "",
    searchQueriesJson: String = "",
    isError: Boolean = false
  ): Long {
    val msgId = dao.insertTutorMessage(
      TutorMessageEntity(
        sessionId = sessionId,
        role = role,
        text = text,
        timestamp = System.currentTimeMillis(),
        modelName = modelName,
        searchGroundingUsed = searchGroundingUsed,
        groundingSourcesJson = groundingSourcesJson,
        searchQueriesJson = searchQueriesJson,
        isError = isError
      )
    )
    val session = dao.getTutorSessionById(sessionId)
    if (session != null) {
      val snippet = if (role == "model") text.take(160) else session.previewSnippet
      dao.updateTutorSession(
        session.copy(
          messageCount = session.messageCount + 1,
          previewSnippet = snippet,
          lastUpdatedAt = System.currentTimeMillis()
        )
      )
    }
    return msgId
  }

  suspend fun deleteTutorSession(sessionId: Long) {
    dao.deleteTutorSessionAtomic(sessionId)
  }

  suspend fun clearTutorHistory(userId: String) {
    dao.clearTutorHistoryForUser(userId)
  }

  // --- AI Chat History Import & Intelligent Learning Memory ---
  fun getImportedChatsForUser(userId: String): Flow<List<ImportedChat>> {
    return dao.getImportedChatsForUser(userId)
  }

  fun getExtractedConceptsForUser(userId: String): Flow<List<ExtractedConcept>> {
    return dao.getExtractedConceptsForUser(userId)
  }

  fun getConceptsForChat(userId: String, chatId: Long): Flow<List<ExtractedConcept>> {
    return dao.getConceptsForChat(userId, chatId)
  }

  suspend fun saveImportedChatWithConcepts(
    userId: String,
    chat: ImportedChat,
    drafts: List<ChatKnowledgeExtractor.ExtractedConceptDraft>
  ): Long {
    val chatId = dao.insertImportedChat(chat.copy(userId = userId, extractedConceptsCount = drafts.size))

    val conceptsToInsert = drafts.map { draft ->
      ExtractedConcept(
        userId = userId,
        sourceChatId = chatId,
        sourceChatTitle = chat.title,
        subjectId = chat.subjectId,
        subjectName = chat.subjectName,
        topic = draft.topic,
        subtopic = draft.subtopic,
        conceptName = draft.conceptName,
        definition = draft.definition,
        technicalDetails = draft.technicalDetails,
        formulaOrCode = draft.formulaOrCode,
        problemsSolved = draft.problemsSolved,
        keyFacts = draft.keyFacts,
        misconceptionsAndCorrections = draft.misconceptionsAndCorrections,
        importanceScore = draft.importanceScore,
        tags = draft.tags.ifBlank { chat.tags },
        relatedConceptsJson = org.json.JSONArray(draft.relatedConcepts).toString()
      )
    }

    if (conceptsToInsert.isNotEmpty()) {
      dao.insertExtractedConcepts(conceptsToInsert)

      // Synchronize with StudyTopics in batch so they integrate into Topic browser and Quiz system
      val today = getTodayDateString()
      val userTopicsByLowerTitle = dao.getTopicsForUser(userId).first()
        .associateBy { it.title.lowercase(Locale.ROOT) }
      val topicsToUpdate = mutableListOf<StudyTopic>()
      val topicsToInsert = mutableListOf<StudyTopic>()
      val aiSummaryTag = "Extracted from ${chat.title} (${chat.aiPlatform})"

      for (c in conceptsToInsert) {
        val existingTopic = userTopicsByLowerTitle[c.conceptName.lowercase(Locale.ROOT)]
        val notes = buildString {
          append(c.definition)
          if (c.technicalDetails.isNotBlank()) append("\n\n• Technical: ${c.technicalDetails}")
          if (c.formulaOrCode.isNotBlank()) append("\n\n```\n${c.formulaOrCode}\n```")
          if (c.misconceptionsAndCorrections.isNotBlank()) append("\n\n⚠️ Exam Trap: ${c.misconceptionsAndCorrections}")
        }
        if (existingTopic != null) {
          topicsToUpdate.add(
            existingTopic.copy(
              descriptionNotes = notes,
              aiSummary = aiSummaryTag
            )
          )
        } else {
          topicsToInsert.add(
            StudyTopic(
              userId = userId,
              subjectId = c.subjectId ?: 0L,
              subjectName = c.subjectName,
              title = c.conceptName,
              dateStudied = today,
              descriptionNotes = notes,
              sourceType = "IMPORTED_CHAT",
              uploadedFileName = chat.title,
              nextRevisionDate = today,
              aiSummary = aiSummaryTag
            )
          )
        }
      }
      if (topicsToUpdate.isNotEmpty()) dao.updateTopics(topicsToUpdate)
      if (topicsToInsert.isNotEmpty()) dao.insertTopics(topicsToInsert)
    }

    return chatId
  }

  suspend fun resolveOrCreateSubjectForImport(
    userId: String,
    title: String,
    rawText: String,
    sourceUrl: String,
    discoveredTopics: List<String>,
    manualSubject: Subject? = null
  ): Subject {
    if (manualSubject != null) return manualSubject

    val existingSubjects = dao.getSubjectsForUser(userId).first()
    val suggestion = ChatKnowledgeExtractor.detectBestSubject(
      title = title,
      rawText = rawText,
      sourceUrl = sourceUrl,
      discoveredTopics = discoveredTopics,
      existingSubjects = existingSubjects
    )
    if (suggestion.matchedSubject != null) {
      return suggestion.matchedSubject
    }

    val existingByName = existingSubjects.firstOrNull {
      it.name.equals(suggestion.suggestedName, ignoreCase = true)
    }
    if (existingByName != null) {
      return existingByName
    }

    val newSubId = addSubject(
      name = suggestion.suggestedName,
      code = suggestion.suggestedCode,
      colorHex = suggestion.suggestedColorHex,
      userId = userId
    )
    return Subject(
      id = newSubId,
      userId = userId,
      name = suggestion.suggestedName,
      code = suggestion.suggestedCode,
      colorHex = suggestion.suggestedColorHex
    )
  }

  suspend fun moveImportedChatToSubject(
    userId: String,
    chat: ImportedChat,
    targetSubject: Subject
  ) {
    dao.updateImportedChat(
      chat.copy(
        subjectId = targetSubject.id,
        subjectName = targetSubject.name
      )
    )
    dao.updateConceptsSubjectForChat(
      chatId = chat.id,
      subjectId = targetSubject.id,
      subjectName = targetSubject.name
    )
    val topicsToUpdate = dao.getTopicsForUser(userId).first()
      .filter { it.sourceType == "IMPORTED_CHAT" && it.uploadedFileName == chat.title }
      .map { it.copy(subjectId = targetSubject.id, subjectName = targetSubject.name) }
    if (topicsToUpdate.isNotEmpty()) {
      dao.updateTopics(topicsToUpdate)
    }
  }

  suspend fun updateImportedChat(chat: ImportedChat) {
    dao.updateImportedChat(chat)
  }

  suspend fun deleteImportedChat(userId: String, chatId: Long) {
    val chat = dao.getImportedChatById(chatId)
    val chatTitle = chat?.title ?: ""
    dao.deleteImportedChatAtomic(chatId)
    if (chatTitle.isNotBlank()) {
      val topics = dao.getTopicsForUser(userId).first()
      for (t in topics) {
        if (t.sourceType == "IMPORTED_CHAT" && t.uploadedFileName == chatTitle) {
          dao.deleteTopicById(t.id)
        }
      }
    }
  }

  suspend fun updateExtractedConcept(concept: ExtractedConcept) {
    dao.updateExtractedConcept(concept)
  }

  suspend fun deleteExtractedConcept(conceptId: Long) {
    dao.deleteExtractedConceptById(conceptId)
  }

  // --- Quiz Generation & Assembly ---
  data class GeneratedQuiz(
    val questions: List<QuizQuestion>,
    val testedTopics: List<StudyTopic>,
    val queuedUntestedTopics: List<StudyTopic>,
    val isAllCovered: Boolean
  )

  suspend fun assembleWeekendQuiz(
    config: QuizConfig,
    filterSubjectId: Long? = null,
    forceWeakOnly: Boolean = false,
    userId: String = ""
  ): GeneratedQuiz {
    // Everything stored (tutor conversations, imported chats, concepts) becomes quiz material.
    if (userId.isNotBlank()) runCatching { quizSources.refresh(userId) }
    val allTopicsList = if (userId.isNotBlank()) {
      dao.getTopicsForUser(userId).first()
    } else {
      dao.getAllTopics().first()
    }
    if (allTopicsList.isEmpty()) {
      return GeneratedQuiz(emptyList(), emptyList(), emptyList(), isAllCovered = true)
    }

    var eligibleTopics = if (filterSubjectId != null) {
      allTopicsList.filter { it.subjectId == filterSubjectId }
    } else {
      allTopicsList
    }

    // Filter by questionSource: ALL, MANUAL, IMPORTED_CHAT, UPLOAD
    eligibleTopics = when (config.questionSource) {
      "MANUAL" -> eligibleTopics.filter { it.sourceType == "MANUAL" || it.sourceType == "PASTED" }
      "IMPORTED_CHAT" -> eligibleTopics.filter { it.sourceType == "IMPORTED_CHAT" }
      QuizSources.TUTOR_CHAT -> eligibleTopics.filter { it.sourceType == QuizSources.TUTOR_CHAT }
      "UPLOAD" -> eligibleTopics.filter { it.sourceType == "UPLOAD" }
      else -> eligibleTopics
    }

    if (eligibleTopics.isEmpty()) {
      return GeneratedQuiz(emptyList(), emptyList(), emptyList(), isAllCovered = true)
    }

    // Sort topics by priority:
    // 1. Weak / Struggling topics
    // 2. Overdue / Never tested topics
    // 3. Spaced repetition interval reached
    val sortedTopics = eligibleTopics.sortedWith(
      compareByDescending<StudyTopic> { topic ->
        var priorityScore = 0
        if (topic.masteryLevel == "STRUGGLING") priorityScore += 50
        if (topic.timesTested == 0) priorityScore += 40
        if (topic.timesTested > 0 && topic.timesCorrect < topic.timesTested) priorityScore += 30
        if (topic.nextRevisionDate <= getTodayDateString()) priorityScore += 20
        priorityScore
      }.thenBy { it.lastTestedDate ?: "1970-01-01" }
    )

    val targetTopicList = if (forceWeakOnly) {
      sortedTopics.filter { it.masteryLevel == "STRUGGLING" || it.timesCorrect < it.timesTested }
        .ifEmpty { sortedTopics }
    } else {
      sortedTopics
    }

    val maxQuestions = config.questionCount.coerceAtLeast(1)
    val questionsPerTopic = if (targetTopicList.size >= maxQuestions) 1 else (maxQuestions / targetTopicList.size).coerceAtLeast(1)
    // 10 questions over 6 topics is 2,2,2,2,1,1, not 1 each.
    val oneMoreFor = if (targetTopicList.size >= maxQuestions) 0 else maxQuestions % targetTopicList.size

    val targetIds = targetTopicList.map { it.id }
    val cachedByTopicId = if (targetIds.isNotEmpty()) {
      // Placeholder questions saved when no model was reachable are dropped, so a model can do better now.
      val (placeholders, usable) = dao.getQuestionsForTopicIds(targetIds).partition { LocalAiEngine.isGenericTemplate(it) }
      if (placeholders.isNotEmpty()) dao.deleteQuestionsByIds(placeholders.map { it.id })
      usable.groupBy { it.topicId }
    } else emptyMap()

    val importedTitles = targetTopicList
      .filter { it.sourceType == "IMPORTED_CHAT" }
      .map { it.title }
      .distinct()
    // Topics made from a chat are named after the concept, so match on the concept's name.
    val conceptsByTopicTitle = if (importedTitles.isNotEmpty()) {
      dao.getConceptsByNames(userId, importedTitles).associateBy { it.conceptName }
    } else emptyMap()

    // Plan slots greedily up front so missing topics can be generated in parallel
    data class TopicPlan(
      val topic: StudyTopic,
      val cachedUsable: List<QuizQuestion>,
      val stillNeeded: Int
    )

    val plans = mutableListOf<TopicPlan>()
    var plannedCount = 0
    var firstUnplannedIndex = 0
    for ((index, topic) in targetTopicList.withIndex()) {
      if (plannedCount >= maxQuestions) {
        firstUnplannedIndex = index
        break
      }
      val cached = cachedByTopicId[topic.id].orEmpty()
      val usableCached = cached.filter { q ->
        (config.difficulty == "MIXED" || q.difficulty.equals(config.difficulty, ignoreCase = true)) &&
          (config.questionTypes.isEmpty() || config.questionTypes.contains(q.questionType))
      }
      val needed = (maxQuestions - plannedCount).coerceAtMost(questionsPerTopic + if (index < oneMoreFor) 1 else 0)
      val takeCached = usableCached.take(needed)
      val stillNeeded = needed - takeCached.size
      plans.add(TopicPlan(topic, takeCached, stillNeeded))
      plannedCount += takeCached.size + stillNeeded
      firstUnplannedIndex = index + 1
    }

    val allNewlyGenerated = mutableListOf<QuizQuestion>()
    val generatedByTopicId: Map<Long, List<QuizQuestion>> = coroutineScope {
      plans.filter { it.stillNeeded > 0 }.map { plan ->
        async {
          val matchingConcept = if (plan.topic.sourceType == "IMPORTED_CHAT") {
            conceptsByTopicTitle[plan.topic.title]
          } else null
          val generated = if (matchingConcept != null) {
            // Filed under the topic so they're reused next time and count towards its mastery.
            ai.generateQuestionsForConcept(matchingConcept, plan.stillNeeded, config.difficulty).map { it.copy(topicId = plan.topic.id) }
          } else {
            ai.generateQuestionsForTopic(plan.topic, plan.stillNeeded, config.difficulty)
          }
          plan.topic.id to generated
        }
      }.awaitAll().toMap()
    }

    val selectedTopics = mutableListOf<StudyTopic>()
    val assembledQuestions = mutableListOf<QuizQuestion>()

    for (plan in plans) {
      val gen = generatedByTopicId[plan.topic.id].orEmpty()
      if (gen.isNotEmpty()) allNewlyGenerated.addAll(gen)
      val chosen = plan.cachedUsable + gen.take(plan.stillNeeded)
      if (chosen.isNotEmpty()) {
        selectedTopics.add(plan.topic)
        assembledQuestions.addAll(chosen)
      }
    }

    // Backfill if any planned topic returned fewer questions than requested
    var idx = firstUnplannedIndex
    while (assembledQuestions.size < maxQuestions && idx < targetTopicList.size) {
      val topic = targetTopicList[idx++]
      val usableCached = cachedByTopicId[topic.id].orEmpty().filter { q ->
        (config.difficulty == "MIXED" || q.difficulty.equals(config.difficulty, ignoreCase = true)) &&
          (config.questionTypes.isEmpty() || config.questionTypes.contains(q.questionType))
      }
      val needed = (maxQuestions - assembledQuestions.size).coerceAtMost(questionsPerTopic)
      val chosenForTopic = usableCached.take(needed).toMutableList()
      val stillNeeded = needed - chosenForTopic.size
      if (stillNeeded > 0) {
        val matchingConcept = if (topic.sourceType == "IMPORTED_CHAT") {
          conceptsByTopicTitle[topic.title]
        } else null
        val generated = if (matchingConcept != null) {
          ai.generateQuestionsForConcept(matchingConcept, stillNeeded, config.difficulty).map { it.copy(topicId = topic.id) }
        } else {
          ai.generateQuestionsForTopic(topic, stillNeeded, config.difficulty)
        }
        if (generated.isNotEmpty()) {
          allNewlyGenerated.addAll(generated)
          chosenForTopic.addAll(generated.take(stillNeeded))
        }
      }
      if (chosenForTopic.isNotEmpty()) {
        selectedTopics.add(topic)
        assembledQuestions.addAll(chosenForTopic)
      }
    }

    if (allNewlyGenerated.isNotEmpty()) {
      dao.insertQuestions(allNewlyGenerated)
    }

    val selectedIds = selectedTopics.map { it.id }.toSet()
    val queuedUntested = eligibleTopics.filter { !selectedIds.contains(it.id) }
    val isAllCovered = queuedUntested.isEmpty()

    return GeneratedQuiz(
      questions = assembledQuestions,
      testedTopics = selectedTopics,
      queuedUntestedTopics = queuedUntested,
      isAllCovered = isAllCovered
    )
  }

  suspend fun evaluateUserAnswer(
    question: QuizQuestion,
    userAnswer: String
  ): EvaluationResult {
    return ai.evaluateAnswer(question, userAnswer)
  }

  // --- Spaced Repetition Updates ---
  suspend fun recordQuizResults(
    title: String,
    questions: List<QuizQuestion>,
    userAnswers: Map<Long, String>,
    evaluations: Map<Long, EvaluationResult>,
    topicsCoveredCount: Int,
    topicsSkippedCount: Int,
    userId: String = ""
  ): QuizAttempt {
    val total = questions.size
    val correctCount = questions.count { q ->
      evaluations[q.id]?.isCorrect == true
    }
    val scorePercentage = if (total > 0) (correctCount * 100 / total) else 0
    val today = getTodayDateString()

    // Group question results by topic to update spaced repetition
    val topicResults = mutableMapOf<Long, MutableList<Boolean>>()
    for (q in questions) {
      val isCorrect = evaluations[q.id]?.isCorrect ?: false
      topicResults.getOrPut(q.topicId) { mutableListOf() }.add(isCorrect)
    }

    val topicIds = topicResults.keys.toList()
    val topicsById = if (topicIds.isNotEmpty()) {
      dao.getTopicsByIds(topicIds).associateBy { it.id }
    } else emptyMap()
    val topicTitles = topicsById.values.map { it.title }.distinct()
    val conceptsByTopicTitle = if (topicTitles.isNotEmpty()) {
      dao.getConceptsByTopics(userId, topicTitles).associateBy { it.topic }
    } else emptyMap()

    val updatedTopics = mutableListOf<StudyTopic>()
    val updatedConcepts = mutableListOf<ExtractedConcept>()

    for ((topicId, results) in topicResults) {
      val topic = topicsById[topicId] ?: continue
      val allCorrect = results.all { it }
      val totalTested = topic.timesTested + results.size
      val totalCorrect = topic.timesCorrect + results.count { it }

      // Spaced repetition interval algorithm: 1d -> 3d -> 7d -> 14d -> 30d
      val newInterval = if (allCorrect) {
        when (topic.repetitionIntervalDays) {
          1 -> 3
          3 -> 7
          7 -> 14
          14 -> 30
          else -> 30
        }
      } else {
        1 // Reset to 1 day on mistake
      }

      val newMastery = when {
        !allCorrect -> "STRUGGLING"
        totalCorrect >= 3 -> "MASTERED"
        else -> "FAMILIAR"
      }

      val cal = Calendar.getInstance()
      cal.add(Calendar.DAY_OF_YEAR, newInterval)
      val nextRevDate = dateFormat.format(cal.time)

      updatedTopics.add(
        topic.copy(
          timesTested = totalTested,
          timesCorrect = totalCorrect,
          repetitionIntervalDays = newInterval,
          masteryLevel = newMastery,
          nextRevisionDate = nextRevDate,
          lastTestedDate = today
        )
      )

      val matchingConcept = conceptsByTopicTitle[topic.title]
      if (matchingConcept != null) {
        updatedConcepts.add(
          matchingConcept.copy(
            timesTested = matchingConcept.timesTested + results.size,
            timesCorrect = matchingConcept.timesCorrect + results.count { it },
            masteryLevel = newMastery,
            lastTestedDate = today,
            nextRevisionDate = nextRevDate
          )
        )
      }
    }

    if (updatedTopics.isNotEmpty()) dao.updateTopics(updatedTopics)
    if (updatedConcepts.isNotEmpty()) dao.updateExtractedConcepts(updatedConcepts)

    val attempt = QuizAttempt(
      userId = userId,
      title = title,
      dateAttempted = dateTimeFormat.format(Date()),
      totalQuestions = total,
      correctCount = correctCount,
      scorePercentage = scorePercentage,
      topicsCoveredCount = topicsCoveredCount,
      topicsSkippedCount = topicsSkippedCount,
      detailsJson = ""
    )
    val id = dao.insertQuizAttempt(attempt)
    if (userId.isNotBlank()) {
      syncToFirestore("users/$userId/quizAttempts/$id", mapOf(
        "id" to id.toString(),
        "userId" to userId,
        "scorePercentage" to scorePercentage,
        "totalQuestions" to total,
        "correctAnswers" to correctCount
      ))
    }
    return attempt.copy(id = id)
  }

  // --- Seed Initial University Data ---
  suspend fun seedSampleUniversityData(userId: String = "", force: Boolean = false) {
    val targetUserId = userId.ifBlank { "guest_user" }
    val seededKey = "has_seeded_sample_data_$targetUserId"
    val deletedKey = "sample_data_deleted_$targetUserId"

    if (!force) {
      if (prefs?.getBoolean(deletedKey, false) == true) {
        // User explicitly deleted sample data. NEVER re-seed automatically!
        return
      }
      if (prefs?.getBoolean(seededKey, false) == true) {
        // Already seeded for this user. Do NOT auto-re-seed.
        return
      }
      val currentLocal = dao.getSubjectsForUser(targetUserId).first()
      if (currentLocal.isNotEmpty()) {
        prefs?.edit { putBoolean(seededKey, true) }
        return
      }
    }

    // Mark as seeded
    prefs?.edit {
      putBoolean(seededKey, true)
      putBoolean(deletedKey, false)
    }

    val isMed = targetUserId.contains("med", ignoreCase = true)
    val isEng = targetUserId.contains("eng", ignoreCase = true)

    val csSub = when {
      isMed -> Subject(userId = targetUserId, name = "Human Physiology & Anatomy", code = "MED101", colorHex = "#D86343")
      isEng -> Subject(userId = targetUserId, name = "Thermodynamics & Fluids", code = "ME201", colorHex = "#D86343")
      else -> Subject(userId = targetUserId, name = "Data Structures & Algorithms", code = "CS201", colorHex = "#D86343")
    }
    val mathSub = when {
      isMed -> Subject(userId = targetUserId, name = "Biochemistry & Genetics", code = "BIO220", colorHex = "#F69176")
      isEng -> Subject(userId = targetUserId, name = "Materials Science & Statics", code = "ME210", colorHex = "#F69176")
      else -> Subject(userId = targetUserId, name = "Linear Algebra & Calculus", code = "MATH220", colorHex = "#F69176")
    }
    val physSub = when {
      isMed -> Subject(userId = targetUserId, name = "Medical Pharmacology", code = "PHARM310", colorHex = "#EBCBB0")
      isEng -> Subject(userId = targetUserId, name = "Robotics & Control Systems", code = "ME340", colorHex = "#EBCBB0")
      else -> Subject(userId = targetUserId, name = "Classical & Quantum Physics", code = "PHYS140", colorHex = "#EBCBB0")
    }
    val engSub = when {
      isMed -> Subject(userId = targetUserId, name = "Clinical Evidence & Biostats", code = "MED240", colorHex = "#E9F4EE")
      isEng -> Subject(userId = targetUserId, name = "Computer-Aided Design (CAD)", code = "ME115", colorHex = "#E9F4EE")
      else -> Subject(userId = targetUserId, name = "Software Engineering Principles", code = "CS310", colorHex = "#E9F4EE")
    }

    val csId = dao.insertSubject(csSub)
    val mathId = dao.insertSubject(mathSub)
    val physId = dao.insertSubject(physSub)
    val engId = dao.insertSubject(engSub)

    val today = getTodayDateString()

    // Sessions
    val sessions = listOf(
      TimetableSession(
        userId = targetUserId,
        subjectId = csId,
        subjectName = csSub.name,
        topic = if (isMed) "Cardiovascular & Respiratory Mechanisms" else if (isEng) "First Law of Thermodynamics & Heat Cycles" else "Binary Search Trees & AVL Rotations",
        date = today,
        startTime = "09:00",
        endTime = "10:30",
        location = if (isMed) "Medical School Lecture Hall A" else if (isEng) "Engineering Lab 3" else "Hall C, Turing Building",
        notes = "Bring lecture notes and review slides.",
        recurringDays = "Mon,Wed,Fri",
        reminderMinutes = 0,
        status = "SCHEDULED"
      ),
      TimetableSession(
        userId = targetUserId,
        subjectId = mathId,
        subjectName = mathSub.name,
        topic = if (isMed) "Enzyme Kinetics & Cellular Respiration" else if (isEng) "Stress-Strain Tensors & Hooke's Law" else "Eigenvalues & Eigenvector Transformations",
        date = today,
        startTime = "11:00",
        endTime = "12:30",
        location = "Lecture Theatre 2",
        notes = "Weekly assignment problem sets.",
        recurringDays = "Tue,Thu",
        reminderMinutes = 0,
        status = "SCHEDULED"
      ),
      TimetableSession(
        userId = targetUserId,
        subjectId = physId,
        subjectName = physSub.name,
        topic = if (isMed) "Receptor Agonists & Pharmacokinetics" else if (isEng) "PID Control Loops & Transfer Functions" else "Wave-Particle Duality & Schrödinger Eq",
        date = today,
        startTime = "14:00",
        endTime = "15:30",
        location = "Specialized Research Lab",
        notes = "Laboratory experiment and group collaboration.",
        recurringDays = "Wed",
        reminderMinutes = 0,
        status = "SCHEDULED"
      )
    )
    dao.insertSessions(sessions)

    // Default Pre-built Schedule Formats
    val standardDayJson = org.json.JSONArray().apply {
      put(org.json.JSONObject().apply {
        put("subjectId", csId)
        put("subjectName", csSub.name)
        put("topic", "Core Computer Science Lecture & Lab")
        put("startTime", "09:00")
        put("endTime", "10:30")
        put("location", "Hall C, Turing Building")
        put("notes", "Morning lecture & live coding lab")
        put("isAlarmEnabled", true)
      })
      put(org.json.JSONObject().apply {
        put("subjectId", mathId)
        put("subjectName", mathSub.name)
        put("topic", "Theoretical Proofs & Practice Exercises")
        put("startTime", "11:00")
        put("endTime", "12:30")
        put("location", "Lecture Theatre 2")
        put("notes", "Matrix diagonalization & homework review")
        put("isAlarmEnabled", true)
      })
      put(org.json.JSONObject().apply {
        put("subjectId", physId)
        put("subjectName", physSub.name)
        put("topic", "Laboratory Experiment & Group Problem Set")
        put("startTime", "14:00")
        put("endTime", "15:30")
        put("location", "Physics Lab 4")
        put("notes", "Hands-on measurement and lab report prep")
        put("isAlarmEnabled", true)
      })
      put(org.json.JSONObject().apply {
        put("subjectId", csId)
        put("subjectName", "Study Sprint")
        put("topic", "Active Recall & Evening AI Revision")
        put("startTime", "16:30")
        put("endTime", "18:00")
        put("location", "Library Quiet Area")
        put("notes", "Practice questions with AI tutor")
        put("isAlarmEnabled", true)
      })
    }.toString()

    val deepWorkJson = org.json.JSONArray().apply {
      put(org.json.JSONObject().apply {
        put("subjectId", csId)
        put("subjectName", "Exam Focus")
        put("topic", "Deep Work Sprint 1: Past Papers & Quizzes")
        put("startTime", "09:30")
        put("endTime", "11:30")
        put("location", "Home Study Desk")
        put("notes", "Zero distraction exam conditions")
        put("isAlarmEnabled", true)
      })
      put(org.json.JSONObject().apply {
        put("subjectId", mathId)
        put("subjectName", "Problem Solving")
        put("topic", "Deep Work Sprint 2: Hard Problem Sets")
        put("startTime", "13:00")
        put("endTime", "15:00")
        put("location", "University Library Floor 3")
        put("notes", "Step-by-step proofs and calculations")
        put("isAlarmEnabled", true)
      })
      put(org.json.JSONObject().apply {
        put("subjectId", engId)
        put("subjectName", "Review & Consolidation")
        put("topic", "Deep Work Sprint 3: AI Tutor Doubt Clearing")
        put("startTime", "16:00")
        put("endTime", "17:30")
        put("location", "Quiet Study Pod")
        put("notes", "Review weak areas with AI tutor")
        put("isAlarmEnabled", true)
      })
    }.toString()

    val lightReviewJson = org.json.JSONArray().apply {
      put(org.json.JSONObject().apply {
        put("subjectId", csId)
        put("subjectName", "Light Study")
        put("topic", "Morning Reading & Concept Mapping")
        put("startTime", "10:00")
        put("endTime", "11:30")
        put("location", "Campus Café")
        put("notes", "High-level summary review")
        put("isAlarmEnabled", true)
      })
      put(org.json.JSONObject().apply {
        put("subjectId", mathId)
        put("subjectName", "Light Study")
        put("topic", "Afternoon Problem Solving Warmup")
        put("startTime", "15:00")
        put("endTime", "16:30")
        put("location", "Student Lounge")
        put("notes", "Quick drill questions")
        put("isAlarmEnabled", true)
      })
    }.toString()

    dao.insertScheduleFormat(
      com.example.data.model.ScheduleFormat(
        userId = targetUserId,
        name = "Standard Academic Day",
        description = "Balanced 4-session day: Morning Lectures, Afternoon Lab, Evening Review",
        formatSessionsJson = standardDayJson
      )
    )
    dao.insertScheduleFormat(
      com.example.data.model.ScheduleFormat(
        userId = targetUserId,
        name = "Exam Prep Deep Work",
        description = "3 intensive 2-hour revision intervals with active recall & alarms",
        formatSessionsJson = deepWorkJson
      )
    )
    dao.insertScheduleFormat(
      com.example.data.model.ScheduleFormat(
        userId = targetUserId,
        name = "Weekend Light Revision",
        description = "2 relaxed study blocks for consolidating concepts and notes",
        formatSessionsJson = lightReviewJson
      )
    )

    // Topics
    val topics = listOf(
      StudyTopic(
        userId = targetUserId,
        subjectId = csId,
        subjectName = csSub.name,
        title = if (isMed) "Cardiovascular Pressure & Heart Dynamics" else if (isEng) "Carnot Cycle & Entropy Variations" else "AVL Tree Rotations & Self-Balancing",
        dateStudied = today,
        descriptionNotes = if (isMed) "Systemic vascular resistance, Frank-Starling law, and arterial blood pressure regulation." else if (isEng) "Reversible thermodynamic processes, isentropic expansions, and maximum theoretical engine efficiency." else "Self-balancing binary search trees maintain O(log n) height using Left-Left, Right-Right, Left-Right, and Right-Left rotations when balance factor exceeds +/-1.",
        sourceType = "MANUAL",
        masteryLevel = "FAMILIAR",
        repetitionIntervalDays = 3,
        nextRevisionDate = today,
        timesTested = 2,
        timesCorrect = 2,
        lastTestedDate = today
      ),
      StudyTopic(
        userId = targetUserId,
        subjectId = mathId,
        subjectName = mathSub.name,
        title = if (isMed) "Glycolysis & Citric Acid Cycle Pathways" else if (isEng) "Mohr's Circle & Principal Stress Analysis" else "Eigenvalues and Characteristic Equations",
        dateStudied = today,
        descriptionNotes = if (isMed) "Glucose oxidation, pyruvate conversion, electron transport chain ATP yield." else if (isEng) "2D state of stress transformations, shear stress maxima, and failure criteria." else "Roots of det(A - lambda*I) = 0 give the eigenvalues. Geometric multiplicity versus algebraic multiplicity determines matrix diagonalizability.",
        sourceType = "PASTED",
        masteryLevel = "UNTESTED",
        repetitionIntervalDays = 1,
        nextRevisionDate = today,
        timesTested = 0,
        timesCorrect = 0,
        lastTestedDate = null
      ),
      StudyTopic(
        userId = targetUserId,
        subjectId = physId,
        subjectName = physSub.name,
        title = if (isMed) "Pharmacodynamics & Dose-Response Curves" else if (isEng) "Second-Order System Damping & Frequency Response" else "Conservation of Angular Momentum",
        dateStudied = today,
        descriptionNotes = if (isMed) "EC50, efficacy vs potency, competitive vs noncompetitive antagonists." else if (isEng) "Underdamped vs critically damped systems, natural frequency, Bode gain and phase margin." else "When net external torque on a system is zero, total angular momentum L = I*omega remains constant throughout motion.",
        sourceType = "MANUAL",
        masteryLevel = "MASTERED",
        repetitionIntervalDays = 14,
        nextRevisionDate = today,
        timesTested = 4,
        timesCorrect = 4,
        lastTestedDate = today
      ),
      StudyTopic(
        userId = targetUserId,
        subjectId = engId,
        subjectName = engSub.name,
        title = if (isMed) "Clinical Trial Protocols & Ethics" else if (isEng) "Finite Element Method & Solid Mechanics" else "SOLID Architecture Principles",
        dateStudied = today,
        descriptionNotes = if (isMed) "Double-blind randomized controlled trials, placebo controls, informed consent." else if (isEng) "Mesh discretization, stiffness matrix formulation, boundary condition solving." else "Single Responsibility, Open/Closed, Liskov Substitution, Interface Segregation, and Dependency Inversion. Foundation of clean decoupled architecture.",
        sourceType = "MANUAL",
        masteryLevel = "FAMILIAR",
        repetitionIntervalDays = 7,
        nextRevisionDate = today,
        timesTested = 2,
        timesCorrect = 2,
        lastTestedDate = today
      )
    )
    dao.insertTopics(topics)

    // Initial questions for instant weekend quiz
    val allInsertedTopics = dao.getTopicsForUser(targetUserId).first()
    val questions = mutableListOf<QuizQuestion>()
    for (t in allInsertedTopics) {
      if (t.title.contains("AVL")) {
        questions.add(
          QuizQuestion(
            topicId = t.id,
            subjectName = t.subjectName,
            topicTitle = t.title,
            questionType = "MULTIPLE_CHOICE",
            difficulty = "MEDIUM",
            questionText = "What is the worst-case search time complexity in an AVL Tree with N elements?",
            optionsJson = "O(log N),O(N),O(N log N),O(1)",
            correctAnswer = "O(log N)",
            explanation = "Because AVL trees enforce a strict balance factor difference <= 1, tree height is guaranteed O(log N)."
          )
        )
        questions.add(
          QuizQuestion(
            topicId = t.id,
            subjectName = t.subjectName,
            topicTitle = t.title,
            questionType = "TRUE_FALSE",
            difficulty = "EASY",
            questionText = "True or False: An AVL tree rotation takes O(1) constant time to rebalance a node.",
            optionsJson = "True,False",
            correctAnswer = "True",
            explanation = "Rotations simply rearrange child and parent pointers, taking strictly O(1) operations."
          )
        )
      } else if (t.title.contains("Dijkstra")) {
        questions.add(
          QuizQuestion(
            topicId = t.id,
            subjectName = t.subjectName,
            topicTitle = t.title,
            questionType = "MULTIPLE_CHOICE",
            difficulty = "HARD",
            questionText = "Why does standard Dijkstra's algorithm fail on graphs with negative edge weights?",
            optionsJson = "It assumes once a node is visited its distance is finalized,Min-heap doesn't support negative values,Negative weights cause cycles,Graph vertices become disconnected",
            correctAnswer = "It assumes once a node is visited its distance is finalized",
            explanation = "Dijkstra greedily marks vertices finalized; a negative weight later could invalidate already finalized shortest paths."
          )
        )
        questions.add(
          QuizQuestion(
            topicId = t.id,
            subjectName = t.subjectName,
            topicTitle = t.title,
            questionType = "CODING",
            difficulty = "HARD",
            questionText = "Implement the distance relaxation step in Dijkstra's algorithm.",
            optionsJson = "",
            correctAnswer = "dist[v] = dist[u] + weight",
            explanation = "If dist[u] + weight < dist[v], update dist[v] and push into priority queue.",
            codeStarter = "fun relax(u: Int, v: Int, weight: Int, dist: MutableMap<Int, Int>) {\n    // Write relaxation logic:\n    if (dist.getValue(u) + weight < dist.getOrDefault(v, Int.MAX_VALUE)) {\n        dist[v] = dist.getValue(u) + weight\n    }\n}"
          )
        )
      } else if (t.title.contains("Eigenvalues")) {
        questions.add(
          QuizQuestion(
            topicId = t.id,
            subjectName = t.subjectName,
            topicTitle = t.title,
            questionType = "SHORT_ANSWER",
            difficulty = "MEDIUM",
            questionText = "What equation is solved to determine the eigenvalues lambda of an n x n matrix A?",
            optionsJson = "",
            correctAnswer = "det(A - lambda*I) = 0",
            explanation = "The characteristic polynomial det(A - lambda*I) = 0 yields the eigenvalues."
          )
        )
      }
    }
    if (questions.isNotEmpty()) {
      dao.insertQuestions(questions)
    }

    // Tasks & Exams
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, 4)
    val examDate = dateFormat.format(cal.time)
    cal.add(Calendar.DAY_OF_YEAR, 3)
    val assignDate = dateFormat.format(cal.time)

    val tasks = listOf(
      StudyTask(
        userId = targetUserId,
        subjectName = csSub.name,
        title = if (isMed) "Clinical Anatomy Midterm Examination" else if (isEng) "Thermodynamic Heat Cycles Exam" else "Midterm Examination (Sorting & Trees)",
        dueDate = examDate,
        type = "EXAM",
        priority = "HIGH"
      ),
      StudyTask(
        userId = targetUserId,
        subjectName = mathSub.name,
        title = if (isMed) "Problem Set 4: Cellular Respiration Pathway" else if (isEng) "Problem Set 4: Stress-Strain Tensor" else "Problem Set 4: Vector Spaces & Inner Products",
        dueDate = assignDate,
        type = "ASSIGNMENT",
        priority = "MEDIUM"
      ),
      StudyTask(
        userId = targetUserId,
        subjectName = engSub.name,
        title = if (isMed) "Clinical Case Study & Diagnostic Report" else if (isEng) "CAD Model & Assembly Drawing Submission" else "System Design & UML Class Diagram Submission",
        dueDate = assignDate,
        type = "ASSIGNMENT",
        priority = "LOW"
      )
    )
    dao.insertTasks(tasks)

    // Seed Sample AI Tutor History across subjects
    val now = System.currentTimeMillis()
    val hour = 3600_000L

    // 1. First Tutor Session
    val session1Id = dao.insertTutorSession(
      TutorSession(
        userId = targetUserId,
        subjectId = csId,
        subjectName = csSub.name,
        title = if (isMed) "Cardiovascular Pressure & Dynamics Review" else if (isEng) "First Law of Thermodynamics & Heat Cycles" else "AVL Tree Left-Right (LR) Rotation Implementation",
        previewSnippet = if (isMed) "Systemic vascular resistance and arterial regulation." else if (isEng) "State transformations and entropy equations." else "An LR rotation is performed when a node is inserted into the right subtree of the left child.",
        modelUsed = "gemini-2.5-flash",
        searchGroundingUsed = false,
        messageCount = 2,
        createdAt = now - (3 * hour),
        lastUpdatedAt = now - (3 * hour)
      )
    )
    dao.insertTutorMessage(
      TutorMessageEntity(
        sessionId = session1Id,
        role = "user",
        text = "Can you explain how AVL tree double rotations (Left-Right) work and show clean Kotlin code for it?",
        timestamp = now - (3 * hour),
        modelName = "gemini-2.5-flash"
      )
    )
    dao.insertTutorMessage(
      TutorMessageEntity(
        sessionId = session1Id,
        role = "model",
        text = """
# AVL Tree Double Rotations (LR Rotation)

An **LR Rotation** is required when a node is inserted into the *right subtree* of the *left child*, causing the balance factor of the ancestor to exceed `+1` (left-heavy) while the left child's balance factor is `-1`.

## Step-by-Step Mechanism
1. **First Rotation**: Perform a **Left Rotation** on the left child `node.left`.
2. **Second Rotation**: Perform a **Right Rotation** on the unbalanced root node `node`.

## Kotlin Implementation
```kotlin
fun rotateLeftRight(node: AvlNode): AvlNode {
    // Step 1: Left rotate on left child
    node.left = rotateLeft(node.left!!)
    // Step 2: Right rotate on root
    return rotateRight(node)
}
```

> **Invariant Guaranteed**: After the double rotation, tree height balance is restored and all subtrees strictly maintain `|height(left) - height(right)| <= 1`.

### Asymptotic Complexity
* **Time Complexity**: `O(1)` constant pointer re-assignments.
* **Search & Insertion Overall**: `O(log N)` guaranteed worst-case boundary.
        """.trimIndent(),
        timestamp = now - (3 * hour) + 2000L,
        modelName = "gemini-2.5-flash"
      )
    )

    // 2. Second Tutor Session
    val session2Id = dao.insertTutorSession(
      TutorSession(
        userId = targetUserId,
        subjectId = mathId,
        subjectName = mathSub.name,
        title = if (isMed) "Cellular Genetics & Respiration Pathway" else if (isEng) "Materials Science & Statics Equations" else "Matrix Diagonalization & Eigenvalue Criterion",
        previewSnippet = if (isMed) "ATP production and biochemical enzymatic control." else if (isEng) "Mohr's circle and principal stresses." else "An n x n matrix A is diagonalizable if and only if it has n linearly independent eigenvectors.",
        modelUsed = "gemini-2.5-pro",
        searchGroundingUsed = false,
        messageCount = 2,
        createdAt = now - (6 * hour),
        lastUpdatedAt = now - (6 * hour)
      )
    )
    dao.insertTutorMessage(
      TutorMessageEntity(
        sessionId = session2Id,
        role = "user",
        text = "Prove the necessary and sufficient condition for an n x n matrix A to be diagonalizable.",
        timestamp = now - (6 * hour),
        modelName = "gemini-2.5-pro"
      )
    )
    dao.insertTutorMessage(
      TutorMessageEntity(
        sessionId = session2Id,
        role = "model",
        text = """
# Diagonalizability Criterion for Linear Operators

An n × n matrix A is **diagonalizable** if and only if there exists an invertible modal matrix P such that:
`P⁻¹ A P = D`
where D is a diagonal matrix containing the eigenvalues λ₁, ..., λₙ.

## Fundamental Theorem
> **Theorem**: An n × n matrix A is diagonalizable if and only if Rⁿ admits an eigenbasis consisting of n linearly independent eigenvectors.

### Proof Outline
1. **Sufficiency (<=)**:
   * Suppose v₁, ..., vₙ are linearly independent eigenvectors with A·vᵢ = λᵢ·vᵢ.
   * Define P = [v₁ | ... | vₙ]. Since vectors are independent, det(P) != 0 so P⁻¹ exists.
   * Then A P = [A·v₁ | ... | A·vₙ] = [λ₁·v₁ | ... | λₙ·vₙ] = P D.
   * Multiply both sides by P⁻¹ yields `P⁻¹ A P = D`.
2. **Necessity (=>)**:
   * If P⁻¹ A P = D, then A P = P D.
   * Equating the j-th columns shows A·pⱼ = dⱼⱼ·pⱼ.
   * Because P is invertible, its columns p₁, ..., pₙ are non-zero and linearly independent.

### Key Exam Check
* For every eigenvalue λ, ensure **Geometric Multiplicity** = dim(ker(A - λ·I)) matches its **Algebraic Multiplicity**!
        """.trimIndent(),
        timestamp = now - (6 * hour) + 3000L,
        modelName = "gemini-2.5-pro"
      )
    )

    // 3. Third Tutor Session with Search Grounding
    val session3Id = dao.insertTutorSession(
      TutorSession(
        userId = targetUserId,
        subjectId = physId,
        subjectName = physSub.name,
        title = if (isMed) "Medical Pharmacology Drug Mechanisms" else if (isEng) "Robotics Transfer Functions & PID Loops" else "Wave-Particle Duality & Davisson-Germer Experiment",
        previewSnippet = if (isMed) "Receptor-ligand binding and pharmacodynamics." else if (isEng) "Proportional-Integral-Derivative error tuning." else "De Broglie proposed lambda = h / p. Verified experimentally by Davisson & Germer through electron diffraction through nickel crystal lattice.",
        modelUsed = "gemini-2.5-flash",
        searchGroundingUsed = true,
        messageCount = 2,
        createdAt = now - (12 * hour),
        lastUpdatedAt = now - (12 * hour)
      )
    )
    dao.insertTutorMessage(
      TutorMessageEntity(
        sessionId = session3Id,
        role = "user",
        text = "What was the critical experimental verification of de Broglie matter waves?",
        timestamp = now - (12 * hour),
        modelName = "gemini-2.5-flash",
        searchGroundingUsed = true
      )
    )
    dao.insertTutorMessage(
      TutorMessageEntity(
        sessionId = session3Id,
        role = "model",
        text = """
# Experimental Verification of De Broglie Hypothesis

In 1924, Louis de Broglie hypothesized that particles such as electrons possess wave-like characteristics with wavelength:
`λ = h / p = h / (m · v)`

## The Davisson-Germer Experiment (1927)
Clinton Davisson and Lester Germer fired slow-moving electrons at a crystalline **nickel target**:
* The scattered electrons exhibited **constructive and destructive interference patterns**.
* The peak scattering angle corresponded precisely to **Bragg's Law**:
`n · λ = 2d · sin(θ)`
* This proved beyond doubt that massive particles undergo wave diffraction.

> **Key Takeaway**: Matter waves are not electromagnetic; they are probability amplitude waves governed by the Schrödinger equation.
        """.trimIndent(),
        timestamp = now - (12 * hour) + 2500L,
        modelName = "gemini-2.5-flash",
        searchGroundingUsed = true,
        groundingSourcesJson = """[{"title":"Nobel Prize in Physics 1937 - Wave Properties of Electrons","url":"https://www.nobelprize.org"},{"title":"Davisson-Germer Experiment on HyperPhysics","url":"http://hyperphysics.phy-astr.gsu.edu"}]"""
      )
    )

    // 4. General Academic Session
    val session4Id = dao.insertTutorSession(
      TutorSession(
        userId = targetUserId,
        subjectId = null,
        subjectName = "General",
        title = "Active Recall & Spaced Repetition Scheduling",
        previewSnippet = "Combine the Leitner 5-stage box method with active retrieval practice to prevent the Ebbinghaus forgetting curve.",
        modelUsed = "gemini-2.5-flash",
        searchGroundingUsed = false,
        messageCount = 2,
        createdAt = now - (24 * hour),
        lastUpdatedAt = now - (24 * hour)
      )
    )
    dao.insertTutorMessage(
      TutorMessageEntity(
        sessionId = session4Id,
        role = "user",
        text = "How should I structure my weekly revision for university exams using spaced repetition?",
        timestamp = now - (24 * hour),
        modelName = "gemini-2.5-flash"
      )
    )
    dao.insertTutorMessage(
      TutorMessageEntity(
        sessionId = session4Id,
        role = "model",
        text = """
# Optimal Spaced Repetition Framework for University Exams

To counter the exponential **Ebbinghaus Forgetting Curve**, structure your revision intervals as follows:

## 1. The 1-3-7-14-30 Day Protocol
* **Day 0**: Attend lecture, take notes, and log the topic in StudyPilot.
* **Day 1**: Initial recall test (10-minute quiz or concept reconstruction).
* **Day 3**: Second retrieval practice. Focus on edge cases.
* **Day 7**: Weekend Revision Quiz covering all topics logged this week.
* **Day 14**: Interleaved testing with related subjects.
* **Day 30**: Full past-exam simulation.

## 2. Active Recall vs Passive Rereading
> Rereading lecture slides provides the illusion of competence. **Self-testing without notes** triggers synaptic consolidation and produces 2.5x higher retention.
        """.trimIndent(),
        timestamp = now - (24 * hour) + 2000L,
        modelName = "gemini-2.5-flash"
      )
    )
  }

  private companion object {
    const val TAG = "StudyPilotRepository"
    val CLOUD_COLLECTIONS = listOf("subjects", "sessions", "tasks", "topics", "quizAttempts", "tutorSessions")
  }
}
