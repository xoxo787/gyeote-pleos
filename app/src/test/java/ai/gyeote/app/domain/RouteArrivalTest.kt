package ai.gyeote.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RouteArrivalTest {
    private val now = Instant.parse("2026-10-22T00:00:00Z")
    private val destination = Destination("dest", "child", "서울 중앙병원", "서울", 37.5, 127.0, "천천히 오세요", "accepted")
    private val consent = Consent(recipients = setOf("child"), shareRouteArrival = true)
    private fun offered() = RouteArrivalFlow().also { it.arrive(destination, consent, now) }

    @Test fun onlyAcceptedDestinationOffersArrival() {
        val flow = RouteArrivalFlow()
        flow.arrive(null, consent, now); assertNull(flow.prompt)
        flow.arrive(destination.copy(status = "pending"), consent, now); assertNull(flow.prompt)
        flow.arrive(destination, consent, now); assertNotNull(flow.prompt)
    }
    @Test fun consentOffOrNoRecipientsNeverOffersOrSends() {
        listOf(Consent(), consent.copy(shareRouteArrival = false), consent.copy(recipients = emptySet()), consent.copy(until = now)).forEach {
            val flow = RouteArrivalFlow(); flow.arrive(destination, it, now)
            assertNull(flow.prompt); assertTrue(flow.queue.isEmpty())
        }
    }
    @Test fun unansweredCardNeverQueuesEvenAfterLongWait() {
        val flow = offered()
        flow.advance(true, consent, now.plusSeconds(10), destination)
        flow.advance(true, consent, now.plusSeconds(3600), destination)
        assertNotNull(flow.prompt); assertTrue(flow.queue.isEmpty())
        flow.confirm(consent, now.plusSeconds(3600), destination)
        assertNull(flow.prompt); assertEquals(1, flow.queue.size)
        assertEquals("dest", flow.queue.single().event.destinationId)
        assertEquals("서울 중앙병원에 잘 도착했어요", flow.queue.single().event.summary)
    }
    @Test fun movingTimeDoesNotCountTowardsAutomaticSend() {
        val flow = offered(); flow.changeMode(ArrivalMode.AUTOMATIC)
        flow.advance(false, consent, now.plusSeconds(90), destination)
        assertNotNull(flow.prompt); assertTrue(flow.queue.isEmpty())
    }
    @Test fun explicitConfirmationQueuesWithoutWaiting() {
        val flow = offered(); flow.confirm(consent, now, destination)
        assertEquals(1, flow.queue.size); assertNull(flow.prompt)
    }
    @Test fun skipNeverQueuesAndDuplicateCallbackCannotReopenPrompt() {
        val flow = offered(); flow.skip(); flow.arrive(destination, consent, now)
        assertNull(flow.prompt); assertTrue(flow.queue.isEmpty())
    }
    @Test fun withdrawalDuringCountdownCancelsCardAndSend() {
        val flow = offered(); flow.advance(true, Consent(), now, destination)
        flow.confirm(Consent(), now, destination)
        assertNull(flow.prompt); assertTrue(flow.queue.isEmpty())
    }
    @Test fun destinationMismatchCannotConfirm() {
        val flow = offered(); flow.confirm(consent, now, destination.copy(id = "other"))
        assertTrue(flow.queue.isEmpty()); assertNotNull(flow.prompt)
    }
    @Test fun failureRemainsQueuedAndConflictNeedsManualRetry() {
        val flow = offered(); flow.confirm(consent, now, destination)
        flow.failed("dest", ArrivalFailure.NETWORK); assertEquals(1, flow.ready().size)
        flow.failed("dest", ArrivalFailure.CONFLICT); assertTrue(flow.ready().isEmpty())
        assertEquals(1, flow.ready(manual = true).size)
        flow.failed("dest", ArrivalFailure.CONSENT); assertEquals(1, flow.queue.size)
        assertTrue(flow.ready().isEmpty())
    }
    @Test fun successRemovesRetryAndDuplicateArrivalCannotSendAgain() {
        val flow = offered(); flow.confirm(consent, now, destination); flow.sent("dest")
        flow.arrive(destination, consent, now)
        assertTrue(flow.queue.isEmpty()); assertNull(flow.prompt)
    }
    @Test fun routeConsentDoesNotEnableEveryArrivalOrDeparture() {
        assertNull(consent.filter(SharedEvent(SharedEventType.ARRIVED, now, "일반 도착"), now))
        assertNull(consent.filter(SharedEvent(SharedEventType.DEPARTED, now, "출발"), now))
        assertNotNull(consent.filter(SharedEvent(SharedEventType.ARRIVED, now, "길 도착", destinationId = "dest"), now))
    }
    @Test fun routeWithdrawalPurgesOldSharedData() {
        assertTrue(consent.copy(shareRouteArrival = false).narrowerThan(consent))
    }
    @Test fun arrivedDestinationSurvivesInboxRestore() {
        val inbox = DestinationInbox(); inbox.restore(listOf(destination)); inbox.arrived("dest")
        val restored = DestinationInbox(); restored.restore(inbox.snapshot())
        assertEquals("arrived", restored.find("dest")!!.status); assertNull(restored.accepted())
    }
    @Test fun missingPreferenceDefaultsToAskEveryTime() {
        val flow = offered()
        assertEquals(ArrivalMode.ASK_EVERY_TIME, flow.mode)
        flow.advance(true, consent, now.plusSeconds(100), destination)
        assertTrue(flow.queue.isEmpty())
    }
    @Test fun automaticModeQueuesImmediatelyWhenStoppedWithoutAResponse() {
        val flow = offered(); flow.changeMode(ArrivalMode.AUTOMATIC)
        flow.advance(true, consent, now, destination)
        assertNull(flow.prompt); assertEquals(now, flow.queue.single().event.at)
        flow.arrive(destination, consent, now); flow.advance(true, consent, now, destination)
        assertEquals(1, flow.queue.size)
    }
    @Test fun unansweredDepartureReasksOnceAndExplicitResponseQueuesExactlyOneEvent() {
        val flow = offered(); flow.depart()
        assertNull(flow.prompt); assertTrue(flow.queue.isEmpty())
        flow.arrive(destination, consent, now.plusSeconds(60))
        flow.advance(true, consent, now.plusSeconds(60), destination)
        assertNotNull(flow.prompt); assertEquals(0, flow.prompt!!.reasksRemaining)
        assertTrue(flow.queue.isEmpty())
        flow.confirm(consent, now.plusSeconds(61), destination)
        flow.confirm(consent, now.plusSeconds(62), destination)
        flow.arrive(destination, consent, now.plusSeconds(63))
        assertNull(flow.prompt); assertEquals(1, flow.queue.size)
        assertEquals("dest", flow.queue.single().event.destinationId)
    }
    @Test fun unansweredReaskClosesSilentlyWithoutSendingOrOpeningAtLaterStops() {
        val flow = offered(); flow.depart()
        flow.advance(true, consent, now.plusSeconds(60), destination)
        flow.depart()
        repeat(3) {
            flow.arrive(destination, consent, now.plusSeconds(120 + it.toLong()))
            flow.advance(true, consent, now.plusSeconds(120 + it.toLong()), destination)
        }
        assertNull(flow.prompt); assertTrue(flow.queue.isEmpty())
        assertNull(flow.deferredPrompt); assertTrue("dest" in flow.handled)
    }
    @Test fun duplicateDepartAndArrivalCallbacksDoNotConsumeExtraReasksOrRestoreWhileMoving() {
        val flow = offered(); flow.depart(); flow.depart()
        flow.arrive(destination, consent, now)
        flow.advance(false, consent, now, destination)
        assertNull(flow.prompt); assertNotNull(flow.deferredPrompt)
        assertTrue(flow.queue.isEmpty())
        flow.advance(true, consent, now, destination)
        assertEquals("dest", flow.prompt!!.destinationId)
        assertEquals(0, flow.prompt!!.reasksRemaining)
    }
    @Test fun consentWithdrawalAlsoCancelsDeferredReask() {
        val flow = offered(); flow.depart()
        flow.advance(true, Consent(), now, destination)
        assertNull(flow.prompt); assertNull(flow.deferredPrompt); assertTrue(flow.queue.isEmpty())
    }
}
