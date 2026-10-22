package ai.gyeote.app.domain

import org.junit.Assert.*
import org.junit.Test

class JourneyMachineTest {
    private fun reading(t: Long, km: Double = 100.0, open: Boolean = false,
        locked: Boolean = !open, brake: Boolean = false, gear: GearSelection = GearSelection.UNKNOWN) = VehicleSample(t, km, open, locked, brake, gear)
    private fun depart(machine: JourneyMachine): JourneyEvent? {
        machine.sample(reading(0, brake = true))
        machine.sample(reading(2_000, brake = false))
        return machine.sample(reading(3_000, 100.2))
    }
    @Test fun requiresClosedLockedDoorBrakeCycleAndMovement() {
        val machine = JourneyMachine()
        assertNull(machine.sample(reading(0, brake = true)))
        assertNull(machine.sample(reading(500, 100.01)))
        assertNull(machine.sample(reading(2_000, 100.01)))
        assertEquals(JourneyEvent.DEPARTED, machine.sample(reading(3_000, 100.2)))
        assertEquals(JourneyPhase.TRAVELLING, machine.phase)
    }
    @Test fun noBrakeObservationCannotStartJourney() {
        val machine = JourneyMachine()
        machine.sample(reading(0))
        assertNull(machine.sample(reading(3_000, 101.0)))
    }
    @Test fun openOrUnlockedDoorCannotStartJourney() {
        val machine = JourneyMachine()
        machine.sample(reading(0, open = true, brake = true))
        assertNull(machine.sample(reading(4_000, 101.0, open = true)))
        machine.sample(reading(5_000, 101.0, locked = false, brake = true))
        assertNull(machine.sample(reading(8_000, 102.0, locked = false)))
    }
    @Test fun repeatedDistanceAndArrivalCallbacksEmitOnlyOnce() {
        val machine = JourneyMachine()
        assertEquals(JourneyEvent.DEPARTED, depart(machine))
        assertNull(machine.sample(reading(4_000, 101.0)))
        assertEquals(JourneyEvent.ARRIVED, machine.destinationArrived(5_000))
        assertNull(machine.destinationArrived(6_000))
    }
    @Test fun doorOpeningWhileMovingDoesNotMeanArrival() {
        val machine = JourneyMachine()
        depart(machine)
        assertNull(machine.sample(reading(4_000, 100.3, open = true)))
        assertNull(machine.sample(reading(7_000, 100.5, open = true)))
    }
    @Test fun stationaryDistanceAndStableOpenDoorProduceArrival() {
        val machine = JourneyMachine()
        depart(machine)
        assertNull(machine.sample(reading(23_000, 100.2, open = true)))
        assertEquals(JourneyEvent.ARRIVED, machine.sample(reading(25_000, 100.2, open = true)))
        assertNull(machine.sample(reading(26_000, 100.2, open = true)))
    }
    @Test fun bouncingDoorResetsArrivalDebounce() {
        val machine = JourneyMachine()
        depart(machine)
        machine.sample(reading(23_000, 100.2, open = true))
        machine.sample(reading(24_000, 100.2))
        assertNull(machine.sample(reading(25_000, 100.2, open = true)))
        assertEquals(JourneyEvent.ARRIVED, machine.sample(reading(27_000, 100.2, open = true)))
    }
    @Test fun postArrivalCooldownPreventsExtraDeparture() {
        val machine = JourneyMachine()
        depart(machine); machine.destinationArrived(5_000)
        machine.sample(reading(6_000, 101.0, brake = true))
        assertNull(machine.sample(reading(9_000, 101.2)))
        machine.sample(reading(21_000, 101.2, brake = true))
        machine.sample(reading(23_000, 101.2))
        assertEquals(JourneyEvent.DEPARTED, machine.sample(reading(24_000, 101.5)))
    }
    @Test fun invalidCounterAndOldSampleCannotCreateDeparture() {
        val machine = JourneyMachine()
        machine.sample(reading(10_000, brake = true))
        assertNull(machine.sample(reading(12_000, 99.0)))
        assertNull(machine.sample(reading(9_000, 102.0)))
        assertNull(machine.sample(reading(13_000, Double.NaN)))
        assertNull(machine.sample(reading(14_000, -1.0)))
        assertNull(machine.sample(reading(15_000, 99.5)))
    }
    @Test fun navigationCallbackWithoutDepartureDoesNotInventTrip() {
        assertNull(JourneyMachine().destinationArrived(50_000))
    }
    @Test fun parkToDriveStartsWithoutDistanceOrBrakeHeuristic() {
        val m = JourneyMachine()
        m.sample(reading(0, gear = GearSelection.PARK))
        assertEquals(JourneyEvent.DEPARTED, m.sample(reading(1_000, gear = GearSelection.DRIVE)))
        assertNull(m.sample(reading(2_000, gear = GearSelection.DRIVE)))
    }
    @Test fun driveAtStartupDoesNotInventDeparture() {
        val m = JourneyMachine()
        assertNull(m.sample(reading(0, gear = GearSelection.DRIVE)))
        assertNull(m.sample(reading(4_000, 110.0, gear = GearSelection.DRIVE)))
    }
    @Test fun knownParkSuppressesFallbackDeparture() {
        val m = JourneyMachine()
        m.sample(reading(0, brake = true, gear = GearSelection.PARK))
        assertNull(m.sample(reading(3_000, 101.0, gear = GearSelection.PARK)))
    }
    @Test fun gearWorksEvenWhenOdometerIsUnavailable() {
        val m = JourneyMachine()
        m.sample(reading(0, Double.NaN, gear = GearSelection.PARK))
        assertEquals(JourneyEvent.DEPARTED, m.sample(reading(1_000, Double.NaN, gear = GearSelection.DRIVE)))
        m.sample(reading(3_000, Double.NaN, open = true, gear = GearSelection.PARK))
        assertEquals(JourneyEvent.ARRIVED, m.sample(reading(5_000, Double.NaN, open = true, gear = GearSelection.PARK)))
    }
    @Test fun returnedParkAndStableDoorProduceArrival() {
        val m = JourneyMachine()
        m.sample(reading(0, gear = GearSelection.PARK)); m.sample(reading(1_000, gear = GearSelection.DRIVE))
        assertNull(m.sample(reading(4_000, gear = GearSelection.PARK)))
        assertNull(m.sample(reading(5_000, open = true, gear = GearSelection.PARK)))
        assertEquals(JourneyEvent.ARRIVED, m.sample(reading(7_000, open = true, gear = GearSelection.PARK)))
        assertNull(m.sample(reading(8_000, open = true, gear = GearSelection.PARK)))
    }
    @Test fun doorOpenInDriveDoesNotFinishEvenWhenStationary() {
        val m = JourneyMachine()
        m.sample(reading(0, gear = GearSelection.PARK)); m.sample(reading(1_000, gear = GearSelection.DRIVE))
        m.sample(reading(25_000, open = true, gear = GearSelection.DRIVE))
        assertNull(m.sample(reading(28_000, open = true, gear = GearSelection.DRIVE)))
    }
    @Test fun parkAloneIsNotArrival() {
        val m = JourneyMachine()
        m.sample(reading(0, gear = GearSelection.PARK)); m.sample(reading(1_000, gear = GearSelection.DRIVE))
        m.sample(reading(3_000, gear = GearSelection.PARK))
        assertNull(m.sample(reading(40_000, gear = GearSelection.PARK)))
    }
    @Test fun unknownBetweenParkAndDriveDoesNotInventTransition() {
        val m = JourneyMachine()
        m.sample(reading(0, gear = GearSelection.PARK)); m.sample(reading(1_000))
        assertNull(m.sample(reading(2_000, gear = GearSelection.DRIVE)))
    }
    @Test fun oldGearSampleIsIgnored() {
        val m = JourneyMachine()
        m.sample(reading(10_000, gear = GearSelection.PARK))
        assertNull(m.sample(reading(9_000, gear = GearSelection.DRIVE)))
        assertEquals(JourneyEvent.DEPARTED, m.sample(reading(11_000, gear = GearSelection.DRIVE)))
    }
    @Test fun gearDepartureRespectsArrivalCooldown() {
        val m = JourneyMachine()
        m.sample(reading(0, gear = GearSelection.PARK)); m.sample(reading(1_000, gear = GearSelection.DRIVE))
        m.destinationArrived(2_000)
        m.sample(reading(3_000, gear = GearSelection.PARK))
        assertNull(m.sample(reading(4_000, gear = GearSelection.DRIVE)))
        m.sample(reading(20_000, gear = GearSelection.PARK))
        assertEquals(JourneyEvent.DEPARTED, m.sample(reading(21_000, gear = GearSelection.DRIVE)))
    }
}
