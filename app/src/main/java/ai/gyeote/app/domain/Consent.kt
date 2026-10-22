package ai.gyeote.app.domain

import java.time.Instant

enum class SharedEventType(val wire: String) { DEPARTED("departed"), ARRIVED("arrived"), ALERT("alert") }
data class Coordinates(val lat: Double, val lng: Double)
data class SharedEvent(val type: SharedEventType, val at: Instant, val summary: String, val location: Coordinates? = null,
    val destinationId: String? = null)
data class Consent(
    val shareDeparture: Boolean = false,
    val shareArrival: Boolean = false,
    val shareAlerts: Boolean = false,
    val shareLocation: Boolean = false,
    val recipients: Set<String> = emptySet(),
    val until: Instant? = null,
    val shareRouteArrival: Boolean = false,
) {
    fun routeArrivalAllowed(now: Instant): Boolean = shareRouteArrival && recipients.isNotEmpty() &&
        (until == null || now.isBefore(until))
    fun allows(type: SharedEventType, now: Instant): Boolean = recipients.isNotEmpty() &&
        (until == null || now.isBefore(until)) && when (type) {
            SharedEventType.DEPARTED -> shareDeparture
            SharedEventType.ARRIVED -> shareArrival
            SharedEventType.ALERT -> shareAlerts
        }
    fun filter(event: SharedEvent, now: Instant): SharedEvent? = if (
        if (event.type == SharedEventType.ARRIVED && event.destinationId != null)
            routeArrivalAllowed(now) || allows(SharedEventType.ARRIVED, now) else allows(event.type, now))
        event.copy(location = event.location.takeIf { shareLocation }) else null
    fun narrowerThan(old: Consent): Boolean =
        (old.shareDeparture && !shareDeparture) || (old.shareArrival && !shareArrival) ||
        (old.shareAlerts && !shareAlerts) || (old.shareLocation && !shareLocation) ||
        (old.shareRouteArrival && !shareRouteArrival) ||
        !recipients.containsAll(old.recipients) ||
        (until != null && (old.until == null || until < old.until))
}

interface ConsentPersistence {
    fun save(consent: Consent, purgePending: Boolean)
}
interface SharingRemote {
    fun putConsent(consent: Consent)
    fun deleteSharedData()
    fun postEvent(event: SharedEvent)
}

/** Local refusal is immediate; failed server erasure remains durable and blocks sharing. */
class ConsentCoordinator(
    initial: Consent = Consent(),
    purgePending: Boolean = false,
    private val persistence: ConsentPersistence,
) {
    @Volatile var current: Consent = initial
        private set
    @Volatile var deletionPending: Boolean = purgePending
        private set
    @Volatile var dirty: Boolean = true
        private set
    private var revision = 0L

    @Synchronized fun update(next: Consent, forcePurge: Boolean = false) {
        deletionPending = deletionPending || forcePurge || next.narrowerThan(current)
        current = next
        dirty = true
        revision++
        persistence.save(current, deletionPending)
    }

    fun flush(remote: SharingRemote) {
        val snapshot: Triple<Consent, Boolean, Long> = synchronized(this) {
            if (!dirty && !deletionPending) return
            Triple(current, deletionPending, revision)
        }
        if (snapshot.second) {
            remote.putConsent(Consent())
            remote.deleteSharedData()
        }
        remote.putConsent(snapshot.first)
        synchronized(this) {
            if (revision == snapshot.third) {
                deletionPending = false
                dirty = false
                persistence.save(current, false)
            }
        }
    }

    fun send(remote: SharingRemote, event: SharedEvent, now: Instant): Boolean {
        if (current.filter(event, now) == null) return false
        flush(remote)
        if (dirty || deletionPending) return false
        val allowed = current.filter(event, now) ?: return false
        remote.postEvent(allowed)
        return true
    }
}

object PollingPolicy {
    fun delayMillis(restricted: Boolean, failures: Int = 0): Long {
        val base = if (restricted) 30_000L else 10_000L
        return (base * (1L shl failures.coerceIn(0, 4))).coerceAtMost(120_000L)
    }
}
