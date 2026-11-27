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
        )
        for (m in all) {
            val line = KeyLink.encode(m)
            assertFalse("one line each: $line", line.contains('\n'))
            assertEquals(m, KeyLink.decode(line))
        }
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
