package ai.gyeote.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaintenanceTest {
    @Test fun noRecordDoesNotPretendVehicleWasServiced() {
        val due = MaintenanceRules.evaluate(12_000, emptyMap())
        assertTrue(due.all { it.lastKm == null && it.remainingKm == null && !it.isDue })
    }
    @Test fun demoCrossesTwelveThousandAtExactBoundary() {
        val records = mapOf(MaintenanceItem.ENGINE_OIL to 2_000L)
        val before = MaintenanceRules.evaluate(11_900, records).first { it.item == MaintenanceItem.ENGINE_OIL }
        val boundary = MaintenanceRules.evaluate(12_000, records).first { it.item == MaintenanceItem.ENGINE_OIL }
        assertEquals(100L, before.remainingKm)
        assertFalse(before.isDue)
        assertEquals(0L, boundary.remainingKm)
        assertTrue(boundary.isDue)
    }
    @Test fun overdueAndNewRecordUseActualDistance() {
        val old = mapOf(MaintenanceItem.ENGINE_OIL to 2_000L)
        assertTrue(MaintenanceRules.evaluate(12_500, old).first().isDue)
        val updated = MaintenanceRules.record(MaintenanceItem.ENGINE_OIL, 12_500, 12_500, old)
        assertEquals(10_000L, MaintenanceRules.evaluate(12_500, updated).first().remainingKm)
    }
    @Test fun impossibleFutureRecordsRemainUnknown() {
        assertNull(MaintenanceRules.evaluate(100, mapOf(MaintenanceItem.ENGINE_OIL to 200L)).first().lastKm)
    }
    @Test(expected = IllegalArgumentException::class) fun cannotRecordFutureDistance() {
        MaintenanceRules.record(MaintenanceItem.ENGINE_OIL, 301, 300, emptyMap())
    }
    @Test(expected = IllegalArgumentException::class) fun cannotRecordNegativeDistance() {
        MaintenanceRules.record(MaintenanceItem.ENGINE_OIL, -1, 300, emptyMap())
    }
    @Test(expected = IllegalArgumentException::class) fun cannotEvaluateNegativeOdometer() {
        MaintenanceRules.evaluate(-1, emptyMap())
    }
}
