package com.example.data.ai

import com.example.data.model.ChatMessage
import com.example.data.model.ExtractedConcept
import com.example.data.model.GroundingSource
import com.example.data.model.ImportedChat
import com.example.data.model.Subject
import com.example.data.remote.ChatResponse
import org.json.JSONArray
import java.util.Locale

/**
 * ChatHistoryAssistant: Link-based chat history retrieval engine for the AI Chatbot.
 *
 * Behavioral Contract:
 * 1. Considers ONLY imported chats that contain a valid non-blank link (`sourceUrl.isNotBlank()`).
 * 2. Respects subject grouping: when `selectedSubject` is specified, searches only linked chats
 *    grouped under that subject.
 * 3. Resolves follow-up button intents (`Another Example`, `Example in Code`, `Another Example in Code`,
 *    `Summarize`, `Explain Example`) against the active/matched linked chat first.
 * 4. If a linked chat answers the query, returns a grounded ChatResponse citing that linked chat's URL
 *    and subject; if NO linked chat matches, returns `null` so the caller seamlessly falls back to AI
 *    (Gemini or Local AI).
 */
object ChatHistoryAssistant {

  data class LinkPromptChip(
    val label: String,
    val prompt: String,
    val sourceUrl: String,
    val chatTitle: String
  )

  private val stopWords = setOf(
    "the", "and", "for", "with", "that", "this", "from", "into", "about", "what", "when", "where",
    "which", "who", "whom", "why", "how", "does", "did", "can", "could", "would", "should",
    "explain", "tell", "give", "show", "define", "mean", "meaning", "please", "help", "need",
    "have", "has", "are", "was", "were", "will", "just", "only", "your", "their", "them", "they",
    "there", "here", "then", "than", "also", "more", "some", "make", "used", "using",
    "linked", "chat", "notes", "answer", "question"
  )

  private data class ParsedPrompt(
    val focusQuery: String,
    val hintedChatTitle: String?
  )

  private val P1_PROMPT_REGEX = Regex("""(?i)^from\s+my\s+linked\s+chat\s+"([^"]+)"\s*,?\s*(?:explain|about|regarding)?\s*(.+?)\.?$""")
  private val P2_PROMPT_REGEX = Regex("""(?i)^(?:explain|describe|tell\s+me\s+about)\s+(.+?)\s+from\s+(?:my\s+)?linked\s+chat\s+"([^"]+)"\.?$""")
  private val MINI_TOPIC_CLEAN_REGEX = Regex("""[*_`#🔗💡📌⚡🔎]+""")
  private val WS_REGEX = Regex("""\s+""")
  private val CODE_FENCE_STRIP_REGEX = Regex("```[\\s\\S]*?```")
  private val BOLD_LABEL_REGEX = Regex("""^\*\*([^*\n]{3,36}?):?\*\*""")
  private val CODE_BLOCK_EXTRACT_REGEX = Regex("```[a-zA-Z0-9_+-]*\\n([\\s\\S]*?)```")
  private val PARAGRAPH_SPLIT_REGEX = Regex("\\n\\s*\\n")
  private val BULLET_PREFIX_REGEX = Regex("^[-*•]\\s+")
  private val TOKEN_SPLIT_REGEX = Regex("[^a-z0-9+]+")

  private fun unwrapLinkedPrompt(rawQuery: String): ParsedPrompt {
    val trimmed = rawQuery.trim()
    val p1 = P1_PROMPT_REGEX.find(trimmed)
    if (p1 != null) {
      return ParsedPrompt(
        focusQuery = p1.groupValues[2].trim().ifBlank { trimmed },
        hintedChatTitle = p1.groupValues[1].trim()
      )
    }
    val p2 = P2_PROMPT_REGEX.find(trimmed)
    if (p2 != null) {
      return ParsedPrompt(
        focusQuery = p2.groupValues[1].trim().ifBlank { trimmed },
        hintedChatTitle = p2.groupValues[2].trim()
      )
    }
    return ParsedPrompt(focusQuery = trimmed, hintedChatTitle = null)
  }

