package ai.gyeote.app.domain

enum class GearSelection { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }

data class VehicleSample(
    val atMillis: Long,
    val odometerKm: Double,
    val doorOpen: Boolean,
    val locked: Boolean,
    val brakePressed: Boolean,
    val gear: GearSelection = GearSelection.UNKNOWN,
)

enum class JourneyEvent { DEPARTED, ARRIVED }
enum class JourneyPhase { PARKED, TRAVELLING }

/** P -> D is primary; without any gear signal, retain the door/brake/distance fallback. */
class JourneyMachine(
    private val minimumTravelKm: Double = 0.1,
    private val doorStableMillis: Long = 2_000,
    private val stopMillis: Long = 20_000,
    private val cooldownMillis: Long = 15_000,
) {
    var phase = JourneyPhase.PARKED
        private set
    private var last: VehicleSample? = null
    private var baselineKm: Double? = null
    private var brakeSeen = false
    private var closedSince: Long? = null
    private var openSince: Long? = null
    private var lastMovementAt: Long? = null
    private var cooldownUntil = Long.MIN_VALUE
    private var gearSeen = false
    private var lastGear = GearSelection.UNKNOWN
    private var parkedSince: Long? = null

    fun sample(value: VehicleSample): JourneyEvent? {
        val previous = last
        if (previous != null && value.atMillis < previous.atMillis) return null
        val gearDeparture = lastGear == GearSelection.PARK && value.gear == GearSelection.DRIVE
        if (value.gear != GearSelection.UNKNOWN) gearSeen = true
        lastGear = value.gear
        if (value.gear == GearSelection.PARK) {
            if (parkedSince == null) parkedSince = value.atMillis
        } else parkedSince = null
        if (phase == JourneyPhase.PARKED && value.atMillis >= cooldownUntil && gearDeparture) {
            phase = JourneyPhase.TRAVELLING
            last = value
            lastMovementAt = value.atMillis
            brakeSeen = false
            return JourneyEvent.DEPARTED
        }
        if (phase == JourneyPhase.TRAVELLING && value.gear == GearSelection.PARK && value.doorOpen) {
            if (openSince == null) openSince = value.atMillis
            val stablePark = parkedSince?.let { value.atMillis - it >= doorStableMillis } == true
            if (stablePark && value.atMillis - (openSince ?: value.atMillis) >= doorStableMillis) {
                last = value
                return finish(value.atMillis, value.odometerKm.takeIf { it.isFinite() && it >= 0 } ?: baselineKm ?: 0.0)
            }
        } else if (!value.doorOpen) openSince = null
        if (!value.odometerKm.isFinite() || value.odometerKm < 0) return null
        last = value
        // A reset/counter rollback cannot count as a departure or arrival.
        if (previous != null && value.odometerKm < previous.odometerKm - 0.01) {
            baselineKm = value.odometerKm
            lastMovementAt = value.atMillis
            brakeSeen = false
            openSince = null
            return null
        }
        if (baselineKm == null) baselineKm = value.odometerKm
        val moved = previous != null && value.odometerKm > previous.odometerKm + 0.005
        if (moved) lastMovementAt = value.atMillis
        if (!value.doorOpen && value.locked) {
            if (closedSince == null) closedSince = value.atMillis
        } else closedSince = null
        if (value.doorOpen) {
            if (openSince == null) openSince = value.atMillis
        } else openSince = null

        if (phase == JourneyPhase.PARKED) {
            if (value.atMillis < cooldownUntil) {
                baselineKm = value.odometerKm
                return null
            }
            if (value.brakePressed && !value.doorOpen && value.locked) brakeSeen = true
            val stableClosed = closedSince?.let { value.atMillis - it >= doorStableMillis } == true
            if (!gearSeen && stableClosed && brakeSeen && !value.brakePressed &&
                value.odometerKm - (baselineKm ?: value.odometerKm) >= minimumTravelKm) {
                phase = JourneyPhase.TRAVELLING
                lastMovementAt = value.atMillis
                brakeSeen = false
                return JourneyEvent.DEPARTED
            }
            if (value.doorOpen) { baselineKm = value.odometerKm; brakeSeen = false }
        } else {
            val stopped = lastMovementAt?.let { value.atMillis - it >= stopMillis } == true
            val stableOpen = openSince?.let { value.atMillis - it >= doorStableMillis } == true
            if (!gearSeen && stopped && stableOpen) return finish(value.atMillis, value.odometerKm)
        }
        return null
    }

    fun destinationArrived(atMillis: Long): JourneyEvent? =
        if (phase == JourneyPhase.TRAVELLING) finish(atMillis, last?.odometerKm ?: 0.0) else null

    private fun finish(atMillis: Long, odometer: Double): JourneyEvent {
        phase = JourneyPhase.PARKED
        baselineKm = odometer
        cooldownUntil = atMillis + cooldownMillis
        brakeSeen = false
        closedSince = null
        openSince = null
        return JourneyEvent.ARRIVED
    }
}
