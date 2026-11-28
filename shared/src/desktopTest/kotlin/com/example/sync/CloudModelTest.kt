package com.example.sync

import com.example.data.ai.StudyAi
import com.example.data.ai.llm.CloudProvider
import com.example.data.ai.llm.LlmRequest
import com.example.data.ai.llm.LlmTurn
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/** Providers retire models: the app finds one the key can use instead of failing every request. */
class CloudModelTest {
  @Test
  fun aRetiredModelIsReplacedByOneTheProviderOffers() = runBlocking<Unit> {
    val asked = mutableListOf<String>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/v1/models") { ex ->
        val body = JSONObject().put("data", JSONArray().put(JSONObject().put("id", "good-model"))).toString().toByteArray()
        ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
      }
      createContext("/v1/chat/completions") { ex ->
        val model = JSONObject(ex.requestBody.readBytes().decodeToString()).getString("model")
        synchronized(asked) { asked += model }
        val (code, text) = if (model == "good-model") {
          200 to JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "Hello from good-model")))).toString()
        } else {
          404 to """{"error":{"message":"The model `$model` does not exist or you do not have access to it.","code":"model_not_found"}}"""
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
        it.cloud.model = "llama-3.3-70b-versatile" // retired
      }
      val reply = ai.cloud.backendOrNull()!!.generate(LlmRequest("sys", listOf(LlmTurn(true, "hi"))), null)
      assertEquals("Hello from good-model", reply)
      // Next time it goes straight to the working model.
      asked.clear()
      ai.cloud.backendOrNull()!!.generate(LlmRequest("sys", listOf(LlmTurn(true, "again"))), null)
      assertEquals(listOf("good-model"), asked)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun aStrictJsonRejectionIsRetriedAsPlainText() = runBlocking<Unit> {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/v1/models") { ex ->
        val body = JSONObject().put("data", JSONArray().put(JSONObject().put("id", "m"))).toString().toByteArray()
        ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
      }
      createContext("/v1/chat/completions") { ex ->
        val req = JSONObject(ex.requestBody.readBytes().decodeToString())
        val strict = req.has("response_format")
        val topics = JSONObject().put("topics", JSONArray().put(JSONObject().put("title", "Circuit switching").put("summary", "Resources are reserved end to end for the whole call.").put("keyPoints", JSONArray().put("Dedicated path")).put("pages", JSONArray().put(2)))).toString()
        val (code, text) = if (strict) 400 to """{"error":{"message":"Failed to validate JSON. Please adjust your prompt.","code":"json_validate_failed"}}"""
        else 200 to JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "Here you go:\n" + topics)))).toString()
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
        it.cloud.model = "m"
      }
      val found = ai.topicsInFile("Lecture 1.pdf", "Networks", "[Page 2]\nCircuit switching reserves resources.")!!
      assertEquals(listOf("Circuit switching"), found.map { it.title })
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun quizWorkWaitsOutARateLimitInsteadOfGivingUp() = runBlocking<Unit> {
    var calls = 0
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/v1/models") { ex ->
        val body = JSONObject().put("data", JSONArray().put(JSONObject().put("id", "m"))).toString().toByteArray()
        ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
      }
      createContext("/v1/chat/completions") { ex ->
        ex.requestBody.readBytes()
        calls++
        val (code, text) = if (calls == 1) 429 to """{"error":{"message":"Rate limit reached"}}"""
        else 200 to JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "answer")))).toString()
        if (code == 429) ex.responseHeaders.add("retry-after", "1")
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
        it.cloud.model = "m"
      }
      assertEquals("answer", ai.prompt("write questions", preferCloud = true))
      assertEquals(2, calls)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun aCutOffReplyStillGivesItsCompleteTopics() {
    val cut = """{"topics": [{"title": "Circuit switching", "summary": "Reserves a path {end to end}.", "pages": [2]}, {"title": "Packet switching", "summary": "Store and forw"""
    val objs = StudyAi(null).completeObjects(cut)
    assertEquals(listOf("Circuit switching"), objs.map { it.getString("title") })
  }
}