  fun answerFromLinkedChats(
    query: String,
    history: List<ChatMessage>,
    importedChats: List<ImportedChat>,
    extractedConcepts: List<ExtractedConcept>,
    selectedSubject: Subject? = null
  ): ChatResponse? {
    val cleanQuery = query.trim()
    if (cleanQuery.isBlank()) return null

    // Strictly restrict chatbot retrieval to link-containing imported chats ONLY
    val linkedChats = importedChats.filter { it.sourceUrl.isNotBlank() }
    if (linkedChats.isEmpty()) return null

    val scopedChats = if (selectedSubject != null) {
      linkedChats.filter {
        it.subjectId == selectedSubject.id ||
          it.subjectName.equals(selectedSubject.name, ignoreCase = true)
      }
    } else {
      linkedChats
    }
    if (scopedChats.isEmpty()) return null

    val conceptsByChat = extractedConcepts.groupBy { it.sourceChatId }

    // Check if the user triggered a follow-up action ("Another Example", "Summarize", "Example in Code", etc.)
    val lower = cleanQuery.lowercase(Locale.ROOT)
    val followUpType = detectFollowUpType(lower)
    val activeHistoryChat = findActiveChatFromHistory(history, scopedChats)
    if (followUpType != null && activeHistoryChat != null) {
      val chatConcepts = conceptsByChat[activeHistoryChat.id].orEmpty()
      val previousModelText = history.lastOrNull { it.role == "model" }?.text.orEmpty()
      val followUpAnswer = buildFollowUpResponse(
        type = followUpType,
        chat = activeHistoryChat,
        concepts = chatConcepts,
        previousModelText = previousModelText
      )
      if (followUpAnswer != null) {
        return followUpAnswer
      }
      // If a follow-up (e.g., "Another Example" or "Another Example in Code") has no distinct
      // second example inside the linked chat, return null so Gemini / Local AI provides a fresh one.
      return null
    }

    val parsedPrompt = unwrapLinkedPrompt(cleanQuery)
    val queryTokens = tokenize(parsedPrompt.focusQuery).ifEmpty { tokenize(cleanQuery) }
    if (queryTokens.isEmpty()) return null

    val weakDescriptors = setOf(
      "algorithm", "algorithms", "method", "methods", "function", "functions",
      "system", "systems", "theory", "model", "models", "concept", "concepts",
      "process", "structure", "structures", "problem", "problems", "example", "examples",
      "code", "implementation", "tutorial", "lecture", "study", "topic", "overview",
      "formula", "equation", "complexity", "time", "space", "difference", "between"
    )
    val distinctiveTokens = queryTokens.filter { it !in weakDescriptors }
    val coreTokens = distinctiveTokens.ifEmpty { queryTokens }

    val tokenMatchers = coreTokens.map { tok ->
      val escaped = Regex.escape(tok)
      Triple(
        tok,
        Regex("\\b$escaped\\b"),
        Regex("(?m)^#{1,4}[^\\n]*\\b$escaped\\b|\\*\\*[^*\\n]*\\b$escaped\\b")
      )
    }

    var bestChat: ImportedChat? = null
    var bestConcept: ExtractedConcept? = null
    var bestScore = 0

    for (chat in scopedChats) {
      val chatConcepts = conceptsByChat[chat.id].orEmpty()
      val titleLower = chat.title.lowercase(Locale.ROOT)
      val tagsLower = chat.tags.lowercase(Locale.ROOT)
      val notesLower = chat.notes.lowercase(Locale.ROOT)
      val rawLower = chat.rawConversation.lowercase(Locale.ROOT)

      val conceptAnchorFields = chatConcepts.map { c ->
        listOf(
          c.conceptName.lowercase(Locale.ROOT),
          c.topic.lowercase(Locale.ROOT),
          c.subtopic.lowercase(Locale.ROOT),
          c.definition.lowercase(Locale.ROOT),
          c.relatedConceptsJson.lowercase(Locale.ROOT),
          (c.technicalDetails + " " + c.keyFacts + " " + c.formulaOrCode + " " + c.problemsSolved + " " + c.misconceptionsAndCorrections).lowercase(Locale.ROOT)
        )
      }

      var chatScore = 0
      var strongHits = 0
      var anchorHits = 0
      var substantialBodyHits = 0

      // Exact phrase boost across title, notes, or multiple occurrences in body
      val corePhrase = coreTokens.joinToString(" ")
      if (corePhrase.length >= 6 && (titleLower.contains(corePhrase) || notesLower.contains(corePhrase))) {
        chatScore += 14
        strongHits += 2
        anchorHits += 2
      } else if (corePhrase.length >= 6 && rawLower.contains(corePhrase)) {
        chatScore += 6
        strongHits += 1
      }

      for ((_, tokRegex, headingBoldRegex) in tokenMatchers) {
        var hit = false
        val inTitle = tokRegex.containsMatchIn(titleLower)
        val inMeta = inTitle || tokRegex.containsMatchIn(tagsLower) || tokRegex.containsMatchIn(notesLower)
        if (inTitle) {
          chatScore += 6
          hit = true
        }
        if (tokRegex.containsMatchIn(notesLower) || tokRegex.containsMatchIn(tagsLower)) {
          chatScore += 4
          hit = true
        }
        val inConceptAnchor = conceptAnchorFields.any { fields ->
          tokRegex.containsMatchIn(fields[0]) ||
            tokRegex.containsMatchIn(fields[1]) ||
            tokRegex.containsMatchIn(fields[2]) ||
            tokRegex.containsMatchIn(fields[3])
        }
        if (inMeta || inConceptAnchor) {
          anchorHits++
        }

        val bodyOccurrences = tokRegex.findAll(rawLower).count()
        if (bodyOccurrences >= 1) {
          chatScore += if (bodyOccurrences >= 2) 4 else 1
          hit = true
        }
        val inHeadingOrBold = headingBoldRegex.containsMatchIn(rawLower)
        if (bodyOccurrences >= 2 || inHeadingOrBold) {
          substantialBodyHits++
        }
        if (hit) strongHits++
      }

      // If the user is asking about a named distinctive topic (e.g. "Bellman-Ford") and the linked chat
      // does NOT cover it in title/tags/notes/concepts/definition AND only mentions it once in passing,
      // skip this chat so Gemini AI or Local AI directly answers what was asked without hallucination!
      if (distinctiveTokens.isNotEmpty() && anchorHits == 0 && substantialBodyHits == 0) {
        continue
      }

      // Weak descriptor tokens add mild tie-break score only
      for (tok in queryTokens) {
        if (tok in weakDescriptors) {
          if (titleLower.contains(tok)) chatScore += 1
          if (rawLower.contains(tok)) chatScore += 1
        }
      }

      var topConceptForChat: ExtractedConcept? = null
      var topConceptScore = 0
      for (idx in chatConcepts.indices) {
        val c = chatConcepts[idx]
        val fields = conceptAnchorFields[idx]
        var cScore = 0
        for ((_, tokRegex, _) in tokenMatchers) {
          if (tokRegex.containsMatchIn(fields[0])) cScore += 6
          else if (tokRegex.containsMatchIn(fields[1]) || tokRegex.containsMatchIn(fields[2])) cScore += 5
          else if (tokRegex.containsMatchIn(fields[3])) cScore += 4
          else if (tokRegex.containsMatchIn(fields[4])) cScore += 3
          else if (tokRegex.containsMatchIn(fields[5])) cScore += 2
        }
        if (cScore > topConceptScore) {
          topConceptScore = cScore
          topConceptForChat = c
        }
      }

      // Tie-break toward explicit hinted chat title or active chat in conversation
      val hintBonus = if (!parsedPrompt.hintedChatTitle.isNullOrBlank() &&
        chat.title.equals(parsedPrompt.hintedChatTitle, ignoreCase = true) &&
        (anchorHits > 0 || substantialBodyHits > 0 || topConceptScore >= 4)
      ) 8 else 0
      val activeBonus = if (activeHistoryChat?.id == chat.id && (anchorHits > 0 || substantialBodyHits > 0)) 4 else 0
      val totalScore = chatScore + topConceptScore + activeBonus + hintBonus

      val passesThreshold = (anchorHits >= 1 && totalScore >= 6) ||
        (substantialBodyHits >= coreTokens.size.coerceAtLeast(1) && totalScore >= 6) ||
        (topConceptScore >= 5 && (anchorHits > 0 || substantialBodyHits > 0))

      if (passesThreshold && totalScore > bestScore) {
        bestScore = totalScore
        bestChat = chat
        bestConcept = topConceptForChat
      }
    }

    val matchedChat = bestChat ?: return null
    val matchedConcepts = conceptsByChat[matchedChat.id].orEmpty()

    return buildDirectChatAnswer(
      rawQuery = parsedPrompt.focusQuery,
      queryTokens = queryTokens,
      chat = matchedChat,
      topConcept = bestConcept,
      allChatConcepts = matchedConcepts
    )
  }

