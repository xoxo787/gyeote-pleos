package ai.gyeote.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Kept as code points so the repository word scan never matches this file itself. */
internal object Wording {
    private fun words(vararg codes: IntArray) = codes.map { String(it, 0, it.size) }
    val banned = words(intArrayOf(44048, 49884), intArrayOf(52628, 51201), intArrayOf(50948, 52824, 32, 54869, 51064),
        intArrayOf(49892, 48260), intArrayOf(45432, 51064), intArrayOf(51648, 53428, 51060), intArrayOf(44553, 48156, 51652),
        intArrayOf(54168, 45804, 32, 50724, 51312, 51089), intArrayOf(52293, 51076), intArrayOf(51096, 47803), intArrayOf(51216, 49688))
    val honorifics = words(intArrayOf(48512, 47784, 45784), intArrayOf(50500, 48260, 51648), intArrayOf(46384, 45784),
        intArrayOf(51088, 45376), intArrayOf(44032, 51313), intArrayOf(46392))
    fun offending(text: String) = (banned + honorifics).filter { it in text }
}

class HandoverTriggerTest {
    private fun at(t: Long, gear: GearSelection, driverOpen: Boolean? = false, anyOpen: Boolean = driverOpen == true) =
        VehicleSample(t, 12_345.0, anyOpen, !anyOpen, false, gear, driverOpen)

    @Test fun appStartedInParkOffersOnce() {
        val machine = JourneyMachine()
        machine.sample(at(0, GearSelection.UNKNOWN))
        assertEquals(HandoverState.IDLE, machine.handover)
        machine.sample(at(100, GearSelection.PARK))
        assertEquals(HandoverState.DUE, machine.handover)
        machine.handoverHandled()
        machine.sample(at(200, GearSelection.PARK, driverOpen = true))
        machine.sample(at(300, GearSelection.PARK, driverOpen = false))
        assertEquals("Same boarding offers once", HandoverState.IDLE, machine.handover)
    }
    @Test fun appStartedInDriveWaitsForParkAndDriverDoorCycle() {
        val machine = JourneyMachine()
        machine.sample(at(0, GearSelection.DRIVE))
        assertEquals(HandoverState.IDLE, machine.handover)
        machine.sample(at(100, GearSelection.PARK))
        assertEquals("Parking alone is not a new boarding", HandoverState.IDLE, machine.handover)
        machine.sample(at(200, GearSelection.PARK, driverOpen = true))
        assertEquals(HandoverState.IDLE, machine.handover)
        machine.sample(at(300, GearSelection.PARK, driverOpen = false))
        assertEquals(HandoverState.DUE, machine.handover)
    }
    @Test fun neverDueWhileDrivingAndCancelledByDrive() {
        val machine = JourneyMachine()
        machine.sample(at(0, GearSelection.PARK))
        assertEquals(HandoverState.DUE, machine.handover)
        machine.sample(at(100, GearSelection.DRIVE))
        assertEquals(HandoverState.IDLE, machine.handover)
        machine.sample(at(200, GearSelection.DRIVE, driverOpen = true))
        machine.sample(at(300, GearSelection.DRIVE, driverOpen = false))
        assertEquals("Door cycle in D is not a boarding", HandoverState.IDLE, machine.handover)
        machine.sample(at(400, GearSelection.REVERSE))
        assertEquals(HandoverState.IDLE, machine.handover)
    }
    @Test fun nextBoardingAfterDrivingOffersAgain() {
        val machine = JourneyMachine()
        machine.sample(at(0, GearSelection.PARK)); machine.handoverHandled()
        machine.sample(at(100, GearSelection.DRIVE))
        machine.sample(at(200, GearSelection.PARK))
        machine.sample(at(300, GearSelection.PARK, driverOpen = true))
        machine.sample(at(400, GearSelection.PARK, driverOpen = true))
        assertEquals(HandoverState.IDLE, machine.handover)
        machine.sample(at(500, GearSelection.PARK, driverOpen = false))
        assertEquals(HandoverState.DUE, machine.handover)
    }
    @Test fun repeatedDoorCyclesWhileDueDoNotStack() {
        val machine = JourneyMachine()
        machine.sample(at(0, GearSelection.DRIVE)); machine.sample(at(10, GearSelection.PARK))
        machine.sample(at(20, GearSelection.PARK, true)); machine.sample(at(30, GearSelection.PARK, false))
        machine.sample(at(40, GearSelection.PARK, true)); machine.sample(at(50, GearSelection.PARK, false))
        assertEquals(HandoverState.DUE, machine.handover)
        machine.handoverHandled()
        machine.sample(at(60, GearSelection.PARK, true)); machine.sample(at(70, GearSelection.PARK, false))
        assertEquals(HandoverState.IDLE, machine.handover)
    }
    @Test fun anyDoorIsUsedWhenDriverDoorIsNotReported() {
        val machine = JourneyMachine()
        machine.sample(at(0, GearSelection.DRIVE))
        machine.sample(at(100, GearSelection.PARK, driverOpen = null, anyOpen = true))
        machine.sample(at(200, GearSelection.PARK, driverOpen = null, anyOpen = false))
        assertEquals(HandoverState.DUE, machine.handover)
    }
    @Test fun outOfOrderSampleIsIgnored() {
        val machine = JourneyMachine()
        machine.sample(at(1_000, GearSelection.DRIVE))
        machine.sample(at(500, GearSelection.PARK))
        assertEquals(HandoverState.IDLE, machine.handover)
    }
    @Test fun handoverDoesNotChangeDepartureEvents() {
        val machine = JourneyMachine()
        assertNull(machine.sample(at(0, GearSelection.PARK)))
        assertEquals(JourneyEvent.DEPARTED, machine.sample(at(100, GearSelection.DRIVE)))
    }
}

class HandoverTextTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = Instant.parse("2026-10-26T05:00:00Z")
    private fun record(code: WarningCode, at: Instant, ended: Boolean = true) = WarningRecord(
        "id-${code.name}-${at.epochSecond}", TelltaleReading(code), WarningMoment(at, 12_300.0, GearSelection.DRIVE, true),
        if (ended) WarningMoment(at.plusSeconds(60), 12_301.0, GearSelection.PARK, false) else null)
    private val full = HandoverInput(
        socPercent = 54, rangeKm = 210.0,
        activeWarnings = listOf(WarningDictionary.lookup(WarningCode.TPMS)),
        history = listOf(record(WarningCode.TPMS, now.minusSeconds(3_600)), record(WarningCode.TPMS, now.minusSeconds(86_400)),
            record(WarningCode.ENGINE, now.minusSeconds(86_400 * 3))),
        odometerKm = 12_345.0, doorOpen = false, trunkOpen = false, chargePortOpen = true,
        previousNote = "뒷바퀴 공기 넣어야 해",
    )

    @Test fun cardFollowsDesignOrderInFirstPerson() {
        val card = HandoverText.build(full, HandoverLength.NORMAL, now, zone)
        assertEquals("안녕하세요. 지금 제 상태를 말씀드릴게요.", card.greeting)
        assertEquals(listOf("배터리", "지금 켜진 경고등", "최근 켜졌던 경고등", "누적 주행거리", "문·트렁크·충전구", "이전 운전자 한마디"),
            card.lines.map { it.label })
        assertEquals("배터리 54% · 갈 수 있는 거리 약 210km", card.lines[0].text)
        assertTrue(card.lines[1].text.contains("타이어 공기압이 낮을 수 있어요."))
        assertTrue(card.lines[2].text.startsWith("타이어 바람 2번 (오늘) · 엔진 점검 1번 (10월 23일)"))
        assertTrue(card.lines[2].text.contains("Passer가 기록 중일 때의 내용이에요."))
        assertEquals("12,345km", card.lines[3].text)
        assertEquals("열려 있어요: 충전구\n닫혀 있어요: 문·트렁크", card.lines[4].text)
        assertTrue(card.speech.startsWith(card.greeting))
        assertTrue(card.speech.contains("배터리는 54퍼센트이고, 약 210킬로미터를 갈 수 있어요."))
        assertTrue(card.speech.contains("충전구가 열려 있어요."))
        assertTrue(card.speech.contains("뒷바퀴 공기 넣어야 해"))
    }
    @Test fun shortSpeechKeepsEssentialsOnly() {
        val normal = HandoverText.build(full, HandoverLength.NORMAL, now, zone)
        val short = HandoverText.build(full, HandoverLength.SHORT, now, zone)
        assertEquals("Card lines do not depend on speech length", normal.lines, short.lines)
        assertTrue(short.speech.length < normal.speech.length)
        assertTrue(short.speech.contains("54퍼센트")); assertTrue(short.speech.contains("충전구가 열려 있어요."))
        assertFalse(short.speech.contains("12,345")); assertFalse(short.speech.contains("한마디"))
    }
    @Test fun historyExplainsBackgroundRecordingAndItsStopConditions() {
        val text = HandoverText.build(full, HandoverLength.NORMAL, now, zone).lines[2].text
        assertTrue(text.contains("화면을 닫아도 기록해요."))
        assertTrue(text.contains("끄기·앱 종료 중에는 기록하지 않아요."))
        assertTrue(Wording.offending(text).isEmpty())
    }
    @Test fun emptyHistoryStillExplainsRecordingLimits() {
        val text = HandoverText.build(HandoverInput(), HandoverLength.SHORT, now, zone).lines[2].text
        assertTrue(text.startsWith("기록이 없어요."))
        assertTrue(text.contains("Passer가 기록 중일 때의 내용이에요."))
        assertTrue(text.contains("끄기·앱 종료 중에는 기록하지 않아요."))
    }
    @Test fun missingValuesAreSaidAsMissingNeverGuessed() {
        val card = HandoverText.build(HandoverInput(), HandoverLength.NORMAL, now, zone)
        assertEquals("배터리 정보를 아직 받지 못했어요.", card.lines[0].text)
        assertEquals("없어요.", card.lines[1].text)
        assertTrue(card.lines[2].text.startsWith("기록이 없어요."))
        assertEquals("아직 받지 못했어요.", card.lines[3].text)
        assertEquals("확인하지 못했어요: 문, 트렁크, 충전구", card.lines[4].text)
        assertEquals(5, card.lines.size)
        assertFalse(card.speech.contains("null"))
    }
    @Test fun allClosedReadsNaturally() {
        val card = HandoverText.build(HandoverInput(doorOpen = false, trunkOpen = false, chargePortOpen = false), HandoverLength.NORMAL, now, zone)
        assertEquals("닫혀 있어요: 문·트렁크·충전구", card.lines[4].text)
        assertTrue(card.speech.contains("문, 트렁크, 충전구는 닫혀 있어요."))
        val door = HandoverText.build(HandoverInput(doorOpen = true, trunkOpen = false, chargePortOpen = false), HandoverLength.SHORT, now, zone)
        assertTrue(door.speech.contains("문이 열려 있어요."))
    }
    @Test fun particlesFollowFinalSyllable() {
        assertEquals("문이", HandoverText.particle("문", "이", "가"))
        assertEquals("충전구가", HandoverText.particle("충전구", "이", "가"))
        assertEquals("트렁크는", HandoverText.particle("트렁크", "은", "는"))
    }
    @Test fun noBannedWordsOrHonorificsInAnyGeneratedText() {
        val texts = mutableListOf<String>()
        val warnings = WarningCode.entries.map { WarningDictionary.lookup(it) }
        val history = WarningCode.entries.mapIndexed { i, code -> record(code, now.minusSeconds(3_600L * i)) }
        listOf(true, false, null).forEach { open ->
            HandoverLength.entries.forEach { length ->
                val card = HandoverText.build(full.copy(activeWarnings = warnings, history = history, doorOpen = open, trunkOpen = open, chargePortOpen = open), length, now, zone)
                texts += card.greeting; texts += card.speech; card.lines.forEach { texts += it.label; texts += it.text }
                texts += HandoverText.build(HandoverInput(doorOpen = open), length, now, zone).speech
            }
        }
        texts += HandoverText.snapshotSummary(HandoverSnapshot.capture(now, full.copy(activeWarnings = warnings)), zone)
        texts += HandoverLength.entries.map { it.label }
        texts += HandoverMode.entries.map { it.label }
        val offending = texts.flatMap { Wording.offending(it) }.distinct()
        assertTrue("Offending: $offending", offending.isEmpty())
    }
}

