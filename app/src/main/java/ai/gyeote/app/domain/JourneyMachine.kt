package ai.gyeote.app.domain

enum class GearSelection { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }

data class VehicleSample(
    val atMillis: Long,
    val odometerKm: Double,
    val doorOpen: Boolean,
    val locked: Boolean,
    val brakePressed: Boolean,
    val gear: GearSelection = GearSelection.UNKNOWN,
    /** Null when the vehicle reports only "any door"; [doorOpen] is used then. */
    val driverDoorOpen: Boolean? = null,
)

enum class JourneyEvent { DEPARTED, ARRIVED }
enum class JourneyPhase { PARKED, TRAVELLING }
/** F1 boarding state: IDLE until a new boarding in P, DUE while the handover card should be offered. */
enum class HandoverState { IDLE, DUE }
/** F3: DUE while the return card should be offered (open takeover, driven in D since, then P + driver door open). */
enum class ReturnState { IDLE, DUE }

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
    /** One card per boarding: re-armed only after the gear has left P. */
    var handover = HandoverState.IDLE
        private set
    private var handoverArmed = true
    private var startChecked = false
    private var driverDoorOpenedInPark = false

    /** The card was answered or dismissed; the same boarding does not offer it again. */
    fun handoverHandled() { handover = HandoverState.IDLE }

    var returnState = ReturnState.IDLE
        private set
    private var takeoverOpen = false
    private var drivenSinceTakeover = false
    private var returnArmed = false

    /** [인수했어요]/[처음 타요]: a new takeover; the return card needs a drive in D first. */
    fun takeoverStarted() { takeoverOpen = true; drivenSinceTakeover = false; returnArmed = false; returnState = ReturnState.IDLE }
    /** After a restart: a stored takeover without its return is still open (a drive is still needed). */
    fun takeoverRestored(open: Boolean) { if (open && !takeoverOpen) { takeoverOpen = true; drivenSinceTakeover = false } }
    /** [돌려줬어요] closes the takeover; [나중에] only hides the card for this stop. */
    fun returnHandled(closed: Boolean) {
        returnState = ReturnState.IDLE
        if (closed) { takeoverOpen = false; drivenSinceTakeover = false; returnArmed = false }
    }
    /** "Clear all": no takeover left to return. */
    fun takeoverCleared() = returnHandled(closed = true)

    private fun boarding(value: VehicleSample) {
        val driverOpen = value.driverDoorOpen ?: value.doorOpen
        when (value.gear) {
            GearSelection.UNKNOWN -> Unit
            GearSelection.PARK -> {
                // App started while parked: offer once.
                if (!startChecked) { startChecked = true; offerHandover() }
                // F3: once per stop, only after a drive in D since the takeover.
                if (driverOpen && takeoverOpen && drivenSinceTakeover && returnArmed) {
                    returnArmed = false
                    returnState = ReturnState.DUE
                }
                if (driverOpen) driverDoorOpenedInPark = true
                else if (driverDoorOpenedInPark) {
                    driverDoorOpenedInPark = false
                    // The door closed on a new boarding: an unanswered return card gives way to the handover card.
                    if (handoverArmed) returnState = ReturnState.IDLE
                    offerHandover()
                }
            }
            else -> {
                // Never while driving; a later P + driver door open -> closed is a new boarding.
                startChecked = true
                handover = HandoverState.IDLE
                handoverArmed = true
                driverDoorOpenedInPark = false
                returnState = ReturnState.IDLE
                if (value.gear == GearSelection.DRIVE && takeoverOpen) { drivenSinceTakeover = true; returnArmed = true }
            }
        }
    }
    private fun offerHandover() {
        if (!handoverArmed) return
        handoverArmed = false
        handover = HandoverState.DUE
    }

    fun sample(value: VehicleSample): JourneyEvent? {
        val previous = last
        if (previous != null && value.atMillis < previous.atMillis) return null
        boarding(value)
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