  private enum class FollowUpType {
    ANOTHER_CODE_EXAMPLE,
    CODE_EXAMPLE,
    SUMMARIZE,
    ANOTHER_EXAMPLE,
    EXPLAIN_EXAMPLE
  }

  private fun detectFollowUpType(lower: String): FollowUpType? {
    return when {
      lower.contains("another example in code") || lower.contains("another code") -> FollowUpType.ANOTHER_CODE_EXAMPLE
      lower.contains("example in code") || lower.contains("in code") -> FollowUpType.CODE_EXAMPLE
      lower == "summarize" || lower.startsWith("summarize ") || lower.contains("short summary") -> FollowUpType.SUMMARIZE
      lower.contains("another example") || lower.contains("different example") -> FollowUpType.ANOTHER_EXAMPLE
      lower.contains("explain the example") || lower == "explain example" || lower.startsWith("explain example") -> FollowUpType.EXPLAIN_EXAMPLE
      else -> null
    }
  }

  private fun findActiveChatFromHistory(
    history: List<ChatMessage>,
    scopedChats: List<ImportedChat>
  ): ImportedChat? {
    val prior = if (history.isNotEmpty() && history.last().role == "user") history.dropLast(1) else history
    // 1. Check most recent model messages for groundingSource URL matching a linked chat
    for (msg in prior.asReversed()) {
      if (msg.role == "model") {
        val urls = msg.groundingSources.map { it.url.trim() }.filter { it.isNotBlank() }
        val byUrl = scopedChats.firstOrNull { c -> urls.any { u -> u.equals(c.sourceUrl.trim(), ignoreCase = true) } }
        if (byUrl != null) return byUrl
        if (msg.modelName.startsWith("Linked Chat", ignoreCase = true)) {
          val byTitle = scopedChats.firstOrNull { msg.text.contains(it.title, ignoreCase = true) }
          if (byTitle != null) return byTitle
        }
        break
      }
    }
    return null
  }

