package com.dschat.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

/** Renders a `data:image/...;base64,...` URL (decoded once, remembered by the URL string). */
@Composable
fun Base64Image(dataUrl: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val bitmap: ImageBitmap? = remember(dataUrl) {
        try {
            val b64 = dataUrl.substringAfter("base64,", dataUrl)
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
    bitmap?.let { Image(bitmap = it, contentDescription = "图片", modifier = modifier, contentScale = contentScale) }
}
