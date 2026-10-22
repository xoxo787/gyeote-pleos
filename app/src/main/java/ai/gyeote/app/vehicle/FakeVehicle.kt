package ai.gyeote.app.vehicle

import ai.gyeote.app.domain.Destination
import ai.gyeote.app.domain.WarningCode
import ai.gyeote.app.domain.GearSelection

internal class Signal<T>(initial: T) {
    var value: T = initial
        private set
    private val callbacks = linkedSetOf<(T) -> Unit>()
    fun emit(next: T) { value = next; callbacks.toList().forEach { it(next) } }
    fun observe(callback: (T) -> Unit): Subscription {
        // A callable reference can hash its mutable receiver; use a stable wrapper for cancellation.
        val observer: (T) -> Unit = { callback(it) }
        callbacks += observer
        observer(value)
        return Subscription { callbacks -= observer }
    }
}

/** Owned by the main thread. All console actions go through the same ports as real signals. */
class FakeVehicle(initiallyRestricted: Boolean = false) : TelltaleSource, OdometerSource, DoorSource, BrakeSource, TireSource,
    GearSource, Navigator, Listener, DrivingStateSource {
    private val warnings = Signal(emptySet<WarningCode>())
    private val distance = Signal(11_900.0)
    private val doors = Signal(DoorState(open = true, locked = false))
    private val brake = Signal(true)
    private val restrictions = Signal(initiallyRestricted)
    private val gears = Signal(GearSelection.UNKNOWN)
    private val tires = Signal(TireState(lowPressure = false))
    private val arrivalListeners = linkedSetOf<() -> Unit>()
    private var textListener: ((String) -> Unit)? = null
    var tireLow: Boolean?
        get() = tires.value.lowPressure
        set(value) { tires.emit(tires.value.copy(lowPressure = value)) }
    var lastRoute: Destination? = null
        private set
    val odometerKm get() = distance.value
    val doorState get() = doors.value
    val brakePressed get() = brake.value
    val restricted get() = restrictions.value
    val activeWarnings get() = warnings.value
    val gearSelection get() = gears.value
    override val status get() = if (lastRoute == null) "모의 길안내 준비 중" else "모의 길안내 요청 기록됨 · 실제 경로는 열지 않았어요"

    override fun observeTelltales(callback: (Set<WarningCode>) -> Unit) = warnings.observe(callback)
    override fun observeOdometer(callback: (Double) -> Unit) = distance.observe(callback)
    override fun observeDoor(callback: (DoorState) -> Unit) = doors.observe(callback)
    override fun observeBrake(callback: (Boolean) -> Unit) = brake.observe(callback)
    override fun observeRestrictions(callback: (Boolean) -> Unit) = restrictions.observe(callback)
    override fun currentTires() = tires.value
    override fun observeTires(callback: (TireState) -> Unit) = tires.observe(callback)
    override fun observeGear(callback: (GearSelection) -> Unit) = gears.observe(callback)
    override fun requestRoute(destination: Destination): Boolean {
        if (restrictions.value) return false
        lastRoute = destination
        return true
    }
    override fun onDestinationArrived(callback: () -> Unit): Subscription {
        arrivalListeners += callback
        return Subscription { arrivalListeners -= callback }
    }
    override fun requestText(callback: (String) -> Unit) { textListener = callback }
    override fun cancel() { textListener = null }
    fun injectText(text: String) { textListener?.invoke(text) }
    fun setRestriction(restricted: Boolean) = restrictions.emit(restricted)
    fun setDoor(open: Boolean, locked: Boolean = !open) = doors.emit(DoorState(open, locked && !open))
    fun setBrake(pressed: Boolean) = brake.emit(pressed)
    fun setGear(gear: GearSelection) = gears.emit(gear)
    fun setTirePressure(wheel: WheelPosition, pressure: Float?, unit: String? = null) {
        val values = tires.value.pressures.toMutableMap()
        if (pressure != null && pressure.isFinite() && pressure >= 0) values[wheel] = pressure else values.remove(wheel)
        tires.emit(tires.value.copy(pressures = values, unit = unit))
    }
    fun setOdometer(km: Double) { require(km.isFinite() && km >= 0); distance.emit(km) }
    fun toggleWarning(code: WarningCode) = warnings.emit(
        if (code in warnings.value) warnings.value - code else warnings.value + code,
    )
    fun arrive() { arrivalListeners.toList().forEach { it() } }
    fun adapters(speaker: Speaker) = VehicleAdapters(this, this, this, this, this, this, speaker, this, this, this)
}
