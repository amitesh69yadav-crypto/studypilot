package com.example.data.repository

import com.example.data.ai.rag.KnowledgeIndex
import com.example.data.ai.rag.KnowledgePassage
import com.example.data.remote.ChatLinkFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.data.ai.ChatKnowledgeExtractor
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import com.example.data.ai.LocalAiEngine
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
    // Topics the student made stay, under General; ones imported from a course site go with it.
    val owned = if (subject.userId.isNotBlank()) dao.getTopicsForUser(subject.userId).first() else dao.getAllTopics().first()
    val (imported, own) = owned.filter { it.subjectId == subject.id }.partition { it.sourceType == "IMPORTED_LMS" }
    imported.forEach { deleteTopic(it.id, subject.userId) }
    dao.getCourseDocumentHeaders(subject.userId).filter { it.subjectId == subject.id && it.localPath.isNotBlank() }
      .forEach { runCatching { java.io.File(it.localPath).delete() } }
    dao.deleteCourseDocumentsForSubject(subject.userId, subject.id)
    val moved = own.map { it.copy(subjectId = 0L, subjectName = "General") }
    if (moved.isNotEmpty()) dao.updateTopics(moved)
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
    // Its questions go with it; nothing else uses them.
    dao.getQuestionsForTopicIds(listOf(id)).map { it.id }.takeIf { it.isNotEmpty() }?.let { dao.deleteQuestionsByIds(it) }
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

  // --- Connected services (calendar feeds, Canvas, Moodle, Google Classroom) ---

  /** What one import changed, for the status line. */
  data class ImportSummary(val subjects: Int = 0, val topics: Int = 0, val tasks: Int = 0, val sessions: Int = 0, val updated: Int = 0, val removed: Int = 0) {
    private fun n(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"
    override fun toString(): String = listOfNotNull(
      subjects.takeIf { it > 0 }?.let { n(it, "new subject", "new subjects") },
      topics.takeIf { it > 0 }?.let { n(it, "new topic", "new topics") },
      tasks.takeIf { it > 0 }?.let { n(it, "new task", "new tasks") },
      sessions.takeIf { it > 0 }?.let { n(it, "new class", "new classes") },
      updated.takeIf { it > 0 }?.let { "$it updated" },
      removed.takeIf { it > 0 }?.let { "$it removed" }
    ).joinToString(", ").ifBlank { "Already up to date" }
  }

  /**
   * Puts a calendar feed's events into the timetable. Each imported class remembers its event
   * ("ics:<feed>:<uid>:<date>"), so a re-import updates moved classes and drops cancelled future ones
   * instead of adding duplicates. Imported classes have no alarm until the student turns one on.
   */
  suspend fun importCalendar(userId: String, feedUrl: String, events: List<com.example.data.connect.CalendarEvent>, today: String): ImportSummary {
    val feed = "ics:" + feedUrl.trim().hashCode().toUInt().toString(16) + ":"
    val existing = dao.getSessionsListForUser(userId)
    val mine = existing.filter { it.googleCalendarEventId?.startsWith(feed) == true }.associateBy { it.googleCalendarEventId!! }
    val subjects = dao.getSubjectsForUser(userId).first()
    var added = 0
    var updated = 0
    var removed = 0
    val seen = HashSet<String>()
    for (e in events.filter { !it.allDay }) {
      val ref = feed + e.uid + ":" + e.date
      if (!seen.add(ref)) continue
      val subject = subjects.firstOrNull { s ->
        (s.code.isNotBlank() && e.summary.contains(s.code, ignoreCase = true)) || e.summary.contains(s.name, ignoreCase = true)
      }
      val subjectName = subject?.name ?: e.summary.split(',', ';', '|').first().split(" - ").first().trim().take(40).ifBlank { "General" }
      val notes = listOf(e.description.take(1500), "From your calendar feed").filter { it.isNotBlank() }.joinToString("\n\n")
      val old = mine[ref]
      if (old != null) {
        val changed = old.copy(startTime = e.startTime, endTime = e.endTime, location = e.location, topic = e.summary.take(120))
        if (changed != old) {
          dao.updateSession(changed)
          updated++
        }
        continue
      }
      // A class the student already typed in by hand at the same time isn't added twice.
      val duplicate = existing.any { it.date == e.date && it.startTime == e.startTime && (it.subjectName.equals(subjectName, true) || it.topic.equals(e.summary, true)) }
      if (duplicate) continue
      dao.insertSession(
        TimetableSession(
          userId = userId, subjectId = subject?.id ?: 0L, subjectName = subjectName, topic = e.summary.take(120),
          date = e.date, startTime = e.startTime, endTime = e.endTime, location = e.location, notes = notes,
          isAlarmEnabled = false, googleCalendarEventId = ref, sessionCategory = "ACADEMIC"
        )
      )
      added++
    }
    // Future classes from this feed that it no longer lists were cancelled or moved.
    mine.values.filter { it.googleCalendarEventId !in seen && it.date >= today }.forEach {
      dao.deleteSessionById(it.id)
      removed++
    }
    // All-day events that look like exams or deadlines become tasks.
    val tasks = dao.getTasksForUser(userId).first()
    var newTasks = 0
    val deadline = Regex("(?i)exam|deadline|due|test|quiz|tenta|submission")
    val exam = Regex("(?i)exam|tenta|test")
    events.filter { it.allDay && it.date >= today && deadline.containsMatchIn(it.summary) }
      .distinctBy { it.summary + it.date }
      .forEach { e ->
        if (tasks.none { it.title.equals(e.summary.take(120), true) && it.dueDate == e.date }) {
          addTask(StudyTask(userId = userId, subjectName = "General", title = e.summary.take(120), dueDate = e.date, type = if (exam.containsMatchIn(e.summary)) "EXAM" else "ASSIGNMENT"))
          newTasks++
        }
      }
    return ImportSummary(sessions = added, tasks = newTasks, updated = updated, removed = removed)
  }

  /**
   * Adds a learning system's courses: each course becomes a subject (matched by name or code if it
   * exists), its syllabus and pages become topics, and assignments with a due date become tasks.
   * Running it again only adds what's new and refreshes changed notes and due dates.
   */
  suspend fun importCourses(
    userId: String,
    courses: List<com.example.data.connect.LmsCourse>,
    today: String,
    /** Lower-case names of courses the student removed; they aren't added back. */
    skip: Set<String> = emptySet()
  ): ImportSummary {
    var subjects = dao.getSubjectsForUser(userId).first()
    val topics = dao.getTopicsForUser(userId).first()
    val tasks = dao.getTasksForUser(userId).first()
    val palette = listOf("#5B6CFF", "#2E9E6B", "#E0823A", "#C2477C", "#3A8FD8", "#8C5BD6", "#C9A227", "#3AA6A0")
    var newSubjects = 0
    var newTopics = 0
    var newTasks = 0
    var updated = 0
    for (course in courses.filter { it.name.isNotBlank() && it.name.take(60).lowercase() !in skip }) {
      val name = course.name.take(60)
      var subject = subjects.firstOrNull { it.name.equals(name, true) || (course.code.isNotBlank() && it.code.equals(course.code, true)) }
      if (subject == null) {
        val code = course.code.take(20).ifBlank { name.take(8).uppercase() }
        val id = addSubject(name, code, palette[(subjects.size + newSubjects) % palette.size], userId)
        subject = Subject(id = id, userId = userId, name = name, code = code, colorHex = "")
        subjects = subjects + subject
        newSubjects++
      }
      for (t in course.topics) {
        val title = t.title.trim().take(80)
        if (title.isBlank()) continue
        val notes = t.text.take(12_000)
        val old = topics.firstOrNull { it.subjectId == subject.id && it.title.equals(title, true) }
        if (old == null) {
          addTopic(
            StudyTopic(
              userId = userId, subjectId = subject.id, subjectName = subject.name, title = title,
              dateStudied = today, descriptionNotes = notes, sourceType = "IMPORTED_LMS", nextRevisionDate = today
            )
          )
          newTopics++
        } else if (old.descriptionNotes != notes && old.sourceType == "IMPORTED_LMS") {
          updateTopic(old.copy(descriptionNotes = notes))
          updated++
        }
      }
      for (a in course.assignments.filter { it.dueDate.isNotBlank() }) {
        val title = a.title.trim().take(120)
        val old = tasks.firstOrNull { it.subjectName.equals(subject.name, true) && it.title.equals(title, true) }
        if (old == null) {
          if (a.dueDate < today) continue // past work isn't worth a task now
          addTask(StudyTask(userId = userId, subjectName = subject.name, title = title, dueDate = a.dueDate, type = if (a.isExam) "EXAM" else "ASSIGNMENT"))
          newTasks++
        } else if (old.dueDate != a.dueDate && !old.isCompleted) {
          updateTask(old.copy(dueDate = a.dueDate))
          updated++
        }
      }
    }
    return ImportSummary(subjects = newSubjects, topics = newTopics, tasks = newTasks, updated = updated)
  }

  // --- Notes ---
  fun notesFor(userId: String): Flow<List<com.example.data.model.StudyNote>> = dao.getNotesForUser(userId)

  /** Adds [note], or updates it when it already has an id. Returns its id. */
  suspend fun saveNote(note: com.example.data.model.StudyNote): Long =
    if (note.id == 0L) dao.insertNote(note) else { dao.updateNote(note); note.id }

  suspend fun deleteNote(id: Long) = dao.deleteNote(id)

  /** Every saved tutor chat with its messages, newest first. */
  suspend fun tutorChatsWithMessages(userId: String): List<Pair<TutorSession, List<TutorMessageEntity>>> =
    dao.getTutorSessionsForUser(userId).first().sortedByDescending { it.lastUpdatedAt }.map { it to dao.getMessagesForSessionSync(it.id) }

  suspend fun chatWithTutor(
    history: List<com.example.data.model.ChatMessage>,
    systemInstruction: String,
    subjectContext: String? = null,
    onPartial: ((String) -> Unit)? = null,
    knowledge: List<KnowledgePassage> = emptyList(),
    strict: Boolean = false
  ): com.example.data.remote.ChatResponse {
    return ai.chat(history, systemInstruction, subjectContext, onPartial, knowledge, strict)
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
    addConceptsToChat(userId, chat.copy(id = chatId), drafts)
    invalidateKnowledge()
    return chatId
  }

  /** Saves [drafts] as concepts of [chat] and gives each one a topic, so they appear in Topics and quizzes. */
  private suspend fun addConceptsToChat(
    userId: String,
    chat: ImportedChat,
    drafts: List<ChatKnowledgeExtractor.ExtractedConceptDraft>
  ) {
    val chatId = chat.id
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
  }

  // --- Imported chat links: checking for new messages ---

  sealed class ChatLinkCheck {
    object NoChange : ChatLinkCheck()
    /** [added] is only the part that wasn't there before; [fullText] replaces the stored copy. */
    data class Updated(val added: String, val fullText: String) : ChatLinkCheck()
    data class Failed(val message: String) : ChatLinkCheck()
  }

  /**
   * Opens [chat]'s share link again and returns what's new. Share links are snapshots: ChatGPT and
   * most others only show later messages after the share is updated on their side.
   */
  suspend fun checkChatLink(chat: ImportedChat): ChatLinkCheck {
    if (chat.sourceUrl.isBlank()) return ChatLinkCheck.Failed("This chat wasn't imported from a link.")
    val fetched = ChatLinkFetcher.fetchChatFromUrl(chat.sourceUrl, forceFresh = true)
    if (!fetched.isSuccess || fetched.conversationText.isBlank()) {
      return ChatLinkCheck.Failed(fetched.errorMessage ?: "Couldn't open the link. Is it still public?")
    }
    val added = newChatMaterial(chat.rawConversation, fetched.conversationText)
    if (added.isBlank()) {
      dao.updateImportedChat(chat.copy(lastProcessedAt = System.currentTimeMillis()))
      return ChatLinkCheck.NoChange
    }
    return ChatLinkCheck.Updated(added, fetched.conversationText)
  }

  /** Stores the refreshed conversation and the concepts found in its new part. */
  suspend fun applyChatUpdate(
    userId: String,
    chat: ImportedChat,
    fullText: String,
    drafts: List<ChatKnowledgeExtractor.ExtractedConceptDraft>,
    addedMessages: Int
  ): ImportedChat {
    addConceptsToChat(userId, chat, drafts)
    val updated = chat.copy(
      rawConversation = fullText,
      wordCount = fullText.split(Regex("""\s+""")).count { it.isNotBlank() },
      messageCount = chat.messageCount + addedMessages,
      extractedConceptsCount = chat.extractedConceptsCount + drafts.size,
      lastProcessedAt = System.currentTimeMillis()
    )
    dao.updateImportedChat(updated)
    invalidateKnowledge()
    return updated
  }

  // --- Searching the student's own material (RAG) ---

  private class CachedIndex(val userId: String, val at: Long, val index: KnowledgeIndex)

  @Volatile private var knowledgeCache: CachedIndex? = null

  /** Everything the student keeps, indexed for search; rebuilt at most every half minute. */
  suspend fun knowledgeIndex(userId: String): KnowledgeIndex {
    knowledgeCache?.let { if (it.userId == userId && System.currentTimeMillis() - it.at < 30_000) return it.index }
    val chats = dao.getImportedChatsForUser(userId).first()
    val sessions = dao.getTutorSessionsForUser(userId).first().map { it to dao.getMessagesForSessionSync(it.id) }
    val topics = dao.getTopicsForUser(userId).first()
    val concepts = dao.getExtractedConceptsForUser(userId).first()
    val index = withContext(Dispatchers.Default) { KnowledgeIndex.build(chats, sessions, topics, concepts) }
    knowledgeCache = CachedIndex(userId, System.currentTimeMillis(), index)
    return index
  }

  fun invalidateKnowledge() {
    knowledgeCache = null
  }

  /** Passages from the student's material that answer [query], leaving out the conversation in progress. */
  suspend fun searchKnowledge(userId: String, query: String, currentSessionTitle: String? = null): List<KnowledgePassage> {
    val own = knowledgeIndex(userId).search(query, limit = 6)
      .filterNot { currentSessionTitle != null && it.sourceTitle == "Tutor chat: $currentSessionTitle" }
      .filter { it.subject.isBlank() } // course pages come from the course-material search below
      .take(3)
    val course = searchCourseMaterial(userId, query, subjectName = null, limit = 3)
    return (own + course).take(6)
  }

  /** Removes questions whose topic no longer exists (left by older versions). Returns how many. */
  suspend fun purgeOrphanQuestions(): Int {
    val ids = dao.getOrphanQuestionIds()
    ids.chunked(500).forEach { dao.deleteQuestionsByIds(it) }
    return ids.size
  }

  // --- Course files ("College notes") ---

  private class CachedCourseIndex(val userId: String, val signature: String, val index: KnowledgeIndex)

  @Volatile private var courseCache: CachedCourseIndex? = null

  /** Course files and course pages, indexed; rebuilt only when files or topics change. */
  private suspend fun courseIndex(userId: String): KnowledgeIndex {
    val topics = dao.getTopicsForUser(userId).first().filter { it.sourceType == "IMPORTED_LMS" }
    val signature = dao.courseDocumentsSignature(userId) + "|" + topics.size + ":" + topics.sumOf { it.descriptionNotes.length }
    courseCache?.let { if (it.userId == userId && it.signature == signature) return it.index }
    val docs = dao.getCourseDocumentsForUser(userId).first()
    val index = withContext(Dispatchers.Default) { KnowledgeIndex.buildCourseMaterial(docs, topics) }
    courseCache = CachedCourseIndex(userId, signature, index)
    return index
  }

  /**
   * The best passages from the student's course files and pages for [query]. With [subjectName],
   * that course's material first, then the rest if it has nothing.
   */
  suspend fun searchCourseMaterial(userId: String, query: String, subjectName: String?, limit: Int = 6): List<KnowledgePassage> {
    val found = courseIndex(userId).search(query, limit = limit * 4)
    if (subjectName == null) return found.take(limit)
    val inSubject = found.filter { it.subject.equals(subjectName, ignoreCase = true) }
    return (inSubject.ifEmpty { found }).take(limit)
  }

  fun courseDocuments(userId: String) = dao.getCourseDocumentsForUser(userId)

  /** Marks course files as chosen (or not) for quizzes and the tutor. */
  suspend fun setUseForStudy(ids: List<Long>, use: Boolean) {
    if (ids.isNotEmpty()) dao.setUseForStudy(ids, use)
    courseCache = null
  }

  /**
   * Adds a document the student uploaded (PDF, PowerPoint or Word) under [subjectName], creating the
   * subject if it's new. Its text is read now; a copy is kept in [saveDir] when the device keeps files
   * (the PC). [scanDocument] then finds its topics. Returns the document's id.
   */
  suspend fun addUploadedDocument(userId: String, fileName: String, bytes: ByteArray, subjectName: String, saveDir: java.io.File?): Long {
    val type = com.example.data.docs.DocumentText.typeOf(fileName) ?: throw IllegalArgumentException("Only PDF, PowerPoint (.pptx) and Word (.docx) files can be added.")
    val name = subjectName.trim().ifBlank { "General" }
    val subjects = dao.getSubjectsForUser(userId).first()
    val subject = subjects.firstOrNull { it.name.equals(name, ignoreCase = true) }
      ?: if (name.equals("General", ignoreCase = true)) null
      else addSubject(name, name.take(8).uppercase(), listOf("#5B6CFF", "#2E9E6B", "#E0823A", "#C2477C", "#3A8FD8")[subjects.size % 5], userId)
        .let { id -> Subject(id = id, userId = userId, name = name, code = name.take(8).uppercase(), colorHex = "") }
    val pages = withContext(Dispatchers.Default) { com.example.data.docs.DocumentText.pages(bytes, type) }
    val target = saveDir?.let { dir -> uniqueFile(java.io.File(dir, safeName(subject?.name ?: "General")), safeName(fileName.take(160))) }
    if (target != null) withContext(Dispatchers.IO) { target.parentFile?.mkdirs(); target.writeBytes(bytes) }
    return dao.upsertCourseDocument(
      com.example.data.model.CourseDocument(
        userId = userId,
        subjectId = subject?.id ?: 0L,
        subjectName = subject?.name ?: "General",
        title = fileName.take(160),
        source = "upload",
        externalId = "upload:" + java.util.UUID.randomUUID().toString(),
        url = "",
        fileType = type,
        remoteUpdatedAt = System.currentTimeMillis().toString(),
        pageCount = pages.size,
        text = pages.joinToString("\u000C").take(400_000),
        localPath = target?.absolutePath.orEmpty(),
        sizeBytes = bytes.size.toLong(),
        useForStudy = true,
        quizStatus = "queued"
      )
    )
  }

  /** Removes a document: its topics (and their questions), its record, and the kept copy. */
  suspend fun removeDocument(userId: String, docId: Long) {
    val doc = dao.getCourseDocument(docId) ?: return
    dao.getTopicsForUser(userId).first().filter { it.uploadedFileName == doc.externalId }.forEach { deleteTopic(it.id, userId) }
    dao.deleteCourseDocument(doc.id)
    if (doc.localPath.isNotBlank()) runCatching { java.io.File(doc.localPath).delete() }
    courseCache = null
  }

  /** The topics read from a document, for a quiz on just that document. */
  suspend fun topicsOfDocument(userId: String, externalId: String): List<StudyTopic> =
    dao.getTopicsForUser(userId).first().filter { it.uploadedFileName == externalId }

  private fun safeName(name: String) = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().trimEnd('.').ifBlank { "file" }

  private fun uniqueFile(dir: java.io.File, name: String): java.io.File {
    var f = java.io.File(dir, name)
    var n = 2
    while (f.exists()) {
      f = java.io.File(dir, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.', "pdf"))
      n++
    }
    return f
  }

  /**
   * Reads a document into knowledge: finds the topics it actually teaches (a model, your API key
   * first; or the built-in reader) and saves them as topics under its subject, with study notes. The
   * tutor and quizzes use them; questions are written when a quiz is made. Progress is saved on the
   * document as it goes, so both devices show it.
   */
  suspend fun scanDocument(userId: String, docId: Long): Boolean {
    var doc = dao.getCourseDocument(docId) ?: return false
    suspend fun stage(status: String, progress: Int) = dao.setQuizStatus(doc.id, status, progress)
    return try {
      stage("reading", 5)
      if (doc.text.isBlank() && doc.localPath.isNotBlank() && java.io.File(doc.localPath).isFile) {
        val pages = withContext(Dispatchers.Default) { com.example.data.docs.DocumentText.pages(java.io.File(doc.localPath).readBytes(), doc.fileType) }
        doc = doc.copy(text = pages.joinToString("\u000C").take(400_000), pageCount = pages.size, quizStatus = "reading", quizProgress = 10)
        dao.upsertCourseDocument(doc)
      }
      val pages = doc.pages
      if (doc.text.isBlank()) {
        stage("failed: no readable text (a scan or images only)", 0)
        return false
      }

      // 1. The topics the file teaches.
      stage("topics", 15)
      val sections = sections(pages, 9000)
      val byTitle = LinkedHashMap<String, MutableList<com.example.data.ai.StudyAi.FileTopic>>()
      val builtIn = mutableListOf<Pair<String, String>>()
      var fallbackWhy: String? = null
      for ((i, section) in sections.withIndex()) {
        ai.waitForCloud()
        val found = ai.topicsInFile(doc.title, doc.subjectName, section.text)
        if (found != null) {
          found.forEach { t -> byTitle.getOrPut(t.title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()) { mutableListOf() } += t }
        } else {
          fallbackWhy = ai.lastError ?: "no AI model answered"
          com.example.platform.Log.w("QuizFromFile", "Built-in reader for part of ${doc.title}: $fallbackWhy")
          // No model answered for this part: the built-in reader takes these pages, the rest keeps the model's topics.
          builtIn += com.example.data.ai.LectureQuiz.topicsFromPages(pages, doc.title.substringBeforeLast('.'), only = section.pages).map { it.title to it.notes }
        }
        stage("topics", 15 + 80 * (i + 1) / sections.size)
      }
      val found: List<Pair<String, String>> = run {
        byTitle.values.take(14).map { parts ->
          val first = parts.first()
          val pageList = parts.flatMap { it.pages }.distinct().sorted()
          val excerpt = pageList.take(4).mapNotNull { pages.getOrNull(it - 1) }.joinToString("\n\n").take(2500)
          first.title to buildString {
            append(parts.joinToString("\n\n") { it.summary })
            val points = parts.flatMap { it.keyPoints }.distinct()
            if (points.isNotEmpty()) append("\n\nKey points:\n").append(points.joinToString("\n") { "- $it" })
            if (excerpt.isNotBlank()) append("\n\nFrom the file").append(if (pageList.isNotEmpty()) " (p. ${pageList.joinToString(", ")})" else "").append(":\n").append(excerpt)
          }
        } + builtIn.filter { (title, _) -> byTitle.keys.none { it == title.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() } }
      }
      if (found.isEmpty()) {
        stage("failed: no study topics found (only course information?)", 0)
        return false
      }

      // 2. Topics under the file's subject, replacing what an earlier quiz made from this file.
      dao.getTopicsForUser(userId).first().filter { it.sourceType == "COURSE_FILE" && it.uploadedFileName == doc.externalId }
        .forEach { deleteTopic(it.id, userId) }
      val today = todayIso()
      val made = found.map { (title, notes) ->
        val topic = StudyTopic(
          userId = userId, subjectId = doc.subjectId, subjectName = doc.subjectName, title = title.take(80),
          dateStudied = today, descriptionNotes = notes.take(60_000), sourceType = "COURSE_FILE",
          uploadedFileName = doc.externalId, nextRevisionDate = today
        )
        topic.copy(id = addTopic(topic))
      }

      courseCache = null
      // "ready", or "ready: built-in reader (why)" when no model could read (part of) the file.
      stage(if (fallbackWhy == null) "ready" else "ready: built-in reader ($fallbackWhy)".take(200), 100)
      true
    } catch (e: kotlinx.coroutines.CancellationException) {
      stage("failed: stopped before it finished", 0)
      throw e
    } catch (e: Exception) {
      stage("failed: ${e.message ?: "something went wrong"}".take(160), 0)
      false
    }
  }

  private class Section(val text: String, val pages: Set<Int>)

  /**
   * Page texts joined into sections of about [size] characters, each page marked "[Page n]". Lines
   * repeated on most pages (headers, footers, author lines) are left out.
   */
  private fun sections(pages: List<String>, size: Int): List<Section> {
    val clean = com.example.data.ai.LectureQuiz.withoutBoilerplate(pages)
    val out = mutableListOf<Section>()
    val current = StringBuilder()
    val inCurrent = mutableSetOf<Int>()
    clean.forEachIndexed { i, page ->
      if (page.isBlank()) return@forEachIndexed
      val piece = "[Page ${i + 1}]\n" + page.take(size)
      if (current.length + piece.length > size && current.isNotEmpty()) {
        out += Section(current.toString(), inCurrent.toSet())
        current.clear(); inCurrent.clear()
      }
      current.append(piece).append("\n\n")
      inCurrent += i + 1
    }
    if (current.isNotBlank()) out += Section(current.toString(), inCurrent.toSet())
    return out.take(12) // a long book: its first dozen sections
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
    userId: String = "",
    /** Only these topics (a quiz on one document). */
    onlyTopicIds: Set<Long>? = null,
    /** Called as each topic's questions are ready: (done, total, topic title). */
    onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
  ): GeneratedQuiz {
    // Everything stored (tutor conversations, imported chats, concepts) becomes quiz material.
    if (userId.isNotBlank()) runCatching { quizSources.refresh(userId) }
    val allTopicsList = (if (userId.isNotBlank()) {
      dao.getTopicsForUser(userId).first()
    } else {
      dao.getAllTopics().first()
    }).filter { it.sourceType != "IMPORTED_LMS" } // course pages (info, overviews) aren't quiz material
    if (allTopicsList.isEmpty()) {
      return GeneratedQuiz(emptyList(), emptyList(), emptyList(), isAllCovered = true)
    }

    var eligibleTopics = if (onlyTopicIds != null) allTopicsList.filter { it.id in onlyTopicIds } else when (filterSubjectId) {
      null -> allTopicsList
      // "General": topics whose subject doesn't exist (none chosen, or since deleted).
      0L -> {
        val known = (if (userId.isNotBlank()) dao.getSubjectsForUser(userId) else dao.getAllSubjects()).first().mapTo(HashSet()) { it.id }
        allTopicsList.filter { it.subjectId !in known }
      }
      else -> allTopicsList.filter { it.subjectId == filterSubjectId }
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

    val history = askedHistory(userId)
    val distractors = LocalAiEngine.distractorPool(allTopicsList)
    val concepts = conceptsFor(userId, targetTopicList)

    // How many questions each topic gives: the count is spread, weakest topics first.
    val needs = LinkedHashMap<StudyTopic, Int>()
    var planned = 0
    for ((index, topic) in targetTopicList.withIndex()) {
      if (planned >= maxQuestions) break
      val n = (maxQuestions - planned).coerceAtMost(questionsPerTopic + if (index < oneMoreFor) 1 else 0)
      needs[topic] = n
      planned += n
    }

    val done = java.util.concurrent.atomic.AtomicInteger(0)
    onProgress(0, needs.size, needs.keys.firstOrNull()?.title.orEmpty())
    val picked: List<Pair<StudyTopic, List<QuizQuestion>>> = coroutineScope {
      needs.map { (topic, n) ->
        async {
          (topic to pickQuestions(topic, n, config, history, concepts[topic.title], distractors)).also { onProgress(done.incrementAndGet(), needs.size, topic.title) }
        }
      }.awaitAll()
    }.toMutableList().also { list ->
      // A topic that came up short: take more from the topics that weren't needed.
      var total = list.sumOf { it.second.size }
      for (topic in targetTopicList.drop(needs.size)) {
        if (total >= maxQuestions) break
        val more = pickQuestions(topic, (maxQuestions - total).coerceAtMost(questionsPerTopic), config, history, concepts[topic.title], distractors)
        if (more.isNotEmpty()) {
          list.add(topic to more)
          total += more.size
        }
      }
    }

    val selectedTopics = picked.filter { it.second.isNotEmpty() }.map { it.first }
    val assembledQuestions = picked.flatMap { it.second }.shuffled()
    rememberServed(assembledQuestions)

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

  // --- Which questions a quiz gets ---

  /**
   * A model on the PC or phone works through requests one at a time; sending a quiz's worth at once
   * leaves the last ones waiting past the timeout, so two at a time. A cloud model answers many
   * requests in parallel, so a quiz's topics are written together there.
   */
  private val localModelSlots = kotlinx.coroutines.sync.Semaphore(2)
  private val cloudModelSlots = kotlinx.coroutines.sync.Semaphore(6)
  private suspend fun modelSlots() =
    if (ai.backend(com.example.data.ai.AiTask.ANSWER)?.label?.startsWith("Cloud") == true) cloudModelSlots else localModelSlots

  /** Questions served this session (newest last), so leaving a quiz early still moves on to new ones. */
  private val recentlyServed = LinkedHashSet<String>()

  private fun rememberServed(questions: List<QuizQuestion>) = synchronized(recentlyServed) {
    questions.forEach { q ->
      val key = LocalAiEngine.questionKey(q)
      recentlyServed.remove(key)
      recentlyServed.add(key)
    }
  }

  /**
   * When each question was last asked, as a rank (higher is more recent): from finished quizzes,
   * which sync between devices, then this session's quizzes.
   */
  private suspend fun askedHistory(userId: String): Map<String, Int> {
    val attempts = (if (userId.isNotBlank()) dao.getQuizAttemptsForUser(userId) else dao.getAllQuizAttempts()).first()
    val rank = HashMap<String, Int>()
    var n = 0
    for (attempt in attempts.sortedBy { it.id }) {
      n++
      runCatching { org.json.JSONArray(attempt.detailsJson.ifBlank { "[]" }) }.getOrNull()?.let { arr ->
        for (i in 0 until arr.length()) {
          val k = arr.optJSONObject(i)?.optString("k")?.takeIf { it.isNotBlank() } ?: continue
          // Early records kept "question|answer" for every question.
          rank[if (k.startsWith("which of these is from your")) k else k.substringBefore("|")] = n
        }
      }
    }
    synchronized(recentlyServed) { recentlyServed.forEach { rank[it] = ++n } }
    return rank
  }

  private suspend fun conceptsFor(userId: String, topics: List<StudyTopic>): Map<String, ExtractedConcept> {
    // Topics made from a chat are named after the concept, so match on the concept's name.
    val titles = topics.filter { it.sourceType == "IMPORTED_CHAT" }.map { it.title }.distinct()
    return if (titles.isEmpty()) emptyMap() else dao.getConceptsByNames(userId, titles).associateBy { it.conceptName }
  }

  private fun fits(q: QuizQuestion, config: QuizConfig) =
    (config.difficulty == "MIXED" || q.difficulty.equals(config.difficulty, ignoreCase = true)) &&
      (config.questionTypes.isEmpty() || q.questionType in config.questionTypes)

  /** The topic's saved questions that fit [config]; placeholders from a time without a model are deleted. */
  private suspend fun savedQuestions(topic: StudyTopic, config: QuizConfig): List<QuizQuestion> {
    val (placeholders, real) = dao.getQuestionsForTopicIds(listOf(topic.id)).partition { LocalAiEngine.isGenericTemplate(it) }
    if (placeholders.isNotEmpty()) dao.deleteQuestionsByIds(placeholders.map { it.id })
    return real.filter { fits(it, config) }
  }

  /**
   * [n] questions on [topic]: ones never asked first, then newly written ones (the model is told
   * what already exists), then the ones asked longest ago.
   */
  private suspend fun pickQuestions(
    topic: StudyTopic,
    n: Int,
    config: QuizConfig,
    history: Map<String, Int>,
    concept: ExtractedConcept?,
    distractors: List<Pair<Long, String>>
  ): List<QuizQuestion> {
    if (n <= 0) return emptyList()
    val saved = savedQuestions(topic, config)
    // Not asked before, not even reworded.
    val askedWords = history.keys.map { LocalAiEngine.keyWords(it.substringBefore("|")) }
    val chosen = saved
      .filter { LocalAiEngine.questionKey(it) !in history && !LocalAiEngine.isNearDuplicate(it.questionText, askedWords) }
      .shuffled().take(n).toMutableList()
    if (chosen.size < n) chosen += writeNewQuestions(topic, n - chosen.size, config, saved, history.keys, concept, distractors)
    if (chosen.size < n) {
      val chosenIds = chosen.mapTo(HashSet()) { it.id }
      chosen += saved.filter { it.id !in chosenIds }.sortedBy { history[LocalAiEngine.questionKey(it)] ?: 0 }.take(n - chosen.size)
    }
    // Labelled with the topic as it is now (it may have moved subject or been renamed).
    return chosen.map { it.copy(subjectName = topic.subjectName, topicTitle = topic.title) }
  }

  /**
   * Writes and saves up to [count] questions on [topic] that differ from [existing]. Uses the model,
   * else multiple choice built from the material. Saved questions get their ids, which the quiz
   * screen keys answers by.
   */
  private suspend fun writeNewQuestions(
    topic: StudyTopic,
    count: Int,
    config: QuizConfig,
    existing: List<QuizQuestion>,
    asked: Set<String>,
    concept: ExtractedConcept?,
    distractors: List<Pair<Long, String>>
  ): List<QuizQuestion> {
    val known = existing.mapTo(HashSet()) { LocalAiEngine.questionKey(it) } + asked
    val avoid = existing.map { it.questionText }
    val fromModel = modelSlots().withPermit {
      // A few spare, since some come back as repeats of what the student already had.
      val ask = if (avoid.isEmpty()) count else (count + 2).coerceAtMost(6)
      if (concept != null) {
        ai.modelQuestionsForConcept(concept, ask, config.difficulty, config.questionTypes, avoid).map { it.copy(topicId = topic.id) }
      } else {
        ai.modelQuestionsForTopic(topic, ask, config.difficulty, config.questionTypes, avoid)
      }
    }
    // Drop the model's repeats, reworded ones included, and repeats within its own reply.
    val knownWords = (existing.map { LocalAiEngine.keyWords(it.questionText) } + asked.map { LocalAiEngine.keyWords(it.substringBefore("|")) }).toMutableList()
    var fresh = fromModel.filter { q ->
      val isNew = LocalAiEngine.questionKey(q) !in known && !LocalAiEngine.isNearDuplicate(q.questionText, knownWords)
      if (isNew) knownWords.add(LocalAiEngine.keyWords(q.questionText))
      isNew
    }
    if (fresh.size < count) {
      val local = buildList {
        // Real questions from definitions, key terms and lists in the material; other topics'
        // definitions make the wrong options.
        val pool = (if (topic.userId.isNotBlank()) dao.getTopicsForUser(topic.userId).first() else dao.getAllTopics().first())
          .filter { it.id != topic.id && it.sourceType != "IMPORTED_LMS" }
          .flatMap { com.example.data.ai.LectureQuiz.definitions(it.descriptionNotes) }.take(80)
        addAll(com.example.data.ai.LectureQuiz.questions(topic, count * 3, config.difficulty.takeIf { it != "MIXED" } ?: "MEDIUM", pool).filter { fits(it, config) })
        if (concept != null) {
          // It cycles through question types; ask for extra and keep the allowed ones.
          addAll(LocalAiEngine.generateLocalQuizQuestionsForConcept(concept, count * 4, config.difficulty).filter { fits(it, config) })
        }
        if (config.questionTypes.isEmpty() || StudyAi.MCQ in config.questionTypes) {
          addAll(LocalAiEngine.mcqFromNotes(topic, count, config.difficulty, distractors, known))
        }
        if (config.questionTypes.isEmpty() || "SHORT_ANSWER" in config.questionTypes) {
          addAll(LocalAiEngine.questionsFromNotes(topic, count, config.difficulty))
        }
      }
      val taken = fresh.mapTo(HashSet()) { LocalAiEngine.questionKey(it) }
      fresh = fresh + local.filter { val k = LocalAiEngine.questionKey(it); k !in known && taken.add(k) }
    }
    // Too little material for multiple choice (it needs three wrong options) and no model: a typed-answer
    // question from the notes beats an empty quiz.
    if (fresh.isEmpty() && existing.isEmpty()) {
      fresh = LocalAiEngine.questionsFromNotes(topic, count, config.difficulty).filter { LocalAiEngine.questionKey(it) !in known }
    }
    fresh = fresh.take(count).map { it.copy(topicId = topic.id) }
    if (fresh.isEmpty()) return emptyList()
    currentCoroutineContext().ensureActive() // a cancelled look-ahead saves nothing
    val ids = dao.insertQuestions(fresh)
    return fresh.zip(ids) { q, id -> q.copy(id = id) }
  }

  /**
   * Writes new questions ahead of time for [topics] (up to [perTopic] each), so the next quiz is ready
   * sooner and different. Topics that already have enough unasked questions are skipped.
   */
  suspend fun prepareNextQuestions(topics: List<StudyTopic>, perTopic: Int, config: QuizConfig, userId: String) {
    val history = askedHistory(userId)
    val all = if (userId.isNotBlank()) dao.getTopicsForUser(userId).first() else dao.getAllTopics().first()
    val distractors = LocalAiEngine.distractorPool(all)
    val concepts = conceptsFor(userId, topics)
    val askedWords = history.keys.map { LocalAiEngine.keyWords(it.substringBefore("|")) }
    for (topic in topics) {
      currentCoroutineContext().ensureActive()
      val saved = savedQuestions(topic, config)
      val unasked = saved.count { LocalAiEngine.questionKey(it) !in history && !LocalAiEngine.isNearDuplicate(it.questionText, askedWords) }
      if (unasked >= perTopic) continue
      writeNewQuestions(topic, perTopic - unasked, config, saved, history.keys, concepts[topic.title], distractors)
    }
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
      // Which questions were asked (synced with the attempt), so later quizzes can ask new ones.
      detailsJson = org.json.JSONArray().also { arr ->
        questions.forEach { q ->
          arr.put(org.json.JSONObject().put("k", LocalAiEngine.questionKey(q)).put("t", q.topicTitle).put("ok", evaluations[q.id]?.isCorrect == true))
        }
      }.toString()
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

/**
 * What [new] has that [old] didn't: the tail when the conversation simply grew, otherwise the
 * paragraphs that are new (a re-fetch can format the start slightly differently).
 */
fun newChatMaterial(old: String, new: String): String {
  val o = old.trim()
  val n = new.trim()
  if (n == o || n.isBlank()) return ""
  if (o.isNotBlank() && n.startsWith(o)) return n.substring(o.length).trim()
  fun norm(t: String) = t.lowercase().replace(Regex("""\s+"""), " ").trim()
  val known = o.split(Regex("""\n\s*\n""")).map(::norm).toHashSet()
  return n.split(Regex("""\n\s*\n""")).filter { it.isNotBlank() && norm(it) !in known }.joinToString("\n\n")

}

/** Today as "yyyy-MM-dd" in this device's time zone. */
internal fun todayIso(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
