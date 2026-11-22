package app.couch.alhasapa.remote

import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.player.PlayerActivity
import app.couch.alhasapa.tv.Delivery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** On the TV: what a phone types goes into the game in front; the code to pair is on screen; a connected phone shows in the dock. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], application = CouchApp::class, qualifiers = "w960dp-h540dp-television-xhdpi")
class RemoteKeysTvTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<PlayerActivity>? = null

    @After fun close() { scenario?.close(); RemoteKeys.ui.value = RemoteKeys.Ui() }

    private fun launch() { scenario = ActivityScenario.launch(PlayerActivity.gameIntent(context, "krillion", null)); rule.waitForIdle() }

    @Test
    fun whatThePhoneTypesGoesIntoTheGameInFrontNamedForThePhone() {
        launch()
        val front = RemoteKeys.front
        assertNotNull("the game's screen is the one the phone types into", front)
        assertEquals("Krillion", front!!.screenTitle)
        val got = ArrayList<Delivery>()
        front.typer = { got += it }
        RemoteKeys.deliver(KeyLink.Msg.Text("Hi"))
        RemoteKeys.deliver(KeyLink.Msg.Press(KeyLink.Key.ENTER, KeyLink.Action.TAP))
        RemoteKeys.deliver(KeyLink.Msg.Press(KeyLink.Key.UP, KeyLink.Action.DOWN))
        assertEquals(listOf(Delivery.Code(KeyEvent.KEYCODE_H, true), Delivery.Code(KeyEvent.KEYCODE_I), Delivery.Code(KeyEvent.KEYCODE_ENTER), Delivery.Hold(KeyEvent.KEYCODE_DPAD_UP, true)), got)
        scenario!!.close(); scenario = null
        assertNull("a closed screen gets nothing", RemoteKeys.front)
    }

    @Test
    fun theCodeToPairIsShownLargeAndAConnectedPhoneShowsInTheDock() {
        launch()
        RemoteKeys.ui.value = RemoteKeys.Ui(listening = true, code = "4821", codeFor = "Pixel 8")
        rule.waitForIdle()
        rule.onNode(hasContentDescription("4821")).assertIsDisplayed()
        rule.onNodeWithText("טלפון רוצה להקליד כאן (Pixel 8)").assertIsDisplayed()
        RemoteKeys.ui.value = RemoteKeys.Ui(listening = true, phones = listOf("Pixel 8"))
        rule.waitForIdle()
        rule.onNode(hasContentDescription("4821")).assertDoesNotExist()
        rule.onNode(hasContentDescription("טלפון מחובר כמקלדת")).assertIsDisplayed()
        rule.onNodeWithText("הטלפון מחובר כמקלדת. מה שמקלידים בו נכתב כאן").assertIsDisplayed()
    }

    @Test
    fun theInformationPanelSaysWhereThePhoneCanFindTheTv() {
        RemoteKeys.ui.value = RemoteKeys.Ui(listening = true, address = "192.168.1.23:47123", phones = listOf("Pixel 8"))
        val t = app.couch.alhasapa.diag.Diagnostics.text(context)
        assertTrue(t.contains("192.168.1.23:47123")); assertTrue(t.contains("Pixel 8"))
    }
}
