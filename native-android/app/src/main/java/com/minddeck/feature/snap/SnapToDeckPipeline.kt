package com.minddeck.feature.snap

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable

@Serializable data class GeneratedCard(val front:String,val back:String,val formulaCloze:String?=null,val subject:String,val chapter:String)
@Serializable data class GeneratedMcq(val prompt:String,val options:List<String>,val correctIndex:Int,val explanation:String,val subject:String,val chapter:String)
@Serializable data class StudyGeneration(val cards:List<GeneratedCard>,val mcqs:List<GeneratedMcq>)
data class OcrDocument(val text:String,val blockCount:Int)

interface StudyGenerationClient {
 suspend fun generateFromNcertText(request:GenerationRequest):StudyGeneration
}
@Serializable data class GenerationRequest(val sourceText:String,val requestedSubject:String?=null,val requestedChapter:String?=null,val cardCount:Int=15,val mcqCount:Int=10,val schemaVersion:Int=1)

class MlKitOcrExtractor {
 private val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
 suspend fun extract(bitmap:Bitmap,rotationDegrees:Int=0):OcrDocument {
  require(bitmap.width>0 && bitmap.height>0)
  return recognize(InputImage.fromBitmap(bitmap,rotationDegrees))
 }
 suspend fun extract(context:Context,uri:Uri):OcrDocument = recognize(InputImage.fromFilePath(context,uri))
 private suspend fun recognize(image:InputImage):OcrDocument {
  val result=recognizer.process(image).await()
  val cleaned=result.text.replace(Regex("[ \\t]+")," ").replace(Regex("\\n{3,}"),"\\n\\n").trim()
  require(cleaned.length>=30) {"Not enough readable text. Retake the photo in brighter light and keep the page flat."}
  return OcrDocument(cleaned,result.textBlocks.size)
 }
 fun close()=recognizer.close()
}

class SnapToDeckPipeline(private val ocr:MlKitOcrExtractor,private val client:StudyGenerationClient) {
 suspend fun execute(bitmap:Bitmap,rotationDegrees:Int=0,subject:String?=null,chapter:String?=null):StudyGeneration {
  val document=ocr.extract(bitmap,rotationDegrees)
  val safeText=document.text.take(16_000)
  return validate(client.generateFromNcertText(GenerationRequest(safeText,subject,chapter)))
 }
 private fun validate(value:StudyGeneration):StudyGeneration {
  require(value.cards.isNotEmpty()) {"The model returned no flashcards."}
  require(value.cards.size<=30 && value.mcqs.size<=30)
  value.cards.forEach { require(it.front.isNotBlank() && it.back.isNotBlank()); require(it.front.length<=500 && it.back.length<=2000) }
  value.mcqs.forEach { require(it.prompt.isNotBlank()); require(it.options.size==4); require(it.correctIndex in 0..3); require(it.options.all(String::isNotBlank)); require(it.explanation.isNotBlank()) }
  return value.copy(cards=value.cards.distinctBy {it.front.trim().lowercase()},mcqs=value.mcqs.distinctBy {it.prompt.trim().lowercase()})
 }
}

