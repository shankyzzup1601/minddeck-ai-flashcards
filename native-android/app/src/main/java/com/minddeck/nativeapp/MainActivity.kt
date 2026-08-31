package com.minddeck.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom

private val Ink=Color(0xFF101318)
private val Panel=Color(0xFF1B2028)
private val Lime=Color(0xFFCEEFA0)
private val Lavender=Color(0xFFCDBCFB)
private val Muted=Color(0xFFA7ADB8)
private val MindDeckColors=darkColorScheme(primary=Lime,onPrimary=Ink,secondary=Lavender,background=Ink,surface=Panel,onSurface=Color(0xFFF3F4F6),onBackground=Color(0xFFF3F4F6),surfaceVariant=Color(0xFF242B35),onSurfaceVariant=Muted)

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(0xFF101318.toInt()),navigationBarStyle=SystemBarStyle.dark(0xFF101318.toInt()))
        setContent { MaterialTheme(colorScheme=MindDeckColors) { MindDeckApp(this) } }
    }
}

@Composable
fun MindDeckApp(activity: ComponentActivity, vm: StudyViewModel=viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var composer by rememberSaveable { mutableStateOf(false) }
    var composerSubject by rememberSaveable { mutableStateOf("") }
    var studyDeck by rememberSaveable { mutableStateOf<String?>(null) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var profileEdit by rememberSaveable { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    val snackbar=remember { SnackbarHostState() }
    LaunchedEffect(state.error,state.info) {
        (state.error ?: state.info)?.let { snackbar.showSnackbar(it,duration=SnackbarDuration.Long); vm.clearMessage() }
    }
    BackHandler(composer || studyDeck != null || tab != 0) { when { composer -> composer=false; studyDeck != null -> studyDeck=null; else -> tab=0 } }
    fun signIn() {
        if(signingIn || state.busy) return
        if(state.serverClientId.isBlank()) {
            vm.refreshConfig()
            vm.showError("Google sign-in is not configured for this new Android build yet. Your saved cards are available without signing in.")
            return
        }
        signingIn=true
        scope.launch {
            try {
                val rawNonce=ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val hashed=MessageDigest.getInstance("SHA-256").digest(rawNonce.toByteArray()).joinToString("") { "%02x".format(it) }
                val option=GetGoogleIdOption.Builder().setFilterByAuthorizedAccounts(false).setAutoSelectEnabled(false)
                    .setServerClientId(state.serverClientId).setNonce(hashed).build()
                val result=CredentialManager.create(activity).getCredential(activity,GetCredentialRequest.Builder().addCredentialOption(option).build())
                val credential=result.credential
                if(credential is CustomCredential && credential.type==GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    vm.signIn(GoogleIdTokenCredential.createFrom(credential.data).idToken,rawNonce)
                } else vm.showError("Google returned an unsupported credential. Please try again.")
            } catch(_: GetCredentialCancellationException) { /* A cancelled chooser is not an error. */ }
              catch(_: Exception) { vm.showError("Google couldn't finish sign-in. Check connectivity and this APK's OAuth certificate registration, then retry.") }
            finally { signingIn=false }
        }
    }
    if(state.loading) {
        Surface(Modifier.fillMaxSize(),color=Ink) { Box(contentAlignment=Alignment.Center) { Column(horizontalAlignment=Alignment.CenterHorizontally) { Icon(Icons.Rounded.AutoStories,"MindDeck",tint=Lime,modifier=Modifier.size(60.dp)); Spacer(Modifier.height(20.dp)); CircularProgressIndicator() } } }
        return
    }
    if(!state.profile.complete || profileEdit) {
        ProfileSetup(state.profile) { name,cls,stream -> vm.saveProfile(name,cls,stream); profileEdit=false }
        return
    }
    Scaffold(
        containerColor=Ink,
        snackbarHost={ SnackbarHost(snackbar) },
        bottomBar={ if(!composer && studyDeck==null) NavigationBar(containerColor=Ink,tonalElevation=0.dp) {
            listOf("Home" to Icons.Rounded.Home,"Library" to Icons.Rounded.AutoStories,"Focus" to Icons.Rounded.Timer,"You" to Icons.Rounded.Person).forEachIndexed { i,(label,icon) ->
                NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(icon,label)},label={Text(label)},colors=NavigationBarItemDefaults.colors(indicatorColor=Panel,selectedIconColor=Lime,selectedTextColor=Lime))
            }
        } }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when {
                composer -> Composer(state,vm,composerSubject,onBack={composer=false},onComplete={if(composer){composer=false;tab=1}},onSignIn={signIn()})
                studyDeck!=null -> StudyScreen(studyDeck!!,state,vm) {studyDeck=null}
                tab==0 -> HomeScreen(state,onCreate={composerSubject="";composer=true},onSubject={composerSubject=it;composer=true},onStudy={tab=1},onFocus={tab=2},onAccount={tab=3})
                tab==1 -> LibraryScreen(state,vm,onCreate={composerSubject="";composer=true},onManual={manual=true},onStudy={studyDeck=it})
                tab==2 -> FocusScreen(state,vm)
                else -> AccountScreen(state,signingIn,onSignIn={signIn()},onSignOut={vm.signOut()},onEdit={profileEdit=true},onRetry={vm.refreshConfig()})
            }
        }
    }
    if(manual) ManualCardDialog(state.profile,vm) {manual=false}
}

