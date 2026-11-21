package app.couch.alhasapa.tv

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.webkit.WebChromeClient
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.BuildConfig
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.player.PlayerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The player on a television (a 960 x 540 dp screen with the television UI mode): the handle, the keyboard, the menu's TV buttons. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class, qualifiers = "w960dp-h540dp-television-xhdpi")
class TvScreensTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<PlayerActivity>? = null

    @After fun close() { scenario?.close() }

    private fun launch() {
        scenario = ActivityScenario.launch(PlayerActivity.gameIntent(context, "wordle", null)); rule.waitForIdle()
    }

    private fun key(a: PlayerActivity, code: Int, action: Int = KeyEvent.ACTION_DOWN) = a.window.callback.dispatchKeyEvent(KeyEvent(0, 0, action, code, 0))

    private fun pushCursorToTheBottom() {
        scenario!!.onActivity { a ->
            // The arrows get the cursor there; holding Down is what a person does.
            a.window.callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(4))
            a.window.callback.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN, 0))
        }
        rule.waitForIdle()
    }

    @Test
    fun theKeyboardHandleRisesWhenTheCursorIsPushedToTheBottomEdgeAndNotBefore() {
        launch()
        rule.onNode(hasContentDescription("פתיחת מקלדת")).assertDoesNotExist()
        pushCursorToTheBottom()
        rule.onNode(hasContentDescription("פתיחת מקלדת")).assertIsDisplayed()
    }

    @Test
    fun theHandleSlidesTheKeyboardUpAndBackClosesIt() {
        launch()
        pushCursorToTheBottom()
        rule.onNode(hasContentDescription("פתיחת מקלדת")).performClick(); rule.waitForIdle()
        rule.onNode(hasContentDescription("q")).assertIsDisplayed()
        rule.onNode(hasContentDescription("סגירת מקלדת")).assertIsDisplayed()
        scenario!!.onActivity { a -> assertTrue(key(a, KeyEvent.KEYCODE_BACK)); assertTrue(key(a, KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)) }
        rule.waitForIdle()
        rule.onNode(hasContentDescription("q")).assertDoesNotExist()
        scenario!!.onActivity { assertFalse("the keyboard is gone, Back is the system's again", it.isFinishing) }
    }

    @Test
    fun thePlayPauseKeyOpensTheKeyboardFromTheRemote() {
        launch()
        scenario!!.onActivity { a -> assertTrue(key(a, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) }
        rule.waitForIdle()
        rule.onNode(hasContentDescription("סגירת מקלדת")).assertIsDisplayed()
    }

    @Test
    fun theArrowsAreTakenByTheCursorOnATelevision() {
        launch()
        scenario!!.onActivity { a -> assertTrue(key(a, KeyEvent.KEYCODE_DPAD_RIGHT)); assertTrue(key(a, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP)) }
    }

    @Test
    fun theMenuOffersTheKeyboardAndTheCursorSwitchOnATelevision() {
        launch()
        rule.onNode(hasContentDescription("תפריט")).performClick()
        rule.onAllNodes(hasContentDescription("פתיחת מקלדת")).assertCountEquals(1)     // the menu's button (the handle is not up)
        rule.onNodeWithText("סמן בשלט: פועל").assertExists()
        rule.onNodeWithText("סמן בשלט: פועל").performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithText("סמן בשלט: כבוי (ניווט רגיל)").assertExists()
    }

    @Test
    fun theLauncherEntryMatchesTheBuild() {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(context.packageName)
        val found = context.packageManager.queryIntentActivities(i, 0)
        if (BuildConfig.TV) assertEquals("the TV build shows up in the TV launcher", 1, found.size)
        else assertTrue("the phone build does not", found.isEmpty())
    }

    @Test
    fun aGamesFullScreenVideoIsShownOverEverythingAndLeftAgain() {
        launch()
        scenario!!.onActivity { a ->
            val chrome = VideoChrome(a)
            val video = View(a)
            var hidden = false
            val cb = object : WebChromeClient.CustomViewCallback { override fun onCustomViewHidden() { hidden = true } }
            chrome.onShowCustomView(video, cb)
            assertTrue(chrome.isShowing); assertEquals(a.window.decorView, video.parent)
            chrome.onHideCustomView()
            assertFalse(chrome.isShowing); assertTrue(hidden); assertNull(video.parent)
            chrome.onHideCustomView()                                   // harmless twice
            assertTrue(FrameLayout(a).childCount == 0)
        }
    }
}
