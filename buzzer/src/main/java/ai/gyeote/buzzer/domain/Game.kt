package ai.gyeote.buzzer.domain

/** The four side windows. Each seat's own window switch is that seat's buzzer. */
enum class Seat(val label: String) {
    DRIVER("운전석"), PASSENGER("조수석"), REAR_LEFT("뒤 왼쪽"), REAR_RIGHT("뒤 오른쪽")
}

data class GameConfig(
    /** How long seats may answer a true/false statement. Long enough to hear it read aloud first. */
    val askMs: Long = 12_000,
    val revealMs: Long = 5_000,
    /** How long after "go" a reflex round stays open. */
    val reflexWindowMs: Long = 6_000,
    /** While charging, the next question arrives after +1% or after this long, whichever is first. */
    val fallbackMs: Long = 45_000,
    /** Rounds of a session that is not charging. */
    val parkedRounds: Int = 5,
    /** Every n-th round is a reflex round. */
    val reflexEvery: Int = 3,
    /**
     * P must hold this long before the lobby opens. On registration the SDK can report a stale P and the real
     * gear a few milliseconds later (measured 2026-11-11: P, then D after 11 ms). Leaving P is never delayed.
     */
    val parkSettleMs: Long = 700,
)

enum class FinishReason { ROUNDS_DONE, CHARGED, UNPLUGGED }

sealed interface RoundResult {
    data class Ox(val question: Question, val correct: Set<Seat>, val wrong: Set<Seat>) : RoundResult
    data class Reflex(val winner: Seat?, val tooEarly: Set<Seat>) : RoundResult
}

sealed interface Phase {
    /**
     * Gear is not P (or not known yet). Nothing is playable.
     * [closedByDriving]: a session was running when the gear left P. [leftScreen]: a session was running when the screen went away.
     */
    data class Locked(val closedByDriving: Boolean, val leftScreen: Boolean = false) : Phase
    data object Lobby : Phase
    /** Between rounds: [raise] windows must go back up (no time limit); [nextAtSoc] is the battery level that brings the next question. */
    data class Between(val raise: Set<Seat>, val nextAtSoc: Int?) : Phase
    data class Asking(val round: Int, val question: Question, val endsAt: Long, val answeredO: Set<Seat>) : Phase
    data class Steady(val round: Int, val goAt: Long, val tooEarly: Set<Seat>) : Phase
    data class Go(val round: Int, val endsAt: Long, val tooEarly: Set<Seat>) : Phase
    data class Reveal(val round: Int, val result: RoundResult, val endsAt: Long) : Phase
    data class Finished(val reason: FinishReason, val openWindows: Set<Seat>) : Phase
}

/** Everything the car says out loud. Kept in one place so wording can be reviewed at once. */
object Lines {
    const val CLOSED_BY_DRIVING = "출발하니까 놀이를 닫을게요. 다음에 이어서 해요."
    const val START = "시작할게요."
    const val HOW_TO_ANSWER = "맞으면 창문을 한 칸 내리고, 아니면 그대로 두세요."
    const val ANSWER_O = "맞는 말이에요."
    const val ANSWER_X = "틀린 말이에요."
    const val RAISE = "창문을 올려 주세요. 천천히 하셔도 돼요."
    const val REFLEX_INTRO = "번개 버저! 지금이라고 하면 창문을 한 칸 내리세요."
    const val GO = "지금!"
    const val NO_WINNER = "이번에는 아무도 내리지 않았어요."
    const val LAST_ROUND = "충전이 거의 끝났어요. 마지막 문제예요."
    const val DONE_ROUNDS = "오늘은 여기까지예요."
    const val DONE_CHARGED = "충전도 놀이도 끝났어요."
    const val DONE_UNPLUGGED = "충전이 끝났네요. 다음 충전 때 이어서 해요."
    const val CLOSE_WINDOWS = "열려 있는 창문을 올려 주세요."
    fun joined(seat: Seat) = "${seat.label} 참가!"
    fun fastest(seat: Seat) = "${seat.label}이 가장 빨랐어요."
}

/**
 * The whole game as a state machine. It never reads a clock or the car: every input carries its time,
 * so the same sequence of inputs always gives the same game (see GameMachineTest).
 *
 * Safety rules built into the machine:
 * - Only lowering a window is an input. Raising is never timed and never rewarded.
 * - Any gear other than P closes the game at once.
 */
