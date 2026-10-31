package ai.gyeote.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

class ReturnTriggerTest {
    private fun at(t: Long, gear: GearSelection, driverOpen: Boolean = false) =
        VehicleSample(t, 12_346.0, driverOpen, !driverOpen, false, gear, driverOpen)

    /** Takeover in P, then the given samples. */
    private fun machine(takeover: Boolean = true): JourneyMachine = JourneyMachine().apply {
        sample(at(0, GearSelection.PARK)); handoverHandled()
        if (takeover) takeoverStarted()
    }

    @Test fun parkAndDriverDoorAfterDriveOffersOnce() {
        val m = machine()
        m.sample(at(100, GearSelection.DRIVE))
        assertEquals("Never while driving", ReturnState.IDLE, m.returnState)
        m.sample(at(200, GearSelection.PARK))
        assertEquals("P alone is not a return", ReturnState.IDLE, m.returnState)
        m.sample(at(300, GearSelection.PARK, driverOpen = true))
        assertEquals(ReturnState.DUE, m.returnState)
    }
    @Test fun noTakeoverNoCard() {
        val m = machine(takeover = false)
        m.sample(at(100, GearSelection.DRIVE)); m.sample(at(200, GearSelection.PARK)); m.sample(at(300, GearSelection.PARK, true))
        assertEquals(ReturnState.IDLE, m.returnState)
    }
    @Test fun noDriveNoCard() {
        val m = machine()
        m.sample(at(100, GearSelection.PARK, true)); m.sample(at(200, GearSelection.PARK, false)); m.sample(at(300, GearSelection.PARK, true))
        assertEquals(ReturnState.IDLE, m.returnState)
        m.sample(at(400, GearSelection.REVERSE)); m.sample(at(500, GearSelection.PARK, true))
        assertEquals("Reverse is not a drive in D", ReturnState.IDLE, m.returnState)
    }
    @Test fun sameStopDoesNotOfferTwice() {
        val m = machine()
        m.sample(at(100, GearSelection.DRIVE)); m.sample(at(200, GearSelection.PARK, true))
        assertEquals(ReturnState.DUE, m.returnState)
        m.returnHandled(closed = false)
        m.sample(at(300, GearSelection.PARK, false)); m.sample(at(400, GearSelection.PARK, true))
        assertEquals(ReturnState.IDLE, m.returnState)
    }
    @Test fun returnedTakeoverNeedsANewTakeover() {
        val m = machine()
        m.sample(at(100, GearSelection.DRIVE)); m.sample(at(200, GearSelection.PARK, true))
        m.returnHandled(closed = true)
        m.sample(at(300, GearSelection.DRIVE)); m.sample(at(400, GearSelection.PARK, true))
        assertEquals(ReturnState.IDLE, m.returnState)
        m.takeoverStarted()
        m.sample(at(500, GearSelection.DRIVE)); m.sample(at(600, GearSelection.PARK, false)); m.sample(at(700, GearSelection.PARK, true))
        assertEquals(ReturnState.DUE, m.returnState)
    }
    @Test fun laterThenAnotherDriveOffersAgain() {
        val m = machine()
        m.sample(at(100, GearSelection.DRIVE)); m.sample(at(200, GearSelection.PARK, true)); m.returnHandled(closed = false)
        m.sample(at(300, GearSelection.DRIVE))
        assertEquals(ReturnState.IDLE, m.returnState)
        m.sample(at(400, GearSelection.PARK, true))
        assertEquals(ReturnState.DUE, m.returnState)
    }
    @Test fun driveHidesAnOpenCard() {
        val m = machine()
        m.sample(at(100, GearSelection.DRIVE)); m.sample(at(200, GearSelection.PARK, true))
        m.sample(at(300, GearSelection.DRIVE, true))
        assertEquals(ReturnState.IDLE, m.returnState)
    }
    @Test fun closingTheDoorHandsOverToTheNextBoarding() {
        val m = machine()
        m.sample(at(100, GearSelection.DRIVE)); m.sample(at(200, GearSelection.PARK, true))
        assertEquals(ReturnState.DUE, m.returnState)
        assertEquals("Handover card waits for the door to close", HandoverState.IDLE, m.handover)
        m.sample(at(300, GearSelection.PARK, false))
        assertEquals(ReturnState.IDLE, m.returnState)
        assertEquals(HandoverState.DUE, m.handover)
    }
    @Test fun restoredTakeoverStillNeedsADrive() {
        val m = JourneyMachine()
        m.takeoverRestored(true)
        m.sample(at(0, GearSelection.PARK, true))
        assertEquals(ReturnState.IDLE, m.returnState)
        m.sample(at(100, GearSelection.DRIVE)); m.sample(at(200, GearSelection.PARK, true))
        assertEquals(ReturnState.DUE, m.returnState)
        val closed = JourneyMachine(); closed.takeoverRestored(false)
        closed.sample(at(0, GearSelection.DRIVE)); closed.sample(at(100, GearSelection.PARK, true))
        assertEquals(ReturnState.IDLE, closed.returnState)
    }
    @Test fun clearedTakeoverNeverOffers() {
        val m = machine()
        m.sample(at(100, GearSelection.DRIVE)); m.takeoverCleared(); m.sample(at(200, GearSelection.PARK, true))
        assertEquals(ReturnState.IDLE, m.returnState)
    }
}

