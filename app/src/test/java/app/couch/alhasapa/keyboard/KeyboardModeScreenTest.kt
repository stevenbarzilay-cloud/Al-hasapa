package app.couch.alhasapa.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.remote.KeyClient
import app.couch.alhasapa.remote.KeyLink
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Keyboard mode on the phone, screen by screen, with a fake link to the TV. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], qualifiers = "w411dp-h914dp")
class KeyboardModeScreenTest {
    @get:Rule val rule = createComposeRule()
    private val sent = ArrayList<String>()
    private val sink = object : KeySink {
        override fun text(s: String) { sent += "text:$s" }
        override fun key(k: KeyLink.Key, a: KeyLink.Action) { sent += "key:$k:$a" }
        override fun code(c: String) { sent += "code:$c" }
        override fun retry() { sent += "retry" }
        override fun address(a: String) { sent += "address:$a" }
        override fun permission() { sent += "permission" }
    }
    private var state by mutableStateOf<KeyClient.State>(KeyClient.State.Looking)
    private var game = 0

    private fun show() = rule.setContent { AlHasapaTheme { KeyboardModeScreen(state, sink, onGame = { game++ }) } }

    @Test
    fun theSwitchAtTheTopGoesBackToPlaying() {
        show()
        rule.onNodeWithText("מקלדת לטלוויזיה").assertIsDisplayed()
        rule.onNodeWithText("משחק").performClick()
        assertEquals(1, game)
    }

    @Test
    fun itSaysWhatItIsDoingOnTheWayToTheTv() {
        show()
        rule.onNodeWithText("מחפשים את הטלוויזיה בבית…").assertIsDisplayed()
        state = KeyClient.State.Connecting("192.168.1.23:47123"); rule.waitForIdle()
        rule.onNodeWithText("מתחברים לטלוויזיה…").assertIsDisplayed()
        state = KeyClient.State.NotFound(KeyClient.NO_TV); rule.waitForIdle()
        rule.onNodeWithText("לא מצאנו את הטלוויזיה").assertIsDisplayed()
        rule.onNodeWithText("לחפש שוב").performClick()
        rule.onNodeWithText("להקליד כתובת").performClick()
        rule.onNodeWithText("להתחבר").performClick()
        assertEquals(listOf("retry", "address:"), sent)
        state = KeyClient.State.NotFound(KeyClient.NO_PERMISSION); rule.waitForIdle()
        rule.onNodeWithText("לאשר").performClick()
        assertEquals("permission", sent.last())
    }

    @Test
    fun theFourDigitsGoOutAsSoonAsTheyAreTyped() {
        state = KeyClient.State.NeedCode("MiBOX4"); show()
        rule.onNodeWithText("בטלוויזיה מופיע קוד של 4 ספרות").assertIsDisplayed()
        rule.onNode(hasContentDescription("קוד")).performTextInput("4821")
        rule.waitForIdle()
        assertEquals(listOf("code:4821"), sent)
        state = KeyClient.State.NeedCode("MiBOX4", left = 2); rule.waitForIdle()
        rule.onNodeWithText("הקוד לא נכון. נשארו 2 ניסיונות.").assertIsDisplayed()
    }

    @Test
    fun connectedItShowsTheGameTheKeysAndTheEnterThePawPresses() {
        state = KeyClient.State.Connected("MiBOX4", "Krillion"); show()
        rule.onNodeWithText("Krillion").assertIsDisplayed()
        rule.onNodeWithText("מה שמקלידים כאן נכתב בטלוויזיה").assertIsDisplayed()
        rule.onNode(hasContentDescription("אנטר (הכפה לוחצת)")).performClick()
        rule.onNode(hasContentDescription("Esc")).performClick()
        rule.onNode(hasContentDescription("Tab")).performClick()
        rule.waitForIdle()
        assertEquals(listOf("key:ENTER:TAP", "key:ESCAPE:TAP", "key:TAB:TAP"), sent)
        rule.onNode(hasContentDescription("חץ שמאלה")).performClick()
        rule.waitForIdle()
        assertTrue("an arrow is held while pressed: down, then up", sent.takeLast(2) == listOf("key:LEFT:DOWN", "key:LEFT:UP"))
    }
}
