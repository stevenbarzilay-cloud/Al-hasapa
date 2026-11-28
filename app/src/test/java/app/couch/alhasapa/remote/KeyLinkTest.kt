package app.couch.alhasapa.remote

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.remote.KeyLink.Msg
import app.couch.alhasapa.tv.Delivery
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [37])
class KeyLinkTest {
    @Test
    fun everyMessageGoesOverAndComesBackTheSame() {
        val all = listOf(
            Msg.Hi("tv1", "MiBOX4"), Msg.Hello("ph1", "Pixel 8", ""), Msg.NeedCode, Msg.Pair("0421"), Msg.Wrong(2), Msg.Paired("ab".repeat(32)),
            Msg.Ok("Krillion"), Msg.Screen("עמוד הבית"), Msg.Text("octopus"), Msg.Text("שלום 😀"), Msg.Press(KeyLink.Key.ENTER, KeyLink.Action.TAP),
            Msg.Press(KeyLink.Key.LEFT, KeyLink.Action.DOWN), Msg.Press(KeyLink.Key.LEFT, KeyLink.Action.UP), Msg.Ping, Msg.Pong, Msg.Bye("code"),
            Msg.Pointer(true), Msg.Pointer(false), Msg.Move(12.5f, -3.25f), Msg.Click, Msg.Drag(true), Msg.Drag(false), Msg.Scroll(-40f),
            Msg.Hi("tv1", "MiBOX4", "0f".repeat(16)), Msg.Hello("ph1", "Pixel 8", "", "ab".repeat(32)),
            Msg.LoginsKey("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE"), Msg.LoginsReady("MFkw", "cd".repeat(32)),
            Msg.Logins("AAECAwQFBgcICQoL", "x".repeat(20_000)), Msg.LoginsDone("NYT Games", 3), Msg.LoginsNo("update-tv"),
            // From 1.9.4: each side says which version of the app it is, and a move says when the finger moved.
            Msg.Hi("tv1", "MiBOX4", "0f".repeat(16), 28, "1.9.4"), Msg.Hello("ph1", "Pixel 8", "", "ab".repeat(32), 28, "1.9.4"),
            Msg.Move(1f, 2f, 86_400_123L),
            // From 1.9.7: a game's end screen, and "save the result" from the phone.
            Msg.Screen("Catfishing", result = true), Msg.SaveResult,
            // From 1.9.8: touch mode.
            Msg.Screen("Dialed", touch = true), Msg.Screen("Dialed", result = true, touch = true),
            Msg.Mirror(true, 1_080), Msg.Mirror(false, 0), Msg.FrameOk(17),
            Msg.Mirror(true, 900, KeyLink.Crop(0.25f, 0.1f, 0.75f, 0.6f)), Msg.FrameNone(KeyLink.NONE_HOME), Msg.FrameNone(KeyLink.NONE_CAPTURE),
            Msg.Touch(KeyLink.TouchAction.DOWN, 0.25f, 0.75f), Msg.Touch(KeyLink.TouchAction.MOVE, 0.2512f, 0.7f),
            Msg.Touch(KeyLink.TouchAction.UP, 1f, 0f), Msg.Touch(KeyLink.TouchAction.CANCEL, 0.5f, 0.5f),
            // From 1.9.9: each of us on our own phone, and notes.
            Msg.CoupleMsg("b", listOf(app.couch.alhasapa.data.Person("a", "סטיב", "star", "teal", 5), app.couch.alhasapa.data.Person("b", "רוני", "", "", 0))),
            Msg.CoupleMsg("", emptyList()),
            Msg.NoteMsg(app.couch.alhasapa.data.Note("n1", "a", "krillion", "כל הכבוד 🎉", listOf(intArrayOf(0, 0, 1000, 1000), intArrayOf(5, 5)), 99L)),
            Msg.NoteMsg(app.couch.alhasapa.data.Note("n2", "b", null, "", listOf(intArrayOf(1, 2)), 100L)),
            Msg.NoteOk("n1"),
            // From 1.10: the others, and notes for anyone.
            Msg.CoupleMsg("u1a2b3c4", listOf(
                app.couch.alhasapa.data.Person("a", "סטיב", "", "", 1), app.couch.alhasapa.data.Person("b", "רוני", "", "", 1),
                app.couch.alhasapa.data.Person("u1a2b3c4", "דני", "star", "green", 5),
                app.couch.alhasapa.data.Person("u5d6e7f8", "מיה", "", "", 6, guest = true, day = "2026-11-27"),
                app.couch.alhasapa.data.Person("u9a9a9a9", "", "", "", 7, removed = true),
            )),
            Msg.NoteMsg(app.couch.alhasapa.data.Note("n3", "u1a2b3c4", null, "לרוני", emptyList(), 5L, to = "b")),
            // From 1.12: a drawing with its pens, the relay, and what the devices keep alike.
            Msg.NoteMsg(app.couch.alhasapa.data.Note("n4", "b", null, "", listOf(intArrayOf(0, 0, 500, 500), intArrayOf(7, 9)), 6L, ink = listOf(21, 102))),
            Msg.Relay("c2VhbGVk"),
            Msg.ShareAll,
            // From 2.0: couches; what a phone comes as; a guest's game played alone, kept. From 2.0.1: a TV with nothing on its couch.
            Msg.Hi("tv1", "MiBOX4", "0f".repeat(16), 40, "2.0.0", "c0123456789abcde", "הספה של דני"),
            Msg.Hi("tv1", "MiBOX4", "0f".repeat(16), 41, "2.0.1", "c0123456789abcde", "הספה שלנו", empty = true),
            Msg.Hello("ph1", "Pixel 8", "", "ab".repeat(32), 41, "2.0.1", KeyLink.AS_GUEST),
            Msg.Ok("עמוד הבית", KeyLink.AS_EVENING), Msg.SoloOk("2026-11-27", "connections", 1_234L),
            // From 2.0.2: touch mode held upright, with the phone's shape.
            Msg.Mirror(true, 960, null, 0.48f), Msg.Mirror(true, 900, KeyLink.Crop(0.25f, 0.1f, 0.75f, 0.6f), 0.5f),
        )
        for (m in all) {
            val line = KeyLink.encode(m)
            assertFalse("one line each: $line", line.contains('\n'))
            assertEquals(m, KeyLink.decode(line))
        }
        // A share is a part of a state: what it holds comes back as it went.
        val share = org.json.JSONObject().put("v", 1).put("log", org.json.JSONObject().put("2026-11-27", org.json.JSONObject().put("wordle", org.json.JSONObject().put("t", 5))))
        val back = KeyLink.decode(KeyLink.encode(Msg.Share(share))) as Msg.Share
        assertEquals(share.toString(), back.share.toString())
        assertNull("another version's share is not taken", KeyLink.decode(KeyLink.encode(Msg.Share(org.json.JSONObject().put("v", 2)))))
    }