@Composable private fun PageHeader(title: String,subtitle: String?=null,back: (() -> Unit)?=null,action: (@Composable () -> Unit)?=null) {
    Row(Modifier.fillMaxWidth().padding(bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
        if(back!=null) IconButton(onClick=back) {Icon(Icons.Rounded.ArrowBack,"Go back")}
        Column(Modifier.weight(1f)) { Text(title,fontSize=26.sp,fontWeight=FontWeight.Bold); subtitle?.let {Text(it,color=Muted,fontSize=14.sp,modifier=Modifier.padding(top=4.dp))} }
        action?.invoke()
    }
}
@Composable private fun Pill(text: String,color: Color=Lime) { Surface(color=color.copy(alpha=.12f),shape=RoundedCornerShape(50)) { Text(text,color=color,fontSize=12.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(horizontal=12.dp,vertical=7.dp)) } }
@Composable private fun ActionButton(text: String,onClick: () -> Unit,modifier: Modifier=Modifier,enabled: Boolean=true) {
    Button(onClick=onClick,enabled=enabled,modifier=modifier.fillMaxWidth().heightIn(min=54.dp),shape=RoundedCornerShape(16.dp)) {Text(text,fontSize=16.sp,fontWeight=FontWeight.Bold,textAlign=TextAlign.Center)}
}
@Composable private fun Section(title: String,subtitle: String?=null) {
    Column {Text(title,fontSize=20.sp,fontWeight=FontWeight.Bold); subtitle?.let {Text(it,color=Muted,fontSize=14.sp,modifier=Modifier.padding(top=4.dp))}}
}
@Composable private fun Metric(value: String,label: String,modifier: Modifier=Modifier) {
    Column(modifier.background(Panel,RoundedCornerShape(18.dp)).padding(16.dp)) {Text(value,fontSize=26.sp,fontWeight=FontWeight.Bold);Text(label,color=Muted,fontSize=13.sp)}
}
@Composable private fun HomeScreen(state: StudyUiState,onCreate: () -> Unit,onSubject: (String) -> Unit,onStudy: () -> Unit,onFocus: () -> Unit,onAccount: () -> Unit) {
    val due=state.cards.count {it.due <= System.currentTimeMillis()}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        item { Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("MINDDECK",color=Lime,fontSize=12.sp,letterSpacing=3.sp,fontWeight=FontWeight.Bold); Text("Hello, ${state.user?.name?.substringBefore(' ') ?: state.profile.name.substringBefore(' ')}",fontSize=26.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=6.dp)) }
            FilledTonalIconButton(onClick=onAccount,modifier=Modifier.size(48.dp)) {Icon(Icons.Rounded.Person,"Your account")}
        } }
        item { Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF28342B),Color(0xFF25302E))),RoundedCornerShape(28.dp)).padding(24.dp)) {
            Pill("YOUR NEXT SMALL WIN")
            Text("Less cramming.\nMore remembering.",fontSize=30.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=20.dp,bottom=12.dp))
            Text(if(due>0) "$due cards are ready for a fresh look." else "Turn a chapter into a study session that sticks.",color=Color(0xFFC7D1C8),fontSize=15.sp)
            Spacer(Modifier.height(22.dp)); ActionButton(if(due>0) "Review your cards  →" else "Create your first AI deck  →",if(due>0) onStudy else onCreate)
        } }
        item { Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {Metric("${state.cards.size}","Saved cards",Modifier.weight(1f));Metric("${state.focusSeconds/60}","Focus minutes",Modifier.weight(1f))} }
        item {Section("Your subjects","${state.profile.classLevel} · ${state.profile.stream}")}
        items(subjectsFor(state.profile.stream)) { subject ->
            Row(Modifier.fillMaxWidth().clickable {onSubject(subject)}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(Lavender.copy(alpha=.12f),RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center) {Icon(subjectIcon(subject),null,tint=Lavender)}
                Column(Modifier.weight(1f).padding(horizontal=16.dp)) {Text(subject,fontSize=17.sp,fontWeight=FontWeight.SemiBold);Text("${state.cards.count {it.subject==subject}} saved cards",color=Muted,fontSize=13.sp)}
                Icon(Icons.Rounded.ChevronRight,"Create $subject cards",tint=Muted)
            }
        }
        item { OutlinedCard(onClick=onFocus,shape=RoundedCornerShape(22.dp),border=BorderStroke(1.dp,Color(0xFF353D46)),colors=CardDefaults.outlinedCardColors(containerColor=Ink)) {
            Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically) {Icon(Icons.Rounded.Timer,null,tint=Lime);Column(Modifier.weight(1f).padding(start=16.dp)){Text("Make room for focus",fontWeight=FontWeight.Bold);Text("One task. One calm session.",color=Muted,fontSize=13.sp)};Icon(Icons.Rounded.ArrowForward,null)}
        } }
    }
}
private fun subjectIcon(subject: String): ImageVector = when(subject) {"Physics" -> Icons.Rounded.Bolt;"Chemistry" -> Icons.Rounded.Science;"Biology" -> Icons.Rounded.Eco;"Mathematics" -> Icons.Rounded.Calculate;else -> Icons.Rounded.MenuBook}

