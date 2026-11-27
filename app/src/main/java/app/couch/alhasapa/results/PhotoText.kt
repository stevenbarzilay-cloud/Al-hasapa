package app.couch.alhasapa.results

import android.content.Context
import android.net.Uri
import app.couch.alhasapa.scoring.Scoring

/** What reading a picture came to (1.11.1). */
sealed interface PhotoRead {
    /** The picture's lines of text, top to bottom. */
    data class Lines(val lines: List<String>) : PhotoRead
    /** The phone's text model is not there yet (the first time, Google Play services downloads it). */
    data object NotReady : PhotoRead
    data object Failed : PhotoRead
}

/** Reads the text in a picture, on the phone itself. [done] may come on any thread. */
fun interface TextReader {
    fun read(context: Context, uri: Uri, done: (PhotoRead) -> Unit)
}

/**
 * A photo of a computer's screen, for a game played there (1.11.1): its text is read on the phone (the phone build's [FlavorText]:
 * ML Kit; it reads English and numbers, not Hebrew letters), and only the line that holds the result is kept, the way a game's shared
 * text is read; the photo itself is deleted at once and nothing else of the screen is kept.
 */
object PhotoText {
    /** Replaced in the tests. */
    @Volatile var reader: TextReader = FlavorText

    private const val MAX_LINE = 120
    /** A date on a line (the computer's clock in a corner): never a result. The same mark twice ("27/11/2026"), so "41.23/50" is not one. */
    private val DATE = Regex("(?<![0-9])[0-9]{1,2}([./-])[0-9]{1,2}\\1[0-9]{2,4}(?![0-9])")
    /** A clock alone on its line: a time game's time can look the same, so it comes last. */
    private val CLOCK_ALONE = Regex("^\\s*[0-9]{1,2}:[0-9]{2}(\\s*[AaPp][Mm])?\\s*$")
    /** Words that go with a result on an end screen. */
    private val WORDS = Regex("(?i)guess|tries|try|attempt|time|score|point|mistake|hint|solved|got it|seconds|words|correct|in [0-9]")

    /**
     * The line of [lines] that holds a result of a game of [kind] ("3/6", "Time 1:42", "41.23/50", "2 mistakes"), or null. A result
     * split over two lines ("Wordle 1,618" / "3/6") is read across them; lines with the words of a result come first.
     */
    fun resultLine(lines: List<String>, kind: String): String? {
        val clean = lines.map { it.trim() }.filter { it.isNotEmpty() && it.length <= MAX_LINE && !DATE.containsMatchIn(it) }
        val candidates = clean + clean.zipWithNext { a, b -> "$a $b" }.filter { it.length <= MAX_LINE }
        val found = candidates.filter { Scoring.parseResult(it, kind) != null }
        return found.minByOrNull { c -> if (WORDS.containsMatchIn(c)) 0 else if (CLOCK_ALONE.matches(c)) 2 else 1 }
    }
}
