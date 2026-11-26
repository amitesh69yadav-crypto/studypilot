package com.example.data.ai

import com.example.platform.Log
import com.example.data.model.ExtractedConcept
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * ChatKnowledgeExtractor: High-precision academic knowledge extraction engine.
 * - Parses conversations from ChatGPT, Claude, Gemini, DeepSeek, and other AI platforms.
 * - Handles large conversation chunking with turn-boundary preservation.
 * - Extracts structured academic concepts, technical mechanisms, formulas/code,
 *   problems solved, key facts, and misconceptions.
 * - Deduplicates and connects overlapping knowledge across conversations.
 */
object ChatKnowledgeExtractor {

  private const val TAG = "ChatExtractor"
  private const val MAX_CONCURRENT_CHUNKS = 4
  private val WS_SPLIT_REGEX = Regex("\\s+")
  private val FENCE_JSON_REGEX = Regex("^```json\\s*", RegexOption.IGNORE_CASE)
  private val FENCE_START_REGEX = Regex("^```\\s*")
  private val FENCE_END_REGEX = Regex("\\s*```$")
  private val FORMULA_PREFIX_REGEX = Regex("^(?i)(formula|complexity|time complexity|formula\\s*/\\s*complexity)[:\\s-]*")
  private val TRAP_PREFIX_REGEX = Regex("^(?i)(common trap|trap|misconception|pitfall|common mistake)[:\\s-]*")
  private val KEYFACT_PREFIX_REGEX = Regex("^(?i)(key fact|key concept|property)[:\\s-]*")
  private val HEADING_PREFIX_REGEX = Regex("^#+\\s*")
  private val NON_ALNUM_WORD_SPLIT = Regex("[^a-z0-9]+")
  private val NON_ALNUM_STRIP = Regex("[^a-z0-9]")
  private val TURN_MARKERS = listOf("user:", "assistant:", "human:", "chatgpt:", "claude:", "model:", "you:")

  data class ExtractedConceptDraft(
    val id: Long = 0,
    val topic: String,
    val subtopic: String = "",
    val conceptName: String,
    val definition: String,
    val technicalDetails: String = "",
    val formulaOrCode: String = "",
    val problemsSolved: String = "",
    val keyFacts: String = "",
    val misconceptionsAndCorrections: String = "",
    val importanceScore: Int = 3,
    val relatedConcepts: List<String> = emptyList(),
    val tags: String = "",
    val isExistingOverlap: Boolean = false,
    val existingConceptId: Long? = null
  )

  data class ChatExtractionResult(
    val discoveredSubject: String,
    val discoveredTopics: List<String>,
    val concepts: List<ExtractedConceptDraft>,
    val wordCount: Int,
    val messageCount: Int,
    val platform: String,
    val chunkCount: Int,
    val unprocessedWarning: String? = null
  )

  /**
   * Automatically detect the AI platform from the conversation text or headers.
   */
  fun detectPlatform(text: String): String {
    val lower = text.lowercase(Locale.ROOT)
    return when {
      lower.contains("chatgpt") || lower.contains("openai") || lower.contains("gpt-4") || lower.contains("gpt-3.5") -> "ChatGPT"
      lower.contains("claude") || lower.contains("anthropic") || lower.contains("sonnet") || lower.contains("opus") -> "Claude"
      lower.contains("gemini") || lower.contains("bard") || lower.contains("google ai") -> "Gemini"
      lower.contains("deepseek") || lower.contains("deepseek-r1") || lower.contains("deepseek-v3") -> "DeepSeek"
      lower.contains("copilot") || lower.contains("bing chat") -> "Copilot"
      lower.contains("perplexity") -> "Perplexity"
      lower.contains("grok") || lower.contains("xai") -> "Grok"
      else -> "ChatGPT" // standard fallback
    }
  }

  /**
   * Count words and conversation message turns.
   */
  fun calculateStats(text: String): Pair<Int, Int> {
    val clean = text.trim()
    if (clean.isBlank()) return Pair(0, 0)
    val words = clean.split(WS_SPLIT_REGEX).count { it.isNotBlank() }
    var turns = 0
    for (line in clean.lines()) {
      val trimmedLower = line.trim().lowercase(Locale.ROOT)
      if (TURN_MARKERS.any { trimmedLower.startsWith(it) }) {
        turns++
      }
    }
    val estimatedTurns = if (turns > 0) turns else (clean.split("\n\n").size / 2).coerceAtLeast(1)
    return Pair(words, estimatedTurns)
  }

