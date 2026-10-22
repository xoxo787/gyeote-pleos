package ai.gyeote.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PairingCodeTest {
    private val now = Instant.parse("2026-10-22T00:00:00Z")

    @Test fun countdownUsesServerDeadlineAndRoundsUpLastPartialSecond() {
        val code = PairingCode("001234", now.plusSeconds(600))
        assertEquals("001234", code.digits)
        assertEquals("남은 시간 10:00", code.remainingText(now))
        assertEquals("남은 시간 9:59", code.remainingText(now.plusSeconds(1)))
        assertEquals(1L, code.remainingSeconds(now.plusSeconds(599).plusNanos(999_999_999)))
    }

    @Test fun expiresExactlyAtDeadlineAndDoesNotShowNegativeTime() {
        val code = PairingCode("001234", now.plusSeconds(600))
        assertEquals(0L, code.remainingSeconds(now.plusSeconds(600)))
        assertEquals("번호가 만료됐어요", code.remainingText(now.plusSeconds(600)))
        assertEquals(0L, code.remainingSeconds(now.plusSeconds(999)))
    }

    @Test fun deadlineIsNotAssumedToBeTenMinutesFromClientTime() {
        val code = PairingCode("001234", now.plusSeconds(75))
        assertEquals("남은 시간 1:15", code.remainingText(now))
    }

    @Test fun malformedEnrollmentDigitsAreRejectedAndDiagnosticTextIsPrivate() {
        for (digits in listOf("12345", "1234567", "abcdef", "12 345")) {
            assertTrue(runCatching { PairingCode(digits, now) }.isFailure)
        }
        assertFalse(PairingCode("001234", now).toString().contains("001234"))
    }
}
