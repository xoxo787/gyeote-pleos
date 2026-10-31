package ai.gyeote.app.domain

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** F3: one takeover ([인수했어요]) and its return ([돌려줬어요]). Vehicle state only, nothing about people. */
data class HandoverPair(val takeover: HandoverSnapshot, val returned: HandoverSnapshot)

/** Fleet mode trip line, written automatically on [돌려줬어요]. No business/private split (outside the design). */
data class TripEntry(
    val takeoverAt: Instant,
    val returnedAt: Instant,
    val startKm: Double?,
    val endKm: Double?,
    val destination: String? = null,
) {
    /** Null when either reading is missing or the counter went backwards. */
    val distanceKm: Double? get() = if (startKm != null && endKm != null && endKm >= startKm) endKm - startKm else null
}

data class TripMonth(val month: YearMonth, val entries: List<TripEntry>) {
    val totalKm: Double get() = entries.sumOf { it.distanceKm ?: 0.0 }
}

/** Before -> now for the return card. Null = not received; never guessed. */
data class ReturnComparison(
    val socBefore: Int?,
    val socNow: Int?,
    val distanceKm: Double?,
    val newWarnings: List<WarningCode>,
    val doorOpen: Boolean?,
    val trunkOpen: Boolean?,
    val chargePortOpen: Boolean?,
    val windowOpen: Boolean?,
)

data class ReturnCard(val title: String, val lines: List<HandoverLine>)

object ReturnText {
    const val TITLE = "이번 운행을 정리했어요."
    const val NOTE_MAX = 60
    private fun km(value: Double) = String.format(Locale.KOREA, "%,.0f", value)
    private fun code(name: String) = WarningCode.entries.firstOrNull { it.name == name }

    fun compare(takeover: HandoverSnapshot, now: HandoverSnapshot): ReturnComparison {
        val before = takeover.activeWarnings.toSet()
        val fresh = now.activeWarnings.filter { it !in before }.distinct().mapNotNull(::code).sortedBy { it.ordinal }
        val distance = if (takeover.odometerKm != null && now.odometerKm != null && now.odometerKm >= takeover.odometerKm)
            now.odometerKm - takeover.odometerKm else null
        return ReturnComparison(takeover.socPercent, now.socPercent, distance, fresh,
            now.doorOpen, now.trunkOpen, now.chargePortOpen, now.windowOpen)
    }

    fun battery(c: ReturnComparison): String = when {
        c.socBefore != null && c.socNow != null -> "${c.socBefore}% → ${c.socNow}%"
        c.socNow != null -> "인수 때 확인 못 함 → ${c.socNow}%"
        c.socBefore != null -> "${c.socBefore}% → 지금 확인 못 함"
        else -> "확인하지 못했어요."
    }
    fun distance(c: ReturnComparison): String = c.distanceKm?.let { "+${km(it)}km" } ?: "확인하지 못했어요."
    fun warnings(c: ReturnComparison): String = if (c.newWarnings.isEmpty()) "0개" else
        "${c.newWarnings.size}개: " + c.newWarnings.joinToString(", ") { WarningDictionary.lookup(it).title }

    fun build(c: ReturnComparison): ReturnCard {
        val parts = listOf("문" to c.doorOpen, "트렁크" to c.trunkOpen, "충전구" to c.chargePortOpen)
        val open = parts.filter { it.second == true }.map { it.first }
        val closed = parts.filter { it.second == false }.map { it.first }
        val unknown = parts.filter { it.second == null }.map { it.first }
        val doors = buildList {
            if (open.isNotEmpty()) add("열려 있어요: ${open.joinToString(", ")}")
            if (closed.isNotEmpty()) add("닫혀 있어요: ${closed.joinToString("·")}")
            if (unknown.isNotEmpty()) add("확인하지 못했어요: ${unknown.joinToString(", ")}")
        }.joinToString("\n")
        val lines = mutableListOf(
            HandoverLine("배터리 · 인수 때 → 지금", battery(c)),
            HandoverLine("누적 주행거리", distance(c)),
            HandoverLine("새로 켜진 경고등", warnings(c)),
            HandoverLine("문·트렁크·충전구", doors),
        )
        // Windows only when the vehicle reported them.
        c.windowOpen?.let { lines += HandoverLine("창문", if (it) "열려 있는 창문이 있어요." else "모두 닫혀 있어요.") }
        return ReturnCard(TITLE, lines)
    }

