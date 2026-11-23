package app.couch.alhasapa.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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

/** Keyboard mode on the phone, sideways, screen by screen, with a fake link to the TV. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37], qualifiers = "w914dp-h411dp-land")
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
        override fun logins() = listOf(
            app.couch.alhasapa.remote.LoginHandoff.Item("nyt", "NYT Games", signedIn = true),
            app.couch.alhasapa.remote.LoginHandoff.Item("hz", "הארץ משחקים", signedIn = false),
        )
        override fun sendLogin(site: String) { sent += "login:$site" }
    }
    private var handoff by mutableStateOf<KeyClient.Handoff?>(null)
    private var state by mutableStateOf<KeyClient.State>(KeyClient.State.Looking)
    private var game = 0
    private val tab = hasContentDescription("משטח מגע לכפה: גוררים מהקצה הימני או נוגעים כדי לפתוח")

    private fun show() = rule.setContent { AlHasapaTheme { KeyboardModeScreen(state, sink, onGame = { game++ }, handoff = handoff) } }
    private fun key(d: String) = rule.onNode(hasContentDescription(d))

    @Test
    fun theSwitchAtTheTopGoesBackToPlaying() {
        show()
        rule.onNodeWithText("מקלדת לטלוויזיה").assertIsDisplayed()
        rule.onNodeWithText("משחק").performClick()
        assertEquals(1, game)
    }

    @Test
    fun eachKeySendsTheMomentItIsPressedAndThereIsNoTypingLineOnThePhone() {
        state = KeyClient.State.Connected("MiTV", "Krillion"); show()
        rule.onNodeWithText("Krillion").assertIsDisplayed()
        rule.onNodeWithText("מה שמקלידים כאן נכתב בטלוויזיה").assertDoesNotExist()
        key("q").performClick(); key("Shift").performClick(); key("A").performClick(); key("b").performClick()
        key("רווח").performClick(); key("1").performClick()
        assertEquals("Shift is one capital", listOf("text:q", "text:A", "text:b", "text: ", "text:1"), sent)
        sent.clear()
        key("Shift").performClick(); key("Shift").performClick()                  // twice: all capitals
        key("C").performClick(); key("D").performClick()
        assertEquals(listOf("text:C", "text:D"), sent)
        key("Shift").performClick()
        sent.clear()
        key("Esc").performClick(); key("Tab").performClick(); key("מחיקה").performClick(); key("אנטר (הכפה לוחצת)").performClick()
        assertEquals(listOf("key:ESCAPE:TAP", "key:TAB:TAP", "key:BACKSPACE:TAP", "key:ENTER:TAP"), sent)
    }

    @Test
    fun hebrewAndSymbolsAreOneKeyAway() {
        state = KeyClient.State.Connected("MiTV", "Krillion"); show()
        key("עב").performClick(); key("ש").performClick(); key("ם").performClick()
        key("123").performClick(); key("#").performClick()
        key("ABC").performClick(); key("z").performClick()
        assertEquals(listOf("text:ש", "text:ם", "text:#", "text:z"), sent)
    }

    @Test
    fun anArrowIsHeldOnTheTvWhileTheFingerIsDown() {
        state = KeyClient.State.Connected("MiTV", "Krillion"); show()
        key("חץ שמאלה").performTouchInput { down(center); advanceEventTime(300); up() }
        assertEquals(listOf("key:LEFT:DOWN", "key:LEFT:UP"), sent)
    }

    @Test
    fun theTouchpadIsPulledOutAtTheRightEdgeAndTheKeyboardStaysUsable() {
        state = KeyClient.State.Connected("MiTV", "Krillion"); show()
        rule.onNode(tab).assertIsDisplayed()
        rule.onNodeWithText("משטח מגע").assertDoesNotExist()
        // Pulled to the left, it comes out.
        rule.onNode(tab).performTouchInput { down(center); repeat(8) { moveBy(Offset(-60f, 0f), 16) }; up() }
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(listOf("pointer:true"), sent)
        rule.onNodeWithText("משטח מגע").assertIsDisplayed()
        key("q").performClick()
        assertEquals("the keys still type, beside it", "text:q", sent.last())
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
        key("גרירה").performClick()
        key("גוררים · לחיצה משחררת").assertIsDisplayed()
        key("סגירת משטח המגע").performClick()
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals("closing lifts the finger and puts the paw away", listOf("drag:true", "drag:false", "pointer:false"), sent)
        rule.onNodeWithText("משטח מגע").assertDoesNotExist()
    }

    @Test
    fun aShortPullSpringsBackAndATouchOnTheTabOpensIt() {
        state = KeyClient.State.Connected("MiTV", "Krillion"); show()
        rule.onNode(tab).performTouchInput { down(center); moveBy(Offset(-40f, 0f), 200); up() }
        rule.mainClock.advanceTimeBy(1_000)
        assertTrue("a little pull is not enough: $sent", sent.isEmpty())
        rule.onNode(tab).performClick(); rule.mainClock.advanceTimeBy(1_000)
        assertEquals(listOf("pointer:true"), sent)
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
        for (k in listOf("1", "0", ".", "0", ".", "0", ".", "7")) key(k).performClick()
        rule.onNodeWithText("להתחבר").performClick()
        assertEquals(listOf("retry", "address:10.0.0.7"), sent)
        state = KeyClient.State.NotFound(KeyClient.NO_PERMISSION); rule.waitForIdle()
        rule.onNodeWithText("לאשר").performClick()
        assertEquals("permission", sent.last())
    }

    @Test
    fun theFourDigitsAreTypedOnItsOwnKeypadAndGoOutAtOnce() {
        state = KeyClient.State.NeedCode("MiTV"); show()
        rule.onNodeWithText("בטלוויזיה מופיע קוד של 4 ספרות").assertIsDisplayed()
        for (k in listOf("4", "8", "⌫", "8", "2", "1")) key(k).performClick()
        assertEquals(listOf("code:4821"), sent)
        state = KeyClient.State.NeedCode("MiTV", left = 2); rule.waitForIdle()
        rule.onNodeWithText("הקוד לא נכון. נשארו 2 ניסיונות.").assertIsDisplayed()
    }

    @Test
    fun theTouchpadIsOnlyThereWhenConnected() {
        state = KeyClient.State.Looking; show()
        rule.onNode(tab).assertDoesNotExist()
    }

    @Test
    fun everyRowOfEveryLayoutIsTheSameWidth() {
        for (l in PhoneLayout.entries) for (row in PhoneKeys.rows(l)) assertEquals("$l $row", PhoneKeys.ROW_UNITS, row.sumOf { it.second.toDouble() }.toFloat(), 0.001f)
        assertEquals(KeyLink.Msg.Text("A"), PhoneKeys.message(PKey.Ch("a"), shift = true))
        assertEquals(KeyLink.Msg.Text("ש"), PhoneKeys.message(PKey.Ch("ש"), shift = true))
        assertEquals(KeyLink.Msg.Press(KeyLink.Key.ESCAPE, KeyLink.Action.TAP), PhoneKeys.message(PKey.Esc, false))
        assertEquals(null, PhoneKeys.message(PKey.Shift, false))
    }
    @Test
    fun aLoginGoesToTheTvFromTheTopStripAndSaysHowItWent() {
        show()
        rule.onNodeWithText("התחברות לטלוויזיה").assertDoesNotExist()              // only while connected
        state = KeyClient.State.Connected("MiTV", "עמוד הבית")
        rule.onNodeWithText("התחברות לטלוויזיה").performClick()
        rule.onNodeWithText("להעביר התחברות לטלוויזיה").assertIsDisplayed()
        rule.onNodeWithText("מחובר בטלפון").assertIsDisplayed()
        rule.onNodeWithText("לא מחובר בטלפון", substring = true).assertIsDisplayed()
        rule.onNodeWithText("לשלוח").performClick()                                // only NYT has the button: the phone is signed in there
        assertEquals(listOf("login:nyt"), sent)
        handoff = KeyClient.Handoff.Sending("nyt")
        rule.onNodeWithText("שולח…").assertIsDisplayed()
        handoff = KeyClient.Handoff.Done("nyt", 3)
        rule.onNodeWithText("✓ ההתחברות ל-NYT Games עברה לטלוויזיה").assertIsDisplayed()
        handoff = KeyClient.Handoff.Failed("nyt", "update-tv")
        rule.onNodeWithText("הטלוויזיה לא ענתה: צריך לעדכן בה את האפליקציה").assertIsDisplayed()
        rule.onNodeWithText("סגירה").performClick()
        rule.onNodeWithText("להעביר התחברות לטלוויזיה").assertDoesNotExist()
    }
}
