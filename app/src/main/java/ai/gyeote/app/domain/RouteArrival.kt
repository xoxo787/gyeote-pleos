package ai.gyeote.app.domain

import java.time.Instant

enum class ArrivalFailure { NETWORK, CONSENT, CONFLICT, OTHER }
enum class ArrivalMode { ASK_EVERY_TIME, AUTOMATIC }
data class QueuedArrival(val event: SharedEvent, val failure: ArrivalFailure? = null)
// The final product limit is undecided; currently ask once more at the next stop.
const val ARRIVAL_REASK_LIMIT = 1
data class ArrivalPrompt(val destinationId: String, val reasksRemaining: Int = ARRIVAL_REASK_LIMIT)

/** Foreground stopped time only; all delivery decisions are durable before HTTP is attempted. */
class RouteArrivalFlow(
    initialQueue: List<QueuedArrival> = emptyList(),
    initialHandled: Set<String> = emptySet(),
    initialPrompt: ArrivalPrompt? = null,
    initialMode: ArrivalMode = ArrivalMode.ASK_EVERY_TIME,
    initialDeferredPrompt: ArrivalPrompt? = null,
) {
    @Volatile var mode: ArrivalMode = initialMode
        private set
    @Volatile var queue: List<QueuedArrival> = initialQueue
        private set
    @Volatile var handled: Set<String> = initialHandled
        private set
    @Volatile var prompt: ArrivalPrompt? = initialPrompt
        private set
    @Volatile var deferredPrompt: ArrivalPrompt? = initialDeferredPrompt
        private set

    @Synchronized fun arrive(destination: Destination?, consent: Consent, now: Instant) {
        if (destination == null || destination.status != "accepted" || !consent.routeArrivalAllowed(now)) return
        if (destination.id in handled || queue.any { it.event.destinationId == destination.id } || prompt != null || deferredPrompt != null) return
        prompt = ArrivalPrompt(destination.id)
    }

    @Synchronized fun advance(stopped: Boolean, consent: Consent, now: Instant, destination: Destination?) {
        if (!consent.routeArrivalAllowed(now)) { cancelPrompt(); return }
        if (stopped && deferredPrompt != null) {
            val deferred = deferredPrompt!!
            if (mode == ArrivalMode.ASK_EVERY_TIME && destination?.id == deferred.destinationId && destination.status == "accepted") {
                prompt = deferred
            }
            deferredPrompt = null
        }
        if (mode == ArrivalMode.AUTOMATIC && stopped && prompt != null) confirm(consent, now, destination)
    }
    @Synchronized fun changeMode(value: ArrivalMode) { mode = value }
    @Synchronized fun depart() {
        if (mode != ArrivalMode.ASK_EVERY_TIME) return
        val current = prompt ?: return
        if (current.reasksRemaining > 0) {
            deferredPrompt = current.copy(reasksRemaining = current.reasksRemaining - 1)
            prompt = null
        } else skip()
    }

    @Synchronized fun confirm(consent: Consent, now: Instant, destination: Destination?) {
        val current = prompt ?: return
        if (!consent.routeArrivalAllowed(now) || destination?.id != current.destinationId || destination.status != "accepted") return
        val event = SharedEvent(SharedEventType.ARRIVED, now, "${destination.name}에 잘 도착했어요", destinationId = destination.id)
        queue = queue + QueuedArrival(event)
        handled = (handled + destination.id).toList().takeLast(200).toSet()
        prompt = null
        deferredPrompt = null
    }

    @Synchronized fun skip() {
        (prompt ?: deferredPrompt)?.let { handled = (handled + it.destinationId).toList().takeLast(200).toSet() }
        prompt = null
        deferredPrompt = null
    }
    @Synchronized fun cancelPrompt() { prompt = null; deferredPrompt = null }
    @Synchronized fun ready(manual: Boolean = false): List<QueuedArrival> = queue.filter {
        manual || it.failure == null || it.failure == ArrivalFailure.NETWORK
    }
    @Synchronized fun failed(id: String, failure: ArrivalFailure) {
        queue = queue.map { if (it.event.destinationId == id) it.copy(failure = failure) else it }
    }
    @Synchronized fun sent(id: String) { queue = queue.filterNot { it.event.destinationId == id } }
}