    /** One line, trimmed, at most [NOTE_MAX] characters; blank means no note. */
    fun cleanNote(text: String?): String? =
        text?.replace(Regex("\\s+"), " ")?.trim()?.take(NOTE_MAX)?.trim()?.takeIf { it.isNotEmpty() }

    fun pairSummary(pair: HandoverPair, zone: ZoneId = ZoneId.systemDefault()): Pair<String, String> {
        val day = DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN).withZone(zone)
        val time = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREAN).withZone(zone)
        val sameDay = pair.takeover.at.atZone(zone).toLocalDate() == pair.returned.at.atZone(zone).toLocalDate()
        val head = "인수 ${day.format(pair.takeover.at)} → 반납 ${(if (sameDay) time else day).format(pair.returned.at)}"
        val c = compare(pair.takeover, pair.returned)
        val doors = listOf("문" to c.doorOpen, "트렁크" to c.trunkOpen, "충전구" to c.chargePortOpen)
            .filter { it.second == true }.map { it.first }
        val body = listOfNotNull(
            "배터리 ${battery(c)}",
            "누적 ${distance(c)}",
            "새로 켜진 경고등 ${warnings(c)}",
            if (doors.isEmpty()) "반납 때 열린 곳 없음" else "반납 때 열림: ${doors.joinToString(", ")}",
        ).joinToString(" · ")
        return head to body
    }

    fun months(entries: List<TripEntry>, zone: ZoneId = ZoneId.systemDefault()): List<TripMonth> =
        entries.groupBy { YearMonth.from(it.returnedAt.atZone(zone)) }
            .map { (month, items) -> TripMonth(month, items.sortedByDescending { it.returnedAt }) }
            .sortedByDescending { it.month }

    fun monthTitle(month: TripMonth): String =
        "${month.month.year}년 ${month.month.monthValue}월 · ${month.entries.size}건 · 합계 ${km(month.totalKm)}km"

    fun tripCells(entry: TripEntry, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val date = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN).withZone(zone)
        val time = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREAN).withZone(zone)
        fun reading(value: Double?) = value?.let { "${km(it)}km" } ?: "확인 못 함"
        return listOf(
            date.format(entry.returnedAt),
            reading(entry.startKm),
            reading(entry.endKm),
            entry.distanceKm?.let { "${km(it)}km" } ?: "확인 못 함",
            entry.destination?.takeIf { it.isNotBlank() } ?: "—",
            "${time.format(entry.takeoverAt)} → ${time.format(entry.returnedAt)}",
        )
    }
}

/**
 * Takeover/return records kept in the car: the last [MAX_PAIRS] pairs, the fleet trip lines and the one-line note
 * for the next driver. Pure state; the caller persists it.
 */
class HandoverLedger(pairs: List<HandoverPair> = emptyList(), trips: List<TripEntry> = emptyList(), note: String? = null) {
    companion object {
        const val MAX_PAIRS = 50
        const val MAX_TRIPS = 500
    }
    var pairs: List<HandoverPair> = pairs.takeLast(MAX_PAIRS)
        private set
    var trips: List<TripEntry> = trips.takeLast(MAX_TRIPS)
        private set
    /** Shown once on the next handover card as "이전 운전자 한마디". */
    var note: String? = ReturnText.cleanNote(note)
        private set

    /** A takeover is open (waiting for its return) until a pair with the same takeover time exists. */
    fun isOpen(takeover: HandoverSnapshot?): Boolean = takeover != null && pairs.none { it.takeover.at == takeover.at }

    fun leaveNote(text: String?): String? { note = ReturnText.cleanNote(text); return note }

    /** The next driver has seen the note on the handover card and taken the car over. */
    fun takenOver() { note = null }

    /** Stores the pair (keeping the last 50) and, in fleet mode, one trip line. Returns the trip line when written. */
    fun returned(takeover: HandoverSnapshot, returned: HandoverSnapshot, mode: HandoverMode, destination: String? = null): TripEntry? {
        pairs = (pairs.filter { it.takeover.at != takeover.at } + HandoverPair(takeover, returned)).takeLast(MAX_PAIRS)
        if (mode != HandoverMode.FLEET) return null
        val trip = TripEntry(takeover.at, returned.at, takeover.odometerKm, returned.odometerKm, destination?.trim()?.takeIf { it.isNotEmpty() })
        trips = (trips + trip).takeLast(MAX_TRIPS)
        return trip
    }

    fun clear() { pairs = emptyList(); trips = emptyList(); note = null }
}
