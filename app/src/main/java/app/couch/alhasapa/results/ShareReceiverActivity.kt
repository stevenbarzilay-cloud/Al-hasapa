package app.couch.alhasapa.results

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.R
import app.couch.alhasapa.catalog.Game
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.ui.WhoPlaysDialog
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import app.couch.alhasapa.ui.theme.Palette
import java.time.LocalDate

/**
 * The Android share target. Press Share in a game (or any app), choose "תוצאה על הספה", and the result is filled in for tonight:
 * the game is recognised from the header (else asked), a head-to-head result asks "who played?", then the same sheet as in the
 * player shows what was understood and what it is worth. Nothing is saved until "שמירה".
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val text = sharedText(intent)
        if (text == null) {
            Toast.makeText(this, R.string.share_not_text, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val app = application as CouchApp
        val pieces = PiecesConfig.load(this)
        val day = CouchState.dayKey(LocalDate.now())
        val detected = GameDetector.detect(text, app.catalog)

        setContent {
            AlHasapaTheme {
                var game by remember { mutableStateOf(detected) }
                var who by remember { mutableStateOf<String?>(null) }
                val state = remember { app.repo.snapshot() }
                val (nameA, nameB) = CouchState.names(state)
                val couple = Couple(nameA, nameB, pieces.styleFor(state, "a"), pieces.styleFor(state, "b"))

                Box(Modifier.fillMaxSize().background(Palette.Cream).safeDrawingPadding(), contentAlignment = Alignment.Center) {
                    val g = game
                    if (g == null) {
                        GamePicker(app, state, onPick = { game = it }, onCancel = { finish() })
                    } else {
                        ResultSheet(
                            support = ResultSupport(g, state, day),
                            couple = couple,
                            who = who,
                            initialText = text,
                            captured = true,
                            onSave = { input -> save(app, g, day, who, input, couple); finish() },
                            onClear = null,
                            onCancel = { finish() },
                            modifier = Modifier.padding(12.dp),
                        )
                        if (g.isDuel && who == null) {
                            WhoPlaysDialog(
                                gameName = g.n, nameA = nameA, nameB = nameB, pieceA = couple.pieceA, pieceB = couple.pieceB,
                                onPick = { who = it }, onDismiss = { finish() },
                                title = stringResource(R.string.who_shared_title), hint = stringResource(R.string.who_shared_hint),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun save(app: CouchApp, game: Game, day: String, who: String?, input: ResultInput, couple: Couple) {
        val msg = when (val o = ResultStore.save(app.repo, game, day, who, input)) {
            is ResultStore.Outcome.Saved -> getString(R.string.result_saved_pts, o.points)
            ResultStore.Outcome.MarkedOnly -> getString(R.string.result_marked)
            ResultStore.Outcome.Nothing -> getString(R.string.result_nothing)
            is ResultStore.Outcome.DuelSide -> when (o.winner) {
                null -> getString(R.string.result_duel_wait)
                "t" -> getString(R.string.result_duel_tie)
                else -> getString(R.string.result_duel_win, if (o.winner == "a") couple.nameA else couple.nameB, game.n)
            }
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val MAX_SHARED = 20_000

        /** The text another app shared, if it is a plain, reasonably small piece of text. */
        fun sharedText(intent: Intent?): String? {
            if (intent?.action != Intent.ACTION_SEND) return null
            val t = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim() ?: return null
            return t.takeIf { it.isNotEmpty() && it.length <= MAX_SHARED }
        }
    }
}

@androidx.compose.runtime.Composable
private fun GamePicker(app: CouchApp, state: org.json.JSONObject, onPick: (Game) -> Unit, onCancel: () -> Unit) {
    // The games on our shelf first: that is almost always where a shared result comes from.
    val games = remember { app.catalog.games.sortedByDescending { app.catalog.inLib(it, state) } }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.share_which_game), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        Text(stringResource(R.string.share_which_game_hint), fontSize = 18.sp, color = Palette.Ink2)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(games, key = { it.id }) { g ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Card)
                        .clickable { onPick(g) }.padding(horizontal = 16.dp)
                        .semantics { role = Role.Button; contentDescription = g.n },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(g.n, fontSize = 20.sp, color = Palette.Ink, fontWeight = FontWeight.Medium)
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Tint).clickable(onClick = onCancel)
                .semantics { role = Role.Button; contentDescription = "ביטול" },
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.result_cancel), fontSize = 20.sp, color = Palette.Ink, fontWeight = FontWeight.SemiBold) }
    }
}
