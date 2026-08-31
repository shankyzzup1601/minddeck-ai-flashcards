package com.minddeck.nativeapp
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class NativeUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun onboard() {
        compose.waitUntil(20000) {compose.onAllNodesWithText("Let's begin  →").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("Home").fetchSemanticsNodes().isNotEmpty()}
        if(compose.onAllNodesWithText("Let's begin  →").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Let's begin  →").performScrollTo().performClick()
        compose.onNodeWithText("Home").assertExists()
    }
    @Test fun navigationAndRecreationRemainUsable() {
        onboard()
        repeat(3) {
            listOf("Library","Focus","You","Home").forEach { label -> compose.onNodeWithText(label,useUnmergedTree=true).performClick() }
        }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Home").assertExists()
    }
    @Test fun timerPauseResumeAndRecreate() {
        onboard();compose.onNodeWithText("Focus",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Start focus").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("24:59").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pause session").performScrollTo().performClick()
        compose.onNodeWithText("Resume session").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Resume session").assertExists()
    }
    @Test fun cardStorageIsAccountScopedAndPersistent() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val first=StudyStore(context)
        val id="test-card-${System.nanoTime()}"
        first.save("test-owner-a",listOf(StudyCard(id,"Test deck","Physics","What is charge?","A physical property.")))
        assertTrue(first.cards("test-owner-a").any {it.id==id})
        assertFalse(first.cards("test-owner-b").any {it.id==id})
        first.close()
        val reopened=StudyStore(context)
        assertTrue(reopened.cards("test-owner-a").any {it.id==id})
        reopened.deleteDeck("test-owner-a","Test deck");reopened.close()
    }
    @Test fun duplicateTimerCompletionNeverDoubleCounts() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=StudyStore(context);val owner="timer-test-${System.nanoTime()}"
        repeat(5) {store.finishFocus(owner,"session-$owner",1500,System.currentTimeMillis())}
        assertEquals(1500,store.focusSeconds(owner));store.close()
    }
    @Test fun secureVaultRoundTripAndLogout() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val vault=SessionVault(context)
        val session=UserSession("test-id","Test","test-access-secret","test-refresh-secret")
        vault.save(session);assertEquals(session,vault.read())
        val raw=context.getSharedPreferences("native-session",Context.MODE_PRIVATE).getString("data","")!!
        assertFalse(raw.contains("test-access-secret"))
        vault.clear();assertNull(vault.read())
    }
}
