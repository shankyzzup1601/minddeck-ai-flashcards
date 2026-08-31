package com.minddeck.nativeapp
import org.junit.Assert.*
import org.junit.Test

class StudyModelsTest {
    @Test fun deadlineSurvivesLongBackgroundTime() {
        val timer=TimerState(1500,1500,2000000,"test")
        assertEquals(500,timer.secondsAt(1500000))
        assertEquals(0,timer.secondsAt(3000000))
    }
    @Test fun clockBeforeDeadlineNeverExceedsDuration() {
        assertEquals(1500,TimerState(1500,1500,2000000,"test").secondsAt(1))
    }
    @Test fun pausedTimerDoesNotDrainOrReset() {
        val timer=TimerState(1500,1498,0,"test")
        assertTrue(timer.paused)
        assertFalse(timer.running)
        assertEquals(1498,timer.secondsAt(Long.MAX_VALUE))
    }
    @Test fun completeTimerIsNeitherRunningNorPaused() {
        val timer=TimerState(1500,0)
        assertFalse(timer.paused);assertFalse(timer.running)
    }
    @Test fun failedReviewReturnsInTenMinutes() {
        val card=StudyCard("id","deck","Physics","q","a",interval=10)
        val next=ReviewScheduler.next(card,false,1000)
        assertEquals(601000L,next.due);assertEquals(0,next.interval)
    }
    @Test fun rememberedReviewProgressesWithoutOverflow() {
        var card=StudyCard("id","deck","Physics","q","a")
        repeat(100) {card=ReviewScheduler.next(card,true,1000)}
        assertEquals(365,card.interval);assertEquals(100,card.reviews)
        assertEquals(1000+365*86400000L,card.due)
    }
    @Test fun streamsNeverMixScienceWithCommerce() {
        assertEquals(listOf("Physics","Chemistry","Biology"),subjectsFor("PCB"))
        assertEquals(listOf("Physics","Chemistry","Mathematics"),subjectsFor("PCM"))
        assertFalse(subjectsFor("Commerce").contains("Physics"))
    }
}
