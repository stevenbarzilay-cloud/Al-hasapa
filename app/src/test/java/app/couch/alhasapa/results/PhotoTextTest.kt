package app.couch.alhasapa.results

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A photo of a computer's screen (1.11.1): of everything on it, only the line that holds the result is taken. */
class PhotoTextTest {
    @Test
    fun theResultIsFoundAmongTheRestOfTheScreen() {
        // Wordle's end screen with the computer's clock and date in the corner.
        assertEquals("3/6", PhotoText.resultLine(listOf("Wordle 1,618", "3/6", "Statistics", "18:42", "27/11/2026"), "guesses"))
        assertEquals("X/6", PhotoText.resultLine(listOf("Next Wordle", "X/6"), "guesses"))
        // A time beside the clock: the line that says it is a time.
        assertEquals("Time 1:42", PhotoText.resultLine(listOf("Puzzle solved!", "Time 1:42", "18:42"), "time"))
        // A score split from its words over two lines is read across them.
        assertEquals("41.23/50 Your score", PhotoText.resultLine(listOf("Dialed", "41.23/50", "Your score"), "score"))
        assertEquals("You made 2 mistakes", PhotoText.resultLine(listOf("Connections", "You made 2 mistakes", "Share"), "mistakes"))
    }

    @Test
    fun nothingIsMadeUpWhenTheScreenHoldsNoResult() {
        assertNull(PhotoText.resultLine(listOf("Settings", "Help", "Share your results"), "guesses"))
        assertNull("a date is never a result", PhotoText.resultLine(listOf("27/11/2026"), "guesses"))
        assertNull("nor with dots", PhotoText.resultLine(listOf("27.11.26"), "score"))
        assertNull("nothing at all", PhotoText.resultLine(emptyList(), "time"))
        assertNull("a line far longer than a result's (a page of text)", PhotoText.resultLine(listOf("3/6 " + "x".repeat(200)), "guesses"))
    }
}
