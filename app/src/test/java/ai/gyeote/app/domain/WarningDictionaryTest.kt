package ai.gyeote.app.domain

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class WarningDictionaryTest(private val code: WarningCode) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun codes(): List<Array<WarningCode>> = WarningCode.entries.map { arrayOf(it) }
    }
    @Test fun everySignalHasPracticalUncertainGuidanceAndShortDrivingText() {
        val advice = WarningDictionary.lookup(code)
        assertEquals(code, advice.code)
        assertTrue(advice.meaning.contains("수 있어요"))
        assertTrue(advice.action.isNotBlank())
        assertTrue(advice.movingText.split(Regex("[.!?]")).filter { it.isNotBlank() }.size in 1..2)
        assertFalse(advice.movingText.contains("고장입니다"))
        assertFalse(advice.movingText.contains("위험합니다"))
    }
    @Test fun redSignalAlwaysPrioritizesStoppingSafely() {
        val advice = WarningDictionary.lookup(code, red = true)
        assertEquals(Urgency.STOP_SAFELY, advice.urgency)
        assertTrue(advice.movingText.contains("안전한 곳"))
    }
}
