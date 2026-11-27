package app.couch.alhasapa.player

import app.couch.alhasapa.TestCouch
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
import app.couch.alhasapa.tv.Delivery
import app.couch.alhasapa.remote.TypedKeys
import app.couch.alhasapa.results.SAVE_ID
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
import app.couch.alhasapa.tv.NOTE_OK_ID
import app.couch.alhasapa.data.Notes
import app.couch.alhasapa.data.Note
import app.couch.alhasapa.data.Person
import app.couch.alhasapa.data.Couple

/** The game's frame on a television: ONE dock at the left edge (clock, score, zoom, shape, frame, paw, keyboard, menu, back, result, "we played"). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class, qualifiers = "w960dp-h540dp-television-xhdpi")
class PlayerTvDockTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app get() = context as CouchApp
    private var scenario: ActivityScenario<PlayerActivity>? = null

    @org.junit.Before fun ourCouch() { TestCouch.ours() }
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
    fun theResultIsSavedWithTheRemoteOrThePhonesKeysAndCelebrated() {
        launch("catfishing")
        val today = CouchState.dayKey(LocalDate.now())
        button(str(R.string.player_result)).performClick()
        rule.mainClock.advanceTimeBy(2_000); rule.waitForIdle()
        // No text came from the page here: the sheet asks for the number, with buttons; the focus is on "save".
        rule.onNodeWithText(str(R.string.result_tv_no_text)).assertIsDisplayed()
        scenario!!.onActivity { org.junit.Assert.assertEquals(SAVE_ID, it.remote.focusedId) }
        // What the phone types goes into the number; Enter saves.
        scenario!!.onActivity { it.remote.remoteInput(TypedKeys.deliveries("8/10")) }
        rule.waitForIdle()
        rule.onNodeWithText("8/10", substring = true).assertIsDisplayed()
        scenario!!.onActivity { it.remote.remoteInput(listOf(Delivery.Code(android.view.KeyEvent.KEYCODE_ENTER))) }
        rule.waitForIdle()
        val r = CouchState.entry(app.repo.snapshot(), today, "catfishing")!!.getJSONObject("r")
        org.junit.Assert.assertEquals(8.0, r.getDouble("v"), 0.0); org.junit.Assert.assertEquals(10, r.getInt("max")); org.junit.Assert.assertEquals("score", r.getString("kind"))
        // The celebration: the points and a word about them.
        rule.onNodeWithText(context.getString(R.string.cheer_points, 10)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.cheer_great)).assertIsDisplayed()
        scenario!!.onActivity { org.junit.Assert.assertNull("the sheet let go of the keys", it.remote.textSink) }
    }

    @Test
    fun aNoteLeftForTheGameShowsAfterItsResultIsCelebratedAndIsReadOnce() {
        app.repo.update { st ->
            st.remove("notes")
            Notes.add(st, Note("k1", "b", "catfishing", "ידעתי שתנצחו את הדגים", listOf(intArrayOf(0, 500, 1000, 500)), at = 1_000))
        }
        launch("catfishing")
        button(str(R.string.player_result)).performClick()
        rule.mainClock.advanceTimeBy(2_000); rule.waitForIdle()
        scenario!!.onActivity { it.remote.remoteInput(TypedKeys.deliveries("9/10")) }
        scenario!!.onActivity { it.remote.remoteInput(listOf(Delivery.Code(android.view.KeyEvent.KEYCODE_ENTER))) }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.cheer_great)).assertIsDisplayed()
        rule.onNodeWithText("ידעתי שתנצחו את הדגים").assertDoesNotExist()
        // The celebration ends, and the note follows, from Roni to Steve, with the focus on its button.
        rule.mainClock.advanceTimeBy(3_500); rule.waitForIdle()
        rule.onNodeWithText("ידעתי שתנצחו את הדגים").assertIsDisplayed()
        rule.onNodeWithText("פתק מרוני לסטיב").assertIsDisplayed()
        scenario!!.onActivity { org.junit.Assert.assertEquals(NOTE_OK_ID, it.remote.focusedId) }
        scenario!!.onActivity { it.remote.activate() }
        rule.waitForIdle()
        rule.onNodeWithText("ידעתי שתנצחו את הדגים").assertDoesNotExist()
        assertTrue(Notes.all(app.repo.snapshot()).single().seen > 0)
        app.repo.update { st -> st.remove("notes") }
    }

    @Test
    fun aGuestOfTheEveningCanBeTheOneWhoCrackedIt() {
        val day = CouchState.dayKey(LocalDate.now())
        app.repo.update { st ->
            Couple.put(st, Person("u5d6e7f8", "מיה", "heart", "mustard", 5, guest = true, day = Couple.evening()))
            Couple.put(st, Person("u9a9a9a9", "של אתמול", "", "", 6, guest = true, day = "2001-01-01"))
        }
        launch("catfishing")
        button(str(R.string.player_result)).performClick()
        rule.mainClock.advanceTimeBy(2_000); rule.waitForIdle()
        button("מיה").assertIsDisplayed()
        button("של אתמול").assertDoesNotExist()
        button("מיה").performClick()
        scenario!!.onActivity { it.remote.remoteInput(TypedKeys.deliveries("7/10")) }
        scenario!!.onActivity { it.remote.remoteInput(listOf(Delivery.Code(android.view.KeyEvent.KEYCODE_ENTER))) }
        rule.waitForIdle()
        org.junit.Assert.assertEquals("u5d6e7f8", CouchState.entry(app.repo.snapshot(), day, "catfishing")!!.getString("w"))
        app.repo.update { st -> st.remove("people") }
    }

    @Test
    fun aGameStayedInIsMarkedAsPlayedByItself() {
        launch("wordle")
        val today = CouchState.dayKey(LocalDate.now())
        rule.mainClock.advanceTimeBy(10_000); rule.waitForIdle()
        assertNull("not after ten seconds", CouchState.entry(app.repo.snapshot(), today, "wordle"))
        rule.mainClock.advanceTimeBy(25_000); rule.waitForIdle()
        assertNotNull("after half a minute in it", CouchState.entry(app.repo.snapshot(), today, "wordle"))
        rule.onNodeWithText("✓ ${str(R.string.player_played)}").assertIsDisplayed()
    }

    @Test
    fun aTickByHandIsNotUndoneByTheGameMarkingItself() {
        launch("wordle")
        val today = CouchState.dayKey(LocalDate.now())
        rule.onNodeWithText(str(R.string.player_played)).performClick(); rule.waitForIdle()
        rule.onNodeWithText("✓ ${str(R.string.player_played)}").performClick(); rule.waitForIdle()
        rule.mainClock.advanceTimeBy(40_000); rule.waitForIdle()
        assertNull("unticked by hand: it stays unticked", CouchState.entry(app.repo.snapshot(), today, "wordle"))
    }

    @Test
    fun theBackButtonLeavesTheGame() {
        launch()
        button(str(R.string.player_back)).performClick(); rule.waitForIdle()
        scenario!!.onActivity { assertTrue(it.isFinishing) }
    }
}
