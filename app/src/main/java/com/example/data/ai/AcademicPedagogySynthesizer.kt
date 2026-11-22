package com.example.data.ai

import java.util.Locale

/**
 * AcademicPedagogySynthesizer: Generates structured, high-yield academic
 * explanations featuring detailed topic breakdowns, previous topic context,
 * concrete practical examples, and connecting topics.
 */
object AcademicPedagogySynthesizer {

  enum class DetectedDomain(val displayName: String, val icon: String) {
    COMPUTER_SCIENCE("Computer Science & Software", "💻"),
    MATHEMATICS("Pure & Applied Mathematics", "📐"),
    PHYSICS("Physics & Engineering", "⚛️"),
    CHEMISTRY("Chemistry & Materials", "🧪"),
    BIOLOGY_MEDICINE("Biology & Medicine", "🧬"),
    ECONOMICS_COMMERCE("Economics & Finance", "📈"),
    LAW_POLITICS("Law & Governance", "⚖️"),
    PHILOSOPHY_HUMANITIES("Philosophy & Humanities", "🏛️"),
    GENERAL_SCIENCE("General Science & Inquiry", "🔍")
  }

  fun detectDomain(query: String): DetectedDomain {
    val lower = query.lowercase(Locale.ROOT)
    return when {
      lower.contains("java") || lower.contains("python") || lower.contains("kotlin") ||
        lower.contains("c++") || lower.contains("sql") || lower.contains("code") ||
        lower.contains("algorithm") || lower.contains("data structure") || lower.contains("compiler") ||
        lower.contains("operating system") || lower.contains("network") || lower.contains("thread") ||
        lower.contains("pointer") || lower.contains("memory") || lower.contains("database") ||
        lower.contains("git") || lower.contains("server") || lower.contains("api") ||
        lower.contains("binary") || lower.contains("tree") || lower.contains("graph") ||
        lower.contains("ai") || lower.contains("llm") || lower.contains("machine learning") -> DetectedDomain.COMPUTER_SCIENCE

      lower.contains("derivative") || lower.contains("integral") || lower.contains("calculus") ||
        lower.contains("matrix") || lower.contains("eigen") || lower.contains("vector") ||
        lower.contains("algebra") || lower.contains("theorem") || lower.contains("proof") ||
        lower.contains("probability") || lower.contains("geometry") || lower.contains("statistic") ||
        lower.contains("prime") || lower.contains("equation") -> DetectedDomain.MATHEMATICS

      lower.contains("gravity") || lower.contains("force") || lower.contains("velocity") ||
        lower.contains("quantum") || lower.contains("relativity") || lower.contains("entropy") ||
        lower.contains("thermodynamic") || lower.contains("light") || lower.contains("energy") ||
        lower.contains("particle") || lower.contains("wave") || lower.contains("laser") ||
        lower.contains("radiation") || lower.contains("electromagnet") -> DetectedDomain.PHYSICS

      lower.contains("atom") || lower.contains("molecule") || lower.contains("acid") ||
        lower.contains("base") || lower.contains("ph") || lower.contains("bond") ||
        lower.contains("periodic") || lower.contains("electron") || lower.contains("reaction") ||
        lower.contains("organic") || lower.contains("catalyst") || lower.contains("gas") -> DetectedDomain.CHEMISTRY

      lower.contains("cell") || lower.contains("dna") || lower.contains("rna") ||
        lower.contains("gene") || lower.contains("protein") || lower.contains("neuron") ||
        lower.contains("brain") || lower.contains("heart") || lower.contains("blood") ||
        lower.contains("disease") || lower.contains("immune") || lower.contains("virus") ||
        lower.contains("bacteria") || lower.contains("photosynthesis") || lower.contains("organ") -> DetectedDomain.BIOLOGY_MEDICINE

      lower.contains("inflation") || lower.contains("market") || lower.contains("price") ||
        lower.contains("demand") || lower.contains("supply") || lower.contains("bank") ||
        lower.contains("money") || lower.contains("trade") || lower.contains("gdp") ||
        lower.contains("fiscal") || lower.contains("monetary") || lower.contains("interest rate") ||
        lower.contains("stock") || lower.contains("capital") -> DetectedDomain.ECONOMICS_COMMERCE

      lower.contains("law") || lower.contains("court") || lower.contains("contract") ||
        lower.contains("constitution") || lower.contains("right") || lower.contains("justice") ||
        lower.contains("tort") || lower.contains("crime") || lower.contains("jurisdiction") ||
        lower.contains("legal") || lower.contains("treaty") -> DetectedDomain.LAW_POLITICS

      lower.contains("ethics") || lower.contains("philosophy") || lower.contains("knowledge") ||
        lower.contains("logic") || lower.contains("morality") || lower.contains("history") ||
        lower.contains("culture") || lower.contains("language") || lower.contains("linguistic") ||
        lower.contains("existential") -> DetectedDomain.PHILOSOPHY_HUMANITIES

      else -> DetectedDomain.GENERAL_SCIENCE
    }
  }

