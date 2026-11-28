package com.example.data.docs

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Text of a PDF, one string per page; empty when the file can't be read (or is only scanned images). */
expect fun pdfPages(bytes: ByteArray): List<String>

/**
 * Text from course files, page by page (slide by slide for presentations), so answers can cite where
 * something came from. PDF, PowerPoint (.pptx) and Word (.docx); older .ppt/.doc aren't supported.
 */
object DocumentText {
  /** "pdf", "pptx" or "docx" for a file name or content type; null for anything else. */
  fun typeOf(fileName: String, contentType: String = ""): String? {
    val name = fileName.lowercase()
    val ct = contentType.lowercase()
    return when {
      name.endsWith(".pdf") || ct == "application/pdf" -> "pdf"
      name.endsWith(".pptx") || ct.contains("presentationml") -> "pptx"
      name.endsWith(".docx") || ct.contains("wordprocessingml") -> "docx"
      else -> null
    }
  }

  fun pages(bytes: ByteArray, type: String): List<String> = runCatching {
    when (type) {
      "pdf" -> pdfPages(bytes)
      "pptx" -> slides(bytes)
      "docx" -> docxSections(bytes)
      else -> emptyList()
    }
  }.getOrDefault(emptyList()).map { tidy(it) }

  private fun tidy(text: String) = text.replace(Regex("[ \t]+"), " ").replace(Regex(" *\n *"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()

  private fun zipEntries(bytes: ByteArray, keep: (String) -> Boolean): Map<String, String> {
    val out = HashMap<String, String>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val e = zip.nextEntry ?: break
        if (!e.isDirectory && keep(e.name)) out[e.name] = zip.readBytes().toString(Charsets.UTF_8)
      }
    }
    return out
  }

  private val textRun = Regex("<a:t>([^<]*)</a:t>|<w:t(?: [^>]*)?>([^<]*)</w:t>|<a:br/>|</a:p>|</w:p>|<w:tab/>|<w:br/>")

  /** Visible text of an Office XML part: runs joined, paragraphs on their own lines. */
  private fun xmlText(xml: String): String = buildString {
    textRun.findAll(xml).forEach { m ->
      val run = m.groupValues[1].ifEmpty { m.groupValues[2] }
      when {
        run.isNotEmpty() -> append(unescape(run))
        m.value == "<w:tab/>" -> append(' ')
        else -> append('\n')
      }
    }
  }

  private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

  /** Each slide's text followed by its speaker notes, in slide order. */
  private fun slides(bytes: ByteArray): List<String> {
    val parts = zipEntries(bytes) { it.startsWith("ppt/slides/slide") || it.startsWith("ppt/notesSlides/notesSlide") }
    fun number(name: String) = Regex("""(\d+)\.xml$""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val slides = parts.filterKeys { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }.toSortedMap(compareBy(::number))
    return slides.map { (name, xml) ->
      val notes = parts["ppt/notesSlides/notesSlide${number(name)}.xml"]?.let(::xmlText)?.trim()
        // Notes slides repeat the slide number; keep only real notes.
        ?.takeIf { it.length > 3 && !it.matches(Regex("""\d+""")) }
      xmlText(xml) + if (notes != null) "\n\nNotes: $notes" else ""
    }
  }

  private val PARAGRAPH = Regex("<w:p[ >].*?</w:p>|<w:p/>", RegexOption.DOT_MATCHES_ALL)
  private val HEADING_STYLE = Regex("""<w:pStyle w:val="(Heading[123]|Title|Kop[123]|Titel)"""", RegexOption.IGNORE_CASE)

  /**
   * A Word document as sections: a new one at each heading (Heading 1-3 or Title), or, when it has no
   * headings, every ~2,500 characters at a paragraph break. Each section is read like a slide.
   */
  private fun docxSections(bytes: ByteArray): List<String> {
    val xml = zipEntries(bytes) { it == "word/document.xml" }["word/document.xml"] ?: return emptyList()
    val paragraphs = PARAGRAPH.findAll(xml).map { it.value }.toList()
    val headed = paragraphs.any { HEADING_STYLE.containsMatchIn(it) }
    val sections = mutableListOf<StringBuilder>()
    var current = StringBuilder()
    for (p in paragraphs) {
      val text = xmlText(p).trim()
      val startsNew = if (headed) HEADING_STYLE.containsMatchIn(p) else current.length > 2500
      if (startsNew && current.isNotBlank()) {
        sections += current
        current = StringBuilder()
      }
      if (text.isNotEmpty()) current.append(text).append('\n')
    }
    if (current.isNotBlank()) sections += current
    return sections.map { it.toString() }
  }
}
