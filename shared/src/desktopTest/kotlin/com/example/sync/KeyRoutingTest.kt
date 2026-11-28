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
  fun aRetiredGeminiModelIsSwappedForTheNewestFlashTheKeyLists() = runBlocking<Unit> {
    val asked = mutableListOf<String>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/v1beta/openai/models") { ex ->
        val ids = listOf("gemini-2.5-flash", "gemini-2.5-flash-image", "gemini-3-flash-preview", "gemini-3.8-flash", "gemini-3.1-pro-preview")
        val body = JSONObject().put("data", JSONArray().apply { ids.forEach { put(JSONObject().put("id", "models/$it")) } }).toString().toByteArray()
        ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
      }
      createContext("/v1beta/models/") { ex ->
        val model = ex.requestURI.path.substringAfterLast('/').substringBefore(':')
        synchronized(asked) { asked += model }
        val (code, text) = if (model == "gemini-3.8-flash") {
          404 to """{"error":{"code":404,"message":"This model models/gemini-3.8-flash is no longer available to new users.","status":"NOT_FOUND"}}"""
        } else {
          200 to JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "{\"topics\": []}")))))).toString()
        }
        val bytes = text.toByteArray()
        ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
      }
      start()
    }
    try {
      val ai = aiWith(CloudProvider.GEMINI)
      ai.cloud.geminiBaseUrlForTests = "http://127.0.0.1:${server.address.port}/v1beta"
      assertEquals(emptyList(), ai.topicsInPdf("Notes.pdf", "Algorithms", "%PDF-1.4".toByteArray()))
      // The newest stable Flash first; when that's refused, the newest preview.
      assertEquals(listOf("gemini-3.8-flash", "gemini-3-flash-preview"), asked)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun aLongPdfIsReadInPartsAtTheSameTimeAndUnreadPagesAreReported() = runBlocking<Unit> {
    val prompts = java.util.Collections.synchronizedList(mutableListOf<String>())
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      executor = java.util.concurrent.Executors.newFixedThreadPool(4)
      createContext("/v1beta/models/") { ex ->
        val body = JSONObject(ex.requestBody.readBytes().decodeToString())
        val prompt = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(1).getString("text")
        prompts += prompt
        val range = Regex("Read pages (\\d+)-(\\d+)").find(prompt)!!.groupValues
        val (code, text) = if (range[1] == "31") {
          503 to """{"error":{"message":"This model is currently experiencing high demand."}}"""
        } else {
          val topic = JSONObject().put("title", "Topic of pages ${range[1]}").put("summary", "A long enough summary of what these pages teach about sorting.").put("pages", JSONArray().put(range[1].toInt()))
          val answer = JSONObject().put("topics", JSONArray().put(topic)).toString()
          val parts = JSONArray().put(JSONObject().put("text", answer))
          200 to JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("parts", parts)))).toString()
        }
        val bytes = text.toByteArray()
        ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
      }
      start()
    }
    try {
      val ai = aiWith(CloudProvider.GEMINI)
      ai.cloud.geminiBaseUrlForTests = "http://127.0.0.1:${server.address.port}/v1beta"
      ai.cloud.model = "gemini-3-flash"
      val missed = mutableSetOf<Int>()
      val topics = ai.topicsInPdf("Book.pdf", "Algorithms", "%PDF-1.4".toByteArray(), pageCount = 40, missed = missed)!!
      assertEquals(listOf("Topic of pages 1", "Topic of pages 16"), topics.map { it.title })
      assertEquals((31..40).toSet(), missed, "the part that failed is left for the text reader")
      assertTrue(prompts.any { "Read pages 1-15" in it } && prompts.any { "Read pages 16-30" in it } && prompts.any { "Read pages 31-40" in it })
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun topicsOnTheSamePagesAreJoinedButSectionsStayApart() {
    fun t(title: String, pages: IntRange, summary: String = "Notes on $title.") = StudyAi.FileTopic(title, summary, listOf("$title fact"), pages.toList())
    val merged = StudyAi(null).mergeOverlapping(listOf(
      t("Circular Vectors", 41..45), t("Circular Vectors and Lists", 41..45, "Longer notes on circular vectors and circular lists."),
      t("Linked Lists", 19..29), t("Double Linked Lists", 25..29), t("Graphs", 63..72)
    ))
    assertEquals(listOf("Circular Vectors and Lists", "Linked Lists", "Double Linked Lists", "Graphs"), merged.map { it.title })
    assertEquals(listOf("Circular Vectors fact", "Circular Vectors and Lists fact"), merged.first().keyPoints)
  }

  @Test
  fun aModelsDailyLimitMovesToTheKeysNextModel() = runBlocking<Unit> {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/v1/models") { ex ->
        val body = JSONObject().put("data", JSONArray().put(JSONObject().put("id", "big")).put(JSONObject().put("id", "small"))).toString().toByteArray()
        ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
      }
      createContext("/v1/chat/completions") { ex ->
        val model = JSONObject(ex.requestBody.readBytes().decodeToString()).getString("model")
        val (code, text) = if (model == "big") {
          429 to """{"error":{"message":"Rate limit reached for model `big` on tokens per day (TPD): Limit 200000, Used 199990."}}"""
        } else {
          200 to JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "from small")))).toString()
        }
        val bytes = text.toByteArray()
        ex.sendResponseHeaders(code, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) }
      }
      start()
    }
    try {
      val ai = StudyAi(null).also {
        it.cloud.provider = CloudProvider.CUSTOM
        it.cloud.customBaseUrl = "http://127.0.0.1:${server.address.port}/v1"
        it.cloud.apiKey = "k"
        it.cloud.model = "big"
      }
      assertEquals("from small", ai.prompt("hi", preferCloud = true))
      assertEquals(0L, ai.cloud.pausedForMs(), "the key isn't paused for one model's daily limit")
    } finally {
      server.stop(0)
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
