package com.dschat.app.util

import android.graphics.Bitmap
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File

/** Offline PDF operations backed by PdfBox-Android: render to images, merge, extract pages. */
object PdfUtils {

    /** Render each page of [pdf] to a PNG in [outDir]; returns the written file paths (capped). */
    fun toImages(pdf: File, outDir: File, baseName: String, dpi: Float = 150f, maxPages: Int = 50): List<String> {
        outDir.mkdirs()
        val out = mutableListOf<String>()
        PDDocument.load(pdf).use { doc ->
            val renderer = PDFRenderer(doc)
            val n = minOf(doc.numberOfPages, maxPages)
            for (i in 0 until n) {
                val bmp = renderer.renderImageWithDPI(i, dpi)
                val f = File(outDir, "${baseName}_p${i + 1}.png")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                out.add(f.absolutePath)
            }
            return out
        }
    }

    /** Total page count of [pdf] (for "rendered N of M pages" notes). */
    fun pageCount(pdf: File): Int = PDDocument.load(pdf).use { it.numberOfPages }

    /** Merge [inputs] (in order) into a single [outFile]. */
    fun merge(inputs: List<File>, outFile: File) {
        outFile.parentFile?.mkdirs()
        val merger = PDFMergerUtility()
        merger.destinationFileName = outFile.absolutePath
        inputs.forEach { merger.addSource(it) }
        merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
    }

    /** Write the 1-based [pages] of [pdf] into a new [outFile]. Returns how many pages were written. */
    fun extractPages(pdf: File, pages: List<Int>, outFile: File): Int {
        outFile.parentFile?.mkdirs()
        PDDocument.load(pdf).use { src ->
            PDDocument().use { dst ->
                var count = 0
                for (p in pages) {
                    val idx = p - 1
                    if (idx in 0 until src.numberOfPages) {
                        dst.importPage(src.getPage(idx)); count++
                    }
                }
                if (count == 0) throw IllegalArgumentException("没有有效页码（文档共 ${src.numberOfPages} 页）")
                dst.save(outFile)
                return count
            }
        }
    }

    /** Parse a page spec like "1-3,5,8-10" into a de-duplicated, ordered 1-based page list. */
    fun parsePages(spec: String): List<Int> {
        val result = LinkedHashSet<Int>()
        for (part in spec.split(",", "，")) {
            val t = part.trim()
            if (t.isEmpty()) continue
            val dash = t.split("-", "–")
            if (dash.size == 2) {
                val a = dash[0].trim().toIntOrNull()
                val b = dash[1].trim().toIntOrNull()
                if (a != null && b != null) for (i in a..b) result.add(i)
            } else {
                t.toIntOrNull()?.let { result.add(it) }
            }
        }
        return result.toList()
    }
}