@Composable private fun LibraryScreen(state: StudyUiState,vm: StudyViewModel,onCreate: () -> Unit,onManual: () -> Unit,onStudy: (String)->Unit) {
    var search by rememberSaveable {mutableStateOf("")}
    var delete by rememberSaveable {mutableStateOf<String?>(null)}
    val decks=state.cards.groupBy {it.deck}.map {(title,cards)->DeckSummary(title,cards.first().subject,cards.size,cards.count {it.due<=System.currentTimeMillis()})}.filter {it.title.contains(search,true)||it.subject.contains(search,true)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {PageHeader("Your library","Small decks. Lasting knowledge.")}
        item {ActionButton("✨  Create with AI",onCreate)}
        item {OutlinedTextField(value=search,onValueChange={search=it},label={Text("Search your decks")},leadingIcon={Icon(Icons.Rounded.Search,null)},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(16.dp))}
        if(decks.isEmpty()) item {Column(Modifier.fillMaxWidth().padding(vertical=36.dp),horizontalAlignment=Alignment.CenterHorizontally) {Icon(Icons.Rounded.AutoStories,null,tint=Lavender,modifier=Modifier.size(60.dp));Spacer(Modifier.height(16.dp));Text(if(search.isBlank()) "Your first deck starts here" else "No matching decks",fontSize=20.sp,fontWeight=FontWeight.Bold);Text("Create from a chapter, or add a card yourself.",color=Muted,textAlign=TextAlign.Center,modifier=Modifier.padding(vertical=10.dp));TextButton(onClick=onManual){Text("＋ Add a card manually")}}}
        items(decks,key={it.title}) { deck -> Card(shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Panel)) {Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {Pill(deck.subject,Lavender);Spacer(Modifier.weight(1f));IconButton(onClick={delete=deck.title},enabled=!state.busy) {Icon(Icons.Rounded.DeleteOutline,"Delete ${deck.title}",tint=Muted)}}
            Text(deck.title,fontSize=22.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=12.dp));Text("${deck.count} cards  ·  ${deck.due} due now",color=Muted)
            Spacer(Modifier.height(16.dp));ActionButton("Study deck  →",{onStudy(deck.title)})
        }} }
        if(decks.isNotEmpty()) item {OutlinedButton(onClick=onManual,modifier=Modifier.fillMaxWidth()){Text("＋ Add a card manually")}}
    }
    delete?.let { title -> AlertDialog(onDismissRequest={delete=null},title={Text("Delete this deck?")},text={Text("This removes “$title” from this account on this device. It cannot be undone.")},confirmButton={TextButton(onClick={vm.deleteDeck(title);delete=null}){Text("Delete")}},dismissButton={TextButton(onClick={delete=null}){Text("Keep deck")}}) }
}

