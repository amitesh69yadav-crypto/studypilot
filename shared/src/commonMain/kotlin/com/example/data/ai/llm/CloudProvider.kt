package com.example.data.ai.llm

/**
 * Chatbot providers the user can bring a key for. All but Anthropic speak the OpenAI API; Anthropic
 * has its own, which [CloudClient] also handles.
 */
enum class CloudProvider(
  val label: String,
  val baseUrl: String,
  /** Tried in order when no model is chosen; otherwise the first chat model the key can use. */
  val preferredModels: List<String>,
  /** Where to make a key. */
  val keyUrl: String,
  /** One line on cost, shown under the picker. */
  val costNote: String,
  /** Requests a day before background work stops using it (free plans are small). */
  val dailyBudget: Int,
  val anthropic: Boolean = false
) {
  GROQ(
    "Groq", "https://api.groq.com/openai/v1",
    listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.1-8b-instant"),
    "https://console.groq.com/keys", "Free plan, no card: about 1,000 requests a day. Fastest.", 1000
  ),
  OPENROUTER(
    "OpenRouter", "https://openrouter.ai/api/v1",
    listOf("meta-llama/llama-3.3-70b-instruct:free", "openai/gpt-oss-120b:free", "deepseek/deepseek-chat-v3-0324:free"),
    "https://openrouter.ai/keys", "Many models; the \":free\" ones cost nothing (about 50 requests a day).", 50
  ),
  GEMINI(
    "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
    // "-latest" follows Google's newest Flash; otherwise the newest Flash the key lists is used.
    listOf("gemini-flash-latest"),
    "https://aistudio.google.com/apikey", "Small free tier; Google may use free-tier prompts to improve its products.", 100
  ),
  OPENAI(
    "OpenAI (ChatGPT)", "https://api.openai.com/v1",
    listOf("gpt-4.1-mini", "gpt-4o-mini"),
    "https://platform.openai.com/api-keys", "Paid per use.", 5000
  ),
  ANTHROPIC(
    "Anthropic (Claude)", "https://api.anthropic.com/v1",
    listOf("claude-haiku-5-5", "claude-sonnet-5-5"),
    "https://console.anthropic.com/settings/keys", "Paid per use.", 5000, anthropic = true
  ),
  MISTRAL(
    "Mistral", "https://api.mistral.ai/v1",
    listOf("mistral-small-latest", "mistral-medium-latest"),
    "https://console.mistral.ai/api-keys", "Free experiment plan with limits.", 500
  ),
  DEEPSEEK(
    "DeepSeek", "https://api.deepseek.com/v1",
    listOf("deepseek-chat"),
    "https://platform.deepseek.com/api_keys", "Paid, low cost.", 5000
  ),
  CUSTOM(
    "Other (OpenAI-compatible)", "",
    emptyList(), "", "Any provider with an OpenAI-style API: enter its address.", 1000
  );

  /** Reads a PDF itself, pictures included (scanned pages, diagrams, formulas), not just its text. */
  val readsPdf: Boolean get() = this == GEMINI || this == ANTHROPIC

  /** Quickest first: the order keys are tried for quiz work. */
  val speedRank: Int get() = when (this) {
    GROQ -> 0; GEMINI -> 1; MISTRAL -> 2; OPENROUTER -> 3; DEEPSEEK -> 4; OPENAI -> 5; ANTHROPIC -> 6; CUSTOM -> 7
  }

  companion object {
    fun byName(name: String?): CloudProvider = entries.firstOrNull { it.name == name } ?: GROQ
  }
}
