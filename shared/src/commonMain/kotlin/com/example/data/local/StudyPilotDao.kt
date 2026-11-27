package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.ImportedChat
import com.example.data.model.ExtractedConcept
import com.example.data.model.QuizAttempt
import com.example.data.model.QuizQuestion
import com.example.data.model.StudyTask
import com.example.data.model.StudyTopic
import com.example.data.model.Subject
import com.example.data.model.TimetableSession
import com.example.data.model.TutorMessageEntity
import com.example.data.model.TutorSession
import kotlinx.coroutines.flow.Flow

@Dao
interface StudyPilotDao {

  // --- Subjects ---
  @Query("SELECT * FROM subjects WHERE userId = :userId ORDER BY name ASC")
  fun getSubjectsForUser(userId: String): Flow<List<Subject>>

  @Query("SELECT * FROM subjects ORDER BY name ASC")
  fun getAllSubjects(): Flow<List<Subject>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSubject(subject: Subject): Long

  @Delete
  suspend fun deleteSubject(subject: Subject)

  // --- Timetable Sessions ---
  @Query("SELECT * FROM timetable_sessions WHERE userId = :userId ORDER BY date ASC, startTime ASC")
  fun getSessionsForUser(userId: String): Flow<List<TimetableSession>>

  @Query("SELECT * FROM timetable_sessions WHERE userId = :userId ORDER BY date ASC, startTime ASC")
  suspend fun getSessionsListForUser(userId: String): List<TimetableSession>

  @Query("SELECT * FROM timetable_sessions WHERE userId = :userId AND date = :date ORDER BY startTime ASC")
  fun getSessionsForDateAndUser(userId: String, date: String): Flow<List<TimetableSession>>

  @Query("SELECT * FROM timetable_sessions ORDER BY date ASC, startTime ASC")
  fun getAllSessions(): Flow<List<TimetableSession>>

  @Query("SELECT * FROM timetable_sessions ORDER BY date ASC, startTime ASC")
  suspend fun getAllSessionsList(): List<TimetableSession>

  @Query("SELECT * FROM timetable_sessions WHERE id = :id LIMIT 1")
  suspend fun getSessionById(id: Long): TimetableSession?

  @Query("SELECT * FROM timetable_sessions WHERE date = :date ORDER BY startTime ASC")
  fun getSessionsForDate(date: String): Flow<List<TimetableSession>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSession(session: TimetableSession): Long

  @Update
  suspend fun updateSession(session: TimetableSession)

  @Query("DELETE FROM timetable_sessions WHERE id = :id")
  suspend fun deleteSessionById(id: Long)

  // --- Study Topics ---
  @Query("SELECT * FROM study_topics WHERE userId = :userId ORDER BY dateStudied DESC, id DESC")
  fun getTopicsForUser(userId: String): Flow<List<StudyTopic>>

  @Query("SELECT * FROM study_topics WHERE userId = :userId AND subjectId = :subjectId")
  fun getTopicsForSubjectAndUser(userId: String, subjectId: Long): Flow<List<StudyTopic>>

  @Query("SELECT * FROM study_topics ORDER BY dateStudied DESC, id DESC")
  fun getAllTopics(): Flow<List<StudyTopic>>

  @Query("SELECT * FROM study_topics WHERE subjectId = :subjectId")
  fun getTopicsForSubject(subjectId: Long): Flow<List<StudyTopic>>

  @Query("SELECT * FROM study_topics WHERE id = :id LIMIT 1")
  suspend fun getTopicById(id: Long): StudyTopic?

  @Query("SELECT * FROM study_topics WHERE id IN (:topicIds)")
  suspend fun getTopicsByIds(topicIds: List<Long>): List<StudyTopic>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTopic(topic: StudyTopic): Long

  @Update
  suspend fun updateTopic(topic: StudyTopic)

  @Update
  suspend fun updateTopics(topics: List<StudyTopic>)

  @Query("DELETE FROM study_topics WHERE id = :id")
  suspend fun deleteTopicById(id: Long)

