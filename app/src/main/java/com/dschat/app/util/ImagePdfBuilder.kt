package com.dschat.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File

/**
 * 把本机图片合成为 PDF（一张图一页，页面尺寸跟随图片）。使用已有的 PdfBox-Android 依赖，
 * 无需额外库。照片走 JPEG 压缩（体积小），PNG 走无损以保留透明/线条。
 */
object ImagePdfBuilder {

    /** 单张超大图会爆内存，限制单边像素；超过则等比缩小后再嵌入。 */
    private const val MAX_EDGE = 2200

    /**
     * 用 [imagePaths] 里的图片生成 PDF 写入 [outFile]。返回成功写入的页数。
     * 没有任何可读图片时抛 [IllegalArgumentException]。
     */
    fun build(imagePaths: List<String>, outFile: File): Int {
        val doc = PDDocument()
        try {
            var pages = 0
            for (path in imagePaths) {
                val bmp = decodeScaled(path) ?: continue
                val isPng = path.lowercase().endsWith(".png")
                val pdImage = try {
                    if (isPng) LosslessFactory.createFromImage(doc, bmp)
                    else JPEGFactory.createFromImage(doc, bmp, 0.85f)
                } catch (_: Exception) {
                    // JPEG 编码偶尔会因色彩格式失败，退回无损。
                    LosslessFactory.createFromImage(doc, bmp)
                }
                val w = bmp.width.toFloat()
                val h = bmp.height.toFloat()
                val page = PDPage(PDRectangle(w, h))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs -> cs.drawImage(pdImage, 0f, 0f, w, h) }
                bmp.recycle()
                pages++
            }
            if (pages == 0) throw IllegalArgumentException("没有可读取的图片")
            outFile.parentFile?.mkdirs()
            doc.save(outFile)
            return pages
        } finally {
            doc.close()
        }
    }

    private fun decodeScaled(path: String): Bitmap? {
        val f = File(path)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > MAX_EDGE) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
