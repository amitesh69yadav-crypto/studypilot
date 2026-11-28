package com.example.sync

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.example.data.ai.AiJob
import com.example.data.ai.AiSource
import com.example.data.ai.StudyAi
import com.example.data.ai.llm.CloudProvider
import com.example.data.local.AppDatabase
import com.example.data.repository.StudyPilotRepository
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Several API keys: each job uses the key best at it, and PDFs go whole to a key that reads them. */
class KeyRoutingTest {

  private fun aiWith(selected: CloudProvider, vararg others: CloudProvider) = StudyAi(null).also { ai ->
    ai.cloud.provider = selected
    ai.cloud.apiKey = "k-$selected"
    ai.setOtherKeys(others.map { StudyAi.CloudKey(it, "k-$it") })
  }

  @Test
  fun eachJobUsesTheKeyBestAtIt() {
    val ai = aiWith(CloudProvider.MISTRAL, CloudProvider.GEMINI, CloudProvider.GROQ, CloudProvider.ANTHROPIC)
    fun order(job: AiJob) = ai.cloudsFor(job).map { it.provider }
    // Quizzes: the fastest first. Reading: the ones that read PDFs. Chat: the key picked in Settings.
    assertEquals(listOf(CloudProvider.GROQ, CloudProvider.GEMINI, CloudProvider.MISTRAL, CloudProvider.ANTHROPIC), order(AiJob.QUIZ))
    assertEquals(listOf(CloudProvider.GEMINI, CloudProvider.ANTHROPIC, CloudProvider.GROQ, CloudProvider.MISTRAL), order(AiJob.READ))
    assertEquals(CloudProvider.MISTRAL, order(AiJob.CHAT).first())
    assertEquals(
      mapOf(CloudProvider.MISTRAL to listOf(AiJob.CHAT), CloudProvider.GROQ to listOf(AiJob.QUIZ), CloudProvider.GEMINI to listOf(AiJob.READ)),
      ai.jobsByKey()
    )
    // A key the student picks for a job goes first; the others stay as backups.
    ai.jobProvider = mapOf(AiJob.QUIZ to CloudProvider.ANTHROPIC)
    assertEquals(listOf(CloudProvider.ANTHROPIC, CloudProvider.GROQ, CloudProvider.GEMINI, CloudProvider.MISTRAL), order(AiJob.QUIZ))
    // Removing a key drops it.
    ai.setOtherKeys(listOf(StudyAi.CloudKey(CloudProvider.GROQ, "k")))
    assertEquals(listOf(CloudProvider.GROQ, CloudProvider.MISTRAL), order(AiJob.QUIZ))
    assertTrue(!ai.canReadPdf(1000), "no key reads PDFs any more")
  }

  @Test
  fun aPdfIsReadWholeByGeminiIntoTopics() = runBlocking<Unit> {
    val seen = mutableListOf<JSONObject>()
    val topics = JSONObject().put("topics", JSONArray()
      .put(JSONObject().put("title", "Binary Search Trees").put("summary", "A binary search tree keeps smaller keys left and larger keys right, so search takes O(h) steps.")
        .put("keyPoints", JSONArray().put("In-order traversal visits keys in sorted order")).put("pages", JSONArray().put(2).put(3)))
      .put(JSONObject().put("title", "Course schedule").put("summary", "The exam is in January and the project deadline is 20 December.").put("pages", JSONArray().put(1))))
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/v1beta/models/") { ex ->
        synchronized(seen) { seen += JSONObject(ex.requestBody.readBytes().decodeToString()).put("_path", ex.requestURI.path).put("_key", ex.requestHeaders.getFirst("x-goog-api-key")) }
        val reply = JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("parts", JSONArray()
          .put(JSONObject().put("text", "thinking…").put("thought", true))
          .put(JSONObject().put("text", "```json\n$topics\n```")))))).toString().toByteArray()
        ex.sendResponseHeaders(200, reply.size.toLong()); ex.responseBody.use { it.write(reply) }
      }
      start()
    }
    val file = File.createTempFile("routing", ".db").also { it.delete() }
    val db = AppDatabase.configure(Room.databaseBuilder<AppDatabase>(name = file.absolutePath).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO))
    val dir = kotlin.io.path.createTempDirectory("uploads").toFile()
    try {
      // Groq picked in Settings (it can't read PDFs); a Gemini key is saved too.
      val ai = aiWith(CloudProvider.GROQ, CloudProvider.GEMINI).also { it.source = AiSource.CLOUD }
      ai.keyFor(CloudProvider.GEMINI)!!.apply { geminiBaseUrlForTests = "http://127.0.0.1:${server.address.port}/v1beta"; model = "gemini-2.5-flash" }
      val repo = StudyPilotRepository(db.studyPilotDao(), ai = ai)
      // A scanned PDF: no text to extract, which only a model that reads the file can handle.
      val pdf = "%PDF-1.4 scanned pages".toByteArray()
      val id = repo.addUploadedDocument("u", "Exercises.pdf", pdf, "Algorithms", dir)
      assertTrue(repo.scanDocument("u", id))
      val doc = repo.document(id)!!
      assertEquals("ready", doc.quizStatus)
      assertEquals(listOf("Binary Search Trees"), repo.topicsOfDocument("u", doc.externalId).map { it.title }, "course info is left out")

      val request = seen.single()
      assertEquals("/v1beta/models/gemini-2.5-flash:generateContent", request.getString("_path"))
      assertEquals("k-GEMINI", request.getString("_key"))
      val parts = request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
      val inline = parts.getJSONObject(0).getJSONObject("inline_data")
      assertEquals("application/pdf", inline.getString("mime_type"))
      assertEquals(pdf.toList(), java.util.Base64.getDecoder().decode(inline.getString("data")).toList())
    } finally {
      server.stop(0); db.close(); file.delete(); dir.deleteRecursively()
    }
  }

  @Test
  fun claudeReadsAPdfAsADocument() = runBlocking<Unit> {
    var body: JSONObject? = null
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/v1/messages") { ex ->
        body = JSONObject(ex.requestBody.readBytes().decodeToString())
        val reply = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "{\"topics\": []}"))).toString().toByteArray()
        ex.sendResponseHeaders(200, reply.size.toLong()); ex.responseBody.use { it.write(reply) }
      }
      start()
    }
    try {
      val ai = aiWith(CloudProvider.ANTHROPIC)
      ai.cloud.anthropicBaseUrlForTests = "http://127.0.0.1:${server.address.port}/v1"
      ai.cloud.model = "claude-haiku-5-5"
      assertEquals(emptyList(), ai.topicsInPdf("Notes.pdf", "Networks", "%PDF-1.4".toByteArray()))
      val content = body!!.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
      assertEquals("document", content.getJSONObject(0).getString("type"))
      assertEquals("application/pdf", content.getJSONObject(0).getJSONObject("source").getString("media_type"))
    } finally {
      server.stop(0)
    }
  }
}
