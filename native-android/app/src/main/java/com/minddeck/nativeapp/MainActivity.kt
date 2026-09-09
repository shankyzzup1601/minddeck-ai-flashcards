@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.minddeck.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.platform.LocalContext
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
import com.minddeck.feature.snap.MlKitOcrExtractor
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.security.SecureRandom

internal val Ink=Color(0xFFF3F5FF)
internal val Paper=Color(0xFF0B0E14)
internal val Panel=Color(0xFF111827)
internal val PanelElevated=Color(0xFF182238)
internal val Navy=Color(0xFF0D1528)
internal val Lime=Color(0xFF10B981)
internal val Lavender=Color(0xFF818CF8)
internal val Peach=Color(0xFFFFC27E)
internal val Muted=Color(0xFFA2ADC5)
internal val Hairline=Color(0xFF2A3958)
internal val Nebula=Color(0xFF542A8F)
internal val CosmicPink=Color(0xFFEC4899)
internal val ElectricBlue=Color(0xFF6366F1)
internal val GlassGlow=Color(0xFFDEE5FF)
internal val Aurora=Brush.linearGradient(listOf(Color(0xFF10B981),Color(0xFF6366F1),Color(0xFFEC4899)))
internal val Cosmos=Brush.linearGradient(listOf(Color(0xFF202B52),Color(0xFF111936),Color(0xFF0A1022)))
private val MindDeckColors=darkColorScheme(primary=Lime,onPrimary=Paper,secondary=Lavender,onSecondary=Paper,background=Paper,surface=Panel,onSurface=Ink,onBackground=Ink,surfaceVariant=PanelElevated,onSurfaceVariant=Muted,outline=Hairline)

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.dark(0xFF080D17.toInt()),navigationBarStyle=SystemBarStyle.dark(0xFF080D17.toInt()))
        setContent { MaterialTheme(colorScheme=MindDeckColors) { MindDeckApp(this) } }
    }
}

@Composable
fun MindDeckApp(activity: ComponentActivity, vm: StudyViewModel=viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var started by rememberSaveable {mutableStateOf(false)}
    var offlineSetup by rememberSaveable {mutableStateOf(false)}
    var launchReady by remember {mutableStateOf(false)}
    var premium by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(Unit) {delay(650);launchReady=true}
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
    BackHandler(premium || composer || studyDeck != null || tab != 0) { when { premium -> premium=false; composer -> composer=false; studyDeck != null -> studyDeck=null; else -> tab=0 } }
    fun signIn() {
        if(signingIn) return
        if(state.serverClientId.isBlank()) {
            vm.refreshConfig()
            vm.showError("Google sign-in is still preparing. Check your connection and tap Continue with Google again.")
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
              catch(_: Exception) { vm.showError("Google sign-in could not connect. Check mobile data or Wi-Fi, then tap Continue with Google again.") }
            finally { signingIn=false }
        }
    }
    if(state.loading || !launchReady) {
        Surface(Modifier.fillMaxSize(),color=Paper) {Box(contentAlignment=Alignment.Center) {Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_launcher),"MindDeck app icon",modifier=Modifier.size(104.dp).clip(RoundedCornerShape(26.dp)))
            Text("MINDDECK",fontSize=24.sp,letterSpacing=4.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=22.dp))
            Text("Build knowledge. Find your focus.",color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=8.dp,bottom=26.dp))
            CircularProgressIndicator(modifier=Modifier.size(24.dp),strokeWidth=2.dp)
        }}}
        return
    }
    if(!state.profile.complete && state.user==null && !offlineSetup) {
        WelcomeScreen(started=started,signingIn=signingIn,error=state.error,configLoading=state.configLoading,onStart={started=true},onSignIn={signIn()},onOffline={offlineSetup=true},onBack={started=false})
        return
    }
    if(!state.profile.complete || profileEdit) {
        ProfileSetup(if(!state.profile.complete && state.user!=null) state.profile.copy(name=state.user!!.name) else state.profile) { name,cls,stream -> vm.saveProfile(name,cls,stream); profileEdit=false }
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
        bottomBar={ if(!premium && !composer && studyDeck==null) Surface(modifier=Modifier.navigationBarsPadding().padding(horizontal=12.dp,vertical=6.dp),color=Panel,shape=RoundedCornerShape(22.dp),shadowElevation=8.dp,border=BorderStroke(1.dp,Hairline)) {
            NavigationBar(modifier=Modifier.height(70.dp),containerColor=Color.Transparent,tonalElevation=0.dp,windowInsets=WindowInsets(0,0,0,0)) {
                listOf("Home" to Icons.Rounded.Home,"Library" to Icons.Rounded.AutoStories,"Tests" to Icons.Rounded.Quiz,"Focus" to Icons.Rounded.Timer,"You" to Icons.Rounded.Person).forEachIndexed { i,(label,icon) ->
                    NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(icon,label)},label={Text(label,fontSize=12.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,textAlign=TextAlign.Center,modifier=Modifier.widthIn(max=72.dp))},colors=NavigationBarItemDefaults.colors(indicatorColor=Lime.copy(alpha=.14f),selectedIconColor=Lime,selectedTextColor=Lime,unselectedIconColor=Muted,unselectedTextColor=Muted))
                }
            }
        } }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            when {
                premium -> PremiumScreen {premium=false}
                composer -> Composer(state,vm,composerSubject,onBack={composer=false},onComplete={if(composer){composer=false;tab=1}},onSignIn={signIn()})
                studyDeck!=null -> StudyScreen(studyDeck!!,state,vm) {studyDeck=null}
                tab==0 -> HomeScreen(state,onCreate={composerSubject="";composer=true},onSubject={composerSubject=it;composer=true},onStudy={tab=1},onFocus={tab=3},onAccount={tab=4})
                tab==1 -> LibraryScreen(state,vm,onCreate={composerSubject="";composer=true},onManual={manual=true},onStudy={studyDeck=it})
                tab==2 -> PracticeScreen(state)
                tab==3 -> FocusScreen(state,vm)
                else -> AccountScreen(state,signingIn,onSignIn={signIn()},onSignOut={vm.signOut()},onEdit={profileEdit=true},onRetry={vm.refreshConfig()},onPremium={premium=true})
            }
        }
    }
    if(manual) ManualCardDialog(state.profile,vm) {manual=false}
}

