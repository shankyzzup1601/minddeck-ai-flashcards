package com.minddeck.domain.srs

import com.minddeck.data.local.FlashcardDao
import com.minddeck.data.local.ReviewTransactionDao
import java.time.Clock
import java.time.Instant

/** Application boundary for one review: load, calculate, then commit atomically. */
class ReviewCardUseCase(
 private val cards:FlashcardDao,
 private val transactions:ReviewTransactionDao,
 private val scheduler:SpacedRepetitionService,
 private val clock:Clock=Clock.systemUTC()
) {
 suspend operator fun invoke(cardId:String,rating:Rating,responseMillis:Long):ScheduleResult {
  require(responseMillis>=0)
  val card=requireNotNull(cards.get(cardId)) { "Card not found: $cardId" }
  val result=scheduler.review(card,rating,Instant.now(clock),responseMillis)
  transactions.commit(result.card,result.log)
  return result
 }
}
