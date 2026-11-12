package ai.gyeote.buzzer.vehicle

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Reads the game's lines aloud, one after another. If the device has no local Korean voice
 * (the Pleos Connect 2.1.0 emulator has none) the game simply stays on-screen only.
 */
class Voice(context: Context) {
    private var ready = false
    private var closed = false
    private val waiting = mutableListOf<String>()
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private lateinit var engine: TextToSpeech

    init {
        engine = TextToSpeech(context.applicationContext) { result ->
            if (closed) return@TextToSpeech
            val voice = if (result == TextToSpeech.SUCCESS) {
                engine.voices?.firstOrNull { it.locale.language == "ko" && !it.isNetworkConnectionRequired }
            } else null
            if (voice != null) {
                engine.language = Locale.KOREAN
                engine.voice = voice
                engine.setAudioAttributes(attributes)
                ready = true
                carLog("tts=ready")
                waiting.forEach(::speak)
            } else carLog("tts=unavailable (no local Korean voice)")
            waiting.clear()
        }
    }

    fun say(lines: List<String>) {
        if (closed || lines.isEmpty()) return
        carLog("say count=${lines.size} first=${lines.first()}")
        if (ready) lines.forEach(::speak) else waiting += lines
    }

    private fun speak(line: String) {
        engine.speak(line, TextToSpeech.QUEUE_ADD, null, "buzzer-${line.hashCode()}")
    }

    /** Drops everything queued. Used when driving closes the game, so only the closing line is heard. */
    fun hush() {
        waiting.clear()
        if (ready) engine.stop()
    }

    fun close() {
        closed = true
        waiting.clear()
        runCatching { engine.stop(); engine.shutdown() }
    }
}
