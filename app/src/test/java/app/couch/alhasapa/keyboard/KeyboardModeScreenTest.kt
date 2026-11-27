package app.couch.alhasapa.keyboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pinch
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
            app.couch.alhasapa.remote.LoginHandoff.Item("my", "אתר שלא ברור", signedIn = null),
        )
        override fun sendLogin(site: String) { sent += "login:$site" }
        override fun signIn(site: String) { sent += "signin:$site" }
        override fun saveResult() { sent += "save-result" }
        override fun mirror(on: Boolean, width: Int, crop: KeyLink.Crop?) {
            sent += "mirror:$on" + (if (on && width > 0) ":w" else "") + (if (crop != null) ":part" else "")
            if (on) lastCrop = crop
        }
        override fun frameShown(n: Int) { sent += "shown:$n" }
        override fun touch(a: KeyLink.TouchAction, x: Float, y: Float) { sent += "touch:${a.name.lowercase()}"; touches += Offset(x, y) }
        override fun saveMe(p: app.couch.alhasapa.data.Person) { sent += "me:${p.who}:${p.name}:${p.shape}:${p.color}" }
        override fun sendNote(game: String?, text: String, doodle: List<IntArray>) { sent += "note:$game:$text:${doodle.size}" }
    }
    private val pieces = app.couch.alhasapa.data.PiecesConfig.parse(java.io.File("src/main/assets/hub/pieces.json").readText())
    private val people = listOf(app.couch.alhasapa.data.Person("a", "סטיב", "", "", 0), app.couch.alhasapa.data.Person("b", "רוני", "", "", 0))
    private var me by mutableStateOf<String?>(null)
    private var withCouple = false
    private val touches = ArrayList<Offset>()
    private var lastCrop: KeyLink.Crop? = null
    private var frame by mutableStateOf<KeyClient.MirrorFrame?>(null)
    private var frameNone by mutableStateOf<String?>(null)
    private var versions by mutableStateOf<KeyClient.Versions?>(null)
    private var openLogins = false
    private var handoff by mutableStateOf<KeyClient.Handoff?>(null)
    private var state by mutableStateOf<KeyClient.State>(KeyClient.State.Looking)
    private var game = 0
    private val tab = hasContentDescription("משטח מגע לכפה: גוררים מהקצה הימני או נוגעים כדי לפתוח")

    private fun show() = rule.setContent {
        AlHasapaTheme {
            KeyboardModeScreen(
                state, sink, onGame = { game++ }, handoff = handoff, versions = versions, openLogins = openLogins, frame = frame, frameNone = frameNone,
                people = if (withCouple) people else emptyList(), me = me, pieces = if (withCouple) pieces else null,
                noteGames = listOf(NoteGame("krillion", "Krillion"), NoteGame("wordle", "Wordle")),
            )
        }
    }
    private fun key(d: String) = rule.onNode(hasContentDescription(d))

    /** A small picture, as the TV sends them: a JPEG. */
    private fun jpeg(w: Int, h: Int): ByteArray {
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(200, 90, 40)) }
        return java.io.ByteArrayOutputStream().also { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun aGamePlayedByDraggingTurnsThePhoneToTouchAndAFingerOnThePictureGoesToTheTv() {
        state = KeyClient.State.Connected("MiTV", "Dialed", touch = true); show()
        rule.onNodeWithTag(MIRROR_TAG).assertIsDisplayed()
        key("q").assertDoesNotExist()
        assertEquals("the pictures are asked for as soon as it shows", "mirror:true:w", sent.first())
        rule.onNodeWithText("מחכים לתמונה מהטלוויזיה…").assertIsDisplayed()

        // A picture arrives: it is drawn, and only then is the TV told it was shown (so it sends the next).
        frame = KeyClient.MirrorFrame(3, 160, 90, jpeg(160, 90))
        // The picture is opened away from the screen's thread, then drawn: the screen's thread is let run until it is.
        val until = System.currentTimeMillis() + 5_000
        while (!sent.contains("shown:3") && System.currentTimeMillis() < until) { rule.waitForIdle(); Thread.sleep(10) }
        assertTrue("sent: $sent", sent.contains("shown:3"))
        rule.onNodeWithText("מחכים לתמונה מהטלוויזיה…").assertDoesNotExist()

        // A finger down in the middle, dragged right, lifted: the same on the TV, as shares of the picture.
        sent.clear()
        rule.onNodeWithTag(MIRROR_TAG).performTouchInput { down(center); moveBy(Offset(60f, 0f)); moveBy(Offset(60f, 10f)); up() }
        assertEquals("touch:down", sent.first()); assertEquals("touch:up", sent.last()); assertTrue(sent.contains("touch:move"))
        assertEquals(0.5f, touches.first().x, 0.02f); assertEquals(0.5f, touches.first().y, 0.02f)
        assertTrue("it moved right", touches.last().x > 0.55f)

        // Back to the keyboard by hand: the pictures stop, the keys are there.
        sent.clear()
        rule.onNodeWithText("מקלדת").performClick(); rule.waitForIdle()
        assertEquals(listOf("mirror:false"), sent.filter { it.startsWith("mirror") })
        key("q").assertIsDisplayed()
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun thePictureIsZoomedWithTwoFingersOrTheButtonsAndTouchesStillLandOnTheirSpot() {
        state = KeyClient.State.Connected("MiTV", "Dialed", touch = true); show()
        frame = KeyClient.MirrorFrame(1, 160, 90, jpeg(160, 90))
        val until = System.currentTimeMillis() + 5_000
        while (!sent.contains("shown:1") && System.currentTimeMillis() < until) { rule.waitForIdle(); Thread.sleep(10) }
        sent.clear()
        // Two fingers spread: the picture grows; nothing touches the game, and the TV is asked for the part in view.
        rule.onNodeWithTag(MIRROR_TAG).performTouchInput {
            pinch(center - Offset(40f, 0f), center - Offset(160f, 0f), center + Offset(40f, 0f), center + Offset(160f, 0f))
        }
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        assertTrue("no touch on the game: $sent", sent.none { it.startsWith("touch:") })
        assertTrue("a part of the page asked for: $sent", sent.any { it == "mirror:true:w:part" })
        val zoomed = lastCrop!!
        assertTrue("smaller than the whole page: $zoomed", zoomed.w < 0.6f && zoomed.h < 0.6f)
        // A tap in the middle is still the middle of the part in view.
        sent.clear(); touches.clear()
        rule.onNodeWithTag(MIRROR_TAG).performTouchInput { click(center) }
        assertEquals(listOf("touch:down", "touch:up"), sent.filter { it.startsWith("touch:") })
        assertEquals((zoomed.x0 + zoomed.x1) / 2, touches.first().x, 0.03f)
        assertEquals((zoomed.y0 + zoomed.y1) / 2, touches.first().y, 0.03f)
        // The whole game again with one button; then larger with the other.
        sent.clear()
        rule.onNode(hasContentDescription("כל המשחק")).assertIsEnabled().performClick()
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        assertEquals("all of the page again: $sent", null, lastCrop)
        rule.onNode(hasContentDescription("הגדלת התמונה")).performClick()
        rule.waitForIdle(); rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        // (The phone's screen is wider than the game: across, more than a share of 1/1.5 of it is in view; down, exactly that.)
        assertEquals(1f / 1.5f, lastCrop!!.h, 0.03f)
    }

    @Test
    fun touchCanBeChosenByHandOnlyWhileConnectedAndSaysWhenNoGameIsOpen() {
        show()
        rule.onNodeWithText("מגע").assertDoesNotExist()
        state = KeyClient.State.Connected("MiTV", "עמוד הבית"); rule.waitForIdle()
        rule.onNodeWithTag(MIRROR_TAG).assertDoesNotExist()
        rule.onNodeWithText("מגע").performClick()
        rule.onNodeWithTag(MIRROR_TAG).assertIsDisplayed()
        frameNone = KeyLink.NONE_HOME
        rule.onNodeWithText("פותחים משחק בטלוויזיה, והוא יופיע כאן לנגיעה.").assertIsDisplayed()
        // The TV opens a game played with the keys: the phone turns back to the keyboard by itself.
        state = KeyClient.State.Connected("MiTV", "Dialed", touch = true); rule.waitForIdle()
        state = KeyClient.State.Connected("MiTV", "Wordle", touch = false); rule.waitForIdle()
        key("q").assertIsDisplayed()
    }

    @Test
    fun whoHoldsThePhoneIsAskedOnceAndTheirLookIsSaved() {
        withCouple = true
        state = KeyClient.State.Connected("MiTV", "עמוד הבית"); show()
        rule.onNodeWithText("מי מחזיק את הטלפון הזה?").assertIsDisplayed()
        rule.onNode(hasContentDescription("רוני")).performClick()
        rule.onNode(hasContentDescription("לב")).performClick()
        rule.onNodeWithText("שמירה").performClick()
        assertEquals(listOf("me:b:רוני:heart:"), sent.filter { it.startsWith("me:") }.map { it.substringBeforeLast(':') + ":" })
        rule.onNodeWithText("מי מחזיק את הטלפון הזה?").assertDoesNotExist()
    }

    @Test
    fun aNoteForTheOtherOneGoesWithItsWordsDrawingAndGame() {
        withCouple = true; me = "a"
        state = KeyClient.State.Connected("MiTV", "Krillion"); show()
        rule.onNodeWithText("מי מחזיק את הטלפון הזה?").assertDoesNotExist()
        rule.onNode(hasContentDescription("השארת פתק")).performClick()
        rule.onNodeWithText("פתק לרוני").assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("כל הכבוד לנו")
        rule.onNodeWithTag(DOODLE_TAG).performTouchInput { down(Offset(20f, 20f)); moveBy(Offset(60f, 10f)); moveBy(Offset(40f, 40f)); up() }
        rule.onNodeWithText("שליחה לטלוויזיה").performClick()
        // The game on the TV is the one it waits for, unless another is chosen; one stroke drawn.
        assertEquals(listOf("note:krillion:כל הכבוד לנו:1"), sent.filter { it.startsWith("note:") })
    }

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
    fun aFingerHeldStillOnTheTouchpadGrabsDragsAndLetsGoWhenLifted() {
        state = KeyClient.State.Connected("MiTV", "Dialed"); show()
        rule.onNode(tab).performTouchInput { down(center); repeat(8) { moveBy(Offset(-60f, 0f), 16) }; up() }
        rule.mainClock.advanceTimeBy(1_000)
        val pad = rule.onNode(hasContentDescription("מזיזים את הכפה עם אצבע אחת · נגיעה קצרה לוחצת · שתי אצבעות גוללות"))
        sent.clear()
        // Held still for a moment: the TV's finger goes down where the paw is; moved: a drag; lifted: let go. No click.
        pad.performTouchInput { down(center); advanceEventTime(HOLD_TO_GRAB_MS + 120); repeat(5) { moveBy(Offset(30f, 0f), delayMillis = 20) }; up() }
        rule.waitForIdle()
        assertEquals("drag:true", sent.first())
        assertTrue("then the moves: $sent", sent.drop(1).dropLast(1).isNotEmpty() && sent.drop(1).dropLast(1).all { it == "move:right" })
        assertEquals("drag:false", sent.last())
        assertTrue("no click", "click" !in sent)
        // A finger that moves at once only moves the paw, as before.
        sent.clear()
        pad.performTouchInput { down(center); repeat(5) { moveBy(Offset(30f, 0f), delayMillis = 20) }; up() }
        assertTrue("$sent", sent.isNotEmpty() && sent.all { it == "move:right" })
        // And a short touch is still a click.
        sent.clear()
        pad.performTouchInput { click(center) }
        assertEquals(listOf("click"), sent)
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
    fun askedForFromTheAccountsScreenTheSignInListOpensAsSoonAsTheTvIsConnected() {
        openLogins = true
        show()
        rule.onNodeWithText("להעביר התחברות לטלוויזיה").assertDoesNotExist()        // not before there is a TV to send to
        state = KeyClient.State.Connected("MiTV", "עמוד הבית")
        rule.onNodeWithText("להעביר התחברות לטלוויזיה").assertIsDisplayed()
        rule.onNodeWithText("סגירה").performClick()
        rule.onNodeWithText("להעביר התחברות לטלוויזיה").assertDoesNotExist()
        key("q").assertIsDisplayed()                                                 // and under it, the keyboard as always
    }

    @Test
    fun whenAGameEndsOnTheTvThePhoneOffersToSaveTheResultThere() {
        state = KeyClient.State.Connected("MiTV", "Catfishing")
        show()
        rule.onNodeWithText("לשמור את התוצאה", substring = true).assertDoesNotExist()
        state = KeyClient.State.Connected("MiTV", "Catfishing", result = true)
        rule.onNodeWithText("לשמור את התוצאה", substring = true).assertIsDisplayed().performClick()
        assertEquals(listOf("save-result"), sent)
        key("q").assertIsDisplayed()
    }

    @Test
    fun itSaysWhenTheTvAndThePhoneAreNotTheSameVersion() {
        versions = KeyClient.Versions.TvNewer("1.9.4")
        show()
        rule.onNodeWithText("גרסה חדשה יותר", substring = true).assertDoesNotExist() // nothing to compare with yet
        state = KeyClient.State.Connected("MiTV", "עמוד הבית")
        rule.onNodeWithText("בטלוויזיה מותקנת גרסה חדשה יותר (1.9.4). כדאי לעדכן גם את הטלפון.").assertIsDisplayed()
        key("q").assertIsDisplayed()
        versions = KeyClient.Versions.TvOlder
        rule.onNodeWithText("בטלוויזיה מותקנת גרסה ישנה יותר של האפליקציה. כדאי לעדכן אותה.").assertIsDisplayed()
        versions = null
        rule.onNodeWithText("כדאי לעדכן", substring = true).assertDoesNotExist()
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
        rule.onNodeWithText("לא ברור אם מחובר בטלפון", substring = true).assertIsDisplayed()
        // A button where the phone is signed in (NYT) and where that cannot be told; none where it is not (Haaretz here).
        rule.onAllNodesWithText("לשלוח").assertCountEquals(2)
        // Signing in is right here too, wherever the phone is not signed in for sure: the whole way to the TV is in this one list.
        rule.onAllNodesWithText("להתחבר כאן").assertCountEquals(2)
        rule.onAllNodesWithText("להתחבר כאן")[0].performClick()
        assertEquals(listOf("signin:hz"), sent)
        sent.clear()
        rule.onAllNodesWithText("לשלוח")[0].performClick()
        assertEquals(listOf("login:nyt"), sent)
        handoff = KeyClient.Handoff.Sending("nyt")
        rule.onNodeWithText("שולח…").assertIsDisplayed()
        handoff = KeyClient.Handoff.Done("nyt", 3)
        rule.onNodeWithText("✓ ההתחברות ל-NYT Games עברה לטלוויזיה").assertIsDisplayed()
        handoff = KeyClient.Handoff.Failed("nyt", "update-tv")
        rule.onNodeWithText("הטלוויזיה לא ענתה: צריך לעדכן בה את האפליקציה").assertIsDisplayed()
        // The way out is at the top of the dialog, on the screen whatever the list's length (the phone lies on its side: a low screen).
        val close = rule.onNodeWithText("סגירה").assertIsDisplayed().fetchSemanticsNode()
        assertTrue("the close button is whole on the screen: ${close.boundsInWindow}", close.boundsInWindow.height >= close.size.height - 1f)
        rule.onNodeWithText("סגירה").performClick()
        rule.onNodeWithText("להעביר התחברות לטלוויזיה").assertDoesNotExist()
    }
}
