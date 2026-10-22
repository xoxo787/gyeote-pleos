package ai.gyeote.app.data

import ai.gyeote.app.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class EdgeJsonTest {
    private val now = Instant.parse("2026-10-22T00:00:00Z")
    @Test fun historyRoundTripPreservesMetadataAndNullableDistance() {
        val reading = TelltaleReading(WarningCode.ENGINE, color = 2, priority = 4, warningCount = 3)
        val start = WarningMoment(now, null, GearSelection.UNKNOWN, false)
        val records = listOf(WarningRecord("id", reading, start, start.copy(at = now.plusSeconds(3), odometerKm = 12500.5)))
        assertEquals(records, EdgeJson.history(EdgeJson.history(records)))
    }
    @Test fun queuedFailureAndHandledIdsSurviveProcessRestart() {
        val flow = RouteArrivalFlow(listOf(QueuedArrival(SharedEvent(SharedEventType.ARRIVED, now, "도착", destinationId = "dest"),
            ArrivalFailure.CONFLICT)), setOf("dest"), ArrivalPrompt("other"), ArrivalMode.AUTOMATIC)
        val restored = EdgeJson.arrival(EdgeJson.arrival(flow))
        assertEquals(flow.queue, restored.queue); assertEquals(flow.handled, restored.handled)
        assertEquals(flow.prompt, restored.prompt)
        assertEquals(ArrivalMode.AUTOMATIC, restored.mode)
    }
    @Test fun oldEncryptedSchemaDefaultsToNoSharingAndEmptyQueue() {
        assertFalse(ApiJson.consent(JSONObject().put("shareArrival", true)).shareRouteArrival)
        assertTrue(EdgeJson.arrival(null).queue.isEmpty())
        assertEquals(ArrivalMode.ASK_EVERY_TIME, EdgeJson.arrival(JSONObject()).mode)
    }
    @Test fun deferredReaskSurvivesRestartWithoutResettingItsAllowance() {
        val consent = Consent(recipients = setOf("child"), shareRouteArrival = true)
        val destination = Destination("dest", "child", "병원", "서울", 37.0, 127.0, "", "accepted")
        val flow = RouteArrivalFlow().also { it.arrive(destination, consent, now); it.depart() }
        val restored = EdgeJson.arrival(EdgeJson.arrival(flow))
        assertNull(restored.prompt)
        assertEquals(ArrivalPrompt("dest", 0), restored.deferredPrompt)
        restored.advance(true, consent, now.plusSeconds(60), destination)
        restored.depart()
        val completed = EdgeJson.arrival(EdgeJson.arrival(restored))
        completed.arrive(destination, consent, now.plusSeconds(120))
        assertNull(completed.prompt); assertNull(completed.deferredPrompt)
        assertTrue(completed.queue.isEmpty()); assertTrue("dest" in completed.handled)
    }
    @Test fun oldPromptSchemaReceivesTheDefaultReaskAllowance() {
        val restored = EdgeJson.arrival(JSONObject("""{"prompt":{"destinationId":"dest"}}"""))
        assertEquals(ArrivalPrompt("dest", ARRIVAL_REASK_LIMIT), restored.prompt)
        assertNull(restored.deferredPrompt)
    }
}
