package com.example.data.export

import com.example.data.model.QuizAttempt
import com.example.data.model.StudyTopic
import com.example.data.model.TimetableSession
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Everything the progress report covers, gathered from the Progress page. */
data class ProgressReport(
  val studentName: String,
  val generatedAt: Date,
  val topics: List<StudyTopic>,
  val attempts: List<QuizAttempt>,
  val sessions: List<TimetableSession>,
  val studyStreak: Int,
  /** Monday to Sunday of this week, in hours, from the timetable. */
  val weekHours: List<Pair<String, Float>>
) {
  val mastered get() = topics.count { it.masteryLevel == "MASTERED" }
  val familiar get() = topics.count { it.masteryLevel == "FAMILIAR" }
  val struggling get() = topics.count { it.masteryLevel == "STRUGGLING" }
  val untested get() = topics.count { it.timesTested == 0 }
  val averageScore get() = if (attempts.isEmpty()) null else attempts.map { it.scorePercentage }.average().toInt()
  val bestScore get() = attempts.maxOfOrNull { it.scorePercentage }
  val weekTotal get() = weekHours.sumOf { it.second.toDouble() }

  /** One answered question from a quiz's stored breakdown. */
  data class Answer(val quizDate: String, val quizTitle: String, val topic: String, val question: String, val correct: Boolean)

  val answers: List<Answer>
    get() = attempts.flatMap { a ->
      runCatching {
        val arr = org.json.JSONArray(a.detailsJson)
        (0 until arr.length()).map { i ->
          val o = arr.getJSONObject(i)
          Answer(
            a.dateAttempted,
            a.title,
            o.optString("t").ifBlank { "General" },
            o.optString("k").substringBefore("|").trim().replaceFirstChar { it.uppercase() },
            o.optBoolean("ok")
          )
        }
      }.getOrDefault(emptyList())
    }

  /** Per subject: topics, how they stand, and accuracy in quizzes. */
  val bySubject: List<List<Any>>
    get() = topics.groupBy { it.subjectName.ifBlank { "General" } }.map { (subject, list) ->
      val tested = list.sumOf { it.timesTested }
      val correct = list.sumOf { it.timesCorrect }
      listOf(
        subject,
        list.size,
        list.count { it.masteryLevel == "MASTERED" },
        list.count { it.masteryLevel == "FAMILIAR" },
        list.count { it.masteryLevel == "STRUGGLING" },
        list.count { it.timesTested == 0 },
        if (tested == 0) "" else (correct * 100 / tested)
      )
    }.sortedBy { it[0].toString() }

  val fileStem: String get() = "StudyPilot progress " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(generatedAt)
}

private fun masteryLabel(level: String) = when (level) {
  "MASTERED" -> "Mastered"
  "FAMILIAR" -> "Familiar"
  "STRUGGLING" -> "Needs review"
  else -> "Untested"
}

private fun accuracy(t: StudyTopic): Any = if (t.timesTested == 0) "" else t.timesCorrect * 100 / t.timesTested

private fun xml(text: String) = text
  .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
  .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }

private fun zip(files: List<Pair<String, String>>): ByteArray {
  val out = ByteArrayOutputStream()
  ZipOutputStream(out).use { z ->
    for ((name, body) in files) {
      z.putNextEntry(ZipEntry(name))
      z.write(body.toByteArray(Charsets.UTF_8))
      z.closeEntry()
    }
  }
  return out.toByteArray()
}

// ---------------------------------------------------------------- Excel

private class Sheet(val name: String, val header: List<String>, val rows: List<List<Any>>, val widths: List<Int>)

