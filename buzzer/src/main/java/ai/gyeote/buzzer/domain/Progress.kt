package ai.gyeote.buzzer.domain

/** The only things kept between sessions: how many stars this car has collected and where the question bank stopped. */
interface Progress {
    var lifetimeStars: Int
    var cursor: Int
}

/** Saves a session's stars exactly once: when it finishes, when driving closes it, or when the screen goes away. */
class ProgressKeeper(private val progress: Progress) {
    private var saved = false

    fun observe(phase: Phase, stars: Map<Seat, Int>, cursor: Int) {
        when {
            phase == Phase.Lobby -> saved = false
            phase is Phase.Finished || (phase is Phase.Locked && (phase.closedByDriving || phase.leftScreen)) -> if (!saved) {
                saved = true
                progress.lifetimeStars += stars.values.sum()
                progress.cursor = cursor
            }
        }
    }
}
