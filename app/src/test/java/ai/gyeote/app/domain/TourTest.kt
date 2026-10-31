package ai.gyeote.app.domain

import org.junit.Assert.*
import org.junit.Test

class TourMachineTest {
    private fun full(brake: Boolean = false, signal: Boolean = false) = TourMachine(TourPlan.missions(HandoverMode.RENTAL), brake, signal)

    @Test fun steeringNeedsBothEndsAtFourHundredDegrees() {
        val tour = full()
        assertEquals(TourMission.STEERING, tour.current)
        assertFalse(tour.steering(-399.9f)); assertFalse(tour.leftReached)
        assertFalse(tour.steering(-400f)); assertTrue(tour.leftReached)
        assertFalse(tour.steering(399.9f)); assertFalse(tour.rightReached)
        assertTrue(tour.steering(400f))
        assertEquals(TourMission.BRAKE, tour.current); assertEquals(1, tour.passed); assertEquals(2, tour.remaining)
    }
    @Test fun steeringOrderDoesNotMatter() {
        val tour = full()
        assertFalse(tour.steering(450f)); assertTrue(tour.rightReached)
        assertFalse(tour.steering(0f))
        assertTrue(tour.steering(-450f))
        assertEquals(TourMission.BRAKE, tour.current)
    }
    @Test fun invalidAnglesAndOutOfTurnInputsAreIgnored() {
        val tour = full()
        assertFalse(tour.steering(null)); assertFalse(tour.steering(Float.NaN)); assertFalse(tour.steering(Float.NEGATIVE_INFINITY))
        assertFalse(tour.leftReached || tour.rightReached)
        assertFalse("brake is not the current mission", tour.brake(true))
        assertFalse(tour.turnSignal(true))
        assertEquals(0, tour.passed)
        tour.steering(-500f); tour.steering(500f)
        assertFalse("steering no longer counts", tour.steering(-500f))
        assertEquals(1, tour.passed)
    }
    @Test fun brakeAndTurnSignalNeedAFreshOn() {
        val tour = full(brake = true, signal = true)
        tour.steering(-400f); tour.steering(400f)
        assertFalse("held since the start", tour.brake(true))
        assertFalse(tour.brake(false))
        assertTrue(tour.brake(true))
        assertEquals(TourMission.TURN_SIGNAL, tour.current)
        assertFalse("signal already on", tour.turnSignal(true))
        assertFalse(tour.turnSignal(false))
        assertTrue(tour.turnSignal(true))
        assertEquals(TourEnd.COMPLETED, tour.end); assertNull(tour.current); assertTrue(tour.finished)
        assertEquals(3, tour.passed); assertEquals(0, tour.remaining)
    }
    @Test fun brakePressedDuringAnEarlierMissionMustBePressedAgain() {
        val tour = full()
        assertFalse(tour.brake(true))
        tour.steering(-420f); tour.steering(420f)
        assertFalse(tour.brake(true))
        tour.brake(false)
        assertTrue(tour.brake(true))
    }
    @Test fun leavingParkEndsTheTourImmediately() {
        for (gear in listOf(GearSelection.DRIVE, GearSelection.REVERSE, GearSelection.NEUTRAL, GearSelection.UNKNOWN)) {
            val tour = full()
            assertFalse(tour.gear(GearSelection.PARK))
            tour.steering(-450f)
            assertTrue(tour.gear(gear))
            assertEquals(TourEnd.LEFT_PARK, tour.end); assertNull(tour.current); assertFalse(tour.finished)
            assertFalse("no input after the end", tour.steering(450f))
            assertFalse(tour.gear(GearSelection.PARK)); assertFalse(tour.skip())
            assertEquals(TourEnd.LEFT_PARK, tour.end)
        }
    }
    @Test fun skipEndsEveryRemainingMission() {
        val tour = full()
        tour.steering(-400f); tour.steering(400f)
        assertTrue(tour.skip())
        assertEquals(TourEnd.SKIPPED, tour.end); assertTrue(tour.finished)
        assertEquals(1, tour.passed); assertEquals(2, tour.remaining)
        assertFalse(tour.brake(true)); assertFalse(tour.skip())
        val atOnce = full(); assertTrue(atOnce.skip()); assertEquals(3, atOnce.remaining)
        val done = TourMachine(listOf(TourMission.BRAKE)); assertTrue(done.brake(true)); assertFalse(done.skip())
        assertEquals(TourEnd.COMPLETED, done.end)
        assertFalse("leaving P after the finish keeps the result", done.gear(GearSelection.DRIVE))
    }
    @Test fun missionCountsByMode() {
        assertEquals(listOf(TourMission.STEERING, TourMission.BRAKE, TourMission.TURN_SIGNAL), TourPlan.missions(HandoverMode.RENTAL))
        assertEquals(listOf(TourMission.STEERING, TourMission.BRAKE), TourPlan.missions(HandoverMode.FAMILY))
        assertEquals(3, TourPlan.missions(HandoverMode.FLEET).size)
        assertTrue(TourPlan.missions(HandoverMode.OFF).isEmpty())
        val family = TourMachine(TourPlan.missions(HandoverMode.FAMILY))
        family.steering(-400f); family.steering(400f); family.brake(true)
        assertEquals(TourEnd.COMPLETED, family.end)
        assertFalse("no third mission in the shared-car mode", family.turnSignal(true))
        assertThrows(IllegalArgumentException::class.java) { TourMachine(emptyList()) }
    }
    @Test fun tourWordingHasNoBannedWordsOrHonorifics() {
        val texts = TourMission.entries.flatMap { listOf(it.title, it.hint) } + TourPlan.DONE + TourPlan.PASS_SPEECH
        texts.forEach { assertEquals(it, emptyList<String>(), Wording.offending(it)) }
    }
}