    /** 1.12: the largest drawing a note may hold still fits a line of the link, with room to spare. */
    @Test
    fun theLargestDrawingStillFitsALine() {
        val per = app.couch.alhasapa.data.Notes.MAX_POINTS / app.couch.alhasapa.data.Notes.MAX_STROKES
        val strokes = List(app.couch.alhasapa.data.Notes.MAX_STROKES) { IntArray(per * 2) { 1000 } }
        val note = app.couch.alhasapa.data.Note("n", "u1a2b3c4d5e6", "a-game-with-a-long-name", "א".repeat(app.couch.alhasapa.data.Notes.MAX_TEXT), strokes, 1_764_000_000_000L,
            to = "u9a9a9a9a9a9", ink = List(strokes.size) { 102 })
        val line = KeyLink.encode(Msg.NoteMsg(note))
        assertTrue("${line.length} characters", line.length < KeyLink.MAX_LINE * 8 / 10)
        assertEquals(note, (KeyLink.decode(line) as Msg.NoteMsg).note)
    }

    @Test
    fun aPictureGoesOnOneLineThatOnlyThePhoneReads() {
        val data = "AbC+/09=".repeat(40_000)                                        // 320 KB of Base64, as a large picture is
        val m = Msg.Frame(42, 960, 540, "AAECAwQFBgcICQoL", data)
        val line = KeyLink.encode(m)
        assertFalse(line.contains('\n'))
        assertEquals("the hand-made line is plain JSON", m, KeyLink.decode(line, KeyLink.MAX_FRAME_LINE))
        assertEquals(42, org.json.JSONObject(line).getInt("n"))
        assertNull("the TV's own limit refuses a line this long (a phone never sends one)", KeyLink.decode(line))
        // Anything that is not Base64 goes the long way round, escaped, and still comes back the same.
        val odd = Msg.Frame(1, 2, 3, "a\"b", "c\\d")
        assertEquals(odd, KeyLink.decode(KeyLink.encode(odd), KeyLink.MAX_FRAME_LINE))
    }