@Composable private fun Composer(state: StudyUiState,vm: StudyViewModel,initialSubject: String,onBack: () -> Unit,onComplete: () -> Unit,onSignIn: () -> Unit) {
    val subjects=subjectsFor(state.profile.stream)
    var subject by rememberSaveable(initialSubject) {mutableStateOf(initialSubject.takeIf {it in subjects} ?: subjects.first())}
    var chapter by rememberSaveable {mutableStateOf("")}
    var notes by rememberSaveable {mutableStateOf("")}
    var useNotes by rememberSaveable {mutableStateOf(false)}
    val chapters=vm.chapters(subject)
    LaunchedEffect(subject,state.profile.classLevel) {if(chapter !in chapters) chapter=chapters.firstOrNull().orEmpty()}
    LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {PageHeader("Create a deck","AI does the drafting. You do the learning.",onBack)}
        item {Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {FilterChip(selected=!useNotes,onClick={useNotes=false},label={Text("Ready syllabus")});FilterChip(selected=useNotes,onClick={useNotes=true},label={Text("My notes")})}}
        item {Pill("${state.profile.classLevel} · ${state.profile.stream}",Lavender)}
        item {SelectField("Subject",subject,subjects) {subject=it}}
        if(useNotes) item {OutlinedTextField(value=notes,onValueChange={notes=it.take(12000)},label={Text("Paste your study notes")},supportingText={Text("${notes.length}/12000 · Sent to MindDeck's AI service when you create.")},modifier=Modifier.fillMaxWidth().heightIn(min=220.dp),minLines=7,shape=RoundedCornerShape(18.dp))}
        else item {SelectField("Chapter",chapter,chapters) {chapter=it}}
        item {Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(20.dp)) {Column(Modifier.padding(20.dp)) {Icon(Icons.Rounded.AutoAwesome,null,tint=Lavender);Text("15 clear revision cards",fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=10.dp));Text("Questions and answers focused on your selected topic. New cards are added without replacing your existing decks.",color=Muted,fontSize=14.sp)}}}
        item {
            if(state.user==null) ActionButton("Continue with Google",onSignIn,enabled=!state.busy)
            else ActionButton(if(state.busy) "Creating your cards…" else "Create revision cards",{vm.generate(subject,if(useNotes) "" else chapter,if(useNotes) notes else "",onComplete)},enabled=!state.busy && (if(useNotes) notes.trim().length>=30 else chapter.isNotBlank()))
        }
        if(state.busy) item {LinearProgressIndicator(Modifier.fillMaxWidth());Text("This can take up to a minute. You can go back without losing saved cards.",color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=12.dp))}
        item {Text("AI may make mistakes. Verify formulas and exam facts with your textbook. No AI keys are stored in this APK.",color=Muted,fontSize=12.sp)}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SelectField(label: String,value: String,options: List<String>,onChange: (String)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=it}) {
        OutlinedTextField(value=value,onValueChange={},readOnly=true,label={Text(label)},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)},modifier=Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),shape=RoundedCornerShape(16.dp))
        ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) { options.forEach { option -> DropdownMenuItem(text={Text(option)},onClick={onChange(option);expanded=false}) } }
    }
}
@Composable private fun ProfileSetup(profile: Profile,onSave: (String,String,String)->Unit) {
    var name by rememberSaveable {mutableStateOf(if(profile.name=="Student") "" else profile.name)}
    var cls by rememberSaveable {mutableStateOf(profile.classLevel)}
    var stream by rememberSaveable {mutableStateOf(profile.stream)}
    Surface(Modifier.fillMaxSize(),color=Ink) {LazyColumn(Modifier.safeDrawingPadding().imePadding(),contentPadding=PaddingValues(28.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        item {Spacer(Modifier.height(28.dp));Icon(Icons.Rounded.AutoStories,"MindDeck",tint=Lime,modifier=Modifier.size(58.dp));Text("A calmer way\nto study.",fontSize=36.sp,lineHeight=42.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=26.dp));Text("Make MindDeck yours. Choose your class and stream so you only see relevant subjects.",fontSize=16.sp,color=Muted,modifier=Modifier.padding(top=14.dp))}
        item {OutlinedTextField(value=name,onValueChange={name=it.take(60)},label={Text("What should we call you?")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp))}
        item {SelectField("Class",cls,listOf("Class 11","Class 12")){cls=it}}
        item {SelectField("Stream",stream,listOf("PCB","PCM","Commerce")){stream=it}}
        item {ActionButton("Let's begin  →",{onSave(name,cls,stream)})}
        item {Text("Your profile stays on this device. Google sign-in is available inside for online AI creation.",color=Muted,fontSize=13.sp)}
    }}
}

