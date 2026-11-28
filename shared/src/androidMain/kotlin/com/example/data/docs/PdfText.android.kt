package com.example.data.docs

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/** PDFBox on Android needs its resources set up once; the app does this at start. */
object PdfSupport {
  @Volatile private var ready = false
  fun init(context: Context) {
    if (!ready) {
      PDFBoxResourceLoader.init(context.applicationContext)
      ready = true
    }
  }
}

actual fun pdfPages(bytes: ByteArray): List<String> =
  PDDocument.load(bytes).use { doc ->
    val stripper = PDFTextStripper()
    (1..doc.numberOfPages).map { page ->
      stripper.startPage = page
      stripper.endPage = page
      stripper.getText(doc)
    }
  }
