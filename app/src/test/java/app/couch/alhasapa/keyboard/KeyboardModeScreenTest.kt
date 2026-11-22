package app.couch.alhasapa.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
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
        override fun pointer(m: KeyLink.Msg) { sent += when (m) {
            is KeyLink.Msg.Pointer -> "pointer:${m.on}"
            is KeyLink.Msg.Move -> "move:" + (if (m.dx > 0) "right" else if (m.dx < 0) "left" else "-") + (if (m.dy > 0) "down" else if (m.dy < 0) "up" else "")
            KeyLink.Msg.Click -> "click"
            is KeyLink.Msg.Drag -> "drag:${m.on}"
            is KeyLink.Msg.Scroll -> "scroll:" + (if (m.dy > 0) "down" else "up")
            else -> "?"
        } }
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

    @Test
    fun theTouchpadIsTuckedAwayUntilPulledOutAndDrivesThePaw() {
        state = KeyClient.State.Connected("MiBOX4", "Krillion"); show()
        val tab = hasContentDescription("משטח מגע לכפה: גוררים מהקצה הימני או נוגעים כדי לפתוח")
        rule.onNode(tab).assertIsDisplayed()
        rule.onNodeWithText("משטח מגע").assertIsNotDisplayed()
        rule.onNode(tab).performClick(); rule.waitForIdle()
        assertEquals(listOf("pointer:true"), sent)
        rule.onNodeWithText("משטח מגע").assertIsDisplayed()
        val pad = rule.onNode(hasContentDescription("מזיזים את הכפה עם אצבע אחת · נגיעה קצרה לוחצת · שתי אצבעות גוללות"))
        sent.clear()
        pad.performTouchInput { click(center) }
        assertEquals("a short touch is a click", listOf("click"), sent)
        sent.clear()
        pad.performTouchInput { down(center); repeat(6) { moveBy(Offset(30f, 10f), delayMillis = 20) }; up() }
        assertTrue("a swipe moves the paw that way: $sent", sent.isNotEmpty() && sent.all { it == "move:rightdown" })
        sent.clear()
        pad.performTouchInput {
            down(0, center); down(1, center + Offset(60f, 0f))
            repeat(5) { moveBy(0, Offset(0f, 25f), delayMillis = 20); moveBy(1, Offset(0f, 25f), delayMillis = 0) }
            up(0); up(1)
        }
        assertTrue("two fingers scroll: $sent", sent.isNotEmpty() && sent.all { it == "scroll:down" })
        sent.clear()
        rule.onNode(hasContentDescription("גרירה")).performClick()
        rule.onNode(hasContentDescription("גוררים · לחיצה משחררת")).assertIsDisplayed()
        rule.onNode(hasContentDescription("סגירת משטח המגע")).performClick(); rule.waitForIdle()
        assertEquals("closing lifts the finger and puts the paw away", listOf("drag:true", "drag:false", "pointer:false"), sent)
        rule.onNodeWithText("משטח מגע").assertIsNotDisplayed()
    }

    @Test
    fun theTouchpadIsOnlyThereWhenConnected() {
        state = KeyClient.State.Looking; show()
        rule.onNode(hasContentDescription("משטח מגע לכפה: גוררים מהקצה הימני או נוגעים כדי לפתוח")).assertDoesNotExist()
    }
}