private fun sheets(r: ProgressReport): List<Sheet> {
  val stamp = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(r.generatedAt)
  val summary = listOf<List<Any>>(
    listOf("Student", r.studentName),
    listOf("Report made", stamp),
    listOf("Topics recorded", r.topics.size),
    listOf("Mastered", r.mastered),
    listOf("Familiar", r.familiar),
    listOf("Needs review", r.struggling),
    listOf("Not yet tested", r.untested),
    listOf("Quizzes completed", r.attempts.size),
    listOf("Average quiz score (%)", r.averageScore ?: ""),
    listOf("Best quiz score (%)", r.bestScore ?: ""),
    listOf("Questions answered", r.answers.size),
    listOf("Questions right", r.answers.count { it.correct }),
    listOf("Study streak (days)", r.studyStreak),
    listOf("Study hours this week", "%.1f".format(r.weekTotal).toDouble())
  )
  return listOf(
    Sheet("Summary", listOf("Measure", "Value"), summary, listOf(28, 26)),
    Sheet(
      "Subjects", listOf("Subject", "Topics", "Mastered", "Familiar", "Needs review", "Untested", "Accuracy (%)"),
      r.bySubject, listOf(26, 9, 10, 10, 13, 10, 13)
    ),
    Sheet(
      "Topics",
      listOf("Topic", "Subject", "Mastery", "Times tested", "Times right", "Accuracy (%)", "Studied on", "Last tested", "Next review", "Review every (days)"),
      r.topics.sortedWith(compareBy({ it.subjectName }, { it.title })).map { t ->
        listOf(t.title, t.subjectName, masteryLabel(t.masteryLevel), t.timesTested, t.timesCorrect, accuracy(t), t.dateStudied, t.lastTestedDate ?: "", t.nextRevisionDate, t.repetitionIntervalDays)
      },
      listOf(34, 18, 13, 12, 11, 12, 12, 12, 12, 18)
    ),
    Sheet(
      "Quizzes", listOf("Date", "Quiz", "Score (%)", "Right", "Questions", "Topics covered", "Topics left for next time"),
      r.attempts.sortedByDescending { it.dateAttempted }.map { a ->
        listOf(a.dateAttempted, a.title, a.scorePercentage, a.correctCount, a.totalQuestions, a.topicsCoveredCount, a.topicsSkippedCount)
      },
      listOf(17, 26, 10, 8, 10, 14, 22)
    ),
    Sheet(
      "Answers", listOf("Quiz date", "Quiz", "Topic", "Question", "Result"),
      r.answers.map { listOf(it.quizDate, it.quizTitle, it.topic, it.question, if (it.correct) "Right" else "Wrong") },
      listOf(17, 24, 24, 60, 9)
    ),
    Sheet("This week", listOf("Day", "Hours studied"), r.weekHours.map { listOf(it.first, "%.1f".format(it.second).toDouble()) }, listOf(12, 14)),
    Sheet(
      "Timetable", listOf("Date", "Start", "End", "Session", "Subject", "Kind", "Repeats on", "Status"),
      r.sessions.sortedWith(compareBy({ it.date }, { it.startTime })).map { s ->
        listOf(s.date, s.startTime, s.endTime, s.topic, s.subjectName, s.sessionCategory.lowercase().replaceFirstChar { it.uppercase() }, s.recurringDays, s.status.lowercase().replaceFirstChar { it.uppercase() })
      },
      listOf(12, 8, 8, 32, 18, 11, 16, 12)
    )
  )
}

private fun column(i: Int): String {
  var n = i + 1
  val sb = StringBuilder()
  while (n > 0) {
    val r = (n - 1) % 26
    sb.insert(0, ('A' + r))
    n = (n - 1) / 26
  }
  return sb.toString()
}

private fun cell(ref: String, value: Any, style: Int): String = when (value) {
  is Number -> "<c r=\"$ref\" s=\"$style\"><v>$value</v></c>"
  else -> "<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value.toString())}</t></is></c>"
}

