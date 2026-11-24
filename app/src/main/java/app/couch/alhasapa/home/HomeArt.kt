package app.couch.alhasapa.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntRect
import java.util.concurrent.ConcurrentHashMap

/**
 * The two pictures of the TV's home: the logo and the two of us on the couch with the cat. They are the app's original ones, the very
 * files the hub shows (assets/hub/img), read once at the size the screen needs: a TV box has little memory to spare for a picture
 * four times larger than what it draws.
 *
 * Each comes with the part of it that is not empty ([Art.bounds]), so what is lined up on the screen is the drawing itself and not the
 * blank margin of its file.
 */
object HomeArt {
    class Art(val image: ImageBitmap, val bounds: IntRect) {
        val aspect: Float get() = bounds.width.toFloat() / bounds.height.toFloat()
    }

    enum class Picture(val asset: String, /** Width to height of the drawing itself, for the room kept for it until it is read. */ val aspect: Float) {
        LOGO("hub/img/logo.webp", 872f / 379f),
        COUPLE("hub/img/couple.webp", 1096f / 703f),
    }

    private val cache = ConcurrentHashMap<Picture, Art>()

    /** The picture if it was read already (by [load], here or earlier). */
    fun cached(picture: Picture): Art? = cache[picture]

    /**
     * Reads [picture], at the smallest size that is still at least [heightPx] tall on the screen. Not on the main thread: decoding
     * takes a moment on a TV box, and the home must be there at once (the picture's place is kept and it appears when it is ready).
     */
    fun load(context: Context, picture: Picture, heightPx: Int): Art? = cache[picture] ?: runCatching {
        val assets = context.applicationContext.assets
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open(picture.asset).use { BitmapFactory.decodeStream(it, null, size) }
        var sample = 1
        while (size.outHeight / (sample * 2) >= heightPx.coerceAtLeast(1)) sample *= 2
        val bitmap = assets.open(picture.asset).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        Art(bitmap.asImageBitmap(), visible(bitmap)).also { cache[picture] = it }
    }.getOrNull()

    /** The smallest box that holds everything in [bitmap] that is not see-through. */
    internal fun visible(bitmap: Bitmap): IntRect {
        val w = bitmap.width; val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        var left = w; var top = h; var right = -1; var bottom = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > 16) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        return if (right < left) IntRect(0, 0, w, h) else IntRect(left, top, right + 1, bottom + 1)
    }
}
