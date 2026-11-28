package com.example.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.example.data.ai.rag.KnowledgePassage
import com.example.data.connect.CanvasSource
import com.example.data.docs.DocumentText
import com.example.data.local.AppDatabase
import com.example.data.repository.StudyPilotRepository
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Course files: reading PDFs and Office files, importing them from Canvas, and answering from them. */
class CourseFilesTest {

  private fun pdf(vararg pages: String): ByteArray = PDDocument().use { doc ->
    for (text in pages) {
      val page = PDPage()
      doc.addPage(page)
      PDPageContentStream(doc, page).use { s ->
        s.beginText()
        s.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
        s.newLineAtOffset(72f, 700f)
        s.showText(text)
        s.endText()
      }
    }
    ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
  }

  private fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
    ZipOutputStream(out).use { z -> entries.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() } }
  }.toByteArray()

  @Test
  fun pdfPowerPointAndWordTextIsReadPageByPage() {
    val pages = DocumentText.pages(pdf("Routing tables map prefixes to next hops.", "OSPF uses link-state advertisements."), "pdf")
    assertEquals(2, pages.size)
    assertTrue("OSPF" in pages[1])

    val slide = { t: String -> """<p:sld><p:txBody><a:p><a:r><a:t>$t</a:t></a:r></a:p><a:p><a:r><a:t>second line</a:t></a:r></a:p></p:txBody></p:sld>""" }
    val pptx = zip(
      "ppt/slides/slide10.xml" to slide("Ten"),
      "ppt/slides/slide2.xml" to slide("Two &amp; more"),
      "ppt/slides/slide1.xml" to slide("One"),
      "ppt/notesSlides/notesSlide2.xml" to "<a:p><a:r><a:t>Mention the exam</a:t></a:r></a:p>"
    )
    val slides = DocumentText.pages(pptx, "pptx")
    assertEquals(listOf("One", "Two & more", "Ten"), slides.map { it.lines().first() })
    assertTrue(slides[1].endsWith("Notes: Mention the exam"))

    val docx = zip("word/document.xml" to """<w:body><w:p><w:r><w:t>Chapter 1</w:t></w:r></w:p><w:p><w:r><w:t xml:space="preserve">Heaps </w:t></w:r><w:r><w:t>and trees</w:t></w:r></w:p></w:body>""")
    assertEquals(listOf("Chapter 1\nHeaps and trees"), DocumentText.pages(docx, "docx"))

    assertEquals("pdf", DocumentText.typeOf("Lecture 5.PDF"))
    assertEquals(null, DocumentText.typeOf("old.ppt"))
  }

  @Test
  fun canvasFilesAreReadOnceAndAnswerWithFileAndPage() = runBlocking<Unit> {
    val downloads = AtomicInteger()
    var updatedAt = "2026-11-01T10:00:00Z"
    val lecture = pdf("Introduction to networks.", "Distance-vector routing sends the whole table to neighbours every 30 seconds.")
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base = { "http://127.0.0.1:${server.address.port}" }
    fun route(path: String, link: String? = null, body: () -> ByteArray) = server.createContext(path) { ex ->
      val bytes = body()
      link?.let { ex.responseHeaders.add("Link", it) }
      ex.sendResponseHeaders(200, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }
    route("/api/v1/courses") { """[{"id":7,"name":"Advanced IT Networks","course_code":"AIN"}]""".toByteArray() }
    // Two pages of files, joined through the Link header.
    server.createContext("/api/v1/courses/7/files") { ex ->
      val second = ex.requestURI.query.orEmpty().contains("page=2")
      val body = if (second) {
        """[{"id":2,"display_name":"old.ppt","url":"${base()}/dl/2","content-type":"application/vnd.ms-powerpoint","size":10,"updated_at":"x"}]"""
      } else {
        ex.responseHeaders.add("Link", "<${base()}/api/v1/courses/7/files?page=2>; rel=\"next\"")
        """[{"id":1,"display_name":"Lecture 5 - Routing.pdf","url":"${base()}/dl/1","content-type":"application/pdf","size":${lecture.size},"updated_at":"$updatedAt"}]"""
      }
      val bytes = body.toByteArray()
      ex.sendResponseHeaders(200, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }
    route("/dl/1") { downloads.incrementAndGet(); lecture }
    server.start()

    val file = File.createTempFile("course-files", ".db").also { it.delete() }
    val db = AppDatabase.configure(Room.databaseBuilder<AppDatabase>(name = file.absolutePath).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO))
    try {
      val repo = StudyPilotRepository(db.studyPilotDao())
      repo.addSubject("Advanced IT Networks", "AIN", "#5B6CFF", "u")
      val canvas = CanvasSource(base(), "token")
      val dir = kotlin.io.path.createTempDirectory("course-files").toFile()

      val first = repo.importCourseFiles("u", canvas, "canvas", emptySet(), dir)
      assertEquals(1, first.added) // the old .ppt is ignored
      assertEquals(1, downloads.get())
      var doc = db.studyPilotDao().getCourseDocumentsForUser("u").first().single()
      assertEquals("Advanced IT Networks", doc.subjectName)
      assertEquals("${base()}/courses/7/files/1", doc.url)
      // The original is kept under its subject, and nothing is read until the student chooses it.
      assertEquals(File(File(dir, "Advanced IT Networks"), "Lecture 5 - Routing.pdf").absolutePath, doc.localPath)
      assertTrue(lecture.contentEquals(File(doc.localPath).readBytes()))
      assertTrue(doc.text.isBlank() && !doc.useForStudy)
      assertTrue(repo.searchCourseMaterial("u", "distance vector routing table", null).isEmpty())

      // Unchanged: nothing downloaded again. Replaced on Canvas: downloaded again.
      assertEquals("Files up to date", repo.importCourseFiles("u", canvas, "canvas", emptySet(), dir).toString())
      assertEquals(1, downloads.get())
      updatedAt = "2026-11-20T10:00:00Z"
      assertEquals(1, repo.importCourseFiles("u", canvas, "canvas", emptySet(), dir).updated)
      assertEquals(2, downloads.get())

      // Chosen for study: read, made a quiz topic under its subject.
      repo.setUseForStudy(listOf(doc.id), true)
      assertEquals(1, repo.prepareChosenFiles("u"))
      doc = db.studyPilotDao().getCourseDocumentsForUser("u").first().single()
      assertEquals(2, doc.pageCount)
      val topic = db.studyPilotDao().getTopicsForUser("u").first().single { it.sourceType == "COURSE_FILE" }
      assertEquals("Lecture 5 - Routing" to "Advanced IT Networks", topic.title to topic.subjectName)
      assertTrue("Distance-vector" in topic.descriptionNotes)

      // College notes finds the right page and cites it.
      val hits = repo.searchCourseMaterial("u", "how often does distance vector routing send its table", "Advanced IT Networks")
      assertEquals("Lecture 5 - Routing.pdf, p. 2", hits.first().sourceTitle)
      val instruction = KnowledgePassage.strictInstruction(hits)
      assertTrue("Answer ONLY from this course material" in instruction && "[1] (Lecture 5 - Routing.pdf, p. 2)" in instruction)
      // Nothing on an unrelated question.
      assertTrue(repo.searchCourseMaterial("u", "photosynthesis chlorophyll", null).isEmpty())

      // No longer chosen: its quiz topic goes, the file stays.
      repo.setUseForStudy(listOf(doc.id), false)
      repo.prepareChosenFiles("u")
      assertTrue(db.studyPilotDao().getTopicsForUser("u").first().none { it.sourceType == "COURSE_FILE" })
      assertTrue(File(doc.localPath).isFile)

      // Removing the course removes its files, from the app and the disk.
      repo.deleteSubject(db.studyPilotDao().getSubjectsForUser("u").first().single())
      assertTrue(db.studyPilotDao().getCourseDocumentsForUser("u").first().isEmpty())
      assertTrue(!File(doc.localPath).exists())
      dir.deleteRecursively()
    } finally {
      server.stop(0)
      db.close(); file.delete()
    }
  }

  @Test
  fun upgradeFromVersion11KeepsFilesUnchosen() = runBlocking<Unit> {
    val file = File.createTempFile("upgrade11", ".db").also { it.delete() }
    fun open() = AppDatabase.configure(Room.databaseBuilder<AppDatabase>(name = file.absolutePath).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO))
    open().also { it.studyPilotDao().getSubjectsForUser("u").first(); it.close() }
    BundledSQLiteDriver().open(file.absolutePath).use { c ->
      c.execSQL("DROP TABLE course_documents")
      c.execSQL("CREATE TABLE `course_documents` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` TEXT NOT NULL, `subjectId` INTEGER NOT NULL, `subjectName` TEXT NOT NULL, `title` TEXT NOT NULL, `source` TEXT NOT NULL, `externalId` TEXT NOT NULL, `url` TEXT NOT NULL, `fileType` TEXT NOT NULL, `remoteUpdatedAt` TEXT NOT NULL, `pageCount` INTEGER NOT NULL, `text` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL)")
      c.execSQL("INSERT INTO course_documents VALUES (1,'u',0,'Nets','L1.pdf','canvas','9','','pdf','x',3,'old text',0)")
      c.execSQL("PRAGMA user_version = 11")
    }
    val db = open()
    try {
      val doc = db.studyPilotDao().getCourseDocumentsForUser("u").first().single()
      assertEquals("old text", doc.text)
      assertTrue(!doc.useForStudy && doc.localPath.isBlank())
    } finally {
      db.close(); file.delete()
    }
  }

  @Test
  fun upgradeFromVersion10AddsCourseFiles() = runBlocking<Unit> {
    val file = File.createTempFile("upgrade10", ".db").also { it.delete() }
    fun open() = AppDatabase.configure(Room.databaseBuilder<AppDatabase>(name = file.absolutePath).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO))
    open().also { db -> StudyPilotRepository(db.studyPilotDao()).addSubject("Kept", "K", "#000000", "u"); db.close() }
    BundledSQLiteDriver().open(file.absolutePath).use { c ->
      c.execSQL("DROP TABLE course_documents")
      c.execSQL("PRAGMA user_version = 10")
    }
    val db = open()
    try {
      assertEquals(listOf("Kept"), db.studyPilotDao().getSubjectsForUser("u").first().map { it.name })
      assertTrue(db.studyPilotDao().getCourseDocumentsForUser("u").first().isEmpty())
    } finally {
      db.close(); file.delete()
    }
  }
}
