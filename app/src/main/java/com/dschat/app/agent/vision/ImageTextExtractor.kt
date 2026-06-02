package com.dschat.app.agent.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Turns an image (base64 data-URL) into a TEXT description fully on-device & offline:
 * ML Kit OCR (text in the image) + ML Kit image labeling (coarse object/scene keywords).
 * Used so a TEXT-ONLY model (e.g. DeepSeek) can still "read" an attached image.
 */
object ImageTextExtractor {
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    }

    suspend fun extract(dataUrl: String): String = withContext(Dispatchers.Default) {
        val bmp = decode(dataUrl) ?: return@withContext ""
        val image = InputImage.fromBitmap(bmp, 0)
        val text = try { recognizer.process(image).awaitResult().text.trim() } catch (e: Exception) { "" }
        val labels = try {
            labeler.process(image).awaitResult()
                .filter { it.confidence >= 0.6f }
                .take(6)
                .joinToString("、") { it.text }
        } catch (e: Exception) { "" }
        buildString {
            if (text.isNotBlank()) append("图中文字：\n").append(text)
            if (labels.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("画面可能包含（自动识别，英文标签）：").append(labels)
            }
        }
    }

    private fun decode(dataUrl: String): Bitmap? = try {
        val b64 = dataUrl.substringAfter("base64,", dataUrl)
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }
}
