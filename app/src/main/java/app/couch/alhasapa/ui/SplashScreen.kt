package app.couch.alhasapa.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.couch.alhasapa.R
import app.couch.alhasapa.ui.theme.Palette
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.delay

/** Never shorter than this, so the animation reads as a moment and not a flicker. */
private const val MIN_VISIBLE_MS = 1500L

/** Never longer than this, even if the hub is somehow slow to report ready. */
private const val MAX_VISIBLE_MS = 3600L
private const val EXIT_MS = 340

/** The artwork keeps its 1100x547 canvas; the Z's rise through this much extra room above it. */
private const val ART_W = 1100f
private const val ART_H = 547f
private const val SKY = 120f

/**
 * Where each cat's Z's start, as fractions of the artwork: the Siamese on the left, the big black cat on top,
 * the orange tabby on the right. Same order as [Z_PHASES].
 */
private val SNORES = listOf(Offset(0.205f, 0.335f), Offset(0.405f, 0.02f), Offset(0.875f, 0.27f))
private val Z_PHASES = listOf(0f, 0.5f, 0.17f, 0.67f, 0.34f, 0.84f)

/**
 * Three sleeping cats that breathe, a little stream of Z's drifting up from each, and the logo bouncing
 * up beneath them. Ends by itself once the hub is ready (and at least [MIN_VISIBLE_MS] has passed),
 * or on tap. Skipped entirely when the system's "remove animations" is on (see MainActivity).
 */
@Composable
fun SplashScreen(hubReady: Boolean, onFinished: () -> Unit) {
    val cats = rememberAssetBitmap("hub/img/cats.webp")
    val logo = rememberAssetBitmap("hub/img/logo.webp")

    var minElapsed by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(MIN_VISIBLE_MS); minElapsed = true }
    LaunchedEffect(Unit) { delay(MAX_VISIBLE_MS); leaving = true }
    LaunchedEffect(hubReady, minElapsed) { if (hubReady && minElapsed) leaving = true }
    val exit by animateFloatAsState(
        targetValue = if (leaving) 1f else 0f,
        animationSpec = tween(EXIT_MS, easing = FastOutSlowInEasing),
        label = "splash-exit",
        finishedListener = { if (leaving && it >= 1f) onFinished() },
    )

    val appear = remember { Animatable(0f) }
    val logoIn = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(560, easing = FastOutSlowInEasing)) }
    LaunchedEffect(Unit) {
        delay(380)
        logoIn.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
    }

    val loop = rememberInfiniteTransition(label = "splash-loop")
    val breath by loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val zPhase by loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing)),
        label = "z",
    )

    val description = stringResource(R.string.splash_description)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Cream)
            .graphicsLayer {
                alpha = 1f - exit
                val s = 1f + 0.04f * exit
                scaleX = s; scaleY = s
            }
            .pointerInput(Unit) { detectTapGestures { leaving = true } }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.88f).widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (cats != null) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(ART_W / (ART_H + SKY))
                        .graphicsLayer {
                            alpha = appear.value
                            val s = 0.9f + 0.1f * appear.value
                            scaleX = s; scaleY = s
                            translationY = (1f - appear.value) * 14.dp.toPx()
                        },
                ) {
                    val w = size.width
                    val unit = w / ART_W
                    val artTop = SKY * unit
                    val artH = ART_H * unit

                    // The cats, breathing: a whisper of vertical swell anchored at the couch.
                    scale(
                        scaleX = 1f - 0.004f * breath,
                        scaleY = 1f + 0.013f * breath,
                        pivot = Offset(w / 2f, artTop + artH),
                    ) {
                        drawImage(
                            image = cats,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(cats.width, cats.height),
                            dstOffset = IntOffset(0, artTop.toInt()),
                            dstSize = IntSize(w.toInt(), artH.toInt()),
                            filterQuality = FilterQuality.High,
                        )
                    }

                    // Z's: each cat sends two up per cycle, growing as they rise and fading at both ends.
                    val rise = w * 0.15f
                    val base = w * 0.034f
                    Z_PHASES.forEachIndexed { i, offset ->
                        val p = (zPhase + offset) % 1f
                        val anchor = SNORES[i / 2]
                        val a = sin(PI.toFloat() * p).pow(1.3f) * appear.value
                        val s = 0.55f + 0.8f * p
                        val x = anchor.x * w + w * 0.03f * p + w * 0.010f * sin(p * 2f * PI.toFloat() * 1.2f)
                        val y = artTop + anchor.y * artH - rise * p
                        translate(left = x, top = y) {
                            scale(s, pivot = Offset.Zero) {
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
                            alpha = logoIn.value.coerceIn(0f, 1f)
                            translationY = (1f - logoIn.value) * 40.dp.toPx()
                        },
                )
            }
        }
    }
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
