package ai.gyeote.app.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How long the spoken handover is. The card always shows every line. */
enum class HandoverLength(val label: String) { SHORT("짧게 · 약 15초"), NORMAL("보통 · 약 30초") }

/**
 * Settings modes. Enum names are what SecureStore keeps, so they never change; only labels and order do (10-31 decision).
 * [noteOn]: the one-line note for the next driver (F3) and its "이전 운전자 한마디" line on F1. Off for rental/car-share,
 * where the drivers do not know each other.
 */
enum class HandoverMode(val label: String, val cardOn: Boolean, val defaultLength: HandoverLength, val noteOn: Boolean) {
    RENTAL("렌터카·카셰어", true, HandoverLength.SHORT, false),
    FAMILY("함께 쓰는 차", true, HandoverLength.SHORT, true),
    FLEET("법인·택시", true, HandoverLength.NORMAL, true),
    OFF("끄기", false, HandoverLength.SHORT, false);

    companion object {
        /** Order on the settings screen. Declaration order stays as it was. */
        val settingsOrder = listOf(RENTAL, FLEET, FAMILY, OFF)
    }
    /** The stored note as this mode shows it: null when the mode has no note. */
    fun visibleNote(note: String?): String? = note.takeIf { noteOn }
}

data class HandoverSettings(val mode: HandoverMode = HandoverMode.RENTAL, val length: HandoverLength = mode.defaultLength) {
    fun withMode(next: HandoverMode) = HandoverSettings(next, next.defaultLength)
}

/** Vehicle state only. Nothing here describes who drove or how. Null = not received from the vehicle. */
data class HandoverInput(
    val socPercent: Int? = null,
    val rangeKm: Double? = null,
    val activeWarnings: List<WarningAdvice> = emptyList(),
    val history: List<WarningRecord> = emptyList(),
    val odometerKm: Double? = null,
    val doorOpen: Boolean? = null,
    val trunkOpen: Boolean? = null,
    val chargePortOpen: Boolean? = null,
    val previousNote: String? = null,
    /** Any side window open; null when the vehicle does not report windows. Used by F3 only. */
    val windowOpen: Boolean? = null,
)

data class HandoverLine(val label: String, val text: String)
data class HandoverCard(val greeting: String, val lines: List<HandoverLine>, val speech: String)

/** Taken when the next driver presses [인수했어요]: the car's own state, nothing about people. */
data class HandoverSnapshot(
    val at: Instant,
    val socPercent: Int?,
    val odometerKm: Double?,
    val activeWarnings: List<String>,
    val doorOpen: Boolean?,
    val trunkOpen: Boolean?,
    val chargePortOpen: Boolean?,
    val windowOpen: Boolean? = null,
) {
    companion object {
        fun capture(at: Instant, input: HandoverInput) = HandoverSnapshot(
            at, input.socPercent, input.odometerKm?.takeIf { it.isFinite() && it >= 0 },
            input.activeWarnings.map { it.code.name }.distinct().sorted(),
            input.doorOpen, input.trunkOpen, input.chargePortOpen, input.windowOpen,
        )
    }
}

/** First-person car voice. Plain words from the existing warning dictionary; no judgement of people. */
object HandoverText {
    const val RECORDING_SCOPE = "Passer가 기록 중일 때의 내용이에요. 화면을 닫아도 기록해요. 끄기·앱 종료 중에는 기록하지 않아요."
    const val GREETING = "안녕하세요. 지금 제 상태를 말씀드릴게요."
    private fun km(value: Double) = String.format(Locale.KOREA, "%,.0f", value)
    /** Korean particle by the final syllable: 문이/충전구가, 문은/충전구는. */
    fun particle(word: String, withFinal: String, withoutFinal: String): String {
        val last = word.lastOrNull() ?: return word + withoutFinal
        val hasFinal = last in '가'..'힣' && (last - '가') % 28 != 0
        return word + if (hasFinal) withFinal else withoutFinal
    }

    fun recentSummary(records: List<WarningRecord>, now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<String> {
        val today = now.atZone(zone).toLocalDate()
        return records.groupBy { it.reading.code }.entries
            .sortedByDescending { (_, items) -> items.maxOf { it.started.at } }
            .map { (code, items) ->
                val latest = items.maxOf { it.started.at }.atZone(zone).toLocalDate()
                val day = when (latest) {
                    today -> "오늘"
                    today.minusDays(1) -> "어제"
                    else -> DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN).format(latest)
                }
                "${WarningDictionary.lookup(code).title} ${items.size}번 ($day)"
            }
    }

    private fun openings(input: HandoverInput): Pair<List<String>, List<String>> {
        val parts = listOf("문" to input.doorOpen, "트렁크" to input.trunkOpen, "충전구" to input.chargePortOpen)
        return parts.filter { it.second == true }.map { it.first } to parts.filter { it.second == null }.map { it.first }
    }

