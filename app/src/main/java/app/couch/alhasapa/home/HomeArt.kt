package app.couch.alhasapa.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
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

    enum class Picture(
        val asset: String,
        /** Width to height of the drawing itself, for the room kept for it until it is read. */ val aspect: Float,
        /** The start of its colour families' masks ("…-r.webp", "…-p.webp"), when it has them (1.12). */ val masks: String? = null,
    ) {
        LOGO("hub/img/logo.webp", 872f / 379f),
        COUPLE("hub/img/couple.webp", 1096f / 703f, "hub/img/couple"),
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

    private val tinted = ConcurrentHashMap<String, Art>()

    /**
     * [picture] in the two people's colours (1.12), as the hub's page draws it: the picture's red family (the first of us: the shirt,
     * the mug, the cushions) takes the first person's colour, its pink family (the second's shirt, the couch) the second's soft tone.
     * Each family is cut out by its soft mask (the hub's own "-r" and "-p" files) and given the new colour's hue and saturation while
     * every pixel keeps its brightness, so the shading and the lines stay; skin, ink, hair and cream are never touched. Drawn once per
     * pair of colours, off the main thread; the plain picture when both are the drawing's own (red and pink), or before Android 10.
     */
    fun tinted(context: Context, picture: Picture, heightPx: Int, a: app.couch.alhasapa.data.PieceStyle, b: app.couch.alhasapa.data.PieceStyle): Art? {
        val base = load(context, picture, heightPx) ?: return null
        val tintA = a.colorId != "red"; val tintB = b.colorId != "pink"
        if ((!tintA && !tintB) || android.os.Build.VERSION.SDK_INT < 29 || picture.masks == null) return base
        val key = "${picture.name}|${a.colorId}|${b.colorId}"
        tinted[key]?.let { return it }
        return runCatching {
            val src = base.image.asAndroidBitmap()
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            c.drawBitmap(src, 0f, 0f, null)
            val whole = android.graphics.Rect(0, 0, src.width, src.height)
            fun mask(suffix: String): Bitmap? = runCatching {
                context.applicationContext.assets.open(picture.masks + suffix + ".webp").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            val cut = android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN); isFilterBitmap = true }
            fun layer(m: Bitmap, color: Int, lift: Float) {
                if (lift > 1.001f) {
                    // A darker red's family is lifted first (as the hub's brightness filter), so the new colour is not muddy.
                    c.saveLayer(null, null)
                    c.drawBitmap(src, 0f, 0f, android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setScale(lift, lift, lift, 1f) }) })
                    c.drawBitmap(m, null, whole, cut)
                    c.restore()
                }
                c.saveLayer(null, android.graphics.Paint().apply { blendMode = android.graphics.BlendMode.COLOR })
                c.drawColor(color)
                c.drawBitmap(m, null, whole, cut)
                c.restore()
            }
            if (tintA) mask("-r")?.let { layer(it, a.base.toArgb(), lift(a.base.toArgb())); it.recycle() }
            if (tintB) mask("-p")?.let { layer(it, b.soft.toArgb(), 1f); it.recycle() }
            // One pair of colours at a time is kept: the one before is of no use any more.
            Art(out.asImageBitmap(), base.bounds).also { tinted.clear(); tinted[key] = it }
        }.getOrElse { base }
    }

    /** The hub's artLift: how much the red family is brightened before it takes [rgb] (a colour lighter than the drawing's red). */
    internal fun lift(rgb: Int): Float {
        fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        fun lum(x: Int) = 0.2126 * lin((x shr 16) and 255) + 0.7152 * lin((x shr 8) and 255) + 0.0722 * lin(x and 255)
        val red = lum(0xD94E38)
        return Math.min(1.6, Math.max(1.0, Math.pow(lum(rgb) / red, 1 / 2.2))).toFloat()
    }

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
