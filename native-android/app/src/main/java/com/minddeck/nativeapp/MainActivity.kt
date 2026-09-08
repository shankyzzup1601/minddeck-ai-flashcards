@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.minddeck.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
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

private val Ink=Color(0xFF171B2E)
private val Paper=Color(0xFFF5F3FA)
private val Panel=Color.White
private val PanelElevated=Color(0xFFEFECF6)
private val Navy=Color(0xFF111D32)
private val Lime=Color(0xFF7349CC)
private val Lavender=Color(0xFF7752B5)
private val Peach=Color(0xFFF6A24B)
private val Muted=Color(0xFF69667A)
private val Hairline=Color(0xFFE3DEEF)
private val Aurora=Brush.linearGradient(listOf(Color(0xFF7349CC),Color(0xFFA34391),Color(0xFFB35C22)))
private val MindDeckColors=lightColorScheme(primary=Lime,onPrimary=Color.White,secondary=Lavender,onSecondary=Color.White,background=Paper,surface=Panel,onSurface=Ink,onBackground=Ink,surfaceVariant=PanelElevated,onSurfaceVariant=Muted,outline=Hairline)

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.light(0xFFF5F3FA.toInt(),0xFFF5F3FA.toInt()),navigationBarStyle=SystemBarStyle.light(0xFFF5F3FA.toInt(),0xFFF5F3FA.toInt()))
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
        val message=state.error ?: state.info?.substringBefore(". AI can make mistakes")
        if(message!=null) {
            snackbar.showSnackbar(message,withDismissAction=true,duration=if(state.error!=null) SnackbarDuration.Long else SnackbarDuration.Short)
            vm.clearMessage()
        }
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
        Surface(Modifier.fillMaxSize(),color=Paper) { Box(contentAlignment=Alignment.Center) { Column(horizontalAlignment=Alignment.CenterHorizontally) { Icon(Icons.Rounded.AutoStories,"MindDeck",tint=Lime,modifier=Modifier.size(60.dp)); Spacer(Modifier.height(20.dp)); CircularProgressIndicator() } } }
        return
    }
    if(!state.profile.complete || profileEdit) {
        ProfileSetup(state.profile) { name,cls,stream -> vm.saveProfile(name,cls,stream); profileEdit=false }
        return
    }
    Scaffold(
        containerColor=Paper,
        snackbarHost={
            SnackbarHost(snackbar,modifier=Modifier.padding(horizontal=16.dp,vertical=8.dp)) { data ->
                Snackbar(
                    modifier=Modifier.fillMaxWidth(),
                    shape=RoundedCornerShape(20.dp),
                    containerColor=Navy,
                    contentColor=Color(0xFFF7F8FA),
                    dismissAction={IconButton(onClick={data.dismiss()}) {Icon(Icons.Rounded.Close,"Dismiss message",tint=Muted)}}
                ) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Icon(if(state.error!=null) Icons.Rounded.Info else Icons.Rounded.CheckCircle,null,tint=if(state.error!=null) Lavender else Lime,modifier=Modifier.size(20.dp))
                        Text(data.visuals.message,fontSize=14.sp,lineHeight=20.sp)
                    }
                }
            }
        },
        bottomBar={ if(!composer && studyDeck==null) Surface(modifier=Modifier.padding(horizontal=16.dp,vertical=8.dp),color=Panel,shape=RoundedCornerShape(28.dp),shadowElevation=8.dp,border=BorderStroke(1.dp,Hairline)) {
            NavigationBar(containerColor=Color.Transparent,tonalElevation=0.dp,windowInsets=WindowInsets.navigationBars) {
                listOf("Home" to Icons.Rounded.Home,"Library" to Icons.Rounded.AutoStories,"Focus" to Icons.Rounded.Timer,"You" to Icons.Rounded.Person).forEachIndexed { i,(label,icon) ->
                    NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(icon,label)},label={Text(label,fontSize=12.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,textAlign=TextAlign.Center,modifier=Modifier.widthIn(max=72.dp))},colors=NavigationBarItemDefaults.colors(indicatorColor=Lime.copy(alpha=.14f),selectedIconColor=Lime,selectedTextColor=Lime,unselectedIconColor=Muted,unselectedTextColor=Muted))
                }
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
    Row(Modifier.fillMaxWidth().padding(bottom=8.dp),verticalAlignment=Alignment.Top) {
        if(back!=null) IconButton(onClick=back,modifier=Modifier.padding(end=8.dp)) {Icon(Icons.Rounded.ArrowBack,"Go back")}
        Column(Modifier.weight(1f).padding(top=if(back!=null) 6.dp else 0.dp)) {
            Text(title,fontSize=28.sp,lineHeight=34.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-.8).sp)
            subtitle?.let {Text(it,color=Muted,fontSize=15.sp,lineHeight=22.sp,modifier=Modifier.padding(top=5.dp))}
        }
        action?.invoke()
    }
}
@Composable private fun Pill(text: String,color: Color=Lime) { Surface(color=color.copy(alpha=.10f),shape=RoundedCornerShape(50),border=BorderStroke(1.dp,color.copy(alpha=.24f))) { Text(text,color=color,fontSize=12.sp,lineHeight=16.sp,letterSpacing=.4.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(horizontal=13.dp,vertical=7.dp)) } }
@Composable private fun ActionButton(text: String,onClick: () -> Unit,modifier: Modifier=Modifier,enabled: Boolean=true) {
    Button(onClick=onClick,enabled=enabled,modifier=modifier.fillMaxWidth().heightIn(min=56.dp).clip(RoundedCornerShape(18.dp)).background(if(enabled) Aurora else Brush.linearGradient(listOf(Hairline,Hairline))),shape=RoundedCornerShape(18.dp),colors=ButtonDefaults.buttonColors(containerColor=Color.Transparent,contentColor=Color.White,disabledContainerColor=Color.Transparent,disabledContentColor=Muted),contentPadding=PaddingValues(horizontal=20.dp,vertical=15.dp)) {Text(text,fontSize=15.sp,lineHeight=21.sp,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center)}
}
@Composable private fun Section(title: String,subtitle: String?=null) {
    Column {Text(title,fontSize=20.sp,fontWeight=FontWeight.Bold); subtitle?.let {Text(it,color=Muted,fontSize=14.sp,modifier=Modifier.padding(top=4.dp))}}
}
@Composable private fun Metric(value: String,label: String,modifier: Modifier=Modifier) {
    Surface(modifier=modifier,shape=RoundedCornerShape(22.dp),color=PanelElevated,border=BorderStroke(1.dp,Hairline)) {Column(Modifier.padding(18.dp)) {Text(value,fontSize=28.sp,fontWeight=FontWeight.Bold,color=Lime);Text(label,color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=3.dp))}}
}
@Composable private fun HomeScreen(state: StudyUiState,onCreate: () -> Unit,onSubject: (String) -> Unit,onStudy: () -> Unit,onFocus: () -> Unit,onAccount: () -> Unit) {
    val due=state.cards.count {it.due <= System.currentTimeMillis()}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=20.dp,top=20.dp,end=20.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {Text("M I N D D E C K",color=Ink,fontSize=17.sp,fontWeight=FontWeight.SemiBold);Text("FOCUS · LEARN · EVOLVE",color=Muted,fontSize=9.sp,letterSpacing=1.5.sp,modifier=Modifier.padding(top=4.dp))}
            FilledTonalIconButton(onClick=onAccount,modifier=Modifier.size(46.dp),colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=Color.White,contentColor=Lime)){Icon(Icons.Rounded.Person,"Your account")}
        }}
        item {Text("Your space, ${state.user?.name?.substringBefore(' ') ?: state.profile.name.substringBefore(' ')}.",fontSize=15.sp,color=Muted)}
        item {Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))) {
            Image(painterResource(R.drawable.focus_city),null,Modifier.matchParentSize(),contentScale=ContentScale.Crop)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha=.46f),Color.Transparent,Navy.copy(alpha=.94f)))))
            Column(Modifier.padding(22.dp)) {
                Text("“",fontSize=48.sp,lineHeight=48.sp,color=Lime,fontWeight=FontWeight.Bold)
                Text("FOCUS CREATES\nCLARITY.",fontSize=30.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold,color=Ink,letterSpacing=(-.8).sp)
                Text("CLARITY CREATES",fontSize=22.sp,lineHeight=30.sp,fontWeight=FontWeight.SemiBold,color=Ink,modifier=Modifier.padding(top=4.dp))
                Text("PROGRESS.",fontSize=30.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold,color=Color(0xFFA84F21))
                Text("ONE CHAPTER. ONE SMALL WIN.",fontSize=10.sp,letterSpacing=1.sp,color=Ink,modifier=Modifier.padding(top=14.dp))
                Spacer(Modifier.height(105.dp))
                Surface(onClick=if(due>0) onStudy else onCreate,color=Navy.copy(alpha=.93f),shape=RoundedCornerShape(50)) {Row(Modifier.fillMaxWidth().padding(start=18.dp,end=8.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {Text(if(due>0) "$due cards ready to review" else "What's your priority today?",fontSize=13.sp,color=Color.White,modifier=Modifier.weight(1f));Box(Modifier.size(38.dp).background(Aurora,CircleShape),contentAlignment=Alignment.Center){Icon(Icons.Rounded.ArrowForward,null,tint=Color.White,modifier=Modifier.size(20.dp))}}}
                Spacer(Modifier.height(12.dp))
                Surface(onClick=onFocus,color=Navy,shape=RoundedCornerShape(18.dp),border=BorderStroke(1.dp,Color.White.copy(alpha=.12f))) {Column(Modifier.fillMaxWidth().padding(16.dp)) {Text("FOCUS SESSION",color=Color.White.copy(alpha=.8f),fontSize=10.sp,letterSpacing=1.sp);Row(verticalAlignment=Alignment.CenterVertically) {Text("%02d:%02d".format(state.timer.remaining/60,state.timer.remaining%60),fontSize=31.sp,fontWeight=FontWeight.Light,color=Color.White,modifier=Modifier.weight(1f));Box(Modifier.size(44.dp).background(Aurora,CircleShape),contentAlignment=Alignment.Center){Icon(Icons.Rounded.PlayArrow,null,tint=Color.White)}};Text("Make time for deeper learning",fontSize=11.sp,color=Color(0xFFBFC5D7));Spacer(Modifier.height(12.dp));Box(Modifier.fillMaxWidth().height(3.dp).background(Aurora,RoundedCornerShape(3.dp)))}}
            }
        }}
        item {Section("Study overview","Your progress, at your pace")}
        item {Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {Metric("${state.cards.size}","Saved cards",Modifier.weight(1f));Metric("${state.focusSeconds/60}","Focus minutes",Modifier.weight(1f))}}
        item {Surface(color=Navy,shape=RoundedCornerShape(22.dp)) {Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically) {
            val ready=state.cards.size-due
            Box(Modifier.size(88.dp),contentAlignment=Alignment.Center) {CircularProgressIndicator(progress={if(state.cards.isEmpty()) 0f else ready.toFloat()/state.cards.size},modifier=Modifier.fillMaxSize(),color=Color(0xFFB287EC),trackColor=Color(0xFF34415A),strokeWidth=7.dp);Column(horizontalAlignment=Alignment.CenterHorizontally){Text("$ready",color=Color.White,fontSize=26.sp);Text("NOT DUE",color=Color(0xFFBDC3D5),fontSize=8.sp)}}
            Column(Modifier.weight(1f).padding(start=20.dp)) {Text("REVIEW BALANCE",color=Color.White,fontSize=11.sp,letterSpacing=1.sp);Text("$due due now",color=Color(0xFFFFB570),fontSize=20.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=10.dp));Text("${state.cards.size} cards in your library",color=Color(0xFFBDC3D5),fontSize=12.sp,modifier=Modifier.padding(top=4.dp))}
        }}}
        item {Section("Your subjects","${state.profile.classLevel} · ${state.profile.stream}")}
        items(subjectsFor(state.profile.stream).chunked(2)) { pair -> Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {pair.forEach {subject ->
            Surface(onClick={onSubject(subject)},modifier=Modifier.weight(1f),color=Color.White,shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,Hairline)) {Column(Modifier.padding(16.dp)) {Box(Modifier.size(42.dp).background(subjectAccent(subject).copy(alpha=.12f),RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){Icon(subjectIcon(subject),null,tint=subjectAccent(subject))};Text(subject,fontSize=15.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=16.dp));Text("${state.cards.count {it.subject==subject}} saved cards",color=Muted,fontSize=11.sp,modifier=Modifier.padding(top=4.dp));Icon(Icons.Rounded.ArrowForward,"Create $subject cards",tint=subjectAccent(subject),modifier=Modifier.align(Alignment.End).padding(top=10.dp).size(18.dp))}}
        };if(pair.size==1) Spacer(Modifier.weight(1f))}}
        item {Surface(color=Navy,shape=RoundedCornerShape(22.dp)) {Row(Modifier.fillMaxWidth().padding(22.dp),verticalAlignment=Alignment.CenterVertically) {Text("“",fontSize=46.sp,color=Color(0xFFBC91F1));Column(Modifier.weight(1f).padding(start=12.dp)){Text("Small progress is still progress.",color=Color.White,fontSize=17.sp,lineHeight=24.sp);Text("YOUR DAILY REMINDER",fontSize=9.sp,color=Color(0xFFBEC4D8),letterSpacing=1.sp,modifier=Modifier.padding(top=12.dp))}}}}
    }
}
private fun subjectAccent(subject: String): Color = when(subject) {
    "Physics" -> Color(0xFF3978BC)
    "Chemistry" -> Color(0xFF8556BD)
    "Biology" -> Color(0xFF25866B)
    "Mathematics" -> Color(0xFFB67327)
    else -> Lavender
}
private fun subjectIcon(subject: String): ImageVector = when(subject) {"Physics" -> Icons.Rounded.Bolt;"Chemistry" -> Icons.Rounded.Science;"Biology" -> Icons.Rounded.Eco;"Mathematics" -> Icons.Rounded.Calculate;else -> Icons.Rounded.MenuBook}

