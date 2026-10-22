package ai.gyeote.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class ConsentTest {
    private val now = Instant.parse("2026-10-22T00:00:00Z")
    private val event = SharedEvent(SharedEventType.DEPARTED, now, "출발했어요", Coordinates(37.0, 127.0))
    private val enabled = Consent(shareDeparture = true, recipients = setOf("child"))
    private val saved = mutableListOf<Pair<Consent, Boolean>>()
    private fun coordinator(initial: Consent = Consent(), pending: Boolean = false) =
        ConsentCoordinator(initial, pending, object : ConsentPersistence {
            override fun save(consent: Consent, purgePending: Boolean) { saved += consent to purgePending }
        })
    private class Remote : SharingRemote {
        val calls = mutableListOf<String>()
        val events = mutableListOf<SharedEvent>()
        var failDeletion = false
        override fun putConsent(consent: Consent) { calls += if (consent == Consent()) "off" else "consent" }
        override fun deleteSharedData() { calls += "delete"; if (failDeletion) throw IOException("offline") }
        override fun postEvent(event: SharedEvent) { calls += "event"; events += event }
    }
    @Test fun allDefaultsBlockSharing() {
        val consent = Consent()
        assertFalse(consent.shareDeparture || consent.shareArrival || consent.shareAlerts || consent.shareLocation)
        SharedEventType.entries.forEach { assertFalse(consent.allows(it, now)) }
    }
    @Test fun noRecipientCannotShareEvenWhenSwitchIsOn() {
        assertNull(enabled.copy(recipients = emptySet()).filter(event, now))
    }
    @Test fun departureDoesNotAuthorizeArrivalOrAlerts() {
        assertNotNull(enabled.filter(event, now))
        assertFalse(enabled.allows(SharedEventType.ARRIVED, now))
        assertFalse(enabled.allows(SharedEventType.ALERT, now))
    }
    @Test fun exactExpiryIsClosedAndEarlierMomentAllowed() {
        val limited = enabled.copy(until = now)
        assertFalse(limited.allows(SharedEventType.DEPARTED, now))
        assertTrue(limited.allows(SharedEventType.DEPARTED, now.minusMillis(1)))
    }
    @Test fun coordinatesNeedSeparatePermission() {
        assertNull(enabled.filter(event, now)?.location)
        assertEquals(event.location, enabled.copy(shareLocation = true).filter(event, now)?.location)
    }
    @Test fun localRevocationPrecedesNetworkAndPurgesStoredEvents() {
        val coordinator = coordinator(enabled)
        val remote = Remote()
        coordinator.update(Consent(), forcePurge = true)
        assertEquals(Consent(), coordinator.current)
        assertTrue(saved.last().second)
        assertFalse(coordinator.send(remote, event, now))
        coordinator.flush(remote)
        assertEquals(listOf("off", "delete", "off"), remote.calls)
        assertFalse(coordinator.deletionPending)
    }
    @Test fun failedErasureSurvivesRestartAndBlocksReenableUntilRetrySucceeds() {
        val remote = Remote().apply { failDeletion = true }
        val first = coordinator(enabled)
        first.update(Consent(), forcePurge = true)
        runCatching { first.flush(remote) }
        assertTrue(first.deletionPending)
        val restored = coordinator(saved.last().first, saved.last().second)
        restored.update(enabled)
        assertTrue(runCatching { restored.send(remote, event, now) }.isFailure)
        assertTrue(remote.events.isEmpty())
        remote.failDeletion = false
        assertTrue(restored.send(remote, event, now))
        assertEquals(1, remote.events.size)
        assertNull(remote.events.single().location)
    }
    @Test fun removingRecipientOrLocationOrShorteningPeriodRequiresErasure() {
        assertTrue(enabled.copy(recipients = emptySet()).narrowerThan(enabled))
        assertTrue(enabled.narrowerThan(enabled.copy(shareLocation = true)))
        assertTrue(enabled.copy(until = now.plusSeconds(60)).narrowerThan(enabled))
        assertFalse(enabled.copy(recipients = setOf("child", "other")).narrowerThan(enabled))
    }
    @Test fun disabledOfflineEventIsNeverQueuedForLaterConsent() {
        val coordinator = coordinator()
        val remote = Remote()
        assertFalse(coordinator.send(remote, event, now))
        coordinator.update(enabled); coordinator.flush(remote)
        assertTrue(remote.events.isEmpty())
    }
    @Test fun stationaryAndDrivingCadenceAndBoundedBackoff() {
        assertEquals(10_000L, PollingPolicy.delayMillis(false))
        assertEquals(30_000L, PollingPolicy.delayMillis(true))
        assertEquals(20_000L, PollingPolicy.delayMillis(false, 1))
        assertEquals(120_000L, PollingPolicy.delayMillis(true, 20))
    }
}
