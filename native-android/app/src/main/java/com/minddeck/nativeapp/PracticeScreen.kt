package com.minddeck.nativeapp

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable internal fun PracticeScreen(state: StudyUiState) {
    val owner=state.user?.id ?: "guest"
    val context=LocalContext.current.applicationContext
    val prefs=remember {context.getSharedPreferences("native-practice",Context.MODE_PRIVATE)}
    var encoded by remember(owner) {mutableStateOf(prefs.getString("active:$owner","").orEmpty())}
    var historyRaw by remember(owner) {mutableStateOf(prefs.getString("history:$owner","[]").orEmpty())}
    var showAttempt by rememberSaveable(owner) {mutableStateOf(encoded.isNotBlank())}
    var historical by rememberSaveable(owner) {mutableStateOf("")}
    var discard by remember {mutableStateOf(false)}
    var submit by remember {mutableStateOf(false)}
    var subject by rememberSaveable(owner) {mutableStateOf(subjectsFor(state.profile.stream).first())}
    var source by rememberSaveable(owner) {mutableStateOf("Starter chapters")}
    var chapter by rememberSaveable(owner) {mutableStateOf("")}
    var clock by remember {mutableLongStateOf(System.currentTimeMillis())}
    val quiz=remember(encoded,historical) {PracticeSession.decode(historical.ifBlank {encoded})}
    val subjects=subjectsFor(state.profile.stream)
    LaunchedEffect(state.profile.stream) {if(subject !in subjects) subject=subjects.first()}
    val available=remember(subject,state.profile.classLevel) {PracticeBank.questions.filter {it.classLevel==state.profile.classLevel&&it.subject==subject}}
    val chapters=if(source=="Starter chapters") available.map {it.chapter}.distinct() else state.cards.filter {it.subject==subject}.map {it.deck}.distinct()
    LaunchedEffect(source,subject,chapters) {if(chapter !in chapters) chapter=chapters.firstOrNull().orEmpty()}
    val questions=if(source=="Starter chapters") available.filter {it.chapter==chapter} else PracticeBank.fromCards(state.cards.filter {it.subject==subject&&it.deck==chapter},state.profile.classLevel)
    fun save(session: PracticeSession) {
        val raw=session.encode()
        prefs.edit().putString("active:$owner",raw).apply();encoded=raw
        if(session.submitted) {
            val old=runCatching {JSONArray(historyRaw)}.getOrElse {JSONArray()}
            if((0 until old.length()).none {PracticeSession.decode(old.getString(it))?.id==session.id}) {
                val next=JSONArray().put(raw);for(i in 0 until minOf(old.length(),19)) next.put(old.getString(i))
                historyRaw=next.toString();prefs.edit().putString("history:$owner",historyRaw).apply()
            }
        }
    }
    LaunchedEffect(quiz?.id,quiz?.submitted) {
        if(quiz!=null&&!quiz.submitted) while(true) {clock=System.currentTimeMillis();delay(500)}
    }
    LaunchedEffect(quiz?.id,quiz?.remaining(clock)) {if(quiz!=null&&!quiz.submitted&&quiz.remaining(clock)==0L) save(quiz.copy(submitted=true))}
    BackHandler(showAttempt) {showAttempt=false;historical=""}
    if(showAttempt&&quiz!=null) {
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            item {PageHeader(if(quiz.submitted) "Test results" else "Practice test",quiz.questions.first().chapter,back={showAttempt=false;historical=""})}
            if(quiz.submitted) {
                item {Surface(color=PanelElevated,shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,Hairline)) {Column(Modifier.fillMaxWidth().padding(24.dp)) {Pill("SAVED ON THIS DEVICE");Text("${quiz.score} / ${quiz.questions.size}",fontSize=42.sp,fontWeight=FontWeight.Bold,color=Lime,modifier=Modifier.padding(vertical=14.dp));Text("${quiz.answered} answered · ${quiz.questions.size-quiz.answered} unanswered",color=Muted,fontSize=13.sp);Text("+1 for a correct answer. No negative marking.",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=10.dp))}}}
                items(quiz.questions.indices.toList()) {i->val q=quiz.questions[i];val chosen=quiz.answers[i];Surface(color=Panel,shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,Hairline)) {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("${i+1}. ${q.prompt}",fontWeight=FontWeight.SemiBold,fontSize=16.sp,lineHeight=23.sp);Text("Your answer: ${q.options.getOrNull(chosen) ?: "Unanswered"}",color=if(chosen==q.correct) Lime else Peach,fontSize=13.sp);Text("Correct answer: ${q.options[q.correct]}",color=Lime,fontSize=13.sp);Text(q.explanation,color=Muted,fontSize=13.sp,lineHeight=20.sp)}}}
                item {ActionButton("Back to tests",{showAttempt=false;historical=""})}
            } else {
                val q=quiz.questions[quiz.index]
                item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Pill("${quiz.index+1} / ${quiz.questions.size}");Spacer(Modifier.weight(1f));Pill("%02d:%02d remaining".format(quiz.remaining(clock)/60,quiz.remaining(clock)%60),Lavender)}}
                item {LinearProgressIndicator(progress={quiz.answered.toFloat()/quiz.questions.size},modifier=Modifier.fillMaxWidth());Text("${quiz.answered} answered · You can go back and change answers.",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=10.dp))}
                item {Text(q.prompt,fontSize=23.sp,lineHeight=31.sp,fontWeight=FontWeight.SemiBold)}
                items(q.options.indices.toList()) {i->val selected=quiz.answers[quiz.index]==i;OutlinedCard(onClick={if(quiz.remaining(System.currentTimeMillis())==0L) save(quiz.copy(submitted=true)) else save(quiz.choose(i))},modifier=Modifier.fillMaxWidth().testTag("answer-option-$i"),shape=RoundedCornerShape(16.dp),border=BorderStroke(if(selected) 2.dp else 1.dp,if(selected) Lime else Hairline),colors=CardDefaults.outlinedCardColors(containerColor=if(selected) PanelElevated else Panel)) {Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Text("${'A'+i}",color=if(selected) Lime else Muted,fontSize=16.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(end=14.dp));Text(q.options[i],fontSize=15.sp,lineHeight=22.sp,modifier=Modifier.weight(1f));if(selected) Icon(Icons.Rounded.CheckCircle,null,tint=Lime,modifier=Modifier.size(20.dp))}}}
                item {Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {OutlinedButton(onClick={save(quiz.copy(index=quiz.index-1))},enabled=quiz.index>0,modifier=Modifier.weight(1f).heightIn(min=52.dp)){Text("Previous")};Button(onClick={if(quiz.index==quiz.questions.lastIndex) submit=true else save(quiz.copy(index=quiz.index+1))},modifier=Modifier.weight(1f).heightIn(min=52.dp)){Text(if(quiz.index==quiz.questions.lastIndex) "Submit test" else "Next")}}}
                item {TextButton(onClick={showAttempt=false;historical=""}){Text("Save and return to tests")};Text("The test timer continues while you leave the screen or close the app.",color=Muted,fontSize=12.sp,lineHeight=18.sp)}
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            item {PageHeader("Free tests","Practise. Understand. Try again.")}
            item {Surface(color=PanelElevated,shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Pill("FREE PRACTICE",Lime);Text("Test what you remember.",fontSize=24.sp,fontWeight=FontWeight.Bold);Text("80 starter questions across Classes 11 and 12, filtered to your stream. Each attempt allows two minutes per question.",color=Muted,fontSize=13.sp,lineHeight=21.sp);Text("This is an original starter bank, not full-syllabus coverage or an official exam.",color=Muted,fontSize=11.sp,lineHeight=17.sp)}}}
            if(quiz!=null&&!quiz.submitted) item {ActionButton("Resume current test",{clock=System.currentTimeMillis();showAttempt=true});TextButton(onClick={discard=true}){Text("Discard current attempt")}}
            item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("Starter chapters","My decks").forEach {s->FilterChip(selected=source==s,onClick={source=s},label={Text(s)})}}}
            item {SelectField("Subject",subject,subjects){subject=it}}
            if(chapters.isNotEmpty()) item {SelectField(if(source=="My decks") "Deck" else "Chapter",chapter,chapters){chapter=it}}
            item {Text(if(questions.isEmpty()&&source=="My decks") "Choose a deck with at least four distinct answers. Create or add cards in your library first." else "${questions.size} questions · ${questions.size*2} minutes · No negative marking",fontSize=13.sp,lineHeight=20.sp,color=Muted)}
            if(source=="My decks") item {Text("Questions and answer choices come from your saved cards. Check their accuracy before using them for exam revision.",color=Muted,fontSize=12.sp,lineHeight=18.sp)}
            item {ActionButton("Start free test",{save(PracticeSession.begin(questions,System.currentTimeMillis()));clock=System.currentTimeMillis();showAttempt=true},enabled=questions.isNotEmpty()&&(quiz==null||quiz.submitted))}
            item {Section("Recent results","Up to 20 attempts, stored for this account on this device")}
            val history=runCatching {val a=JSONArray(historyRaw);(0 until a.length()).mapNotNull {PracticeSession.decode(a.getString(it))}}.getOrDefault(emptyList())
            if(history.isEmpty()) item {Text("Your completed tests will appear here.",color=Muted,fontSize=13.sp)}
            items(history,key={it.id}) {past->OutlinedCard(onClick={historical=past.encode();showAttempt=true},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),colors=CardDefaults.outlinedCardColors(containerColor=Panel)) {Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(past.questions.first().chapter,fontWeight=FontWeight.SemiBold);Text("${past.questions.first().subject} · ${SimpleDateFormat("d MMM",Locale.getDefault()).format(Date(past.endAt-past.questions.size*120_000L))}",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=6.dp))};Text("${past.score}/${past.questions.size}",fontSize=20.sp,fontWeight=FontWeight.Bold,color=Lime)}}}
        }
    }
    if(discard) AlertDialog(onDismissRequest={discard=false},title={Text("Discard this attempt?")},text={Text("Your unfinished answers will be removed. Completed results stay saved.")},confirmButton={TextButton(onClick={prefs.edit().remove("active:$owner").apply();encoded="";discard=false;showAttempt=false;historical=""}){Text("Discard")}},dismissButton={TextButton(onClick={discard=false}){Text("Keep attempt")}})
    if(submit&&quiz!=null) AlertDialog(onDismissRequest={submit=false},title={Text("Submit test?")},text={Text("${quiz.answered} of ${quiz.questions.size} questions answered. Submission is final.")},confirmButton={TextButton(onClick={save(quiz.copy(submitted=true));submit=false}){Text("Submit")}},dismissButton={TextButton(onClick={submit=false}){Text("Keep reviewing")}})
}