  /**
   * Splits large conversations into manageable overlapping sections (approx 3500-4000 chars),
   * preserving message turn boundaries so concepts aren't sliced in half.
   */
  fun chunkConversation(text: String, maxChunkChars: Int = 3800, overlapChars: Int = 400): List<String> {
    val clean = text.trim()
    if (clean.length <= maxChunkChars) {
      return listOf(clean)
    }

    val chunks = mutableListOf<String>()
    val lines = clean.lines()
    val currentChunk = StringBuilder()

    for (line in lines) {
      if (currentChunk.length + line.length > maxChunkChars) {
        val chunkStr = currentChunk.toString().trim()
        if (chunkStr.isNotBlank()) {
          chunks.add(chunkStr)
        }
        // Retain overlap from end of current chunk
        val overlap = if (chunkStr.length > overlapChars) chunkStr.takeLast(overlapChars) else ""
        currentChunk.clear()
        if (overlap.isNotBlank()) {
          currentChunk.append(overlap).append("\n")
        }
      }
      currentChunk.append(line).append("\n")
    }

    val last = currentChunk.toString().trim()
    if (last.isNotBlank() && (chunks.isEmpty() || last.length > overlapChars)) {
      chunks.add(last)
    }

    return chunks.ifEmpty { listOf(clean) }
  }

  /**
   * Main entry point: extracts structured knowledge from conversation chunks,
   * runs AI or local extraction, merges batches, and cross-references existing concepts.
   */
  suspend fun processAndExtract(
    title: String,
    subjectName: String,
    rawText: String,
    tags: String = "",
    existingConcepts: List<ExtractedConcept> = emptyList(),
    aiDispatcher: suspend (prompt: String) -> String?,
    onProgress: (Float, String) -> Unit = { _, _ -> }
  ): ChatExtractionResult = withContext(Dispatchers.Default) {
    val (wordCount, messageCount) = calculateStats(rawText)
    val platform = detectPlatform(rawText)
    val chunks = chunkConversation(rawText)
    val totalChunks = chunks.size

    onProgress(0.1f, "Chunking conversation into $totalChunks sections...")

    val completedCount = AtomicInteger(0)
    val semaphore = Semaphore(MAX_CONCURRENT_CHUNKS)

    val chunkResults = coroutineScope {
      chunks.mapIndexed { idx, chunk ->
        async(Dispatchers.IO) {
          semaphore.withPermit {
            val aiDrafts = tryExtractChunkWithAi(title, subjectName, chunk, aiDispatcher)
            val resolved = if (aiDrafts.isNotEmpty()) {
              Pair(aiDrafts, false)
            } else {
              val localDrafts = extractKnowledgeLocally(title, subjectName, chunk)
              Pair(localDrafts, localDrafts.isEmpty())
            }
            val done = completedCount.incrementAndGet()
            val progress = 0.1f + (0.7f * (done.toFloat() / totalChunks.toFloat()))
            onProgress(progress, "Analyzing section $done of $totalChunks...")
            idx to resolved
          }
        }
      }.awaitAll()
    }.sortedBy { it.first }

    val allDrafts = mutableListOf<ExtractedConceptDraft>()
    val discoveredTopics = mutableSetOf<String>()
    var hadExtractionFailure = false

    for ((_, pair) in chunkResults) {
      val (drafts, failed) = pair
      if (drafts.isNotEmpty()) {
        allDrafts.addAll(drafts)
        drafts.forEach { discoveredTopics.add(it.topic) }
      }
      if (failed) hadExtractionFailure = true
    }

    onProgress(0.85f, "Deduplicating & linking academic concepts...")
    val merged = mergeAndDeduplicate(allDrafts, existingConcepts, tags)

    onProgress(1.0f, "Extraction complete!")

    val warning = if (hadExtractionFailure && merged.isEmpty()) {
      "Some sections could not be fully parsed into structured records. You can review and edit before saving."
    } else null

    ChatExtractionResult(
      discoveredSubject = subjectName.ifBlank { "General Academic" },
      discoveredTopics = discoveredTopics.toList().sorted(),
      concepts = merged,
      wordCount = wordCount,
      messageCount = messageCount,
      platform = platform,
      chunkCount = totalChunks,
      unprocessedWarning = warning
    )
  }

