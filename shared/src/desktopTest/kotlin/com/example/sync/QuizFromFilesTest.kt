package com.example.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.data.ai.LectureQuiz
import com.example.data.local.AppDatabase
import com.example.data.model.CourseDocument
import com.example.data.model.QuizConfig
import com.example.data.model.StudyTopic
import com.example.data.repository.StudyPilotRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Quizzes from course files: the topics they teach, not course information, and real questions. */
class QuizFromFilesTest {

  private val slides = listOf(
    "Info for students\nLecturer: Dr. Peeters\nEmail: peeters@vub.be\nOffice hours: Tuesday 14:00\nGrading: exam 70%, project 30%\nDeadline for the project: 20 December",
    "Medium Access Control\nCSMA/CA is a protocol that listens before sending and backs off randomly when the channel is busy.\nCollisions are avoided rather than detected in wireless networks.",
    "Medium Access Control (cont.)\nThe RTS/CTS handshake reserves the channel before a long frame.\nThe hidden terminal problem happens when two stations cannot hear each other.",
    "Time Slotted Channel Hopping\nTSCH is a MAC mode that divides time into slots and hops between channels.\nAdvantages of TSCH:\n- Predictable latency\n- Resilience to interference\n- Low power use",
    "Routing\nA routing table is a list that maps destination prefixes to next hops.\nOSPF is a link-state protocol that floods link-state advertisements to every router.\nRIP is a distance-vector protocol that shares its table with neighbours every 30 seconds.\nDisadvantages of RIP:\n- Slow convergence\n- Count to infinity\n- Hop limit of 15"
  )

  @Test
  fun builtInReaderFindsTeachingTopicsAndSkipsCourseInfo() {
    val topics = LectureQuiz.topicsFromPages(slides, "Lecture 3")
    assertEquals(listOf("Medium Access Control", "Time Slotted Channel Hopping", "Routing"), topics.map { it.title })
    assertEquals(listOf(2, 3), topics.first().pages) // the "(cont.)" slide joins its topic
    assertTrue(LectureQuiz.isAdministrative("Info for students", slides[0]))
    assertTrue(!LectureQuiz.isAdministrative("Routing", slides[4]))
  }

  @Test
  fun repeatedHeadersAndFootersAreNotTopics() {
    // Every slide carries a footer with the date, lecturers and page number (as in real lecture PDFs).
    val withFooters = slides.mapIndexed { i, s -> "04-09-K. Steenhaut & J. Tiberghien${i + 1}\n$s\nJ. Kurose & K. Ross 9th ed" }
    val titles = LectureQuiz.topicsFromPages(withFooters, "Lecture 3").map { it.title }
    assertEquals(listOf("Medium Access Control", "Time Slotted Channel Hopping", "Routing"), titles)
  }

  @Test
  fun builtInQuestionsAreRealAndAnswerable() {
    val topic = StudyTopic(id = 1, subjectId = 1, subjectName = "Networks", title = "Routing", dateStudied = "", descriptionNotes = slides[4] + "\n\n" + slides[1] + "\n\n" + slides[3])
    val defs = LectureQuiz.definitions(topic.descriptionNotes).map { it.term }
    assertTrue("OSPF" in defs && "RIP" in defs && "CSMA/CA" in defs, "found: $defs")
    val questions = LectureQuiz.questions(topic, 30, "MEDIUM")
    assertTrue(questions.size >= 6, "only ${questions.size}")
    assertTrue(questions.any { it.questionText.startsWith("What is ") })
    assertTrue(questions.any { it.questionType == "TRUE_FALSE" })
    assertTrue(questions.any { it.questionText.startsWith("Which of these belongs under") })
    for (q in questions.filter { it.questionType == "MULTIPLE_CHOICE" }) {
      val options = q.optionsJson.split(if ('|' in q.optionsJson) "|" else ",").map { it.trim() }
      assertTrue(q.correctAnswer in options, "answer missing from options: ${q.questionText}")
      assertEquals(4, options.distinct().size, q.questionText)
    }
    // The app's own note labels never become list questions.
    val labelled = topic.copy(descriptionNotes = "A summary.\n\nKey points:\n- one fact here\n- another fact\n\nFrom the file (p. 3, 4):\nThe Chappe Telegraph\nThe Morse Telegraph")
    assertTrue(LectureQuiz.lists(labelled.descriptionNotes).isEmpty())
    // No "Which of these is from your notes" filler.
    assertTrue(questions.none { "is from your" in it.questionText })
  }

