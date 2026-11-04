package ai.gyeote.app.vehicle

import ai.gyeote.app.domain.HandoverMode

/** Main-thread service policy and a replaceable screen callback. Holds no Android objects. */
internal class RecordLifecycle(private val startService: () -> Unit, private val stopService: () -> Unit) {
    private var screen: (() -> Unit)? = null
    private var requested = false
    val screenAttached: Boolean get() = screen != null
    val serviceRequested: Boolean get() = requested

    fun attach(mode: HandoverMode, callback: () -> Unit): Subscription {
        screen = callback
        updateMode(mode)
        return Subscription { if (screen === callback) screen = null }
    }

    fun updateMode(mode: HandoverMode) {
        if (!mode.cardOn && requested) {
            requested = false
            stopService()
        } else if (mode.cardOn && screenAttached && !requested) {
            requested = true
            startService()
        }
    }

    fun serviceStopped() { requested = false }
    fun changed() { screen?.invoke() }
}