  // --- Quiz Questions ---
  @Query("SELECT * FROM quiz_questions WHERE topicId = :topicId")
  fun getQuestionsForTopic(topicId: Long): Flow<List<QuizQuestion>>

  @Query("SELECT * FROM quiz_questions")
  fun getAllQuestions(): Flow<List<QuizQuestion>>

  @Query("SELECT * FROM quiz_questions WHERE topicId IN (:topicIds)")
  suspend fun getQuestionsForTopicIds(topicIds: List<Long>): List<QuizQuestion>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertQuestions(questions: List<QuizQuestion>): List<Long>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertQuestion(question: QuizQuestion): Long

  // --- Quiz Attempts ---
  @Query("SELECT * FROM quiz_attempts WHERE userId = :userId ORDER BY id DESC")
  fun getQuizAttemptsForUser(userId: String): Flow<List<QuizAttempt>>

  @Query("SELECT * FROM quiz_attempts ORDER BY id DESC")
  fun getAllQuizAttempts(): Flow<List<QuizAttempt>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertQuizAttempt(attempt: QuizAttempt): Long

  // --- Study Tasks & Exams ---
  @Query("SELECT * FROM study_tasks WHERE userId = :userId ORDER BY dueDate ASC")
  fun getTasksForUser(userId: String): Flow<List<StudyTask>>

  @Query("SELECT * FROM study_tasks ORDER BY dueDate ASC")
  fun getAllTasks(): Flow<List<StudyTask>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTask(task: StudyTask): Long

  @Update
  suspend fun updateTask(task: StudyTask)

  @Query("DELETE FROM study_tasks WHERE id = :id")
  suspend fun deleteTaskById(id: Long)

