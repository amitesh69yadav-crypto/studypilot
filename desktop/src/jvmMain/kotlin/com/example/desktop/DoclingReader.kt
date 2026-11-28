package com.example.desktop

import com.example.platform.LocalDocumentReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads documents on this PC with Docling (layout, tables, OCR for scans) into Markdown. Docling is a
 * Python tool the student installs once ("pip install docling"); it's found on PATH or through Python.
 */
class DoclingReader : LocalDocumentReader {

  /** The command that runs Docling here, found once. */
  @Volatile private var command: List<String>? = null
  @Volatile private var checked = 0L

  private fun run(cmd: List<String>, timeoutSec: Long): Pair<Int, String> {
    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
    val out = StringBuilder()
    val reader = Thread { p.inputStream.bufferedReader().forEachLine { synchronized(out) { if (out.length < 20_000) out.append(it).append('\n') } } }
    reader.isDaemon = true
    reader.start()
    if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
      p.destroyForcibly()
      return -1 to "timed out"
    }
    reader.join(1000)
    return p.exitValue() to synchronized(out) { out.toString() }
  }

  private fun find(): List<String>? {
    command?.let { return it }
    if (System.currentTimeMillis() - checked < 30_000) return null
    checked = System.currentTimeMillis()
    // PyTorch is loaded before Docling: on Windows another library Docling loads first can make
    // PyTorch's DLLs fail to start (WinError 1114).
    val launcher = "import torch, sys; sys.argv = ['docling'] + sys.argv[1:]; from docling.cli.main import app; app()"
    // StudyPilot's own environment first (matching versions, nothing else's packages), then the system's.
    val ownPython = File(System.getenv("LOCALAPPDATA") ?: "", "StudyPilot-Docling\\Scripts\\python.exe")
    val candidates = listOfNotNull(
      if (ownPython.isFile) listOf(ownPython.absolutePath, "-c", launcher) else null,
      listOf("python", "-c", launcher),
      listOf("docling")
    )
    for (c in candidates) {
      val ok = runCatching { run(c + "--version", 120).first == 0 }.getOrDefault(false)
      if (ok) { command = c; return c }
    }
    return null
  }

  override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) { find() != null }

  override suspend fun toMarkdown(file: File): String = withContext(Dispatchers.IO) {
    val cmd = find() ?: throw IllegalStateException("Docling isn't installed on this PC (pip install docling).")
    val out = File(System.getProperty("java.io.tmpdir"), "studypilot-docling-" + System.nanoTime()).apply { mkdirs() }
    try {
      // The first run downloads Docling's layout models, so it can take a while.
      val (code, log) = run(cmd + listOf(file.absolutePath, "--to", "md", "--image-export-mode", "placeholder", "--output", out.absolutePath), 30 * 60)
      val md = out.walkTopDown().firstOrNull { it.isFile && it.extension == "md" }
      if (code != 0 || md == null) throw IllegalStateException("Docling couldn't read it: " + log.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(200))
      md.readText()
    } finally {
      out.deleteRecursively()
    }
  }
}
