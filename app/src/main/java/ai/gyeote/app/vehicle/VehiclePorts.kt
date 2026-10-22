package ai.gyeote.app.vehicle

import ai.gyeote.app.domain.Destination
import ai.gyeote.app.domain.WarningCode
import ai.gyeote.app.domain.GearSelection
import ai.gyeote.app.domain.TelltaleReading

fun interface Subscription { fun cancel() }
data class DoorState(val open: Boolean, val locked: Boolean)
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
)
