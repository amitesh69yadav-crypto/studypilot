package com.example

import com.example.platform.AndroidPlatform
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.AiSource
import com.example.data.model.ChatMessage
import com.example.ui.viewmodel.StudyPilotViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Follow-up buttons on an older answer must be answered about that answer's question. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TutorFollowUpTest {

  private lateinit var vm: StudyPilotViewModel

  @Before
  fun setUp() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    app.getSharedPreferences("studypilot_auth_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    vm = StudyPilotViewModel(AndroidPlatform.get(app))
    vm.setAiSource(AiSource.BUILT_IN)
    vm.startNewTutorChat(null)
  }

  private fun send(text: String, replyingTo: String? = null) {
    vm.sendChatMessage(text, replyingTo)
    val deadline = System.currentTimeMillis() + 15_000
    do {
      shadowOf(Looper.getMainLooper()).idle()
      Thread.sleep(20)
    } while (vm.isChatLoading.value && System.currentTimeMillis() < deadline)
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse("tutor did not answer in time", vm.isChatLoading.value)
  }

  private fun answers() = vm.chatMessages.value.filter { it.role == "model" }

  @Test
  fun followUpOnAnOlderAnswerIsAboutThatAnswersQuestion() {
    send("explain dijkstra algorithm")
    val dijkstraAnswer = answers().last()
    send("explain binary search")
    assertTrue(answers().last().text.contains("binary search", ignoreCase = true))

    send("Summarize \"explain dijkstra algorithm\". Answer only with a concise summary and key takeaways.", replyingTo = dijkstraAnswer.id)

    val reply = answers().last().text
    assertTrue("expected a Dijkstra summary, got: ${reply.take(200)}", reply.contains("Dijkstra", ignoreCase = true))
    assertFalse("summary drifted to the latest question: ${reply.take(200)}", reply.contains("binary search", ignoreCase = true))
  }

  @Test
  fun followUpContextStopsAtTheChosenAnswer() {
    val q1 = ChatMessage(role = "user", text = "what is a heap?")
    val a1 = ChatMessage(role = "model", text = "A heap is ...")
    val q2 = ChatMessage(role = "user", text = "what is a trie?")
    val a2 = ChatMessage(role = "model", text = "A trie is ...")
    val history = listOf(q1, a1, q2, a2)

    assertEquals(listOf(q1, a1), vm.followUpContext(history, a1.id))
    assertEquals(history, vm.followUpContext(history, null))
    assertEquals(history, vm.followUpContext(history, "no-such-message"))
  }

  @Test
  fun theConversationStillShowsEveryMessageInOrder() {
    send("explain dijkstra algorithm")
    val first = answers().last()
    send("explain binary search")
    send("Give another example of Dijkstra. Answer only with the alternative example and its walkthrough.", replyingTo = first.id)

    val roles = vm.chatMessages.value.drop(1).map { it.role } // drop the greeting
    assertEquals(listOf("user", "model", "user", "model", "user", "model"), roles)
  }
}
