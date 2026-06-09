package com.dschat.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File

/**
 * Renders plain text to a PDF by laying it out with Android's own text stack (so CJK renders correctly
 * with the system font — no embedded-font headaches) onto A4 page bitmaps, then embedding each page as
 * an image. Text isn't selectable in the result, but it's visually faithful. Paginates on line
 * boundaries so no line is cut across pages.
 */
object TextPdfBuilder {
    private const val PAGE_W = 1240          // A4 width  @150dpi
    private const val PAGE_H = 1754          // A4 height @150dpi
    private const val MARGIN = 90
    private const val A4_PT_W = 595f         // A4 in PDF points
    private const val A4_PT_H = 842f
    private const val MAX_PAGES = 300

    fun build(text: String, outFile: File): Int {
        val paint = TextPaint().apply {
            isAntiAlias = true
            color = Color.BLACK
            textSize = 30f
            typeface = Typeface.DEFAULT
        }
        val contentW = PAGE_W - MARGIN * 2
        val contentH = PAGE_H - MARGIN * 2
        val safe = text.ifBlank { "（空白文档）" }
        val layout = StaticLayout.Builder.obtain(safe, 0, safe.length, paint, contentW).build()
        val lineCount = layout.lineCount

        val doc = PDDocument()
        try {
            var pages = 0
            var line = 0
            while (line < lineCount && pages < MAX_PAGES) {
                val pageTop = layout.getLineTop(line)
                var end = line
                while (end + 1 < lineCount && layout.getLineBottom(end + 1) - pageTop <= contentH) end++

                val bmp = Bitmap.createBitmap(PAGE_W, PAGE_H, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.WHITE)
                canvas.save()
                canvas.translate(MARGIN.toFloat(), MARGIN.toFloat() - pageTop)
                canvas.clipRect(0, pageTop, contentW, pageTop + contentH)
                layout.draw(canvas)
                canvas.restore()

                val img = JPEGFactory.createFromImage(doc, bmp, 0.9f)
                val page = PDPage(PDRectangle(A4_PT_W, A4_PT_H))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs -> cs.drawImage(img, 0f, 0f, A4_PT_W, A4_PT_H) }
                bmp.recycle()
                pages++
                line = end + 1
            }
            if (pages == 0) throw IllegalArgumentException("无内容可渲染")
            outFile.parentFile?.mkdirs()
            doc.save(outFile)
            return pages
        } finally {
            doc.close()
        }
    }
}
