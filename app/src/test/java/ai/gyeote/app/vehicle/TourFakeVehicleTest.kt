package ai.gyeote.app.vehicle

import ai.gyeote.app.domain.*
import org.junit.Assert.*
import org.junit.Test

/** Drives the tour from FakeVehicle signals the same way AppController feeds it (raw SDK steering sign). */
class TourFakeVehicleTest {
    private class Run(mode: HandoverMode) {
        val fake = FakeVehicle()
        val passes = mutableListOf<TourMission>()
        val tour: TourMachine
        init {
            fake.setGear(GearSelection.PARK); fake.setBrake(false); fake.setTurnSignal(TurnSignal.OFF)
            tour = TourMachine(TourPlan.missions(mode), fake.brakePressed, fake.turnSignal == TurnSignal.LEFT || fake.turnSignal == TurnSignal.RIGHT)
            fun feed(passed: Boolean, before: TourMission?) { if (passed && before != null) passes += before }
            fake.observeSteeringAngle { val before = tour.current; feed(tour.steering(it), before) }
            fake.observeBrake { val before = tour.current; feed(tour.brake(it), before) }
            fake.observeTurnSignal { val before = tour.current; feed(tour.turnSignal(it == TurnSignal.LEFT || it == TurnSignal.RIGHT), before) }
            fake.observeGear { tour.gear(it) }
        }
    }

    @Test fun rentalTourPassesEndToEndWithConsoleValues() {
        val run = Run(HandoverMode.RENTAL)
        // Console steering=-450 arrives from the SDK as +450; the app sees -450.
        run.fake.setSteeringFromSdk(450f)
        assertEquals(-450f, run.fake.steeringAngle!!, 0f)
        assertTrue(run.tour.leftReached); assertEquals(TourMission.STEERING, run.tour.current)
        run.fake.setSteeringFromSdk(-450f)
        assertEquals(TourMission.BRAKE, run.tour.current)
        run.fake.setBrake(true)
        assertEquals(TourMission.TURN_SIGNAL, run.tour.current)
        run.fake.setTurnSignal(TurnSignal.LEFT)
        assertEquals(TourEnd.COMPLETED, run.tour.end)
        assertEquals(listOf(TourMission.STEERING, TourMission.BRAKE, TourMission.TURN_SIGNAL), run.passes)
        run.fake.setGear(GearSelection.DRIVE)
        assertEquals("finished result is not turned into an early end", TourEnd.COMPLETED, run.tour.end)
    }
    @Test fun rawSdkSignBelowTheLimitDoesNotPass() {
        val run = Run(HandoverMode.RENTAL)
        run.fake.setSteeringFromSdk(399f); run.fake.setSteeringFromSdk(-399f)
        assertEquals(TourMission.STEERING, run.tour.current); assertTrue(run.passes.isEmpty())
    }
    @Test fun driveInTheMiddleEndsTheTour() {
        val run = Run(HandoverMode.RENTAL)
        run.fake.setSteeringFromSdk(450f); run.fake.setSteeringFromSdk(-450f)
        run.fake.setGear(GearSelection.DRIVE)
        assertEquals(TourEnd.LEFT_PARK, run.tour.end)
        run.fake.setGear(GearSelection.PARK); run.fake.setBrake(true)
        assertEquals(listOf(TourMission.STEERING), run.passes)
    }
    @Test fun sharedCarModeEndsAfterTwoMissions() {
        val run = Run(HandoverMode.FAMILY)
        run.fake.setSteeringFromSdk(-500f); run.fake.setSteeringFromSdk(500f); run.fake.setBrake(true)
        assertEquals(TourEnd.COMPLETED, run.tour.end)
        run.fake.setTurnSignal(TurnSignal.RIGHT)
        assertEquals(2, run.passes.size)
    }
}
