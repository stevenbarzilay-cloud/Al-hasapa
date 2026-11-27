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
import app.couch.alhasapa.BuildConfig
import app.couch.alhasapa.data.Solo
import app.couch.alhasapa.data.Couple as People
import app.couch.alhasapa.remote.RelayStore
import app.couch.alhasapa.remote.RemoteStore
import app.couch.alhasapa.remote.SoloRelay
import app.couch.alhasapa.ui.Challenger
import app.couch.alhasapa.ui.PlayModeDialog
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import app.couch.alhasapa.ui.theme.Palette
import java.time.LocalDate

/**
 * The Android share target. Press Share in a game (or any app), choose "תוצאה על הספה", and the result is filled in for tonight:
 * the game is recognised from the header (else asked), a head-to-head result asks "who played?", then the same sheet as in the
 * player shows what was understood and what it is worth. Nothing is saved until "שמירה".
 */
class ShareReceiverActivity : ComponentActivity() {
    /**
     * A photo of a computer's screen (1.11.1): the camera app writes it into the cache, its text is read on the phone, the line that
     * holds the result goes into the sheet ([photoLine], said by [photoNote]), and the photo is deleted.
     */
    private var photoLine by mutableStateOf<String?>(null)
    private var photoNote by mutableStateOf<Int?>(null)
    private var photoRound by androidx.compose.runtime.mutableIntStateOf(0)
    private var photoKind = "guesses"
    private val photoFile by lazy { java.io.File(cacheDir, "photos/screen.jpg").apply { parentFile?.mkdirs() } }
    private val takePhoto = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok ->
        if (ok) readPhoto(photoUri()) else photoFile.delete()
    }
    private val pickPhoto = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) readPhoto(uri)
    }

    private fun photoUri(): android.net.Uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.photos", photoFile)

    private fun askPhoto(camera: Boolean) {
        if (camera) { photoFile.delete(); takePhoto.launch(photoUri()) }
        else pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** The picture's text, read on the phone: only the line with the result is kept, and the photo goes. */
    internal fun readPhoto(uri: android.net.Uri) {
        PhotoText.reader.read(this, uri) { r ->
            runOnUiThread {
                photoFile.delete()
                when (r) {
                    is PhotoRead.Lines -> {
                        val line = PhotoText.resultLine(r.lines, photoKind)
                        photoLine = line
                        photoNote = if (line != null) R.string.result_photo_read else R.string.result_photo_none
                        photoRound++
                        app.couch.alhasapa.diag.Flight.log("photo: ${r.lines.size} lines read, ${if (line != null) "a result found" else "no result"}")
                    }
                    PhotoRead.NotReady -> Toast.makeText(this, R.string.result_photo_wait, Toast.LENGTH_LONG).show()
                    PhotoRead.Failed -> Toast.makeText(this, R.string.result_photo_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as CouchApp
        // 1.11: a result typed in for a game played alone elsewhere (a computer), from the home's "together or alone?".
        val manualSolo = intent?.getBooleanExtra(EXTRA_SOLO, false) == true
        val text = if (manualSolo) "" else sharedText(intent)
        if (text == null) {
            Toast.makeText(this, R.string.share_not_text, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val pieces = PiecesConfig.load(this)
        val day = CouchState.dayKey(LocalDate.now())
        val detected = if (manualSolo) intent.getStringExtra(EXTRA_GAME)?.let { app.catalog.game(it) } else GameDetector.detect(text, app.catalog)
        if (manualSolo && detected == null) { finish(); return }
        // Who holds this phone, if they are here this evening: only they can play alone on it.
        val me = RemoteStore(this).me?.takeIf { w -> People.everyone(app.repo.snapshot(), People.evening()).any { it.who == w } }
        val relay = RelayStore(this)

        setContent {
            AlHasapaTheme {
                var game by remember { mutableStateOf(detected) }
                var who by remember { mutableStateOf<String?>(null) }
                // Together or alone (1.11), on a phone whose holder is known: kept for the game and the day, asked when not yet.
                var mode by remember { mutableStateOf(if (manualSolo) "solo" else null) }
                var solo by remember { mutableStateOf<SoloOutcome?>(null) }
                val state = remember { app.repo.snapshot() }
                val (nameA, nameB) = CouchState.names(state)
                val couple = Couple(nameA, nameB, pieces.styleFor(state, "a"), pieces.styleFor(state, "b"))

                Box(Modifier.fillMaxSize().background(Palette.Cream).safeDrawingPadding(), contentAlignment = Alignment.Center) {
                    val g = game
                    val o = solo
                    val askMode = g != null && !g.isDuel && me != null && !BuildConfig.TV && (mode ?: relay.mode(day, g.id)) == null
                    val alone = g != null && !g.isDuel && me != null && (mode ?: relay.mode(day, g.id)) == "solo"
                    if (o != null) {
                        SoloCard(o, other = when (me) { "a" -> nameB; "b" -> nameA; else -> null }, reduceMotion = false, onDone = { finish() }, modifier = Modifier.padding(16.dp))
                    } else if (g == null) {
                        GamePicker(app, state, onPick = { game = it }, onCancel = { finish() })
                    } else if (askMode) {
                        PlayModeDialog(
                            gameName = g.n,
                            challengers = Solo.sides(state, day, g.id).filter { it.who != me }
                                .map { s -> Challenger(People.name(state, s.who), pieces.styleOf(People.person(state, s.who)), s.who in People.WHO) },
                            onTogether = { relay.setMode(day, g.id, "together"); mode = "together" },
                            onSolo = { relay.setMode(day, g.id, "solo"); mode = "solo" },
                            onElsewhere = null,
                            onDismiss = { finish() },
                        )
                    } else androidx.compose.runtime.key(photoRound) {
                        ResultSheet(
                            support = ResultSupport(g, state, day),
                            couple = couple,
                            who = if (alone) me else who,
                            initialText = photoLine ?: text,
                            captured = !manualSolo,
                            onSave = { input ->
                                if (alone) {
                                    SoloPlay.save(app.repo, g, day, me, input)
                                    Thread({ runCatching { SoloRelay(applicationContext, app.repo).let { r -> r.send(me); r.receive(me) } } }, "solo-send").start()
                                    solo = SoloPlay.outcome(app.repo.snapshot(), g, day, me, pieces) ?: run { finish(); null }
                                } else { save(app, g, day, who, input, couple); finish() }
                            },
                            onClear = null,
                            onCancel = { finish() },
                            modifier = Modifier.padding(12.dp),
                            solo = alone,
                            // A game played elsewhere (1.11.1): its result from a photo of the screen, on a phone.
                            onPhoto = if (manualSolo && !BuildConfig.TV) ({ camera -> photoKind = ResultDraft.kindOf(g); askPhoto(camera) }) else null,
                            photoNote = photoNote?.let { stringResource(it) },
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
        const val EXTRA_GAME = "game"
        const val EXTRA_SOLO = "solo"

        /** A result typed in by hand for [gameId], played alone elsewhere (1.11). */
        fun soloIntent(context: android.content.Context, gameId: String): Intent =
            Intent(context, ShareReceiverActivity::class.java).putExtra(EXTRA_GAME, gameId).putExtra(EXTRA_SOLO, true)

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
