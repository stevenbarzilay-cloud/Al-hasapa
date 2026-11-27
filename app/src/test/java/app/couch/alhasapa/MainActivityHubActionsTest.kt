package app.couch.alhasapa

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.accounts.AccountsActivity
import app.couch.alhasapa.diag.CrashReport
import app.couch.alhasapa.diag.Flight
import app.couch.alhasapa.keyboard.KeyboardModeActivity
import app.couch.alhasapa.player.PlayerActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What the hub's page asks of the app when a game, the accounts or keyboard mode is pressed. Nothing covered this path before, and
 * 1.9.0 shipped with it broken: every game opened from the hub crashed the app (the function that opens a game called itself until the
 * stack ran out). These press exactly what the page's bridge presses.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class)
class MainActivityHubActionsTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun noReportOfAPreviousRun() { CrashReport.clear(context); Flight.acknowledgePrevious() }
    @After fun close() { scenario?.close() }

    private fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java).also { scenario = it; rule.waitForIdle() }

    private val today get() = app.couch.alhasapa.data.CouchState.dayKey(java.time.LocalDate.now())

    @Test
    fun aGamePressedInTheHubOpensInThePlayer() {
        val s = launch()
        s.onActivity { a -> a.hubActions.play("wordle"); shadowOf(Looper.getMainLooper()).idle() }
        if (!BuildConfig.TV) {
            // On a phone (1.11): together or alone, first; together opens the couch's game as always.
            s.onActivity { a -> assertNull("not before the answer", shadowOf(a).nextStartedActivity) }
            rule.waitForIdle()
            rule.onNodeWithText("ביחד או לבד?").assertIsDisplayed()
            rule.onNode(androidx.compose.ui.test.hasTestTag(app.couch.alhasapa.ui.PLAY_TOGETHER_TAG)).performClick()
            rule.waitForIdle()
        }
        s.onActivity { a ->
            val started = shadowOf(a).nextStartedActivity
            assertEquals(PlayerActivity::class.java.name, started.component!!.className)
            assertEquals("wordle", started.getStringExtra(PlayerActivity.EXTRA_GAME))
            assertTrue("together", !started.getBooleanExtra(PlayerActivity.EXTRA_SOLO, false))
            assertNull("once", shadowOf(a).nextStartedActivity)
            // Asked once a game and a day: the next time it opens straight away.
            a.hubActions.play("wordle")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("wordle", shadowOf(a).nextStartedActivity.getStringExtra(PlayerActivity.EXTRA_GAME))
        }
    }

    /** 1.11: alone, in the person's own corner; the question once; who plays is asked first when the phone does not know. */
    @Test
    fun aGamePlayedAloneOpensForWhoeverHoldsThePhoneAndIsAskedOnce() {
        org.junit.Assume.assumeFalse("the phone's home only", BuildConfig.TV)
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val store = app.couch.alhasapa.remote.RemoteStore(context)
        store.me = null
        // Someone already played Dialed alone today: the sheet says so.
        (context as CouchApp).repo.update { st ->
            app.couch.alhasapa.data.Solo.put(st, today, "dialed", app.couch.alhasapa.data.SoloSide("a", 41.0, null, false, "score", System.currentTimeMillis() - 1000))
        }
        val s = launch()
        s.onActivity { a -> a.hubActions.play("dialed"); shadowOf(Looper.getMainLooper()).idle() }
        rule.waitForIdle()
        rule.onNodeWithText("סטיב כבר שיחק את זה היום לבד").assertIsDisplayed()
        rule.onNodeWithText("מקבלים את האתגר").assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.hasTestTag(app.couch.alhasapa.ui.PLAY_SOLO_TAG)).performClick()
        rule.waitForIdle()
        // Nobody holds the phone yet: who plays is asked first, then the game opens for them, alone.
        rule.onNodeWithText("מי מחזיק את הטלפון הזה?").assertIsDisplayed()
        rule.onNode(hasContentDescription("רוני")).performClick()
        rule.onNodeWithText("שמירה").performClick(); rule.waitForIdle()
        s.onActivity { a ->
            shadowOf(Looper.getMainLooper()).idle()
            val started = shadowOf(a).nextStartedActivity
            assertEquals("dialed", started.getStringExtra(PlayerActivity.EXTRA_GAME))
            assertEquals("b", started.getStringExtra(PlayerActivity.EXTRA_WHO))
            assertTrue(started.getBooleanExtra(PlayerActivity.EXTRA_SOLO, false))
            // The same game again today: alone, straight away. The challenge from the home's card: alone, no question.
            a.hubActions.play("dialed"); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(a).nextStartedActivity.getBooleanExtra(PlayerActivity.EXTRA_SOLO, false))
            a.hubActions.playSolo("bandle"); shadowOf(Looper.getMainLooper()).idle()
            val b = shadowOf(a).nextStartedActivity
            assertEquals("bandle", b.getStringExtra(PlayerActivity.EXTRA_GAME)); assertTrue(b.getBooleanExtra(PlayerActivity.EXTRA_SOLO, false))
        }
        assertEquals("solo", app.couch.alhasapa.remote.RelayStore(context).mode(today, "dialed"))
        store.me = null
        (context as CouchApp).repo.update { st -> st.remove("solo") }
    }

    /** 1.11: played elsewhere (a computer): its result typed in, alone, without opening the game. */
    @Test
    fun aGamePlayedElsewhereOpensItsResultAlone() {
        org.junit.Assume.assumeFalse("the phone's home only", BuildConfig.TV)
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val store = app.couch.alhasapa.remote.RemoteStore(context)
        store.me = "a"
        val s = launch()
        s.onActivity { a -> a.hubActions.play("krillion"); shadowOf(Looper.getMainLooper()).idle() }
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasTestTag(app.couch.alhasapa.ui.PLAY_ELSEWHERE_TAG)).performClick()
        rule.waitForIdle()
        s.onActivity { a ->
            val started = shadowOf(a).nextStartedActivity
            assertEquals(app.couch.alhasapa.results.ShareReceiverActivity::class.java.name, started.component!!.className)
            assertEquals("krillion", started.getStringExtra(app.couch.alhasapa.results.ShareReceiverActivity.EXTRA_GAME))
            assertTrue(started.getBooleanExtra(app.couch.alhasapa.results.ShareReceiverActivity.EXTRA_SOLO, false))
        }
        store.me = null
    }

    @Test
    fun andFromThePagesOwnThreadAsThePageReallyCalls() {
        // Together already chosen today (on a phone the question comes first otherwise).
        app.couch.alhasapa.remote.RelayStore(context).setMode(today, "connections", "together")
        val s = launch()
        val called = CountDownLatch(1)
        s.onActivity { a -> Thread({ a.hubActions.play("connections"); called.countDown() }, "JavaBridge").start() }
        assertTrue(called.await(5, TimeUnit.SECONDS))
        s.onActivity { a ->
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("connections", shadowOf(a).nextStartedActivity.getStringExtra(PlayerActivity.EXTRA_GAME))
        }
    }

    @Test
    fun aHeadToHeadGameAsksWhoPlaysFirstAndThenOpensForThatPerson() {
        val s = launch()
        s.onActivity { a ->
            a.hubActions.play("queens")
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("not opened yet: whose turn is it?", shadowOf(a).nextStartedActivity)
        }
        rule.waitForIdle()
        rule.onNodeWithText("מי משחק?").assertIsDisplayed()
        rule.onNode(hasContentDescription("משחק: רוני")).performClick()
        rule.waitForIdle()
        s.onActivity { a ->
            val started = shadowOf(a).nextStartedActivity
            assertEquals("queens", started.getStringExtra(PlayerActivity.EXTRA_GAME))
            assertEquals("b", started.getStringExtra(PlayerActivity.EXTRA_WHO))
        }
    }

    @Test
    fun aGameThatIsNotInTheCatalogueOpensNothing() {
        launch().onActivity { a ->
            a.hubActions.play("no-such-game")
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(a).nextStartedActivity)
        }
    }

    @Test
    fun theAccountsAndKeyboardModeOpen() {
        launch().onActivity { a ->
            a.hubActions.openAccounts()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(AccountsActivity::class.java.name, shadowOf(a).nextStartedActivity.component!!.className)
            a.hubActions.openKeyboardMode()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(KeyboardModeActivity::class.java.name, shadowOf(a).nextStartedActivity.component!!.className)
        }
    }

    /** On the phone's home (1.10): who holds the phone, a guest added for the evening, and a note left from there for the TV. */
    @Test
    fun whoHoldsThePhoneAndANoteAreOnTheHomeToo() {
        org.junit.Assume.assumeFalse("the phone's home only: the TV has its own", app.couch.alhasapa.BuildConfig.TV)
        val store = app.couch.alhasapa.remote.RemoteStore(context)
        store.me = null
        store.pendingNotes().forEach { store.noteKept(it.id) }
        val scenario = launch()
        // A note with nobody holding the phone yet: who holds it is asked first.
        scenario.onActivity { it.hubActions.openNote() }
        shadowOf(Looper.getMainLooper()).idle(); rule.waitForIdle()
        rule.onNodeWithText("מי מחזיק את הטלפון הזה?").assertIsDisplayed()
        // A guest for this evening: named, and saved as the one who holds this phone.
        rule.onNodeWithText("+ אורח").performClick()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("מיה")
        rule.onNodeWithText("שמירה").performClick(); rule.waitForIdle()
        val me = store.me!!
        val repo = (context as CouchApp).repo
        val maya = app.couch.alhasapa.data.Couple.person(repo.snapshot(), me)
        assertEquals("מיה", maya.name); assertTrue(maya.guest)
        assertEquals(app.couch.alhasapa.data.Couple.evening(), maya.day)
        // Now the note: for one of the two of us, kept on the phone until the TV is reached.
        scenario.onActivity { it.hubActions.openNote() }
        shadowOf(Looper.getMainLooper()).idle(); rule.waitForIdle()
        rule.onNodeWithText("פתק לסטיב").assertIsDisplayed()
        rule.onNode(hasContentDescription("רוני")).performClick()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("תודה על הערב")
        rule.onNodeWithText("שליחה לטלוויזיה").performClick(); rule.waitForIdle()
        val kept = store.pendingNotes().single()
        assertEquals(me, kept.from); assertEquals("b", kept.to); assertEquals("תודה על הערב", kept.text)
        store.pendingNotes().forEach { store.noteKept(it.id) }
        store.me = null
    }
}
