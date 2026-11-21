package app.couch.alhasapa.tv

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

private class EdgePage : PageNav {
    var result: PageResult = PageResult.Edge
    override fun enter(dir: Dir?, cb: (PageResult) -> Unit) = cb(PageResult.Moved)
    override fun move(dir: Dir, cb: (PageResult) -> Unit) = cb(result)
    override fun activate(cb: (PageResult) -> Unit) = cb(PageResult.None)
    override fun blur() {}
    override fun tap(x: Float, y: Float) {}
}

/** The player on a television (a 960 x 540 dp screen with the television UI mode): the dock, the paw switch, the keyboard, the ring. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class, qualifiers = "w960dp-h540dp-television-xhdpi")
class TvScreensTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<PlayerActivity>? = null

    @After fun close() { scenario?.close(); TvController.PageNavOverride = null }

    private fun launch(page: PageNav? = null) {
        TvController.PageNavOverride = page
        scenario = ActivityScenario.launch(PlayerActivity.gameIntent(context, "wordle", null)); rule.waitForIdle()
    }

    private fun key(a: PlayerActivity, code: Int, action: Int = KeyEvent.ACTION_DOWN) = a.window.callback.dispatchKeyEvent(KeyEvent(0, 0, action, code, 0))
    private fun press(code: Int) { scenario!!.onActivity { a -> key(a, code); key(a, code, KeyEvent.ACTION_UP) }; rule.waitForIdle() }

    @Test
    fun aTelevisionAlwaysHasTheDockWithThePawSwitchAndTheKeyboardButton() {
        launch()
        rule.onNode(hasContentDescription("להפעיל כפה (עכבר)")).assertIsDisplayed()
        rule.onNode(hasContentDescription("פתיחת מקלדת")).assertIsDisplayed()
    }

    @Test
    fun thePawSwitchSaysClearlyWhichModeIsOn() {
        launch()
        rule.onNode(hasContentDescription("להפעיל כפה (עכבר)")).performClick(); rule.waitForIdle()
        rule.onNode(hasContentDescription("כפה פועלת. לחיצה על הכפה חוזרת לניווט בין כפתורים")).assertIsDisplayed()
        rule.onNodeWithText("מצב כפה: החצים מזיזים את הכפה · OK לחיצה · OK ארוך לגרירה").assertIsDisplayed()
        rule.onNode(hasContentDescription("כפה פועלת. לחיצה על הכפה חוזרת לניווט בין כפתורים")).performClick(); rule.waitForIdle()
        rule.onNodeWithText("מצב ניווט: החצים עוברים בין כפתורים · OK לוחץ").assertIsDisplayed()
        rule.onNode(hasContentDescription("להפעיל כפה (עכבר)")).assertIsDisplayed()
    }

    @Test
    fun theKeyboardButtonSlidesTheKeyboardUpAndBackClosesIt() {
        launch()
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
    fun theArrowsAreTakenByTheFocusNavigation() {
        launch()
        scenario!!.onActivity { a -> assertTrue(key(a, KeyEvent.KEYCODE_DPAD_RIGHT)); assertTrue(key(a, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP)) }
    }

    @Test
    fun atTheEdgeOfTheGameTheFocusRingLandsOnAButtonOfTheApp() {
        launch(EdgePage())
        val lit = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "נבחר")
        rule.onAllNodes(lit).assertCountEquals(0)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.onAllNodes(lit).assertCountEquals(1)
        // ...and it can be walked on from there: Up goes to the next button that way, still exactly one ring.
        press(KeyEvent.KEYCODE_DPAD_UP)
        rule.onAllNodes(lit).assertCountEquals(1)
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
        }
    }
}
