package ai.gyeote.app.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.gyeote.app.BuildConfig
import ai.gyeote.app.domain.GearSelection
import ai.gyeote.app.domain.WarningCode
import ai.gyeote.app.domain.TelltaleReading
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleColor
import ai.pleos.playground.vehicle.listener.*

/** Read-only vehicle integration; no credentials or SDK artifacts are stored by this adapter. */
object PleosAdapters {
    const val VEHICLE_SERVICE = "ai.pleos.playground.service.vehicle"
    fun available(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(VEHICLE_SERVICE, 0)
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun create(context: Context, speaker: Speaker, listener: Listener, publicTelltalesOnly: Boolean = false): VehicleAdapters? {
        if (!available(context)) return null
        return try { PleosVehicle(context, publicTelltalesOnly).adapters(speaker, listener) }
        catch (error: Exception) { vehicleLog("initialization unavailable: ${error.javaClass.simpleName}"); null }
    }
}

internal fun vehicleLog(value: String) { if (BuildConfig.DEBUG) Log.i("GyeoteVehicle", value) }

private class PleosVehicle(private val context: Context, private val publicTelltalesOnly: Boolean) : TelltaleSource, OdometerSource, DoorSource,
    BrakeSource, TireSource, GearSource {
    private val main = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context)
    private val driving = PlatformDrivingState(context)
    private val navigator = PleosNavigator(context) { driving.restricted }
    private val warnings = Signal(emptySet<WarningCode>())
    private val details = Signal(emptyList<TelltaleReading>())
    private val distance = Signal(Double.NaN)
    private val doors = Signal(DoorState(open = true, locked = false))
    private val brake = Signal(false)
    private val gears = Signal(GearSelection.UNKNOWN)
    private val tires = Signal(TireState())
    private val unregister = mutableListOf<() -> Unit>()
    private val positions = mutableMapOf<DoorValue, Boolean>()
    private val locks = mutableMapOf<DoorValue, Boolean>()
    private var closed = false
    private var telltalesReady = false
    private val privateSafety get() = context.checkSelfPermission("pleos.car.permission.PRIVATE_CAR_SAFETY") == PackageManager.PERMISSION_GRANTED

