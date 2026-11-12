package ai.gyeote.buzzer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameMachineTest {
    private val yes = Question("참인 문제", truth = true, why = "참 설명")
    private val no = Question("거짓인 문제", truth = false, why = "거짓 설명")
    /** Most tests open the lobby the moment P arrives; the settle time has its own tests below. */
    private val config = GameConfig(parkSettleMs = 0)

    /** Round 1 = yes, round 2 = no, round 3 = reflex, round 4 = yes, round 5 = no. */
    private fun game() = GameMachine(QuestionSource(listOf(yes, no), carEvery = 0), config, reflexDelayMs = { 3_000 })

    private fun GameMachine.press(seat: Seat, at: Long) = window(seat, 1, at)
    private fun GameMachine.release(seat: Seat, at: Long) = window(seat, 0, at)

    /** Parked, [seats] joined with windows back up, started at t=1000. Returns the time the first question began. */
    private fun GameMachine.startWith(vararg seats: Seat): Long {
        gear(parked = true, now = 0)
        seats.forEach { press(it, 100); release(it, 200) }
        assertTrue(start(1_000))
        return 1_000
    }

    private inline fun <reified T : Phase> GameMachine.phaseAs(): T {
        assertTrue("expected ${T::class.simpleName} but was $phase", phase is T)
        return phase as T
    }

    // --- gate ---

    @Test fun `stays locked until the gear is known to be P`() {
        val game = game()
        assertEquals(Phase.Locked(closedByDriving = false), game.phase)
        game.gear(parked = true, now = 0)
        assertEquals(Phase.Lobby, game.phase)
    }

    @Test fun `window moves are ignored while locked`() {
        val game = game()
        game.press(Seat.DRIVER, 10)
        game.gear(parked = true, now = 20)
        assertTrue(game.joined.isEmpty())
    }

    @Test fun `the lobby opens only after P has held for the settle time`() {
        val game = GameMachine(QuestionSource(listOf(yes), carEvery = 0), GameConfig(parkSettleMs = 700), reflexDelayMs = { 3_000 })
        game.gear(parked = true, now = 1_000)
        game.tick(1_699)
        assertEquals(Phase.Locked(closedByDriving = false), game.phase)
        game.tick(1_700)
        assertEquals(Phase.Lobby, game.phase)
    }

    @Test fun `a stale P that turns into D right away never opens the lobby`() {
        val game = GameMachine(QuestionSource(listOf(yes), carEvery = 0), GameConfig(parkSettleMs = 700), reflexDelayMs = { 3_000 })
        game.gear(parked = true, now = 1_000)
        assertEquals(Phase.Locked(closedByDriving = false), game.phase)
        game.gear(parked = false, now = 1_011)
        game.tick(5_000)
        assertEquals(Phase.Locked(closedByDriving = false), game.phase)
    }

    @Test fun `repeated P reports do not restart the settle time`() {
        val game = GameMachine(QuestionSource(listOf(yes), carEvery = 0), GameConfig(parkSettleMs = 700), reflexDelayMs = { 3_000 })
        game.gear(parked = true, now = 1_000)
        game.gear(parked = true, now = 1_600)
        game.tick(1_700)
        assertEquals(Phase.Lobby, game.phase)
    }

    // --- lobby ---

    @Test fun `lowering a window in the lobby joins that seat`() {
        val game = game()
        game.gear(parked = true, now = 0)
        game.press(Seat.REAR_LEFT, 100)
        assertEquals(setOf(Seat.REAR_LEFT), game.joined)
    }

    @Test fun `cannot start with nobody joined`() {
        val game = game()
        game.gear(parked = true, now = 0)
        assertFalse(game.start(1_000))
        assertEquals(Phase.Lobby, game.phase)
    }

    @Test fun `start waits until joined windows are back up`() {
        val game = game()
        game.gear(parked = true, now = 0)
        game.press(Seat.DRIVER, 100)
        game.start(1_000)
        assertEquals(setOf(Seat.DRIVER), game.phaseAs<Phase.Between>().raise)
        game.release(Seat.DRIVER, 1_500)
        assertEquals(yes, game.phaseAs<Phase.Asking>().question)
    }

    // --- OX round ---

    @Test fun `lowering during a question answers O and staying up answers X`() {
        val game = game()
        val t = game.startWith(Seat.DRIVER, Seat.REAR_LEFT)
        game.press(Seat.REAR_LEFT, t + 2_000)
        game.tick(t + config.askMs)
        val result = game.phaseAs<Phase.Reveal>().result as RoundResult.Ox
        assertEquals(setOf(Seat.REAR_LEFT), result.correct)
        assertEquals(setOf(Seat.DRIVER), result.wrong)
        assertEquals(1, game.stars[Seat.REAR_LEFT])
        assertEquals(0, game.stars[Seat.DRIVER])
    }

    @Test fun `question does not end before its time when someone has not answered`() {
        val game = game()
        val t = game.startWith(Seat.DRIVER, Seat.REAR_LEFT)
        game.press(Seat.REAR_LEFT, t + 2_000)
        game.tick(t + config.askMs - 1)
        game.phaseAs<Phase.Asking>()
    }

    @Test fun `question ends early once every joined seat has lowered`() {
        val game = game()
        val t = game.startWith(Seat.DRIVER, Seat.REAR_LEFT)
        game.press(Seat.DRIVER, t + 1_000)
        game.press(Seat.REAR_LEFT, t + 2_000)
        game.phaseAs<Phase.Reveal>()
    }

    @Test fun `raising the window again does not take the answer back`() {
        val game = game()
        val t = game.startWith(Seat.DRIVER, Seat.REAR_LEFT)
        game.press(Seat.DRIVER, t + 1_000)
        game.release(Seat.DRIVER, t + 2_000)
        game.tick(t + config.askMs)
        assertEquals(setOf(Seat.DRIVER), (game.phaseAs<Phase.Reveal>().result as RoundResult.Ox).correct)
    }

    @Test fun `a seat that did not join cannot answer`() {
        val game = game()
        val t = game.startWith(Seat.DRIVER)
        game.press(Seat.PASSENGER, t + 1_000)
        game.tick(t + config.askMs)
        val result = game.phaseAs<Phase.Reveal>().result as RoundResult.Ox
        assertEquals(setOf(Seat.DRIVER), result.wrong)
        assertFalse(Seat.PASSENGER in game.stars)
    }

    @Test fun `staying up on a false statement earns the star`() {
        val game = game()
        var t = game.startWith(Seat.DRIVER)
        game.tick(t + config.askMs)                       // round 1 (true): no star
        t += config.askMs + config.revealMs
        game.tick(t)                                      // round 2 (false) starts
        assertEquals(no, game.phaseAs<Phase.Asking>().question)
        game.tick(t + config.askMs)
        assertEquals(1, game.stars[Seat.DRIVER])
    }

    @Test fun `next question waits for lowered windows to go back up`() {
        val game = game()
        val t = game.startWith(Seat.DRIVER)
        game.press(Seat.DRIVER, t + 1_000)                // all answered -> reveal
        game.tick(t + 1_000 + config.revealMs)
        assertEquals(setOf(Seat.DRIVER), game.phaseAs<Phase.Between>().raise)
        game.tick(t + 60_000)                             // no time pressure on raising
        game.phaseAs<Phase.Between>()
        game.release(Seat.DRIVER, t + 61_000)
        game.phaseAs<Phase.Asking>()
    }

    // --- reflex round ---

    private fun GameMachine.toReflexRound(vararg seats: Seat): Long {
        var t = startWith(*seats)
        repeat(2) {
            tick(t + config.askMs)
            t += config.askMs + config.revealMs
            tick(t)
        }
        return t
    }

    @Test fun `third round is a reflex round that says go after the delay`() {
        val game = game()
        val t = game.toReflexRound(Seat.DRIVER, Seat.REAR_LEFT)
        assertEquals(t + 3_000, game.phaseAs<Phase.Steady>().goAt)
        game.tick(t + 3_000)
        game.phaseAs<Phase.Go>()
    }

    @Test fun `first seat to lower after go wins the star`() {
        val game = game()
        val t = game.toReflexRound(Seat.DRIVER, Seat.REAR_LEFT)
        val before = game.stars.getValue(Seat.REAR_LEFT)
        game.tick(t + 3_000)
        game.press(Seat.REAR_LEFT, t + 3_400)
        game.press(Seat.DRIVER, t + 3_500)
        val result = game.phaseAs<Phase.Reveal>().result as RoundResult.Reflex
        assertEquals(Seat.REAR_LEFT, result.winner)
        assertEquals(before + 1, game.stars[Seat.REAR_LEFT])
    }

    @Test fun `lowering before go puts that seat out of the round`() {
        val game = game()
        val t = game.toReflexRound(Seat.DRIVER, Seat.REAR_LEFT)
        game.press(Seat.DRIVER, t + 1_000)
        game.release(Seat.DRIVER, t + 1_500)
        game.tick(t + 3_000)
        game.press(Seat.DRIVER, t + 3_100)
        game.phaseAs<Phase.Go>()
        game.press(Seat.REAR_LEFT, t + 3_900)
        val result = game.phaseAs<Phase.Reveal>().result as RoundResult.Reflex
        assertEquals(Seat.REAR_LEFT, result.winner)
        assertEquals(setOf(Seat.DRIVER), result.tooEarly)
    }

    @Test fun `reflex round ends without a winner when nobody lowers`() {
        val game = game()
        val t = game.toReflexRound(Seat.DRIVER)
        game.tick(t + 3_000)
        game.tick(t + 3_000 + config.reflexWindowMs)
        assertEquals(null, (game.phaseAs<Phase.Reveal>().result as RoundResult.Reflex).winner)
    }

    // --- driving closes the game ---

    @Test fun `leaving P closes the game at once and says so once`() {
        val game = game()
        val t = game.startWith(Seat.DRIVER)
        game.drainSpeech()
        game.gear(parked = false, now = t + 500)
        assertEquals(Phase.Locked(closedByDriving = true), game.phase)
        assertEquals(listOf(Lines.CLOSED_BY_DRIVING), game.drainSpeech())
        game.press(Seat.DRIVER, t + 600)
        game.tick(t + 60_000)
        assertEquals(Phase.Locked(closedByDriving = true), game.phase)
        assertTrue(game.drainSpeech().isEmpty())
    }

    @Test fun `leaving P from the lobby locks without the closing line`() {
        val game = game()
        game.gear(parked = true, now = 0)
        game.gear(parked = false, now = 10)
        assertEquals(Phase.Locked(closedByDriving = false), game.phase)
        assertTrue(game.drainSpeech().isEmpty())
    }

    @Test fun `back in P a fresh lobby opens`() {
        val game = game()
        val t = game.startWith(Seat.DRIVER)
        game.gear(parked = false, now = t + 500)
        game.gear(parked = true, now = t + 9_000)
        assertEquals(Phase.Lobby, game.phase)
        assertTrue(game.joined.isEmpty())
    }

    @Test fun `leaving P closes the game from every phase of a session`() {
        val reached = mutableListOf<String>()
        fun closes(name: String, arrange: (GameMachine) -> Unit) {
            val game = game()
            arrange(game)
            reached += "$name=${game.phase::class.simpleName}"
            game.gear(parked = false, now = 900_000)
            assertEquals(name, Phase.Locked(closedByDriving = true), game.phase)
        }
        closes("between") { it.gear(parked = true, now = 0); it.press(Seat.DRIVER, 100); it.start(1_000) }
        closes("steady") { it.toReflexRound(Seat.DRIVER) }
        closes("go") { val t = it.toReflexRound(Seat.DRIVER); it.tick(t + 3_000) }
        closes("reveal") { val t = it.startWith(Seat.DRIVER); it.tick(t + config.askMs) }
        assertEquals(listOf("between=Between", "steady=Steady", "go=Go", "reveal=Reveal"), reached)
    }

    @Test fun `after driving closed a session the lobby still waits for a settled P`() {
        val game = GameMachine(QuestionSource(listOf(yes), carEvery = 0), GameConfig(parkSettleMs = 700), reflexDelayMs = { 3_000 })
        game.gear(parked = true, now = 0)
        game.tick(700)
        game.press(Seat.DRIVER, 800)
        game.release(Seat.DRIVER, 900)
        game.start(1_000)
        game.gear(parked = false, now = 2_000)
        game.gear(parked = true, now = 3_000)
        game.tick(3_699)
        assertEquals(Phase.Locked(closedByDriving = true), game.phase)
        game.tick(3_700)
        assertEquals(Phase.Lobby, game.phase)
    }

    @Test fun `start and again do nothing while locked`() {
        val game = game()
        assertFalse(game.start(100))
        game.again(200)
        assertEquals(Phase.Locked(closedByDriving = false), game.phase)
    }

    @Test fun `a window that is already open has to come up before it can join`() {
        val game = game()
        game.window(Seat.REAR_RIGHT, 5, 0)
        game.gear(parked = true, now = 10)
        game.window(Seat.REAR_RIGHT, 3, 100)              // raised part of the way: not a press
        game.window(Seat.REAR_RIGHT, 8, 200)              // lowered further: not a press either
        assertTrue(game.joined.isEmpty())
        game.window(Seat.REAR_RIGHT, 0, 300)
        game.window(Seat.REAR_RIGHT, 1, 400)
        assertEquals(setOf(Seat.REAR_RIGHT), game.joined)
    }

    @Test fun `raising a window during go is not a buzz`() {
        val game = game()
        val t = game.toReflexRound(Seat.DRIVER, Seat.REAR_LEFT)
        game.press(Seat.DRIVER, t + 1_000)                // too early, and left down
        game.tick(t + 3_000)
        game.release(Seat.DRIVER, t + 3_200)
        game.phaseAs<Phase.Go>()
    }

    // --- leaving the screen ---

    @Test fun `leaving the screen mid-session locks quietly and is not called driving`() {
        val game = game()
        val t = game.startWith(Seat.DRIVER)
        game.drainSpeech()
        game.screenLeft(t + 500)
        assertEquals(Phase.Locked(closedByDriving = false, leftScreen = true), game.phase)
        assertTrue(game.drainSpeech().isEmpty())
    }

    @Test fun `leaving the screen from the lobby is a plain lock`() {
        val game = game()
        game.gear(parked = true, now = 0)
        game.screenLeft(10)
        assertEquals(Phase.Locked(closedByDriving = false), game.phase)
    }

    @Test fun `after leaving the screen P has to be reported again`() {
        val game = game()
        game.gear(parked = true, now = 0)
        game.screenLeft(10)
        game.tick(60_000)
        assertEquals(Phase.Locked(closedByDriving = false), game.phase)
    }

    // --- parked session length ---

    /** Plays a full round with nobody lowering; returns the time the next phase began. */
    private fun GameMachine.sitThrough(from: Long): Long {
        var t = from
        when (phase) {
            is Phase.Asking -> { tick(t + config.askMs); t += config.askMs }
            is Phase.Steady -> { tick(t + 3_000); tick(t + 3_000 + config.reflexWindowMs); t += 3_000 + config.reflexWindowMs }
            else -> error("not in a round: $phase")
        }
        t += config.revealMs
        tick(t)
        return t
    }

    @Test fun `a parked session without charging ends after five rounds`() {
        val game = game()
        var t = game.startWith(Seat.DRIVER)
        repeat(4) { t = game.sitThrough(t) }
        assertEquals(4, game.phaseAs<Phase.Asking>().round)
        game.sitThrough(t)
        assertEquals(FinishReason.ROUNDS_DONE, game.phaseAs<Phase.Finished>().reason)
    }

    @Test fun `finish lists the windows that are still open`() {
        val game = game()
        var t = game.startWith(Seat.DRIVER)
        game.window(Seat.REAR_RIGHT, 4, t + 10)           // not a player, left open
        repeat(5) { t = game.sitThrough(t) }
        assertEquals(setOf(Seat.REAR_RIGHT), game.phaseAs<Phase.Finished>().openWindows)
        game.window(Seat.REAR_RIGHT, 0, t + 10)
        assertTrue(game.phaseAs<Phase.Finished>().openWindows.isEmpty())
    }

    @Test fun `again returns to an empty lobby`() {
        val game = game()
        var t = game.startWith(Seat.DRIVER)
        repeat(5) { t = game.sitThrough(t) }
        game.again(t)
        assertEquals(Phase.Lobby, game.phase)
        assertTrue(game.joined.isEmpty())
        assertTrue(game.stars.isEmpty())
    }

    // --- charging session: the battery is the clock ---

    private fun GameMachine.startCharging(soc: Int): Long {
        car(CarFacts(socPercent = soc, charging = true), 0)
        return startWith(Seat.DRIVER)
    }

    @Test fun `while charging the next question waits for one more percent`() {
        val game = game()
        var t = game.startCharging(soc = 60)
        t = game.sitThrough(t)
        assertEquals(61, game.phaseAs<Phase.Between>().nextAtSoc)
        game.tick(t + 10_000)
        game.phaseAs<Phase.Between>()
        game.car(CarFacts(socPercent = 61, charging = true), t + 11_000)
        assertEquals(1, game.phaseAs<Phase.Asking>().round)
    }

    @Test fun `a slow charger does not stall the game past the fallback time`() {
        val game = game()
        var t = game.startCharging(soc = 60)
        t = game.sitThrough(t)
        game.tick(t + config.fallbackMs - 1)
        game.phaseAs<Phase.Between>()
        game.tick(t + config.fallbackMs)
        game.phaseAs<Phase.Asking>()
    }

    @Test fun `a charging session goes past five rounds`() {
        val game = game()
        var t = game.startCharging(soc = 40)
        repeat(7) { round ->
            t = game.sitThrough(t)
            game.car(CarFacts(socPercent = 41 + round, charging = true), t)
        }
        assertTrue(game.phase is Phase.Asking || game.phase is Phase.Steady)
    }

    @Test fun `unplugging ends the session after the current round`() {
        val game = game()
        var t = game.startCharging(soc = 60)
        game.car(CarFacts(socPercent = 60, charging = false), t + 1_000)
        game.phaseAs<Phase.Asking>()
        t = game.sitThrough(t)
        assertEquals(FinishReason.UNPLUGGED, game.phaseAs<Phase.Finished>().reason)
    }

    @Test fun `unplugging while waiting for the next question ends the session`() {
        val game = game()
        var t = game.startCharging(soc = 60)
        t = game.sitThrough(t)
        game.car(CarFacts(socPercent = 60, charging = false), t + 1_000)
        assertEquals(FinishReason.UNPLUGGED, game.phaseAs<Phase.Finished>().reason)
    }

    @Test fun `reaching the target plays one last round and ends`() {
        val game = game()
        var t = game.startCharging(soc = 79)
        assertEquals(80, game.targetSoc)
        t = game.sitThrough(t)
        game.car(CarFacts(socPercent = 80, charging = true), t)
        assertTrue(game.finalRound)
        game.sitThrough(t)
        assertEquals(FinishReason.CHARGED, game.phaseAs<Phase.Finished>().reason)
    }

    @Test fun `charging that stops on reaching the target ends as charged`() {
        val game = game()
        var t = game.startCharging(soc = 79)
        t = game.sitThrough(t)
        game.car(CarFacts(socPercent = 80, charging = false), t + 1_000)
        assertEquals(FinishReason.CHARGED, game.phaseAs<Phase.Finished>().reason)
    }

    @Test fun `charging that stops at the target during a round ends as charged after it`() {
        val game = game()
        val t = game.startCharging(soc = 79)
        game.car(CarFacts(socPercent = 80, charging = false), t + 1_000)
        game.sitThrough(t)
        assertEquals(FinishReason.CHARGED, game.phaseAs<Phase.Finished>().reason)
    }

    @Test fun `a lost battery reading keeps the last known level as the clock`() {
        val game = game()
        var t = game.startCharging(soc = 60)
        game.car(CarFacts(socPercent = null, charging = true), t + 1_000)
        t = game.sitThrough(t)
        assertEquals(61, game.phaseAs<Phase.Between>().nextAtSoc)
        game.tick(t + config.fallbackMs)                  // fallback brings round 2
        t = game.sitThrough(t + config.fallbackMs)
        assertEquals(61, game.phaseAs<Phase.Between>().nextAtSoc)
    }

    @Test fun `starting at or above eighty percent aims for one hundred`() {
        val game = game()
        game.startCharging(soc = 85)
        assertEquals(100, game.targetSoc)
    }

    // --- speech ---

    @Test fun `a question is read aloud with how to answer`() {
        val game = game()
        game.startWith(Seat.DRIVER)
        val said = game.drainSpeech()
        assertTrue(said.last().startsWith(yes.text))
        assertTrue(said.last().contains(Lines.HOW_TO_ANSWER))
    }

    @Test fun `later questions are read without repeating how to answer`() {
        val game = game()
        val t = game.startWith(Seat.DRIVER)
        game.tick(t + config.askMs)
        game.drainSpeech()
        game.tick(t + config.askMs + config.revealMs)
        assertEquals(listOf(no.text), game.drainSpeech())
    }

    @Test fun `there is time to hear the question and still answer`() {
        assertTrue(GameConfig().askMs >= 12_000)
    }

    @Test fun `the reveal says the answer and why`() {
        val game = game()
        val t = game.startWith(Seat.DRIVER)
        game.drainSpeech()
        game.tick(t + config.askMs)
        assertEquals(listOf("${Lines.ANSWER_O} ${yes.why}"), game.drainSpeech())
    }
}