@Composable internal fun PageHeader(title: String,subtitle: String?=null,back: (() -> Unit)?=null,action: (@Composable () -> Unit)?=null) {
    Row(Modifier.fillMaxWidth().padding(bottom=8.dp),verticalAlignment=Alignment.Top) {
        if(back!=null) IconButton(onClick=back,modifier=Modifier.padding(end=8.dp)) {Icon(Icons.Rounded.ArrowBack,"Go back")}
        Column(Modifier.weight(1f).padding(top=if(back!=null) 6.dp else 0.dp)) {
            Text(title,fontSize=24.sp,lineHeight=30.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-.5).sp)
            subtitle?.let {Text(it,color=Muted,fontSize=13.sp,lineHeight=19.sp,modifier=Modifier.padding(top=5.dp))}
        }
        action?.invoke()
    }
}
@Composable internal fun Pill(text: String,color: Color=Lime) { Surface(color=color.copy(alpha=.10f),shape=RoundedCornerShape(50),border=BorderStroke(1.dp,color.copy(alpha=.24f))) { Text(text,color=color,fontSize=12.sp,lineHeight=16.sp,letterSpacing=.4.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(horizontal=13.dp,vertical=7.dp)) } }
@Composable internal fun ActionButton(text: String,onClick: () -> Unit,modifier: Modifier=Modifier,enabled: Boolean=true) {
    val interaction=remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lift by animateFloatAsState(if(pressed) 5f else 0f,spring(stiffness=520f),label="button press")
    Box(modifier.fillMaxWidth().heightIn(min=62.dp)) {
        Box(Modifier.matchParentSize().padding(top=6.dp).clip(RoundedCornerShape(19.dp)).background(Color(0xFF16204A)).border(BorderStroke(1.dp,Lavender.copy(alpha=.28f)),RoundedCornerShape(19.dp)))
        Button(onClick=onClick,enabled=enabled,interactionSource=interaction,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp).graphicsLayer {translationY=lift;shadowElevation=if(pressed) 2f else 14f;shape=RoundedCornerShape(19.dp);clip=true}.background(if(enabled) Aurora else Brush.linearGradient(listOf(Hairline,Hairline))),shape=RoundedCornerShape(19.dp),colors=ButtonDefaults.buttonColors(containerColor=Color.Transparent,contentColor=Color.White,disabledContainerColor=Color.Transparent,disabledContentColor=Muted),contentPadding=PaddingValues(horizontal=20.dp,vertical=15.dp)) {Text(text,fontSize=15.sp,lineHeight=21.sp,fontWeight=FontWeight.Bold,textAlign=TextAlign.Center)}
    }
}
@Composable internal fun Section(title: String,subtitle: String?=null) {
    Column {Text(title,fontSize=20.sp,fontWeight=FontWeight.Bold); subtitle?.let {Text(it,color=Muted,fontSize=14.sp,modifier=Modifier.padding(top=4.dp))}}
}
@Composable private fun Metric(value: String,label: String,modifier: Modifier=Modifier) {
    Surface(modifier=modifier,shape=RoundedCornerShape(22.dp),color=PanelElevated,border=BorderStroke(1.dp,Hairline)) {Column(Modifier.padding(18.dp)) {Text(value,fontSize=24.sp,fontWeight=FontWeight.Bold,color=Lime);Text(label,color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=3.dp))}}
}
@Composable internal fun DepthCard(onClick: ()->Unit, modifier: Modifier=Modifier, content: @Composable ColumnScope.()->Unit) {
    val interaction=remember {MutableInteractionSource()}
    val pressed by interaction.collectIsPressedAsState()
    val tilt by animateFloatAsState(if(pressed) 5.5f else -1.2f,animationSpec=spring(stiffness=380f),label="card tilt")
    val scale by animateFloatAsState(if(pressed) .972f else 1f,animationSpec=spring(stiffness=380f),label="card depth")
    Box(modifier) {
        Box(Modifier.matchParentSize().padding(top=8.dp,start=2.dp,end=2.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF050814)).border(BorderStroke(1.dp,ElectricBlue.copy(alpha=.18f)),RoundedCornerShape(24.dp)))
        Column(Modifier.graphicsLayer {rotationX=tilt;rotationY=if(pressed) -1.5f else 0f;translationY=if(pressed) 5f else 0f;scaleX=scale;scaleY=scale;cameraDistance=18*density;shadowElevation=18f;shape=RoundedCornerShape(24.dp);clip=true}.background(Cosmos).border(BorderStroke(1.dp,GlassGlow.copy(alpha=.13f)),RoundedCornerShape(24.dp)).clickable(interactionSource=interaction,indication=null,onClick=onClick).padding(18.dp),content=content)
    }
}
@Composable internal fun DeckSculpture(modifier: Modifier=Modifier) {
    Box(modifier.size(86.dp),contentAlignment=Alignment.Center) {
        listOf(-18f,0f,18f).forEachIndexed { i,angle ->
            Box(Modifier.size(45.dp,59.dp).graphicsLayer {rotationZ=angle;rotationY=-18f;translationX=(i-1)*12.dp.toPx();translationY=(1-i)*3.dp.toPx();cameraDistance=12*density}.shadow(6.dp,RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(if(i==2) Lime else Lavender,if(i==2) Color(0xFF198F91) else Color(0xFF365487))),RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center) {
                if(i==2) Icon(Icons.Rounded.AutoAwesome,null,tint=Paper,modifier=Modifier.size(23.dp))
            }
        }
    }
}
@Composable private fun HomeScreen(state: StudyUiState,onCreate: () -> Unit,onSubject: (String) -> Unit,onStudy: () -> Unit,onFocus: () -> Unit,onAccount: () -> Unit) {
    val due=state.cards.count {it.due <= System.currentTimeMillis()}
    val decks=state.cards.map {it.deck}.distinct().size
    val firstName=state.profile.name.substringBefore(' ').ifBlank {"Student"}
    val mastery=if(state.cards.isEmpty()) 0 else ((state.cards.count {it.reviews>0}*100f)/state.cards.size).toInt()
    val subjects=subjectsFor(state.profile.stream)
    LazyColumn(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF17122E),Paper,Color(0xFF07151A)))),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Row(verticalAlignment=Alignment.CenterVertically) {
            Surface(onClick=onAccount,color=PanelElevated,shape=RoundedCornerShape(18.dp),border=BorderStroke(1.dp,Lavender.copy(alpha=.7f)),shadowElevation=16.dp) {Box(Modifier.size(52.dp).background(Brush.radialGradient(listOf(CosmicPink,ElectricBlue,Panel))),contentAlignment=Alignment.Center){Text(firstName.take(1).uppercase(),fontSize=20.sp,color=Color.White,fontWeight=FontWeight.Black)}}
            Column(Modifier.weight(1f).padding(horizontal=13.dp)){Text("MINDDECK // ${state.profile.stream}",color=Lime,fontSize=9.sp,letterSpacing=1.8.sp,fontWeight=FontWeight.Black);Text("Welcome back, $firstName",fontSize=21.sp,fontWeight=FontWeight.Bold);Text("${state.profile.classLevel} learning system",color=Muted,fontSize=12.sp)}
            Pill("FREE",Peach)
        }}
        item {Card(onClick=onCreate,colors=CardDefaults.cardColors(containerColor=Color.Transparent),shape=RoundedCornerShape(30.dp),border=BorderStroke(1.dp,Lavender.copy(alpha=.35f)),modifier=Modifier.fillMaxWidth().shadow(24.dp,RoundedCornerShape(30.dp),ambientColor=ElectricBlue.copy(.35f),spotColor=CosmicPink.copy(.28f))) {
            Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF252E60),Color(0xFF191C3D),Color(0xFF113138)))).padding(22.dp)) {Column {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)){Pill("TODAY'S MISSION",Lime);Text(if(decks==0) "Build your first\\nmemory system" else "$due cards ready\\nto strengthen",fontSize=28.sp,lineHeight=33.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(top=17.dp));Text(if(decks==0) "Turn any chapter or photo into an intelligent deck." else "A short review now protects your long-term recall.",color=Muted,fontSize=13.sp,lineHeight=19.sp,modifier=Modifier.padding(top=9.dp))}
                    Box(Modifier.size(104.dp),contentAlignment=Alignment.Center){CircularProgressIndicator(progress={mastery/100f},modifier=Modifier.fillMaxSize(),color=Lime,trackColor=Color.White.copy(.08f),strokeWidth=9.dp);Column(horizontalAlignment=Alignment.CenterHorizontally){Text("$mastery%",fontSize=23.sp,fontWeight=FontWeight.Black);Text("MASTERY",fontSize=7.sp,letterSpacing=1.sp,color=Muted)}}
                }
                Spacer(Modifier.height(20.dp));Box(Modifier.fillMaxWidth().height(54.dp).background(Aurora,RoundedCornerShape(18.dp)),contentAlignment=Alignment.Center){Text("CREATE WITH AI  →",color=Color.White,fontSize=13.sp,fontWeight=FontWeight.Black,letterSpacing=.7.sp)}
            }}
        }}
        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            listOf(Triple("$due","DUE",Lavender),Triple("$decks","DECKS",CosmicPink),Triple("${state.focusSeconds/60}m","FOCUS",Lime)).forEach {(value,label,accent)->Surface(Modifier.weight(1f),color=Panel,shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,accent.copy(alpha=.38f)),shadowElevation=10.dp){Column(Modifier.padding(vertical=15.dp),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(7.dp).background(accent,CircleShape));Text(value,fontSize=20.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(top=7.dp));Text(label,fontSize=8.sp,letterSpacing=1.1.sp,color=Muted)}}}
        }}
        item {Surface(color=Panel,shape=RoundedCornerShape(26.dp),border=BorderStroke(1.dp,Hairline),shadowElevation=12.dp){Column(Modifier.padding(19.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("MASTERY PULSE",fontSize=10.sp,letterSpacing=1.5.sp,color=Lavender,fontWeight=FontWeight.Black);Text("Your last 30 days",fontSize=18.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=4.dp))};Pill(if(due==0) "CLEAR" else "$due DUE",if(due==0)Lime else Peach)}
            MasteryHeatmap(state.cards.size,state.focusSeconds,Modifier.fillMaxWidth().height(92.dp).padding(top=17.dp));Text("Small repetitions compound into durable memory.",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=10.dp))
        }}}
        item {Section("Knowledge worlds","Tap a subject to begin")}
        subjects.chunked(2).forEach {rowSubjects -> item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            rowSubjects.forEach {subject -> val accent=subjectAccent(subject);Card(onClick={onSubject(subject)},modifier=Modifier.weight(1f).height(142.dp),colors=CardDefaults.cardColors(containerColor=PanelElevated),shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,accent.copy(alpha=.42f))){Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent.copy(alpha=.2f),Color.Transparent))).padding(17.dp)){Column{Box(Modifier.size(43.dp).background(accent.copy(.18f),RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){Icon(subjectIcon(subject),null,tint=accent)};Spacer(Modifier.weight(1f));Text(subject,fontSize=17.sp,fontWeight=FontWeight.Bold);Text("${state.cards.count {it.subject==subject}} cards",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=3.dp))}}}}
            if(rowSubjects.size==1) Spacer(Modifier.weight(1f))
        }}}
        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            DepthCard(onClick=onStudy,modifier=Modifier.weight(1f)){Icon(Icons.Rounded.AutoStories,null,tint=Lavender);Text("Library",fontSize=17.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp));Text("Review what matters",fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=4.dp))}
            DepthCard(onClick=onFocus,modifier=Modifier.weight(1f)){Icon(Icons.Rounded.Timer,null,tint=Lime);Text("Deep Focus",fontSize=17.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp));Text("%02d:%02d".format(state.timer.remaining/60,state.timer.remaining%60),fontSize=11.sp,color=Muted,modifier=Modifier.padding(top=4.dp))}
        }}
    }
}