class HandoverSnapshotTest {
    private val now = Instant.parse("2026-10-26T05:00:00Z")
    @Test fun snapshotKeepsVehicleStateOnly() {
        val input = HandoverInput(54, 210.0, listOf(WarningDictionary.lookup(WarningCode.TPMS), WarningDictionary.lookup(WarningCode.ABS),
            WarningDictionary.lookup(WarningCode.TPMS)), emptyList(), 12_345.0, false, null, true, "메모는 스냅샷에 넣지 않음")
        val snapshot = HandoverSnapshot.capture(now, input)
        assertEquals(HandoverSnapshot(now, 54, 12_345.0, listOf("ABS", "TPMS"), false, null, true), snapshot)
    }
    @Test fun invalidDistanceIsNotStored() {
        assertNull(HandoverSnapshot.capture(now, HandoverInput(odometerKm = Double.NaN)).odometerKm)
        assertNull(HandoverSnapshot.capture(now, HandoverInput(odometerKm = -1.0)).odometerKm)
    }
    @Test fun summaryNamesEveryStoredField() {
        val text = HandoverText.snapshotSummary(HandoverSnapshot(now, 54, 12_345.0, listOf("TPMS"), false, null, true), ZoneId.of("Asia/Seoul"))
        assertEquals("10월 26일 14:00 · 배터리 54% · 누적 12,345km · 켜진 경고등 타이어 바람 · 문 닫힘 · 트렁크 확인 못 함 · 충전구 열림", text)
    }
    @Test fun modesSwitchCardAndDefaultLength() {
        assertEquals(HandoverSettings(HandoverMode.RENTAL, HandoverLength.SHORT), HandoverSettings())
        assertEquals(HandoverLength.SHORT, HandoverSettings().withMode(HandoverMode.FAMILY).length)
        assertFalse(HandoverMode.OFF.cardOn)
        assertTrue(HandoverMode.entries.filter { it != HandoverMode.OFF }.all { it.cardOn })
        assertEquals(HandoverLength.SHORT, HandoverSettings().copy(length = HandoverLength.SHORT).length)
    }
}

