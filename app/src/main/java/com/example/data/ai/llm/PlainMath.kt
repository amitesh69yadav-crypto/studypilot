package com.example.data.ai.llm

/**
 * The chat renderer shows Markdown but not LaTeX, so models' `$\lambda \mathbf{v}$` would appear raw.
 * Converts the common commands to Unicode and drops the math delimiters. Unknown commands are left as-is.
 */
object PlainMath {

  private val SYMBOLS = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε",
    "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ",
    "nu" to "ν", "xi" to "ξ", "pi" to "π", "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "phi" to "φ",
    "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Pi" to "Π", "Sigma" to "Σ",
    "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    "cdot" to "·", "times" to "×", "div" to "÷", "pm" to "±", "mp" to "∓",
    "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "neq" to "≠", "ne" to "≠", "approx" to "≈",
    "equiv" to "≡", "sim" to "∼", "propto" to "∝", "infty" to "∞", "partial" to "∂", "nabla" to "∇",
    "sum" to "Σ", "prod" to "Π", "int" to "∫", "oint" to "∮",
    "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
    "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "implies" to "⇒", "iff" to "⇔", "mapsto" to "↦",
    "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃", "cup" to "∪",
    "cap" to "∩", "emptyset" to "∅", "forall" to "∀", "exists" to "∃", "neg" to "¬", "land" to "∧",
    "lor" to "∨", "circ" to "∘", "degree" to "°", "ldots" to "…", "cdots" to "⋯", "dots" to "…",
    "langle" to "⟨", "rangle" to "⟩", "quad" to " ", "qquad" to "  ", "," to " ", ";" to " ", "!" to ""
  )
  private val SUPERSCRIPTS = mapOf(
    '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷',
    '8' to '⁸', '9' to '⁹', '+' to '⁺', '-' to '⁻', 'n' to 'ⁿ', 'i' to 'ⁱ', 'T' to 'ᵀ'
  )

  private val DISPLAY = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL)
  private val BRACKET_DISPLAY = Regex("""\\\[(.+?)\\]""", RegexOption.DOT_MATCHES_ALL)
  private val PAREN_INLINE = Regex("""\\\((.+?)\\\)""")
  // $...$ with no leading digit, so prices like "$5 and $10" are left alone.
  private val INLINE = Regex("""\$(?![\s\d])([^$\n]{1,300}?)(?<!\s)\$""")
  private val FRAC = Regex("""\\[dt]?frac\{([^{}]*)\}\{([^{}]*)\}""")
  private val SQRT = Regex("""\\sqrt\{([^{}]*)\}""")
  private val WRAPPERS = Regex("""\\(?:mathbf|mathrm|mathit|mathcal|mathbb|boldsymbol|text|textbf|textit|operatorname|vec|hat|bar|overline)\{([^{}]*)\}""")
  private val COMMAND = Regex("""\\([A-Za-z]+|[,;!])""")
  private val SUPER = Regex("""\^\{?([0-9+\-niT]{1,3})\}?""")
  // Android's ICU regex engine needs "}" escaped even where desktop Java doesn't.
  private val LEFT_RIGHT = Regex("""\\(?:left|right|big|Big|bigg|Bigg)\s*""")

  fun convert(text: String): String {
    if ('\\' !in text && '$' !in text) return text
    var s = text
    s = DISPLAY.replace(s) { "\n" + it.groupValues[1].trim() + "\n" }
    s = BRACKET_DISPLAY.replace(s) { "\n" + it.groupValues[1].trim() + "\n" }
    s = PAREN_INLINE.replace(s) { it.groupValues[1] }
    s = INLINE.replace(s) { m -> if (looksLikeMath(m.groupValues[1])) m.groupValues[1] else m.value }
    s = LEFT_RIGHT.replace(s, "")
    // Symbols first, so "\lambda\mathbf{v}" doesn't become the unknown command "\lambdav".
    s = COMMAND.replace(s) { m -> SYMBOLS[m.groupValues[1]] ?: m.value }
    // Innermost first, a few passes for nesting like \frac{\sqrt{x}}{2}.
    repeat(3) {
      s = WRAPPERS.replace(s) { it.groupValues[1] }
      s = SQRT.replace(s) { "√(${it.groupValues[1]})" }
      s = FRAC.replace(s) { "(${it.groupValues[1]})/(${it.groupValues[2]})" }
    }
    s = COMMAND.replace(s) { m -> SYMBOLS[m.groupValues[1]] ?: greekRunOn(m.groupValues[1]) ?: m.value }
    s = SUPER.replace(s) { m -> m.groupValues[1].map { SUPERSCRIPTS[it] ?: it }.joinToString("") }
    return s
  }

  private val GREEK = SYMBOLS.filterValues { it.length == 1 && it[0] in 'Α'..'ω' }

  /** Small models write "\lambdav" for "\lambda v": a Greek letter run into a 1-2 letter variable. */
  private fun greekRunOn(command: String): String? {
    val name = GREEK.keys.filter { command.startsWith(it) && command.length - it.length in 1..2 }.maxByOrNull { it.length }
      ?: return null
    return GREEK.getValue(name) + command.removePrefix(name)
  }

  private fun looksLikeMath(inner: String) =
    inner.length <= 3 || inner.any { it == '\\' || it == '^' || it == '_' || it == '=' || it == '{' }
}