  fun getRelatedTopics(query: String): List<String> {
    val lower = query.lowercase(Locale.ROOT)
    return when {
      lower.contains("class") -> listOf("Objects", "Methods", "Attributes", "Constructors", "Inheritance", "Encapsulation")
      lower.contains("inheritance") -> listOf("Polymorphism", "Superclass & Subclass", "Method Overriding", "Abstract Classes", "Interfaces")
      lower.contains("polymorphism") -> listOf("Method Overriding", "Method Overloading", "Virtual Method Table", "Interfaces", "Dynamic Dispatch")
      lower.contains("encapsulation") -> listOf("Access Modifiers", "Getters & Setters", "Information Hiding", "Data Integrity", "Packages")
      lower.contains("constructor") -> listOf("Default Constructors", "Parameterized Constructors", "Constructor Overloading", "Super Keyword", "This Keyword")
      lower.contains("object") -> listOf("Classes", "Instance Variables", "Heap Memory", "References", "Instantiation")
      lower.contains("method") || lower.contains("function") -> listOf("Parameters", "Return Values", "Recursion", "Scope", "Signatures")
      lower.contains("memory") || lower.contains("heap") || lower.contains("stack") -> listOf("Garbage Collection", "Stack Frames", "Pointers & References", "Memory Leaks", "Heap Allocation")
      lower.contains("thread") || lower.contains("concurrency") -> listOf("Synchronization", "Deadlocks", "Race Conditions", "Volatile & Atomic", "Thread Pools")
      lower.contains("recursion") -> listOf("Base Case", "Call Stack", "Stack Overflow", "Tail Recursion", "Memoization")
      lower.contains("algorithm") || lower.contains("sort") -> listOf("Time Complexity (Big-O)", "QuickSort", "MergeSort", "Binary Search", "Space Complexity")
      lower.contains("tree") || lower.contains("graph") -> listOf("Binary Search Trees", "DFS (Depth-First)", "BFS (Breadth-First)", "Tree Traversals", "AVL Balance")
      lower.contains("database") || lower.contains("sql") -> listOf("Primary & Foreign Keys", "JOINS", "Normalization", "Indexes", "Transactions (ACID)")
      lower.contains("calculus") || lower.contains("derivative") -> listOf("Chain Rule", "Integration", "Limits", "Partial Derivatives", "Optimization")
      lower.contains("matrix") || lower.contains("linear algebra") -> listOf("Eigenvalues & Eigenvectors", "Determinants", "Orthogonality", "Rank & Nullity", "Matrix Inversion")
      lower.contains("quantum") -> listOf("Wave-Particle Duality", "Schrödinger Equation", "Quantum Entanglement", "Superposition", "Uncertainty Principle")
      lower.contains("gravity") || lower.contains("force") || lower.contains("physics") -> listOf("Newton's Laws of Motion", "Conservation of Momentum", "Kinetic & Potential Energy", "Frictional Forces", "Work-Energy Theorem")
      lower.contains("chemistry") || lower.contains("reaction") || lower.contains("atom") -> listOf("Covalent & Ionic Bonds", "Reaction Kinetics", "Equilibrium", "Catalysts", "Thermodynamics")
      lower.contains("cell") || lower.contains("dna") || lower.contains("biology") -> listOf("RNA & Transcription", "Translation & Protein Synthesis", "Mitosis & Meiosis", "Cell Membrane", "Enzymes")
      lower.contains("inflation") || lower.contains("economy") || lower.contains("market") -> listOf("Interest Rates", "Monetary Policy", "Supply & Demand", "Fiscal Policy", "GDP & Economic Growth")
      lower.contains("network") || lower.contains("protocol") -> listOf("TCP / IP Protocol", "HTTP & HTTPS", "OSI Model Layers", "Sockets & Ports", "DNS Resolution")
      lower.contains("operating system") || lower.contains("os") -> listOf("Process Scheduling", "Virtual Memory & Paging", "File Systems", "Inter-Process Communication", "System Calls")
      else -> listOf("Core Mechanisms", "Key Properties", "Practical Applications", "Underlying Architecture", "Edge Cases")
    }
  }

