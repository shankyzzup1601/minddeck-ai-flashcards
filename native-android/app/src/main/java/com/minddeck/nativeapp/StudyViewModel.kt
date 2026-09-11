package com.minddeck.nativeapp

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

data class StudyUiState(
    val profile: Profile = Profile(), val user: UserSession? = null, val cards: List<StudyCard> = emptyList(),
    val timer: TimerState = TimerState(), val focusSeconds: Int = 0, val loading: Boolean = true,
    val busy: Boolean = false, val error: String? = null, val info: String? = null,
    val serverClientId: String = PUBLIC_GOOGLE_SERVER_CLIENT_ID, val serverOnline: Boolean = false, val configLoading: Boolean = false,
    val cloudSyncing: Boolean = false, val cloudReady: Boolean = false
)

// OAuth client IDs are public identifiers. Bundling this fallback keeps sign-in available
// when the optional runtime configuration request is slow or temporarily unavailable.
private const val PUBLIC_GOOGLE_SERVER_CLIENT_ID = "407603468709-bghb7s1qoin5kbr12o5bgjodemq4qjvl.apps.googleusercontent.com"
class StudyViewModel(application: Application): AndroidViewModel(application) {
    private val store = StudyStore(application)
    private val vault = SessionVault(application)
    private val api = NativeApi()
    private val prefs = application.getSharedPreferences("native-preferences", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(StudyUiState())
    val state = mutable.asStateFlow()
    private val owner get() = mutable.value.user?.id ?: "guest"
    private val catalog = JSONObject(application.assets.open("syllabus.json").bufferedReader().use { it.readText() })
    init {
        viewModelScope.launch {
            val user = withContext(Dispatchers.IO) { vault.read() }
            val profile = Profile(prefs.getString("name","Student") ?: "Student",prefs.getString("class","Class 12") ?: "Class 12",prefs.getString("stream","PCB") ?: "PCB",prefs.getBoolean("onboarded",false))
            mutable.update { it.copy(user=user,profile=profile,timer=restoreTimer(user?.id ?: "guest")) }
            reload()
            mutable.update { it.copy(loading=false) }
            refreshConfig()
            if(user != null) runCatching { syncCloud(user) }
        }
        viewModelScope.launch {
            while(true) {
                delay(500)
                val current = mutable.value.timer
                if(current.running) {
                    val remaining = current.secondsAt(System.currentTimeMillis())
                    if(remaining == 0) completeFocus(current) else mutable.update { it.copy(timer=current.copy(remaining=remaining)) }
                }
            }
        }
    }
    private suspend fun reload() {
        val currentOwner = owner
        val cards = withContext(Dispatchers.IO) { store.cards(currentOwner) }
        val seconds = withContext(Dispatchers.IO) { store.focusSeconds(currentOwner) }
        if(owner == currentOwner) mutable.update { it.copy(cards=cards,focusSeconds=seconds) }
    }
    private fun cloudDeck(cards: List<StudyCard>): JSONObject {
        val array=JSONArray()
        cards.forEach { card -> array.put(JSONObject()
            .put("id",card.id).put("deck",card.deck).put("subject",card.subject)
            .put("front",card.front).put("back",card.back).put("due",card.due)
            .put("interval",card.interval).put("reviews",card.reviews)) }
        return JSONObject().put("version",4).put("cards",array).put("updatedAt",System.currentTimeMillis())
    }
    private fun cloudCards(data: JSONObject): List<StudyCard> {
        val source=data.optJSONObject("deck")?.optJSONArray("cards") ?: return emptyList()
        return (0 until source.length()).mapNotNull { index ->
            val card=source.optJSONObject(index) ?: return@mapNotNull null
            val id=card.optString("id").take(100)
            val deck=card.optString("deck").take(120)
            val subject=card.optString("subject").take(60)
            val front=card.optString("front").take(1000)
            val back=card.optString("back").take(3000)
            if(id.isBlank()||deck.isBlank()||subject.isBlank()||front.isBlank()||back.isBlank()) null
            else StudyCard(id,deck,subject,front,back,card.optLong("due",0).coerceAtLeast(0),card.optInt("interval",0).coerceIn(0,3650),card.optInt("reviews",0).coerceIn(0,100000))
        }.distinctBy { it.id }.take(2000)
    }
    private fun dirtyKey(userId: String)="cloud-dirty-$userId"
    private fun markCloudDirty() { mutable.value.user?.let { prefs.edit().putBoolean(dirtyKey(it.id),true).apply() } }
    private suspend fun pushCloud(session: UserSession) {
        val cards=withContext(Dispatchers.IO) { store.cards(session.id) }
        api.request(JSONObject().put("action","pushDeck").put("deck",cloudDeck(cards)),session.accessToken)
        prefs.edit().putBoolean(dirtyKey(session.id),false).apply()
        mutable.update { it.copy(cloudReady=true) }
    }
    private suspend fun syncCloud(session: UserSession) {
        mutable.update { it.copy(cloudSyncing=true) }
        try {
            val local=withContext(Dispatchers.IO) { store.cards(session.id) }
            if(prefs.getBoolean(dirtyKey(session.id),false)) pushCloud(session)
            else {
                val remote=cloudCards(api.request(JSONObject().put("action","pullDeck"),session.accessToken))
                if(remote.isEmpty() && local.isNotEmpty()) pushCloud(session)
                else {
                    withContext(Dispatchers.IO) { store.replaceCards(session.id,remote) }
                    reload()
                    mutable.update { it.copy(cloudReady=true) }
                }
            }
        } finally { mutable.update { it.copy(cloudSyncing=false) } }
    }
    private suspend fun pushCloudBestEffort() {
        val session=mutable.value.user ?: return
        runCatching { pushCloud(session) }
    }
    fun syncNow() = task {
        val session=mutable.value.user ?: throw IllegalStateException("Sign in with Google to sync your library.")
        syncCloud(session)
        mutable.update { it.copy(info="Your cards are synced securely with Supabase.") }
    }
    fun refreshConfig() {
        if(mutable.value.configLoading) return
        mutable.update { it.copy(configLoading=true) }
        viewModelScope.launch {
            try {
                val config=api.config()
                val configuredClientId = config.optString("googleClientId").ifBlank { PUBLIC_GOOGLE_SERVER_CLIENT_ID }
                mutable.update { it.copy(serverOnline=true,serverClientId=configuredClientId) }
            } catch (_: Exception) {
                mutable.update { it.copy(serverOnline=false,serverClientId=it.serverClientId.ifBlank { PUBLIC_GOOGLE_SERVER_CLIENT_ID }) }
            }
            finally { mutable.update { it.copy(configLoading=false) } }
        }
    }
    fun saveProfile(name: String, classLevel: String, stream: String) {
        val profile=Profile(name.trim().ifEmpty { "Student" }.take(60),classLevel,stream,true)
        prefs.edit().putString("name",profile.name).putString("class",classLevel).putString("stream",stream).putBoolean("onboarded",true).apply()
        mutable.update { it.copy(profile=profile) }
    }
    fun chapters(subject: String): List<String> {
        val list=catalog.optJSONObject(mutable.value.profile.classLevel)?.optJSONArray(subject) ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it).getString("title") }
    }
    fun showError(message: String) { mutable.update { it.copy(error=message) } }
    fun clearMessage() { mutable.update { it.copy(error=null,info=null) } }
    fun signIn(idToken: String, nonce: String) = task {
        val session = api.session(api.request(JSONObject().put("action","signIn").put("idToken",idToken).put("nonce",nonce)))
        withContext(Dispatchers.IO) { vault.save(session) }
        mutable.update { it.copy(user=session,cards=emptyList(),focusSeconds=0,timer=restoreTimer(session.id),info="Signed in securely. Your study space is ready.") }
        reload()
        syncCloud(session)
    }
    fun signOut() = task {
        val user=mutable.value.user
        // Hide local account data first; remote revocation must not block local sign-out.
        withContext(Dispatchers.IO) { vault.clear() }
        mutable.update { it.copy(user=null,cards=emptyList(),focusSeconds=0,timer=restoreTimer("guest"),info="Signed out. Account cards stay private on this device.") }
        if(user != null) viewModelScope.launch { runCatching { api.request(JSONObject().put("action","signOut"),user.accessToken) } }
        reload()
    }
    private suspend fun authenticatedRequest(body: JSONObject): JSONObject {
        var user=mutable.value.user ?: throw ApiFailure(401,"Sign in with Google to create AI cards.")
        try { return api.request(body,user.accessToken) }
        catch(e: ApiFailure) {
            if(e.status != 401) throw e
            try {
                user=api.session(api.request(JSONObject().put("action","refresh").put("refreshToken",user.refreshToken)))
            } catch(refreshError: ApiFailure) {
                if(refreshError.status == 401) {
                    withContext(Dispatchers.IO) { vault.clear() }
                    mutable.update { it.copy(user=null,cards=emptyList(),focusSeconds=0,timer=restoreTimer("guest")) }
                    reload()
                    throw ApiFailure(401,"Your Google session expired. Please sign in again. Your account cards have not been deleted.")
                }
                throw refreshError
            }
            withContext(Dispatchers.IO) { vault.save(user) }
            mutable.update { it.copy(user=user) }
            return api.request(body,user.accessToken)
        }
    }
    fun generate(subject: String, chapter: String, notes: String, onDone: () -> Unit) = task {
        require(chapter.isNotBlank() || notes.trim().length >= 30) { "Choose a chapter or paste at least 30 characters of notes." }
        val currentOwner = owner
        val title=if(notes.isBlank()) chapter else "My notes · $subject"
        val cards = if (mutable.value.user == null) {
            // Offline setup stays useful: use the bundled starter bank instead of a dead AI error.
            PracticeBank.questions.filter { it.subject == subject && (chapter.isBlank() || it.chapter == chapter) }
                .take(15).map { StudyCard(UUID.randomUUID().toString(), title, subject, it.prompt, "${it.options[it.correct]}\\n\\n${it.explanation}") }
        } else {
            val data=authenticatedRequest(JSONObject().put("action","generate").put("subject",subject).put("classLevel",mutable.value.profile.classLevel).put("chapter",chapter).put("notes",notes.trim().take(12000)))
            val source=data.getJSONArray("cards")
            (0 until source.length()).map { source.getJSONObject(it) }.map {
                StudyCard(UUID.randomUUID().toString(),title,subject,it.getString("front").take(1000),it.getString("back").take(3000))
            }.filter { it.front.isNotBlank() && it.back.isNotBlank() }.take(20)
        }
        require(cards.isNotEmpty()) { "No usable cards came back. Your existing decks have not changed." }
        withContext(Dispatchers.IO) { store.save(currentOwner,cards) }
        markCloudDirty()
        reload()
        pushCloudBestEffort()
        mutable.update { it.copy(info="${cards.size} cards saved. AI can make mistakes—check important facts against your textbook.") }
        onDone()
    }
    fun addCard(deck: String, subject: String, front: String, back: String) = task {
        require(front.isNotBlank() && back.isNotBlank()) { "Add both a question and its answer." }
        val card=StudyCard(UUID.randomUUID().toString(),deck.trim().ifBlank { "My $subject notes" }.take(120),subject,front.trim().take(1000),back.trim().take(3000))
        withContext(Dispatchers.IO) { store.save(owner,listOf(card)) }
        markCloudDirty()
        reload()
        pushCloudBestEffort()
    }
    fun review(card: StudyCard, remembered: Boolean, onDone: () -> Unit) = task {
        val updated=ReviewScheduler.next(card,remembered,System.currentTimeMillis())
        withContext(Dispatchers.IO) { store.save(owner,listOf(updated)) }
        markCloudDirty(); reload(); pushCloudBestEffort(); onDone()
    }
    fun deleteDeck(title: String) = task { withContext(Dispatchers.IO) { store.deleteDeck(owner,title) }; markCloudDirty(); reload(); pushCloudBestEffort() }
    private fun task(block: suspend () -> Unit) {
        if(mutable.value.busy) return
        mutable.update { it.copy(busy=true,error=null,info=null) }
        viewModelScope.launch {
            try { block() }
            catch(e: Exception) { mutable.update { it.copy(error=e.message ?: "Something went wrong. Please try again.") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    private fun restoreTimer(owner: String): TimerState {
        val duration=prefs.getInt("$owner-duration",1500).coerceIn(60,7200)
        return TimerState(duration,prefs.getInt("$owner-remaining",duration).coerceIn(0,duration),prefs.getLong("$owner-end",0),prefs.getString("$owner-session","") ?: "")
    }
    private fun persistTimer(timer: TimerState) {
        prefs.edit().putInt("$owner-duration",timer.duration).putInt("$owner-remaining",timer.remaining).putLong("$owner-end",timer.endAt).putString("$owner-session",timer.sessionId).apply()
        mutable.update { it.copy(timer=timer) }
    }
    fun timerToggle() {
        val current=mutable.value.timer
        val remaining=current.secondsAt(System.currentTimeMillis())
        if(current.running && remaining == 0) { viewModelScope.launch { completeFocus(current) }; return }
        persistTimer(if(current.running) current.copy(remaining=remaining,endAt=0) else {
            val fresh=current.remaining <= 0 || current.sessionId.isBlank()
            current.copy(remaining=if(current.remaining<=0) current.duration else current.remaining,endAt=System.currentTimeMillis()+(if(current.remaining<=0) current.duration else current.remaining)*1000L,sessionId=if(fresh) UUID.randomUUID().toString() else current.sessionId)
        })
    }
    fun resetTimer(minutes: Int = mutable.value.timer.duration / 60) { persistTimer(TimerState(duration=minutes*60,remaining=minutes*60)) }
    private suspend fun completeFocus(timer: TimerState) {
        val currentOwner=owner
        // Unique session key makes repeated completion after a process restart harmless.
        withContext(Dispatchers.IO) { store.finishFocus(currentOwner,timer.sessionId,timer.duration,timer.endAt) }
        if(owner==currentOwner && mutable.value.timer.sessionId == timer.sessionId) {
            persistTimer(timer.copy(remaining=0,endAt=0))
            reload()
            mutable.update { it.copy(info="Focus session complete. Take a short break.") }
        }
    }
    override fun onCleared() { store.close(); super.onCleared() }
}
