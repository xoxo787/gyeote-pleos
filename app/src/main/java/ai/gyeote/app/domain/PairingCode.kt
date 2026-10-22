package ai.gyeote.app.domain

import java.time.Duration
import java.time.Instant
import java.util.Locale

/** A short-lived enrollment secret. It is never persisted or included in diagnostic text. */
class PairingCode(val digits: String, val expiresAt: Instant) {
    init { require(digits.matches(Regex("[0-9]{6}"))) }

    fun remainingSeconds(now: Instant): Long {
        val remaining = Duration.between(now, expiresAt)
        if (remaining.isNegative || remaining.isZero) return 0
        return remaining.seconds + if (remaining.nano > 0) 1 else 0
    }

    fun remainingText(now: Instant): String {
        val seconds = remainingSeconds(now)
        return if (seconds == 0L) "번호가 만료됐어요" else
            String.format(Locale.KOREA, "남은 시간 %d:%02d", seconds / 60, seconds % 60)
    }

    override fun toString(): String = "PairingCode([private])"
}
