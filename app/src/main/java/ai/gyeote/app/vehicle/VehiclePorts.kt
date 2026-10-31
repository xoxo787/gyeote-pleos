package ai.gyeote.app.vehicle

import ai.gyeote.app.domain.Destination
import ai.gyeote.app.domain.WarningCode
import ai.gyeote.app.domain.GearSelection
import ai.gyeote.app.domain.TelltaleReading

fun interface Subscription { fun cancel() }
/** [open] is any side door. Driver door and trunk stay null when the vehicle did not report them. */
data class DoorState(val open: Boolean, val locked: Boolean, val driverOpen: Boolean? = null, val trunkOpen: Boolean? = null)
interface TelltaleSource {
    fun observeTelltales(callback: (Set<WarningCode>) -> Unit): Subscription
    fun observeTelltaleDetails(callback: (List<TelltaleReading>) -> Unit): Subscription =
        observeTelltales { codes -> callback(codes.map { TelltaleReading(it) }) }
}
interface OdometerSource { fun observeOdometer(callback: (Double) -> Unit): Subscription }
interface DoorSource { fun observeDoor(callback: (DoorState) -> Unit): Subscription }
interface BrakeSource { fun observeBrake(callback: (Boolean) -> Unit): Subscription }
interface GearSource { fun observeGear(callback: (GearSelection) -> Unit): Subscription }
enum class WheelPosition(val label: String) { LEFT_FRONT("앞 왼쪽"), RIGHT_FRONT("앞 오른쪽"), LEFT_REAR("뒤 왼쪽"), RIGHT_REAR("뒤 오른쪽") }
/** Unit remains absent until the provider's numeric unit has been verified. No guessed thresholds. */
data class TireState(val lowPressure: Boolean? = null, val pressures: Map<WheelPosition, Float> = emptyMap(), val unit: String? = null) {
    init { require(pressures.values.all { it.isFinite() && it >= 0 }) }
}
interface TireSource {
    fun currentTires(): TireState
    fun observeTires(callback: (TireState) -> Unit): Subscription
    fun lowPressure(): Boolean? = currentTires().lowPressure
}
interface Navigator {
    val status: String get() = "길안내 요청 준비 중"
    fun requestRoute(destination: Destination): Boolean
    fun onDestinationArrived(callback: () -> Unit): Subscription
    /** Destination name from the navigation (route start or arrival), when it reports one. Fleet trip lines only. */
    fun onDestinationName(callback: (String) -> Unit): Subscription = Subscription { }
}
interface Speaker {
    val status: String
    fun speak(text: String)
    fun stop()
    fun close()
}
interface Listener {
    fun requestText(callback: (String) -> Unit)
    fun cancel()
}
/** Energy as reported by the vehicle; null means not received. Range is converted from the SDK's meters to km. */
data class EnergyState(val socPercent: Int? = null, val rangeKm: Double? = null, val chargePortOpen: Boolean? = null) {
    init {
        require(socPercent == null || socPercent in 0..100)
        require(rangeKm == null || (rangeKm.isFinite() && rangeKm >= 0))
    }
    companion object {
        fun soc(value: Int?): Int? = value?.takeIf { it in 0..100 }
        fun rangeFromMeters(meters: Float?): Double? = meters?.takeIf { it.isFinite() && it >= 0 }?.let { it / 1000.0 }
    }
}
interface EnergySource { fun observeEnergy(callback: (EnergyState) -> Unit): Subscription }
enum class TurnSignal { OFF, LEFT, RIGHT, UNKNOWN }
interface TurnSignalSource { fun observeTurnSignal(callback: (TurnSignal) -> Unit): Subscription }
/**
 * The emulator delivers the steering angle with the opposite sign of the Virtual Console (console +120 -> SDK -120, measured 10-26).
 * Ports publish the console convention; which physical side is positive is not verified yet.
 */
object SteeringCalibration {
    const val SDK_SIGN = -1f
    fun normalize(sdkDegrees: Float?): Float? = sdkDegrees?.takeIf { it.isFinite() }?.let { if (it == 0f) 0f else it * SDK_SIGN }
}
interface SteeringSource { fun observeSteeringAngle(callback: (Float?) -> Unit): Subscription }
/** True when any side window is open, false when all reported side windows are closed, null when not reported. */
interface WindowSource { fun observeWindows(callback: (Boolean?) -> Unit): Subscription }
data class SeatOccupancy(val driver: Boolean? = null, val passenger: Boolean? = null)
interface SeatSource { fun observeSeats(callback: (SeatOccupancy) -> Unit): Subscription }
/** Used when a vehicle does not offer a signal: one "not received" value, never a guess. */
object UnavailableSignals : EnergySource, TurnSignalSource, SteeringSource, SeatSource, WindowSource {
    override fun observeEnergy(callback: (EnergyState) -> Unit): Subscription { callback(EnergyState()); return Subscription { } }
    override fun observeTurnSignal(callback: (TurnSignal) -> Unit): Subscription { callback(TurnSignal.UNKNOWN); return Subscription { } }
    override fun observeSteeringAngle(callback: (Float?) -> Unit): Subscription { callback(null); return Subscription { } }
    override fun observeSeats(callback: (SeatOccupancy) -> Unit): Subscription { callback(SeatOccupancy()); return Subscription { } }
    override fun observeWindows(callback: (Boolean?) -> Unit): Subscription { callback(null); return Subscription { } }
}
interface DrivingStateSource { fun observeRestrictions(callback: (Boolean) -> Unit): Subscription }

data class VehicleAdapters(
    val telltales: TelltaleSource,
    val odometer: OdometerSource,
    val door: DoorSource,
    val brake: BrakeSource,
    val tire: TireSource,
    val navigator: Navigator,
    val speaker: Speaker,
    val listener: Listener,
    val driving: DrivingStateSource,
    val gear: GearSource,
    val simulated: Boolean = true,
    val close: () -> Unit = {},
    val energy: EnergySource = UnavailableSignals,
    val turnSignal: TurnSignalSource = UnavailableSignals,
    val steering: SteeringSource = UnavailableSignals,
    val seats: SeatSource = UnavailableSignals,
    val windows: WindowSource = UnavailableSignals,
)