class ReturnComparisonTest {
    private val t0 = Instant.parse("2026-10-26T05:00:00Z")
    private val t1 = Instant.parse("2026-10-26T05:40:00Z")
    private val takeover = HandoverSnapshot(t0, 54, 12_346.0, listOf("ABS"), false, false, false)
    private val now = HandoverSnapshot(t1, 41, 12_378.0, listOf("ABS", "TPMS"), false, false, false, false)

    @Test fun newWarningsAreTheSetDifference() {
        val c = ReturnText.compare(takeover, now)
        assertEquals(listOf(WarningCode.TPMS), c.newWarnings)
        assertEquals(32.0, c.distanceKm!!, 0.0)
        assertEquals(54, c.socBefore); assertEquals(41, c.socNow)
        val goneOff = ReturnText.compare(takeover.copy(activeWarnings = listOf("ABS", "TPMS")), now.copy(activeWarnings = emptyList()))
        assertTrue("A warning that went off is not new", goneOff.newWarnings.isEmpty())
        val dup = ReturnText.compare(takeover.copy(activeWarnings = emptyList()), now.copy(activeWarnings = listOf("TPMS", "TPMS", "ENGINE")))
        assertEquals(listOf(WarningCode.ENGINE, WarningCode.TPMS).sortedBy { it.ordinal }, dup.newWarnings)
    }
    @Test fun cardShowsBeforeAndNowInPlainWords() {
        val card = ReturnText.build(ReturnText.compare(takeover, now))
        assertEquals("이번 운행을 정리했어요.", card.title)
        assertEquals(listOf("배터리 · 인수 때 → 지금", "누적 주행거리", "새로 켜진 경고등", "문·트렁크·충전구", "창문"), card.lines.map { it.label })
        assertEquals("54% → 41%", card.lines[0].text)
        assertEquals("+32km", card.lines[1].text)
        assertEquals("1개: 타이어 바람", card.lines[2].text)
        assertEquals("닫혀 있어요: 문·트렁크·충전구", card.lines[3].text)
        assertEquals("모두 닫혀 있어요.", card.lines[4].text)
    }
    @Test fun missingValuesAreNotGuessedAndWindowsOnlyWhenRead() {
        val c = ReturnText.compare(HandoverSnapshot(t0, null, null, emptyList(), null, null, null),
            HandoverSnapshot(t1, 41, 12_000.0, emptyList(), true, null, false))
        val card = ReturnText.build(c)
        assertEquals("인수 때 확인 못 함 → 41%", card.lines[0].text)
        assertEquals("확인하지 못했어요.", card.lines[1].text)
        assertEquals("0개", card.lines[2].text)
        assertEquals("열려 있어요: 문\n닫혀 있어요: 충전구\n확인하지 못했어요: 트렁크", card.lines[3].text)
        assertFalse(card.lines.any { it.label == "창문" })
        val back = ReturnText.compare(takeover, now.copy(odometerKm = 12_000.0))
        assertNull("A counter that went down is not a distance", back.distanceKm)
    }
    @Test fun noBannedWordsOrHonorifics() {
        val texts = mutableListOf<String>()
        val all = WarningCode.entries.map { it.name }
        listOf(true, false, null).forEach { open ->
            val c = ReturnText.compare(takeover.copy(activeWarnings = emptyList()), now.copy(activeWarnings = all, doorOpen = open, trunkOpen = open, chargePortOpen = open, windowOpen = open))
            val card = ReturnText.build(c)
            texts += card.title; card.lines.forEach { texts += it.label; texts += it.text }
            val pair = ReturnText.pairSummary(HandoverPair(takeover, now.copy(doorOpen = open)), ZoneId.of("Asia/Seoul"))
            texts += pair.first; texts += pair.second
        }
        val trip = TripEntry(t0, t1, 12_346.0, 12_378.0, "중앙역")
        val month = ReturnText.months(listOf(trip)).first()
        texts += ReturnText.monthTitle(month); texts += ReturnText.tripCells(trip)
        val offending = texts.flatMap { Wording.offending(it) }.distinct()
        assertTrue("Offending: $offending", offending.isEmpty())
    }
}

class HandoverLedgerTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val t0 = Instant.parse("2026-10-26T05:00:00Z")
    private fun snap(at: Instant, soc: Int = 54, km: Double = 12_346.0) = HandoverSnapshot(at, soc, km, emptyList(), false, false, false)

    @Test fun noteIsShownOnTheNextHandoverCardThenCleared() {
        val ledger = HandoverLedger()
        assertEquals("뒷바퀴 공기 넣어야 해", ledger.leaveNote("  뒷바퀴   공기\n넣어야 해 "))
        val card = HandoverText.build(HandoverInput(socPercent = 41, previousNote = ledger.note), HandoverLength.NORMAL, t0, zone)
        assertEquals("뒷바퀴 공기 넣어야 해", card.lines.single { it.label == "이전 운전자 한마디" }.text)
        assertTrue(card.speech.contains("이전 운전자 한마디가 있어요. 뒷바퀴 공기 넣어야 해"))
        ledger.takenOver()
        assertNull(ledger.note)
        val next = HandoverText.build(HandoverInput(previousNote = ledger.note), HandoverLength.NORMAL, t0, zone)
        assertFalse(next.lines.any { it.label == "이전 운전자 한마디" })
    }
    @Test fun noteIsOneLineAndLimited() {
        val ledger = HandoverLedger()
        assertNull(ledger.leaveNote("   \n "))
        assertEquals(ReturnText.NOTE_MAX, ledger.leaveNote("가".repeat(200))!!.length)
    }
    @Test fun pairIsStoredAndTakeoverClosed() {
        val ledger = HandoverLedger()
        val takeover = snap(t0)
        assertTrue(ledger.isOpen(takeover)); assertFalse(ledger.isOpen(null))
        assertNull("Rental mode writes no trip line", ledger.returned(takeover, snap(t0.plusSeconds(600), 41, 12_378.0), HandoverMode.RENTAL))
        assertEquals(1, ledger.pairs.size); assertTrue(ledger.trips.isEmpty())
        assertFalse(ledger.isOpen(takeover))
    }
    @Test fun fleetModeWritesOneTripLine() {
        val ledger = HandoverLedger()
        val trip = ledger.returned(snap(t0), snap(t0.plusSeconds(2_400), 41, 12_378.0), HandoverMode.FLEET, " 중앙역 ")!!
        assertEquals(TripEntry(t0, t0.plusSeconds(2_400), 12_346.0, 12_378.0, "중앙역"), trip)
        assertEquals(32.0, trip.distanceKm!!, 0.0)
        assertEquals(listOf(trip), ledger.trips)
        val cells = ReturnText.tripCells(trip, zone)
        assertEquals(listOf("10월 26일", "12,346km", "12,378km", "32km", "중앙역", "14:00 → 14:40"), cells)
        val noName = ledger.returned(snap(t0.plusSeconds(9_000)), snap(t0.plusSeconds(9_600), 40, 12_383.0), HandoverMode.FLEET, null)!!
        assertNull(noName.destination); assertEquals("—", ReturnText.tripCells(noName, zone)[4])
        val month = ReturnText.months(ledger.trips, zone).single()
        assertEquals(YearMonth.of(2026, 10), month.month)
        assertEquals("2026년 10월 · 2건 · 합계 69km", ReturnText.monthTitle(month))
    }
    @Test fun tripsGroupByMonthNewestFirst() {
        val sep = TripEntry(Instant.parse("2026-09-30T14:00:00Z"), Instant.parse("2026-09-30T14:30:00Z"), 100.0, 110.0)
        // 2026-09-30T15:30Z is already 10월 1일 in Seoul.
        val oct = TripEntry(Instant.parse("2026-09-30T15:00:00Z"), Instant.parse("2026-09-30T15:30:00Z"), 110.0, 130.0)
        val months = ReturnText.months(listOf(sep, oct), zone)
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9)), months.map { it.month })
        assertEquals(20.0, months[0].totalKm, 0.0)
        val missing = TripEntry(sep.takeoverAt, sep.returnedAt, null, 110.0)
        assertNull(missing.distanceKm); assertEquals(0.0, TripMonth(YearMonth.of(2026, 9), listOf(missing)).totalKm, 0.0)
    }
    @Test fun keepsOnlyTheLastFiftyPairs() {
        val ledger = HandoverLedger()
        repeat(60) { i -> ledger.returned(snap(t0.plusSeconds(i * 100L)), snap(t0.plusSeconds(i * 100L + 50)), HandoverMode.RENTAL) }
        assertEquals(HandoverLedger.MAX_PAIRS, ledger.pairs.size)
        assertEquals(t0.plusSeconds(1_000), ledger.pairs.first().takeover.at)
        assertEquals(t0.plusSeconds(5_900), ledger.pairs.last().takeover.at)
        val restored = HandoverLedger(List(70) { i -> HandoverPair(snap(t0.plusSeconds(i.toLong())), snap(t0.plusSeconds(i + 1L))) })
        assertEquals(50, restored.pairs.size)
    }
    @Test fun sameTakeoverIsNotPairedTwice() {
        val ledger = HandoverLedger()
        val takeover = snap(t0)
        ledger.returned(takeover, snap(t0.plusSeconds(10)), HandoverMode.RENTAL)
        ledger.returned(takeover, snap(t0.plusSeconds(20)), HandoverMode.RENTAL)
        assertEquals(1, ledger.pairs.size); assertEquals(t0.plusSeconds(20), ledger.pairs.single().returned.at)
    }
    @Test fun clearRemovesEverything() {
        val ledger = HandoverLedger()
        ledger.leaveNote("메모"); ledger.returned(snap(t0), snap(t0.plusSeconds(5)), HandoverMode.FLEET)
        ledger.clear()
        assertTrue(ledger.pairs.isEmpty()); assertTrue(ledger.trips.isEmpty()); assertNull(ledger.note)
    }
}