  private fun buildFollowUpResponse(
    type: FollowUpType,
    chat: ImportedChat,
    concepts: List<ExtractedConcept>,
    previousModelText: String = ""
  ): ChatResponse? {
    val codeBlocks = extractCodeBlocks(chat.rawConversation, concepts)
    val topics = extractLinkBasedMiniTopics(chat, concepts, maxCount = 5)

    val sourceBadge = listOf(
      GroundingSource(
        title = "🔗 ${chat.subjectName} • ${chat.title}",
        url = chat.sourceUrl
      )
    )

    val bodyText: String? = when (type) {
      FollowUpType.CODE_EXAMPLE -> {
        val block = codeBlocks.firstOrNull()
        if (block != null) {
          buildString {
            append("### 💻 Code From Linked Chat: ${chat.title}\n\n")
            append("```\n$block\n```\n\n")
            append("**Quick Walkthrough (from your linked chat):**\n")
            val primary = concepts.firstOrNull()
            if (primary != null) {
              if (primary.technicalDetails.isNotBlank()) append("- ${primary.technicalDetails}\n")
              if (primary.keyFacts.isNotBlank()) append("- ${primary.keyFacts}\n")
              if (primary.misconceptionsAndCorrections.isNotBlank()) append("- ⚠️ Watch out: ${primary.misconceptionsAndCorrections}\n")
            } else {
              append("- Directly extracted from **${chat.title}** (${chat.aiPlatform}).")
            }
          }.trim()
        } else null // Fall back to AI if the linked chat has no code snippet
      }
      FollowUpType.ANOTHER_CODE_EXAMPLE -> {
        val altBlock = codeBlocks.getOrNull(1)
        if (altBlock != null) {
          buildString {
            append("### 💻 Alternative Code Snippet From Linked Chat: ${chat.title}\n\n")
            append("```\n$altBlock\n```\n\n")
            append("Extracted from **${chat.title}** (`${chat.subjectName}`).")
          }
        } else null // Fall back to AI for a new code example variation
      }
      FollowUpType.SUMMARIZE -> {
        buildString {
          append("### 📌 Concise Summary: ${chat.title}\n")
          append("**Subject:** ${chat.subjectName} • **Source:** ${chat.aiPlatform}\n\n")
          if (concepts.isNotEmpty()) {
            concepts.take(4).forEach { c ->
              append("- **${c.conceptName}:** ${c.definition.take(180)}\n")
            }
            val traps = concepts.mapNotNull { it.misconceptionsAndCorrections.takeIf { m -> m.isNotBlank() } }.firstOrNull()
            if (!traps.isNullOrBlank()) {
              append("\n**⚠️ Key Exam Pitfall:** $traps")
            }
          } else {
            val turns = extractRelevantParagraphs(chat.rawConversation, emptyList(), maxParagraphs = 3)
            turns.forEach { append("- $it\n") }
          }
        }.trim()
      }
      FollowUpType.ANOTHER_EXAMPLE -> {
        // Only return from linked chat if a genuinely DIFFERENT second problem/example exists
        val altConceptProblem = concepts.drop(1)
          .firstOrNull { c ->
            c.problemsSolved.isNotBlank() &&
              !previousModelText.contains(c.problemsSolved.take(40), ignoreCase = true)
          }
        val altSnippet = extractRelevantParagraphs(chat.rawConversation, listOf("example", "scenario", "instance", "case"), maxParagraphs = 3)
          .firstOrNull { line -> !previousModelText.contains(line.take(40), ignoreCase = true) }

        if (altConceptProblem != null) {
          buildString {
            append("### 💡 Another Example From Linked Chat: ${chat.title}\n\n")
            append("- **${altConceptProblem.conceptName}:** ${altConceptProblem.problemsSolved}\n")
            if (altConceptProblem.technicalDetails.isNotBlank()) {
              append("- **Walkthrough:** ${altConceptProblem.technicalDetails}\n")
            }
          }.trim()
        } else if (altSnippet != null) {
          buildString {
            append("### 💡 Another Example From Linked Chat: ${chat.title}\n\n")
            append("- $altSnippet\n")
          }.trim()
        } else {
          null // Fall back to Gemini / Local AI to synthesize a brand-new alternative example
        }
      }
      FollowUpType.EXPLAIN_EXAMPLE -> {
        val sections = mutableListOf<String>()
        concepts.forEach { c ->
          if (c.problemsSolved.isNotBlank()) sections.add("**Problem / Example (${c.conceptName}):** ${c.problemsSolved}")
          if (c.technicalDetails.isNotBlank()) sections.add("**Step-by-Step Mechanics:** ${c.technicalDetails}")
          if (c.misconceptionsAndCorrections.isNotBlank()) sections.add("**Key Pitfall to Watch:** ${c.misconceptionsAndCorrections}")
        }
        val firstCode = codeBlocks.firstOrNull()
        if (sections.isEmpty() && firstCode == null) {
          null
        } else {
          buildString {
            append("### 🔎 Example Breakdown From Linked Chat: ${chat.title}\n\n")
            if (firstCode != null) {
              append("```\n$firstCode\n```\n\n")
            }
            sections.distinct().take(4).forEach { s ->
              append("- $s\n")
            }
          }.trim()
        }
      }
    }

    if (bodyText.isNullOrBlank()) return null

    return ChatResponse(
      text = bodyText,
      modelName = "Linked Chat (${chat.subjectName})",
      groundingSources = sourceBadge,
      relatedTopics = topics
    )
  }

