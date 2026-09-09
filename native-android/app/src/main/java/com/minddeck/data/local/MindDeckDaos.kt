package com.minddeck.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class WeakCardProjection(val cardId:String,val deckId:String,val front:String,val back:String,val subject:Subject,val chapter:String,val lapses:Int,val stability:Double,val failureRate:Double,val weaknessScore:Double)
data class DailyActivity(val dayEpoch:Long,val reviewCount:Int,val focusMinutes:Int)

@Dao interface DeckDao {
 @Query("SELECT * FROM decks ORDER BY updatedAt DESC") fun observeAll():Flow<List<DeckEntity>>
 @Query("SELECT * FROM decks WHERE id=:id") suspend fun get(id:String):DeckEntity?
 @Upsert suspend fun upsert(value:DeckEntity)
 @Transaction @Query("DELETE FROM decks WHERE id=:id") suspend fun delete(id:String)
}

@Dao interface FlashcardDao {
 @Query("SELECT * FROM flashcards WHERE deckId=:deckId AND suspended=0 ORDER BY dueAt") fun observeDeck(deckId:String):Flow<List<FlashcardEntity>>
 @Query("SELECT * FROM flashcards WHERE dueAt<=:now AND suspended=0 ORDER BY dueAt LIMIT :limit") suspend fun due(now:Instant,limit:Int):List<FlashcardEntity>
 @Query("SELECT * FROM flashcards WHERE id=:id") suspend fun get(id:String):FlashcardEntity?
 @Upsert suspend fun upsert(value:FlashcardEntity)
 @Upsert suspend fun upsertAll(values:List<FlashcardEntity>)
}

@Dao interface ReviewLogDao {
 @Insert suspend fun insert(value:ReviewLogEntity)
 @Query("SELECT * FROM review_logs WHERE cardId=:cardId ORDER BY reviewedAt DESC") fun observeCard(cardId:String):Flow<List<ReviewLogEntity>>
 @Query("SELECT COUNT(*) FROM review_logs WHERE reviewedAt>=:from AND reviewedAt<:until") suspend fun countBetween(from:Instant,until:Instant):Int
}

@Dao interface QuestionDao {
 @Upsert suspend fun upsertAll(values:List<QuestionEntity>)
 @Query("SELECT * FROM questions WHERE id IN (:ids)") suspend fun byIds(ids:List<String>):List<QuestionEntity>
 @Query("""SELECT f.id AS cardId,f.deckId,f.front,f.back,f.subject,f.chapter,f.lapses,f.stability,
 CASE WHEN q.timesAttempted=0 THEN 0.0 ELSE CAST(q.timesIncorrect AS REAL)/q.timesAttempted END AS failureRate,
 (f.lapses*2.0)+(10.0-MIN(f.stability,10.0))+(CASE WHEN q.timesAttempted=0 THEN 0.0 ELSE (CAST(q.timesIncorrect AS REAL)/q.timesAttempted)*10.0 END) AS weaknessScore
 FROM flashcards f LEFT JOIN questions q ON q.sourceCardId=f.id
 WHERE f.suspended=0 AND (f.lapses>0 OR f.stability<3.0 OR q.timesIncorrect>0)
 ORDER BY weaknessScore DESC LIMIT :limit""") suspend fun weakCards(limit:Int=20):List<WeakCardProjection>
}

@Dao interface TestSessionDao {
 @Query("SELECT * FROM test_sessions WHERE id=:id") fun observe(id:String):Flow<TestSessionEntity?>
 @Upsert suspend fun upsert(value:TestSessionEntity)
 @Query("SELECT * FROM test_sessions ORDER BY COALESCE(startedAt,0) DESC") fun history():Flow<List<TestSessionEntity>>
}

@Database(entities=[DeckEntity::class,FlashcardEntity::class,ReviewLogEntity::class,QuestionEntity::class,TestSessionEntity::class],version=1,exportSchema=true)
@TypeConverters(MindDeckConverters::class)
abstract class MindDeckDatabase:RoomDatabase() {
 abstract fun decks():DeckDao
 abstract fun cards():FlashcardDao
 abstract fun reviews():ReviewLogDao
 abstract fun questions():QuestionDao
 abstract fun tests():TestSessionDao
}

