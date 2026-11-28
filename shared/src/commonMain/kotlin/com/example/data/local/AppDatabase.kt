package com.example.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import com.example.data.model.QuizAttempt
import com.example.data.model.QuizQuestion
import com.example.data.model.StudyTask
import com.example.data.model.StudyTopic
import com.example.data.model.Subject
import com.example.data.model.TimetableSession
import com.example.data.model.TutorMessageEntity
import com.example.data.model.TutorSession

import com.example.data.model.ImportedChat
import com.example.data.model.ExtractedConcept

@Database(
  entities = [
    Subject::class,
    TimetableSession::class,
    StudyTopic::class,
    QuizQuestion::class,
    QuizAttempt::class,
    StudyTask::class,
    TutorSession::class,
    TutorMessageEntity::class,
    com.example.data.model.ScheduleFormat::class,
    ImportedChat::class,
    ExtractedConcept::class,
    com.example.data.model.StudyNote::class,
    com.example.data.model.CourseDocument::class
  ],
  version = 13,
  exportSchema = false
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
  abstract fun studyPilotDao(): StudyPilotDao

  companion object {
    /** File name on every platform; the Android app has always used this name. */
    const val FILE_NAME = "studypilot_db"

    /** Shared settings for a builder from either platform. */
    fun configure(builder: Builder<AppDatabase>): AppDatabase = builder
      .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
      // Real migrations come first, so an upgrade keeps everyone's data. Only the old versions before
      // them start over. A database from a NEWER version is never emptied: opening it fails instead
      // (an older copy of the app must not wipe a newer one's data).
      .addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
      .fallbackToDestructiveMigrationFrom(true, 1, 2, 3, 4, 5, 6, 7)
      .build()
  }
}

/** Version 9 adds the user's own notes and changes nothing else. */
/** Version 10 gives notes a topic (heading) under their chapter (title). */
/** Version 13 tracks making a quiz from each course file (status and progress). */
val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
  override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
    val existing = mutableSetOf<String>()
    connection.prepare("PRAGMA table_info(`course_documents`)").use { st -> while (st.step()) existing += st.getText(1) }
    if ("quizStatus" !in existing) connection.prepare("ALTER TABLE `course_documents` ADD COLUMN `quizStatus` TEXT NOT NULL DEFAULT ''").use { it.step() }
    if ("quizProgress" !in existing) connection.prepare("ALTER TABLE `course_documents` ADD COLUMN `quizProgress` INTEGER NOT NULL DEFAULT 0").use { it.step() }
    // Files chosen before quizzes were made this way get a proper quiz.
    connection.prepare("UPDATE `course_documents` SET `quizStatus` = 'queued' WHERE `useForStudy` = 1").use { it.step() }
  }
}

/**
 * Version 12 keeps course files as files on the PC (their path and size) and lets the student choose
 * which ones the tutor and quizzes use; none are chosen at first.
 */
val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
  override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
    // Only the columns that aren't there yet, so a database that already has them upgrades too.
    val existing = mutableSetOf<String>()
    connection.prepare("PRAGMA table_info(`course_documents`)").use { st -> while (st.step()) existing += st.getText(1) }
    listOf(
      "localPath" to "TEXT NOT NULL DEFAULT ''",
      "sizeBytes" to "INTEGER NOT NULL DEFAULT 0",
      "useForStudy" to "INTEGER NOT NULL DEFAULT 0"
    ).filter { it.first !in existing }.forEach { (name, type) ->
      connection.prepare("ALTER TABLE `course_documents` ADD COLUMN `$name` $type").use { it.step() }
    }
  }
}

/** Version 11 adds course files' text for "College notes". */
val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
  override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
    connection.prepare(
      "CREATE TABLE IF NOT EXISTS `course_documents` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` TEXT NOT NULL, " +
        "`subjectId` INTEGER NOT NULL, `subjectName` TEXT NOT NULL, `title` TEXT NOT NULL, `source` TEXT NOT NULL, " +
        "`externalId` TEXT NOT NULL, `url` TEXT NOT NULL, `fileType` TEXT NOT NULL, `remoteUpdatedAt` TEXT NOT NULL, " +
        "`pageCount` INTEGER NOT NULL, `text` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL)"
    ).use { it.step() }
  }
}

val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
  override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
    connection.prepare("ALTER TABLE `notes` ADD COLUMN `heading` TEXT NOT NULL DEFAULT ''").use { it.step() }
    // Notes from version 9 had one title, which named the note itself: it becomes the topic.
    connection.prepare("UPDATE `notes` SET `heading` = `title`, `title` = ''").use { it.step() }
  }
}

val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
  override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
    connection.prepare(
      "CREATE TABLE IF NOT EXISTS `notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` TEXT NOT NULL, " +
        "`title` TEXT NOT NULL, `body` TEXT NOT NULL, `subjectName` TEXT NOT NULL, `pinned` INTEGER NOT NULL, " +
        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
    ).use { it.step() }
  }
}

/** Generated by Room for each platform. */
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
  override fun initialize(): AppDatabase
}
