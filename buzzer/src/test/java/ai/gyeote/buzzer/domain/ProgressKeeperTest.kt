package ai.gyeote.buzzer.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressKeeperTest {
    private class MemoryProgress(override var lifetimeStars: Int = 0, override var cursor: Int = 0) : Progress

    private val finished = Phase.Finished(FinishReason.ROUNDS_DONE, emptySet())
    private val stars = mapOf(Seat.DRIVER to 2, Seat.REAR_LEFT to 1)

    @Test fun `finishing a session adds its stars to the lifetime total once`() {
        val progress = MemoryProgress(lifetimeStars = 10)
        val keeper = ProgressKeeper(progress)
        keeper.observe(finished, stars, cursor = 4)
        keeper.observe(finished, stars, cursor = 4)
        assertEquals(13, progress.lifetimeStars)
    }

    @Test fun `a session closed by driving keeps the stars earned so far`() {
        val progress = MemoryProgress()
        ProgressKeeper(progress).observe(Phase.Locked(closedByDriving = true), stars, cursor = 2)
        assertEquals(3, progress.lifetimeStars)
    }

    @Test fun `a session ended by leaving the screen keeps the stars earned so far`() {
        val progress = MemoryProgress()
        ProgressKeeper(progress).observe(Phase.Locked(closedByDriving = false, leftScreen = true), stars, cursor = 2)
        assertEquals(3, progress.lifetimeStars)
    }

    @Test fun `a plain lock from the lobby adds nothing`() {
        val progress = MemoryProgress(lifetimeStars = 5)
        ProgressKeeper(progress).observe(Phase.Locked(closedByDriving = false), emptyMap(), cursor = 0)
        assertEquals(5, progress.lifetimeStars)
    }

    @Test fun `the next session is added after the lobby reopens`() {
        val progress = MemoryProgress()
        val keeper = ProgressKeeper(progress)
        keeper.observe(finished, stars, cursor = 4)
        keeper.observe(Phase.Lobby, emptyMap(), cursor = 4)
        keeper.observe(finished, mapOf(Seat.DRIVER to 1), cursor = 8)
        assertEquals(4, progress.lifetimeStars)
    }

    @Test fun `the question cursor is saved so the next session continues from there`() {
        val progress = MemoryProgress()
        ProgressKeeper(progress).observe(finished, stars, cursor = 7)
        assertEquals(7, progress.cursor)
    }
}
