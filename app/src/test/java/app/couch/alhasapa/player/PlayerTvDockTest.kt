package app.couch.alhasapa.player

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.R
import app.couch.alhasapa.data.CouchState
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The game's frame on a television: ONE dock at the left edge (clock, score, zoom, shape, frame, paw, keyboard, menu, back, result, "we played"). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class, qualifiers = "w960dp-h540dp-television-xhdpi")
class PlayerTvDockTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app get() = context as CouchApp
    private var scenario: ActivityScenario<PlayerActivity>? = null

    @After fun close() { scenario?.close() }

    private fun launch(gameId: String = "wordle") {
        scenario = ActivityScenario.launch(PlayerActivity.gameIntent(context, gameId, null)); rule.waitForIdle()
    }

    private fun button(desc: String) = rule.onNode(hasContentDescription(desc))
    private fun str(id: Int) = context.getString(id)
    private fun slimSaved() = context.getSharedPreferences("tv", Context.MODE_PRIVATE).getBoolean("slim", false)

    @Test
    fun theDockCarriesTheClockTheScoreAndEveryControlTheGameNeeds() {
        launch()
        rule.onNodeWithText(context.getString(R.string.panel_points_short, 0)).assertIsDisplayed()
        rule.onNodeWithText("100%").assertIsDisplayed()
        for (id in listOf(R.string.display_zoom_in, R.string.display_zoom_out, R.string.bar_mode_desc, R.string.tv_frame_slim, R.string.tv_paw_off, R.string.tv_keyboard_open, R.string.menu_open, R.string.player_back))
            button(str(id)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.player_result)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.player_played)).assertIsDisplayed()
        // ...and the phone's bars and side panels are not drawn around the game.
        rule.onNodeWithText("על הספה שלנו").assertDoesNotExist()
    }

    @Test
    fun zoomIsOnePressAwayAndIsKeptForTheSite() {
        launch()
        button(str(R.string.display_zoom_in)).performClick(); rule.waitForIdle()
        rule.onNodeWithText("110%").assertIsDisplayed()
        button(str(R.string.display_zoom_out)).performClick(); button(str(R.string.display_zoom_out)).performClick(); rule.waitForIdle()
        rule.onNodeWithText("90%").assertIsDisplayed()
        assertNotNull("kept for this site without a second step", app.repo.snapshot().optJSONObject("displays"))
    }

    @Test
    fun weAreDoneWithOnePressAndItCanBeTakenBack() {
        launch()
        val today = CouchState.dayKey(LocalDate.now())
        assertNull(CouchState.entry(app.repo.snapshot(), today, "wordle"))
        rule.onNodeWithText(str(R.string.player_played)).performClick(); rule.waitForIdle()
        assertNotNull(CouchState.entry(app.repo.snapshot(), today, "wordle"))
        rule.onNodeWithText("✓ ${str(R.string.player_played)}").assertIsDisplayed()
        rule.onNodeWithText("✓ ${str(R.string.player_played)}").performClick(); rule.waitForIdle()
        assertNull(CouchState.entry(app.repo.snapshot(), today, "wordle"))
    }

    @Test
    fun theFrameCanBeMadeSlimAndTheChoiceIsRemembered() {
        launch()
        assertFalse(slimSaved())
        button(str(R.string.tv_frame_slim)).performClick(); rule.waitForIdle()
        assertTrue("remembered for the next time", slimSaved())
        // Slim keeps only the controls: the words and the score go, the buttons (and zoom) stay.
        rule.onNodeWithText(str(R.string.player_played)).assertDoesNotExist()
        rule.onNodeWithText("100%").assertDoesNotExist()
        button(str(R.string.tv_frame_full)).assertIsDisplayed()
        button(str(R.string.display_zoom_in)).assertIsDisplayed()
        button(str(R.string.player_back)).assertIsDisplayed()
        button(str(R.string.tv_frame_full)).performClick(); rule.waitForIdle()
        rule.onNodeWithText(str(R.string.player_played)).assertIsDisplayed()
        assertFalse(slimSaved())
    }

    @Test
    fun theSwitchBetweenPhoneShapedAndWideIsInTheDock() {
        launch()
        val before = app.repo.snapshot().optJSONObject("displays")?.toString()
        button(str(R.string.bar_mode_desc)).performClick(); rule.waitForIdle()
        assertTrue("the shape was saved for the site", app.repo.snapshot().optJSONObject("displays")?.toString() != before)
    }

    @Test
    fun theDockShowsTheTimeOfDayNotHowLongTheGameTook() {
        launch()
        val now = Clock.now()
        rule.onNode(hasContentDescription("השעה $now")).assertIsDisplayed()
        rule.onNodeWithText("0:00").assertDoesNotExist()
    }

    @Test
    fun aGameCanHaveTheWholeScreenAndBackBringsTheFrameBack() {
        context.getSharedPreferences("tv", Context.MODE_PRIVATE).edit().clear().commit()
        launch()
        button(str(R.string.tv_frame_hide)).performClick(); rule.waitForIdle()
        button(str(R.string.tv_paw_off)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.player_played)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.tv_hint_frame_hidden)).assertIsDisplayed()
        assertTrue("remembered for this game", context.getSharedPreferences("tv", Context.MODE_PRIVATE).getBoolean("frame-hidden:wordle", false))
        // Back: the frame comes back and the game stays open.
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        button(str(R.string.tv_paw_off)).assertIsDisplayed()
        scenario!!.onActivity { assertFalse(it.isFinishing) }
        assertFalse(context.getSharedPreferences("tv", Context.MODE_PRIVATE).getBoolean("frame-hidden:wordle", true))
        // Hidden again and the game closed: the next time it opens with the whole screen.
        button(str(R.string.tv_frame_hide)).performClick(); rule.waitForIdle()
        scenario!!.close()
        launch()
        button(str(R.string.tv_paw_off)).assertDoesNotExist()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        button(str(R.string.tv_paw_off)).assertIsDisplayed()
    }

    @Test
    fun theTvsSettingsOpenFromTheDockAndAreKept() {
        context.getSharedPreferences("tv", Context.MODE_PRIVATE).edit().clear().commit()
        launch()
        button(str(R.string.tv_settings_open)).performClick(); rule.waitForIdle()
        rule.onNodeWithText(str(R.string.tvset_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.tvset_picture)).assertIsDisplayed()
        rule.onNode(hasContentDescription("עכבר")).performClick(); rule.waitForIdle()
        rule.onNode(hasContentDescription("קטן")).performClick(); rule.waitForIdle()
        val prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
        org.junit.Assert.assertEquals("mouse", prefs.getString("cursor", null))
        org.junit.Assert.assertEquals("s", prefs.getString("cursor_size", null))
    }

    @Test
    fun theBackButtonLeavesTheGame() {
        launch()
        button(str(R.string.player_back)).performClick(); rule.waitForIdle()
        scenario!!.onActivity { assertTrue(it.isFinishing) }
    }
}
