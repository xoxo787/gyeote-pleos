package ai.gyeote.app.data

import ai.gyeote.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Additive keys inside the existing encrypted state. The snapshot holds vehicle state only. */
object HandoverJson {
    /** Every key a stored snapshot may contain; tests keep person data out of it. */
    val SNAPSHOT_KEYS = setOf("at", "socPercent", "odometerKm", "activeWarnings", "doorOpen", "trunkOpen", "chargePortOpen", "windowOpen")
    val PAIR_KEYS = setOf("takeover", "returned")
    val TRIP_KEYS = setOf("takeoverAt", "returnedAt", "startKm", "endKm", "destination")

    private fun JSONObject.nullable(key: String, value: Any?) = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.optionalBoolean(key: String): Boolean? = if (!has(key) || isNull(key)) null else getBoolean(key)

    fun snapshot(value: HandoverSnapshot) = JSONObject().apply {
        put("at", value.at.toString())
        nullable("socPercent", value.socPercent)
        nullable("odometerKm", value.odometerKm)
        put("activeWarnings", JSONArray(value.activeWarnings))
        nullable("doorOpen", value.doorOpen)
        nullable("trunkOpen", value.trunkOpen)
        nullable("chargePortOpen", value.chargePortOpen)
        nullable("windowOpen", value.windowOpen)
    }

    fun snapshot(value: JSONObject?): HandoverSnapshot? {
        if (value == null) return null
        return runCatching {
            val warnings = value.optJSONArray("activeWarnings") ?: JSONArray()
            HandoverSnapshot(
                Instant.parse(value.getString("at")),
                if (!value.has("socPercent") || value.isNull("socPercent")) null else value.getInt("socPercent"),
                if (!value.has("odometerKm") || value.isNull("odometerKm")) null else value.getDouble("odometerKm"),
                (0 until warnings.length()).map { warnings.getString(it) },
                value.optionalBoolean("doorOpen"), value.optionalBoolean("trunkOpen"), value.optionalBoolean("chargePortOpen"),
                value.optionalBoolean("windowOpen"),
            )
        }.getOrNull()
    }

    private fun JSONObject.optionalDouble(key: String): Double? = if (!has(key) || isNull(key)) null else getDouble(key)

    fun pairs(values: List<HandoverPair>) = JSONArray().apply {
        values.forEach { put(JSONObject().put("takeover", snapshot(it.takeover)).put("returned", snapshot(it.returned))) }
    }

    /** Broken entries are skipped, never guessed. */
    fun pairs(values: JSONArray?): List<HandoverPair> {
        if (values == null) return emptyList()
        return (0 until values.length()).mapNotNull { i ->
            val item = values.optJSONObject(i) ?: return@mapNotNull null
            val takeover = snapshot(item.optJSONObject("takeover")) ?: return@mapNotNull null
            val returned = snapshot(item.optJSONObject("returned")) ?: return@mapNotNull null
            HandoverPair(takeover, returned)
        }
    }

    fun trips(values: List<TripEntry>) = JSONArray().apply {
        values.forEach {
            put(JSONObject().apply {
                put("takeoverAt", it.takeoverAt.toString()); put("returnedAt", it.returnedAt.toString())
                nullable("startKm", it.startKm); nullable("endKm", it.endKm); nullable("destination", it.destination)
            })
        }
    }

    fun trips(values: JSONArray?): List<TripEntry> {
        if (values == null) return emptyList()
        return (0 until values.length()).mapNotNull { i ->
            val item = values.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                TripEntry(Instant.parse(item.getString("takeoverAt")), Instant.parse(item.getString("returnedAt")),
                    item.optionalDouble("startKm"), item.optionalDouble("endKm"),
                    if (!item.has("destination") || item.isNull("destination")) null else item.getString("destination"))
            }.getOrNull()
        }
    }

    fun settings(value: HandoverSettings) = JSONObject().put("mode", value.mode.name).put("length", value.length.name)

    fun settings(value: JSONObject?): HandoverSettings {
        if (value == null) return HandoverSettings()
        val mode = HandoverMode.entries.firstOrNull { it.name == value.optString("mode") } ?: HandoverMode.RENTAL
        val length = HandoverLength.entries.firstOrNull { it.name == value.optString("length") } ?: mode.defaultLength
        return HandoverSettings(mode, length)
    }
}