@Composable private fun MasteryHeatmap(cardCount:Int,focusSeconds:Int,modifier:Modifier=Modifier) {
    val seed=(cardCount*13+focusSeconds/60).coerceAtLeast(1)
    Canvas(modifier) {
        val columns=15;val gap=5.dp.toPx();val cell=(size.width-gap*(columns-1))/columns
        repeat(30){index->val level=(index*7+seed)%5;val color=when(level){0->Color(0xFF20283A);1->Color(0xFF143E39);2->Color(0xFF116B55);3->Color(0xFF10B981);else->Color(0xFF6EE7B7)};val x=(index%columns)*(cell+gap);val y=(index/columns)*(cell+gap);drawRoundRect(color,Offset(x,y),androidx.compose.ui.geometry.Size(cell,cell),CornerRadius(4.dp.toPx()))}
    }
}

private fun subjectAccent(subject: String): Color = when(subject) {
    "Physics" -> Color(0xFF8AB8FF)
    "Chemistry" -> Color(0xFFC0A3FF)
    "Biology" -> Color(0xFF66D9B2)
    "Mathematics" -> Color(0xFFF1C58A)
    else -> Lavender
}
private fun subjectIcon(subject: String): ImageVector = when(subject) {
    "Physics" -> Icons.Rounded.Bolt
    "Chemistry" -> Icons.Rounded.Science
    "Biology" -> Icons.Rounded.Eco
    "Mathematics" -> Icons.Rounded.Calculate
    else -> Icons.Rounded.MenuBook
}