  private enum class QuestionFocus {
    COMPLEXITY,
    PITFALL_OR_WHY_FAILS,
    CODE_OR_FORMULA,
    STEPS_OR_HOW,
    PROBLEM_OR_EXAMPLE,
    DEFINITION_OR_GENERAL
  }

  private fun detectQuestionFocus(lowerQuery: String): QuestionFocus {
    return when {
      lowerQuery.contains("complex") || lowerQuery.contains("big-o") || lowerQuery.contains("big o") ||
        lowerQuery.contains("time") || lowerQuery.contains("space") || lowerQuery.contains("runtime") ||
        lowerQuery.contains("o(") -> QuestionFocus.COMPLEXITY

      lowerQuery.contains("fail") || lowerQuery.contains("trap") || lowerQuery.contains("wrong") ||
        lowerQuery.contains("mistake") || lowerQuery.contains("misconception") || lowerQuery.contains("negative") ||
        lowerQuery.contains("limitation") || lowerQuery.contains("cannot") || lowerQuery.contains("pitfall") -> QuestionFocus.PITFALL_OR_WHY_FAILS

      lowerQuery.contains("code") || lowerQuery.contains("implement") || lowerQuery.contains("snippet") ||
        lowerQuery.contains("syntax") || lowerQuery.contains("formula") || lowerQuery.contains("equation") -> QuestionFocus.CODE_OR_FORMULA

      lowerQuery.contains("how") || lowerQuery.contains("step") || lowerQuery.contains("work") ||
        lowerQuery.contains("mechanic") || lowerQuery.contains("process") || lowerQuery.contains("algorithm") -> QuestionFocus.STEPS_OR_HOW

      lowerQuery.contains("example") || lowerQuery.contains("problem") || lowerQuery.contains("use case") ||
        lowerQuery.contains("scenario") || lowerQuery.contains("apply") -> QuestionFocus.PROBLEM_OR_EXAMPLE

      else -> QuestionFocus.DEFINITION_OR_GENERAL
    }
  }

