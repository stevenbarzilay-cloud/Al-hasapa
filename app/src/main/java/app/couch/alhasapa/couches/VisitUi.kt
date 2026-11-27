package app.couch.alhasapa.couches

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.R
import app.couch.alhasapa.data.Couches
import app.couch.alhasapa.remote.SoloTarget
import app.couch.alhasapa.remote.Visit
import app.couch.alhasapa.ui.theme.Palette

const val VISIT_BAR_TAG = "visit-bar"
const val VISIT_END_TAG = "visit-end"
const val TARGET_HOME_TAG = "target-home"
const val TARGET_VISIT_TAG = "target-visit"

/** A contest at the couch being visited that the phone's holder may join: the game, and who is in it already. */
data class OpenContest(val game: String, val gameName: String, val players: String)

/**
 * The line that stays at the top of the phone's home for as long as a visit lasts (2.0): whose couch the phone's holder is a guest
 * at, whether its TV is reached this moment (said in words; it changes the moment the network drops), and the way out. Under it:
 * a result that still waits to be sent there, and the contests of that couch that can be joined.
 */
@Composable
fun VisitBar(
    visit: Visit, connected: Boolean, pending: List<String>, contests: List<OpenContest>,
    onEnd: () -> Unit, onOpen: () -> Unit, onJoin: (String) -> Unit, modifier: Modifier = Modifier,
) {
    val where = stringResource(R.string.visit_bar, Couches.inCouch(visit.name))
    val status = stringResource(if (connected) R.string.visit_connected else R.string.visit_lost)
    Column(modifier.fillMaxWidth().background(Palette.Ink).statusBarsPadding().testTag(VISIT_BAR_TAG)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(
                Modifier.weight(1f).clickable(onClick = onOpen).semantics(mergeDescendants = true) { contentDescription = "$where. $status"; liveRegion = LiveRegionMode.Polite },
            ) {
                Text("🛋️ $where", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text((if (connected) "🔗 " else "⚠️ ") + status + " · " + stringResource(R.string.visit_as, visit.meName), fontSize = 13.sp, color = Palette.Cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SmallButton(stringResource(R.string.visit_end), dark = false, tag = VISIT_END_TAG, onClick = onEnd)
        }
        for (game in pending) {
            Text(
                "⏳ " + stringResource(R.string.visit_pending, game, Couches.toCouch(visit.name)), fontSize = 14.sp, color = Palette.Cream,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
        // A contest of that couch that can be joined: who is in it, and under it the press that joins (it says the couch itself).
        for (c in contests) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.visit_contest, c.gameName, c.players), fontSize = 14.sp, color = Palette.Cream, maxLines = 2, overflow = TextOverflow.Ellipsis)
                SmallButton(stringResource(R.string.visit_join, c.gameName, Couches.inCouch(visit.name)), dark = false, tag = "visit-join-${c.game}") { onJoin(c.game) }
            }
        }
    }
}

/**
 * Before a game is played alone during a visit: which couch its result is for, the home's or the one being visited, each named in
 * full with what it is to the phone's holder. Asked for every new game played alone while the visit lasts; the answer stands for
 * that game only.
 */
@Composable
fun CouchTargetDialog(gameName: String, home: SoloTarget, visit: SoloTarget, onPick: (SoloTarget) -> Unit, onDismiss: () -> Unit) {
    DialogCard(onDismiss) {
        Text(stringResource(R.string.target_question, gameName), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        Choice(stringResource(R.string.target_home, home.name), stringResource(R.string.visit_as, home.whoName), dark = true, tag = TARGET_HOME_TAG) { onPick(home) }
        Choice(stringResource(R.string.target_visit, visit.name), stringResource(R.string.visit_as, visit.whoName), dark = false, tag = TARGET_VISIT_TAG) { onPick(visit) }
        Text(stringResource(R.string.target_hint), fontSize = 14.sp, color = Palette.Ink2)
        SmallButton(stringResource(R.string.couch_cancel), dark = false, onClick = onDismiss)
    }
}
