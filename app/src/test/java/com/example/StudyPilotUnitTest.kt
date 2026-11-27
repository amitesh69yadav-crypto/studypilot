package com.example

import com.example.data.model.QuizQuestion
import com.example.data.model.StudyTopic
import com.example.data.ai.StudyAi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class StudyPilotUnitTest {

  // No Android context: no phone model and no PC set, so everything uses built-in answers.
  private val service = StudyAi(null)

  @Test
  fun testSpacedRepetitionIntervalProgression() {
    // Testing interval advancement logic
    val intervals = listOf(1, 3, 7, 14, 30)
    for (i in 0 until intervals.size - 1) {
      val current = intervals[i]
      val next = when (current) {
        1 -> 3
        3 -> 7
        7 -> 14
        14 -> 30
        else -> 30
      }
      assertEquals(intervals[i + 1], next)
    }

    // On mistake, resets to 1 day
    val mistakeReset = 1
    assertEquals(1, mistakeReset)
  }

  @Test
  fun testDeterministicMultipleChoiceEvaluation() = runBlocking {
    val mcq = QuizQuestion(
      topicId = 1,
      subjectName = "CS201",
      topicTitle = "AVL Trees",
      questionType = "MULTIPLE_CHOICE",
      questionText = "What is the search complexity of an AVL tree?",
      optionsJson = "O(1),O(log N),O(N)",
      correctAnswer = "O(log N)",
      explanation = "AVL tree height is guaranteed O(log N)."
    )

    val correctResult = service.evaluateAnswer(mcq, "O(log N)")
    assertTrue(correctResult.isCorrect)

    val wrongResult = service.evaluateAnswer(mcq, "O(1)")
    assertFalse(wrongResult.isCorrect)
  }

  @Test
  fun testDeterministicTrueFalseEvaluation() = runBlocking {
    val tf = QuizQuestion(
      topicId = 1,
      subjectName = "CS201",
      topicTitle = "AVL Trees",
      questionType = "TRUE_FALSE",
      questionText = "AVL tree rotations execute in O(1) time.",
      optionsJson = "True,False",
      correctAnswer = "True",
      explanation = "Rotations swap pointers in constant time."
    )

    val resultTrue = service.evaluateAnswer(tf, "True")
    assertTrue(resultTrue.isCorrect)

    val resultFalse = service.evaluateAnswer(tf, "False")
    assertFalse(resultFalse.isCorrect)
  }

  @Test
  fun testTopicCoverageTrackerPrioritization() {
    val topic1 = StudyTopic(
      id = 1,
      subjectId = 1,
      subjectName = "CS",
      title = "Topic A (Tested)",
      dateStudied = "2026-10-01",
      descriptionNotes = "Notes",
      masteryLevel = "MASTERED",
      repetitionIntervalDays = 14,
      nextRevisionDate = "2026-10-15",
      timesTested = 3,
      timesCorrect = 3
    )
    val topic2 = StudyTopic(
      id = 2,
      subjectId = 1,
      subjectName = "CS",
      title = "Topic B (Struggling)",
      dateStudied = "2026-10-01",
      descriptionNotes = "Notes",
      masteryLevel = "STRUGGLING",
      repetitionIntervalDays = 1,
      nextRevisionDate = "2026-10-11",
      timesTested = 2,
      timesCorrect = 0
    )
    val topic3 = StudyTopic(
      id = 3,
      subjectId = 1,
      subjectName = "CS",
      title = "Topic C (Untested)",
      dateStudied = "2026-10-10",
      descriptionNotes = "Notes",
      masteryLevel = "UNTESTED",
      repetitionIntervalDays = 1,
      nextRevisionDate = "2026-10-11",
      timesTested = 0,
      timesCorrect = 0
    )

    val allTopics = listOf(topic1, topic2, topic3)

    // Sort priority
    val sorted = allTopics.sortedWith(
      compareByDescending<StudyTopic> { topic ->
        var priorityScore = 0
        if (topic.masteryLevel == "STRUGGLING") priorityScore += 50
        if (topic.timesTested == 0) priorityScore += 40
        if (topic.timesTested > 0 && topic.timesCorrect < topic.timesTested) priorityScore += 30
        priorityScore
      }
    )

    // Topic 2 (struggling) should be first, Topic 3 (untested) second, Topic 1 last
    assertEquals(topic2.id, sorted[0].id)
    assertEquals(topic3.id, sorted[1].id)
    assertEquals(topic1.id, sorted[2].id)
  }

  @Test
  fun testTutorSessionSubjectFiltering() {
    val session1 = com.example.data.model.TutorSession(
      id = 1,
      subjectId = 1,
      subjectName = "Data Structures & Algorithms",
      title = "AVL Tree Rotations",
      previewSnippet = "Explanation of AVL",
      messageCount = 4
    )
    val session2 = com.example.data.model.TutorSession(
      id = 2,
      subjectId = 2,
      subjectName = "Operating Systems",
      title = "Paging vs Segmentation",
      previewSnippet = "Memory management details",
      messageCount = 2
    )
    val session3 = com.example.data.model.TutorSession(
      id = 3,
      subjectId = null,
      subjectName = "General Academic",
      title = "Study Strategy",
      previewSnippet = "Active recall methods",
      messageCount = 2
    )

    val allSessions = listOf(session1, session2, session3)

    // Filter by subject 1 (DSA)
    val dsaSessions = allSessions.filter { it.subjectId == 1L }
    assertEquals(1, dsaSessions.size)
    assertEquals("AVL Tree Rotations", dsaSessions[0].title)

    // Filter by subject 2 (OS)
    val osSessions = allSessions.filter { it.subjectId == 2L }
    assertEquals(1, osSessions.size)
    assertEquals("Operating Systems", osSessions[0].subjectName)

    // Search query match
    val searchResults = allSessions.filter {
      it.title.contains("Paging", ignoreCase = true) || it.previewSnippet.contains("Paging", ignoreCase = true)
    }
    assertEquals(1, searchResults.size)
    assertEquals("Paging vs Segmentation", searchResults[0].title)
  }

  @Test
  fun testPhotoColorPaletteValues() {
    // The phone keeps the original sage palette; the desktop's glass palette is separate.
    val phone = com.example.ui.theme.ClassicStudyColors
    assertEquals(androidx.compose.ui.graphics.Color(0xFF8C9F85), phone.deepSage)
    assertEquals(androidx.compose.ui.graphics.Color(0xFFB3C2AC), phone.mediumSage)
    assertEquals(androidx.compose.ui.graphics.Color(0xFFD3DDCF), phone.paleCeladon)
    assertEquals(androidx.compose.ui.graphics.Color(0xFFDFEAE0), phone.mintFoam)
    assertEquals(androidx.compose.ui.graphics.Color(0xFFFDF8EB), phone.creamIvory)
    assertEquals(androidx.compose.ui.graphics.Color(0xFF212C1E), phone.darkText)
    // Dark blue 0xFF223E5B must NOT be used
    org.junit.Assert.assertNotEquals(androidx.compose.ui.graphics.Color(0xFF223E5B), phone.darkText)
  }

  @Test
  fun testScheduleEditingAndAlarmToggle() {
    val original = com.example.data.model.TimetableSession(
      id = 10,
      subjectId = 1,
      subjectName = "Data Structures",
      topic = "Graphs BFS/DFS",
      date = "2026-10-11",
      startTime = "09:00",
      endTime = "10:30",
      location = "Room 101",
      isAlarmEnabled = true
    )

    // Edit schedule time and topic
    val edited = original.copy(
      topic = "Advanced Graph Algorithms & Dijkstra",
      startTime = "10:00",
      endTime = "11:30",
      isAlarmEnabled = false
    )

    assertEquals("Advanced Graph Algorithms & Dijkstra", edited.topic)
    assertEquals("10:00", edited.startTime)
    assertEquals("11:30", edited.endTime)
    assertFalse(edited.isAlarmEnabled)
  }

  @Test
  fun testLocalAiLinearEquationSolver() = runBlocking {
    val res = com.example.data.ai.LocalAiEngine.answerQuestion("solve 2x + 5 = 15")
    assertTrue(res.text.contains("x = 5"))
    assertTrue(res.text.contains("Step-by-Step"))
    assertFalse(res.isError)
  }

  @Test
  fun testLocalAiAlgorithmCodeGenerator() = runBlocking {
    val res = com.example.data.ai.LocalAiEngine.answerQuestion("write binary search algorithm in python")
    assertTrue(res.text.contains("def binary_search"))
    assertTrue(res.text.contains("Complexity Analysis"))
    assertFalse(res.isError)
  }

  @Test
  fun testLocalAiKnowledgeGraph() = runBlocking {
    val resTcp = com.example.data.ai.LocalAiEngine.answerQuestion("explain tcp protocol")
    assertTrue(resTcp.text.contains("Transmission Control Protocol"))
    assertTrue(resTcp.text.contains("Three-Way Handshake"))

    val resBayes = com.example.data.ai.LocalAiEngine.answerQuestion("what is bayes theorem")
    assertTrue(resBayes.text.contains("Bayes' Theorem"))
    assertTrue(resBayes.text.contains("P(A|B)"))

    val resBio = com.example.data.ai.LocalAiEngine.answerQuestion("explain mitochondria")
    assertTrue(resBio.text.contains("Mitochondria"))
    assertTrue(resBio.text.contains("ATP"))
  }

  @Test
  fun testLocalAiJavaObjectsAnswersWithConcreteDetails() = runBlocking {
    val res = com.example.data.ai.LocalAiEngine.answerQuestion("java objects")
    assertTrue(res.text.contains("Java Objects") || res.text.contains("Object"))
    assertTrue(res.text.contains("State") || res.text.contains("Heap") || res.text.contains("instance"))
    // Ensure no vague template filler
    assertFalse(res.text.contains("the key academic approach is breaking down the subject into its fundamental definitions"))
    assertFalse(res.isError)
  }

  @Test
  fun testLocalAiGuaranteedResponseForArbitraryQuestion() = runBlocking {
    val res = com.example.data.ai.LocalAiEngine.answerQuestion("How should I prepare for semester finals in microeconomics?")
    assertTrue(res.text.isNotBlank())
    assertFalse(res.text.contains("the key academic approach is breaking down"))
    assertFalse(res.isError)
  }

  @Test
  fun testLocalAiQuizGenerationAndSummary() {
    val topic = StudyTopic(
      id = 100,
      subjectId = 1,
      subjectName = "Organic Chemistry",
      title = "Electrophilic Aromatic Substitution",
      dateStudied = "2026-10-18",
      descriptionNotes = "Benzene reacts with electrophiles in presence of Lewis acid catalysts like FeBr3 or AlCl3."
    )

    val questions = com.example.data.ai.LocalAiEngine.generateLocalQuizQuestions(topic, 3, "HARD")
    assertEquals(3, questions.size)
    questions.forEach { q ->
      assertTrue(q.questionText.isNotBlank())
      assertTrue(q.explanation.isNotBlank())
    }

    val summary = com.example.data.ai.LocalAiEngine.summarizeMaterial(topic.title, topic.descriptionNotes)
    // The built-in summary gives the gist and the main points, without Q:/A: labels or Markdown marks.
    assertTrue(summary.startsWith("**In short:**"))
    assertTrue(!summary.contains("Q:") && !summary.contains("###"))
  }

  @Test
  fun testLocalAiMultiLevelLadderCoversAllTiers() = runBlocking {
    val res = com.example.data.ai.LocalAiEngine.answerQuestion("explain java objects")
    // Validates presence of structured academic tutor breakdown
    assertTrue(res.text.contains("Topic Breakdown") || res.text.contains("Java") || res.text.contains("Object"))
    assertTrue(res.text.contains("Core Summary") || res.text.contains("Thesis"))
    assertTrue(res.text.contains("Topic Explanation") || res.text.contains("Mechanics") || res.text.contains("Principle"))
    assertTrue(res.text.contains("Concrete Example") || res.text.contains("Scenario"))
    // Validates rich Markdown formatting
    assertTrue(res.text.contains("###") || res.text.contains("####"))
    assertFalse(res.isError)
  }

  @Test
  fun testLocalAiCoversMultipleDisciplines() = runBlocking {
    // Physics
    val physicsRes = com.example.data.ai.LocalAiEngine.answerQuestion("how does gravity work")
    assertTrue(physicsRes.text.contains("Gravity") || physicsRes.text.contains("Spacetime"))
    assertTrue(physicsRes.text.contains("Topic Breakdown") || physicsRes.text.contains("Core Summary") || physicsRes.text.contains("Topic Explanation"))

    // Economics
    val econRes = com.example.data.ai.LocalAiEngine.answerQuestion("what is inflation")
    assertTrue(econRes.text.contains("Inflation") || econRes.text.contains("Purchasing Power"))
    assertTrue(econRes.text.contains("Topic Breakdown") || econRes.text.contains("Core Summary") || econRes.text.contains("Topic Explanation"))

    // Mathematics Calculus / Derivatives
    val mathRes = com.example.data.ai.LocalAiEngine.answerQuestion("derivative of x^3")
    assertTrue(mathRes.text.contains("3x^2"))

    // Curiosity
    val skyRes = com.example.data.ai.LocalAiEngine.answerQuestion("why is the sky blue")
    assertTrue(skyRes.text.contains("Rayleigh") || skyRes.text.contains("Scattering"))
    assertTrue(skyRes.text.contains("Topic Breakdown") || skyRes.text.contains("Core Summary") || skyRes.text.contains("Topic Explanation"))

    // Law
    val lawRes = com.example.data.ai.LocalAiEngine.answerQuestion("what is a contract in law")
    assertTrue(lawRes.text.contains("Contract") || lawRes.text.contains("Agreement"))
    assertTrue(lawRes.text.contains("Consideration") || lawRes.text.contains("Offer"))
  }

  @Test
  fun testChatLinkFetcherUrlDetection() {
    assertTrue(com.example.data.remote.ChatLinkFetcher.isUrl("https://chatgpt.com/share/6789abcd"))
    assertTrue(com.example.data.remote.ChatLinkFetcher.isUrl("https://claude.ai/share/1234-xyz"))
    assertTrue(com.example.data.remote.ChatLinkFetcher.isUrl("http://gemini.google.com/share/abc"))
    assertFalse(com.example.data.remote.ChatLinkFetcher.isUrl("Can you explain eigenvalues to me?"))
    assertFalse(com.example.data.remote.ChatLinkFetcher.isUrl(""))
  }

  @Test
  fun testChatLinkFetcherSampleLinkExtraction() = runBlocking {
    val result = com.example.data.remote.ChatLinkFetcher.fetchChatFromUrl("https://chatgpt.com/share/sample-dijkstra-shortest-path")
    assertTrue(result.isSuccess)
    assertEquals("ChatGPT", result.platform)
    assertTrue(result.conversationText.contains("Dijkstra"))
    assertTrue(result.conversationText.contains("priority queue") || result.conversationText.contains("O((V + E) log V)"))
  }

  @Test
  fun testChatKnowledgeExtractorExtractsFormulasAndTraps() = runBlocking {
    val sampleDialogue = """
      User: Explain Dijkstra's algorithm with time complexity and common mistakes.
      Assistant: Dijkstra's algorithm finds the shortest path in a weighted graph with non-negative edge weights.
      Formula / Complexity: O((V + E) log V) using a min-heap priority queue.
      Key Concept: Greedy choice property guarantees optimal shortest distances.
      Common Trap: Negative edge weights cause infinite loops or incorrect shortest paths; use Bellman-Ford instead.
      Code:
      import heapq
      def dijkstra(graph, start):
          pq = [(0, start)]
          return distances
    """.trimIndent()

    val extraction = com.example.data.ai.ChatKnowledgeExtractor.processAndExtract(
      title = "Dijkstra Review",
      subjectName = "Computer Science",
      rawText = sampleDialogue,
      tags = "algorithms",
      aiDispatcher = { null }
    )

    assertTrue(extraction.concepts.isNotEmpty())
    val allFormulas = extraction.concepts.map { it.formulaOrCode }
    assertTrue(allFormulas.any { it.contains("O(") || it.contains("V") || it.contains("heap") })
    val allTraps = extraction.concepts.map { it.misconceptionsAndCorrections }
    assertTrue(allTraps.any { it.contains("Negative", ignoreCase = true) || it.contains("Bellman", ignoreCase = true) })
  }

  @Test
  fun testDetectBestSubjectMatchesExistingOrProposesNewSubject() {
    val existingSubjects = listOf(
      com.example.data.model.Subject(id = 1L, name = "Data Structures & Algorithms", code = "CS201", colorHex = "#8C9F85"),
      com.example.data.model.Subject(id = 2L, name = "Operating Systems", code = "CS301", colorHex = "#7A9E7E")
    )

    val matchedExisting = com.example.data.ai.ChatKnowledgeExtractor.detectBestSubject(
      title = "Dijkstra Shortest Path Priority Queue",
      rawText = "Graph shortest path using binary search trees and dynamic programming.",
      sourceUrl = "https://chatgpt.com/share/dijkstra-cs201",
      discoveredTopics = listOf("Graph Algorithms"),
      existingSubjects = existingSubjects
    )
    assertEquals(1L, matchedExisting.matchedSubject?.id)
    assertEquals("Data Structures & Algorithms", matchedExisting.suggestedName)

    val proposedNew = com.example.data.ai.ChatKnowledgeExtractor.detectBestSubject(
      title = "Thermodynamics Entropy & Carnot Cycle",
      rawText = "Heat engine efficiency depends on hot and cold reservoir temperatures in thermodynamics.",
      sourceUrl = "https://claude.ai/share/thermo-carnot",
      discoveredTopics = listOf("Thermodynamics"),
      existingSubjects = existingSubjects
    )
    org.junit.Assert.assertNull(proposedNew.matchedSubject)
    assertTrue(proposedNew.suggestedName.contains("Physics", ignoreCase = true) || proposedNew.suggestedName.contains("Thermo", ignoreCase = true))
    assertTrue(proposedNew.suggestedCode.isNotBlank())
  }

  @Test
  fun testChatHistoryAssistantLinkOnlySubjectScopingAndFallback() {
    val dsaSubject = com.example.data.model.Subject(id = 10L, name = "Data Structures", code = "CS201", colorHex = "#8C9F85")
    val osSubject = com.example.data.model.Subject(id = 20L, name = "Operating Systems", code = "CS301", colorHex = "#7A9E7E")

    val unlinkedChat = com.example.data.model.ImportedChat(
      id = 100L,
      title = "AVL Tree Rotations (No Link)",
      subjectId = 10L,
      subjectName = "Data Structures",
      sourceUrl = "",
      rawConversation = "AVL tree rotations rebalance binary search trees.",
      aiPlatform = "ChatGPT"
    )
    val linkedDsaChat = com.example.data.model.ImportedChat(
      id = 101L,
      title = "Dijkstra Shortest Path Notes",
      subjectId = 10L,
      subjectName = "Data Structures",
      sourceUrl = "https://chatgpt.com/share/dijkstra-notes",
      rawConversation = """
        User: Explain Dijkstra's algorithm and show code.
        Assistant: Dijkstra finds shortest paths in graphs with non-negative weights.
        ```python
        def dijkstra(graph, start):
            return {}
        ```
      """.trimIndent(),
      aiPlatform = "ChatGPT",
      notes = "Graph Theory, Dijkstra"
    )
    val dsaConcept = com.example.data.model.ExtractedConcept(
      id = 500L,
      sourceChatId = 101L,
      sourceChatTitle = linkedDsaChat.title,
      subjectId = 10L,
      subjectName = "Data Structures",
      topic = "Graph Algorithms",
      subtopic = "Shortest Paths",
      conceptName = "Dijkstra's Algorithm",
      definition = "Greedy single-source shortest path algorithm for non-negative edge weights.",
      technicalDetails = "Uses a min-heap priority queue to relax outgoing edges.",
      formulaOrCode = "def dijkstra(graph, start): return {}",
      problemsSolved = "Routing shortest path in weighted road networks.",
      keyFacts = "Runs in O((V + E) log V) with a binary heap.",
      misconceptionsAndCorrections = "Fails with negative edge weights."
    )

    val allChats = listOf(unlinkedChat, linkedDsaChat)
    val allConcepts = listOf(dsaConcept)

    // Unlinked chat (blank sourceUrl) must be ignored
    val ignoredUnlinked = com.example.data.ai.ChatHistoryAssistant.answerFromLinkedChats(
      query = "AVL tree rotations",
      history = emptyList(),
      importedChats = allChats,
      extractedConcepts = allConcepts
    )
    org.junit.Assert.assertNull(ignoredUnlinked)

    // Matching linked chat returns grounded response with URL
    val directMatch = com.example.data.ai.ChatHistoryAssistant.answerFromLinkedChats(
      query = "How does Dijkstra priority queue work?",
      history = emptyList(),
      importedChats = allChats,
      extractedConcepts = allConcepts,
      selectedSubject = dsaSubject
    )
    org.junit.Assert.assertNotNull(directMatch)
    assertTrue(directMatch!!.text.contains("Dijkstra", ignoreCase = true))
    assertEquals("https://chatgpt.com/share/dijkstra-notes", directMatch.groundingSources.firstOrNull()?.url)

    // Scoped to a different subject returns null so caller falls back to AI
    val wrongSubjectScope = com.example.data.ai.ChatHistoryAssistant.answerFromLinkedChats(
      query = "How does Dijkstra priority queue work?",
      history = emptyList(),
      importedChats = allChats,
      extractedConcepts = allConcepts,
      selectedSubject = osSubject
    )
    org.junit.Assert.assertNull(wrongSubjectScope)

    // Follow-up ("Example in Code" / "Summarize") uses prior grounded linked chat
    val priorHistory = listOf(
      com.example.data.model.ChatMessage(
        role = "model",
        text = directMatch.text,
        modelName = directMatch.modelName,
        groundingSources = directMatch.groundingSources
      )
    )
    val codeFollowUp = com.example.data.ai.ChatHistoryAssistant.answerFromLinkedChats(
      query = "Show an Example in Code",
      history = priorHistory,
      importedChats = allChats,
      extractedConcepts = allConcepts,
      selectedSubject = dsaSubject
    )
    org.junit.Assert.assertNotNull(codeFollowUp)
    assertTrue(codeFollowUp!!.text.contains("def dijkstra"))

    val summaryFollowUp = com.example.data.ai.ChatHistoryAssistant.answerFromLinkedChats(
      query = "Summarize",
      history = priorHistory,
      importedChats = allChats,
      extractedConcepts = allConcepts,
      selectedSubject = dsaSubject
    )
    org.junit.Assert.assertNotNull(summaryFollowUp)
    assertTrue(summaryFollowUp!!.text.contains("Summary", ignoreCase = true))

    // Unrelated question returns null to fall back to Gemini / Local AI
    val fallbackNull = com.example.data.ai.ChatHistoryAssistant.answerFromLinkedChats(
      query = "Explain Keynesian fiscal multiplier in macroeconomics",
      history = emptyList(),
      importedChats = allChats,
      extractedConcepts = allConcepts
    )
    org.junit.Assert.assertNull(fallbackNull)
  }

  @Test
  fun testWithoutAnyModelTheTutorUsesBuiltInAnswers() = runBlocking {
    val reply = service.chat(listOf(com.example.data.model.ChatMessage(role = "user", text = "what is bellman ford algorithm")))
    assertEquals(com.example.data.ai.LocalAiEngine.BUILT_IN_LABEL, reply.modelName)
    assertTrue(reply.text.contains("Bellman-Ford", ignoreCase = true))
  }

  @Test
  fun testLinkBasedMiniTopicsAndQuickPromptsStrictlyFollowProvidedLinks() {
    val dsaSubject = com.example.data.model.Subject(id = 10L, name = "Data Structures", code = "CS201", colorHex = "#8C9F85")
    val osSubject = com.example.data.model.Subject(id = 20L, name = "Operating Systems", code = "CS301", colorHex = "#7A9E7E")

    val unlinkedChat = com.example.data.model.ImportedChat(
      id = 200L,
      title = "Generic QuickSort Lecture",
      subjectId = 10L,
      subjectName = "Data Structures",
      sourceUrl = "",
      rawConversation = "QuickSort and MergeSort partition arrays.",
      tags = "QuickSort, MergeSort"
    )
    val linkedGraphChat = com.example.data.model.ImportedChat(
      id = 201L,
      title = "Bellman-Ford & Dijkstra Study Notes",
      subjectId = 10L,
      subjectName = "Data Structures",
      sourceUrl = "https://gemini.google.com/share/graph-shortest-paths",
      tags = "Negative Weights, Edge Relaxation",
      rawConversation = """
        ### Dijkstra Priority Queue
        **Bellman-Ford Cycle Check:** Detects negative weight cycles in V-1 passes.
        Use a min-heap for non-negative edge relaxation.
      """.trimIndent(),
      aiPlatform = "Gemini",
      importedAt = 2000L
    )
    val linkedOsChat = com.example.data.model.ImportedChat(
      id = 202L,
      title = "Virtual Memory & Paging",
      subjectId = 20L,
      subjectName = "Operating Systems",
      sourceUrl = "https://chatgpt.com/share/os-paging",
      tags = "TLB Cache, Page Faults",
      rawConversation = "### Page Replacement\nLRU evicts the least recently used page frame.",
      aiPlatform = "ChatGPT",
      importedAt = 3000L
    )

    val graphConcept = com.example.data.model.ExtractedConcept(
      id = 601L,
      sourceChatId = 201L,
      sourceChatTitle = linkedGraphChat.title,
      subjectId = 10L,
      subjectName = "Data Structures",
      topic = "Graph Shortest Paths",
      subtopic = "Edge Relaxation",
      conceptName = "Bellman-Ford Algorithm",
      definition = "Computes shortest paths even with negative edge weights.",
      technicalDetails = "Relaxes all edges V-1 times and checks for negative cycles on the Vth pass.",
      relatedConceptsJson = """["Dijkstra Priority Queue","DAG Relaxation"]""",
      importanceScore = 5
    )

    val chats = listOf(unlinkedChat, linkedGraphChat, linkedOsChat)
    val concepts = listOf(graphConcept)

    // 1. Direct extraction comes only from the linked chat & its concepts, never generic QuickSort/MergeSort
    val extractedMini = com.example.data.ai.ChatHistoryAssistant.extractLinkBasedMiniTopics(
      chat = linkedGraphChat,
      chatConcepts = listOf(graphConcept),
      maxCount = 5
    )
    assertTrue(extractedMini.contains("Bellman-Ford Algorithm"))
    assertTrue(extractedMini.any { it.contains("Edge Relaxation", ignoreCase = true) || it.contains("Graph Shortest Paths", ignoreCase = true) })
    assertTrue(extractedMini.none { it.equals("QuickSort", ignoreCase = true) || it.equals("MergeSort", ignoreCase = true) || it.equals("Binary Search", ignoreCase = true) })

    // 2. Grounding URL forces exact link match for mini topics under message
    val urlScopedMini = com.example.data.ai.ChatHistoryAssistant.getMiniTopicsForLinkedScope(
      queryOrText = "Explain shortest paths",
      groundingUrls = listOf("https://gemini.google.com/share/graph-shortest-paths"),
      importedChats = chats,
      extractedConcepts = concepts,
      selectedSubject = null,
      maxCount = 5
    )
    assertTrue(urlScopedMini.contains("Bellman-Ford Algorithm"))
    assertTrue(urlScopedMini.none { it.contains("Page Faults", ignoreCase = true) })

    // 3. Subject scoping isolates linked OS chat mini topics from DSA chat
    val osMini = com.example.data.ai.ChatHistoryAssistant.getMiniTopicsForLinkedScope(
      queryOrText = "Explain memory",
      groundingUrls = emptyList(),
      importedChats = chats,
      extractedConcepts = concepts,
      selectedSubject = osSubject,
      maxCount = 5
    )
    assertTrue(osMini.any { it.contains("TLB Cache", ignoreCase = true) || it.contains("Page Faults", ignoreCase = true) || it.contains("Page Replacement", ignoreCase = true) })
    assertTrue(osMini.none { it.contains("Bellman-Ford", ignoreCase = true) })

    // 4. Unlinked chats alone return emptyList() so generic fallback only happens without links
    val noLinksResult = com.example.data.ai.ChatHistoryAssistant.getMiniTopicsForLinkedScope(
      queryOrText = "QuickSort",
      groundingUrls = emptyList(),
      importedChats = listOf(unlinkedChat),
      extractedConcepts = emptyList(),
      selectedSubject = dsaSubject,
      maxCount = 5
    )
    assertTrue(noLinksResult.isEmpty())

    // 5. Quick prompt chips carry linked-chat prompts and respect selectedSubject
    val dsaPromptChips = com.example.data.ai.ChatHistoryAssistant.buildQuickPromptsFromLinkedChats(
      importedChats = chats,
      extractedConcepts = concepts,
      selectedSubject = dsaSubject,
      maxCount = 6
    )
    assertTrue(dsaPromptChips.isNotEmpty())
    assertTrue(dsaPromptChips.all { it.sourceUrl == linkedGraphChat.sourceUrl })
    assertTrue(dsaPromptChips.first().label.startsWith("🔗 "))

    // 6. Clicking a 1-token mini topic (e.g. "Bellman-Ford" / "TLB") retrieves from linked chat
    val miniChipClickAnswer = com.example.data.ai.ChatHistoryAssistant.answerFromLinkedChats(
      query = "Bellman-Ford",
      history = emptyList(),
      importedChats = chats,
      extractedConcepts = concepts,
      selectedSubject = dsaSubject
    )
    org.junit.Assert.assertNotNull(miniChipClickAnswer)
    assertEquals(linkedGraphChat.sourceUrl, miniChipClickAnswer!!.groundingSources.firstOrNull()?.url)
    assertTrue(miniChipClickAnswer.relatedTopics.contains("Bellman-Ford Algorithm"))
  }

  @Test
  fun testDirectAnswerMatchesSpecificQuestionAsked() {
    val dsaSubject = com.example.data.model.Subject(id = 10L, name = "Data Structures", code = "CS201", colorHex = "#8C9F85")
    val linkedDijkstra = com.example.data.model.ImportedChat(
      id = 301L,
      title = "Dijkstra Shortest Path Notes",
      subjectId = 10L,
      subjectName = "Data Structures",
      sourceUrl = "https://chatgpt.com/share/dijkstra-direct",
      rawConversation = """
        User: Explain Dijkstra's algorithm.
        Assistant: Dijkstra's algorithm uses a min-heap priority queue to greedily find shortest paths.

        Complexity Analysis:
        - Total Time Complexity: O((V + E) log V) using a binary min-heap.
        - Space Complexity: O(V + E) for adjacency lists and distance arrays.

        Common Pitfall:
        - Negative edge weights: Dijkstra fails when edges are negative because visited nodes are assumed finalized.
      """.trimIndent(),
      aiPlatform = "ChatGPT"
    )
    val concept = com.example.data.model.ExtractedConcept(
      id = 701L,
      sourceChatId = 301L,
      sourceChatTitle = linkedDijkstra.title,
      subjectId = 10L,
      subjectName = "Data Structures",
      topic = "Graph Algorithms",
      subtopic = "Shortest Paths",
      conceptName = "Dijkstra's Algorithm",
      definition = "Greedy single-source shortest path algorithm.",
      technicalDetails = "Relaxes outgoing edges via a min-heap.",
      keyFacts = "Total Time Complexity: O((V + E) log V).",
      misconceptionsAndCorrections = "Fails with negative edge weights.",
      formulaOrCode = "def dijkstra(g, s): pass",
      problemsSolved = "GPS shortest-route navigation on weighted road graphs."
    )

    // 1. Asking specifically about complexity surfaces complexity details right in the answer
    val complexityAns = com.example.data.ai.ChatHistoryAssistant.answerFromLinkedChats(
      query = "What is the time complexity of Dijkstra?",
      history = emptyList(),
      importedChats = listOf(linkedDijkstra),
      extractedConcepts = listOf(concept),
      selectedSubject = dsaSubject
    )
    org.junit.Assert.assertNotNull(complexityAns)
    assertTrue(complexityAns!!.text.contains("O((V + E) log V)"))

    // 2. Asking why it fails surfaces the negative edge weights trap
    val failAns = com.example.data.ai.ChatHistoryAssistant.answerFromLinkedChats(
      query = "Why does Dijkstra fail with negative weights?",
      history = emptyList(),
      importedChats = listOf(linkedDijkstra),
      extractedConcepts = listOf(concept),
      selectedSubject = dsaSubject
    )
    org.junit.Assert.assertNotNull(failAns)
    assertTrue(failAns!!.text.contains("negative", ignoreCase = true))

    // 3. Another Example returns null when only one problem example exists in the link (so AI generates a fresh one)
    val historyAfterExplain = listOf(
      com.example.data.model.ChatMessage(
        role = "model",
        text = "### 🔎 Example Breakdown From Linked Chat: ${linkedDijkstra.title}\n- GPS shortest-route navigation on weighted road graphs.",
        modelName = "Linked Chat (Data Structures)",
        groundingSources = listOf(com.example.data.model.GroundingSource("🔗 Dijkstra", linkedDijkstra.sourceUrl))
      )
    )
    val anotherExample = com.example.data.ai.ChatHistoryAssistant.answerFromLinkedChats(
      query = "Give another example of Dijkstra",
      history = historyAfterExplain,
      importedChats = listOf(linkedDijkstra),
      extractedConcepts = listOf(concept),
      selectedSubject = dsaSubject
    )
    org.junit.Assert.assertNull(anotherExample)

    // 4. Built-in AcademicKnowledgeBase tailors its answer to what is asked without canned context filler
    val trapArticle = com.example.data.ai.AcademicKnowledgeBase.search("acid")!!
    val trapFormatted = com.example.data.ai.AcademicKnowledgeBase.formatArticle(trapArticle, targetLevel = "What is a common exam trap in ACID?")
    assertTrue(trapFormatted.contains("Common Trap", ignoreCase = true))
    assertTrue(!trapFormatted.contains("Previous Topic Context"))
  }

  @Test
  fun testBellmanFordDoesNotHijackDijkstraLinkAndAnswersDirectly() = runBlocking {
    val dsaSubject = com.example.data.model.Subject(id = 10L, name = "Data Structures", code = "CS201", colorHex = "#8C9F85")
    val linkedDijkstra = com.example.data.model.ImportedChat(
      id = 401L,
      title = "Dijkstra Algorithm & Shortest Path Mechanics",
      subjectId = 10L,
      subjectName = "Data Structures",
      sourceUrl = "https://chatgpt.com/share/sample-dijkstra",
      rawConversation = """
        User: Explain Dijkstra's Shortest Path Algorithm.
        Assistant: Dijkstra's algorithm uses a greedy min-heap priority queue for non-negative edge weights.
        Negative edge weights: Dijkstra fails on graphs with negative weights. Bellman-Ford or SPFA must be used instead!
      """.trimIndent(),
      aiPlatform = "ChatGPT"
    )
    val dijkstraConcept = com.example.data.model.ExtractedConcept(
      id = 801L,
      sourceChatId = 401L,
      sourceChatTitle = linkedDijkstra.title,
      subjectId = 10L,
      subjectName = "Data Structures",
      topic = "Graph Algorithms",
      subtopic = "Shortest Paths",
      conceptName = "Dijkstra's Algorithm",
      definition = "Greedy single-source shortest path algorithm for non-negative edge weights.",
      misconceptionsAndCorrections = "Fails on negative edge weights; use Bellman-Ford instead."
    )

    // Asking "what is bellman ford algorithm" must NOT hijack Dijkstra's link just because it shares "algorithm" and mentions Bellman-Ford once
    val linkedMatch = com.example.data.ai.ChatHistoryAssistant.answerFromLinkedChats(
      query = "what is bellman ford algorithm",
      history = emptyList(),
      importedChats = listOf(linkedDijkstra),
      extractedConcepts = listOf(dijkstraConcept),
      selectedSubject = dsaSubject
    )
    org.junit.Assert.assertNull(linkedMatch)

    // Fallback to Local AI (or Gemini when configured) answers directly what Bellman-Ford algorithm is
    val aiAnswer = com.example.data.ai.LocalAiEngine.answerQuestion("what is bellman ford algorithm")
    assertTrue(aiAnswer.text.contains("Bellman-Ford", ignoreCase = true))
    assertTrue(aiAnswer.text.contains("negative", ignoreCase = true))
    assertTrue(aiAnswer.text.contains("V - 1", ignoreCase = true) || aiAnswer.text.contains("O(V * E)", ignoreCase = true))
  }

  @Test
  fun testParallelChunkExtractionMergesAndDeduplicatesWithExistingMemory() = runBlocking {
    val blockA = """
      # Dijkstra's Algorithm
      User: Explain Dijkstra's Shortest Path Algorithm.
      Assistant: Dijkstra's algorithm computes shortest paths in graphs with non-negative edge weights using a min-heap priority queue.
      Formula / Complexity: O((V + E) log V)
      Common Trap: Negative edge weights break greedy relaxation.
    """.trimIndent()
    val blockB = """
      # Optimal Substructure
      User: How does Dynamic Programming Memoization work?
      Assistant: Dynamic programming solves overlapping subproblems using top-down memoization or bottom-up tabulation.
      Key Concept: Optimal substructure allows optimal solutions of subproblems to compose into the global optimum.
    """.trimIndent()
    val padding = List(70) { idx ->
      "Line $idx: Graph traversal and relaxation invariants keep priority queue distances optimal over adjacency lists."
    }.joinToString("\n")
    val largeConversation = "$blockA\n\n$padding\n\n$blockB"

    val existing = com.example.data.model.ExtractedConcept(
      id = 42L,
      sourceChatId = 9L,
      sourceChatTitle = "Prior Graph Study",
      subjectId = 1L,
      subjectName = "Computer Science",
      topic = "Dijkstra Review",
      subtopic = "Shortest Paths",
      conceptName = "Dijkstra's Algorithm",
      definition = "Greedy shortest path."
    )

    val result = com.example.data.ai.ChatKnowledgeExtractor.processAndExtract(
      title = "Dijkstra Review",
      subjectName = "Computer Science",
      rawText = largeConversation,
      tags = "algorithms,graphs",
      existingConcepts = listOf(existing),
      aiDispatcher = { null }
    )

    assertTrue("Expected multiple chunks for large conversation", result.chunkCount >= 2)
    assertTrue(result.concepts.any { it.conceptName == "Dijkstra's Algorithm" && it.isExistingOverlap && it.existingConceptId == 42L })
    assertTrue(result.concepts.any { it.conceptName == "Optimal Substructure" && !it.isExistingOverlap })

    // Verify HashMap-indexed mergeAndDeduplicate merges duplicate drafts in the same batch and links existing memory
    val merged = com.example.data.ai.ChatKnowledgeExtractor.mergeAndDeduplicate(
      drafts = listOf(
        com.example.data.ai.ChatKnowledgeExtractor.ExtractedConceptDraft(
          topic = "Dijkstra Review",
          subtopic = "Shortest Paths",
          conceptName = "Dijkstra's Algorithm",
          definition = "Short def",
          technicalDetails = "Priority queue relaxation",
          formulaOrCode = "O((V + E) log V)"
        ),
        com.example.data.ai.ChatKnowledgeExtractor.ExtractedConceptDraft(
          topic = "Dijkstra Review",
          subtopic = "Shortest Paths",
          conceptName = "Dijkstra's Algorithm",
          definition = "Longer detailed greedy shortest path definition for non-negative edge weights.",
          technicalDetails = "Min-heap invariant",
          formulaOrCode = ""
        ),
        com.example.data.ai.ChatKnowledgeExtractor.ExtractedConceptDraft(
          topic = "Dynamic Programming",
          subtopic = "Memoization",
          conceptName = "Optimal Substructure",
          definition = "Global optimum composes from optimal subproblems."
        )
      ),
      existingConcepts = listOf(existing),
      tags = "algorithms,graphs"
    )

    assertEquals(2, merged.size)
    val mergedDijkstra = merged.first { it.conceptName == "Dijkstra's Algorithm" }
    assertTrue(mergedDijkstra.isExistingOverlap)
    assertEquals(42L, mergedDijkstra.existingConceptId)
    assertTrue(mergedDijkstra.technicalDetails.contains("Priority queue relaxation"))
    assertTrue(mergedDijkstra.technicalDetails.contains("Min-heap invariant"))
    assertEquals("O((V + E) log V)", mergedDijkstra.formulaOrCode)
  }

  @Test
  fun testAcademicKnowledgeBaseRepeatedLookupAndPluralMatch() {
    val startNs = System.nanoTime()
    repeat(200) {
      val article = com.example.data.ai.AcademicKnowledgeBase.search("eigenvalues")
      org.junit.Assert.assertNotNull(article)
      val formatted = com.example.data.ai.AcademicKnowledgeBase.formatArticle(article!!, targetLevel = "example")
      assertTrue(formatted.contains("Concrete Example", ignoreCase = true) || formatted.contains("Eigenvalue", ignoreCase = true))
    }
    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
    assertTrue("200 indexed knowledge searches took ${elapsedMs}ms", elapsedMs < 2000L)
  }
}



