package ai.gyeote.buzzer.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.gyeote.buzzer.BuildConfig
import ai.gyeote.buzzer.domain.CarFacts
import ai.gyeote.buzzer.domain.Seat
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener

internal fun carLog(value: String) { if (BuildConfig.DEBUG) Log.i("BuzzerCar", value) }

/**
 * Read-only bridge from the Pleos Vehicle SDK to [CarListener]. It never writes to the car:
 * windows are moved by people with the switch on their own door, never by this app.
 *
 * Signals used (all measured on the Pleos Connect 2.1.0 emulator):
 * - gear: P gate for the whole game
 * - side window position x4: each seat's buzzer
 * - EV battery %: the clock of a charging session
 * - charge switch (polled, the SDK has no listener for it), charge port, range, odometer: session mode and car-made questions
 */
class PleosCar private constructor(context: Context, private val listener: CarListener) : Car {
    private val main = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(context)
    private val unregister = mutableListOf<() -> Unit>()
    private var facts = CarFacts()
    private var closed = false
    private var lastGear: VehicleGear? = null
    private var gearSeen = false

    private val seats = mapOf(
        // ROW_1_LEFT is the driver's window on left-hand-drive cars, as sold in Korea.
        WindowValue.ROW_1_LEFT to Seat.DRIVER, WindowValue.ROW_1_RIGHT to Seat.PASSENGER,
        WindowValue.ROW_2_LEFT to Seat.REAR_LEFT, WindowValue.ROW_2_RIGHT to Seat.REAR_RIGHT,
    )

    init {
        vehicle.initialize()
        // Vehicle.initialize binds asynchronously; register after the initial connection window.
        main.postDelayed({ if (!closed) subscribe() }, 1_000)
    }

    private fun post(action: () -> Unit) {
        main.post { if (!closed) runCatching(action).onFailure { carLog("callback unavailable: ${it.javaClass.simpleName}") } }
    }
    private fun failed(signal: String, error: Exception) = carLog("$signal unavailable: ${error.javaClass.simpleName} ${error.message.orEmpty().take(120)}")
    private fun update(change: CarFacts.() -> CarFacts) = post {
        val next = facts.change()
        if (next != facts) { facts = next; listener.onFacts(next) }
    }
    private fun gear(value: VehicleGear?) = post {
        if (!gearSeen || value != lastGear) { gearSeen = true; lastGear = value; carLog("gear=$value") }
        listener.onGear(value == VehicleGear.GEAR_PARK)
    }

