package ai.gyeote.app.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import ai.gyeote.app.domain.GearSelection

/** Controls open only with both platform UX permission and a known P gear. */
internal class PlatformDrivingState(context: Context) : DrivingStateSource {
    private val main = Handler(Looper.getMainLooper())
    private val signal = Signal(true)
    private var uxRestricted = true
    private var gear = GearSelection.UNKNOWN
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var closed = false
    val restricted get() = signal.value
    init {
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connectedCar, ready ->
                main.post {
                    if (!closed) {
                        uxRestricted = true
                        if (ready) runCatching {
                            manager = connectedCar.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                            manager?.registerListener { info -> main.post { if (!closed) { uxRestricted = info.isRequiresDistractionOptimization; update() } } }
                            uxRestricted = manager?.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: true
                            vehicleLog("uxRestricted=$uxRestricted")
                        }.onFailure { vehicleLog("ux unavailable: ${it.javaClass.simpleName}") }
                        update()
                    }
                }
            }
        }.onFailure { vehicleLog("ux unavailable: ${it.javaClass.simpleName}") }
    }
    fun gear(value: GearSelection) { gear = value; update() }
    private fun update() {
        val next = uxRestricted || gear != GearSelection.PARK
        if (signal.value != next) { signal.emit(next); vehicleLog("restricted=$next") }
    }
    override fun observeRestrictions(callback: (Boolean) -> Unit) = signal.observe(callback)
    fun close() {
        closed = true; main.removeCallbacksAndMessages(null)
        runCatching { manager?.unregisterListener() }; runCatching { car?.disconnect() }
    }
}
