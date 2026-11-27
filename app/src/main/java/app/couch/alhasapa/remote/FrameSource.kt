package app.couch.alhasapa.remote

import android.graphics.Bitmap

/**
 * What the TV can picture for a phone in touch mode (1.9.8): the game's page on its screen. Given by the game's screen while it is in
 * front; there is none on the home.
 */
fun interface FrameSource {
    /**
     * A picture of the game's page (of its part [crop] only, when a phone is zoomed in; null: all of it) at most [maxWidth] pixels wide,
     * handed to [done] on any thread, or null when there is none. The bitmap stays the source's own and may be drawn into again by the
     * next call: it is read before that call is made.
     */
    fun capture(maxWidth: Int, crop: KeyLink.Crop?, done: (Bitmap?) -> Unit)
}
