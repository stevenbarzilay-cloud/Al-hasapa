package app.couch.alhasapa.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.couch.alhasapa.results.SOLO_OK_TAG
import app.couch.alhasapa.results.SoloCard
import app.couch.alhasapa.results.SoloOutcome
import app.couch.alhasapa.ui.theme.Palette

/** The prefix of the reveal's buttons, so the remote stays inside it while it is up. */
const val SOLO_PREFIX = "solo-"

/**
 * On the TV's home (1.11): a game both of us played alone today, revealed for the couch once both results are in (each reveal shown
 * once on this TV). The remote's focus is on its one button; OK or Back puts it away.
 */
@Composable
fun SoloTvCard(o: SoloOutcome, reduceMotion: Boolean, onDone: () -> Unit) {
    val tv = LocalTv.current
    val done by rememberUpdatedState(onDone)
    if (tv != null) DisposableEffect(tv, o) {
        tv.modalPrefix = SOLO_PREFIX
        tv.focus(SOLO_OK_TAG)
        onDispose { tv.endModal() }
    }
    BackHandler { done() }
    Box(Modifier.fillMaxSize().background(Palette.Ink.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        SoloCard(o, other = null, reduceMotion = reduceMotion, onDone = { done() }, modifier = Modifier.padding(24.dp))
    }
}