@Composable private fun StudyScreen(deck: String,state: StudyUiState,vm: StudyViewModel,onBack: () -> Unit) {
    var reviewedIds by rememberSaveable(deck) {mutableStateOf(listOf<String>())}
    var reveal by rememberSaveable {mutableStateOf(false)}
    val cards=state.cards.filter {it.deck==deck}
    val card=cards.firstOrNull {it.id !in reviewedIds}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        item {PageHeader("Study session",deck,onBack)}
        item {LinearProgressIndicator(progress={if(cards.isEmpty()) 1f else reviewedIds.size.toFloat()/cards.size},modifier=Modifier.fillMaxWidth(),color=Lime);Text("${reviewedIds.size} of ${cards.size} reviewed",color=Muted,modifier=Modifier.padding(top=10.dp))}
        if(card==null) item {Column(Modifier.fillMaxWidth().padding(vertical=50.dp),horizontalAlignment=Alignment.CenterHorizontally) {Icon(Icons.Rounded.CheckCircle,null,tint=Lime,modifier=Modifier.size(64.dp));Text("Session complete",fontSize=28.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=18.dp));Text("Your review progress is saved.",color=Muted);Spacer(Modifier.height(28.dp));ActionButton("Back to library",onBack)}}
        else {
            item {Card(shape=RoundedCornerShape(28.dp),colors=CardDefaults.cardColors(containerColor=Panel),modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(26.dp)) {Pill(if(reveal) "ANSWER" else "QUESTION",if(reveal) Lime else Lavender);Text(if(reveal) card.back else card.front,fontSize=24.sp,lineHeight=34.sp,fontWeight=if(reveal) FontWeight.Normal else FontWeight.SemiBold,modifier=Modifier.padding(top=28.dp,bottom=28.dp));if(!reveal) Text("Think it through before revealing.",color=Muted,fontSize=13.sp)}}}
            item {if(!reveal) ActionButton("Reveal answer",{reveal=true}) else Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {ActionButton("Got it  ✓",{vm.review(card,true){reviewedIds=reviewedIds+card.id;reveal=false}},enabled=!state.busy);OutlinedButton(onClick={vm.review(card,false){reviewedIds=reviewedIds+card.id;reveal=false}},enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("Review again in 10 minutes")}}}
        }
    }
}

