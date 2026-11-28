package com.example.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.example.data.ai.rag.KnowledgePassage
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
