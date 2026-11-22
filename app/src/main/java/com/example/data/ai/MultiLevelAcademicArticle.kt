package com.example.data.ai

/**
 * MultiLevelAcademicArticle: Represents a full pedagogical breakdown of an academic concept,
 * structured across multiple academic tiers from intuitive foundation to advanced research rigor.
 */
data class MultiLevelAcademicArticle(
  val title: String,
  val domain: String,
  val thesis: String,
  val elementary: String, // Intuitive Foundation (relatable analogy, zero jargon)
  val highSchool: String, // Foundational Principles (causal mechanism, fundamental laws)
  val undergraduate: String, // Technical Analysis (formal equations, system architecture)
  val phdResearch: String, // Advanced Research Rigor (asymptotic limits, open questions)
  val formulaOrCode: Pair<String, String>? = null, // language/type and snippet
  val summaryTable: List<Pair<String, String>> = emptyList(), // Level -> Key Takeaway
  val examTrap: String? = null
)
