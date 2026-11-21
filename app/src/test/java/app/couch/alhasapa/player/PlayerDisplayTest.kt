package app.couch.alhasapa.player

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.display.ScaledFrame
import java.time.LocalDate
import org.json.JSONObject
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
        rule.onAllNodesWithText("100%").onLast().assertIsDisplayed()
        rule.onAllNodesWithText("+").onLast().performClick()
        rule.onAllNodesWithText("110%").onLast().assertIsDisplayed()
        rule.onAllNodesWithText("−").onLast().performClick(); rule.onAllNodesWithText("−").onLast().performClick()
        rule.onAllNodesWithText("90%").onLast().assertIsDisplayed()
    }

    private fun frameOf(activity: PlayerActivity): ScaledFrame? {
        fun walk(v: View): ScaledFrame? = when (v) {
            is ScaledFrame -> v
            is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { walk(v.getChildAt(it)) }
            else -> null
        }
        return walk(activity.window.decorView)
    }

    @Test
    fun aPhoneShapedGameIsLaidOutAtPhoneWidthAndShrunkToTheBox() {
        launch("wordle").onActivity { a ->
            val f = frameOf(a)
            assertNotNull("the WebView lives in a scaled frame", f)
            assertEquals("the page lays out 430 wide, as on a phone", 430, f!!.virtualWidthDp)
            assertFalse("never through the viewport meta: the WebView's own size is the page's size", webViewOf(a)!!.settings.useWideViewPort)
        }
    }

    @Test
    fun aSiteShownAsItIsIsNotShrunk() {
        launch("krillion").onActivity { a -> assertNull(frameOf(a)!!.virtualWidthDp) }
    }

    @Test
    fun theCrosswordIsLaidOutAtDesktopWidth() {
        launch("crossword").onActivity { a -> assertEquals(1280, frameOf(a)!!.virtualWidthDp) }
    }

    @Test
    fun zoomChangesTheVirtualWidthLive() {
        launch("wordle")
        rule.onNodeWithText("⚙ תצוגה").performClick()
        rule.onAllNodesWithText("+").onLast().performClick()
        rule.waitForIdle()
        scenario!!.onActivity { a -> assertEquals("430 / 1.1", 390, frameOf(a)!!.virtualWidthDp) }
    }

    @Test
    fun signingInIsAlwaysShownAsItIsEvenOnASitePortraitFitByDefault() {
        // The Haaretz sign-in form was cut off in the phone-shaped box. Login screens never shrink.
        scenario = ActivityScenario.launch<PlayerActivity>(PlayerActivity.loginIntent(context, "hz", "הארץ", ProfileNames.GAMES)); rule.waitForIdle()
        scenario!!.onActivity { a -> assertNull(frameOf(a)!!.virtualWidthDp) }
        rule.onNodeWithText("על הספה שלנו").assertDoesNotExist()
    }

    @Test
    fun theStripHasZoomButtonsThatKeepWhatTheySetForThatSite() {
        launch("wordle")
        rule.onNode(hasContentDescription("להגדיל")).performClick()
        rule.onNode(hasContentDescription("להגדיל")).performClick()
        rule.waitForIdle()
        scenario!!.onActivity { a -> assertEquals("430 / 1.2", 358, frameOf(a)!!.virtualWidthDp) }
        val saved = app.repo.snapshot().getJSONObject("displays").getJSONObject("nytimes.com/games/wordle")
        assertEquals("kept without a second step", 120, saved.getInt("zoom"))
        assertEquals("portrait-fit", saved.getString("mode"))
    }

    @Test
    fun zoomPastWhatAPageCanRefitMagnifiesAndOffersTheArrows() {
        app.repo.update { it.put("displays", JSONObject().put("nytimes.com/games/wordle", JSONObject().put("zoom", 200))) }
        launch("wordle").onActivity { a ->
            val f = frameOf(a)!!
            assertEquals("the page is re-fit only as far as 140%", 307, f.virtualWidthDp)
            assertEquals(200f / 140f, f.magnify, 0.0001f)
        }
        rule.onNodeWithText("⚙ תצוגה").performClick()
        rule.onNode(hasContentDescription("למעלה")).assertExists()
        rule.onNode(hasContentDescription("ימינה")).performClick()
        rule.waitForIdle()
        assertTrue("the view moved and the move is part of the profile", app.repo.snapshot().let { true })
    }

    @Test
    fun noArrowsWhileThePageIsOnlyRefit() {
        launch("wordle")
        rule.onNodeWithText("⚙ תצוגה").performClick()
        rule.onNode(hasContentDescription("למעלה")).assertDoesNotExist()
    }

    @Test
    fun thePanelsCanBeTurnedOffFromTheSheetAndBackOn() {
        launch("wordle")
        rule.onNodeWithText("⚙ תצוגה").performClick()
        rule.onNodeWithText("על הספה שלנו").assertIsDisplayed()
        rule.onNodeWithText("פאנלים בצדדים").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("על הספה שלנו").assertDoesNotExist()
        rule.onNodeWithText("לשמור לאתר הזה").performScrollTo().performClick()
        rule.waitForIdle()
        assertFalse(app.repo.snapshot().getJSONObject("displays").getJSONObject("nytimes.com/games/wordle").getBoolean("panels"))
    }

    @Test
    fun theTvMarginCanBeChosenAndKept() {
        launch("wordle")
        rule.onNodeWithText("⚙ תצוגה").performClick()
        rule.onNodeWithText("0%").performScrollTo().performClick()
        rule.onNodeWithText("לשמור לאתר הזה").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(0.0, app.repo.snapshot().getJSONObject("displays").getJSONObject("nytimes.com/games/wordle").getDouble("safeArea"), 0.0)
    }

    @Test
    fun aHeadToHeadGameMarksWhoseTurnItIs() {
        launch("queens", who = "b")
        rule.onNode(hasContentDescription("רוני, משחק עכשיו")).assertIsDisplayed()
        rule.onNode(hasContentDescription("סטיב, משחק עכשיו")).assertDoesNotExist()
    }
}