  private fun buildDirectChatAnswer(
    rawQuery: String,
    queryTokens: List<String>,
    chat: ImportedChat,
    topConcept: ExtractedConcept?,
    allChatConcepts: List<ExtractedConcept>
  ): ChatResponse {
    val focus = detectQuestionFocus(rawQuery.lowercase(Locale.ROOT))
    val codeBlocks = extractCodeBlocks(chat.rawConversation, allChatConcepts)
    val focusedExcerpts = extractRelevantParagraphs(chat.rawConversation, queryTokens, maxParagraphs = 3)

    // Collect direct answer points tailored to what was specifically asked
    val directPoints = mutableListOf<String>()

    fun addPoint(label: String, value: String?) {
      val clean = value?.trim().orEmpty()
      if (clean.isNotBlank() && directPoints.none { it.contains(clean.take(40), ignoreCase = true) }) {
        directPoints.add(if (label.isNotBlank()) "**$label:** $clean" else clean)
      }
    }

    when (focus) {
      QuestionFocus.COMPLEXITY -> {
        val complexityExcerpts = extractRelevantParagraphs(
          chat.rawConversation,
          (queryTokens + listOf("complexity", "o(", "time", "space", "heap")).distinct(),
          maxParagraphs = 3
        )
        complexityExcerpts.forEach { addPoint("", it) }
        allChatConcepts.forEach { c ->
          if (c.keyFacts.contains("O(", ignoreCase = true) || c.keyFacts.contains("complexity", ignoreCase = true)) {
            addPoint("Key Fact", c.keyFacts)
          }
          if (c.technicalDetails.contains("O(", ignoreCase = true) || c.technicalDetails.contains("complexity", ignoreCase = true)) {
            addPoint("Details", c.technicalDetails)
          }
        }
      }
      QuestionFocus.PITFALL_OR_WHY_FAILS -> {
        allChatConcepts.forEach { c ->
          if (c.misconceptionsAndCorrections.isNotBlank()) addPoint("Limitation / Trap", c.misconceptionsAndCorrections)
        }
        focusedExcerpts.forEach { addPoint("", it) }
      }
      QuestionFocus.CODE_OR_FORMULA -> {
        topConcept?.let {
          if (it.formulaOrCode.isNotBlank()) {
            val wrapped = if (it.formulaOrCode.contains("```")) it.formulaOrCode else "```\n${it.formulaOrCode.trim()}\n```"
            directPoints.add(wrapped)
          }
          if (it.technicalDetails.isNotBlank()) addPoint("How it works", it.technicalDetails)
        }
        if (directPoints.none { it.contains("```") } && codeBlocks.isNotEmpty()) {
          directPoints.add(0, "```\n${codeBlocks.first()}\n```")
        }
      }
      QuestionFocus.STEPS_OR_HOW -> {
        topConcept?.let {
          if (it.definition.isNotBlank()) addPoint("Core Idea", it.definition)
          if (it.technicalDetails.isNotBlank()) addPoint("Step-by-Step Mechanics", it.technicalDetails)
        }
        focusedExcerpts.forEach { addPoint("", it) }
      }
      QuestionFocus.PROBLEM_OR_EXAMPLE -> {
        topConcept?.let {
          if (it.problemsSolved.isNotBlank()) addPoint("Example / Application", it.problemsSolved)
          if (it.technicalDetails.isNotBlank()) addPoint("Walkthrough", it.technicalDetails)
        }
        focusedExcerpts.forEach { addPoint("", it) }
      }
      QuestionFocus.DEFINITION_OR_GENERAL -> {
        // Put the most targeted excerpt or matching concept definition first
        if (topConcept != null) {
          addPoint(topConcept.conceptName, topConcept.definition)
          if (topConcept.keyFacts.isNotBlank()) addPoint("Key Fact", topConcept.keyFacts)
        }
        focusedExcerpts.forEach { addPoint("", it) }
        if (directPoints.size <= 1 && topConcept?.technicalDetails?.isNotBlank() == true) {
          addPoint("Details", topConcept.technicalDetails)
        }
      }
    }

    // Fallback safety if specific focus matched nothing directly
    if (directPoints.isEmpty()) {
      topConcept?.let {
        if (it.definition.isNotBlank()) addPoint(it.conceptName, it.definition)
        if (it.technicalDetails.isNotBlank()) addPoint("Details", it.technicalDetails)
      }
      focusedExcerpts.forEach { addPoint("", it) }
    }

    val formatted = buildString {
      append("### 🔗 Answer From Linked Chat: ${chat.title}\n")
      append("**Subject:** ${chat.subjectName} • **Source:** ${chat.aiPlatform}\n\n")

      directPoints.take(5).forEach { pt ->
        if (pt.startsWith("```")) {
          append("$pt\n\n")
        } else {
          append("- $pt\n")
        }
      }

      if (focus != QuestionFocus.CODE_OR_FORMULA &&
        directPoints.none { it.contains("```") } &&
        (rawQuery.lowercase(Locale.ROOT).contains("algorithm") || rawQuery.lowercase(Locale.ROOT).contains("pca") || rawQuery.lowercase(Locale.ROOT).contains("svd"))
      ) {
        val codeToInclude = topConcept?.formulaOrCode?.takeIf { it.isNotBlank() } ?: codeBlocks.firstOrNull()
        if (!codeToInclude.isNullOrBlank()) {
          val wrapped = if (codeToInclude.contains("```")) codeToInclude else "```\n$codeToInclude\n```"
          append("\n**Formula / Code:**\n$wrapped\n")
        }
      }
    }.trim()

    val related = extractLinkBasedMiniTopics(chat, allChatConcepts, maxCount = 5)

    return ChatResponse(
      text = formatted,
      modelName = "Linked Chat (${chat.subjectName})",
      groundingSources = listOf(
        GroundingSource(
          title = "🔗 ${chat.subjectName}: ${chat.title}",
          url = chat.sourceUrl
        )
      ),
      relatedTopics = related
    )
  }

