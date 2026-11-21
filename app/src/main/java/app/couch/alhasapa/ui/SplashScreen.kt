package app.couch.alhasapa.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.couch.alhasapa.R
import app.couch.alhasapa.diag.Diag
import app.couch.alhasapa.ui.theme.Palette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Never shorter than this, so the animation reads as a moment and not a flicker. */
private const val MIN_VISIBLE_MS = 1500L

/** Never longer than this, even if the hub is somehow slow to report ready. */
private const val MAX_VISIBLE_MS = 3600L
private const val EXIT_MS = 340L

/** The artwork keeps its 1100x547 canvas; the Z's rise through this much extra room above it. */
private const val ART_W = 1100f
private const val ART_H = 547f
private const val SKY = 120f

/** What sits under the cats: the gap and the logo (about 190 dp at most), so the whole splash always fits the screen's height. */
private val BELOW_ART = 190.dp

/**
 * Where each cat's Z's start, as fractions of the artwork: the Siamese on the left, the big black cat on top,
 * the orange tabby on the right. Same order as [Z_PHASES].
 */
private val SNORES = listOf(Offset(0.205f, 0.335f), Offset(0.405f, 0.02f), Offset(0.875f, 0.27f))
private val Z_PHASES = listOf(0f, 0.5f, 0.17f, 0.67f, 0.34f, 0.84f)

/**
 * Three sleeping cats that breathe, a little stream of Z's drifting up from each, and the logo bouncing
 * up beneath them. Ends by itself once the hub is ready (and at least [MIN_VISIBLE_MS] has passed),
 * or on tap. Skipped entirely when the system's "remove animations" is on (see MainActivity), except on a TV.
 *
 * Everything moves from one clock, the frame time, and not from Compose's animation specs: those follow the system's animation
 * scale, which some TV boxes ship set to zero or tiny (the splash then froze or flashed by). On a TV this animated splash is not used
 * at all (see LoadingCover): the box is too slow to start for it to be anything but choppy. The cats are one still picture that is
 * only scaled a hair (cheap on a weak GPU), the Z's are a few strokes, and the artwork is sized to fit the screen's height as well
 * as its width, so a wide, short TV screen gets the same splash as a phone.
 */
@Composable
fun SplashScreen(hubReady: Boolean, onFinished: () -> Unit) {
    val cats = rememberAssetBitmap("hub/img/cats.webp")
    val logo = rememberAssetBitmap("hub/img/logo.webp")

    // Milliseconds since the splash began, from the frame clock. It is only ever READ inside draw and layer blocks, so a frame
    // redraws the layers and nothing is recomposed: on a weak device the difference is a smooth splash or a stuttering one.
    val clock = remember { mutableLongStateOf(0L) }
    val exitStart = remember { mutableLongStateOf(-1L) }
    val tapped = remember { mutableStateOf(false) }
    val hubNow by rememberUpdatedState(hubReady)
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        Diag.splashShowing = true; Diag.log("splash: shown")
        val start = withFrameNanos { it }
        while (true) {
            var done = false
            withFrameNanos { nanos ->
                val t = (nanos - start) / 1_000_000L
                clock.longValue = t
                if (exitStart.longValue < 0 && (tapped.value || t >= MAX_VISIBLE_MS || (hubNow && t >= MIN_VISIBLE_MS))) exitStart.longValue = t
                if (exitStart.longValue >= 0 && t - exitStart.longValue >= EXIT_MS) done = true
            }
            if (done) break
        }
        // Removal follows the clock too, so the splash can never stay behind.
        Diag.log("splash: leaving"); Diag.splashShowing = false; Diag.log("splash: removed")
        finished()
    }

    val description = stringResource(R.string.splash_description)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // The fade layer comes FIRST so that the cream background is inside it and fades with the cats.
            .graphicsLayer {
                val es = exitStart.longValue
                alpha = if (es < 0) 1f else 1f - ((clock.longValue - es) / EXIT_MS.toFloat()).coerceIn(0f, 1f)
            }
            .background(Palette.Cream)
            .pointerInput(Unit) { detectTapGestures { tapped.value = true } }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val artWidth = minOf(maxWidth * 0.88f, 560.dp, (maxHeight - BELOW_ART).coerceAtLeast(120.dp) * (ART_W / (ART_H + SKY)))
        Column(
            modifier = Modifier.width(artWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (cats != null) {
                Box(Modifier.fillMaxWidth().aspectRatio(ART_W / (ART_H + SKY))) {
                    // The cats, breathing: a whisper of vertical swell anchored at the couch, done by the layer, not by redrawing.
                    Image(
                        bitmap = cats,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        filterQuality = FilterQuality.Medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(ART_W / ART_H)
                            .align(Alignment.BottomCenter)
                            .graphicsLayer {
                                val breath = 0.5f - 0.5f * cos(2f * PI.toFloat() * clock.longValue / 4600f)
                                scaleX = 1f - 0.004f * breath
                                scaleY = 1f + 0.013f * breath
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            },
                    )
                    Canvas(Modifier.matchParentSize()) {
                        val zPhase = (clock.longValue % 3000L) / 3000f
                        val w = size.width
                        val unit = w / ART_W
                        val artTop = SKY * unit
                        val artH = ART_H * unit
                        // Z's: each cat sends two up per cycle, growing as they rise and fading at both ends.
                        val rise = w * 0.15f
                        val base = w * 0.034f
                        Z_PHASES.forEachIndexed { i, offset ->
                            val p = (zPhase + offset) % 1f
                            val anchor = SNORES[i / 2]
                            val a = sin(PI.toFloat() * p).pow(1.3f)
                            val sz = 0.55f + 0.8f * p
                            val x = anchor.x * w + w * 0.03f * p + w * 0.010f * sin(p * 2f * PI.toFloat() * 1.2f)
                            val y = artTop + anchor.y * artH - rise * p
                            translate(left = x, top = y) {
                                scale(sz, pivot = Offset.Zero) {
                                    drawPath(
                                        path = zPath(base),
                                        color = Palette.Ink.copy(alpha = a),
                                        style = Stroke(width = base * 0.3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            if (logo != null) {
                Image(
                    bitmap = logo,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth(0.52f)
                        .widthIn(max = 260.dp)
                        .aspectRatio(900f / 444f)
                        .graphicsLayer {
                            val logoIn = easeOutBack(((clock.longValue - 150L) / 900f).coerceIn(0f, 1f))
                            alpha = logoIn.coerceIn(0f, 1f)
                            translationY = (1f - logoIn) * 40.dp.toPx()
                        },
                )
            }
        }
    }
}

/** The logo's bounce: it overshoots a little and settles (the spring the splash used to use, as a plain curve of time). */
private fun easeOutBack(p: Float): Float {
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val x = p - 1f
    return 1f + c3 * x * x * x + c1 * x * x
}

/** A hand-drawn feeling "Z": top bar, diagonal, bottom bar. */
private fun zPath(size: Float): Path = Path().apply {
    moveTo(0f, 0f); lineTo(size, 0f); lineTo(0f, size); lineTo(size, size)
}

/** Decodes a bundled asset once. The hub's illustrations are the single source of truth, so there are no copies in res/. */
@Composable
fun rememberAssetBitmap(path: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(path) {
        runCatching { context.assets.open(path).use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
    }
}
