package app.couch.alhasapa.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.couch.alhasapa.R
import app.couch.alhasapa.diag.Diag
import app.couch.alhasapa.ui.theme.Palette
import kotlinx.coroutines.delay

/**
 * What a TV shows while the hub loads: the three cats, still, exactly where the system's starting window already drew them (same picture,
 * same size, same place), then a quick fade to the hub. No animation loop and no work per frame: a TV box is slow enough to start that an
 * animated splash only stutters, and a still picture that is already on screen from the first instant is the honest answer.
 */
@Composable
fun LoadingCover(hubReady: Boolean, onFinished: () -> Unit) {
    val cats = rememberAssetBitmap("hub/img/cats.webp")
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) { Diag.splashShowing = true }
    LaunchedEffect(hubReady) {
        if (hubReady) { delay(280); Diag.splashShowing = false; finished() }
    }
    val alpha by animateFloatAsState(if (hubReady) 0f else 1f, tween(240), label = "cover")
    val description = stringResource(R.string.splash_description)
    BoxWithConstraints(
        Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }.background(Palette.Cream).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (cats != null) {
            // The starting window draws the picture at its own pixel size, so this does too.
            val nativeWidth = with(LocalDensity.current) { cats.width.toDp() }
            Image(
                bitmap = cats, contentDescription = null,
                modifier = Modifier.width(minOf(nativeWidth, maxWidth * 0.9f)).aspectRatio(cats.width.toFloat() / cats.height),
            )
        }
    }
}