@Composable private fun LibraryScreen(state: StudyUiState,vm: StudyViewModel,onCreate: () -> Unit,onManual: () -> Unit,onStudy: (String)->Unit) {
    var search by rememberSaveable {mutableStateOf("")}
    var delete by rememberSaveable {mutableStateOf<String?>(null)}
    val decks=state.cards.groupBy {it.deck}.map {(title,cards)->DeckSummary(title,cards.first().subject,cards.size,cards.count {it.due<=System.currentTimeMillis()})}.filter {it.title.contains(search,true)||it.subject.contains(search,true)}
    LazyColumn(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Paper,Color(0xFF0A1120),Paper))),contentPadding=PaddingValues(start=18.dp,top=18.dp,end=18.dp,bottom=40.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {PageHeader("Your library","Small decks. Lasting knowledge.")}
        item {Surface(color=Navy,shape=RoundedCornerShape(24.dp)) {Column(Modifier.padding(20.dp)) {Text("YOUR KNOWLEDGE HUB",fontSize=10.sp,letterSpacing=1.5.sp,color=Lavender);Text("A little today.\nA lot remembered.",fontSize=21.sp,lineHeight=27.sp,color=Color.White,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(vertical=14.dp));ActionButton("✨  Create with AI",onCreate)}}}
        item {OutlinedTextField(value=search,onValueChange={search=it},label={Text("Search your decks")},leadingIcon={Icon(Icons.Rounded.Search,null)},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(16.dp))}
        if(decks.isEmpty()) item {Column(Modifier.fillMaxWidth().padding(vertical=36.dp),horizontalAlignment=Alignment.CenterHorizontally) {Icon(Icons.Rounded.AutoStories,null,tint=Lavender,modifier=Modifier.size(60.dp));Spacer(Modifier.height(16.dp));Text(if(search.isBlank()) "Your first deck starts here" else "No matching decks",fontSize=20.sp,fontWeight=FontWeight.Bold);Text("Create from a chapter, or add a card yourself.",color=Muted,textAlign=TextAlign.Center,modifier=Modifier.padding(vertical=10.dp));TextButton(onClick=onManual){Text("＋ Add a card manually")}}}
        items(decks,key={it.title}) { deck -> Card(shape=RoundedCornerShape(26.dp),colors=CardDefaults.cardColors(containerColor=Panel),border=BorderStroke(1.dp,Hairline)) {Column(Modifier.padding(20.dp)) {
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
    var scanning by remember {mutableStateOf(false)}
    var scanError by remember {mutableStateOf<String?>(null)}
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val ocr=remember {MlKitOcrExtractor()}
    DisposableEffect(ocr) {onDispose {ocr.close()}}
    val photoPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {uri ->
        if(uri!=null) scope.launch {
            scanning=true
            scanError=null
            try {
                val document=ocr.extract(context,uri)
                notes=document.text.take(12000)
                useNotes=true
            } catch(e:Exception) {
                scanError=e.message ?: "MindDeck could not read this image. Try a brighter, sharper photo."
            } finally {scanning=false}
        }
    }
    val chapters=vm.chapters(subject)
    LaunchedEffect(subject,state.profile.classLevel) {if(chapter !in chapters) chapter=chapters.firstOrNull().orEmpty()}
    LazyColumn(Modifier.fillMaxSize().imePadding(),contentPadding=PaddingValues(start=18.dp,top=18.dp,end=18.dp,bottom=48.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {PageHeader("Create a deck","AI does the drafting. You do the learning.",onBack)}
        item {FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {FilterChip(selected=!useNotes,onClick={useNotes=false},label={Text("Ready syllabus")});FilterChip(selected=useNotes,onClick={useNotes=true},label={Text("My notes")})}}
        item {
            OutlinedButton(
                onClick={photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},
                enabled=!scanning && !state.busy,
                modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),
                shape=RoundedCornerShape(18.dp)
            ) {
                if(scanning) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                else Icon(Icons.Rounded.DocumentScanner,null,modifier=Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(if(scanning) "Reading your page…" else "Snap-to-Deck · Choose a photo")
            }
            scanError?.let {Text(it,color=Color(0xFFFF8FA3),fontSize=13.sp,lineHeight=18.sp,modifier=Modifier.padding(top=8.dp))}
        }
        item {Surface(color=Navy,shape=RoundedCornerShape(22.dp)) {Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(44.dp).background(Aurora,RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){Icon(Icons.Rounded.AutoAwesome,null,tint=Color.White)};Column(Modifier.weight(1f).padding(start=14.dp)){Text("YOUR NEXT DISCOVERY",color=Lavender,fontSize=10.sp,letterSpacing=1.sp);Text("${state.profile.classLevel} · ${state.profile.stream}",color=Color.White,fontSize=17.sp,modifier=Modifier.padding(top=5.dp))}}}}
        item {SelectField("Subject",subject,subjects) {subject=it}}
        if(useNotes) item {OutlinedTextField(value=notes,onValueChange={notes=it.take(12000)},label={Text("Paste your study notes")},supportingText={Text("${notes.length}/12000 · ${if(state.user == null) "Starter cards work offline." else "AI drafting is ready."}")},modifier=Modifier.fillMaxWidth().heightIn(min=220.dp),minLines=7,shape=RoundedCornerShape(18.dp))}
        else item {SelectField("Chapter",chapter,chapters) {chapter=it}}
        item {Card(colors=CardDefaults.cardColors(containerColor=PanelElevated),shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,Hairline)) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {Box(Modifier.size(44.dp).background(Lavender.copy(alpha=.12f),RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){Icon(Icons.Rounded.AutoAwesome,null,tint=Lavender)};Text("15 clear revision cards",fontSize=19.sp,lineHeight=25.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))};Text("Focused questions. Clear answers. Ready for your next review.",color=Muted,fontSize=14.sp,lineHeight=21.sp);HorizontalDivider(color=Hairline);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {Icon(Icons.Rounded.CheckCircle,null,tint=Lime,modifier=Modifier.size(16.dp));Text("Your existing decks stay safe.",color=Muted,fontSize=12.sp,lineHeight=18.sp)}}}}
        item {
            ActionButton(if(state.busy) "Creating your cards…" else "Create revision cards",{vm.generate(subject,if(useNotes) "" else chapter,if(useNotes) notes else "",onComplete)},enabled=!state.busy && (if(useNotes) notes.trim().length>=30 else chapter.isNotBlank()))
        }
        if(state.busy) item {LinearProgressIndicator(Modifier.fillMaxWidth());Text("This can take up to a minute. You can go back without losing saved cards.",color=Muted,fontSize=13.sp,modifier=Modifier.padding(top=12.dp))}
        item {Text("AI may make mistakes. Verify formulas and exam facts with your textbook. ",color=Muted,fontSize=12.sp)}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SelectField(label: String,value: String,options: List<String>,onChange: (String)->Unit) {
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
        item {ActionButton("Let's begin  →",{onSave(name,cls,stream)},enabled=name.trim().isNotEmpty())}
        item {Text("Your profile stays on this device. Google sign-in is available inside for online AI creation.",color=Muted,fontSize=13.sp)}
    }}
}