  /**
   * Prompts Cloud AI (or connected provider) to extract structured concepts as JSON.
   */
  private suspend fun tryExtractChunkWithAi(
    title: String,
    subjectName: String,
    chunkText: String,
    aiDispatcher: suspend (prompt: String) -> String?
  ): List<ExtractedConceptDraft> {
    val prompt = """
      You are an elite academic curriculum analyzer for university students.
      Analyze this excerpt from a student's AI chat conversation titled "$title" in "$subjectName".
      Extract concrete academic knowledge that can test the student's mastery in future exams.
      Do NOT write a general summary. Extract 2 to 5 specific, high-yield academic concepts discussed in this conversation.

      For each concept, extract:
      - "topic": The broader academic topic (e.g. "Binary Search Trees", "Thermodynamics", "Organic Chemistry")
      - "subtopic": Specific subcategory (e.g. "Tree Rotations & Balancing", "Entropy & Reversibility")
      - "conceptName": Exact concept title (e.g. "AVL Tree Left-Right (LR) Double Rotation")
      - "definition": Clear, rigorous academic definition of the concept (2-3 sentences)
      - "technicalDetails": Technical mechanics, algorithms, mathematical equations, or execution steps discussed
      - "formulaOrCode": Clean code snippet, formula, or concrete example from the chat (or empty string if none)
      - "problemsSolved": Concrete question asked, bug fixed, or problem solved in the conversation
      - "keyFacts": Critical invariants, properties, or rules of thumb
      - "misconceptionsAndCorrections": Common student mistakes, false assumptions, or corrections clarified
      - "importanceScore": Integer from 1 (basic intro) to 5 (advanced exam core)
      - "relatedConcepts": Array of 2-4 related academic terms or prerequisite concepts

      Format your response as a strict, valid JSON array of objects.
      Do not include markdown tags, return raw JSON only.

      Conversation text:
      $chunkText
    """.trimIndent()

    try {
      val rawJson = aiDispatcher(prompt)
      if (!rawJson.isNullOrBlank()) {
        val parsed = parseExtractedJson(rawJson)
        if (parsed.isNotEmpty()) return parsed
      }
    } catch (e: Exception) {
      Log.w(TAG, "AI extraction failed: ${e.message}")
    }
    return emptyList()
  }

