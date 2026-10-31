package ai.gyeote.app.domain

/** F2 "first 3 minutes" missions. Real controls, parked only. Nothing here is stored. */
enum class TourMission(val title: String, val hint: String) {
    STEERING("핸들을 왼쪽 끝까지,\n그다음 오른쪽 끝까지", "양쪽 끝을 한 번씩 찍으면 통과예요. 어느 쪽부터 해도 괜찮아요."),
    BRAKE("브레이크를\n꾹 밟아 보세요", "발을 떼었다가 다시 밟아도 괜찮아요."),
    TURN_SIGNAL("방향지시등을\n켜 보세요", "왼쪽이든 오른쪽이든 괜찮아요."),
}

/** COMPLETED: every mission passed. SKIPPED: [건너뛰기]. LEFT_PARK: the gear left P, so the tour stops at once. */
enum class TourEnd { COMPLETED, SKIPPED, LEFT_PARK }

object TourPlan {
    /** Calibrated angle (console sign convention, see SteeringCalibration). */
    const val STEERING_LIMIT_DEGREES = 400f
    const val PASS_SPEECH = "좋아요."
    const val DONE = "준비 끝났어요. 안전 운전하세요."
    fun missions(mode: HandoverMode): List<TourMission> = when (mode) {
        HandoverMode.RENTAL, HandoverMode.FLEET -> TourMission.entries.toList()
        HandoverMode.FAMILY -> listOf(TourMission.STEERING, TourMission.BRAKE)
        HandoverMode.OFF -> emptyList()
    }
}

/**
 * One mission at a time, in plan order. Brake and turn signal pass on a fresh "on" (off -> on) seen while
 * their mission is current, so a pedal already held when the mission starts has to be pressed again.
 * The steering mission passes once both ends (<= -400 and >= +400) have been reached, in any order.
 */
class TourMachine(val missions: List<TourMission>, brakePressed: Boolean = false, signalOn: Boolean = false) {
    init { require(missions.isNotEmpty()) }
    var passed = 0
        private set
    var end: TourEnd? = null
        private set
    var leftReached = false
        private set
    var rightReached = false
        private set
    private var lastBrake = brakePressed
    private var lastSignal = signalOn

    val current: TourMission? get() = if (end == null) missions.getOrNull(passed) else null
    val remaining: Int get() = missions.size - passed
    val finished: Boolean get() = end == TourEnd.COMPLETED || end == TourEnd.SKIPPED

    /** Returns true when this reading passed a mission. */
    fun steering(degrees: Float?): Boolean {
        if (current != TourMission.STEERING || degrees == null || !degrees.isFinite()) return false
        if (degrees <= -TourPlan.STEERING_LIMIT_DEGREES) leftReached = true
        if (degrees >= TourPlan.STEERING_LIMIT_DEGREES) rightReached = true
        return leftReached && rightReached && pass()
    }
    fun brake(pressed: Boolean): Boolean {
        val fresh = pressed && !lastBrake
        lastBrake = pressed
        return fresh && current == TourMission.BRAKE && pass()
    }
    fun turnSignal(on: Boolean): Boolean {
        val fresh = on && !lastSignal
        lastSignal = on
        return fresh && current == TourMission.TURN_SIGNAL && pass()
    }
    /** Any gear but P ends an unfinished tour immediately. Returns true when this call ended it. */
    fun gear(value: GearSelection): Boolean {
        if (end != null || value == GearSelection.PARK) return false
        end = TourEnd.LEFT_PARK
        return true
    }
    /** Skips every remaining mission. */
    fun skip(): Boolean {
        if (end != null) return false
        end = TourEnd.SKIPPED
        return true
    }
    private fun pass(): Boolean {
        passed++
        if (passed >= missions.size) end = TourEnd.COMPLETED
        return true
    }
}