  /**
   * Builds crisp, concise mini-chip topics exclusively from a linked ImportedChat
   * and its extracted concepts/headings/tags/notes. Never injects generic unrelated topics.
   */
  fun extractLinkBasedMiniTopics(
    chat: ImportedChat,
    chatConcepts: List<ExtractedConcept>,
    maxCount: Int = 5
  ): List<String> {
    val candidates = mutableListOf<String>()

    fun addCandidate(raw: String?) {
      if (raw.isNullOrBlank()) return
      val cleaned = raw
        .replace(MINI_TOPIC_CLEAN_REGEX, "")
        .replace(WS_REGEX, " ")
        .trim()
        .trimEnd(':', '.', '-', ';')
      val low = cleaned.lowercase(Locale.ROOT)
      val isBoilerplate = low in setOf(
        "user", "assistant", "system", "chatgpt", "claude", "gemini", "deepseek",
        "key concept", "formula", "code", "example", "summary", "general", "notes",
        "http", "https", "source", "overview", "introduction", "conclusion"
      ) || low.startsWith("http://") || low.startsWith("https://")
      if (!isBoilerplate && cleaned.length in 3..38 && candidates.none { it.equals(cleaned, ignoreCase = true) }) {
        candidates.add(cleaned)
      }
    }

    // 1. Extracted concepts (sorted by importance)
    chatConcepts.sortedByDescending { it.importanceScore }.forEach { c ->
      addCandidate(c.conceptName)
    }

    // 2. Subtopics & main topics from those concepts
    chatConcepts.forEach { c ->
      addCandidate(c.subtopic)
      addCandidate(c.topic)
      if (c.relatedConceptsJson.isNotBlank() && c.relatedConceptsJson != "[]") {
        try {
          val arr = JSONArray(c.relatedConceptsJson)
          for (i in 0 until arr.length()) {
            addCandidate(arr.optString(i))
          }
        } catch (_: Exception) {
          c.relatedConceptsJson.split(",", "|", ";").forEach { addCandidate(it) }
        }
      }
    }

    // 3. Tags & notes attached to the linked chat
    chat.tags.split(",", ";", "•", "|").forEach { addCandidate(it) }
    chat.notes.split(",", ";", "•", "|").forEach { addCandidate(it) }

    // 4. Headings & bold key phrases from the linked conversation text
    val strippedConv = chat.rawConversation.replace(CODE_FENCE_STRIP_REGEX, " ")
    for (line in strippedConv.lineSequence()) {
      val t = line.trim()
      if (t.startsWith("##") || t.startsWith("###")) {
        addCandidate(t.trimStart('#').trim())
      } else {
        val boldLabel = BOLD_LABEL_REGEX.find(t)
        if (boldLabel != null) {
          addCandidate(boldLabel.groupValues[1])
        }
      }
      if (candidates.size >= maxCount * 2) break
    }

    // 5. Fallback to clauses of the linked chat's own title (still 100% from the link)
    if (candidates.isEmpty() && chat.title.isNotBlank()) {
      chat.title.split(":", "|", "—", "-", "&", ",")
        .map { it.trim() }
        .forEach { addCandidate(it) }
      if (candidates.isEmpty()) {
        addCandidate(chat.title.take(36))
      }
    }

    return candidates.take(maxCount)
  }

  /**
   * Resolves mini-text topic pills across the user's link-containing chats
   * (scoped to `selectedSubject` if selected).
   * Returns emptyList() when no linked chats exist in scope.
   */
  fun getMiniTopicsForLinkedScope(
    queryOrText: String,
    groundingUrls: List<String>,
    importedChats: List<ImportedChat>,
    extractedConcepts: List<ExtractedConcept>,
    selectedSubject: Subject? = null,
    maxCount: Int = 5
  ): List<String> {
    val linkedChats = importedChats.filter { it.sourceUrl.isNotBlank() }
    if (linkedChats.isEmpty()) return emptyList()

    val scopedChats = if (selectedSubject != null) {
      linkedChats.filter {
        it.subjectId == selectedSubject.id ||
          it.subjectName.equals(selectedSubject.name, ignoreCase = true)
      }
    } else {
      linkedChats
    }
    if (scopedChats.isEmpty()) return emptyList()

    val conceptsByChat = extractedConcepts.groupBy { it.sourceChatId }

    // 1. Direct grounding URL match to a specific linked chat
    val cleanUrls = groundingUrls.map { it.trim() }.filter { it.isNotBlank() }
    if (cleanUrls.isNotEmpty()) {
      val exactChat = scopedChats.firstOrNull { c ->
        cleanUrls.any { u -> u.equals(c.sourceUrl.trim(), ignoreCase = true) }
      }
      if (exactChat != null) {
        val exactTopics = extractLinkBasedMiniTopics(
          chat = exactChat,
          chatConcepts = conceptsByChat[exactChat.id].orEmpty(),
          maxCount = maxCount
        )
        if (exactTopics.isNotEmpty()) return exactTopics
      }
    }

    // 2. Score scoped linked chats against query/text if provided
    val tokens = tokenize(queryOrText)
    val orderedChats = if (tokens.isNotEmpty()) {
      scopedChats.sortedByDescending { chat ->
        val cList = conceptsByChat[chat.id].orEmpty()
        val hay = (
          chat.title + " " + chat.tags + " " + chat.notes + " " +
            cList.joinToString(" ") { "${it.conceptName} ${it.topic} ${it.subtopic}" } + " " +
            chat.rawConversation.take(1500)
          ).lowercase(Locale.ROOT)
        tokens.count { hay.contains(it) }
      }
    } else {
      scopedChats.sortedByDescending { it.importedAt }
    }

    val aggregated = mutableListOf<String>()
    for (chat in orderedChats) {
      val list = extractLinkBasedMiniTopics(
        chat = chat,
        chatConcepts = conceptsByChat[chat.id].orEmpty(),
        maxCount = maxCount
      )
      for (t in list) {
        if (aggregated.none { it.equals(t, ignoreCase = true) }) {
          aggregated.add(t)
        }
        if (aggregated.size >= maxCount) return aggregated
      }
    }
    return aggregated.take(maxCount)
  }

