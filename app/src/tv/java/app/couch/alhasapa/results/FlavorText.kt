package app.couch.alhasapa.results

import android.content.Context
import android.net.Uri

/** The TV takes no photos of a computer's screen (1.11.1): nothing to read with here. */
object FlavorText : TextReader {
    override fun read(context: Context, uri: Uri, done: (PhotoRead) -> Unit) = done(PhotoRead.Failed)
}
