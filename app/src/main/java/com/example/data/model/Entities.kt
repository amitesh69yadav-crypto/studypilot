package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subjects")
data class Subject(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val userId: String = "",
  val name: String,
  val code: String,
  val colorHex: String,
  val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "timetable_sessions")
data class TimetableSession(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val userId: String = "",
  val subjectId: Long,
  val subjectName: String,
  val topic: String,
  val date: String, // "YYYY-MM-DD"
  val startTime: String, // "HH:mm"
  val endTime: String, // "HH:mm"
  val location: String = "Campus Lecture Hall",
  val notes: String = "",
  val recurringDays: String = "", // e.g., "Mon,Wed,Fri" or empty
  val reminderMinutes: Int = 0,
  val status: String = "SCHEDULED", // SCHEDULED, COMPLETED, MISSED, RESCHEDULED
  val isAlarmEnabled: Boolean = true,
  val googleCalendarEventId: String? = null,
  val sessionCategory: String = "ACADEMIC", // ACADEMIC, FOOD, SLEEP, EXERCISE, PERSONAL
  val isRoutineOnly: Boolean = false
)

@Entity(tableName = "study_topics")
data class StudyTopic(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val userId: String = "",
  val subjectId: Long,
  val subjectName: String,
  val title: String,
  val dateStudied: String, // "YYYY-MM-DD"
  val descriptionNotes: String,
  val sourceType: String = "MANUAL", // MANUAL, UPLOAD, PASTED
  val uploadedFileName: String? = null,
  val masteryLevel: String = "UNTESTED", // UNTESTED, STRUGGLING, FAMILIAR, MASTERED
  val repetitionIntervalDays: Int = 1,
  val nextRevisionDate: String = "", // "YYYY-MM-DD"
  val timesTested: Int = 0,
  val timesCorrect: Int = 0,
  val lastTestedDate: String? = null,
  val aiSummary: String? = null
)

@Entity(tableName = "quiz_questions")
data class QuizQuestion(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val topicId: Long,
  val subjectName: String,
  val topicTitle: String,
  val questionType: String, // MULTIPLE_CHOICE, TRUE_FALSE, SHORT_ANSWER, CODING
  val difficulty: String = "MEDIUM", // EASY, MEDIUM, HARD
  val questionText: String,
  val optionsJson: String = "", // Comma/pipe separated or JSON list
  val correctAnswer: String,
  val explanation: String,
  val codeStarter: String? = null,
  val sourceConversationId: Long? = null,
  val sourceConversationTitle: String? = null
)

@Entity(tableName = "quiz_attempts")
data class QuizAttempt(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val userId: String = "",
  val title: String,
  val dateAttempted: String, // "YYYY-MM-DD HH:mm"
  val totalQuestions: Int,
  val correctCount: Int,
  val scorePercentage: Int,
  val topicsCoveredCount: Int,
  val topicsSkippedCount: Int,
  val detailsJson: String = "" // Serialized answer breakdown
)

@Entity(tableName = "study_tasks")
data class StudyTask(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val userId: String = "",
  val subjectName: String,
  val title: String,
  val dueDate: String, // "YYYY-MM-DD"
  val type: String = "ASSIGNMENT", // ASSIGNMENT, EXAM, TASK
  val isCompleted: Boolean = false,
  val priority: String = "MEDIUM" // HIGH, MEDIUM, LOW
)

data class QuizConfig(
  val quizDays: String = "Saturday & Sunday",
  val quizTime: String = "10:00 AM",
  val questionCount: Int = 10,
  val difficulty: String = "MIXED", // EASY, MEDIUM, HARD, MIXED
  val questionTypes: Set<String> = setOf("MULTIPLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER", "CODING"),
  val prioritizeWeakTopics: Boolean = true,
  val questionSource: String = "ALL" // ALL, MANUAL, IMPORTED_CHAT, UPLOAD
)

@Entity(tableName = "imported_chats")
data class ImportedChat(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val userId: String = "",
  val title: String,
  val subjectId: Long? = null,
  val subjectName: String = "General",
  val sourceUrl: String = "",
  val rawConversation: String,
  val tags: String = "", // comma-separated tags
  val aiPlatform: String = "ChatGPT", // ChatGPT, Gemini, Claude, DeepSeek, Other
  val importedAt: Long = System.currentTimeMillis(),
  val lastProcessedAt: Long? = null,
  val status: String = "PROCESSED", // PENDING, PROCESSING, PROCESSED, ERROR
  val extractedConceptsCount: Int = 0,
  val chunkCount: Int = 1,
  val wordCount: Int = 0,
  val messageCount: Int = 0,
  val notes: String = ""
)

@Entity(tableName = "extracted_concepts")
data class ExtractedConcept(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val userId: String = "",
  val sourceChatId: Long,
  val sourceChatTitle: String,
  val subjectId: Long? = null,
  val subjectName: String = "General",
  val topic: String, // Main topic e.g. "Binary Search Trees"
  val subtopic: String = "", // Subtopic e.g. "AVL Balancing"
  val conceptName: String, // Concept e.g. "LR Rotation"
  val definition: String, // Core academic definition
  val technicalDetails: String = "", // Methodology, technical mechanism
  val formulaOrCode: String = "", // Formulas, algorithms, or code example
  val problemsSolved: String = "", // Questions asked and problems solved
  val keyFacts: String = "", // Facts and relationships
  val misconceptionsAndCorrections: String = "", // Common student traps & corrections
  val importanceScore: Int = 3, // 1 (Introductory) to 5 (Advanced Exam Core)
  val masteryLevel: String = "UNTESTED", // UNTESTED, STRUGGLING, FAMILIAR, MASTERED
  val timesTested: Int = 0,
  val timesCorrect: Int = 0,
  val lastTestedDate: String? = null,
  val nextRevisionDate: String = "",
  val extractedAt: Long = System.currentTimeMillis(),
  val relatedConceptsJson: String = "[]", // List of related concept names serialized
  val tags: String = ""
)

@Entity(tableName = "tutor_sessions")
data class TutorSession(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val userId: String = "",
  val subjectId: Long? = null,
  val subjectName: String = "General",
  val title: String,
  val previewSnippet: String,
  val modelUsed: String = "gemini-3.5-flash",
  val searchGroundingUsed: Boolean = false,
  val messageCount: Int = 0,
  val createdAt: Long = System.currentTimeMillis(),
  val lastUpdatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "tutor_messages")
data class TutorMessageEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val sessionId: Long,
  val role: String, // "user" or "model"
  val text: String,
  val timestamp: Long = System.currentTimeMillis(),
  val modelName: String = "gemini-2.5-flash",
  val searchGroundingUsed: Boolean = false,
  val groundingSourcesJson: String = "",
  val searchQueriesJson: String = "",
  val isError: Boolean = false
)

@Entity(tableName = "schedule_formats")
data class ScheduleFormat(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val userId: String = "",
  val name: String,
  val description: String = "",
  val formatSessionsJson: String = "[]", // List of SessionTemplate items serialized to JSON
  val createdAt: Long = System.currentTimeMillis()
)

data class UserProfile(
  val id: String = "guest_user",
  val name: String = "Guest",
  val email: String = "",
  val role: String = "Guest Mode",
  val university: String = "Local Session",
  val studentId: String = "GUEST",
  val avatarInitials: String = "G",
  val isLoggedIn: Boolean = false,
  val authProvider: String = "Guest Mode",
  val rememberMe: Boolean = false,
  val lastLoginTime: Long = System.currentTimeMillis()
)
