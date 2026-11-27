package app.couch.alhasapa.results

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.BuildConfig
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.player.PlayerActivity
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** The result sheet in the player, the clipboard route, and the Android share target, through the real framework. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class, qualifiers = "w914dp-h411dp-land")
class ResultFlowTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app get() = context as CouchApp
    private val today get() = CouchState.dayKey(LocalDate.now())
    private var scenario: ActivityScenario<*>? = null

    @After fun close() { scenario?.close() }

    private fun player(gameId: String, who: String? = null) {
        scenario = ActivityScenario.launch<PlayerActivity>(PlayerActivity.gameIntent(context, gameId, who)); rule.waitForIdle()
    }

    private fun share(text: String?) {
        val i = Intent(context, ShareReceiverActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
        if (text != null) i.putExtra(Intent.EXTRA_TEXT, text)
        scenario = ActivityScenario.launch<ShareReceiverActivity>(i); rule.waitForIdle()
    }

    /** The phone's sheet (fields to paste and type into). On a TV the sheet is buttons and the phone's keys: see PlayerTvDockTest. */
    private fun phoneOnly() = org.junit.Assume.assumeFalse(BuildConfig.TV)

    /** The strip and the panel both have a result button; either one will do. */
    private fun resultButton() = rule.onAllNodesWithText("תוצאה").onFirst().performClick()

    private fun type(label: String, text: String) = rule.onNode(hasSetTextAction() and hasContentDescription(label)).performScrollTo().performTextInput(text)
    private fun press(label: String) = rule.onNodeWithText(label).performScrollTo().performClick()

    // ---- in the player ----

    @Test
    fun theResultButtonOpensTheSheetAndAManualNumberIsSavedAndScored() {
        phoneOnly()
        player("wordle")
        resultButton()
        rule.onNodeWithText("Wordle: התוצאה שלנו").assertIsDisplayed()
        type("או ידנית: ניסיונות", "4")
        rule.onNodeWithText("זוהה: 4/6 · 7 נקודות ספה").assertIsDisplayed()
        press("שמירה"); rule.waitForIdle()
        val e = CouchState.entry(app.repo.snapshot(), today, "wordle")!!
        assertEquals(4, e.getJSONObject("r").getInt("v"))
        assertEquals("נשמר. 7 נקודות ספה.", ShadowToast.getTextOfLatestToast())
        rule.onNodeWithText("Wordle: התוצאה שלנו").assertDoesNotExist()
    }

    @Test
    fun aPastedShareIsUnderstoodBeforeItIsSaved() {
        phoneOnly()
        player("wordle")
        resultButton()
        type("להדביק את מה שהמשחק העתיק", "Wordle 1,618 3/6\n\n🟩🟩🟩🟩🟩")
        rule.onNodeWithText("זוהה: 3/6 · 9 נקודות ספה").assertIsDisplayed()
        assertNull("nothing is saved until Save", CouchState.entry(app.repo.snapshot(), today, "wordle"))
    }

    @Test
    fun whoCrackedItIsCreditAndCanBeChosen() {
        phoneOnly()
        player("wordle")
        resultButton()
        type("או ידנית: ניסיונות", "3")
        rule.onNode(hasContentDescription("רוני") and hasClickAction()).performScrollTo().performClick(); press("שמירה"); rule.waitForIdle()
        assertEquals("b", CouchState.entry(app.repo.snapshot(), today, "wordle")!!.getString("w"))
    }

    @Test
    fun aCopyThatLooksLikeTheResultOpensTheSheetFilledIn() {
        phoneOnly()
        player("wordle")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("result", "Wordle 1,618 3/6\n\n🟩🟩🟩🟩🟩"))
        rule.waitForIdle()
        rule.onNodeWithText("העתקנו את התוצאה מהמשחק. אפשר לתקן לפני השמירה.").assertIsDisplayed()
        rule.onNodeWithText("זוהה: 3/6 · 9 נקודות ספה").assertIsDisplayed()
    }

    @Test
    fun aCopyThatIsNotAResultIsLeftAlone() {
        phoneOnly()
        player("wordle")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("link", "https://example.org/some/article"))
        rule.waitForIdle()
        rule.onNodeWithText("Wordle: התוצאה שלנו").assertDoesNotExist()
    }

    @Test
    fun aHeadToHeadResultBelongsToWhoIsPlaying() {
        phoneOnly()
        player("queens", who = "b")
        resultButton()
        rule.onNodeWithText("Queens: התוצאה של רוני").assertIsDisplayed()
        type("להדביק את מה שהמשחק העתיק", "Queens #612 | 1:24")
        rule.onNodeWithText("זוהה: 1:24").assertIsDisplayed()
        press("שמירה"); rule.waitForIdle()
        val d = CouchState.entry(app.repo.snapshot(), today, "queens")!!.getJSONObject("d")
        assertEquals(84, d.getJSONObject("b").getInt("v")); assertTrue(!d.has("a"))
        assertEquals("נשמר. מחכים לצד השני.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun theSheetOpensOnWhatIsAlreadySavedAndCanClearIt() {
        phoneOnly()
        app.repo.update { CouchState.setEntry(it, today, "wordle", org.json.JSONObject().put("w", "").put("r", org.json.JSONObject().put("v", 3).put("max", 6).put("kind", "guesses").put("fail", false).put("raw", "Wordle 9 3/6"))) }
        player("wordle")
        resultButton()
        rule.onNodeWithText("Wordle 9 3/6").assertIsDisplayed()
        press("למחוק תוצאה"); rule.waitForIdle()
        val e = CouchState.entry(app.repo.snapshot(), today, "wordle")!!
        assertTrue("the tick stays", e.has("w")); assertTrue(!e.has("r"))
    }

    // ---- the share target ----

    @Test
    fun aSharedWordleIsRecognisedShownAndSavedForTonight() {
        share("Wordle 1,618 3/6\n\n🟩🟩🟩🟩🟩")
        rule.onNodeWithText("Wordle: התוצאה שלנו").assertIsDisplayed()
        rule.onNodeWithText("זוהה: 3/6 · 9 נקודות ספה").assertIsDisplayed()
        press("שמירה"); rule.waitForIdle()
        assertEquals(3, CouchState.entry(app.repo.snapshot(), today, "wordle")!!.getJSONObject("r").getInt("v"))
        assertEquals("נשמר. 9 נקודות ספה.", ShadowToast.getTextOfLatestToast())
        scenario!!.let { sc -> if (sc.state != androidx.lifecycle.Lifecycle.State.DESTROYED) (sc as ActivityScenario<ShareReceiverActivity>).onActivity { assertTrue("it closes after saving", it.isFinishing) } }
    }

    @Test
    fun aSharedLinkedInResultAsksWhoPlayedAndSavesThatSide() {
        share("Queens #612 | 1:24\nFirst placed 👑")
        rule.onNodeWithText("מי שיחק?").assertIsDisplayed()
        rule.onNode(hasContentDescription("משחק: רוני")).performClick()
        rule.onNodeWithText("Queens: התוצאה של רוני").assertIsDisplayed()
        press("שמירה"); rule.waitForIdle()
        assertEquals(84, CouchState.entry(app.repo.snapshot(), today, "queens")!!.getJSONObject("d").getJSONObject("b").getInt("v"))
    }

    @Test
    fun aTextItCannotPlaceAsksWhichGame() {
        share("Some game #5 3 tries")
        rule.onNodeWithText("באיזה משחק מדובר?").assertIsDisplayed()
        rule.onNode(hasContentDescription("Strands")).performScrollTo().performClick()
        rule.onNodeWithText("Strands: התוצאה שלנו").assertIsDisplayed()
    }

    @Test
    fun somethingThatIsNotTextClosesAtOnce() {
        share(null)
        assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario!!.state)
        assertEquals("אין כאן טקסט של תוצאה.", ShadowToast.getTextOfLatestToast())
        assertNotNull(ShareReceiverActivity.sharedText(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "hi")))
        assertNull("another action is not a share", ShareReceiverActivity.sharedText(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "hi")))
        assertNull("far too long to be a result", ShareReceiverActivity.sharedText(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "x".repeat(20_001))))
    }
}
