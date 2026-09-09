package com.minddeck.data.local

import androidx.room.*
import java.time.Instant
import java.util.UUID

enum class Subject { PHYSICS, CHEMISTRY, BIOLOGY }
enum class CardState { NEW, LEARNING, REVIEW, RELEARNING }
enum class QuestionType { MCQ, FLASHCARD, FORMULA_CLOZE }
enum class TestStatus { CREATED, IN_PROGRESS, SUBMITTED, ABANDONED }
enum class QuestionVisitState { NOT_VISITED, VISITED, ANSWERED, MARKED_FOR_REVIEW }

@Entity(tableName="decks", indices=[Index("subject"),Index("chapter")])
data class DeckEntity(
 @PrimaryKey val id:String=UUID.randomUUID().toString(), val title:String,
 val subject:Subject, val chapter:String, val classLevel:Int,
 val createdAt:Instant=Instant.now(), val updatedAt:Instant=Instant.now(),
 val remoteId:String?=null, val dirty:Boolean=true
)

@Entity(tableName="flashcards", foreignKeys=[ForeignKey(entity=DeckEntity::class,parentColumns=["id"],childColumns=["deckId"],onDelete=ForeignKey.CASCADE)], indices=[Index("deckId"),Index("dueAt"),Index(value=["subject","chapter"])])
data class FlashcardEntity(
 @PrimaryKey val id:String=UUID.randomUUID().toString(), val deckId:String,
 val front:String, val back:String, val formulaCloze:String?=null,
 val subject:Subject, val chapter:String, val tags:List<String> = emptyList(),
 val state:CardState=CardState.NEW, val dueAt:Instant=Instant.now(),
 val lastReviewedAt:Instant?=null, val scheduledDays:Int=0,
 val stability:Double=0.0, val difficulty:Double=5.0,
 val repetitions:Int=0, val lapses:Int=0, val learningStep:Int=0,
 val easeFactor:Double=2.5, val suspended:Boolean=false,
 val createdAt:Instant=Instant.now(), val updatedAt:Instant=Instant.now(),
 val remoteId:String?=null, val dirty:Boolean=true
)

@Entity(tableName="review_logs", foreignKeys=[ForeignKey(entity=FlashcardEntity::class,parentColumns=["id"],childColumns=["cardId"],onDelete=ForeignKey.CASCADE)], indices=[Index("cardId"),Index("reviewedAt")])
data class ReviewLogEntity(
 @PrimaryKey val id:String=UUID.randomUUID().toString(), val cardId:String,
 val rating:Int, val stateBefore:CardState, val stateAfter:CardState,
 val scheduledDaysBefore:Int, val scheduledDaysAfter:Int,
 val stabilityBefore:Double, val stabilityAfter:Double,
 val difficultyBefore:Double, val difficultyAfter:Double,
 val elapsedDays:Int, val responseMillis:Long,
 val reviewedAt:Instant=Instant.now(), val remoteId:String?=null, val dirty:Boolean=true
)

@Entity(tableName="questions", foreignKeys=[ForeignKey(entity=DeckEntity::class,parentColumns=["id"],childColumns=["deckId"],onDelete=ForeignKey.CASCADE)], indices=[Index("deckId"),Index(value=["subject","chapter"]),Index("sourceCardId")])
data class QuestionEntity(
 @PrimaryKey val id:String=UUID.randomUUID().toString(), val deckId:String,
 val sourceCardId:String?=null, val type:QuestionType=QuestionType.MCQ,
 val prompt:String, val options:List<String>, val correctIndex:Int,
 val explanation:String, val subject:Subject, val chapter:String,
 val difficulty:Double=5.0, val timesAttempted:Int=0, val timesIncorrect:Int=0,
 val createdAt:Instant=Instant.now(), val remoteId:String?=null, val dirty:Boolean=true
) { init { require(options.size==4); require(correctIndex in 0..3) } }

@Entity(tableName="test_sessions", indices=[Index("startedAt"),Index("status")])
data class TestSessionEntity(
 @PrimaryKey val id:String=UUID.randomUUID().toString(), val title:String,
 val status:TestStatus=TestStatus.CREATED, val questionIds:List<String>,
 val answers:Map<String,Int> = emptyMap(), val visitStates:Map<String,QuestionVisitState> = emptyMap(),
 val responseTimesMillis:Map<String,Long> = emptyMap(), val currentQuestionIndex:Int=0,
 val correctCount:Int=0, val incorrectCount:Int=0, val omittedCount:Int=0,
 val score:Int=0, val startedAt:Instant?=null, val submittedAt:Instant?=null,
 val remoteId:String?=null, val dirty:Boolean=true
)

class MindDeckConverters {
 @TypeConverter fun instantToLong(value:Instant?):Long?=value?.toEpochMilli()
 @TypeConverter fun longToInstant(value:Long?):Instant?=value?.let(Instant::ofEpochMilli)
 @TypeConverter fun stringsToText(value:List<String>)=value.joinToString("\u001F")
 @TypeConverter fun textToStrings(value:String)=if(value.isBlank()) emptyList() else value.split("\u001F")
 @TypeConverter fun subjectToText(value:Subject)=value.name
 @TypeConverter fun textToSubject(value:String)=Subject.valueOf(value)
 @TypeConverter fun cardStateToText(value:CardState)=value.name
 @TypeConverter fun textToCardState(value:String)=CardState.valueOf(value)
 @TypeConverter fun questionTypeToText(value:QuestionType)=value.name
 @TypeConverter fun textToQuestionType(value:String)=QuestionType.valueOf(value)
 @TypeConverter fun testStatusToText(value:TestStatus)=value.name
 @TypeConverter fun textToTestStatus(value:String)=TestStatus.valueOf(value)
 @TypeConverter fun stringIntMapToText(value:Map<String,Int>)=value.entries.joinToString("\u001E") { "${it.key}\u001F${it.value}" }
 @TypeConverter fun textToStringIntMap(value:String)=decodeMap(value) { it.toInt() }
 @TypeConverter fun stringLongMapToText(value:Map<String,Long>)=value.entries.joinToString("\u001E") { "${it.key}\u001F${it.value}" }
 @TypeConverter fun textToStringLongMap(value:String)=decodeMap(value) { it.toLong() }
 @TypeConverter fun visitMapToText(value:Map<String,QuestionVisitState>)=value.entries.joinToString("\u001E") { "${it.key}\u001F${it.value.name}" }
 @TypeConverter fun textToVisitMap(value:String)=decodeMap(value) { QuestionVisitState.valueOf(it) }
 private fun <T> decodeMap(value:String,parse:(String)->T):Map<String,T> = if(value.isBlank()) emptyMap() else value.split("\u001E").associate { row -> val p=row.split("\u001F",limit=2); p[0] to parse(p[1]) }
}

