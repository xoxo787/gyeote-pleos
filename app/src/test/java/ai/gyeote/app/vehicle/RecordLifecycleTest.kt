package ai.gyeote.app.vehicle

import ai.gyeote.app.domain.HandoverMode
import org.junit.Assert.*
import org.junit.Test

class RecordLifecycleTest {
    private var starts = 0
    private var stops = 0
    private val lifecycle = RecordLifecycle({ starts++ }, { stops++ })

    @Test fun openingAnEnabledModeRequestsOnlyOneService() {
        lifecycle.attach(HandoverMode.RENTAL) { }
        repeat(5) { lifecycle.updateMode(HandoverMode.RENTAL) }
        assertEquals(1, starts)
        assertTrue(lifecycle.serviceRequested)
    }

    @Test fun openingOffDoesNotStartRecording() {
        lifecycle.attach(HandoverMode.OFF) { }
        lifecycle.updateMode(HandoverMode.OFF)
        assertEquals(0, starts)
        assertEquals(0, stops)
        assertFalse(lifecycle.serviceRequested)
    }

    @Test fun modeOffStopsOnceAndAnotherModeStartsAgain() {
        lifecycle.attach(HandoverMode.FLEET) { }
        repeat(3) { lifecycle.updateMode(HandoverMode.OFF) }
        assertEquals(1, stops)
        assertFalse(lifecycle.serviceRequested)
        lifecycle.updateMode(HandoverMode.FAMILY)
        assertEquals(2, starts)
    }

    @Test fun leavingTheScreenKeepsTheRequestedService() {
        val screen = lifecycle.attach(HandoverMode.RENTAL) { }
        screen.cancel()
        assertFalse(lifecycle.screenAttached)
        assertTrue(lifecycle.serviceRequested)
        assertEquals(0, stops)
    }

    @Test fun detachedScreenNeverReceivesAnotherRender() {
        var renders = 0
        val screen = lifecycle.attach(HandoverMode.RENTAL) { renders++ }
        lifecycle.changed()
        screen.cancel()
        repeat(10) { lifecycle.changed() }
        assertEquals(1, renders)
        lifecycle.attach(HandoverMode.RENTAL) { renders++ }
        lifecycle.changed()
        assertEquals(2, renders)
        assertEquals(1, starts)
    }

    @Test fun oldScreenCannotDetachItsReplacement() {
        var oldRenders = 0
        var newRenders = 0
        val old = lifecycle.attach(HandoverMode.RENTAL) { oldRenders++ }
        lifecycle.attach(HandoverMode.RENTAL) { newRenders++ }
        old.cancel()
        lifecycle.changed()
        assertEquals(0, oldRenders)
        assertEquals(1, newRenders)
        assertTrue(lifecycle.screenAttached)
    }

    @Test fun aStoppedServiceIsStartedOnlyOnTheNextVisibleOpening() {
        val screen = lifecycle.attach(HandoverMode.FLEET) { }
        screen.cancel()
        lifecycle.serviceStopped()
        lifecycle.updateMode(HandoverMode.FLEET)
        assertEquals(1, starts)
        assertFalse(lifecycle.serviceRequested)
        lifecycle.attach(HandoverMode.FLEET) { }
        assertEquals(2, starts)
    }

    @Test fun allEnabledModesShareOneServiceUntilOff() {
        val screen = lifecycle.attach(HandoverMode.RENTAL) { }
        lifecycle.updateMode(HandoverMode.FLEET)
        lifecycle.updateMode(HandoverMode.FAMILY)
        assertEquals(1, starts)
        screen.cancel()
        lifecycle.updateMode(HandoverMode.OFF)
        assertEquals(1, stops)
        assertFalse(lifecycle.serviceRequested)
    }
}
