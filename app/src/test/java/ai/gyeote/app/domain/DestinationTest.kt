package ai.gyeote.app.domain

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class IntentParserTest(private val text: String, private val expected: DestinationIntent) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases() = listOf(
            "응" to DestinationIntent.ACCEPT, "네" to DestinationIntent.ACCEPT,
            "좋아" to DestinationIntent.ACCEPT, "가자" to DestinationIntent.ACCEPT,
            "그래" to DestinationIntent.ACCEPT, "출발" to DestinationIntent.ACCEPT,
            " 네! " to DestinationIntent.ACCEPT, "네 좋아요" to DestinationIntent.ACCEPT,
            "나중에" to DestinationIntent.DEFER, "다음에" to DestinationIntent.DEFER,
            "이따" to DestinationIntent.DEFER, "다음에 할게요" to DestinationIntent.DEFER,
            "아니" to DestinationIntent.DECLINE, "싫어" to DestinationIntent.DECLINE,
            "취소" to DestinationIntent.DECLINE, "아니요" to DestinationIntent.DECLINE,
            "출발지" to DestinationIntent.ASK_AGAIN, "가자미" to DestinationIntent.ASK_AGAIN,
            "좋아 취소" to DestinationIntent.ASK_AGAIN, "" to DestinationIntent.ASK_AGAIN,
            "잘 모르겠어" to DestinationIntent.ASK_AGAIN,
        ).map { arrayOf<Any>(it.first, it.second) }
    }
    @Test fun onlyUnambiguousParentIntentChangesDestination() { assertEquals(expected, KoreanIntentParser.parse(text)) }
}

class DestinationInboxTest {
    private val destination = Destination("id", "child", "병원", "주소", 37.0, 127.0, "천천히 오세요")
    @Test fun drivingDefersPromptAndAllResponsesUntilParked() {
        val inbox = DestinationInbox().apply { restricted = true; receive(destination) }
        assertNull(inbox.nextPrompt())
        assertNull(inbox.respond("id", DestinationIntent.ACCEPT))
        inbox.restricted = false
        assertEquals(destination, inbox.nextPrompt())
        assertNull(inbox.nextPrompt())
        assertEquals("accepted", inbox.respond("id", DestinationIntent.ACCEPT)?.status)
    }
    @Test fun duplicatePollCannotRestoreAcceptedDestination() {
        val inbox = DestinationInbox().apply { receive(destination) }
        inbox.respond("id", DestinationIntent.ACCEPT)
        inbox.receive(destination)
        assertTrue(inbox.all().isEmpty())
        assertNull(inbox.respond("id", DestinationIntent.ACCEPT))
    }
    @Test fun deferredDestinationCanBeChosenLater() {
        val inbox = DestinationInbox().apply { receive(destination) }
        assertEquals("deferred", inbox.respond("id", DestinationIntent.DEFER)?.status)
        assertEquals(1, inbox.all().size)
        assertNull(inbox.nextPrompt())
        assertEquals("accepted", inbox.respond("id", DestinationIntent.ACCEPT)?.status)
    }
    @Test fun unclearReplyLeavesDestinationPending() {
        val inbox = DestinationInbox().apply { receive(destination) }
        assertNull(inbox.respond("id", DestinationIntent.ASK_AGAIN))
        assertEquals("pending", inbox.all().single().status)
    }
    @Test fun restoredDeferredDestinationRemainsAvailableWithoutRepeatedPrompt() {
        val first = DestinationInbox().apply { receive(destination); respond("id", DestinationIntent.DEFER) }
        val restored = DestinationInbox().apply { restore(first.snapshot()) }
        assertEquals("deferred", restored.all().single().status)
        assertNull(restored.nextPrompt())
        assertEquals("accepted", restored.respond("id", DestinationIntent.ACCEPT)?.status)
    }
}