  /**
   * Builds top quick-prompt chips directly from the user's provided chat links
   * (scoped to `selectedSubject` when active).
   */
  fun buildQuickPromptsFromLinkedChats(
    importedChats: List<ImportedChat>,
    extractedConcepts: List<ExtractedConcept>,
    selectedSubject: Subject? = null,
    maxCount: Int = 6
  ): List<LinkPromptChip> {
    val linkedChats = importedChats.filter { it.sourceUrl.isNotBlank() }
    if (linkedChats.isEmpty()) return emptyList()

    val scopedChats = if (selectedSubject != null) {
      linkedChats.filter {
        it.subjectId == selectedSubject.id ||
          it.subjectName.equals(selectedSubject.name, ignoreCase = true)
      }
    } else {
      linkedChats
    }.sortedByDescending { it.importedAt }

    if (scopedChats.isEmpty()) return emptyList()

    val conceptsByChat = extractedConcepts.groupBy { it.sourceChatId }
    val result = mutableListOf<LinkPromptChip>()
    val seenTopics = mutableSetOf<String>()

    // Round-robin pick across linked chats so every provided link gets represented
    val perChatTopics = scopedChats.map { chat ->
      chat to extractLinkBasedMiniTopics(chat, conceptsByChat[chat.id].orEmpty(), maxCount = 4)
    }

    var idx = 0
    while (result.size < maxCount && idx < 4) {
      var addedAny = false
      for ((chat, topics) in perChatTopics) {
        val topic = topics.getOrNull(idx) ?: continue
        val key = topic.lowercase(Locale.ROOT)
        if (seenTopics.add(key)) {
          val shortLabel = if (topic.length > 26) "${topic.take(24)}…" else topic
          result.add(
            LinkPromptChip(
              label = "🔗 $shortLabel",
              prompt = "From my linked chat \"${chat.title}\", explain $topic.",
              sourceUrl = chat.sourceUrl,
              chatTitle = chat.title
            )
          )
          addedAny = true
          if (result.size >= maxCount) break
        }
      }
      if (!addedAny) break
      idx++
    }

    return result
  }

  private fun extractCodeBlocks(rawText: String, concepts: List<ExtractedConcept>): List<String> {
    val blocks = mutableListOf<String>()
    CODE_BLOCK_EXTRACT_REGEX.findAll(rawText).forEach { match ->
      val code = match.groupValues.getOrNull(1)?.trim().orEmpty()
      if (code.isNotBlank()) blocks.add(code)
    }
    concepts.forEach { c ->
      if (c.formulaOrCode.isNotBlank() && blocks.none { it.contains(c.formulaOrCode) }) {
        blocks.add(c.formulaOrCode.trim())
      }
    }
    return blocks
  }

  private fun extractRelevantParagraphs(
    rawConversation: String,
    queryTokens: List<String>,
    maxParagraphs: Int = 3
  ): List<String> {
    val stripped = rawConversation.replace(CODE_FENCE_STRIP_REGEX, " ")
    val rawUnits = mutableListOf<String>()
    stripped.split(PARAGRAPH_SPLIT_REGEX).forEach { block ->
      val trimmedBlock = block.trim()
      if (trimmedBlock.isBlank()) return@forEach
      val lines = trimmedBlock.lines().map { it.trim() }.filter { it.length > 20 && !it.startsWith("User:", ignoreCase = true) }
      if (lines.size > 1) {
        rawUnits.addAll(lines.map { it.removePrefix("Assistant:").trim() })
      } else {
        val cleanBlock = trimmedBlock.removePrefix("Assistant:").trim()
        if (cleanBlock.length > 20 && !cleanBlock.startsWith("User:", ignoreCase = true)) {
          rawUnits.add(cleanBlock)
        }
      }
    }

    val units = rawUnits
      .map { it.replace(BULLET_PREFIX_REGEX, "").trim() }
      .filter { it.length > 18 && !it.startsWith("User:", ignoreCase = true) }
      .distinct()

    if (queryTokens.isEmpty()) {
      return units.take(maxParagraphs).map { it.take(360) }
    }

    return units.map { block ->
      val low = block.lowercase(Locale.ROOT)
      val score = queryTokens.count { low.contains(it) }
      block to score
    }
      .filter { it.second > 0 }
      .sortedByDescending { it.second }
      .take(maxParagraphs)
      .map { it.first.take(400) }
  }

  private fun tokenize(text: String): List<String> {
    return text.lowercase(Locale.ROOT)
      .split(TOKEN_SPLIT_REGEX)
      .map { it.trim() }
      .filter { it.length >= 3 && !stopWords.contains(it) }
      .distinct()
  }
}
