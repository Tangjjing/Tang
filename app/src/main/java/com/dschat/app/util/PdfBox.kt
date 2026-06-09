package com.dschat.app.util

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * Lazy initializer for PdfBox-Android's resource loader. [PDFBoxResourceLoader.init] loads bundled
 * font/resource assets and costs tens of ms — but the vast majority of chat sessions never touch a
 * PDF. So instead of paying it on every cold start (in App.onCreate), we only stash the app context
 * at startup (free) and run the heavy init the first time a PDF operation actually needs it.
 */
object PdfBox {
    @Volatile private var appCtx: Context? = null
    @Volatile private var initialized = false

    /** Called once from App.onCreate — just remembers the context, does NO PdfBox work. */
    fun attach(context: Context) {
        appCtx = context.applicationContext
    }

    /** Idempotent, thread-safe: runs PDFBoxResourceLoader.init exactly once, on first PDF use. */
    fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appCtx?.let { ctx ->
                try {
                    PDFBoxResourceLoader.init(ctx)
                } catch (_: Throwable) {
                }
            }
            initialized = true
        }
    }
}
