package com.dschat.app.util

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Minimal Text-To-Speech holder: lazily inits the engine (Chinese locale) and reads one reply at a time
 * (a new [speak] interrupts the previous one). Create it with `remember` in a screen and call [shutdown]
 * from the screen's onDispose so the engine is released.
 */
class Tts(context: Context) {
    // Written on the TTS init-callback thread, read on the main thread → @Volatile for visibility.
    @Volatile private var ready = false
    @Volatile private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.CHINESE
                ready = true
            }
        }
    }

    /** Speak [text] now, interrupting any current playback. No-op until the engine is ready / for blank text. */
    fun speak(text: String) {
        val e = engine ?: return
        if (!ready) return
        val say = forSpeech(text)
        if (say.isBlank()) return
        // TextToSpeech caps a single utterance (~4000 chars); take a little under to be safe.
        e.speak(say.take(3900), TextToSpeech.QUEUE_FLUSH, null, "tang-tts")
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    /** Strip the most jarring Markdown so the reader doesn't pronounce "星号星号", backticks, link URLs, etc. */
    private fun forSpeech(md: String): String = md
        .replace(Regex("```[\\s\\S]*?```"), "（代码略）")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("[*_~#>|]"), "")
        .trim()
}
