package ai.gyeote.app.vehicle

import org.junit.Assert.*
import org.junit.Test

class HandoverPortsTest {
    @Test fun steeringSignIsCorrected() {
        // Measured 10-26: console +120 arrives from the SDK as -120.
        assertEquals(120f, SteeringCalibration.normalize(-120f)!!, 0f)
        assertEquals(-200f, SteeringCalibration.normalize(200f)!!, 0f)
        assertEquals(0f, SteeringCalibration.normalize(-0f)!!, 0f)
        assertFalse(1f / SteeringCalibration.normalize(-0f)!! < 0) // no negative zero
        assertNull(SteeringCalibration.normalize(null))
        assertNull(SteeringCalibration.normalize(Float.NaN))
        assertNull(SteeringCalibration.normalize(Float.POSITIVE_INFINITY))
    }
    @Test fun fakeSteeringGoesThroughTheSameCorrection() {
        val fake = FakeVehicle(); val seen = mutableListOf<Float?>()
        val subscription = fake.observeSteeringAngle(seen::add)
        fake.setSteeringFromSdk(-120f); fake.setSteeringFromSdk(400f)
        subscription.cancel(); fake.setSteeringFromSdk(10f)
        assertEquals(listOf(0f, 120f, -400f), seen)
    }
    @Test fun rangeIsConvertedFromMetersAndSocIsBounded() {
        assertEquals(210.0, EnergyState.rangeFromMeters(210_000f)!!, 1e-9)
        assertNull(EnergyState.rangeFromMeters(null)); assertNull(EnergyState.rangeFromMeters(-1f)); assertNull(EnergyState.rangeFromMeters(Float.NaN))
        assertEquals(54, EnergyState.soc(54)); assertNull(EnergyState.soc(101)); assertNull(EnergyState.soc(-1)); assertNull(EnergyState.soc(null))
    }
    @Test fun fakeEnergyPathCarriesBatteryRangeAndChargePort() {
        val fake = FakeVehicle(); var last: EnergyState? = null
        fake.observeEnergy { last = it }
        assertEquals(EnergyState(80, 320.0, false), last)
        fake.setBattery(54, 210.0); fake.setChargePort(true)
        assertEquals(EnergyState(54, 210.0, true), last)
        fake.setBattery(150)
        assertNull("Out-of-range SOC becomes unknown", last!!.socPercent)
    }
    @Test fun fakeTurnSignalAndSeatsPublishChanges() {
        val fake = FakeVehicle(); val turns = mutableListOf<TurnSignal>(); var seats: SeatOccupancy? = null
        fake.observeTurnSignal(turns::add); fake.observeSeats { seats = it }
        fake.setTurnSignal(TurnSignal.LEFT); fake.setTurnSignal(TurnSignal.OFF)
        fake.setSeats(SeatOccupancy(driver = true, passenger = true))
        assertEquals(listOf(TurnSignal.OFF, TurnSignal.LEFT, TurnSignal.OFF), turns)
        assertEquals(SeatOccupancy(true, true), seats)
    }
    @Test fun trunkSurvivesDoorChangesAndDriverDoorDefaultsToUnknown() {
        val fake = FakeVehicle(); var door: DoorState? = null
        fake.observeDoor { door = it }
        fake.setTrunk(true); fake.setDoor(false)
        assertEquals(DoorState(open = false, locked = true, driverOpen = null, trunkOpen = true), door)
    }
    @Test fun fakeAdaptersExposeTheNewPortsAndUnavailableIsExplicit() {
        val fake = FakeVehicle()
        val adapters = fake.adapters(object : Speaker {
            override val status = "test"; override fun speak(text: String) = Unit; override fun stop() = Unit; override fun close() = Unit
        })
        assertSame(fake, adapters.energy); assertSame(fake, adapters.turnSignal); assertSame(fake, adapters.steering); assertSame(fake, adapters.seats)
        var energy: EnergyState? = null; var turn: TurnSignal? = null; var angle: Float? = 1f; var seat: SeatOccupancy? = null
        UnavailableSignals.observeEnergy { energy = it }; UnavailableSignals.observeTurnSignal { turn = it }
        UnavailableSignals.observeSteeringAngle { angle = it }; UnavailableSignals.observeSeats { seat = it }
        assertEquals(EnergyState(), energy); assertEquals(TurnSignal.UNKNOWN, turn); assertNull(angle); assertEquals(SeatOccupancy(), seat)
    }
}
