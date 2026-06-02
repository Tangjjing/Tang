package com.dschat.app.agent.tasks

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * Watches MediaStore for newly-added screenshots and, when [shouldProcess] returns true, runs
 * on-device OCR (ML Kit, offline) and hands the recognized text to [onText].
 */
class ScreenshotWatcher(
    private val context: Context,
    private val shouldProcess: () -> Boolean,
    private val onText: (String) -> Unit
) {
    private val resolver = context.contentResolver
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    @Volatile
    private var lastId = -1L

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (shouldProcess()) checkLatest()
        }
    }

    fun start() {
        lastId = latest()?.first ?: -1L // ignore screenshots that already existed
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    fun stop() {
        try { resolver.unregisterContentObserver(observer) } catch (_: Exception) {}
        try { recognizer.close() } catch (_: Exception) {}
    }

    private fun checkLatest() {
        val (id, uri, name) = latest() ?: return
        if (id <= lastId) return
        lastId = id
        if (!name.contains("screenshot", ignoreCase = true) &&
            !name.contains("截屏") && !name.contains("截图")
        ) return
        ocr(uri)
    }

    private fun latest(): Triple<Long, Uri, String>? {
        val proj = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
        return try {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                "${MediaStore.Images.Media._ID} DESC"
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: ""
                    Triple(id, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), name)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun ocr(uri: Uri) {
        try {
            val image = InputImage.fromFilePath(context, uri)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.text
                    if (text.isNotBlank()) onText(text)
                }
                .addOnFailureListener { }
        } catch (e: Exception) {
        }
    }
}
