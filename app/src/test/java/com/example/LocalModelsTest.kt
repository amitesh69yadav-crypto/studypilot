package com.example

import com.example.data.ai.StudyAi
import com.example.data.ai.llm.LlmRequest
import com.example.data.ai.llm.LlmTurn
import com.example.data.ai.llm.OllamaClient
import com.example.data.ai.llm.extractJson
import com.example.data.ai.llm.stripThinking
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/** Talks to a fake Ollama server on localhost, so no PC or model is needed. Robolectric supplies org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalModelsTest {

  private lateinit var server: HttpServer
  private val tagCalls = AtomicInteger()
  private val chatBodies = mutableListOf<JSONObject>()
  @Volatile private var rejectThink = false

  @Before
  fun startFakeOllama() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/api/tags") { ex ->
      tagCalls.incrementAndGet()
      val body = """{"models":[{"name":"nomic-embed-text:latest"},{"name":"qwen3:8b"},{"name":"gemma3:4b"}]}""".toByteArray()
      ex.sendResponseHeaders(200, body.size.toLong())
      ex.responseBody.use { it.write(body) }
    }
    server.createContext("/api/chat") { ex ->
      val request = JSONObject(ex.requestBody.readBytes().decodeToString())
      synchronized(chatBodies) { chatBodies.add(request) }
      if (rejectThink && request.has("think")) {
        val err = """{"error":"\"qwen3:8b\" does not support thinking"}""".toByteArray()
        ex.sendResponseHeaders(400, err.size.toLong())
        ex.responseBody.use { it.write(err) }
        return@createContext
      }
      val reply = if (request.optBoolean("stream")) {
        listOf("<think>plan</think>", "A heap ", "is a tree", "").joinToString("\n") { part ->
          JSONObject().put("message", JSONObject().put("role", "assistant").put("content", part)).put("done", part.isEmpty()).toString()
        }
      } else {
        JSONObject().put("message", JSONObject().put("content", "[{\"a\":1}]")).put("done", true).toString()
      }
      val bytes = reply.toByteArray()
      ex.sendResponseHeaders(200, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }
    server.start()
  }

  @After
  fun stop() = server.stop(0)

  private fun client() = OllamaClient().apply { baseUrl = "127.0.0.1:${server.address.port}" }

  @Test
  fun addressWithoutSchemeOrPortIsCompleted() {
    val c = OllamaClient().apply { baseUrl = "192.168.1.60" }
    assertEquals("http://192.168.1.60:11434", c.baseUrl)
    c.baseUrl = "http://10.0.0.5:8080/"
    assertEquals("http://10.0.0.5:8080", c.baseUrl)
  }

  @Test
  fun picksPreferredModelOrFirstChatModelAndSkipsEmbeddings() = runBlocking {
    val c = client()
    assertEquals("qwen3:8b", c.chosenModel())
    c.preferredModel = "gemma3"
    assertEquals("gemma3:4b", c.chosenModel())
  }

  @Test
  fun reachabilityIsCachedBetweenQuestions() = runBlocking {
    val c = client()
    repeat(5) { c.status() }
    assertEquals(1, tagCalls.get())
    c.status(forceRefresh = true)
    assertEquals(2, tagCalls.get())
  }

  @Test
  fun streamsTheReplyAndDropsThinking() = runBlocking {
    val backend = client().backendOrNull()!!
    val partials = mutableListOf<String>()
    val text = backend.generate(
      LlmRequest(system = "Be brief", turns = listOf(LlmTurn(true, "What is a heap?"))),
      onPartial = { partials.add(it) }
    )
    assertEquals("A heap is a tree", text)
    assertEquals("A heap is a tree", partials.last())
    val sent = chatBodies.last()
    assertEquals("system", sent.getJSONArray("messages").getJSONObject(0).getString("role"))
    assertEquals(false, sent.getBoolean("think"))
  }

  @Test
  fun retriesWithoutTheThinkFlagWhenTheModelRejectsIt() = runBlocking {
    rejectThink = true
    val text = client().backendOrNull()!!.generate(LlmRequest("", listOf(LlmTurn(true, "hi")), json = true))
    assertEquals("[{\"a\":1}]", text)
    assertFalse(chatBodies.last().has("think"))
    assertEquals("json", chatBodies.last().getString("format"))
  }

  @Test
  fun unreachablePcMeansNoBackend() = runBlocking {
    val c = OllamaClient().apply { baseUrl = "127.0.0.1:1" }
    assertNull(c.backendOrNull())
    assertFalse(c.status().reachable)
  }

  @Test
  fun jsonIsFoundInsideProseAndFences() {
    assertEquals("""[{"q":1}]""", extractJson("Sure! ```json\n[{\"q\":1}]\n``` Hope it helps"))
    assertEquals("""{"isCorrect":true}""", extractJson("<think>hmm</think>{\"isCorrect\":true}"))
    assertNull(extractJson("no json here"))
    assertEquals("answer", stripThinking("<think>unfinished reasoning"+ "</think>answer"))
  }

  @Test
  fun generatedQuestionsAreValidatedBeforeUse() {
    val raw = """
      {"questions": [
        {"questionType": "MULTIPLE_CHOICE", "questionText": "2+2?", "optionsJson": ["3", "4", "5", "6"], "correctAnswer": "4", "explanation": "sum"},
        {"questionType": "MULTIPLE_CHOICE", "questionText": "Capital of France?", "optionsJson": "Rome,Madrid", "correctAnswer": "Paris"},
        {"questionType": "SHORT_ANSWER", "questionText": "", "correctAnswer": "x"}
      ]}
    """.trimIndent()
    val parsed = StudyAi(null).parseQuestions(raw, "EASY") { it }
    assertEquals(2, parsed.size)
    assertEquals("3,4,5,6", parsed[0].optionsJson)
    // An answer that isn't one of the options can't be graded as multiple choice.
    assertEquals("SHORT_ANSWER", parsed[1].questionType)
    assertTrue(parsed[1].optionsJson.isEmpty())
  }
}