    fun snapshotSummary(value: HandoverSnapshot, zone: ZoneId = ZoneId.systemDefault()): String {
        fun state(open: Boolean?) = when (open) { true -> "열림"; false -> "닫힘"; null -> "확인 못 함" }
        val warnings = if (value.activeWarnings.isEmpty()) "켜진 경고등 없음" else "켜진 경고등 " + value.activeWarnings.joinToString(", ") { name ->
            WarningCode.entries.firstOrNull { it.name == name }?.let { WarningDictionary.lookup(it).title } ?: name
        }
        return listOf(
            DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN).withZone(zone).format(value.at),
            value.socPercent?.let { "배터리 $it%" } ?: "배터리 확인 못 함",
            value.odometerKm?.let { "누적 ${km(it)}km" } ?: "누적 거리 확인 못 함",
            warnings,
            "문 ${state(value.doorOpen)}", "트렁크 ${state(value.trunkOpen)}", "충전구 ${state(value.chargePortOpen)}",
        ).joinToString(" · ")
    }

    fun build(input: HandoverInput, length: HandoverLength, now: Instant, zone: ZoneId = ZoneId.systemDefault()): HandoverCard {
        val lines = mutableListOf<HandoverLine>()
        val speech = mutableListOf(GREETING)

        val battery = listOfNotNull(input.socPercent?.let { "배터리 $it%" }, input.rangeKm?.let { "갈 수 있는 거리 약 ${km(it)}km" })
        lines += HandoverLine("배터리", battery.joinToString(" · ").ifEmpty { "배터리 정보를 아직 받지 못했어요." })
        speech += when {
            input.socPercent != null && input.rangeKm != null -> "배터리는 ${input.socPercent}퍼센트이고, 약 ${km(input.rangeKm)}킬로미터를 갈 수 있어요."
            input.socPercent != null -> "배터리는 ${input.socPercent}퍼센트예요."
            input.rangeKm != null -> "약 ${km(input.rangeKm)}킬로미터를 갈 수 있어요."
            else -> "배터리 정보는 아직 받지 못했어요."
        }

        val active = input.activeWarnings.distinctBy { it.code }.sortedBy { it.urgency.ordinal }
        lines += HandoverLine("지금 켜진 경고등", if (active.isEmpty()) "없어요." else active.joinToString("\n") { "${it.title}: ${it.meaning}" })
        speech += if (active.isEmpty()) "지금 켜진 경고등은 없어요." else
            "지금 켜진 경고등이 있어요. " + active.joinToString(" ") { it.meaning }

        val recent = recentSummary(input.history, now, zone)
        lines += HandoverLine("최근 켜졌던 경고등", if (recent.isEmpty()) "기록이 없어요.\n$RECORDING_SCOPE" else
            recent.joinToString(" · ") + "\n$RECORDING_SCOPE")
        if (length == HandoverLength.NORMAL) speech += if (recent.isEmpty()) "최근에 켜졌던 경고등 기록은 없어요." else
            "최근에 켜졌던 경고등은 " + recent.joinToString(", ") { it.replace(Regex(" \\((.+)\\)$"), ", $1") } + "이에요."

        lines += HandoverLine("누적 주행거리", input.odometerKm?.takeIf { it.isFinite() && it >= 0 }?.let { "${km(it)}km" } ?: "아직 받지 못했어요.")
        if (length == HandoverLength.NORMAL) input.odometerKm?.takeIf { it.isFinite() && it >= 0 }?.let {
            speech += "지금까지 ${km(it)}킬로미터를 달렸어요."
        }

        val (open, unknown) = openings(input)
        val closed = listOf("문", "트렁크", "충전구").filter { it !in open && it !in unknown }
        val doorText = buildList {
            if (open.isNotEmpty()) add("열려 있어요: ${open.joinToString(", ")}")
            if (closed.isNotEmpty()) add("닫혀 있어요: ${closed.joinToString("·")}")
            if (unknown.isNotEmpty()) add("확인하지 못했어요: ${unknown.joinToString(", ")}")
        }.joinToString("\n")
        lines += HandoverLine("문·트렁크·충전구", doorText)
        when {
            open.isNotEmpty() -> speech += particle(open.joinToString(", "), "이", "가") + " 열려 있어요."
            length == HandoverLength.NORMAL && closed.isNotEmpty() -> speech += particle(closed.joinToString(", "), "은", "는") + " 닫혀 있어요."
        }

        input.previousNote?.trim()?.takeIf { it.isNotEmpty() }?.let { note ->
            lines += HandoverLine("이전 운전자 한마디", note)
            if (length == HandoverLength.NORMAL) speech += "이전 운전자 한마디가 있어요. $note"
        }
        return HandoverCard(GREETING, lines, speech.joinToString(" "))
    }
}
