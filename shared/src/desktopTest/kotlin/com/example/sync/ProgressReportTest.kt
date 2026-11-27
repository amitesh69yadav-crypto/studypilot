package com.example.sync

import com.example.data.export.ProgressReport
import com.example.data.export.progressDocx
import com.example.data.export.progressXlsx
import com.example.data.model.QuizAttempt
import com.example.data.model.StudyTopic
import com.example.data.model.TimetableSession
import java.io.File
import java.util.Date
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgressReportTest {
  private val report = ProgressReport(
    studentName = "Test Student",
    generatedAt = Date(),
    topics = listOf(
      StudyTopic(id = 1, subjectId = 1, subjectName = "Maths", title = "Integration <by parts> & limits", dateStudied = "2026-11-20", descriptionNotes = "", masteryLevel = "MASTERED", timesTested = 4, timesCorrect = 4),
      StudyTopic(id = 2, subjectId = 1, subjectName = "Maths", title = "Series", dateStudied = "2026-11-21", descriptionNotes = "", masteryLevel = "STRUGGLING", timesTested = 4, timesCorrect = 1),
      StudyTopic(id = 3, subjectId = 2, subjectName = "Physics", title = "Waves", dateStudied = "2026-11-22", descriptionNotes = "")
    ),
    attempts = listOf(
      QuizAttempt(id = 1, title = "Weekend quiz", dateAttempted = "2026-11-26 20:14", totalQuestions = 2, correctCount = 1, scorePercentage = 50, topicsCoveredCount = 2, topicsSkippedCount = 0,
        detailsJson = """[{"k":"what is a series","t":"Series","ok":false},{"k":"integrate x","t":"Integration","ok":true}]""")
    ),
    sessions = listOf(TimetableSession(subjectId = 1, subjectName = "Maths", topic = "Revision", date = "2026-11-27", startTime = "09:00", endTime = "10:00")),
    studyStreak = 3,
    weekHours = listOf("Mon" to 1.5f, "Tue" to 0f, "Wed" to 2f, "Thu" to 0f, "Fri" to 1f, "Sat" to 0f, "Sun" to 0f)
  )

  private fun entries(bytes: ByteArray): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    ZipInputStream(bytes.inputStream()).use { z ->
      while (true) {
        val e = z.nextEntry ?: break
        out[e.name] = z.readBytes().toString(Charsets.UTF_8)
      }
    }
    return out
  }

  @Test
  fun workbookHasEverySheetAndEscapesText() {
    val bytes = progressXlsx(report)
    val files = entries(bytes)
    assertTrue("xl/workbook.xml" in files)
    assertEquals(7, files.keys.count { it.startsWith("xl/worksheets/sheet") })
    val topics = files.getValue("xl/worksheets/sheet3.xml")
    assertTrue("Integration &lt;by parts&gt; &amp; limits" in topics)
    assertTrue("Needs review" in topics)
    assertTrue("what is a series".replaceFirstChar { it.uppercase() } in files.getValue("xl/worksheets/sheet5.xml"))
    File("build/report-check").mkdirs()
    File("build/report-check/report.xlsx").writeBytes(bytes)
  }

  @Test
  fun documentHasSummaryAndTables() {
    val bytes = progressDocx(report)
    val doc = entries(bytes).getValue("word/document.xml")
    assertTrue("3 topics recorded: 1 mastered" in doc)
    assertTrue("Topics to review first" in doc)
    assertTrue("<w:tbl>" in doc)
    File("build/report-check").mkdirs()
    File("build/report-check/report.docx").writeBytes(bytes)
  }
}
