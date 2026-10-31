package ai.gyeote.app.data

import ai.gyeote.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ReturnJsonTest {
    private val t0 = Instant.parse("2026-10-26T05:00:00Z")
    private val takeover = HandoverSnapshot(t0, 54, 12_346.0, listOf("ABS"), false, false, true)
    private val returned = HandoverSnapshot(t0.plusSeconds(2_400), 41, 12_378.0, listOf("ABS", "TPMS"), false, null, false, true)

    @Test fun pairsRoundTripWithVehicleKeysOnly() {
        val pairs = listOf(HandoverPair(takeover, returned), HandoverPair(returned, returned.copy(windowOpen = null)))
        val json = HandoverJson.pairs(pairs)
        assertEquals(pairs, HandoverJson.pairs(JSONArray(json.toString())))
        val item = json.getJSONObject(0)
        assertEquals(HandoverJson.PAIR_KEYS, item.keys().asSequence().toSet())
        assertEquals(HandoverJson.SNAPSHOT_KEYS, item.getJSONObject("returned").keys().asSequence().toSet())
        val personal = listOf("name", "driver", "user", "account", "phone", "location", "lat", "lng", "note", "speed")
        assertTrue((HandoverJson.PAIR_KEYS + HandoverJson.SNAPSHOT_KEYS).none { key -> personal.any { key.contains(it, ignoreCase = true) } })
    }
    @Test fun tripsRoundTripAndKeys() {
        val trips = listOf(TripEntry(t0, t0.plusSeconds(2_400), 12_346.0, 12_378.0, "중앙역"), TripEntry(t0, t0.plusSeconds(5), null, null))
        val json = HandoverJson.trips(trips)
        assertEquals(trips, HandoverJson.trips(JSONArray(json.toString())))
        assertEquals(HandoverJson.TRIP_KEYS, json.getJSONObject(0).keys().asSequence().toSet())
    }
    @Test fun brokenEntriesAreSkipped() {
        assertTrue(HandoverJson.pairs(null).isEmpty()); assertTrue(HandoverJson.trips(null).isEmpty())
        val pairs = JSONArray().put(JSONObject().put("takeover", HandoverJson.snapshot(takeover))).put("x")
            .put(HandoverJson.pairs(listOf(HandoverPair(takeover, returned))).get(0))
        assertEquals(listOf(HandoverPair(takeover, returned)), HandoverJson.pairs(pairs))
        val trips = JSONArray().put(JSONObject().put("takeoverAt", "bad").put("returnedAt", t0.toString()))
            .put(JSONObject().put("takeoverAt", t0.toString()).put("returnedAt", t0.toString()))
        assertEquals(listOf(TripEntry(t0, t0, null, null, null)), HandoverJson.trips(trips))
    }
    @Test fun olderSnapshotWithoutWindowRestoresAsUnknown() {
        val old = JSONObject(HandoverJson.snapshot(takeover).toString()).apply { remove("windowOpen") }
        assertNull(HandoverJson.snapshot(old)!!.windowOpen)
    }
}
