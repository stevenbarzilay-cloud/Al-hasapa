package app.couch.alhasapa.player

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.data.CouchState
import java.time.LocalDate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The player on the phone held sideways (914 x 411 dp, the Pixel 8): panels, the tuning sheet, saved profiles. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class, qualifiers = "w914dp-h411dp-land")
class PlayerDisplayTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app get() = context as CouchApp
    private var scenario: ActivityScenario<PlayerActivity>? = null

    @After fun close() { scenario?.close() }

    private fun launch(gameId: String, who: String? = null): ActivityScenario<PlayerActivity> =
        ActivityScenario.launch<PlayerActivity>(PlayerActivity.gameIntent(context, gameId, who)).also { scenario = it; rule.waitForIdle() }

    private fun webViewOf(activity: PlayerActivity): WebView? {
        fun walk(v: View): WebView? = when (v) {
            is WebView -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { walk(v.getChildAt(it)) }
            else -> null
        }
        return walk(activity.window.decorView)
    }

    @Test
    fun aPhoneShapedGameShowsTheCouchPanelsAtItsSides() {
        launch("wordle")
        rule.onNodeWithText("על הספה שלנו").assertIsDisplayed()                // identity panel
        rule.onNodeWithText("0").assertIsDisplayed()                            // played tonight
        rule.onNodeWithText("0 נקודות ספה").assertIsDisplayed()
        rule.onNodeWithText("סטיב").assertIsDisplayed()
        rule.onNodeWithText("רוני").assertIsDisplayed()
    }

    @Test
    fun theProgressPanelFollowsTheEvening() {
        val today = CouchState.dayKey(LocalDate.now())
        app.repo.update { CouchState.setEntry(it, today, "connections", JSONObject().put("w", "").put("t", 1_800_000_000_000L)) }
        launch("wordle")
        rule.onNodeWithText("1").assertIsDisplayed()
        // ...and it follows a tick made right here.
        rule.onNodeWithText("שיחקנו").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun aSiteShownAsItIsHasNoPanels() {
        launch("krillion")
        rule.onNodeWithText("על הספה שלנו").assertDoesNotExist()
        rule.onNodeWithText("0 נקודות ספה").assertDoesNotExist()
    }

    @Test
    fun aPersonalOverrideChangesHowTheSiteIsShown() {
        app.repo.update { it.put("displays", JSONObject().put("nytimes.com/games/wordle", JSONObject().put("mode", "native"))) }
        launch("wordle")
        rule.onNodeWithText("על הספה שלנו").assertDoesNotExist()
    }

    @Test
    fun theTuningSheetChangesTheViewLiveAndOnlyKeepsWhatIsSaved() {
        launch("wordle")
        rule.onNodeWithText("⚙ תצוגה").performClick()
        rule.onNodeWithText("רגיל").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("על הספה שלנו").assertDoesNotExist()                  // live: the panels went away at once
        assertNull("nothing is kept until it is saved", app.repo.snapshot().optJSONObject("displays"))
        rule.onNodeWithText("לשמור לאתר הזה").performClick()
        rule.waitForIdle()
        val saved = app.repo.snapshot().getJSONObject("displays").getJSONObject("nytimes.com/games/wordle")
        assertEquals("native", saved.getString("mode"))
        rule.onNodeWithText("✓ נשמר").assertIsDisplayed()
        // The built-in way back:
        rule.onNodeWithText("לחזור לברירת המחדל").performClick()
        rule.waitForIdle()
        assertFalse(app.repo.snapshot().getJSONObject("displays").has("nytimes.com/games/wordle"))
        rule.onNodeWithText("על הספה שלנו").assertIsDisplayed()
    }

    @Test
    fun zoomStepsAndIsKeptWithinItsRange() {
        launch("wordle")
        rule.onNodeWithText("⚙ תצוגה").performClick()
        rule.onNodeWithText("100%").assertIsDisplayed()
        rule.onNodeWithText("+").performClick()
        rule.onNodeWithText("105%").assertIsDisplayed()
        rule.onNodeWithText("−").performClick(); rule.onNodeWithText("−").performClick()
        rule.onNodeWithText("95%").assertIsDisplayed()
    }

    @Test
    fun theWebViewIsToldToHonourThePagesViewportOnlyWhenTheProfileAsksForIt() {
        launch("wordle").onActivity { a ->
            assertTrue("portrait-fit lays the page out at the virtual width", webViewOf(a)!!.settings.useWideViewPort)
        }
        scenario!!.close()
        launch("krillion").onActivity { a ->
            assertFalse("native leaves the page alone", webViewOf(a)!!.settings.useWideViewPort)
        }
    }

    @Test
    fun aHeadToHeadGameMarksWhoseTurnItIs() {
        launch("queens", who = "b")
        rule.onNode(hasContentDescription("רוני, משחק עכשיו")).assertIsDisplayed()
        rule.onNode(hasContentDescription("סטיב, משחק עכשיו")).assertDoesNotExist()
    }
}