  @Test
  fun anUploadedDocumentIsReadIntoItsSubjectAndQuizzedOnItsOwn() = runBlocking<Unit> {
    val file = File.createTempFile("quiz-files", ".db").also { it.delete() }
    val db = AppDatabase.configure(Room.databaseBuilder<AppDatabase>(name = file.absolutePath).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO))
    val dir = kotlin.io.path.createTempDirectory("uploads").toFile()
    try {
      val dao = db.studyPilotDao()
      // No model in tests: the built-in reader and question writer do the work.
      val repo = StudyPilotRepository(dao, ai = com.example.data.ai.StudyAi().also { it.source = com.example.data.ai.AiSource.BUILT_IN })
      val subjectId = repo.addSubject("Advanced IT Networks", "AIN", "#000000", "u")
      // A course page imported from Canvas: knowledge only, never quizzed.
      repo.addTopic(StudyTopic(userId = "u", subjectId = subjectId, subjectName = "Advanced IT Networks", title = "Info for students", dateStudied = "", descriptionNotes = slides[0], sourceType = "IMPORTED_LMS"))
      // Something else in the same subject, which a quiz on the document must leave out.
      repo.addTopic(StudyTopic(userId = "u", subjectId = subjectId, subjectName = "Advanced IT Networks", title = "Ethernet", dateStudied = "", descriptionNotes = "Ethernet is a wired LAN technology that frames data with MAC addresses."))

      val docx = java.io.ByteArrayOutputStream().also { out ->
        java.util.zip.ZipOutputStream(out).use { z ->
          z.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
          // Each slide's first line is a Heading 1, as in a real lecture handout.
          z.write(slides.joinToString("") { slide ->
            slide.lines().mapIndexed { i, line ->
              val style = if (i == 0) "<w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>" else ""
              "<w:p>$style<w:r><w:t>${line.replace("&", "&amp;")}</w:t></w:r></w:p>"
            }.joinToString("")
          }.toByteArray())
          z.closeEntry()
        }
      }.toByteArray()
      // Uploaded into a new subject: the subject is made, the file kept, the text read.
      val docId = repo.addUploadedDocument("u", "Lecture 3.docx", docx, "Wireless Networks", dir)
      var doc = dao.getCourseDocument(docId)!!
      assertEquals("Wireless Networks" to "upload", doc.subjectName to doc.source)
      assertTrue(dao.getSubjectsForUser("u").first().any { it.name == "Wireless Networks" })
      assertTrue(File(doc.localPath).isFile && doc.localPath.contains("Wireless Networks"))

      assertTrue(repo.scanDocument("u", docId))
      doc = dao.getCourseDocument(docId)!!
      assertTrue(doc.quizStatus.startsWith("ready") && doc.quizProgress == 100, doc.quizStatus)
      val made = repo.topicsOfDocument("u", doc.externalId)
      assertTrue(made.isNotEmpty() && made.all { it.subjectName == "Wireless Networks" }, made.map { it.title }.toString())
      assertTrue(made.none { it.title == "Info for students" })

      // A quiz on just this document, reporting progress topic by topic.
      val progress = mutableListOf<Pair<Int, Int>>()
      val quiz = repo.assembleWeekendQuiz(QuizConfig(questionCount = 6), userId = "u", onlyTopicIds = made.map { it.id }.toSet(),
        onProgress = { done, total, _ -> synchronized(progress) { progress += done to total } })
      assertTrue(quiz.questions.isNotEmpty())
      assertTrue(quiz.questions.all { q -> made.any { it.id == q.topicId } }, quiz.questions.map { it.topicTitle }.toString())
      assertTrue(progress.isNotEmpty() && progress.last().first == progress.last().second, progress.toString())

      // The whole-subject quiz never uses course pages.
      val all = repo.assembleWeekendQuiz(QuizConfig(questionCount = 10), userId = "u")
      assertTrue(all.questions.none { it.topicTitle == "Info for students" }, all.questions.map { it.topicTitle }.toString())

      // Removing it takes its topics, questions and the kept copy.
      val path = doc.localPath
      repo.removeDocument("u", docId)
      assertTrue(repo.topicsOfDocument("u", doc.externalId).isEmpty())
      assertTrue(dao.getCourseDocument(docId) == null && !File(path).exists())
      assertTrue(dao.getOrphanQuestionIds().isEmpty())
    } finally {
      db.close(); file.delete(); dir.deleteRecursively()
    }
  }
}