class GameMachine(
    private val questions: QuestionSource,
    private val config: GameConfig = GameConfig(),
    private val reflexDelayMs: () -> Long,
) {
    var phase: Phase = Phase.Locked(closedByDriving = false)
        private set
    var joined: Set<Seat> = emptySet()
        private set
    var stars: Map<Seat, Int> = emptyMap()
        private set
    /** Battery level at which a charging session plays its last round. */
    var targetSoc: Int? = null
        private set
    var finalRound = false
        private set
    val chargingSession get() = targetSoc != null

    private val positions = mutableMapOf<Seat, Int>()
    private var facts = CarFacts()
    private var round = 0
    private var socAtRoundStart: Int? = null
    private var fallbackAt = 0L
    private var parkedSince: Long? = null
    /** Last battery level the car reported. A reading that drops out does not stop the clock. */
    private var lastSoc: Int? = null
    private val speech = mutableListOf<String>()

    private val lowered get() = positions.filterValues { it > 0 }.keys
    private val inSession get() = phase !is Phase.Locked && phase !is Phase.Lobby && phase !is Phase.Finished

    fun drainSpeech(): List<String> = speech.toList().also { speech.clear() }

    fun gear(parked: Boolean, now: Long) {
        if (!parked) {
            parkedSince = null
            if (phase is Phase.Locked) return
            val closing = inSession
            if (closing) speech += Lines.CLOSED_BY_DRIVING
            phase = Phase.Locked(closedByDriving = closing)
        } else if (phase is Phase.Locked) {
            if (parkedSince == null) parkedSince = now
            openLobbyOnceSettled(now)
        }
    }

    private fun openLobbyOnceSettled(now: Long) {
        val since = parkedSince ?: return
        if (now - since >= config.parkSettleMs) openLobby()
    }

    fun car(next: CarFacts, now: Long) {
        facts = next
        next.socPercent?.let { lastSoc = it }
        if (phase is Phase.Between) {
            if (chargingSession && next.charging != true) finish(chargeEndReason()) else advance(now)
        }
    }

    /** The screen went away (app left, display off). A running session ends without being called driving. */
    fun screenLeft(now: Long) {
        parkedSince = null
        if (phase is Phase.Locked) return
        phase = Phase.Locked(closedByDriving = false, leftScreen = inSession)
    }

    /** Charging that stops at or above the target has simply finished; below it, the plug was pulled. */
    private fun chargeEndReason(): FinishReason {
        val target = targetSoc ?: return FinishReason.UNPLUGGED
        return if ((lastSoc ?: 0) >= target) FinishReason.CHARGED else FinishReason.UNPLUGGED
    }

    fun window(seat: Seat, position: Int, now: Long) {
        val wasDown = (positions[seat] ?: 0) > 0
        positions[seat] = position
        val pressed = position > 0 && !wasDown
        when (val current = phase) {
            is Phase.Locked -> Unit
            Phase.Lobby -> if (pressed && seat !in joined) {
                joined = joined + seat
                stars = stars + (seat to 0)
                speech += Lines.joined(seat)
            }
            is Phase.Between -> advance(now)
            is Phase.Asking -> if (pressed && seat in joined && seat !in current.answeredO) {
                val answered = current.answeredO + seat
                phase = current.copy(answeredO = answered)
                if (answered == joined) revealOx(now)
            }
            is Phase.Steady -> if (pressed && seat in joined) phase = current.copy(tooEarly = current.tooEarly + seat)
            is Phase.Go -> if (pressed && seat in joined && seat !in current.tooEarly) revealReflex(seat, current, now)
            is Phase.Reveal -> Unit
            is Phase.Finished -> phase = current.copy(openWindows = lowered)
        }
    }

    /** [시작] in the lobby. */
    fun start(now: Long): Boolean {
        if (phase != Phase.Lobby || joined.isEmpty()) return false
        val soc = lastSoc
        targetSoc = if (facts.charging == true && soc != null) (if (soc < 80) 80 else 100) else null
        round = 0
        finalRound = false
        socAtRoundStart = null
        speech += Lines.START
        enterBetween(now)
        return true
    }

    /** [한 판 더] on the finish card. */
    fun again(now: Long) {
        if (phase is Phase.Finished) openLobby()
    }

    fun tick(now: Long) {
        when (val current = phase) {
            is Phase.Locked -> openLobbyOnceSettled(now)
            is Phase.Between -> advance(now)
            is Phase.Asking -> if (now >= current.endsAt) revealOx(now)
            is Phase.Steady -> if (now >= current.goAt) {
                speech += Lines.GO
                phase = Phase.Go(current.round, now + config.reflexWindowMs, current.tooEarly)
                if (current.tooEarly == joined) revealReflex(null, phase as Phase.Go, now)
            }
            is Phase.Go -> if (now >= current.endsAt) revealReflex(null, current, now)
            is Phase.Reveal -> if (now >= current.endsAt) afterReveal(now)
            else -> Unit
        }
    }

    private fun openLobby() {
        joined = emptySet()
        stars = emptyMap()
        targetSoc = null
        finalRound = false
        phase = Phase.Lobby
    }

    private fun enterBetween(now: Long) {
        fallbackAt = now + config.fallbackMs
        val raise = joined intersect lowered
        if (raise.isNotEmpty()) speech += Lines.RAISE
        phase = Phase.Between(raise, nextAtSoc())
        advance(now)
    }

    /** Battery level that unlocks the next question, or null when nothing has to be waited for. */
    private fun nextAtSoc(): Int? {
        val last = socAtRoundStart ?: return null
        return if (chargingSession) last + 1 else null
    }

    private fun advance(now: Long) {
        if (phase !is Phase.Between) return
        val raise = joined intersect lowered
        val waitFor = nextAtSoc()
        val unlocked = waitFor == null || (lastSoc ?: 0) >= waitFor || now >= fallbackAt
        if (raise.isEmpty() && unlocked) beginRound(now) else phase = Phase.Between(raise, if (unlocked) null else waitFor)
    }

    private fun beginRound(now: Long) {
        socAtRoundStart = lastSoc
        val target = targetSoc
        if (target != null && (lastSoc ?: 0) >= target) {
            finalRound = true
            speech += Lines.LAST_ROUND
        }
        if (config.reflexEvery > 0 && (round + 1) % config.reflexEvery == 0) {
            speech += Lines.REFLEX_INTRO
            phase = Phase.Steady(round, now + reflexDelayMs(), emptySet())
        } else {
            val question = questions.next(facts)
            // How to answer is said with the first question only; the screen keeps showing it.
            speech += if (round == 0) "${question.text} ${Lines.HOW_TO_ANSWER}" else question.text
            phase = Phase.Asking(round, question, now + config.askMs, emptySet())
        }
    }

    private fun revealOx(now: Long) {
        val current = phase as Phase.Asking
        val correct = if (current.question.truth) current.answeredO else joined - current.answeredO
        award(correct)
        speech += "${if (current.question.truth) Lines.ANSWER_O else Lines.ANSWER_X} ${current.question.why}"
        phase = Phase.Reveal(current.round, RoundResult.Ox(current.question, correct, joined - correct), now + config.revealMs)
    }

    private fun revealReflex(winner: Seat?, current: Phase.Go, now: Long) {
        if (winner != null) award(setOf(winner))
        speech += if (winner != null) Lines.fastest(winner) else Lines.NO_WINNER
        phase = Phase.Reveal(current.round, RoundResult.Reflex(winner, current.tooEarly), now + config.revealMs)
    }

    private fun award(seats: Set<Seat>) {
        stars = stars.mapValues { (seat, count) -> if (seat in seats) count + 1 else count }
    }

    private fun afterReveal(now: Long) {
        round++
        when {
            !chargingSession -> if (round >= config.parkedRounds) finish(FinishReason.ROUNDS_DONE) else enterBetween(now)
            finalRound -> finish(FinishReason.CHARGED)
            facts.charging != true -> finish(chargeEndReason())
            else -> enterBetween(now)
        }
    }

    private fun finish(reason: FinishReason) {
        speech += when (reason) {
            FinishReason.ROUNDS_DONE -> Lines.DONE_ROUNDS
            FinishReason.CHARGED -> Lines.DONE_CHARGED
            FinishReason.UNPLUGGED -> Lines.DONE_UNPLUGGED
        }
        if (lowered.isNotEmpty()) speech += Lines.CLOSE_WINDOWS
        phase = Phase.Finished(reason, lowered)
    }
}