  // Batch insert for sample data
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSubjects(subjects: List<Subject>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSessions(sessions: List<TimetableSession>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTopics(topics: List<StudyTopic>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTasks(tasks: List<StudyTask>)

  // --- AI Tutor Sessions & Messages ---
  @Query("SELECT * FROM tutor_sessions WHERE userId = :userId ORDER BY lastUpdatedAt DESC")
  fun getTutorSessionsForUser(userId: String): Flow<List<TutorSession>>

  @Query("SELECT * FROM tutor_sessions WHERE userId = :userId AND subjectId = :subjectId ORDER BY lastUpdatedAt DESC")
  fun getTutorSessionsForSubjectAndUser(userId: String, subjectId: Long): Flow<List<TutorSession>>

  @Query("SELECT * FROM tutor_sessions ORDER BY lastUpdatedAt DESC")
  fun getAllTutorSessions(): Flow<List<TutorSession>>

  @Query("SELECT * FROM tutor_sessions WHERE subjectId = :subjectId ORDER BY lastUpdatedAt DESC")
  fun getTutorSessionsForSubject(subjectId: Long): Flow<List<TutorSession>>

  @Query("SELECT * FROM tutor_sessions WHERE id = :id LIMIT 1")
  suspend fun getTutorSessionById(id: Long): TutorSession?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTutorSession(session: TutorSession): Long

  @Update
  suspend fun updateTutorSession(session: TutorSession)

  @Query("DELETE FROM tutor_sessions WHERE id = :id")
  suspend fun deleteTutorSessionById(id: Long)

  @Query("DELETE FROM tutor_sessions")
  suspend fun clearAllTutorSessions()

  @Query("SELECT * FROM tutor_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
  fun getMessagesForSession(sessionId: Long): Flow<List<TutorMessageEntity>>

  @Query("SELECT * FROM tutor_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
  suspend fun getMessagesForSessionSync(sessionId: Long): List<TutorMessageEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTutorMessage(message: TutorMessageEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertTutorMessages(messages: List<TutorMessageEntity>)

  @Query("DELETE FROM tutor_messages WHERE sessionId = :sessionId")
  suspend fun deleteMessagesForSession(sessionId: Long)

  @Query("DELETE FROM tutor_messages")
  suspend fun clearAllTutorMessages()

  @Transaction
  suspend fun deleteTutorSessionAtomic(sessionId: Long) {
    deleteMessagesForSession(sessionId)
    deleteTutorSessionById(sessionId)
  }

  @Query("DELETE FROM tutor_messages WHERE sessionId IN (SELECT id FROM tutor_sessions WHERE userId = :userId)")
  suspend fun deleteTutorMessagesForUser(userId: String)

  @Transaction
  suspend fun clearTutorHistoryForUser(userId: String) {
    deleteTutorMessagesForUser(userId)
    deleteTutorSessionsForUser(userId)
  }

  @Query("DELETE FROM quiz_questions WHERE topicId IN (SELECT id FROM study_topics WHERE userId = :userId)")
  suspend fun deleteQuestionsForUser(userId: String)

  // --- Schedule Formats / Templates ---
  @Query("SELECT * FROM schedule_formats WHERE userId = :userId ORDER BY createdAt ASC")
  fun getScheduleFormatsForUser(userId: String): Flow<List<com.example.data.model.ScheduleFormat>>

  @Query("SELECT * FROM schedule_formats ORDER BY createdAt ASC")
  fun getAllScheduleFormats(): Flow<List<com.example.data.model.ScheduleFormat>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertScheduleFormat(format: com.example.data.model.ScheduleFormat): Long

  @Query("DELETE FROM schedule_formats WHERE id = :id")
  suspend fun deleteScheduleFormatById(id: Long)

  @Query("DELETE FROM timetable_sessions WHERE date = :date")
  suspend fun deleteSessionsForDate(date: String)

  @Query("DELETE FROM timetable_sessions WHERE userId = :userId AND date = :date")
  suspend fun deleteSessionsForDateAndUser(userId: String, date: String)

  @Transaction
  suspend fun replaceSessionsForDateAndUser(userId: String, date: String, sessions: List<TimetableSession>) {
    deleteSessionsForDateAndUser(userId, date)
    if (sessions.isNotEmpty()) insertSessions(sessions)
  }

  @Transaction
  suspend fun replaceSessionsForDate(date: String, sessions: List<TimetableSession>) {
    deleteSessionsForDate(date)
    if (sessions.isNotEmpty()) insertSessions(sessions)
  }

  // --- Per-User Account Data Deletion ---
  @Query("DELETE FROM subjects WHERE userId = :userId")
  suspend fun deleteSubjectsForUser(userId: String)

  @Query("DELETE FROM timetable_sessions WHERE userId = :userId")
  suspend fun deleteSessionsForUser(userId: String)

  @Query("DELETE FROM study_topics WHERE userId = :userId")
  suspend fun deleteTopicsForUser(userId: String)

  @Query("DELETE FROM study_tasks WHERE userId = :userId")
  suspend fun deleteTasksForUser(userId: String)

  @Query("DELETE FROM quiz_attempts WHERE userId = :userId")
  suspend fun deleteQuizAttemptsForUser(userId: String)

  @Query("DELETE FROM tutor_sessions WHERE userId = :userId")
  suspend fun deleteTutorSessionsForUser(userId: String)

  @Query("DELETE FROM schedule_formats WHERE userId = :userId")
  suspend fun deleteScheduleFormatsForUser(userId: String)

  // --- Imported AI Chats ---
  @Query("SELECT * FROM imported_chats WHERE userId = :userId ORDER BY importedAt DESC")
  fun getImportedChatsForUser(userId: String): Flow<List<ImportedChat>>

  @Query("SELECT * FROM imported_chats ORDER BY importedAt DESC")
  fun getAllImportedChats(): Flow<List<ImportedChat>>

  @Query("SELECT * FROM imported_chats WHERE id = :id LIMIT 1")
  suspend fun getImportedChatById(id: Long): ImportedChat?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertImportedChat(chat: ImportedChat): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertImportedChats(chats: List<ImportedChat>)

  @Update
  suspend fun updateImportedChat(chat: ImportedChat)

  @Query("DELETE FROM imported_chats WHERE id = :id")
  suspend fun deleteImportedChatById(id: Long)

  @Query("DELETE FROM imported_chats WHERE userId = :userId")
  suspend fun deleteImportedChatsForUser(userId: String)

  // --- Extracted Academic Concepts (Learning Memory) ---
  @Query("SELECT * FROM extracted_concepts WHERE userId = :userId ORDER BY extractedAt DESC")
  fun getExtractedConceptsForUser(userId: String): Flow<List<ExtractedConcept>>

  @Query("SELECT * FROM extracted_concepts WHERE userId = :userId AND sourceChatId = :chatId ORDER BY id ASC")
  fun getConceptsForChat(userId: String, chatId: Long): Flow<List<ExtractedConcept>>

  @Query("SELECT * FROM extracted_concepts WHERE userId = :userId AND sourceChatId = :chatId ORDER BY id ASC")
  suspend fun getConceptsForChatSync(userId: String, chatId: Long): List<ExtractedConcept>

  @Query("SELECT * FROM extracted_concepts WHERE userId = :userId AND subjectId = :subjectId ORDER BY extractedAt DESC")
  fun getConceptsForSubjectAndUser(userId: String, subjectId: Long): Flow<List<ExtractedConcept>>

  @Query("SELECT * FROM extracted_concepts WHERE id = :id LIMIT 1")
  suspend fun getExtractedConceptById(id: Long): ExtractedConcept?

  @Query("SELECT * FROM extracted_concepts WHERE userId = :userId AND topic = :topic LIMIT 1")
  suspend fun getConceptByTopic(userId: String, topic: String): ExtractedConcept?

  @Query("DELETE FROM quiz_questions WHERE id IN (:ids)")
  suspend fun deleteQuestionsByIds(ids: List<Long>)

  @Query("DELETE FROM quiz_questions WHERE topicId = :topicId")
  suspend fun deleteQuestionsForTopic(topicId: Long)

  @Query("SELECT * FROM extracted_concepts WHERE userId = :userId AND conceptName IN (:names)")
  suspend fun getConceptsByNames(userId: String, names: List<String>): List<ExtractedConcept>

  @Query("SELECT * FROM extracted_concepts WHERE userId = :userId AND topic IN (:topics)")
  suspend fun getConceptsByTopics(userId: String, topics: List<String>): List<ExtractedConcept>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertExtractedConcept(concept: ExtractedConcept): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertExtractedConcepts(concepts: List<ExtractedConcept>)

  @Update
  suspend fun updateExtractedConcept(concept: ExtractedConcept)

  @Update
  suspend fun updateExtractedConcepts(concepts: List<ExtractedConcept>)

  @Query("DELETE FROM extracted_concepts WHERE id = :id")
  suspend fun deleteExtractedConceptById(id: Long)

  @Query("DELETE FROM extracted_concepts WHERE sourceChatId = :chatId")
  suspend fun deleteConceptsForChat(chatId: Long)

  @Transaction
  suspend fun deleteImportedChatAtomic(chatId: Long) {
    deleteConceptsForChat(chatId)
    deleteImportedChatById(chatId)
  }

  @Query("UPDATE extracted_concepts SET subjectId = :subjectId, subjectName = :subjectName WHERE sourceChatId = :chatId")
  suspend fun updateConceptsSubjectForChat(chatId: Long, subjectId: Long?, subjectName: String)

  @Query("DELETE FROM extracted_concepts WHERE userId = :userId")
  suspend fun deleteExtractedConceptsForUser(userId: String)

  // --- Notes ---
  @Query("SELECT * FROM notes WHERE userId = :userId ORDER BY pinned DESC, updatedAt DESC")
  fun getNotesForUser(userId: String): Flow<List<com.example.data.model.StudyNote>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertNote(note: com.example.data.model.StudyNote): Long

  @Update
  suspend fun updateNote(note: com.example.data.model.StudyNote)

  @Query("DELETE FROM notes WHERE id = :id")
  suspend fun deleteNote(id: Long)
}
