package com.minddeck.nativeapp
import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.After
import org.junit.rules.TestName
import androidx.lifecycle.ViewModelProvider
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.*

class NativeUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val testName=TestName()
    @After fun captureUiForReview() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.getExternalFilesDir(null),"screenshots").apply {mkdirs()}
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder,"${testName.methodName}.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    private fun onboard() {
        compose.waitUntil(20000) {compose.onAllNodesWithText("A calmer way\nto study.").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("Home").fetchSemanticsNodes().isNotEmpty()}
        if(compose.onAllNodesWithText("A calmer way\nto study.").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Let's begin  →"))
            compose.onNodeWithText("Let's begin  →").performClick()
        }
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
    @Test fun primaryActionsRemainReachableOnLongPages() {
        onboard()
        compose.onNodeWithText("Library",useUnmergedTree=true).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("✨  Create with AI"))
        compose.onNodeWithText("✨  Create with AI").assertIsDisplayed().performClick()
        compose.onNodeWithText("Create a deck").assertExists()
        val primaryAction=hasText("Continue with Google") or hasText("Create revision cards")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(primaryAction)
        compose.onNode(primaryAction).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Go back"))
        compose.onNodeWithContentDescription("Go back").assertIsDisplayed().performClick()
        compose.onNodeWithText("Library",useUnmergedTree=true).assertExists()
    }

    @Test fun timerPauseResumeAndRecreate() {
        onboard();compose.onNodeWithText("Focus",useUnmergedTree=true).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Start focus"))
        compose.onNodeWithText("Start focus").performClick()
        val model=ViewModelProvider(compose.activity)[StudyViewModel::class.java]
        compose.waitUntil(5000) { model.state.value.timer.remaining < 1500 }
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