/** 10-31 decision: labels, settings order, default length and the note per mode. */
class HandoverModeTest {
    private val now = Instant.parse("2026-10-31T05:00:00Z")
    @Test fun labelsChangedEnumNamesKept() {
        assertEquals(listOf("렌터카·카셰어", "함께 쓰는 차", "법인·택시", "끄기"), HandoverMode.entries.map { it.label })
        assertEquals(listOf("RENTAL", "FAMILY", "FLEET", "OFF"), HandoverMode.entries.map { it.name })
        assertTrue(HandoverMode.entries.flatMap { Wording.offending(it.label) }.isEmpty())
    }
    @Test fun settingsOrderRentalFleetSharedOff() {
        assertEquals(listOf(HandoverMode.RENTAL, HandoverMode.FLEET, HandoverMode.FAMILY, HandoverMode.OFF), HandoverMode.settingsOrder)
        assertEquals(HandoverMode.entries.toSet(), HandoverMode.settingsOrder.toSet())
    }
    @Test fun defaultLengthPerMode() {
        assertEquals(HandoverLength.SHORT, HandoverSettings().withMode(HandoverMode.RENTAL).length)
        assertEquals(HandoverLength.NORMAL, HandoverSettings().withMode(HandoverMode.FLEET).length)
        assertEquals(HandoverLength.SHORT, HandoverSettings().withMode(HandoverMode.FAMILY).length)
        // A length the user picked after choosing the mode stays.
        assertEquals(HandoverLength.NORMAL, HandoverSettings().withMode(HandoverMode.RENTAL).copy(length = HandoverLength.NORMAL).length)
    }
    @Test fun noteShownOnlyInFleetAndShared() {
        assertEquals(mapOf(HandoverMode.RENTAL to false, HandoverMode.FAMILY to true, HandoverMode.FLEET to true, HandoverMode.OFF to false),
            HandoverMode.entries.associateWith { it.noteOn })
        HandoverMode.entries.forEach { mode ->
            val card = HandoverText.build(HandoverInput(socPercent = 41, previousNote = mode.visibleNote("뒷바퀴 공기 넣어야 해")), HandoverLength.NORMAL, now)
            assertEquals(mode.name, mode.noteOn, card.lines.any { it.label == "이전 운전자 한마디" })
            assertEquals(mode.name, mode.noteOn, card.speech.contains("한마디"))
        }
        assertNull(HandoverMode.FLEET.visibleNote(null))
    }
}
