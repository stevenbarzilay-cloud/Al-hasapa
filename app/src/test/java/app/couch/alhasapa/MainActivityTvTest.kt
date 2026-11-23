package app.couch.alhasapa

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.diag.CrashReport
import app.couch.alhasapa.diag.Flight
import app.couch.alhasapa.tv.CouchWebView
import java.io.File
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

/**
 * The screen a TV opens first: its own native home (1.9), with the dock; the web hub one step away. Nothing here was covered before 1.5, so a mistake on this path (the TV theme, the
 * starting window, the dock without a game, the reports of the previous run) could have passed every other test.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class, qualifiers = "w960dp-h540dp-television-xhdpi")
class MainActivityTvTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun close() { scenario?.close() }

    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java); rule.waitForIdle() }

    private fun webOf(v: View): WebView? = when (v) {
        is WebView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { webOf(v.getChildAt(it)) }
        else -> null
    }

    private fun remote(vararg codes: Int) {
        for (code in codes) {
            scenario!!.onActivity { a ->
                a.window.callback.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
                a.window.callback.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
            }
            rule.waitForIdle()
        }
    }

    private val inFocus = androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "נבחר")

    @Test
    fun theTvOpensOnItsOwnHomeAndTheRemoteOpensAGame() {
        CrashReport.clear(context); Flight.acknowledgePrevious()
        launch()
        rule.onNodeWithText("המדף של הערב").assertIsDisplayed()
        rule.onNode(hasContentDescription("להפעיל כפה (עכבר)")).assertDoesNotExist()        // no dock on the home
        rule.onNode(hasContentDescription("q")).assertDoesNotExist()                          // and no keyboard
        scenario!!.onActivity { a -> assertNull("no web page behind the home: it is native", webOf(a.window.decorView)) }
        // The first game left to play has the focus from the start: one OK on the remote opens it.
        rule.onNode(hasContentDescription("Wordle").and(inFocus)).assertIsDisplayed()
        remote(android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        scenario!!.onActivity { a ->
            val started = org.robolectric.Shadows.shadowOf(a).nextStartedActivity
            assertEquals(app.couch.alhasapa.player.PlayerActivity::class.java.name, started.component!!.className)
            assertEquals("connections", started.getStringExtra(app.couch.alhasapa.player.PlayerActivity.EXTRA_GAME))
        }
    }

    @Test
    fun theFullHubIsOneStepFromTheHomeAndBackComesHome() {
        CrashReport.clear(context); Flight.acknowledgePrevious()
        launch()
        // Down to the other games, down to the buttons, along to "הגדרות ועוד", OK.
        remote(android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        rule.onNode(hasContentDescription("הגדרות ועוד").and(inFocus)).assertIsDisplayed()
        remote(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        scenario!!.onActivity { a ->
            val web = webOf(a.window.decorView)
            assertNotNull("the hub's WebView is there", web)
            assertTrue(web is CouchWebView)
            // Coming back to the window (from a game) must not open anything.
            a.onWindowFocusChanged(false); a.onWindowFocusChanged(true)
            web!!.onCreateInputConnection(android.view.inputmethod.EditorInfo())
        }
        rule.waitForIdle()
        rule.onNode(hasContentDescription("להפעיל כפה (עכבר)")).assertIsDisplayed()         // the hub is a page: the dock is back
        rule.onNode(hasContentDescription("פתיחת מקלדת")).assertIsDisplayed()
        rule.onNode(hasContentDescription("q")).assertDoesNotExist()
        // Back leaves the hub for the home (the page gets a moment to answer first), and the focus is where it was.
        scenario!!.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
        rule.waitForIdle()
        rule.onNodeWithText("המדף של הערב").assertIsDisplayed()
        rule.onNode(hasContentDescription("הגדרות ועוד").and(inFocus)).assertIsDisplayed()
        rule.onNode(hasContentDescription("להפעיל כפה (עכבר)")).assertDoesNotExist()
        scenario!!.onActivity { a -> assertNull(webOf(a.window.decorView)); assertFalse(a.isFinishing) }
    }

    @Test
    fun theInformationPanelOpensFromTheHomeWithTheRemote() {
        CrashReport.clear(context); Flight.acknowledgePrevious()
        launch()
        remote(android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        rule.onNodeWithText("אם משהו לא עובד", substring = true).assertIsDisplayed()
        rule.onNodeWithText("app ", substring = true).assertIsDisplayed()
        remote(android.view.KeyEvent.KEYCODE_BACK)
        rule.onNodeWithText("אם משהו לא עובד", substring = true).assertDoesNotExist()
        rule.onNode(hasContentDescription("מידע לתיקון תקלות").and(inFocus)).assertIsDisplayed()
    }

    @Test
    fun afterARunThatStoppedWhileInUseTheStartSaysSoAndThenGoesOn() {
        CrashReport.clear(context)
        Flight.drainForTests()
        File(context.filesDir, "session.txt").writeText("front 21:40:00.000 PlayerActivity")
        File(context.filesDir, "flight.txt").writeText("21:39:59.000 MAIN THREAD STUCK for 2600 ms, at:\n    at somewhere")
        Flight.start(context)
        launch()
        rule.onNodeWithText("בפעם הקודמת האפליקציה נעצרה באמצע").assertIsDisplayed()
        rule.onNodeWithText("MAIN THREAD STUCK", substring = true).assertIsDisplayed()
        // One OK on the remote goes on (the button has the focus from the start).
        scenario!!.onActivity { a ->
            a.window.callback.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
            a.window.callback.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
        }
        rule.waitForIdle()
        rule.onNodeWithText("בפעם הקודמת האפליקציה נעצרה באמצע").assertDoesNotExist()
        assertFalse(Flight.previous!!.endedInFront)
        rule.onNodeWithText("המדף של הערב").assertIsDisplayed()                 // and the home is there
    }

    @Test
    fun afterACrashTheStartShowsTheCrash() {
        File(context.filesDir, "last-crash.txt").writeText("crashed\njava.lang.IllegalStateException: boom")
        launch()
        rule.onNodeWithText("האפליקציה קרסה בפעם הקודמת").assertIsDisplayed()
        rule.onNode(hasContentDescription("להמשיך")).performClick(); rule.waitForIdle()
        assertFalse(File(context.filesDir, "last-crash.txt").exists())
    }
}