    private fun post(action: () -> Unit) { main.post { if (!closed) runCatching(action).onFailure { vehicleLog("callback unavailable: ${it.javaClass.simpleName}") } } }
    private fun failed(signal: String, error: Exception) = vehicleLog("$signal unavailable: ${error.javaClass.simpleName} ${error.message.orEmpty().take(200)}")
    private fun gear(value: VehicleGear?) = post {
        val next = when (value) {
            VehicleGear.GEAR_PARK -> GearSelection.PARK
            VehicleGear.GEAR_DRIVE -> GearSelection.DRIVE
            VehicleGear.GEAR_REVERSE -> GearSelection.REVERSE
            VehicleGear.GEAR_NEUTRAL -> GearSelection.NEUTRAL
            else -> GearSelection.UNKNOWN
        }
        vehicleLog("gear=$next"); gears.emit(next); driving.gear(next)
    }
    private fun telltales(value: Telltales?) = post {
        if (value == null) return@post
        val readings = value.telltales.filter { it.state != TelltaleState.OFF }.map { item ->
            val code = WarningCode.entries.firstOrNull { it.name == item.type.name }
                ?: when {
                    item.type.name == "ELECTRONIC_PARKING_BRAKE" -> WarningCode.PARKING_BRAKE
                    item.type.name.startsWith("ACCEL_RESPONSE_") -> WarningCode.SAFETY_ASSIST
                    else -> WarningCode.UNKNOWN
                }
            TelltaleReading(code, item.type.name, item.color, item.priority, item.warningCount,
                // In the pinned Vehicle 2.3.2 SDK, color IDs 0..5 match the public enum order.
                TelltaleColor.entries.getOrNull(item.color)?.name)
        }
        val codes = readings.map { it.code }.toSet()
        vehicleLog("telltales=${codes.joinToString { it.name }}")
        warnings.emit(codes)
        telltalesReady = true
        details.emit(readings)
        // Do not infer low pressure from an unverified numeric threshold.
        tires.emit(tires.value.copy(lowPressure = WarningCode.TPMS in codes))
    }
    private fun door() {
        val next = DoorState(open = positions.isEmpty() || positions.values.any { it },
            locked = locks.isNotEmpty() && locks.values.all { it })
        vehicleLog("doorOpen=${next.open} locked=${next.locked}"); doors.emit(next)
    }
    private fun pressure(wheel: WheelPosition, value: Float?) = post {
        val readings = tires.value.pressures.toMutableMap()
        if (value != null && value.isFinite() && value >= 0) readings[wheel] = value else readings.remove(wheel)
        tires.emit(tires.value.copy(pressures = readings))
        vehicleLog("tire=$wheel pressure=$value unit=unverified")
    }
    init {
        vehicle.initialize()
        // Vehicle.initialize binds asynchronously; register/read after the initial connection window.
        main.postDelayed({ if (!closed) subscribe() }, 1_000)
        main.postDelayed(object : Runnable {
            override fun run() {
                if (closed) return
                runCatching { vehicle.getSafety().getTelltalesInfo(::telltales) { failed("telltaleRead", it) } }
                main.postDelayed(this, 3_000)
            }
        }, 1_500)
    }
    private fun subscribe() {
        runCatching {
            val source = vehicle.getGear()
            val listener = object : GearSelectionListener {
                override fun onSelected(value: VehicleGear?) = gear(value)
                override fun onFailed(exception: Exception) { failed("gear", exception); gear(null) }
            }
            source.registerGearSelection(listener); unregister += { source.unregisterGearSelection(listener) }
            source.getCurrentGearSelectionState(::gear) { listener.onFailed(it) }
        }.onFailure { vehicleLog("gear unavailable: ${it.javaClass.simpleName}") }
        runCatching {
            val source = vehicle.getSafety()
            val listener = object : TelltaleInfoListener {
                override fun onTelltaleInfoUpdated(value: Telltales?) = telltales(value)
                override fun onFailed(exception: Exception) = failed("telltale", exception)
            }
            if (privateSafety && !publicTelltalesOnly) {
                source.registerTelltales(listener); unregister += { source.unregisterTelltales(listener) }
                vehicleLog("telltalePath=privateSubscription+publicPoll")
            } else vehicleLog("telltalePath=publicPoll")
            source.getTelltalesInfo(::telltales) { failed("telltale", it) }
        }.onFailure { vehicleLog("telltale unavailable: ${it.javaClass.simpleName}") }
        runCatching {
            val source = vehicle.getOdometer()
            val listener = object : DistanceDrivenListener {
                override fun onDistanceDrivenUpdated(value: Float?) = post {
                    if (value != null && value.isFinite() && value >= 0) { distance.emit(value.toDouble()); vehicleLog("odometerKm=$value") }
                }
                override fun onFailed(exception: Exception) = failed("odometer", exception)
            }
            source.registerDistanceDriven(listener); unregister += { source.unregisterDistanceDriven(listener) }
            source.getCurrentDistanceDriven(listener::onDistanceDrivenUpdated) { failed("odometer", it) }
        }.onFailure { vehicleLog("odometer unavailable: ${it.javaClass.simpleName}") }
        runCatching {
            val source = vehicle.getBrake()
            val listener = object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(value: Boolean?) = post { if (value != null) { brake.emit(value); vehicleLog("brakePressed=$value") } }
                override fun onFailed(exception: Exception) = failed("brake", exception)
            }
            source.registerBrakePedalStatus(listener); unregister += { source.unregisterBrakePedalStatus(listener) }
            source.getCurrentBrakePedalStatus(listener::onBrakePedalStatusUpdated) { failed("brake", it) }
        }.onFailure { vehicleLog("brake unavailable: ${it.javaClass.simpleName}") }
        runCatching {
            val source = vehicle.getDoor()
            source.checkDoorPositionCapabilities(onComplete = { _, supported ->
                supported.keys.forEach { area ->
                    val key = area.values.firstOrNull() ?: return@forEach
                    if (key !in listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)) return@forEach
                    val listener = object : DoorPositionListener {
                        override fun onPositionUpdated(value: Int?) = post { if (value != null) { positions[key] = value > 0; door() } }
                        override fun onFailed(exception: Exception) = failed("door", exception)
                    }
                    post {
                        source.registerDoorPosition(area, listener); unregister += { source.unregisterDoorPosition(area, listener) }
                        source.getCurrentDoorPosition(area, listener::onPositionUpdated) { failed("door", it) }
                    }
                }
            }, onFailed = { failed("doorCapabilities", it) })
            source.checkDoorLockCapabilities(onComplete = { _, supported ->
                supported.forEach { area ->
                    val key = area.values.firstOrNull() ?: return@forEach
                    val listener = object : DoorLockStateListener {
                        override fun onDoorLockStateUpdated(value: Boolean?) = post { if (value != null) { locks[key] = value; door() } }
                        override fun onFailed(exception: Exception) = failed("doorLock", exception)
                    }
                    post {
                        source.registerDoorLockState(area, listener); unregister += { source.unregisterDoorLockState(area, listener) }
                        source.getDoorLockState(area, listener::onDoorLockStateUpdated) { failed("doorLock", it) }
                    }
                }
            }, onFailed = { failed("doorLockCapabilities", it) })
        }.onFailure { vehicleLog("doors unavailable: ${it.javaClass.simpleName}") }
        if (privateSafety) runCatching {
            val source = vehicle.getTire()
            source.checkTirePressureCapabilities(onComplete = { _, supported ->
                supported.forEach { area ->
                    val wheel = area.values.firstOrNull()?.name?.let { n -> WheelPosition.entries.firstOrNull { it.name == n } } ?: return@forEach
                    val listener = object : TirePressureListener {
                        override fun onTirePressureUpdated(value: Float?) = pressure(wheel, value)
                        override fun onFailed(exception: Exception) { failed("tire", exception); pressure(wheel, null) }
                    }
                    post {
                        source.registerTirePressure(area, listener); unregister += { source.unregisterTirePressure(area, listener) }
                        source.getTirePressure(area, { pressure(wheel, it) }, { listener.onFailed(it) })
                    }
                }
            }, onFailed = { failed("tireCapabilities", it) })
        }.onFailure { vehicleLog("tires unavailable: ${it.javaClass.simpleName}") }
    }
    override fun observeTelltales(callback: (Set<WarningCode>) -> Unit) = warnings.observe(callback)
    override fun observeTelltaleDetails(callback: (List<TelltaleReading>) -> Unit) = details.observe {
        if (telltalesReady) callback(it)
    }
    override fun observeOdometer(callback: (Double) -> Unit) = distance.observe(callback)
    override fun observeDoor(callback: (DoorState) -> Unit) = doors.observe(callback)
    override fun observeBrake(callback: (Boolean) -> Unit) = brake.observe(callback)
    override fun observeGear(callback: (GearSelection) -> Unit) = gears.observe(callback)
    override fun observeTires(callback: (TireState) -> Unit) = tires.observe(callback)
    override fun currentTires() = tires.value
    fun adapters(speaker: Speaker, listener: Listener) = VehicleAdapters(this, this, this, this, this, navigator,
        speaker, listener, driving, this, simulated = false, close = ::close)
    private fun close() {
        closed = true; main.removeCallbacksAndMessages(null)
        unregister.asReversed().forEach { runCatching(it) }
        driving.close(); navigator.close(); runCatching { vehicle.release() }
    }
}
