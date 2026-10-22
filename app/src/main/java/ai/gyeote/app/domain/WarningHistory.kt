package ai.gyeote.app.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

data class TelltaleReading(
    val code: WarningCode,
    val sdkName: String = code.name,
    val color: Int? = null,
    val priority: Int? = null,
    val warningCount: Int? = null,
    val colorName: String? = null,
) {
    val advice: WarningAdvice get() = WarningDictionary.lookup(code, red = colorName == "RED")
    val colorLabel: String get() = when (colorName) {
        "RED" -> "빨강"; "YELLOW" -> "노랑"; "GREEN" -> "초록"; "BLUE" -> "파랑"
        "WHITE" -> "흰색"; "GRAY" -> "회색"
        else -> color?.let { "SDK $it" } ?: "미수신"
    }
}

data class WarningMoment(
    val at: Instant,
    val odometerKm: Double?,
    val gear: GearSelection,
    val moving: Boolean,
)

data class WarningRecord(
    val id: String,
    val reading: TelltaleReading,
    val started: WarningMoment,
    val ended: WarningMoment? = null,
) {
    val duration: Duration? get() = ended?.let { Duration.between(started.at, it.at).coerceAtLeast(Duration.ZERO) }
}

/** Snapshot transitions from both SDK paths; the same snapshot never creates another occurrence. */
class WarningHistory(initial: List<WarningRecord> = emptyList()) {
    var records: List<WarningRecord> = initial
        private set

    fun retain(now: Instant): Boolean {
        val cutoff = now.minus(Duration.ofDays(30))
        val next = records.filter { !it.started.at.isBefore(cutoff) }.sortedBy { it.started.at }.takeLast(200)
        return (next != records).also { records = next }
    }

    fun sample(readings: List<TelltaleReading>, moment: WarningMoment): Boolean {
        val before = records
        retain(moment.at)
        val active = readings.associateBy { it.sdkName }
        records = records.map { record ->
            if (record.ended != null || moment.at.isBefore(record.started.at)) record else {
                val current = active[record.reading.sdkName]
                if (current == null) record.copy(ended = moment)
                else record.copy(reading = current)
            }
        }
        val openNames = records.filter { it.ended == null }.map { it.reading.sdkName }.toSet()
        records = records + active.values.filter { it.sdkName !in openNames }.map {
            WarningRecord(UUID.randomUUID().toString(), it, moment)
        }
        retain(moment.at)
        return before != records
    }

    fun clear() { records = emptyList() }
}

data class WarningGroup(val records: List<WarningRecord>) {
    val first get() = records.first()
    val last get() = records.last()
}

object WarningHistoryText {
    fun mechanicDetails(reading: TelltaleReading): String =
        "${standardName(reading.code)} (${reading.sdkName})\n${reading.advice.urgency.label} · ${reading.colorLabel}" +
            (reading.warningCount?.takeIf { it >= 1 }?.let { " · 차량이 알린 횟수 ${it}회" } ?: "")

    fun group(records: List<WarningRecord>, gap: Duration = Duration.ofMinutes(5)): List<WarningGroup> {
        val groups = mutableListOf<MutableList<WarningRecord>>()
        records.sortedBy { it.started.at }.forEach { record ->
            val previous = groups.lastOrNull { group ->
                group.last().reading.sdkName == record.reading.sdkName &&
                    group.last().reading.color == record.reading.color &&
                    group.last().started.moving == record.started.moving &&
                    group.last().ended?.let { end ->
                        !record.started.at.isBefore(end.at) && Duration.between(end.at, record.started.at) <= gap
                    } == true
            }
            if (previous != null) previous += record else groups += mutableListOf(record)
        }
        return groups.map { WarningGroup(it) }.sortedByDescending { it.first.started.at }
    }

    fun standardName(code: WarningCode): String = when (code) {
        WarningCode.ENGINE -> "엔진 경고등"
        WarningCode.TPMS -> "타이어 공기압 경고등"
        WarningCode.ENGINE_OIL_PRESSURE -> "엔진 오일 압력 경고등"
        WarningCode.ENGINE_TEMPERATURE -> "엔진 냉각수 온도 경고등"
        WarningCode.ABS -> "ABS 경고등"
        WarningCode.AIRBAG -> "에어백 경고등"
        else -> WarningDictionary.lookup(code).title + " 경고등"
    }

    fun duration(value: Duration?): String = when {
        value == null -> "계속 켜짐"
        value.seconds < 60 -> "${value.seconds}초"
        value.toMinutes() < 60 -> "${value.toMinutes()}분"
        else -> "${value.toHours()}시간 ${value.toMinutes() % 60}분"
    }

    fun parent(group: WarningGroup, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = DateTimeFormatter.ofPattern("M월 d일 a", Locale.KOREAN).withZone(zone).format(group.first.started.at)
        val state = if (group.records.any { it.started.moving }) "달리는 중에" else "정차 중에"
        val name = when (group.first.reading.code) {
            WarningCode.ENGINE -> "엔진 경고"
            else -> WarningDictionary.lookup(group.first.reading.code).title + " 경고"
        }
        val count = "${group.records.size}번"
        val ending = group.last.ended?.let {
            "켜졌다가 ${duration(Duration.between(group.first.started.at, it.at))} 뒤 꺼졌어요."
        } ?: "켜졌어요. 아직 켜져 있어요."
        return "$date, $state $name 가 $count $ending".replace("경고 가", "경고가")
    }
}