@Composable private fun LibraryScreen(state: StudyUiState,vm: StudyViewModel,onCreate: () -> Unit,onManual: () -> Unit,onStudy: (String)->Unit) {
    var search by rememberSaveable {mutableStateOf("")}
    var delete by rememberSaveable {mutableStateOf<String?>(null)}
    val decks=state.cards.groupBy {it.deck}.map {(title,cards)->DeckSummary(title,cards.first().subject,cards.size,cards.count {it.due<=System.currentTimeMillis()})}.filter {it.title.contains(search,true)||it.subject.contains(search,true)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=22.dp,top=22.dp,end=22.dp,bottom=40.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {PageHeader("Your library","Small decks. Lasting knowledge.")}
        item {Surface(color=Navy,shape=RoundedCornerShape(24.dp)) {Column(Modifier.padding(20.dp)) {Text("BUILD YOUR KNOWLEDGE",fontSize=10.sp,letterSpacing=1.5.sp,color=Color(0xFFCFBAF2));Text("A little today.\nA lot remembered.",fontSize=25.sp,lineHeight=31.sp,color=Color.White,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(vertical=14.dp));ActionButton("✨  Create with AI",onCreate)}}}
        item {OutlinedTextField(value=search,onValueChange={search=it},label={Text("Search your decks")},leadingIcon={Icon(Icons.Rounded.Search,null)},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(16.dp))}
        if(decks.isEmpty()) item {Column(Modifier.fillMaxWidth().padding(vertical=36.dp),horizontalAlignment=Alignment.CenterHorizontally) {Icon(Icons.Rounded.AutoStories,null,tint=Lavender,modifier=Modifier.size(60.dp));Spacer(Modifier.height(16.dp));Text(if(search.isBlank()) "Your first deck starts here" else "No matching decks",fontSize=20.sp,fontWeight=FontWeight.Bold);Text("Create from a chapter, or add a card yourself.",color=Muted,textAlign=TextAlign.Center,modifier=Modifier.padding(vertical=10.dp));TextButton(onClick=onManual){Text("＋ Add a card manually")}}}
        items(decks,key={it.title}) { deck -> Card(shape=RoundedCornerShape(26.dp),colors=CardDefaults.cardColors(containerColor=Color.White),border=BorderStroke(1.dp,Hairline)) {Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {Box(Modifier.size(40.dp).background(subjectAccent(deck.subject).copy(alpha=.12f),RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center) {Icon(subjectIcon(deck.subject),null,tint=subjectAccent(deck.subject),modifier=Modifier.size(22.dp))};Spacer(Modifier.width(10.dp));Pill(deck.subject,subjectAccent(deck.subject));Spacer(Modifier.weight(1f));IconButton(onClick={delete=deck.title},enabled=!state.busy) {Icon(Icons.Rounded.DeleteOutline,"Delete ${deck.title}",tint=Muted)}}
            Text(deck.title,fontSize=23.sp,lineHeight=29.sp,letterSpacing=(-.3).sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=18.dp,bottom=12.dp));FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {Pill("${deck.count} cards",Muted);Pill(if(deck.due>0) "${deck.due} due now" else "All caught up",if(deck.due>0) Lime else subjectAccent(deck.subject))}
            Spacer(Modifier.height(16.dp));Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {Text("Ready for a fresh look?",color=Muted,fontSize=12.sp,modifier=Modifier.weight(1f));FilledTonalButton(onClick={onStudy(deck.title)},colors=ButtonDefaults.filledTonalButtonColors(containerColor=PanelElevated,contentColor=Lime)){Text("Study deck  →",fontSize=13.sp)}}
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
    LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(start=22.dp,top=22.dp,end=22.dp,bottom=48.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {PageHeader("Create a deck","AI does the drafting. You do the learning.",onBack)}
        item {FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {FilterChip(selected=!useNotes,onClick={useNotes=false},label={Text("Ready syllabus")});FilterChip(selected=useNotes,onClick={useNotes=true},label={Text("My notes")})}}
        item {Surface(color=Navy,shape=RoundedCornerShape(22.dp)) {Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(44.dp).background(Aurora,RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){Icon(Icons.Rounded.AutoAwesome,null,tint=Color.White)};Column(Modifier.weight(1f).padding(start=14.dp)){Text("YOUR NEXT DISCOVERY",color=Color(0xFFD4BFF6),fontSize=10.sp,letterSpacing=1.sp);Text("${state.profile.classLevel} · ${state.profile.stream}",color=Color.White,fontSize=17.sp,modifier=Modifier.padding(top=5.dp))}}}}
        item {SelectField("Subject",subject,subjects) {subject=it}}
        if(useNotes) item {OutlinedTextField(value=notes,onValueChange={notes=it.take(12000)},label={Text("Paste your study notes")},supportingText={Text("${notes.length}/12000 · Sent to MindDeck's AI service when you create.")},modifier=Modifier.fillMaxWidth().heightIn(min=220.dp),minLines=7,shape=RoundedCornerShape(18.dp))}
        else item {SelectField("Chapter",chapter,chapters) {chapter=it}}
        item {Card(colors=CardDefaults.cardColors(containerColor=PanelElevated),shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,Hairline)) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {Box(Modifier.size(44.dp).background(Lavender.copy(alpha=.12f),RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){Icon(Icons.Rounded.AutoAwesome,null,tint=Lavender)};Text("15 clear revision cards",fontSize=19.sp,lineHeight=25.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))};Text("Focused questions. Clear answers. Ready for your next review.",color=Muted,fontSize=14.sp,lineHeight=21.sp);HorizontalDivider(color=Hairline);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Icon(Icons.Rounded.CheckCircle,null,tint=Lime,modifier=Modifier.size(16.dp));Text("Your existing decks stay safe.",color=Muted,fontSize=12.sp,lineHeight=18.sp)}}}}
        item {
            if(state.user==null) ActionButton("Continue with Google",onSignIn,enabled=!state.busy)
            else ActionButton(if(state.busy) "Creating your cards…" else "Create revision cards",{vm.generate(subject,if(useNotes) "" else chapter,if(useNotes) notes else "",onComplete)},enabled=!state.busy && (if(useNotes) notes.trim().length>=30 else chapter.isNotBlank()))
        }
        if(state.busy) item {LinearProgressIndicator(Modifier.fillMaxWidth());Text("This can take up to a minute. You can go back without losing saved cards.",color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=12.dp))}
        item {Text("AI may make mistakes. Verify formulas and exam facts with your textbook. ",color=Muted,fontSize=12.sp)}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SelectField(label: String,value: String,options: List<String>,onChange: (String)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=it}) {
        OutlinedTextField(value=value,onValueChange={},readOnly=true,label={Text(label)},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded)},modifier=Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth().heightIn(min=56.dp),shape=RoundedCornerShape(16.dp))
        ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) { options.forEach { option -> DropdownMenuItem(text={Text(option)},onClick={onChange(option);expanded=false}) } }
    }
}
@Composable private fun ProfileSetup(profile: Profile,onSave: (String,String,String)->Unit) {
    var name by rememberSaveable {mutableStateOf(if(profile.name=="Student") "" else profile.name)}
    var cls by rememberSaveable {mutableStateOf(profile.classLevel)}
    var stream by rememberSaveable {mutableStateOf(profile.stream)}
    Surface(Modifier.fillMaxSize(),color=Paper) {LazyColumn(Modifier.safeDrawingPadding().imePadding(),contentPadding=PaddingValues(28.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
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
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=22.dp,top=22.dp,end=22.dp,bottom=48.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        item {PageHeader("Study session",deck,onBack)}
        item {LinearProgressIndicator(progress={if(cards.isEmpty()) 1f else reviewedIds.size.toFloat()/cards.size},modifier=Modifier.fillMaxWidth(),color=Lime);Text("${reviewedIds.size} of ${cards.size} reviewed",color=Muted,modifier=Modifier.padding(top=10.dp))}
        if(card==null) item {Column(Modifier.fillMaxWidth().padding(vertical=50.dp),horizontalAlignment=Alignment.CenterHorizontally) {Icon(Icons.Rounded.CheckCircle,null,tint=Lime,modifier=Modifier.size(64.dp));Text("Session complete",fontSize=28.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(vertical=18.dp));Text("Your review progress is saved.",color=Muted);Spacer(Modifier.height(28.dp));ActionButton("Back to library",onBack)}}
        else {
            item {Card(shape=RoundedCornerShape(30.dp),colors=CardDefaults.cardColors(containerColor=PanelElevated),border=BorderStroke(1.dp,Hairline),modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(26.dp)) {Pill(if(reveal) "ANSWER" else "QUESTION",if(reveal) Lime else Lavender);Text(if(reveal) card.back else card.front,fontSize=24.sp,lineHeight=34.sp,fontWeight=if(reveal) FontWeight.Normal else FontWeight.SemiBold,modifier=Modifier.padding(top=28.dp,bottom=28.dp));if(!reveal) Text("Think it through before revealing.",color=Muted,fontSize=13.sp)}}}
            item {if(!reveal) ActionButton("Reveal answer",{reveal=true}) else Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {ActionButton("Got it  ✓",{vm.review(card,true){reviewedIds=reviewedIds+card.id;reveal=false}},enabled=!state.busy);OutlinedButton(onClick={vm.review(card,false){reviewedIds=reviewedIds+card.id;reveal=false}},enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("Review again in 10 minutes")}}}
        }
    }
}

@Composable private fun FocusScreen(state: StudyUiState,vm: StudyViewModel) {
    val timer=state.timer
    var reset by remember {mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=22.dp,top=22.dp,end=22.dp,bottom=48.dp),verticalArrangement=Arrangement.spacedBy(26.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        item {PageHeader("Find your focus","One task is enough for now.")}
        item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {listOf(10,25,50).forEach {minutes -> FilterChip(selected=timer.duration==minutes*60,onClick={if(timer.running||timer.paused) reset=true else vm.resetTimer(minutes)},label={Text("$minutes min")},enabled=!timer.running&&!timer.paused)}}}
        item {Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Navy,Color(0xFF27324A))),RoundedCornerShape(28.dp)).padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally) {Text("FIND YOUR FLOW",color=Color(0xFFCAC2DF),fontSize=10.sp,letterSpacing=2.sp);Spacer(Modifier.height(20.dp));Box(Modifier.widthIn(max=280.dp).fillMaxWidth().aspectRatio(1f).padding(12.dp),contentAlignment=Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {val radius=size.minDimension*.49f;for(i in 0 until 60){val angle=Math.toRadians(i*6.0);val long=i%5==0;val inner=radius-(if(long) 10.dp.toPx() else 4.dp.toPx());drawLine(Color.White.copy(alpha=if(long) .4f else .16f),Offset(center.x+inner*kotlin.math.sin(angle).toFloat(),center.y-inner*kotlin.math.cos(angle).toFloat()),Offset(center.x+radius*kotlin.math.sin(angle).toFloat(),center.y-radius*kotlin.math.cos(angle).toFloat()),2.dp.toPx(),StrokeCap.Round)}}
            CircularProgressIndicator(progress={(timer.duration-timer.remaining).toFloat()/timer.duration},modifier=Modifier.fillMaxSize().padding(20.dp),color=Color(0xFFB583ED),trackColor=Color(0xFF39435C),strokeWidth=6.dp)
            Column(horizontalAlignment=Alignment.CenterHorizontally) {Text("%02d:%02d".format(timer.remaining/60,timer.remaining%60),fontSize=40.sp,letterSpacing=(-1).sp,fontWeight=FontWeight.Light,color=Color.White);Text(when {timer.running->"FOCUSING";timer.paused->"PAUSED";timer.remaining==0->"COMPLETE";else->"READY WHEN YOU ARE"},fontSize=11.sp,lineHeight=16.sp,textAlign=TextAlign.Center,letterSpacing=1.sp,color=Color(0xFFC8C4D6),modifier=Modifier.padding(horizontal=14.dp).padding(top=10.dp))}
        };Spacer(Modifier.height(14.dp));Box(Modifier.fillMaxWidth().height(3.dp).background(Aurora,RoundedCornerShape(3.dp)))}}
        item {ActionButton(when {timer.running->"Pause session";timer.paused->"Resume session";timer.remaining==0->"Start another session";else->"Start focus"},{vm.timerToggle()})}
        item {OutlinedButton(onClick={reset=true},modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)){Text("Reset timer")}}
        item {Text("Your timer keeps time when you leave. Come back to see your progress. Completion notifications aren’t available yet.",color=Muted,fontSize=14.sp,textAlign=TextAlign.Center)}
        item {Metric("${state.focusSeconds/60} minutes","Total completed focus",Modifier.fillMaxWidth())}
    }
    if(reset) AlertDialog(onDismissRequest={reset=false},title={Text("Reset this session?")},text={Text("Unfinished time will not count as a completed focus session.")},confirmButton={TextButton(onClick={vm.resetTimer();reset=false}){Text("Reset")}},dismissButton={TextButton(onClick={reset=false}){Text("Keep going")}})
}
@Composable private fun AccountScreen(state: StudyUiState,signingIn: Boolean,onSignIn: () -> Unit,onSignOut: () -> Unit,onEdit: () -> Unit,onRetry: () -> Unit) {
    var logout by remember {mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=22.dp,top=22.dp,end=22.dp,bottom=48.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        item {PageHeader("Your space","Built around the way you learn.")}
        item {Row(verticalAlignment=Alignment.CenterVertically) {Box(Modifier.size(64.dp).background(Lavender,CircleShape),contentAlignment=Alignment.Center){Text((state.user?.name ?: state.profile.name).take(1).uppercase(),color=Ink,fontSize=26.sp,fontWeight=FontWeight.Bold)};Column(Modifier.weight(1f).padding(start=16.dp)){Text(state.user?.name ?: state.profile.name,fontSize=23.sp,fontWeight=FontWeight.Bold);Text("${state.profile.classLevel} · ${state.profile.stream}",color=Muted)}}}
        item {OutlinedButton(onClick=onEdit,modifier=Modifier.fillMaxWidth()){Text("Edit study profile")}}
        item {Card(shape=RoundedCornerShape(26.dp),colors=CardDefaults.cardColors(containerColor=PanelElevated),border=BorderStroke(1.dp,Hairline)) {Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Icon(Icons.Rounded.VerifiedUser,null,tint=Lime);Text(if(state.user!=null) "Google account connected" else "Secure Google sign-in",fontSize=21.sp,fontWeight=FontWeight.Bold);Text(if(state.user!=null) "You’re ready to create AI decks. Your saved cards stay on this device." else "Connect your Google account to turn chapters and notes into revision cards.",color=Muted,fontSize=14.sp);if(state.user==null) ActionButton(if(signingIn||state.busy) "Signing in…" else "Continue with Google",onSignIn,enabled=!signingIn&&!state.busy) else OutlinedButton(onClick={logout=true},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Sign out")}}}}
        item {Section("Connection status")}
        item {Text(if(state.serverOnline) "Online service connected." else "You’re offline or the service is unavailable. Saved cards are still available.",color=Muted);TextButton(onClick=onRetry,enabled=!state.configLoading){Text(if(state.configLoading) "Checking…" else "Check connection")}}
        if(state.serverClientId.isBlank()) item {Text("Google sign-in is temporarily unavailable. Check the connection and try again.",color=Lavender,fontSize=14.sp)}
        item {HorizontalDivider(color=Hairline);Text("MindDeck Native · ${BuildConfig.VERSION_NAME}\nMade for calmer study.\nCards are stored on this device. Cloud sync isn’t available yet.",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=20.dp))}
    }
    if(logout) AlertDialog(onDismissRequest={logout=false},title={Text("Sign out?")},text={Text("Your account's saved cards will be hidden until you sign in again. This does not delete your account.")},confirmButton={TextButton(onClick={onSignOut();logout=false}){Text("Sign out")}},dismissButton={TextButton(onClick={logout=false}){Text("Cancel")}})
}
@Composable private fun ManualCardDialog(profile: Profile,vm: StudyViewModel,onDismiss: () -> Unit) {
    var front by rememberSaveable {mutableStateOf("")};var back by rememberSaveable {mutableStateOf("")};var title by rememberSaveable {mutableStateOf("")}
    var subject by rememberSaveable {mutableStateOf(subjectsFor(profile.stream).first())}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Add a revision card")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){SelectField("Subject",subject,subjectsFor(profile.stream)){subject=it};OutlinedTextField(title,{title=it.take(120)},label={Text("Deck title")});OutlinedTextField(front,{front=it.take(1000)},label={Text("Question")},maxLines=4);OutlinedTextField(back,{back=it.take(3000)},label={Text("Answer")},maxLines=5)}},confirmButton={TextButton(enabled=front.isNotBlank()&&back.isNotBlank(),onClick={vm.addCard(title,subject,front,back);onDismiss()}){Text("Save card")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}

