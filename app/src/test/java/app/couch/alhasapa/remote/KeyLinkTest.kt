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
        )
        for (m in all) {
            val line = KeyLink.encode(m)
            assertFalse("one line each: $line", line.contains('\n'))
            assertEquals(m, KeyLink.decode(line))
        }
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
    fun theDifferenceRubsOutOnlyWhatChangedAndNeverHalfACharacter() {
        assertEquals(TextMirror.Edit(0, "l"), TextMirror.diff("hel", "hell"))
        assertEquals(TextMirror.Edit(3, "he "), TextMirror.diff("teh ", "the "))
        assertEquals(TextMirror.Edit(6, "Hello "), TextMirror.diff("hello ", "Hello "))
        assertEquals(TextMirror.Edit(1, ""), TextMirror.diff("abc", "ab"))
        assertEquals("an emoji is one character", TextMirror.Edit(1, ""), TextMirror.diff("a😀", "a"))
        assertEquals(TextMirror.Edit(1, "😃"), TextMirror.diff("a😀", "a😃"))
        assertEquals(TextMirror.Edit(1, "ם"), TextMirror.diff("שלו", "שלם"))
        assertTrue(TextMirror.diff("same", "same").isEmpty)
    }
}
