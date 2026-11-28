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
import org.junit.Assert.assertNotNull
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

    @After fun close() { scenario?.close(); TvController.PageNavOverride = null; app.couch.alhasapa.remote.RemoteKeys.uprightChanged(0f) }

    private fun launch(page: PageNav? = null, game: String = "wordle") {
        TvController.PageNavOverride = page
        scenario = ActivityScenario.launch(PlayerActivity.gameIntent(context, game, null)); rule.waitForIdle()
    }

    /** The game's box on the screen (the frame that holds its WebView), width over height. */
    private fun gameShape(): Float {
        var shape = 0f
        scenario!!.onActivity { a -> val box = findWeb(a.window.decorView)!!.parent as View; shape = box.width.toFloat() / box.height }
        return shape
    }

    /** 2.0.2: a phone in touch mode held upright: the game, shown on the whole TV, is shaped like the phone meanwhile, and only meanwhile. */
    @Test
    fun aPhoneHeldUprightInTouchModeShapesTheGameLikeItAndOnItsSideItIsAsItWas() {
        launch(game = "dialed")
        val own = gameShape()
        assertTrue("Dialed is shown on the whole screen beside the dock: $own", own > 1.2f)
        app.couch.alhasapa.remote.RemoteKeys.uprightChanged(0.48f); rule.waitForIdle()
        assertEquals("shaped like the phone", 0.48f, gameShape(), 0.02f)
        app.couch.alhasapa.remote.RemoteKeys.uprightChanged(0f); rule.waitForIdle()
        assertEquals("as it was", own, gameShape(), 0.01f)
        val displays = (context as CouchApp).repo.snapshot().optJSONObject("displays")
        assertTrue("nothing of it was kept for the game", displays == null || displays.length() == 0)
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
    fun theKeyboardNeverOpensByItself() {
        launch()
        // The remote's Play/Pause is the page's now.
        scenario!!.onActivity { a -> assertFalse(key(a, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) }
        rule.waitForIdle()
        rule.onNode(hasContentDescription("q")).assertDoesNotExist()
        // The system asks the page for a way to type every time the window gets focus back (the start, a game closed): nothing opens.
        scenario!!.onActivity { a ->
            val web = findWeb(a.window.decorView)!!
            assertTrue("it is our WebView", web is CouchWebView)
            assertNull("the system keyboard gets nothing to type into", web.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            assertFalse("and the system is told this is not a text editor", web.onCheckIsTextEditor())
            a.onWindowFocusChanged(false); a.onWindowFocusChanged(true)
        }
        rule.waitForIdle()
        rule.onNode(hasContentDescription("q")).assertDoesNotExist()
        rule.onNode(hasContentDescription("סגירת מקלדת")).assertDoesNotExist()
    }

    @Test
    fun theInformationPanelOpensFromTheDockWithoutThePageAndBackClosesIt() {
        launch()
        rule.onNode(hasContentDescription("מידע לתיקון תקלות")).performClick(); rule.waitForIdle()
        rule.onNodeWithText("מידע לתיקון תקלות", substring = false).assertIsDisplayed()
        rule.onNodeWithText("app ", substring = true).assertIsDisplayed()
        scenario!!.onActivity { a -> assertTrue(key(a, KeyEvent.KEYCODE_BACK)); assertTrue(key(a, KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP)) }
        rule.waitForIdle()
        rule.onNode(hasContentDescription("סגירה")).assertDoesNotExist()
        scenario!!.onActivity { assertFalse("Back closed the panel, not the game", it.isFinishing) }
    }

    @Test
    fun theRemoteWalksThisScreensOwnWebViewAndLetsGoOfItWhenTheScreenCloses() {
        launch()
        var web: android.webkit.WebView? = null
        scenario!!.onActivity { a -> web = findWeb(a.window.decorView) }
        assertNotNull(web)
        scenario!!.close(); scenario = null
        // The game's WebView is gone with its screen: nothing keeps it as the target of the remote or the keyboard.
        val c = TvController(true, 1f)
        c.web = web; c.releaseWeb(web!!)
        assertNull(c.web)
    }

    private fun findWeb(v: View): android.webkit.WebView? = when (v) {
        is android.webkit.WebView -> v
        is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findWeb(v.getChildAt(it)) }
        else -> null
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