  fun extractAndCleanRelatedTopics(rawContent: String, fallbackQuery: String = ""): Pair<String, List<String>> {
    var content = rawContent.trim()
    val extractedTopics = mutableListOf<String>()

    // 1. Check for AI single-line tag: [RELATED_TOPICS: Topic 1, Topic 2, ...]
    val tagRegex = Regex("""\[(?:RELATED_TOPICS|CONNECTING_TOPICS):\s*([^\]]+)\]""", RegexOption.IGNORE_CASE)
    val tagMatch = tagRegex.find(content)
    if (tagMatch != null) {
      val topicsStr = tagMatch.groupValues[1]
      val parsed = topicsStr.split(Regex("[,|•;]+"))
        .map { it.replace(Regex("""[*_`#]+"""), "").trim() }
        .filter { it.isNotBlank() && it.length in 2..50 }
      extractedTopics.addAll(parsed)
      content = content.replace(tagMatch.value, "").trim()
    }

    // 2. Check for HTML comment tag: <!--RELATED_TOPICS: Topic 1, Topic 2-->
    val commentRegex = Regex("""<!--\s*(?:RELATED_TOPICS|CONNECTING_TOPICS):\s*([^>]+)-->""", RegexOption.IGNORE_CASE)
    val commentMatch = commentRegex.find(content)
    if (commentMatch != null) {
      val topicsStr = commentMatch.groupValues[1]
      val parsed = topicsStr.split(Regex("[,|•;]+"))
        .map { it.replace(Regex("""[*_`#]+"""), "").trim() }
        .filter { it.isNotBlank() && it.length in 2..50 }
      extractedTopics.addAll(parsed)
      content = content.replace(commentMatch.value, "").trim()
    }

    // 3. Strip any residual markdown bullet sections like "#### Connecting & Related Topics"
    val residualHeaderRegex = Regex("""(?i)(?:#{2,4}\s*(?:🔗\s*)?(?:Connecting|Related|Next)\s*(?:&|and)?\s*Topics?|Related\s*Topics\s*:)[\s\S]*""")
    val headerMatch = residualHeaderRegex.find(content)
    if (headerMatch != null) {
      val sectionText = headerMatch.value
      if (extractedTopics.isEmpty()) {
        for (line in sectionText.lines()) {
          val trimmedLine = line.trim()
          if (trimmedLine.startsWith("-") || trimmedLine.startsWith("*") || trimmedLine.startsWith("•")) {
            val cleanItem = trimmedLine.replace(Regex("^[-*•#]+\\s*"), "")
              .replace(Regex("""[*_`]+"""), "")
              .trim()
            if (cleanItem.isNotBlank() && cleanItem.length in 2..50 && !cleanItem.contains("Source Reference", ignoreCase = true)) {
              extractedTopics.add(cleanItem)
            }
          }
        }
      }
      content = content.substring(0, headerMatch.range.first).trim()
    }

    val finalTopics = if (extractedTopics.isNotEmpty()) {
      extractedTopics.distinct().take(6)
    } else {
      getRelatedTopics(fallbackQuery)
    }

    return Pair(content, finalTopics)
  }

  fun synthesizeEncyclopedicResponse(
    query: String,
    factExtract: String,
    sourceUrl: String
  ): String {
    val cleanQuery = query.trim().capitalizeWords()
    val domain = detectDomain(query)
    val sentences = factExtract
      .split(Regex("(?<=[.!?])\\s+"))
      .map { it.trim() }
      .filter { it.isNotBlank() }

    val leadSentence = sentences.firstOrNull() ?: factExtract.trim()
    val supportingPoints = sentences.drop(1).take(4)

    return buildString {
      append("### 💡 $cleanQuery (${domain.displayName})\n\n")
      append("> 📌 **Direct Answer:** $leadSentence\n\n")

      if (supportingPoints.isNotEmpty()) {
        append("#### 📖 Key Details\n")
        supportingPoints.forEach { pt ->
          append("- $pt\n")
        }
        append("\n")
      }

      append("🔗 **Reference:** [$sourceUrl]($sourceUrl)")
    }.trim()
  }

  fun synthesizeGeneralResponse(
    query: String,
    targetLevel: String? = null
  ): String {
    val cleanRaw = query.trim()
    val clean = cleanRaw.capitalizeWords()
    val domain = detectDomain(query)
    val lower = cleanRaw.lowercase(Locale.ROOT)

    val vsMatch = Regex("""(?i)(?:difference\s+between\s+|compare\s+)?(.+?)\s+(?:vs\.?|versus|and)\s+(.+?)(?:\?|$)""").find(cleanRaw)
    if (vsMatch != null && (lower.contains("vs") || lower.contains("versus") || lower.contains("difference") || lower.contains("compare"))) {
      val itemA = vsMatch.groupValues[1].trim().capitalizeWords()
      val itemB = vsMatch.groupValues[2].trim().capitalizeWords()
      return buildString {
        append("### ⚖️ Comparison: $itemA vs $itemB (${domain.displayName})\n\n")
        append("- **$itemA** and **$itemB** represent distinct mechanisms/concepts within ${domain.displayName}.\n")
        append("- To compare exact technical rules, connect your Gemini API key in **Settings** or enable **Google Search** above, or paste a chat link discussing **$itemA** & **$itemB** in **AI Chat Import**.")
      }
    }

    return buildString {
      append("### 💬 $clean (${domain.displayName})\n\n")
      append("I do not have an offline article or linked chat covering **$cleanRaw** specifically yet.\n\n")
      append("- **Get a direct detailed answer:** Enable **Google Search** or ensure your **Gemini AI** key is active in **Settings**.\n")
      append("- **Answer from your own notes:** Paste a ChatGPT/Gemini/Claude link about **$cleanRaw** in **AI Chat Import**.")
    }
  }

  private fun String.capitalizeWords(): String =
    split(" ").joinToString(" ") { word ->
      word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }
}
