package ai.gyeote.app.vehicle

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class AndroidSpeaker(context: Context) : Speaker {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var ready = false
    private var queued: String? = null
    private var closed = false
    override var status: String = "음성 준비 중"
        private set
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change < 0) stop()
        }.build()
    private lateinit var engine: TextToSpeech

    init {
        engine = TextToSpeech(context.applicationContext) { result ->
            if (closed) return@TextToSpeech
            if (result == TextToSpeech.SUCCESS) {
                val localVoice = engine.voices?.firstOrNull { it.locale.language == "ko" && !it.isNetworkConnectionRequired }
                if (localVoice != null) {
                    engine.language = Locale.KOREAN
                    engine.voice = localVoice
                    engine.setAudioAttributes(attributes)
                    engine.setSpeechRate(0.88f)
                    ready = true
                    status = "한국어 음성 준비됨"
                    queued?.let(::speak)
                    queued = null
                } else status = "한국어 음성 데이터가 없어 화면으로 안내해요"
            } else status = "음성 엔진이 없어 화면으로 안내해요"
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            // Debug evidence only: utterance lifecycle, never the spoken text.
            override fun onStart(utteranceId: String?) { vehicleLog("tts=start") }
            override fun onDone(utteranceId: String?) { vehicleLog("tts=done"); audio.abandonAudioFocusRequest(focus) }
            @Deprecated("platform callback")
            override fun onError(utteranceId: String?) { vehicleLog("tts=error"); audio.abandonAudioFocusRequest(focus) }
        })
    }
    override fun speak(text: String) {
        if (closed) return
        if (!ready) { queued = text; vehicleLog("tts=queued status=$status"); return }
        if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gyeote-guidance")
        } else vehicleLog("tts=focusDenied")
    }
    override fun stop() { queued = null; engine.stop(); audio.abandonAudioFocusRequest(focus) }
    override fun close() { closed = true; stop(); engine.shutdown() }
}