private fun sheetXml(sheet: Sheet): String {
  val sb = StringBuilder()
  sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
  sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
  // Header row stays in view while scrolling.
  sb.append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
  sb.append("<cols>")
  sheet.widths.forEachIndexed { i, w -> sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
  sb.append("</cols><sheetData>")
  sb.append("<row r=\"1\">")
  sheet.header.forEachIndexed { i, h -> sb.append(cell("${column(i)}1", h, 1)) }
  sb.append("</row>")
  sheet.rows.forEachIndexed { r, row ->
    sb.append("<row r=\"${r + 2}\">")
    row.forEachIndexed { i, v -> sb.append(cell("${column(i)}${r + 2}", v, 0)) }
    sb.append("</row>")
  }
  sb.append("</sheetData>")
  if (sheet.rows.isNotEmpty()) sb.append("""<autoFilter ref="A1:${column(sheet.header.size - 1)}${sheet.rows.size + 1}"/>""")
  sb.append("</worksheet>")
  return sb.toString()
}

/** The report as an Excel workbook: a summary and one sheet each for subjects, topics, quizzes and more. */
fun progressXlsx(r: ProgressReport): ByteArray {
  val list = sheets(r)
  val files = mutableListOf<Pair<String, String>>()
  files += "[Content_Types].xml" to (
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
      """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""" +
      """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
      """<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
      list.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } +
      "</Types>")
  files += "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
  files += "xl/workbook.xml" to (
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
      list.mapIndexed { i, s -> """<sheet name="${xml(s.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") +
      "</sheets></workbook>")
  files += "xl/_rels/workbook.xml.rels" to (
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
      list.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
      """<Relationship Id="rId${list.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
  // Style 0: plain. Style 1: bold header on a pale sage fill.
  files += "xl/styles.xml" to (
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
      """<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>""" +
      """<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFDFEAE0"/></patternFill></fill></fills>""" +
      """<borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
      """<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs>""" +
      """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
  list.forEachIndexed { i, s -> files += "xl/worksheets/sheet${i + 1}.xml" to sheetXml(s) }
  return zip(files)
}

// ---------------------------------------------------------------- Word

private fun para(text: String, bold: Boolean = false, size: Int = 22, color: String? = null, spaceAfter: Int = 120): String {
  val rpr = buildString {
    append("<w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/>")
    if (bold) append("<w:b/>")
    if (color != null) append("<w:color w:val=\"$color\"/>")
    append("<w:sz w:val=\"$size\"/></w:rPr>")
  }
  return "<w:p><w:pPr><w:spacing w:after=\"$spaceAfter\"/></w:pPr><w:r>$rpr<w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r></w:p>"
}

private fun table(header: List<String>, rows: List<List<Any>>): String {
  if (rows.isEmpty()) return para("Nothing here yet.", color = "6B7280")
  val sb = StringBuilder()
  sb.append("<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"pct\"/>")
  sb.append("<w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"C9D3C6\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"C9D3C6\"/><w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"E3E8E1\"/></w:tblBorders>")
  sb.append("<w:tblCellMar><w:top w:w=\"60\" w:type=\"dxa\"/><w:left w:w=\"100\" w:type=\"dxa\"/><w:bottom w:w=\"60\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr>")
  // The column grid Word expects: the page width shared evenly.
  sb.append("<w:tblGrid>")
  repeat(header.size) { sb.append("<w:gridCol w:w=\"${9638 / header.size}\"/>") }
  sb.append("</w:tblGrid>")
  fun row(cells: List<Any>, head: Boolean) {
    sb.append("<w:tr>")
    for (c in cells) {
      sb.append("<w:tc><w:tcPr>")
      if (head) sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"DFEAE0\"/>")
      sb.append("</w:tcPr>")
      sb.append(para(c.toString(), bold = head, size = 20, spaceAfter = 0))
      sb.append("</w:tc>")
    }
    sb.append("</w:tr>")
  }
  row(header, true)
  rows.forEach { row(it, false) }
  sb.append("</w:tbl>")
  sb.append(para("", spaceAfter = 120))
  return sb.toString()
}

/** The report as a Word document: a summary, then tables for subjects, topics, quizzes and the week. */
fun progressDocx(r: ProgressReport): ByteArray {
  val stamp = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale.getDefault()).format(r.generatedAt)
  val body = StringBuilder()
  body.append(para("Study progress report", bold = true, size = 40, color = "1D2521", spaceAfter = 60))
  body.append(para("${r.studentName} · made $stamp", size = 20, color = "46534A", spaceAfter = 240))

  body.append(para("Summary", bold = true, size = 28, color = "2C5A45"))
  body.append(
    para(
      "${r.topics.size} topics recorded: ${r.mastered} mastered, ${r.familiar} familiar, ${r.struggling} needing review and ${r.untested} not yet tested. " +
        if (r.attempts.isEmpty()) "No quizzes taken yet. "
        else "${if (r.attempts.size == 1) "1 quiz" else "${r.attempts.size} quizzes"} taken with an average score of ${r.averageScore}% (best ${r.bestScore}%), " +
          "${r.answers.count { it.correct }} of ${r.answers.size} questions right. " +
          "Study streak: ${r.studyStreak} days. ${"%.1f".format(r.weekTotal)} hours studied this week."
    )
  )
  body.append(para("Subjects", bold = true, size = 28, color = "2C5A45"))
  body.append(table(listOf("Subject", "Topics", "Mastered", "Familiar", "Needs review", "Untested", "Accuracy %"), r.bySubject))

  val weak = r.topics.filter { it.masteryLevel == "STRUGGLING" }
  body.append(para("Topics to review first", bold = true, size = 28, color = "2C5A45"))
  body.append(table(listOf("Topic", "Subject", "Right", "Next review"), weak.map { listOf(it.title, it.subjectName, "${it.timesCorrect} of ${it.timesTested}", it.nextRevisionDate) }))

  body.append(para("All topics", bold = true, size = 28, color = "2C5A45"))
  body.append(
    table(
      listOf("Topic", "Subject", "Mastery", "Accuracy %", "Studied", "Next review"),
      r.topics.sortedWith(compareBy({ it.subjectName }, { it.title })).map { listOf(it.title, it.subjectName, masteryLabel(it.masteryLevel), accuracy(it), it.dateStudied, it.nextRevisionDate) }
    )
  )
  body.append(para("Quizzes", bold = true, size = 28, color = "2C5A45"))
  body.append(
    table(
      listOf("Date", "Quiz", "Score", "Right", "Topics"),
      r.attempts.sortedByDescending { it.dateAttempted }.map { listOf(it.dateAttempted, it.title, "${it.scorePercentage}%", "${it.correctCount} of ${it.totalQuestions}", it.topicsCoveredCount) }
    )
  )
  body.append(para("This week", bold = true, size = 28, color = "2C5A45"))
  body.append(table(listOf("Day", "Hours"), r.weekHours.map { listOf(it.first, "%.1f".format(it.second)) }))

  val document =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""" +
      body +
      """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""
  return zip(
    listOf(
      "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>""",
      // Marks the file as a current Word document, so Word doesn't open it in compatibility mode.
      "word/_rels/document.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/></Relationships>""",
      "word/settings.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat></w:settings>""",
      "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
      "word/document.xml" to document
    )
  )
}