@Composable private fun FocusScreen(state: StudyUiState,vm: StudyViewModel) {
    val timer=state.timer
    var reset by remember {mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(26.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        item {PageHeader("Find your focus","One task is enough for now.")}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf(10,25,50).forEach {minutes -> FilterChip(selected=timer.duration==minutes*60,onClick={if(timer.running||timer.paused) reset=true else vm.resetTimer(minutes)},label={Text("$minutes min")},enabled=!timer.running&&!timer.paused)}}}
        item {Box(Modifier.widthIn(max=280.dp).fillMaxWidth().aspectRatio(1f).padding(20.dp),contentAlignment=Alignment.Center) {
            CircularProgressIndicator(progress={(timer.duration-timer.remaining).toFloat()/timer.duration},modifier=Modifier.fillMaxSize(),color=Lime,trackColor=Panel,strokeWidth=8.dp)
            Column(horizontalAlignment=Alignment.CenterHorizontally) {Text("%02d:%02d".format(timer.remaining/60,timer.remaining%60),fontSize=54.sp,fontWeight=FontWeight.Light);Text(when {timer.running->"FOCUSING";timer.paused->"PAUSED";timer.remaining==0->"COMPLETE";else->"READY WHEN YOU ARE"},fontSize=12.sp,letterSpacing=2.sp,color=Muted,modifier=Modifier.padding(top=10.dp))}
        }}
        item {ActionButton(when {timer.running->"Pause session";timer.paused->"Resume session";timer.remaining==0->"Start another session";else->"Start focus"},{vm.timerToggle()})}
        item {OutlinedButton(onClick={reset=true},modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)){Text("Reset timer")}}
        item {Text("The deadline is saved when you leave the app. Return to see the correct remaining time. This preview does not send background completion notifications.",color=Muted,fontSize=14.sp,textAlign=TextAlign.Center)}
        item {Metric("${state.focusSeconds/60} minutes","Total completed focus",Modifier.fillMaxWidth())}
    }
    if(reset) AlertDialog(onDismissRequest={reset=false},title={Text("Reset this session?")},text={Text("Unfinished time will not count as a completed focus session.")},confirmButton={TextButton(onClick={vm.resetTimer();reset=false}){Text("Reset")}},dismissButton={TextButton(onClick={reset=false}){Text("Keep going")}})
}
@Composable private fun AccountScreen(state: StudyUiState,signingIn: Boolean,onSignIn: () -> Unit,onSignOut: () -> Unit,onEdit: () -> Unit,onRetry: () -> Unit) {
    var logout by remember {mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        item {PageHeader("Your space","Built around the way you learn.")}
        item {Row(verticalAlignment=Alignment.CenterVertically) {Box(Modifier.size(64.dp).background(Lavender,CircleShape),contentAlignment=Alignment.Center){Text((state.user?.name ?: state.profile.name).take(1).uppercase(),color=Ink,fontSize=26.sp,fontWeight=FontWeight.Bold)};Column(Modifier.padding(start=16.dp)){Text(state.user?.name ?: state.profile.name,fontSize=23.sp,fontWeight=FontWeight.Bold);Text("${state.profile.classLevel} · ${state.profile.stream}",color=Muted)}}}
        item {OutlinedButton(onClick=onEdit,modifier=Modifier.fillMaxWidth()){Text("Edit study profile")}}
        item {Card(shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=Panel)) {Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Icon(Icons.Rounded.VerifiedUser,null,tint=Lime);Text(if(state.user!=null) "Google account connected" else "Secure Google sign-in",fontSize=21.sp,fontWeight=FontWeight.Bold);Text(if(state.user!=null) "Your login tokens are encrypted using Android Keystore. Your email is not displayed on the dashboard." else "Use Google's account chooser to enable online AI. MindDeck never asks for your Google password.",color=Muted,fontSize=14.sp);if(state.user==null) ActionButton(if(signingIn||state.busy) "Signing in…" else "Continue with Google",onSignIn,enabled=!signingIn&&!state.busy) else OutlinedButton(onClick={logout=true},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Sign out")}}}}
        item {Section("Connection status")}
        item {Text(if(state.serverOnline) "Native backend reachable. AI generation is verified only when a request succeeds." else "Native backend not connected. Local study remains available.",color=Muted);TextButton(onClick=onRetry,enabled=!state.configLoading){Text(if(state.configLoading) "Checking…" else "Check connection")}}
        if(state.serverClientId.isBlank()) item {Text("Google login setup is pending for this build. The owner must configure the Google client ID and register this APK's signing certificate.",color=Lavender,fontSize=14.sp)}
        item {HorizontalDivider(color=Panel);Text("MindDeck Native · ${BuildConfig.VERSION_NAME}\nA real Android app. No WebView. No browser shell.\nCards are saved on this device; cloud deck sync is not included in this preview.",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=20.dp))}
    }
    if(logout) AlertDialog(onDismissRequest={logout=false},title={Text("Sign out?")},text={Text("Your account's saved cards will be hidden until you sign in again. This does not delete your account.")},confirmButton={TextButton(onClick={onSignOut();logout=false}){Text("Sign out")}},dismissButton={TextButton(onClick={logout=false}){Text("Cancel")}})
}
@Composable private fun ManualCardDialog(profile: Profile,vm: StudyViewModel,onDismiss: () -> Unit) {
    var front by rememberSaveable {mutableStateOf("")};var back by rememberSaveable {mutableStateOf("")};var title by rememberSaveable {mutableStateOf("")}
    var subject by rememberSaveable {mutableStateOf(subjectsFor(profile.stream).first())}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Add a revision card")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){SelectField("Subject",subject,subjectsFor(profile.stream)){subject=it};OutlinedTextField(title,{title=it.take(120)},label={Text("Deck title")});OutlinedTextField(front,{front=it.take(1000)},label={Text("Question")},maxLines=4);OutlinedTextField(back,{back=it.take(3000)},label={Text("Answer")},maxLines=5)}},confirmButton={TextButton(enabled=front.isNotBlank()&&back.isNotBlank(),onClick={vm.addCard(title,subject,front,back);onDismiss()}){Text("Save card")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