    @Test
    fun aPictureOfAPartOfThePageSaysWhichPart() {
        val part = KeyLink.Crop(0.1f, 0.2f, 0.6f, 0.7f)
        val m = Msg.Frame(3, 640, 360, "AAECAwQFBgcICQoL", "QUJD".repeat(100), part)
        assertEquals(m, KeyLink.decode(KeyLink.encode(m), KeyLink.MAX_FRAME_LINE))
        // From an older TV there is no part: the whole page.
        assertEquals(null, (KeyLink.decode(KeyLink.encode(m.copy(crop = null)), KeyLink.MAX_FRAME_LINE) as Msg.Frame).crop)
        fun crop(json: String) = KeyLink.Crop.of(org.json.JSONArray(json))
        assertNull("all of it is no part", crop("[0,0,1,1]"))
        assertNull("too small to be one", crop("[0.5,0.5,0.52,0.6]"))
        assertNull("not numbers", crop("[\"a\",0,1,1]"))
        assertEquals(KeyLink.Crop(0f, 0.5f, 1f, 1f), crop("[-1,0.5,3,1]"))
        assertEquals(KeyLink.Crop(0.1f, 0.1f, 0.9f, 0.8f), KeyLink.Crop.union(KeyLink.Crop(0.1f, 0.2f, 0.5f, 0.8f), KeyLink.Crop(0.3f, 0.1f, 0.9f, 0.4f)))
        assertNull("a phone that shows all of it wins", KeyLink.Crop.union(part, null))
    }

    /** 2.0.2: a phone that never says (before 2.0.2) is on its side; a shape no phone has is not taken. */
    @Test
    fun anUprightPhoneSaysItsShapeAndOnlyAPhonesShapeIsTaken() {
        assertEquals(0f, (KeyLink.decode("{\"t\":\"mirror\",\"on\":true,\"w\":960}") as Msg.Mirror).up)
        assertEquals(0f, (KeyLink.decode("{\"t\":\"mirror\",\"on\":true,\"w\":960,\"up\":2.1}") as Msg.Mirror).up)
        assertEquals(0f, (KeyLink.decode("{\"t\":\"mirror\",\"on\":true,\"w\":960,\"up\":\"x\"}") as Msg.Mirror).up)
        assertEquals(0.462f, (KeyLink.decode("{\"t\":\"mirror\",\"on\":true,\"w\":960,\"up\":0.462}") as Msg.Mirror).up, 0.0001f)
        assertFalse("on its side, nothing more is said", KeyLink.encode(Msg.Mirror(true, 960)).contains("up"))
    }

    @Test
    fun aTouchIsAlwaysOnThePageAndNeverNotANumber() {
        assertEquals(Msg.Touch(KeyLink.TouchAction.MOVE, 1f, 0f), KeyLink.decode("{\"t\":\"touch\",\"a\":\"move\",\"x\":7.5,\"y\":-2}"))
        assertNull(KeyLink.decode("{\"t\":\"touch\",\"a\":\"down\",\"x\":0.5}"))
        assertNull(KeyLink.decode("{\"t\":\"touch\",\"a\":\"pinch\",\"x\":0.5,\"y\":0.5}"))
        assertNull(KeyLink.decode("{\"t\":\"frame\",\"n\":1,\"w\":2,\"h\":3,\"iv\":\"a\"}"))
        // To a ten-thousandth of the page: a fifth of a pixel on a TV's screen.
        assertTrue(KeyLink.encode(Msg.Touch(KeyLink.TouchAction.DOWN, 0.123456f, 0.5f)).contains("\"x\":0.1235"))
        // An older TV does not say "touch" at all: the phone stays as it is.
        assertEquals(Msg.Screen("Wordle"), KeyLink.decode("{\"t\":\"screen\",\"screen\":\"Wordle\"}"))
        assertFalse(KeyLink.encode(Msg.Screen("Wordle")).contains("touch"))
    }

