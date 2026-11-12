package ai.gyeote.buzzer.vehicle

import ai.gyeote.buzzer.domain.CarFacts
import ai.gyeote.buzzer.domain.Seat

/** What the game needs from the car. All callbacks arrive on the main thread. */
interface CarListener {
    /** True only when the gear is known to be P. */
    fun onGear(parked: Boolean)
    /** Side window position: 0 = closed, larger = lowered further. */
    fun onWindow(seat: Seat, position: Int)
    fun onFacts(facts: CarFacts)
}

interface Car {
    fun close()
}
