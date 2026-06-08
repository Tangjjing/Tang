package com.dschat.app.ui.components

import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fullscreen QR / barcode scanner: CameraX preview + offline ML Kit barcode analysis. Calls [onResult]
 * exactly once with the first decoded value. Camera is bound to the host lifecycle and explicitly
 * unbound on dispose so it never leaks after the scanner closes.
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun QrScannerScreen(onResult: (String) -> Unit, onClose: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val handled = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    val providerHolder = remember { arrayOfNulls<ProcessCameraProvider>(1) }
    // Created once so DisposableEffect can close it (the ML Kit detector holds native model memory).
    val scanner = remember { BarcodeScanning.getClient() }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            providerHolder[0]?.unbindAll()
            try { scanner.close() } catch (_: Exception) {}
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val future = ProcessCameraProvider.getInstance(ctx)
                // The provider resolves asynchronously; the callback + the analyzer both run on the MAIN
                // executor (same thread as onDispose), so the `disposed` checks are race-free.
                future.addListener({
                    val provider = try { future.get() } catch (_: Exception) { return@addListener }
                    providerHolder[0] = provider
                    if (disposed.get()) { provider.unbindAll(); return@addListener } // closed before camera arrived
                    val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                        // Stop touching the (closed) scanner once disposed or after the first hit.
                        if (disposed.get() || handled.get()) { proxy.close(); return@setAnalyzer }
                        val media = proxy.image
                        if (media == null) { proxy.close(); return@setAnalyzer }
                        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                        scanner.process(img)
                            .addOnSuccessListener { codes ->
                                val v = codes.firstOrNull()?.rawValue
                                if (!v.isNullOrBlank() && handled.compareAndSet(false, true)) onResult(v)
                            }
                            .addOnCompleteListener { proxy.close() }
                    }
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    } catch (_: Exception) {
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White) }
            Text("将二维码 / 条码对准摄像头", color = Color.White, fontSize = 15.sp)
        }
    }
}
