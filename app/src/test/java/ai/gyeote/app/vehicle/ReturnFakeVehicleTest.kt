package ai.gyeote.app.vehicle

import ai.gyeote.app.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** F3 end to end on FakeVehicle signals, wired the way AppController feeds JourneyMachine and the ledger. */
class ReturnFakeVehicleTest {
    private class Run {
        val fake = FakeVehicle()
        val journey = JourneyMachine()
        val ledger = HandoverLedger()
        var destination: String? = null
        var window: Boolean? = null
        var t = 0L
        init {
            fake.setGear(GearSelection.PARK); fake.setDoor(false)
            fake.onDestinationName { destination = it }
            fake.observeWindows { window = it }
            fake.observeGear { sample() }; fake.observeDoor { sample() }; fake.observeOdometer { sample() }
        }
        fun sample() {
            t += 100
            val door = fake.doorState
            journey.sample(VehicleSample(t, fake.odometerKm, door.open, door.locked, fake.brakePressed, fake.gearSelection, door.driverOpen))
        }
        fun input() = HandoverInput(socPercent = fake.energyState.socPercent, activeWarnings = fake.activeWarnings.map { WarningDictionary.lookup(it) },
            odometerKm = fake.odometerKm, doorOpen = fake.doorState.open, trunkOpen = fake.doorState.trunkOpen,
            chargePortOpen = fake.energyState.chargePortOpen, previousNote = ledger.note, windowOpen = window)
    }

    @Test fun takeoverDriveReturnNoteAndNextCard() {
        val run = Run()
        val fake = run.fake
        fake.setBattery(54); fake.setOdometer(12_346.0)
        val takeover = HandoverSnapshot.capture(Instant.parse("2026-10-26T05:00:00Z"), run.input())
        run.journey.handoverHandled(); run.journey.takeoverStarted()

        fake.setGear(GearSelection.DRIVE)
        fake.setDestinationName("중앙역")
        fake.setBattery(41); fake.setOdometer(12_378.0); fake.toggleWarning(WarningCode.TPMS)
        assertEquals(ReturnState.IDLE, run.journey.returnState)
        fake.setGear(GearSelection.PARK)
        assertEquals(ReturnState.IDLE, run.journey.returnState)
        fake.setDoor(true)
        assertEquals(ReturnState.DUE, run.journey.returnState)

        val now = HandoverSnapshot.capture(Instant.parse("2026-10-26T05:40:00Z"), run.input())
        val card = ReturnText.build(ReturnText.compare(takeover, now))
        assertEquals("54% → 41%", card.lines[0].text)
        assertEquals("+32km", card.lines[1].text)
        assertEquals("1개: 타이어 바람", card.lines[2].text)
        assertEquals("모두 닫혀 있어요.", card.lines.single { it.label == "창문" }.text)

        run.ledger.leaveNote("뒷바퀴 공기 확인해 주세요")
        val trip = run.ledger.returned(takeover, now, HandoverMode.FLEET, run.destination)
        run.journey.returnHandled(closed = true)
        assertEquals(1, run.ledger.pairs.size)
        assertEquals("중앙역", trip!!.destination); assertEquals(32.0, trip.distanceKm!!, 0.0)

        // The door closes: next boarding, the handover card shows the note.
        fake.setDoor(false)
        assertEquals(HandoverState.DUE, run.journey.handover)
        val next = HandoverText.build(run.input(), HandoverLength.NORMAL, now.at)
        assertEquals("뒷바퀴 공기 확인해 주세요", next.lines.single { it.label == "이전 운전자 한마디" }.text)
        // Same stop: opening the door again does not bring the return card back.
        fake.setDoor(true)
        assertEquals(ReturnState.IDLE, run.journey.returnState)
    }
    @Test fun windowOpenIsReported() {
        val run = Run()
        run.fake.setWindowOpen(true)
        assertEquals(true, run.window)
        run.fake.setWindowOpen(null)
        assertFalse(ReturnText.build(ReturnText.compare(HandoverSnapshot.capture(Instant.EPOCH, run.input()),
            HandoverSnapshot.capture(Instant.EPOCH, run.input()))).lines.any { it.label == "창문" })
    }
}
