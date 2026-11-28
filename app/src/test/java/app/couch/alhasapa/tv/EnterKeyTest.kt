package app.couch.alhasapa.tv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Enter typed for a game on the TV (2.0.2): the scripts that keep it from a button of the game's (the browser check runs them). */
class EnterKeyTest {
    /** Written for tools/enter_check.cjs, which runs the real scripts in Chromium. */
    @Test
    fun dumpTheEnterScriptsForTheBrowserCheck() {
        val dir = File("build/display-scripts").apply { mkdirs() }
        File(dir, "enter-before.js").writeText(EnterKey.BEFORE)
        File(dir, "enter-after.js").writeText(EnterKey.AFTER)
        assertTrue(EnterKey.BEFORE.contains("__couchEnter") && EnterKey.AFTER.contains("__couchEnter"))
    }

    @Test
    fun theyLookAtNoWordsOfThePageAndSendNothing() {
        for (js in listOf(EnterKey.BEFORE, EnterKey.AFTER)) {
            for (banned in listOf("innerText", "innerHTML", "outerHTML", "textContent", ".value", "cookie", "localStorage", "sessionStorage", "fetch(", "XMLHttpRequest", "sendBeacon", "WebSocket", "eval(", "postMessage", "=>", "let ", "const "))
                assertFalse("must not use $banned", js.contains(banned))
        }
    }

    @Test
    fun theirAnswersAreReadAsPlainWords() {
        assertEquals("moved BUTTON", EnterKey.answer("\"moved BUTTON\""))
        assertEquals("seen", EnterKey.answer("\"seen\""))
        assertEquals("", EnterKey.answer(null))
        assertEquals("", EnterKey.answer("null"))
        assertEquals("", EnterKey.answer("{not json"))
    }
}