    @Test
    fun anAppFromBeforeTheVersionsWereSaidIsVersionNothing() {
        assertEquals(Msg.Hello("p", "Pixel", "tok"), KeyLink.decode("{\"t\":\"hello\",\"v\":2,\"phone\":\"p\",\"name\":\"Pixel\",\"token\":\"tok\",\"proof\":\"\"}"))
        assertEquals(0, (KeyLink.decode("{\"t\":\"hi\",\"tv\":\"t\",\"name\":\"TV\"}") as Msg.Hi).app)
        assertEquals("a version number is never negative", 0, (KeyLink.decode("{\"t\":\"hi\",\"tv\":\"t\",\"name\":\"TV\",\"app\":-5}") as Msg.Hi).app)
        // A move from an older phone has no time; "t" stays the message's kind, the time travels as "at".
        assertEquals(Msg.Move(3f, 4f), KeyLink.decode("{\"t\":\"move\",\"dx\":3,\"dy\":4}"))
        assertTrue(KeyLink.encode(Msg.Move(3f, 4f, 99L)).contains("\"at\":99"))
        assertFalse(KeyLink.encode(Msg.Move(3f, 4f)).contains("\"at\""))
    }

    @Test
    fun anythingElseIsNothing() {
        for (bad in listOf("", "garbage", "{}", "{\"t\":\"nope\"}", "{\"t\":\"key\",\"k\":\"F13\"}", "{\"t\":\"key\",\"k\":\"ENTER\",\"a\":\"twice\"}",
            "{\"t\":\"text\",\"s\":\"\"}", "{\"t\":\"text\",\"s\":\"${"x".repeat(5_000)}\"}"))
            assertNull(bad.take(60), KeyLink.decode(bad))
    }

    @Test
    fun onlyTheHomesOwnAddressesMayConnect() {
        for (a in listOf("192.168.1.23", "10.0.0.5", "172.16.4.4", "127.0.0.1", "169.254.1.1", "fe80::1", "fd12:3456::1", "::1"))
            assertTrue(a, KeyLink.isHomeAddress(InetAddress.getByName(a)))
        for (a in listOf("8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", "100.64.0.1"))
            assertFalse(a, KeyLink.isHomeAddress(InetAddress.getByName(a)))
    }

    @Test
    fun typedTextBecomesTheKeysOfAUsKeyboardAndTheRestText() {
        assertEquals(listOf(Delivery.Code(KeyEvent.KEYCODE_H, true), Delivery.Code(KeyEvent.KEYCODE_I), Delivery.Code(KeyEvent.KEYCODE_1, true)), TypedKeys.deliveries("Hi!"))
        assertEquals(
            listOf(Delivery.Code(KeyEvent.KEYCODE_A), Delivery.Text("שלום"), Delivery.Code(KeyEvent.KEYCODE_SPACE), Delivery.Text("😀"), Delivery.Code(KeyEvent.KEYCODE_B)),
            TypedKeys.deliveries("aשלום 😀b"),
        )
        assertEquals(Delivery.Code(KeyEvent.KEYCODE_SEMICOLON, true), TypedKeys.keyFor(':'))
        assertEquals(Delivery.Code(KeyEvent.KEYCODE_2, true), TypedKeys.keyFor('@'))
        assertEquals(Delivery.Code(KeyEvent.KEYCODE_ENTER), TypedKeys.keyFor('\n'))
        assertNull(TypedKeys.keyFor('א'))
        assertEquals(Delivery.Hold(KeyEvent.KEYCODE_DPAD_LEFT, true), TypedKeys.forKey(KeyLink.Key.LEFT, KeyLink.Action.DOWN))
        assertEquals(Delivery.Code(KeyEvent.KEYCODE_ESCAPE), TypedKeys.forKey(KeyLink.Key.ESCAPE, KeyLink.Action.TAP))
    }

    @Test
    fun aMoveOrScrollIsNeverHugeOrNotANumber() {
        assertEquals(Msg.Move(400f, -400f), KeyLink.decode("{\"t\":\"move\",\"dx\":99999,\"dy\":-99999}"))
        assertEquals(Msg.Move(0f, 0f), KeyLink.decode("{\"t\":\"move\",\"dx\":\"NaN\",\"dy\":\"x\"}"))
        assertEquals(Msg.Scroll(400f), KeyLink.decode("{\"t\":\"scroll\",\"dy\":1e9}"))
    }
}