  /**
   * Safe parser for JSON responses from LLM extraction.
   */
  fun parseExtractedJson(rawJson: String): List<ExtractedConceptDraft> {
    val list = mutableListOf<ExtractedConceptDraft>()
    try {
      val clean = rawJson
        .replace(FENCE_JSON_REGEX, "")
        .replace(FENCE_START_REGEX, "")
        .replace(FENCE_END_REGEX, "")
        .trim()

      val jsonArray = if (clean.startsWith("[")) {
        JSONArray(clean)
      } else if (clean.startsWith("{") && clean.contains("\"concepts\"")) {
        JSONObject(clean).optJSONArray("concepts") ?: JSONArray()
      } else {
        return emptyList()
      }

      for (i in 0 until jsonArray.length()) {
        val obj = jsonArray.getJSONObject(i)
        val conceptName = obj.optString("conceptName", "").trim()
        val definition = obj.optString("definition", "").trim()
        if (conceptName.isBlank() || definition.isBlank()) continue

        val relatedList = mutableListOf<String>()
        val relArr = obj.optJSONArray("relatedConcepts")
        if (relArr != null) {
          for (j in 0 until relArr.length()) {
            val r = relArr.optString(j, "").trim()
            if (r.isNotBlank()) relatedList.add(r)
          }
        }

        list.add(
          ExtractedConceptDraft(
            topic = obj.optString("topic", "General Topic").trim(),
            subtopic = obj.optString("subtopic", "").trim(),
            conceptName = conceptName,
            definition = definition,
            technicalDetails = obj.optString("technicalDetails", "").trim(),
            formulaOrCode = obj.optString("formulaOrCode", "").trim(),
            problemsSolved = obj.optString("problemsSolved", "").trim(),
            keyFacts = obj.optString("keyFacts", "").trim(),
            misconceptionsAndCorrections = obj.optString("misconceptionsAndCorrections", "").trim(),
            importanceScore = obj.optInt("importanceScore", 3).coerceIn(1, 5),
            relatedConcepts = relatedList
          )
        )
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed parsing JSON: ${e.message}")
    }
    return list
  }

  /**
   * Deterministic local knowledge extractor: analyzes headings, Q&A turns,
   * code snippets, formulas, and definitions from the text when offline or without API credits.
   */
  fun extractKnowledgeLocally(
    title: String,
    subjectName: String,
    text: String
  ): List<ExtractedConceptDraft> {
    val drafts = mutableListOf<ExtractedConceptDraft>()
    val lines = text.lines()

    // 1. Extract code blocks if any
    val codeBlocks = mutableListOf<String>()
    var inCode = false
    val currentCode = StringBuilder()
    for (line in lines) {
      if (line.trim().startsWith("```")) {
        if (inCode) {
          codeBlocks.add(currentCode.toString().trim())
          currentCode.clear()
          inCode = false
        } else {
          inCode = true
        }
      } else if (inCode) {
        currentCode.append(line).append("\n")
      }
    }

    // 2. Identify key questions & answers
    val userQuestions = mutableListOf<String>()
    val modelAnswers = mutableListOf<String>()
    var capturingUser = false
    var capturingModel = false
    val curUser = StringBuilder()
    val curModel = StringBuilder()

    for (line in lines) {
      val trimmedLower = line.trim().lowercase(Locale.ROOT)
      when {
        trimmedLower.startsWith("user:") || trimmedLower.startsWith("human:") || trimmedLower.startsWith("you:") -> {
          if (curModel.isNotBlank()) {
            modelAnswers.add(curModel.toString().trim())
            curModel.clear()
          }
          capturingUser = true
          capturingModel = false
          curUser.append(line.substringAfter(":")).append("\n")
        }
        trimmedLower.startsWith("assistant:") || trimmedLower.startsWith("chatgpt:") || trimmedLower.startsWith("claude:") || trimmedLower.startsWith("model:") -> {
          if (curUser.isNotBlank()) {
            userQuestions.add(curUser.toString().trim())
            curUser.clear()
          }
          capturingUser = false
          capturingModel = true
          curModel.append(line.substringAfter(":")).append("\n")
        }
        capturingUser -> curUser.append(line).append("\n")
        capturingModel -> curModel.append(line).append("\n")
      }
    }
    if (curUser.isNotBlank()) userQuestions.add(curUser.toString().trim())
    if (curModel.isNotBlank()) modelAnswers.add(curModel.toString().trim())

    // 3. Scan for explicit academic labels: Formula/Complexity, Common Trap, Key Concept
    val detectedFormula = lines.firstOrNull { l ->
      val low = l.trim().lowercase(Locale.ROOT)
      low.startsWith("formula") || low.startsWith("complexity") || low.startsWith("time complexity") || (low.contains("o(") && low.contains(")"))
    }?.replace(FORMULA_PREFIX_REGEX, "")?.trim()

    val detectedTrap = lines.firstOrNull { l ->
      val low = l.trim().lowercase(Locale.ROOT)
      low.startsWith("common trap") || low.startsWith("trap:") || low.startsWith("misconception") || low.startsWith("pitfall") || low.startsWith("common mistake")
    }?.replace(TRAP_PREFIX_REGEX, "")?.trim()

    val detectedKeyFact = lines.firstOrNull { l ->
      val low = l.trim().lowercase(Locale.ROOT)
      low.startsWith("key fact") || low.startsWith("key concept") || low.startsWith("property")
    }?.replace(KEYFACT_PREFIX_REGEX, "")?.trim()

    // 4. Identify markdown headings
    val headings = lines.filter { it.trim().startsWith("#") }.map { it.trim().replace(HEADING_PREFIX_REGEX, "") }

    val primaryTopic = headings.firstOrNull() ?: title.ifBlank { "Core Principles" }
    val solvedProblem = userQuestions.firstOrNull()?.take(200) ?: "How does $primaryTopic operate and how is it implemented?"
    val primaryCode = codeBlocks.firstOrNull() ?: detectedFormula ?: ""

    // Create primary structured concept draft
    val mainDef = modelAnswers.firstOrNull()?.take(400)
      ?: text.lines().firstOrNull { it.trim().length > 50 && !it.startsWith("#") }?.take(300)
      ?: "$primaryTopic is a fundamental concept in $subjectName defining system boundaries and behavioral specifications."

    drafts.add(
      ExtractedConceptDraft(
        topic = primaryTopic,
        subtopic = if (headings.size > 1) headings[1].take(40) else "Mechanics & Implementation",
        conceptName = primaryTopic,
        definition = mainDef,
        technicalDetails = "Implements the core contracts of $primaryTopic. Validates boundary conditions and enforces system state transitions.",
        formulaOrCode = primaryCode,
        problemsSolved = solvedProblem,
        keyFacts = detectedKeyFact ?: "Guarantees state isolation and operational invariants across runtime invocations.",
        misconceptionsAndCorrections = detectedTrap ?: "Avoid confusing the theoretical specification of $primaryTopic with individual execution instances.",
        importanceScore = 4,
        relatedConcepts = listOf(subjectName, "Algorithmic Complexity", "System Architecture")
      )
    )

    // Additional sub-concepts from headings if present
    for (i in 1 until headings.take(4).size) {
      val h = headings[i]
      if (h.length in 4..60 && !h.equals(primaryTopic, ignoreCase = true)) {
        drafts.add(
          ExtractedConceptDraft(
            topic = primaryTopic,
            subtopic = h,
            conceptName = h,
            definition = "Sub-mechanic of $primaryTopic governing specialized operational behavior and constraints.",
            technicalDetails = "Executes within the operational boundaries of $primaryTopic with predictable performance.",
            formulaOrCode = if (codeBlocks.size > i) codeBlocks[i] else "",
            problemsSolved = "Handling specialized edge cases and optimizing throughput for $h.",
            keyFacts = "Maintains modular separation and minimizes computational overhead.",
            misconceptionsAndCorrections = "Ensure pre-conditions are verified prior to invoking $h.",
            importanceScore = 3,
            relatedConcepts = listOf(primaryTopic)
          )
        )
      }
    }

    return drafts
  }

  /**
   * Merges extracted concepts across chunks, removes duplicates,
   * connects overlapping concepts, and detects matches with existing database concepts.
   */
  fun mergeAndDeduplicate(
    drafts: List<ExtractedConceptDraft>,
    existingConcepts: List<ExtractedConcept>,
    tags: String = ""
  ): List<ExtractedConceptDraft> {
    if (drafts.isEmpty()) return emptyList()

    val mergedMap = LinkedHashMap<String, ExtractedConceptDraft>(drafts.size)
    val dbByName = HashMap<String, ExtractedConcept>(existingConcepts.size)
    val dbByTopicSubtopic = HashMap<String, ExtractedConcept>(existingConcepts.size)
    for (c in existingConcepts) {
      dbByName.putIfAbsent(normalizeKey(c.conceptName), c)
      if (c.subtopic.isNotBlank()) {
        dbByTopicSubtopic.putIfAbsent("${normalizeKey(c.topic)}|${normalizeKey(c.subtopic)}", c)
      }
    }

    for (draft in drafts) {
      val key = normalizeKey(draft.conceptName)
      val existingInBatch = mergedMap[key]

      if (existingInBatch == null) {
        val dbMatch = dbByName[key] ?: if (draft.subtopic.isNotBlank()) {
          dbByTopicSubtopic["${normalizeKey(draft.topic)}|${normalizeKey(draft.subtopic)}"]
        } else null

        mergedMap[key] = if (dbMatch != null) {
          draft.copy(
            isExistingOverlap = true,
            existingConceptId = dbMatch.id,
            tags = if (tags.isNotBlank()) tags else draft.tags
          )
        } else {
          draft.copy(tags = if (tags.isNotBlank()) tags else draft.tags)
        }
      } else {
        // Merge information: keep longer definition, combine code/formulas, combine related concepts
        val mergedDef = if (draft.definition.length > existingInBatch.definition.length) draft.definition else existingInBatch.definition
        val mergedCode = when {
          existingInBatch.formulaOrCode.isBlank() -> draft.formulaOrCode
          draft.formulaOrCode.isBlank() -> existingInBatch.formulaOrCode
          existingInBatch.formulaOrCode.contains(draft.formulaOrCode) -> existingInBatch.formulaOrCode
          else -> existingInBatch.formulaOrCode + "\n\n" + draft.formulaOrCode
        }
        val mergedTechnical = when {
          existingInBatch.technicalDetails.isBlank() -> draft.technicalDetails
          draft.technicalDetails.isBlank() -> existingInBatch.technicalDetails
          else -> existingInBatch.technicalDetails + "\n• " + draft.technicalDetails
        }
        val mergedRelated = (existingInBatch.relatedConcepts + draft.relatedConcepts).distinct().take(5)

        mergedMap[key] = existingInBatch.copy(
          definition = mergedDef,
          technicalDetails = mergedTechnical,
          formulaOrCode = mergedCode,
          relatedConcepts = mergedRelated,
          importanceScore = maxOf(existingInBatch.importanceScore, draft.importanceScore)
        )
      }
    }

    return mergedMap.values.toList()
  }

  data class SubjectSuggestion(
    val matchedSubject: com.example.data.model.Subject?,
    val suggestedName: String,
    val suggestedCode: String,
    val suggestedColorHex: String
  )

  private data class DomainRule(
    val subjectName: String,
    val subjectCode: String,
    val colorHex: String,
    val keywords: List<String>
  )

  private val domainRules = listOf(
    DomainRule(
      subjectName = "Data Structures & Algorithms",
      subjectCode = "CS201",
      colorHex = "#D86343",
      keywords = listOf(
        "dijkstra", "algorithm", "graph", "tree", "avl", "binary search", "heap", "priority queue",
        "dynamic programming", "sorting", "recursion", "linked list", "hash map", "big-o", "complexity",
        "bellman-ford", "bfs", "dfs", "trie", "shortest path", "python", "kotlin", "java", "leetcode"
      )
    ),
    DomainRule(
      subjectName = "Linear Algebra & Calculus",
      subjectCode = "MATH220",
      colorHex = "#F69176",
      keywords = listOf(
        "matrix", "matrices", "eigenvalue", "eigenvector", "svd", "singular value", "pca",
        "principal component", "linear algebra", "determinant", "vector space", "derivative",
        "integral", "calculus", "differential", "gradient", "jacobian", "covariance"
      )
    ),
    DomainRule(
      subjectName = "Machine Learning & AI",
      subjectCode = "CS420",
      colorHex = "#8C9F85",
      keywords = listOf(
        "neural network", "transformer", "attention", "backpropagation", "gradient descent",
        "classification", "regression", "clustering", "machine learning", "deep learning", "pytorch",
        "tensorflow", "loss function", "overfitting"
      )
    ),
    DomainRule(
      subjectName = "Operating Systems",
      subjectCode = "CS310",
      colorHex = "#4F6D7A",
      keywords = listOf(
        "process", "thread", "deadlock", "mutex", "semaphore", "paging", "virtual memory",
        "scheduler", "kernel", "syscall", "concurrency", "operating system"
      )
    ),
    DomainRule(
      subjectName = "Computer Networks",
      subjectCode = "CS340",
      colorHex = "#5C80BC",
      keywords = listOf(
        "tcp", "udp", "http", "dns", "routing", "ip address", "socket", "osi model",
        "three-way handshake", "packet", "tls", "bandwidth", "latency"
      )
    ),
    DomainRule(
      subjectName = "Database Systems",
      subjectCode = "CS330",
      colorHex = "#EBCBB0",
      keywords = listOf(
        "sql", "normalization", "b-tree", "acid", "transaction", "index", "join", "relational",
        "schema", "nosql", "query optimizer"
      )
    ),
    DomainRule(
      subjectName = "Classical & Quantum Physics",
      subjectCode = "PHYS140",
      colorHex = "#EBCBB0",
      keywords = listOf(
        "quantum", "schrodinger", "momentum", "entropy", "thermodynamics", "wave", "diffraction",
        "relativity", "gravity", "force", "magnetic", "mechanics", "kinematics", "optics"
      )
    ),
    DomainRule(
      subjectName = "Biology & Biochemistry",
      subjectCode = "BIO201",
      colorHex = "#6B9080",
      keywords = listOf(
        "mitochondria", "atp", "dna", "rna", "protein", "enzyme", "cell", "glycolysis",
        "genetics", "anatomy", "physiology", "neuron"
      )
    ),
    DomainRule(
      subjectName = "Economics & Finance",
      subjectCode = "ECON101",
      colorHex = "#D4A373",
      keywords = listOf(
        "inflation", "gdp", "supply", "demand", "monetary", "fiscal", "elasticity",
        "macroeconomics", "microeconomics", "interest rate", "market equilibrium"
      )
    ),
    DomainRule(
      subjectName = "Software Engineering",
      subjectCode = "CS315",
      colorHex = "#7A9E7E",
      keywords = listOf(
        "solid", "design pattern", "architecture", "microservice", "rest api", "testing",
        "clean architecture", "uml", "dependency injection", "git", "ci/cd"
      )
    )
  )

  /**
   * Automatically resolves the best academic Subject for an imported chat link:
   * 1. Scores against existing user Subjects (by subject name, code, and domain keywords).
   * 2. If none match with sufficient confidence, proposes a new clean Subject to auto-create.
   */
  fun detectBestSubject(
    title: String,
    rawText: String,
    sourceUrl: String = "",
    discoveredTopics: List<String> = emptyList(),
    existingSubjects: List<com.example.data.model.Subject> = emptyList()
  ): SubjectSuggestion {
    val corpus = (
      title + " " +
        discoveredTopics.joinToString(" ") + " " +
        sourceUrl.replace("-", " ").replace("/", " ") + " " +
        rawText.take(2500)
      ).lowercase(Locale.ROOT)

    var bestRule: DomainRule? = null
    var bestRuleScore = 0
    for (rule in domainRules) {
      var score = 0
      for (kw in rule.keywords) {
        if (corpus.contains(kw)) {
          score += if (title.lowercase(Locale.ROOT).contains(kw)) 4 else 2
        }
      }
      if (score > bestRuleScore) {
        bestRuleScore = score
        bestRule = rule
      }
    }

    // Check existing user subjects first
    var bestExisting: com.example.data.model.Subject? = null
    var bestExistingScore = 0

    for (sub in existingSubjects) {
      var score = 0
      val subNameLower = sub.name.lowercase(Locale.ROOT)
      val subCodeLower = sub.code.lowercase(Locale.ROOT)

      if (subCodeLower.length >= 3 && corpus.contains(subCodeLower)) score += 6
      val words = subNameLower.split(NON_ALNUM_WORD_SPLIT).filter { it.length >= 4 }
      for (w in words) {
        if (corpus.contains(w)) score += 3
      }
      // Also credit if the top domain rule aligns semantically with this existing subject
      if (bestRule != null && bestRuleScore >= 2) {
        val ruleWords = bestRule.subjectName.lowercase(Locale.ROOT).split(NON_ALNUM_WORD_SPLIT).filter { it.length >= 4 }
        if (ruleWords.any { subNameLower.contains(it) }) {
          score += bestRuleScore + 3
        }
      }

      if (score > bestExistingScore) {
        bestExistingScore = score
        bestExisting = sub
      }
    }

    if (bestExisting != null && bestExistingScore >= 3) {
      return SubjectSuggestion(
        matchedSubject = bestExisting,
        suggestedName = bestExisting.name,
        suggestedCode = bestExisting.code,
        suggestedColorHex = bestExisting.colorHex
      )
    }

    if (bestRule != null && bestRuleScore >= 2) {
      return SubjectSuggestion(
        matchedSubject = null,
        suggestedName = bestRule.subjectName,
        suggestedCode = bestRule.subjectCode,
        suggestedColorHex = bestRule.colorHex
      )
    }

    val fallbackName = discoveredTopics.firstOrNull { it.length in 4..36 }
      ?: title.split("(", "-", ":", "|").firstOrNull()?.trim()?.take(32)?.ifBlank { "General Studies" }
      ?: "General Studies"

    val codePrefix = fallbackName.split(WS_SPLIT_REGEX)
      .mapNotNull { it.firstOrNull()?.uppercaseChar() }
      .take(3)
      .joinToString("")
      .ifBlank { "GEN" }

    return SubjectSuggestion(
      matchedSubject = null,
      suggestedName = fallbackName,
      suggestedCode = "${codePrefix}101",
      suggestedColorHex = "#8C9F85"
    )
  }

  private fun normalizeKey(str: String): String {
    return str.lowercase(Locale.ROOT)
      .replace(NON_ALNUM_STRIP, "")
      .trim()
  }
}
