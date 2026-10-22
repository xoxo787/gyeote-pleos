package ai.gyeote.app.vehicle

import ai.gyeote.app.domain.Destination
import ai.gyeote.app.domain.GearSelection
import ai.gyeote.app.domain.WarningCode
import org.junit.Assert.*
import org.junit.Test

class VehiclePortsTest {
    @Test fun defaultDetailedTelltalesPreservesFakePortsAndDoesNotInventMetadata() {
        val fake = FakeVehicle(); val readings = mutableListOf<ai.gyeote.app.domain.TelltaleReading>()
        val subscription = fake.observeTelltaleDetails { readings += it }
        fake.toggleWarning(WarningCode.ENGINE); subscription.cancel(); fake.toggleWarning(WarningCode.ABS)
        assertEquals(1, readings.size); assertEquals(WarningCode.ENGINE, readings.single().code)
        assertNull(readings.single().color); assertNull(readings.single().warningCount)
    }
    @Test fun gearSubscriptionGetsCurrentValueUpdatesAndCancels() {
        val fake = FakeVehicle()
        val seen = mutableListOf<GearSelection>()
        val subscription = fake.observeGear(seen::add)
        fake.setGear(GearSelection.PARK); fake.setGear(GearSelection.DRIVE)
        subscription.cancel(); fake.setGear(GearSelection.REVERSE)
        assertEquals(listOf(GearSelection.UNKNOWN, GearSelection.PARK, GearSelection.DRIVE), seen)
    }
    @Test fun tirePressureRetainsDecimalsAndDoesNotInferLowPressure() {
        val fake = FakeVehicle()
        fake.setTirePressure(WheelPosition.LEFT_FRONT, 183.75f)
        assertEquals(183.75f, fake.currentTires().pressures.getValue(WheelPosition.LEFT_FRONT), 0f)
        assertEquals(false, fake.lowPressure()); assertNull(fake.currentTires().unit)
    }
    @Test fun pressureAndWarningAreIndependent() {
        val fake = FakeVehicle()
        fake.tireLow = true
        fake.setTirePressure(WheelPosition.RIGHT_REAR, 245f)
        assertEquals(true, fake.lowPressure())
        assertEquals(245f, fake.currentTires().pressures.getValue(WheelPosition.RIGHT_REAR), 0f)
    }
    @Test fun invalidPressureClearsOnlyThatWheel() {
        val fake = FakeVehicle()
        fake.setTirePressure(WheelPosition.LEFT_FRONT, 210f); fake.setTirePressure(WheelPosition.RIGHT_FRONT, 220f)
        fake.setTirePressure(WheelPosition.LEFT_FRONT, Float.NaN)
        assertFalse(fake.currentTires().pressures.containsKey(WheelPosition.LEFT_FRONT))
        assertTrue(fake.currentTires().pressures.containsKey(WheelPosition.RIGHT_FRONT))
        fake.setTirePressure(WheelPosition.RIGHT_FRONT, -1f)
        assertTrue(fake.currentTires().pressures.isEmpty())
    }
    @Test fun tireSubscriptionIsCancelled() {
        val fake = FakeVehicle(); val seen = mutableListOf<TireState>()
        val subscription = fake.observeTires(seen::add)
        fake.setTirePressure(WheelPosition.LEFT_REAR, 219.5f, "kPa")
        subscription.cancel(); fake.tireLow = true
        assertEquals(2, seen.size); assertEquals("kPa", seen.last().unit)
    }
    @Test fun unknownTireStatusRemainsUnknown() {
        val fake = FakeVehicle(); fake.tireLow = null
        assertNull(fake.lowPressure()); assertTrue(fake.currentTires().pressures.isEmpty())
    }
    @Test fun fakeRouteIsRecordedOnlyWhenUnrestricted() {
        val fake = FakeVehicle(initiallyRestricted = true)
        val destination = Destination("demo", "child", "병원", "주소", 37.5, 127.0, "")
        assertFalse(fake.requestRoute(destination)); assertNull(fake.lastRoute)
        fake.setRestriction(false)
        assertTrue(fake.requestRoute(destination)); assertEquals(destination, fake.lastRoute)
    }
    @Test fun consoleSignalsStillUseTheSamePorts() {
        val fake = FakeVehicle(); var door: DoorState? = null; var brake = false; var km = 0.0
        var warnings = emptySet<WarningCode>(); var arrived = 0; var text = ""
        fake.observeDoor { door = it }; fake.observeBrake { brake = it }; fake.observeOdometer { km = it }
        fake.observeTelltales { warnings = it }; fake.onDestinationArrived { arrived++ }; fake.requestText { text = it }
        fake.setDoor(false); fake.setBrake(false); fake.setOdometer(12_000.0); fake.toggleWarning(WarningCode.ABS)
        fake.arrive(); fake.injectText("응")
        assertEquals(DoorState(false, true), door); assertFalse(brake); assertEquals(12_000.0, km, 0.0)
        assertEquals(setOf(WarningCode.ABS), warnings); assertEquals(1, arrived); assertEquals("응", text)
    }
}
