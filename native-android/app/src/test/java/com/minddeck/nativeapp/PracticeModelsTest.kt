package com.minddeck.nativeapp
import org.junit.Assert.*
import org.junit.Test
class PracticeModelsTest {
    private val questions get()=PracticeBank.questions.take(5)
    @Test fun starterBankHasCompleteQuestionsForEverySupportedClassAndSubject() {
        assertEquals(80,PracticeBank.questions.size)
        listOf("Class 11","Class 12").forEach {cls->listOf("PCB","PCM","Commerce").flatMap {subjectsFor(it)}.distinct().forEach {subject->assertEquals(5,PracticeBank.questions.count {it.classLevel==cls&&it.subject==subject})}}
        PracticeBank.questions.forEach {q->assertEquals(4,q.options.distinct().size);assertTrue(q.correct in q.options.indices);assertTrue(q.explanation.isNotBlank())}
    }
    @Test fun shufflingPreservesCorrectAnswerAndScoring() {
        val source=questions.associateBy {it.id};var session=PracticeSession.begin(questions,1_000L)
        session.questions.indices.forEach {i->val q=session.questions[i];assertEquals(source[q.id]!!.options[0],q.options[q.correct]);session=session.copy(index=i).choose(q.correct)}
        assertEquals(5,session.score);assertEquals(5,session.answered)
    }
    @Test fun deadlineContinuesAcrossTimeAwayAndNeverBecomesNegative() {
        val session=PracticeSession.begin(questions,1_000L)
        assertEquals(600L,session.remaining(1_000L));assertEquals(1L,session.remaining(600_001L));assertEquals(0L,session.remaining(601_000L));assertEquals(0L,session.remaining(900_000L))
    }
    @Test fun replacingAnAnswerDoesNotDoubleCount() {
        var session=PracticeSession.begin(questions,0L);val correct=session.questions[0].correct
        session=session.choose(correct);assertEquals(1,session.score)
        session=session.choose((correct+1)%4);assertEquals(0,session.score);assertEquals(1,session.answered)
    }
    @Test fun unsuitableDecksAreNotTurnedIntoBrokenTests() {
        val cards=listOf(StudyCard("a","d","Physics","Q1","Same"),StudyCard("b","d","Physics","Q2","same"),StudyCard("c","d","Physics","Q3","Other"))
        assertTrue(PracticeBank.fromCards(cards,"Class 12").isEmpty())
    }
    @Test(expected=IllegalArgumentException::class) fun submittedAttemptsCannotBeChanged() {
        PracticeSession.begin(questions,0L).copy(submitted=true).choose(0)
    }
}
