package app.couch.alhasapa.remote

/**
 * The phone's keyboard does not send keys: it edits text (it suggests, corrects, completes words, deletes a whole emoji). The phone keeps a
 * copy of what it has typed into the TV since the last Enter, and after every edit sends only the difference: how many characters to rub
 * out, then what to type. The TV's field then always ends up holding exactly what the phone shows.
 */
object TextMirror {
    data class Edit(val back: Int, val text: String) {
        val isEmpty: Boolean get() = back == 0 && text.isEmpty()
    }

    fun diff(old: String, new: String): Edit {
        val n = minOf(old.length, new.length)
        var i = 0
        while (i < n && old[i] == new[i]) i++
        // Never split a character made of two halves (emoji and the like): step back to its start.
        if (i in 1 until old.length && Character.isHighSurrogate(old[i - 1])) i--
        if (i in 1 until new.length && Character.isHighSurrogate(new[i - 1])) i--
        return Edit(old.codePointCount(i, old.length), new.substring(i))
    }
}
