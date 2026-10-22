package ai.gyeote.app.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.gyeote.app.BuildConfig
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.stt.SpeechToText

/** Explicit debug-only, on-device probe. Never registers an account or supplies server credentials. */
class PleosSpeechProbe(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    fun start() {
        if (!BuildConfig.DEBUG) return
        vehicleLog("speechProbe=start mode=ON_DEVICE noAppRegistration=true")
        runCatching {
            tts = TextToSpeech(context, ai.pleos.playground.tts.constant.Mode.ON_DEVICE).also { engine ->
                val events = object : ai.pleos.playground.tts.listener.EventListener {
                    override fun onStart() = vehicleLog("speechProbe=ttsStarted")
                    override fun onReady() = vehicleLog("speechProbe=ttsReady")
                    override fun onDone() = vehicleLog("speechProbe=ttsDone")
                    override fun onError(errorType: ai.pleos.playground.tts.constant.ErrorType) = vehicleLog("speechProbe=ttsError type=${errorType.name}")
                    override fun onStop() = Unit
                    override fun onUpdatedRms(rms: Double) = Unit
                }
                engine.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
                    override fun onSuccess() {
                        vehicleLog("speechProbe=ttsInitialized")
                        runCatching { engine.addEventListener(events); engine.setLocale(java.util.Locale.KOREAN); engine.speak("오늘도 편안하게 다녀오세요.", false) }
                            .onFailure { vehicleLog("speechProbe=ttsRequestException type=${it.javaClass.simpleName}") }
                    }
                    override fun onFailure(errorType: ai.pleos.playground.tts.constant.ErrorType) = vehicleLog("speechProbe=ttsInitializationError type=${errorType.name}")
                })
            }
        }.onFailure { vehicleLog("speechProbe=ttsException type=${it.javaClass.simpleName}") }
        runCatching {
            stt = SpeechToText(context, ai.pleos.playground.stt.constant.Mode.ON_DEVICE).also { engine ->
                val events = object : ai.pleos.playground.stt.listener.ResultListener {
                    override fun onUpdated(text: String, isFinal: Boolean) { vehicleLog("speechProbe=sttResult final=$isFinal") }
                    override fun onUpdatedEpdData(start: Long, end: Long) = Unit
                    override fun onStartedRecognition() = vehicleLog("speechProbe=sttStarted")
                    override fun onEndedRecognition() = vehicleLog("speechProbe=sttEnded")
                    override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) = vehicleLog("speechProbe=sttError type=${errorType.name}")
                    override fun onReady() = vehicleLog("speechProbe=sttReady")
                    override fun onUpdatedRms(rms: Double) = Unit
                }
                engine.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                    override fun onSuccess() {
                        vehicleLog("speechProbe=sttInitialized")
                        runCatching { engine.addListener(events); engine.setLocale(java.util.Locale.KOREAN); engine.request() }
                            .onFailure { vehicleLog("speechProbe=sttRequestException type=${it.javaClass.simpleName}") }
                    }
                    override fun onFailure(errorType: ai.pleos.playground.stt.constant.ErrorType) = vehicleLog("speechProbe=sttInitializationError type=${errorType.name}")
                })
            }
        }.onFailure { vehicleLog("speechProbe=sttException type=${it.javaClass.simpleName}") }
        main.postDelayed(::close, 15_000)
    }
    fun close() {
        main.removeCallbacksAndMessages(null)
        runCatching { tts?.stop(); tts?.release() }; tts = null
        runCatching { stt?.stop(); stt?.release() }; stt = null
        vehicleLog("speechProbe=closed")
    }
}
