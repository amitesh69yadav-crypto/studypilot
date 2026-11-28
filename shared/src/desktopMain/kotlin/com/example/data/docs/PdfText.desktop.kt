package com.example.data.docs

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

actual fun pdfPages(bytes: ByteArray): List<String> =
  Loader.loadPDF(bytes).use { doc ->
    val stripper = PDFTextStripper()
    (1..doc.numberOfPages).map { page ->
      stripper.startPage = page
      stripper.endPage = page
      stripper.getText(doc)
    }
  }