@Composable private fun StudyScreen(deck: String,state: StudyUiState,vm: StudyViewModel,onBack: () -> Unit) {
    var reviewedIds by rememberSaveable(deck) {mutableStateOf(listOf<String>())}
    var reveal by rememberSaveable {mutableStateOf(false)}
    val cards=state.cards.filter {it.deck==deck}
    val card=cards.firstOrNull {it.id !in reviewedIds}
    LazyColumn(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Paper,Color(0xFF0A1120),Paper))),contentPadding=PaddingValues(start=18.dp,top=18.dp,end=18.dp,bottom=48.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
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
    LazyColumn(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Paper,Color(0xFF0A1120),Paper))),contentPadding=PaddingValues(start=18.dp,top=18.dp,end=18.dp,bottom=48.dp),verticalArrangement=Arrangement.spacedBy(26.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        item {PageHeader("Find your focus","One task is enough for now.")}
        item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {listOf(10,25,50).forEach {minutes -> FilterChip(selected=timer.duration==minutes*60,onClick={if(timer.running||timer.paused) reset=true else vm.resetTimer(minutes)},label={Text("$minutes min")},enabled=!timer.running&&!timer.paused)}}}
        item {Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Navy,Color(0xFF17314A))),RoundedCornerShape(28.dp)).padding(22.dp),horizontalAlignment=Alignment.CenterHorizontally) {Text("FIND YOUR FLOW",color=Color(0xFFCAC2DF),fontSize=10.sp,letterSpacing=2.sp);Spacer(Modifier.height(20.dp));Box(Modifier.widthIn(max=245.dp).fillMaxWidth().aspectRatio(1f).padding(12.dp),contentAlignment=Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {val radius=size.minDimension*.49f;for(i in 0 until 60){val angle=Math.toRadians(i*6.0);val long=i%5==0;val inner=radius-(if(long) 10.dp.toPx() else 4.dp.toPx());drawLine(Color.White.copy(alpha=if(long) .4f else .16f),Offset(center.x+inner*kotlin.math.sin(angle).toFloat(),center.y-inner*kotlin.math.cos(angle).toFloat()),Offset(center.x+radius*kotlin.math.sin(angle).toFloat(),center.y-radius*kotlin.math.cos(angle).toFloat()),2.dp.toPx(),StrokeCap.Round)}}
            CircularProgressIndicator(progress={(timer.duration-timer.remaining).toFloat()/timer.duration},modifier=Modifier.fillMaxSize().padding(20.dp),color=Lime,trackColor=Color(0xFF39435C),strokeWidth=6.dp)
            Column(horizontalAlignment=Alignment.CenterHorizontally) {Text("%02d:%02d".format(timer.remaining/60,timer.remaining%60),fontSize=40.sp,letterSpacing=(-1).sp,fontWeight=FontWeight.Light,color=Color.White);Text(when {timer.running->"FOCUSING";timer.paused->"PAUSED";timer.remaining==0->"COMPLETE";else->"READY WHEN YOU ARE"},fontSize=11.sp,lineHeight=16.sp,textAlign=TextAlign.Center,letterSpacing=1.sp,color=Color(0xFFC8C4D6),modifier=Modifier.padding(horizontal=14.dp).padding(top=10.dp))}
        };Spacer(Modifier.height(14.dp));Box(Modifier.fillMaxWidth().height(3.dp).background(Aurora,RoundedCornerShape(3.dp)))}}
        item {ActionButton(when {timer.running->"Pause session";timer.paused->"Resume session";timer.remaining==0->"Start another session";else->"Start focus"},{vm.timerToggle()})}
        item {OutlinedButton(onClick={reset=true},modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)){Text("Reset timer")}}
        item {Text("Your timer keeps time when you leave. Come back to see your progress. Completion notifications aren’t available yet.",color=Muted,fontSize=14.sp,textAlign=TextAlign.Center)}
        item {Metric("${state.focusSeconds/60} minutes","Total completed focus",Modifier.fillMaxWidth())}
    }
    if(reset) AlertDialog(onDismissRequest={reset=false},title={Text("Reset this session?")},text={Text("Unfinished time will not count as a completed focus session.")},confirmButton={TextButton(onClick={vm.resetTimer();reset=false}){Text("Reset")}},dismissButton={TextButton(onClick={reset=false}){Text("Keep going")}})
}
@Composable private fun AccountScreen(state: StudyUiState,signingIn: Boolean,onSignIn: () -> Unit,onSignOut: () -> Unit,onEdit: () -> Unit,onRetry: () -> Unit,onPremium: () -> Unit) {
    var logout by remember {mutableStateOf(false)}
    LazyColumn(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Paper,Color(0xFF0A1120),Paper))),contentPadding=PaddingValues(start=18.dp,top=18.dp,end=18.dp,bottom=48.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        item {PageHeader("Your space","Profile, preferences and connection.")}
        item {Row(verticalAlignment=Alignment.CenterVertically) {Box(Modifier.size(64.dp).background(Lavender,CircleShape),contentAlignment=Alignment.Center){Text(state.profile.name.take(1).uppercase(),color=Paper,fontSize=26.sp,fontWeight=FontWeight.Bold)};Column(Modifier.weight(1f).padding(start=16.dp)){Text(state.profile.name,fontSize=23.sp,fontWeight=FontWeight.Bold);Text("${state.profile.classLevel} · ${state.profile.stream}",color=Muted)}}}
        item {OutlinedButton(onClick=onEdit,modifier=Modifier.fillMaxWidth()){Text("Edit study profile")}}
        item {Card(shape=RoundedCornerShape(26.dp),colors=CardDefaults.cardColors(containerColor=PanelElevated),border=BorderStroke(1.dp,Hairline)) {Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Icon(Icons.Rounded.VerifiedUser,null,tint=Lime);Text(if(state.user!=null) "Google account connected" else "Secure Google sign-in",fontSize=21.sp,fontWeight=FontWeight.Bold);Text(if(state.user!=null) "You’re ready to create AI decks. Your saved cards stay on this device." else "Connect your Google account to turn chapters and notes into revision cards.",color=Muted,fontSize=14.sp);if(state.user==null) ActionButton(if(signingIn||state.busy) "Signing in…" else "Continue with Google",onSignIn,enabled=!signingIn&&!state.busy) else OutlinedButton(onClick={logout=true},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Sign out")}}}}
        item {DepthCard(onClick=onPremium,modifier=Modifier.fillMaxWidth()){Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Rounded.WorkspacePremium,null,tint=Peach);Column(Modifier.weight(1f).padding(start=12.dp)){Text("MindDeck Plus",fontSize=18.sp,fontWeight=FontWeight.Bold);Text("Explore the planned premium features",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=5.dp))};Icon(Icons.Rounded.ChevronRight,null,tint=Muted)}}}
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




@Composable private fun WelcomeScreen(started: Boolean,signingIn: Boolean,error: String?,configLoading: Boolean,onStart: ()->Unit,onSignIn: ()->Unit,onOffline: ()->Unit,onBack: ()->Unit) {
    BackHandler(started&&!signingIn,onBack)
    Surface(Modifier.fillMaxSize(),color=Paper) {LazyColumn(Modifier.safeDrawingPadding(),contentPadding=PaddingValues(26.dp),verticalArrangement=Arrangement.spacedBy(20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        item {Spacer(Modifier.height(36.dp));Image(painterResource(R.drawable.ic_launcher),"MindDeck app icon",modifier=Modifier.size(100.dp).shadow(20.dp,RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)));Text("MindDeck",fontSize=34.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1).sp,modifier=Modifier.padding(top=24.dp));Text("LEARN · PRACTISE · REMEMBER",color=Lime,fontSize=10.sp,letterSpacing=2.sp,modifier=Modifier.padding(top=10.dp))}
        item {Text(if(started) "Your learning starts here." else "Big ambitions.\nSmall daily wins.",fontSize=30.sp,lineHeight=37.sp,fontWeight=FontWeight.Bold,textAlign=TextAlign.Center,modifier=Modifier.padding(top=24.dp));Text(if(started) "Sign in securely, then choose your name, class and stream." else "Turn chapters into revision cards. Test what you know. Make time to focus.",color=Muted,fontSize=15.sp,lineHeight=23.sp,textAlign=TextAlign.Center,modifier=Modifier.padding(top=16.dp))}
        if(error!=null) item {Surface(color=PanelElevated,shape=RoundedCornerShape(14.dp)){Text(error,color=Ink,fontSize=13.sp,lineHeight=20.sp,modifier=Modifier.padding(16.dp))}}
        item {Spacer(Modifier.height(12.dp));ActionButton(if(!started) "Get started" else if(signingIn) "Signing in…" else "Continue with Google",if(started) onSignIn else onStart,enabled=!signingIn);if(started&&configLoading) Text("Preparing secure sign-in…",color=Muted,fontSize=12.sp,modifier=Modifier.padding(top=10.dp))}
        item {if(!started) TextButton(onClick={onStart()}){Text("I already have an account")} else TextButton(onClick=onOffline,enabled=!signingIn){Text("Try free practice offline")}}
        item {Text("Saved study data stays on this device. Google sign-in enables online AI card creation.",color=Muted,fontSize=11.sp,lineHeight=17.sp,textAlign=TextAlign.Center)}
    }}
}
@Composable private fun PremiumScreen(onBack: ()->Unit) {
    LazyColumn(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Paper,Color(0xFF0A1120),Paper))),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item {PageHeader("Choose your study path","Free practice now. More ways to learn ahead.",onBack)}
        item {Surface(color=Panel,shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,Lime.copy(alpha=.5f))){Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Pill("AVAILABLE NOW",Lime);Text("MindDeck Free",fontSize=26.sp,fontWeight=FontWeight.Bold);Text("₹0",fontSize=36.sp,fontWeight=FontWeight.Bold);listOf("Bundled chapter practice tests","Tests from suitable saved decks","Scores, explanations and local test history","Saved revision cards and focus timer","Google sign-in for online AI creation, subject to service availability").forEach {Text("✓  $it",fontSize=14.sp,lineHeight=22.sp,color=Ink)}}}}
        item {Surface(color=PanelElevated,shape=RoundedCornerShape(24.dp),border=BorderStroke(1.dp,Lavender.copy(alpha=.5f))){Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Pill("PLANNED · NOT AVAILABLE YET",Lavender);Text("MindDeck Plus",fontSize=28.sp,fontWeight=FontWeight.Bold);Text("For a more personal preparation plan.",color=Muted,fontSize=14.sp);listOf("Higher AI card and test generation limits","Full-length timed mock exams","Chapter and topic performance analysis","A personal study plan and revision reminders","PDF and document-based practice","Cloud backup across your devices").forEach {Text("✦  $it",fontSize=14.sp,lineHeight=22.sp)};HorizontalDivider(color=Hairline);Text("These premium features are proposed. Pricing and subscription billing have not been enabled.",color=Muted,fontSize=13.sp,lineHeight=20.sp)}}}
    }
}
