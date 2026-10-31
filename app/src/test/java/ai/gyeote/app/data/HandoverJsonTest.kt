package ai.gyeote.app.data

import ai.gyeote.app.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HandoverJsonTest {
    private val now = Instant.parse("2026-10-26T05:00:00Z")
    @Test fun snapshotRoundTripKeepsNullsAsUnknown() {
        listOf(
            HandoverSnapshot(now, 54, 12_345.0, listOf("TPMS"), false, false, true),
            HandoverSnapshot(now, null, null, emptyList(), null, null, null),
        ).forEach { snapshot ->
            // Same path as SecureStore: JSON text inside the encrypted state, parsed back.
            val restored = HandoverJson.snapshot(JSONObject(HandoverJson.snapshot(snapshot).toString()))
            assertEquals(snapshot, restored)
        }
    }
    @Test fun storedSnapshotContainsVehicleStateKeysOnly() {
        val json = HandoverJson.snapshot(HandoverSnapshot(now, 54, 12_345.0, listOf("TPMS"), false, null, true))
        assertEquals(HandoverJson.SNAPSHOT_KEYS, json.keys().asSequence().toSet())
        val personal = listOf("name", "driver", "user", "account", "phone", "location", "lat", "lng", "note", "speed")
        assertTrue(HandoverJson.SNAPSHOT_KEYS.none { key -> personal.any { key.contains(it, ignoreCase = true) } })
    }
    @Test fun missingOrBrokenSnapshotRestoresAsNone() {
        assertNull(HandoverJson.snapshot(null))
        assertNull(HandoverJson.snapshot(JSONObject().put("socPercent", 54)))
        assertNull(HandoverJson.snapshot(JSONObject().put("at", "not-a-time")))
    }
    @Test fun olderSnapshotWithoutOptionalKeysStillRestores() {
        val restored = HandoverJson.snapshot(JSONObject().put("at", now.toString()))
        assertEquals(HandoverSnapshot(now, null, null, emptyList(), null, null, null), restored)
    }
    @Test fun settingsRoundTripAndDefaults() {
        HandoverMode.entries.forEach { mode ->
            HandoverLength.entries.forEach { length ->
                val value = HandoverSettings(mode, length)
                assertEquals(value, HandoverJson.settings(JSONObject(HandoverJson.settings(value).toString())))
            }
        }
        assertEquals(HandoverSettings(), HandoverJson.settings(null))
        assertEquals(HandoverSettings(HandoverMode.RENTAL, HandoverLength.SHORT), HandoverJson.settings(JSONObject().put("mode", "UNKNOWN_MODE")))
        assertEquals(HandoverSettings(HandoverMode.FAMILY, HandoverLength.SHORT), HandoverJson.settings(JSONObject().put("mode", "FAMILY")))
    }
    @Test fun valuesStoredBeforeRelabelStillRestore() {
        // Stored names are unchanged by the 10-31 relabel; a stored length wins over the new rental default.
        assertEquals(HandoverSettings(HandoverMode.FLEET, HandoverLength.NORMAL), HandoverJson.settings(JSONObject().put("mode", "FLEET")))
        assertEquals(HandoverSettings(HandoverMode.RENTAL, HandoverLength.NORMAL),
            HandoverJson.settings(JSONObject().put("mode", "RENTAL").put("length", "NORMAL")))
        assertEquals(HandoverSettings(HandoverMode.RENTAL, HandoverLength.SHORT), HandoverJson.settings(JSONObject().put("mode", "RENTAL")))
    }
}