    private fun subscribe() {
        runCatching {
            val source = vehicle.getGear()
            val gearListener = object : GearSelectionListener {
                override fun onSelected(value: VehicleGear?) = gear(value)
                override fun onFailed(exception: Exception) { failed("gear", exception); gear(null) }
            }
            source.registerGearSelection(gearListener); unregister += { source.unregisterGearSelection(gearListener) }
            // Besides the listener, the gear is read again twice a second. The first value after registration can be
            // stale (P, then the real D 11 ms later, measured 2026-11-11), so the P gate never rests on one callback.
            main.post(object : Runnable {
                override fun run() {
                    if (closed) return
                    runCatching { source.getCurrentGearSelectionState(::gear) { gearListener.onFailed(it) } }
                    main.postDelayed(this, 500)
                }
            })
        }.onFailure { carLog("gear unavailable: ${it.javaClass.simpleName}") }

        runCatching {
            val source = vehicle.getWindow()
            source.checkWindowPositionCapabilities(onComplete = { _, supported ->
                supported.keys.forEach { area ->
                    val seat = seats[area.values.firstOrNull()] ?: return@forEach
                    val windowListener = object : WindowPositionListener {
                        override fun onWindowPositionUpdated(value: Int?) = post {
                            if (value != null) { carLog("window=$seat position=$value"); listener.onWindow(seat, value) }
                        }
                        override fun onFailed(exception: Exception) = failed("window", exception)
                    }
                    post {
                        source.registerWindowPosition(area, windowListener); unregister += { source.unregisterWindowPosition(area, windowListener) }
                        source.getCurrentWindowPosition(area, windowListener::onWindowPositionUpdated) { failed("window", it) }
                    }
                }
            }, onFailed = { failed("windowCapabilities", it) })
        }.onFailure { carLog("windows unavailable: ${it.javaClass.simpleName}") }

        runCatching {
            val source = vehicle.getEvBattery()
            val soc = object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                    carLog("socPercent=$value")
                    update { copy(socPercent = value?.takeIf { it in 0..100 }) }
                }
                override fun onFailed(exception: Exception) = failed("soc", exception)
            }
            source.registerEvBatteryStateOfCharge(soc); unregister += { source.unregisterEvBatteryStateOfCharge(soc) }
            source.getEvBatteryStateOfCharge(soc::onEvBatteryStateOfChargeUpdated) { failed("soc", it) }
            val port = object : EvChargePortOpenListener {
                override fun onEvChargePortOpenUpdated(value: Boolean?) {
                    carLog("chargePortOpen=$value")
                    update { copy(chargePortOpen = value) }
                }
                override fun onFailed(exception: Exception) = failed("chargePort", exception)
            }
            source.registerEvChargePortOpen(port); unregister += { source.unregisterEvChargePortOpen(port) }
            source.isEvChargePortOpen(port::onEvChargePortOpenUpdated) { failed("chargePort", it) }
            // The SDK offers only a getter for the charge switch, so it is read every 1.5 s.
            main.post(object : Runnable {
                private var last: Boolean? = null
                override fun run() {
                    if (closed) return
                    runCatching {
                        source.getEvChargeSwitchState({ value ->
                            if (value != last) { last = value; carLog("charging=$value") }
                            update { copy(charging = value) }
                        }, { failed("chargeSwitch", it) })
                    }
                    main.postDelayed(this, 1_500)
                }
            })
        }.onFailure { carLog("evBattery unavailable: ${it.javaClass.simpleName}") }

        runCatching {
            val source = vehicle.getOdometer()
            val distance = object : DistanceDrivenListener {
                override fun onDistanceDrivenUpdated(value: Float?) {
                    if (value != null && value.isFinite() && value >= 0) update { copy(odometerKm = value.toDouble()) }
                }
                override fun onFailed(exception: Exception) = failed("odometer", exception)
            }
            source.registerDistanceDriven(distance); unregister += { source.unregisterDistanceDriven(distance) }
            source.getCurrentDistanceDriven(distance::onDistanceDrivenUpdated) { failed("odometer", it) }
            val range = object : RangeRemainingListener {
                // The SDK reports meters (measured 2026-10-26).
                override fun onRangeRemainingChanged(value: Float?) {
                    if (value != null && value.isFinite() && value >= 0) update { copy(rangeKm = value / 1000.0) }
                }
                override fun onFailed(exception: Exception) = failed("range", exception)
            }
            source.registerRangeRemainingListener(range); unregister += { source.unregisterRangeRemainingListener(range) }
            source.getRangeRemainingDistance(range::onRangeRemainingChanged) { failed("range", it) }
        }.onFailure { carLog("odometer unavailable: ${it.javaClass.simpleName}") }
    }

    override fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        unregister.asReversed().forEach { runCatching(it) }
        runCatching { vehicle.release() }
    }

    companion object {
        private const val VEHICLE_SERVICE = "ai.pleos.playground.service.vehicle"

        /** Null when this device has no Pleos vehicle service (for example a phone). */
        fun open(context: Context, listener: CarListener): Car? {
            val present = try {
                context.packageManager.getPackageInfo(VEHICLE_SERVICE, 0)
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
            } catch (_: PackageManager.NameNotFoundException) { false }
            if (!present) return null
            return try { PleosCar(context.applicationContext, listener) }
            catch (error: Exception) { carLog("initialization unavailable: ${error.javaClass.simpleName}"); null }
        }
    }
}
