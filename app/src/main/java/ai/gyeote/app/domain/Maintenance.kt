package ai.gyeote.app.domain

enum class MaintenanceItem(val title: String, val intervalKm: Long) {
    ENGINE_OIL("엔진오일", 10_000), AIR_FILTER("에어클리너", 15_000),
    TIRES("타이어 상태", 10_000), BRAKES("브레이크 상태", 20_000),
}

data class MaintenanceDue(val item: MaintenanceItem, val lastKm: Long?, val remainingKm: Long?) {
    val isDue: Boolean get() = remainingKm != null && remainingKm <= 0
}

object MaintenanceRules {
    // Demonstration intervals only. The vehicle's own manual takes precedence.
    fun evaluate(odometerKm: Long, records: Map<MaintenanceItem, Long>): List<MaintenanceDue> {
        require(odometerKm >= 0)
        return MaintenanceItem.entries.map { item ->
            val last = records[item]?.takeIf { it in 0..odometerKm }
            MaintenanceDue(item, last, last?.let { item.intervalKm - (odometerKm - it) })
        }
    }

    fun record(item: MaintenanceItem, servicedKm: Long, currentKm: Long,
        records: Map<MaintenanceItem, Long>): Map<MaintenanceItem, Long> {
        require(servicedKm in 0..currentKm) { "정비 거리를 다시 살펴 주세요." }
        return records + (item to servicedKm)
    }
}
