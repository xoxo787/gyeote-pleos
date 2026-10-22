package ai.gyeote.app.data

import ai.gyeote.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Additive keys inside the existing encrypted state; old state defaults to empty/off. */
object EdgeJson {
    private fun moment(value: WarningMoment) = JSONObject().apply {
        put("at", value.at.toString()); put("odometerKm", value.odometerKm ?: JSONObject.NULL)
        put("gear", value.gear.name); put("moving", value.moving)
    }
    private fun moment(value: JSONObject) = WarningMoment(Instant.parse(value.getString("at")),
        if (value.isNull("odometerKm")) null else value.getDouble("odometerKm"),
        GearSelection.valueOf(value.getString("gear")), value.getBoolean("moving"))
    fun history(records: List<WarningRecord>) = JSONArray().apply {
        records.forEach { record -> put(JSONObject().apply {
            put("id", record.id); put("code", record.reading.code.name); put("sdkName", record.reading.sdkName)
            put("color", record.reading.color ?: JSONObject.NULL)
            put("colorName", record.reading.colorName ?: JSONObject.NULL)
            put("priority", record.reading.priority ?: JSONObject.NULL)
            put("warningCount", record.reading.warningCount ?: JSONObject.NULL)
            put("started", moment(record.started)); put("ended", record.ended?.let(::moment) ?: JSONObject.NULL)
        }) }
    }
    fun history(values: JSONArray): List<WarningRecord> = (0 until values.length()).map { index ->
        val value = values.getJSONObject(index)
        fun optionalInt(key: String): Int? = if (value.isNull(key)) null else value.getInt(key)
        WarningRecord(value.getString("id"), TelltaleReading(WarningCode.valueOf(value.getString("code")),
            value.getString("sdkName"), optionalInt("color"), optionalInt("priority"), optionalInt("warningCount"),
            value.optString("colorName").takeIf { it.isNotBlank() && it != "null" }),
            moment(value.getJSONObject("started")), value.optJSONObject("ended")?.let(::moment))
    }
    fun arrival(flow: RouteArrivalFlow): JSONObject = synchronized(flow) { JSONObject().apply {
        fun prompt(value: ArrivalPrompt?) = value?.let {
            JSONObject().put("destinationId", it.destinationId).put("reasksRemaining", it.reasksRemaining)
        } ?: JSONObject.NULL
        put("handled", JSONArray(flow.handled.toList()))
        put("mode", flow.mode.name)
        put("prompt", prompt(flow.prompt))
        put("deferredPrompt", prompt(flow.deferredPrompt))
        put("queue", JSONArray().apply { flow.queue.forEach { item ->
            put(JSONObject().put("event", ApiJson.event(item.event)).put("failure", item.failure?.name ?: JSONObject.NULL))
        } })
    } }
    fun arrival(value: JSONObject?): RouteArrivalFlow {
        if (value == null) return RouteArrivalFlow()
        fun prompt(key: String) = value.optJSONObject(key)?.let {
            ArrivalPrompt(it.getString("destinationId"), it.optInt("reasksRemaining", ARRIVAL_REASK_LIMIT).coerceIn(0, ARRIVAL_REASK_LIMIT))
        }
        val values = value.optJSONArray("queue") ?: JSONArray()
        val handled = value.optJSONArray("handled") ?: JSONArray()
        return RouteArrivalFlow((0 until values.length()).map { index ->
            val item = values.getJSONObject(index)
            val event = item.getJSONObject("event")
            QueuedArrival(SharedEvent(SharedEventType.ARRIVED, Instant.parse(event.getString("at")), event.getString("summary"),
                destinationId = event.getString("destinationId")),
                item.optString("failure").takeIf { it.isNotBlank() && it != "null" }?.let(ArrivalFailure::valueOf))
        }, (0 until handled.length()).map { handled.getString(it) }.toSet(),
            prompt("prompt"),
            value.optString("mode").takeIf { it in ArrivalMode.entries.map(ArrivalMode::name) }?.let(ArrivalMode::valueOf)
                ?: ArrivalMode.ASK_EVERY_TIME, prompt("deferredPrompt"))
    }
}
