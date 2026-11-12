package ai.gyeote.buzzer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionsTest {
    private val a = Question("가", truth = true, why = "가 설명")
    private val b = Question("나", truth = false, why = "나 설명")
    private val c = Question("다", truth = true, why = "다 설명")

    @Test fun `bank questions come in order and wrap around`() {
        val source = QuestionSource(listOf(a, b), carEvery = 0)
        assertEquals(listOf(a, b, a), List(3) { source.next(CarFacts()) })
    }

    @Test fun `cursor resumes where the last session stopped`() {
        val first = QuestionSource(listOf(a, b, c), carEvery = 0)
        first.next(CarFacts())
        val second = QuestionSource(listOf(a, b, c), cursor = first.cursor, carEvery = 0)
        assertEquals(b, second.next(CarFacts()))
    }

    @Test fun `every third question is made from the car when the car has data`() {
        val source = QuestionSource(listOf(a, b, c), carEvery = 3)
        val asked = List(3) { source.next(CarFacts(socPercent = 62)) }
        assertEquals(listOf(a, b), asked.take(2))
        assertTrue(asked[2].fromCar)
    }

    @Test fun `without car data the bank fills the car slot`() {
        val source = QuestionSource(listOf(a, b, c), carEvery = 3)
        assertEquals(listOf(a, b, c), List(3) { source.next(CarFacts()) })
    }

    @Test fun `battery question is true above half and shows the real value`() {
        val question = CarQuestions.make(CarFacts(socPercent = 62), 0)
        assertNotNull(question)
        assertTrue(question!!.truth)
        assertTrue(question.why.contains("62%"))
    }

    @Test fun `battery question is false at half or below`() {
        assertFalse(CarQuestions.make(CarFacts(socPercent = 50), 0)!!.truth)
    }

    @Test fun `car questions rotate over the signals that are present`() {
        val facts = CarFacts(socPercent = 62, rangeKm = 180.0, odometerKm = 12_346.0, chargePortOpen = true)
        val texts = List(4) { CarQuestions.make(facts, it)!!.text }
        assertEquals(4, texts.toSet().size)
        assertEquals(texts[0], CarQuestions.make(facts, 4)!!.text)
    }

    @Test fun `range question compares with 200 km`() {
        val question = CarQuestions.make(CarFacts(rangeKm = 180.0), 0)!!
        assertFalse(question.truth)
        assertTrue(question.why.contains("180km"))
    }

    @Test fun `odometer question compares with one lap of the earth`() {
        val question = CarQuestions.make(CarFacts(odometerKm = 12_346.0), 0)!!
        assertFalse(question.truth)
        assertTrue(question.why.contains("12,346km"))
    }

    @Test fun `no car data means no car question`() {
        assertNull(CarQuestions.make(CarFacts(), 0))
    }

    @Test fun `built-in bank has enough questions and each has an explanation`() {
        assertTrue(QuestionBank.all.size >= 30)
        assertTrue(QuestionBank.all.all { it.text.isNotBlank() && it.why.isNotBlank() && !it.fromCar })
        assertEquals(QuestionBank.all.size, QuestionBank.all.map { it.text }.toSet().size)
    }

    @Test fun `built-in bank mixes true and false answers`() {
        val trues = QuestionBank.all.count { it.truth }
        assertTrue(trues in QuestionBank.all.size / 3..QuestionBank.all.size * 2 / 3)
    }
}
