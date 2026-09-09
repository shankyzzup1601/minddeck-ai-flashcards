package com.minddeck.domain.srs

import com.minddeck.data.local.*
import java.time.*
import kotlin.math.*

enum class Rating(val value:Int) { AGAIN(1), HARD(2), GOOD(3), EASY(4) }
data class ScheduleResult(val card:FlashcardEntity,val log:ReviewLogEntity)

class SpacedRepetitionService(
 private val targetRetention:Double=0.90,
 private val learningSteps:List<Duration> = listOf(Duration.ofMinutes(1),Duration.ofMinutes(10)),
 private val relearningSteps:List<Duration> = listOf(Duration.ofMinutes(10))
) {
 init { require(targetRetention in 0.80..0.98); require(learningSteps.isNotEmpty()) }

 fun review(card:FlashcardEntity,rating:Rating,now:Instant=Instant.now(),responseMillis:Long=0):ScheduleResult {
  require(!card.suspended)
  val elapsed=card.lastReviewedAt?.let { Duration.between(it,now).toDays().coerceAtLeast(0).toInt() } ?: 0
  val next=when(card.state) {
   CardState.NEW,CardState.LEARNING -> scheduleLearning(card,rating,now)
   CardState.REVIEW -> scheduleReview(card,rating,elapsed,now)
   CardState.RELEARNING -> scheduleRelearning(card,rating,now)
  }.copy(lastReviewedAt=now,updatedAt=now,dirty=true)
  val log=ReviewLogEntity(cardId=card.id,rating=rating.value,stateBefore=card.state,stateAfter=next.state,scheduledDaysBefore=card.scheduledDays,scheduledDaysAfter=next.scheduledDays,stabilityBefore=card.stability,stabilityAfter=next.stability,difficultyBefore=card.difficulty,difficultyAfter=next.difficulty,elapsedDays=elapsed,responseMillis=responseMillis,reviewedAt=now)
  return ScheduleResult(next,log)
 }

 private fun scheduleLearning(c:FlashcardEntity,r:Rating,now:Instant):FlashcardEntity = when(r) {
  Rating.AGAIN -> step(c,CardState.LEARNING,0,learningSteps[0],now,lapse=false)
  Rating.HARD -> step(c,CardState.LEARNING,c.learningStep,(learningSteps.getOrNull(c.learningStep)?:Duration.ofMinutes(10)).multipliedBy(2),now,false)
  Rating.GOOD -> if(c.learningStep+1<learningSteps.size) step(c,CardState.LEARNING,c.learningStep+1,learningSteps[c.learningStep+1],now,false) else graduate(c,1,now,1.0)
  Rating.EASY -> graduate(c,4,now,1.3)
 }

 private fun scheduleRelearning(c:FlashcardEntity,r:Rating,now:Instant):FlashcardEntity = when(r) {
  Rating.AGAIN -> step(c,CardState.RELEARNING,0,relearningSteps[0],now,false)
  Rating.HARD -> step(c,CardState.RELEARNING,c.learningStep,relearningSteps.last().multipliedBy(2),now,false)
  Rating.GOOD -> graduate(c,max(1,(c.stability*0.55).roundToInt()),now,0.85)
  Rating.EASY -> graduate(c,max(2,(c.stability*0.8).roundToInt()),now,1.0)
 }

 private fun scheduleReview(c:FlashcardEntity,r:Rating,elapsed:Int,now:Instant):FlashcardEntity {
  if(r==Rating.AGAIN) return c.copy(state=CardState.RELEARNING,dueAt=now.plus(relearningSteps[0]),scheduledDays=0,learningStep=0,lapses=c.lapses+1,repetitions=c.repetitions+1,stability=max(0.25,c.stability*0.45),difficulty=(c.difficulty+0.8).coerceIn(1.0,10.0),easeFactor=max(1.3,c.easeFactor-0.2))
  val easeDelta=when(r){Rating.HARD->-0.15;Rating.GOOD->0.0;Rating.EASY->0.15;else->0.0}
  val nextEase=(c.easeFactor+easeDelta).coerceIn(1.3,3.0)
  val qualityFactor=when(r){Rating.HARD->1.2;Rating.GOOD->nextEase;Rating.EASY->nextEase*1.3;else->1.0}
  val base=max(c.scheduledDays.toDouble(),max(1,elapsed).toDouble())
  val retentionScale=ln(targetRetention)/ln(0.90)
  val days=max(1,(base*qualityFactor*retentionScale).roundToInt()).coerceAtMost(36500)
  val newStability=max(c.stability,days.toDouble())*when(r){Rating.HARD->1.05;Rating.GOOD->1.18;Rating.EASY->1.35;else->1.0}
  val newDifficulty=(c.difficulty+when(r){Rating.HARD->0.25;Rating.GOOD->-0.08;Rating.EASY->-0.3;else->0.0}).coerceIn(1.0,10.0)
  return c.copy(state=CardState.REVIEW,dueAt=now.plus(days.toLong(),java.time.temporal.ChronoUnit.DAYS),scheduledDays=days,learningStep=0,repetitions=c.repetitions+1,stability=newStability,difficulty=newDifficulty,easeFactor=nextEase)
 }

 private fun step(c:FlashcardEntity,state:CardState,index:Int,d:Duration,now:Instant,lapse:Boolean)=c.copy(state=state,dueAt=now.plus(d),scheduledDays=0,learningStep=index,repetitions=c.repetitions+1,lapses=c.lapses+if(lapse)1 else 0,difficulty=(c.difficulty+0.15).coerceAtMost(10.0))
 private fun graduate(c:FlashcardEntity,days:Int,now:Instant,stabilityMultiplier:Double)=c.copy(state=CardState.REVIEW,dueAt=now.plus(days.toLong(),java.time.temporal.ChronoUnit.DAYS),scheduledDays=days,learningStep=0,repetitions=c.repetitions+1,stability=max(1.0,days*stabilityMultiplier),difficulty=(c.difficulty-0.15).coerceAtLeast(1.0))
}

