package app.couch.alhasapa.tv

import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

private val NAMES = mapOf(
    KeyEvent.KEYCODE_E to "E", KeyEvent.KEYCODE_S to "S", KeyEvent.KEYCODE_T to "T", KeyEvent.KEYCODE_A to "A", KeyEvent.KEYCODE_B to "B",
    KeyEvent.KEYCODE_X to "X", KeyEvent.KEYCODE_Y to "Y", KeyEvent.KEYCODE_ENTER to "ENTER", KeyEvent.KEYCODE_DEL to "DEL",
)

/** Enter typed for a game on the TV (2.0.2), as the typer sends it: after a word from the page, and never overtaken by what follows. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37])
class WebTyperEnterTest {
    /** A page that answers its scripts only when told to (as a busy page does), and keeps the keys it was given. */
    private class Page(c: Context) : WebView(c) {
        val keys = ArrayList<String>()
        val asked = ArrayList<Pair<String, ValueCallback<String>?>>()
        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.action == KeyEvent.ACTION_DOWN) keys += NAMES[e.keyCode] ?: e.keyCode.toString()
            return true
        }
        override fun evaluateJavascript(script: String, cb: ValueCallback<String>?) { asked += script to cb }
        fun answer(i: Int, value: String) = asked[i].second?.onReceiveValue(value)
    }

    private val page = Page(ApplicationProvider.getApplicationContext())

    /** Each test ends with nothing waiting, whatever it did (the typer is one for the whole app). */
    @org.junit.After fun drain() = idle(WebTyper.ENTER_WAIT_MS + EnterKey.CHECK_MS + 50)
    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun theFocusComesOffTheGamesButtonFirstAndWhatIsTypedNextWaitsForTheEnter() {
        WebTyper.deliver(page, Delivery.Code(KeyEvent.KEYCODE_E))
        WebTyper.deliver(page, Delivery.Code(KeyEvent.KEYCODE_ENTER))
        // Typed fast, before the page answered: it waits.
        WebTyper.deliver(page, Delivery.Code(KeyEvent.KEYCODE_S))
        WebTyper.deliver(page, Delivery.Code(KeyEvent.KEYCODE_T))
        assertEquals(listOf("E"), page.keys)
        assertEquals(EnterKey.BEFORE, page.asked[0].first)
        page.answer(0, "\"moved BUTTON\"")
        assertEquals("Enter, then what was typed after it, in order", listOf("E", "ENTER", "S", "T"), page.keys)
        // A moment later the page is asked whether it saw the key.
        idle(EnterKey.CHECK_MS + 10)
        assertEquals(EnterKey.AFTER, page.asked[1].first)
        page.answer(1, "\"seen\"")
        // The time limit that came after does nothing more.
        idle(WebTyper.ENTER_WAIT_MS)
        assertEquals(listOf("E", "ENTER", "S", "T"), page.keys)
        WebTyper.deliver(page, Delivery.Code(KeyEvent.KEYCODE_A))
        assertEquals("nothing waits any more", "A", page.keys.last())
    }

    @Test
    fun aPageThatDoesNotAnswerHoldsNoKeyBack() {
        WebTyper.deliver(page, Delivery.Code(KeyEvent.KEYCODE_ENTER))
        WebTyper.deliver(page, Delivery.Code(KeyEvent.KEYCODE_X))
        assertTrue(page.keys.isEmpty())
        idle(WebTyper.ENTER_WAIT_MS + 10)
        assertEquals(listOf("ENTER", "X"), page.keys)
        // Its answer, coming late, sends nothing twice.
        page.answer(0, "\"moved BUTTON\"")
        assertEquals(listOf("ENTER", "X"), page.keys)
        WebTyper.deliver(page, Delivery.Code(KeyEvent.KEYCODE_Y))
        assertEquals(listOf("ENTER", "X", "Y"), page.keys)
    }

    @Test
    fun twoEntersInARowEachWaitInTurn() {
        WebTyper.deliver(page, Delivery.Code(KeyEvent.KEYCODE_ENTER))
        WebTyper.deliver(page, Delivery.Code(KeyEvent.KEYCODE_ENTER))
        WebTyper.deliver(page, Delivery.Code(KeyEvent.KEYCODE_B))
        page.answer(0, "\"BODY\"")
        assertEquals("the first went; the second waits for its own word", listOf("ENTER"), page.keys)
        page.answer(1, "\"BODY\"")
        assertEquals(listOf("ENTER", "ENTER", "B"), page.keys)
        // A frame had the focus: nothing is asked of the page afterwards (the two before it are each asked once).
        val before = page.asked.size
        WebTyper.deliver(page, Delivery.Code(KeyEvent.KEYCODE_ENTER))
        page.answer(before, "\"IFRAME\"")
        idle(EnterKey.CHECK_MS + 10)
        assertEquals(2, page.asked.count { it.first == EnterKey.AFTER })
        assertEquals(listOf("ENTER", "ENTER", "B", "ENTER"), page.keys)
    }

    @Test
    fun shiftEnterAndOtherKeysGoAtOnce() {
        WebTyper.deliver(page, Delivery.Code(KeyEvent.KEYCODE_ENTER, shift = true))
        WebTyper.deliver(page, Delivery.Code(KeyEvent.KEYCODE_DEL))
        assertEquals(listOf("ENTER", "DEL"), page.keys)
        assertTrue("nothing asked of the page", page.asked.isEmpty())
    }
}
