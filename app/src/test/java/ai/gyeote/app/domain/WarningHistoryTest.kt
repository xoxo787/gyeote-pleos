package ai.gyeote.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class WarningHistoryTest {
    @Test fun mechanicTextHidesUnavailableRawMetadataButKeepsSdkNameAndMeaningfulCount() {
        val reading = TelltaleReading(WarningCode.ENGINE, priority = -1, warningCount = 0)
        val hidden = WarningHistoryText.mechanicDetails(reading)
        assertTrue(hidden.contains("ENGINE"))
        assertFalse(hidden.contains("-1"))
        assertFalse(hidden.contains("횟수"))
        assertTrue(WarningHistoryText.mechanicDetails(reading.copy(warningCount = 2)).contains("차량이 알린 횟수 2회"))
    }
    @Test fun safetyAssistanceDoesNotInventAnUnconfirmedCause() {
        val advice = WarningDictionary.lookup(WarningCode.SAFETY_ASSIST)
        assertEquals("차의 안전 기능이 작동했을 수 있어요.", advice.meaning)
        assertTrue(advice.action.contains("설명서"))
        assertTrue(WarningDictionary.lookup(WarningCode.FCA).meaning.contains("줄였을 수 있어요"))
    }
    @Test fun redMetadataEscalatesAdviceAndRetainsHumanReadableColor() {
        val reading = TelltaleReading(WarningCode.ENGINE, color = 4, colorName = "RED")
        assertEquals(Urgency.STOP_SAFELY, reading.advice.urgency)
        assertEquals("빨강", reading.colorLabel)
    }
    private val now = Instant.parse("2026-10-20T04:00:00Z")
    private val engine = TelltaleReading(WarningCode.ENGINE, color = 2, priority = 3, warningCount = 2)
    private fun moment(seconds: Long = 0, km: Double? = 12_345.6, moving: Boolean = true) =
        WarningMoment(now.plusSeconds(seconds), km, if (moving) GearSelection.DRIVE else GearSelection.PARK, moving)
    private fun record(start: Long, end: Long?, reading: TelltaleReading = engine, moving: Boolean = true) =
        WarningRecord("id-$start-${reading.sdkName}", reading, moment(start, moving = moving), end?.let { moment(it, moving = moving) })

    @Test fun onsetStoresExactMetadataAndVehicleContext() {
        val history = WarningHistory()
        assertTrue(history.sample(listOf(engine), moment()))
        val record = history.records.single()
        assertEquals(engine, record.reading); assertEquals(moment(), record.started); assertNull(record.ended)
    }
    @Test fun identicalPrivateAndPollSnapshotsAreOneOccurrence() {
        val history = WarningHistory()
        history.sample(listOf(engine), moment())
        assertFalse(history.sample(listOf(engine), moment(3)))
        assertEquals(1, history.records.size)
    }
    @Test fun offCapturesEndDistanceGearAndDuration() {
        val history = WarningHistory()
        history.sample(listOf(engine), moment())
        val end = moment(300, 12_350.0, moving = false)
        history.sample(emptyList(), end)
        assertEquals(end, history.records.single().ended)
        assertEquals(Duration.ofMinutes(5), history.records.single().duration)
    }
    @Test fun anotherOnAfterOffIsAnotherOccurrence() {
        val history = WarningHistory()
        history.sample(listOf(engine), moment()); history.sample(emptyList(), moment(10))
        history.sample(listOf(engine), moment(20))
        assertEquals(2, history.records.size); assertNotEquals(history.records[0].id, history.records[1].id)
    }
    @Test fun unknownSdkTypesStayDistinct() {
        val history = WarningHistory()
        history.sample(listOf(TelltaleReading(WarningCode.UNKNOWN, "A"), TelltaleReading(WarningCode.UNKNOWN, "B")), moment())
        assertEquals(2, history.records.size)
        history.sample(listOf(TelltaleReading(WarningCode.UNKNOWN, "B")), moment(10))
        assertNotNull(history.records.first { it.reading.sdkName == "A" }.ended)
        assertNull(history.records.first { it.reading.sdkName == "B" }.ended)
    }
    @Test fun missingOdometerIsNotInventedAsZero() {
        val history = WarningHistory(); history.sample(listOf(engine), moment(km = null))
        assertNull(history.records.single().started.odometerKm)
    }
    @Test fun retentionKeepsExactThirtyDayBoundaryAndRemovesOlder() {
        val edge = now.minus(Duration.ofDays(30))
        val history = WarningHistory(listOf(record(0, 2).copy(started = moment().copy(at = edge)),
            record(1, 3).copy(started = moment().copy(at = edge.minusNanos(1)))))
        assertTrue(history.retain(now)); assertEquals(listOf(edge), history.records.map { it.started.at })
    }
    @Test fun retentionCapsNewestTwoHundred() {
        val history = WarningHistory((0L..249L).map { record(-it, -it + 1) })
        history.retain(now)
        assertEquals(200, history.records.size)
        assertEquals(now.minusSeconds(199), history.records.first().started.at)
    }
    @Test fun fiveMinuteRepetitionGroupsAcrossOtherWarningTypes() {
        val groups = WarningHistoryText.group(listOf(record(0, 60), record(90, 95, TelltaleReading(WarningCode.ABS)), record(120, 300)))
        assertEquals(2, groups.size); assertEquals(2, groups.first { it.first.reading.code == WarningCode.ENGINE }.records.size)
    }
    @Test fun distantOrDifferentDrivingStateIsNotGrouped() {
        assertEquals(3, WarningHistoryText.group(listOf(record(0, 10), record(400, 410), record(420, 430, moving = false))).size)
    }
    @Test fun differentColorsAreNotGrouped() {
        assertEquals(2, WarningHistoryText.group(listOf(record(0, 10), record(20, 30, engine.copy(color = 1)))).size)
    }
    @Test fun parentTimelineUsesKoreanDateCountMovementAndElapsedTime() {
        val group = WarningHistoryText.group(listOf(record(0, 60), record(120, 300))).single()
        val text = WarningHistoryText.parent(group, ZoneId.of("Asia/Seoul"))
        assertEquals("10월 20일 오후, 달리는 중에 엔진 경고가 2번 켜졌다가 5분 뒤 꺼졌어요.", text)
    }
    @Test fun openRecordNeverClaimsItWentOff() {
        val text = WarningHistoryText.parent(WarningGroup(listOf(record(0, null))))
        assertTrue(text.contains("아직 켜져 있어요")); assertFalse(text.contains("꺼졌어요"))
    }
    @Test fun restoredOpenRecordClosesOnFirstRealOffSnapshot() {
        val history = WarningHistory(listOf(record(0, null)))
        history.sample(emptyList(), moment(20))
        assertEquals(Duration.ofSeconds(20), history.records.single().duration)
    }
    @Test fun backwardsTimestampCannotCloseAnOpenRecord() {
        val history = WarningHistory(listOf(record(0, null)))
        history.sample(emptyList(), moment(-1))
        assertNull(history.records.single().ended)
    }
    @Test fun clearingHistoryLeavesOtherDomainStateUntouchedAndAllowsFutureOccurrences() {
        val history = WarningHistory(listOf(record(0, 2)))
        history.clear(); assertTrue(history.records.isEmpty())
        history.sample(listOf(engine), moment(3)); assertEquals(1, history.records.size)
    }
}
