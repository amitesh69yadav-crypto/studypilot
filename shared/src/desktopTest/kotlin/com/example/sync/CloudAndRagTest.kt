package com.example.sync

import com.example.data.ai.AiTask
import com.example.data.ai.StudyAi
import com.example.data.ai.llm.CloudProvider
import com.example.data.ai.llm.LlmRequest
import com.example.data.ai.llm.LlmTurn
import com.example.data.ai.rag.KnowledgeIndex
import com.example.data.ai.rag.KnowledgePassage
import com.example.data.model.ChatMessage
import com.example.data.model.ExtractedConcept
import com.example.data.model.ImportedChat
import com.example.data.model.StudyTopic
import com.example.data.repository.newChatMaterial
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudAndRagTest {

  // --- A stand-in for Groq's OpenAI-compatible API ---

  private val requests = mutableListOf<JSONObject>()
  @Volatile private var reply: String = "Hello"
  @Volatile private var status = 200
  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/v1/chat/completions") { ex ->
      val body = JSONObject(ex.requestBody.readBytes().decodeToString())
      synchronized(requests) { requests += body }
      val auth = ex.requestHeaders.getFirst("Authorization")
      val (code, text) = when {
        auth != "Bearer test-key" -> 401 to """{"error":{"message":"Invalid API Key"}}"""
        status == 429 -> 429 to """{"error":{"message":"Rate limit reached"}}"""
        body.optBoolean("stream") -> 200 to reply.chunked(3).joinToString("") { piece ->
          "data: " + JSONObject().put("choices", listOf(JSONObject().put("delta", JSONObject().put("content", piece)))) + "\n\n"
        } + "data: [DONE]\n\n"
        else -> 200 to JSONObject().put("choices", listOf(JSONObject().put("message", JSONObject().put("content", reply)))).toString()
      }
      if (code == 429) ex.responseHeaders.add("retry-after", "30")
      val bytes = text.encodeToByteArray()
      ex.sendResponseHeaders(code, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }
    createContext("/anthropic/v1/messages") { ex ->
      val body = JSONObject(ex.requestBody.readBytes().decodeToString())
      synchronized(requests) { requests += body.put("_key", ex.requestHeaders.getFirst("x-api-key")).put("_version", ex.requestHeaders.getFirst("anthropic-version").orEmpty()) }
      val text = if (body.optBoolean("stream")) {
        reply.chunked(4).joinToString("") { piece ->
          "event: content_block_delta\ndata: " + JSONObject().put("type", "content_block_delta").put("delta", JSONObject().put("type", "text_delta").put("text", piece)) + "\n\n"
        } + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
      } else {
        JSONObject().put("content", listOf(JSONObject().put("type", "text").put("text", reply))).toString()
      }
      val bytes = text.encodeToByteArray()
      ex.sendResponseHeaders(200, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }
    createContext("/v1/models") { ex ->
      val bytes = """{"data":[{"id":"llama-3.3-70b-versatile"},{"id":"whisper-large-v3"},{"id":"openai/gpt-oss-120b"}]}""".encodeToByteArray()
      ex.sendResponseHeaders(200, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }
    start()
  }
  private val baseUrl = "http://127.0.0.1:${server.address.port}/v1"

  @AfterTest
  fun stop() = server.stop(0)

  private fun ai() = StudyAi(null).also {
    it.cloud.provider = CloudProvider.CUSTOM
    it.cloud.customBaseUrl = baseUrl
    it.cloud.apiKey = "test-key"
    it.cloud.model = "llama-3.3-70b-versatile"
  }

  @Test
  fun cloudAnswersAndStreams() = runBlocking<Unit> {
    val ai = ai()
    assertEquals("Hello", ai.prompt("Hi"))
    val backend = assertNotNull(ai.backend())
    assertTrue(backend.label.startsWith("Cloud"))

    reply = "A heap is a complete binary tree."
    val partials = mutableListOf<String>()
    val text = backend.generate(LlmRequest("sys", listOf(LlmTurn(true, "What is a heap?"))), onPartial = { partials += it })
    assertEquals(reply, text)
    assertTrue(partials.size > 2 && partials.last() == reply, "streamed in pieces")
    val last = synchronized(requests) { requests.last() }
    assertEquals("llama-3.3-70b-versatile", last.getString("model"))
    assertEquals("system", last.getJSONArray("messages").getJSONObject(0).getString("role"))
  }

  @Test
  fun keyCheckListsChatModels() = runBlocking<Unit> {
    val status = ai().cloud.status()
    assertTrue(status.ok)
    assertEquals(listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b"), status.models, "speech models are left out")

    val bad = StudyAi(null).also { it.cloud.provider = CloudProvider.CUSTOM; it.cloud.customBaseUrl = baseUrl; it.cloud.apiKey = "wrong" }
    assertNull(bad.prompt("Hi"), "a rejected key falls through to no answer, not a crash")
  }

  @Test
  fun aRateLimitPausesTheCloudAndOtherModelsTakeOver() = runBlocking<Unit> {
    val ai = ai()
    status = 429
    assertNull(ai.prompt("Hi"), "no other model here, so nothing answers")
    assertNull(ai.cloud.backendOrNull(), "paused after the 429")
    assertTrue(ai.backends().isEmpty())
  }

  @Test
  fun backgroundWorkLeavesTheBudgetForAnswers() = runBlocking<Unit> {
    val ai = ai()
    ai.cloud.dailyBudget = 3
    repeat(3) { ai.prompt("Hi", task = AiTask.BACKGROUND) }
    assertNull(ai.backend(AiTask.BACKGROUND), "70% of the day's budget is spent")
    assertNotNull(ai.backend(AiTask.ANSWER), "the student's questions still get the cloud")
  }

  @Test
  fun tutorAnswerCitesTheStudentsMaterial() = runBlocking<Unit> {
    val ai = ai()
    reply = "Round robin gives each process a fixed time slice [1], unlike first-come scheduling."
    val passages = listOf(
      KnowledgePassage("Round robin gives every process a quantum, then moves it to the back of the queue.", "Imported chat: OS scheduling", "https://chatgpt.com/share/abc"),
      KnowledgePassage("A heap is a complete binary tree.", "Tutor chat: Heaps")
    )
    val response = ai.chat(listOf(ChatMessage(role = "user", text = "How does round robin work?")), knowledge = passages)
    assertEquals(listOf("Imported chat: OS scheduling"), response.groundingSources.map { it.title }, "only the cited passage")
    assertEquals("https://chatgpt.com/share/abc", response.groundingSources.single().url)
    val messages = synchronized(requests) { requests.last() }.getJSONArray("messages")
    val question = messages.getJSONObject(messages.length() - 1).getString("content")
    assertTrue(question.startsWith("How does round robin work?"), "the student's question comes first")
    assertTrue("[1] (Imported chat: OS scheduling)" in question && "quantum" in question, "the passages went to the model with it")
  }

  @Test
  fun anUncitedAnswerStillShowsTheMaterialItUsed() {
    val passages = listOf(
      KnowledgePassage("When a vector runs out of capacity it allocates a larger block, usually double, copies the elements and frees the old block.", "Tutor chat: vectors"),
      KnowledgePassage("Dijkstra finds shortest paths with non-negative weights.", "Imported chat: Graphs")
    )
    val answer = "A vector that runs out of capacity allocates a larger block of memory, usually double the size, copies the existing elements across and frees the old block."
    assertEquals(listOf("Tutor chat: vectors"), KnowledgePassage.citedSources(answer, passages).map { it.title })
    assertTrue(KnowledgePassage.citedSources("Photosynthesis turns light into chemical energy.", passages).isEmpty())
  }

  // --- Search over the student's material ---

  @Test
  fun searchFindsTheRightChat() {
    val chats = listOf(
      ImportedChat(id = 1, title = "OS scheduling", sourceUrl = "https://chatgpt.com/share/os", rawConversation =
        "User: how does round robin scheduling work?\n\nAssistant: Round robin gives each process a fixed time quantum. When the quantum expires the process goes to the back of the ready queue. A small quantum means more context switches."),
      ImportedChat(id = 2, title = "Graphs", sourceUrl = "https://chatgpt.com/share/g", rawConversation =
        "User: explain dijkstra\n\nAssistant: Dijkstra's algorithm finds shortest paths from a source when edge weights are non-negative, using a priority queue.")
    )
    val topics = listOf(StudyTopic(userId = "u", subjectId = 0, subjectName = "", title = "Recursion", dateStudied = "", descriptionNotes = "A base case stops the recursive calls.", sourceType = "MANUAL"))
    val concepts = listOf(ExtractedConcept(userId = "u", sourceChatId = 2, sourceChatTitle = "Graphs", topic = "Graphs", conceptName = "Relaxation", definition = "Updating a node's distance when a shorter path is found."))
    val index = KnowledgeIndex.build(chats, emptyList(), topics, concepts)

    val hits = index.search("what happens when the time quantum expires in round robin?")
    assertEquals("Imported chat: OS scheduling", hits.first().sourceTitle)
    assertEquals("https://chatgpt.com/share/os", hits.first().url)
    assertEquals("Notes: Recursion", index.search("what stops recursive calls").first().sourceTitle)
    assertTrue(index.search("dijkstra shortest paths").first().sourceTitle.contains("Graphs"))
    assertTrue(index.search("tell me about it please").isEmpty(), "filler words alone match nothing")
  }

  @Test
  fun longChatsAreSplitIntoOverlappingPassages() {
    val text = (1..40).joinToString(" ") { "Sentence number $it talks about topic $it in some detail." }
    val chunks = KnowledgeIndex.chunk(text)
    assertTrue(chunks.size >= 2)
    assertTrue(chunks.all { it.length <= 1800 })
    val firstEnd = chunks[0].split(". ").last()
    assertTrue(chunks[1].contains(firstEnd.take(20)), "the next passage repeats the last sentence")
  }

  // --- Imported links: what's new ---

  @Test
  fun onlyNewMessagesAreTakenFromARefreshedLink() {
    val old = "User: what is a stack?\n\nAssistant: A stack is last in, first out."
    assertEquals("", newChatMaterial(old, old))
    assertEquals("", newChatMaterial(old, "  $old \n"), "whitespace isn't news")
    val grown = "$old\n\nUser: and a queue?\n\nAssistant: A queue is first in, first out."
    assertEquals("User: and a queue?\n\nAssistant: A queue is first in, first out.", newChatMaterial(old, grown))
    // The page came back formatted a little differently at the start.
    val reformatted = "User:  what is a stack?\n\nAssistant: A stack is last in,  first out.\n\nUser: and a deque?"
    assertEquals("User: and a deque?", newChatMaterial(old, reformatted))
  }

  @Test
  fun claudeKeysUseAnthropicsApi() = runBlocking<Unit> {
    // Point the Anthropic adapter at the fake by checking the request it would send.
    val ai = StudyAi(null)
    ai.cloud.provider = CloudProvider.ANTHROPIC
    ai.cloud.apiKey = "claude-key"
    assertEquals("https://api.anthropic.com/v1", ai.cloud.baseUrl)
    assertEquals("claude-haiku-5-5", ai.cloud.chosenModel())
    assertNull(StudyAi(null).also { it.cloud.provider = CloudProvider.CUSTOM; it.cloud.apiKey = "k" }.cloud.backendOrNull(), "Other needs an address")
  }

  @Test
  fun anthropicRepliesAreRead() = runBlocking<Unit> {
    reply = "Paris is the capital of France."
    val client = com.example.data.ai.llm.CloudClient()
    client.provider = CloudProvider.ANTHROPIC
    client.apiKey = "claude-key"
    client.anthropicBaseUrlForTests = "http://127.0.0.1:${server.address.port}/anthropic/v1"
    val backend = assertNotNull(client.backendOrNull())
    assertEquals(reply, backend.generate(LlmRequest("Be brief.", listOf(LlmTurn(true, "Capital of France?")))))
    val partials = mutableListOf<String>()
    assertEquals(reply, backend.generate(LlmRequest("Be brief.", listOf(LlmTurn(true, "Capital of France?"))), onPartial = { partials += it }))
    assertTrue(partials.size > 2)
    val sent = synchronized(requests) { requests.last() }
    assertEquals("claude-key", sent.getString("_key"))
    assertEquals("2023-06-01", sent.getString("_version"))
    assertEquals("Be brief.", sent.getString("system"), "the system prompt is a separate field")
    assertEquals("user", sent.getJSONArray("messages").getJSONObject(0).getString("role"))
  }
}
