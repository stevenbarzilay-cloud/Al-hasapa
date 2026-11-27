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
import app.couch.alhasapa.remote.GuestSolo
import app.couch.alhasapa.couches.CouchTargetDialog
import app.couch.alhasapa.data.Couches
import app.couch.alhasapa.remote.SoloTarget
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
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
        val today = CouchState.dayKey(LocalDate.now())
        val yesterday = CouchState.dayKey(LocalDate.now().minusDays(1))
        val detected = if (manualSolo) intent.getStringExtra(EXTRA_GAME)?.let { app.catalog.game(it) } else GameDetector.detect(text, app.catalog, app.repo.snapshot())
        if (manualSolo && detected == null) { finish(); return }
        // Who holds this phone, if they are here this evening: only they can play alone on it.
        val me = RemoteStore(this).me?.takeIf { w -> People.everyone(app.repo.snapshot(), People.evening()).any { it.who == w } }
        val relay = RelayStore(this)
        val ctx = this

        setContent {
            AlHasapaTheme {
                var game by remember { mutableStateOf(detected) }
                var who by remember { mutableStateOf<String?>(null) }
                // Together or alone (1.11), on a phone whose holder is known: kept for the game and the day, asked when not yet.
                var mode by remember { mutableStateOf(if (manualSolo) "solo" else null) }
                var solo by remember { mutableStateOf<SoloOutcome?>(null) }
                // Which day's puzzle the result is of (2.0): today's, or yesterday's reported the day after.
                var day by remember { mutableStateOf(today) }
                // Where a result played alone goes (2.0): kept with the game and the day once it was said; asked during a visit.
                var picked by remember { mutableStateOf<Pair<String, SoloTarget>?>(null) }
                val guestChanges by GuestSolo.changes.collectAsState()
                val state = remember { app.repo.snapshot() }
                val (nameA, nameB) = CouchState.names(state)
                val couple = Couple(nameA, nameB, pieces.styleFor(state, "a"), pieces.styleFor(state, "b"))

                Box(Modifier.fillMaxSize().background(Palette.Cream).safeDrawingPadding(), contentAlignment = Alignment.Center) {
                    val g = game
                    val o = solo
                    val askMode = g != null && !g.isDuel && me != null && !BuildConfig.TV && (mode ?: relay.mode(day, g.id)) == null
                    val alone = g != null && !g.isDuel && me != null && (mode ?: relay.mode(day, g.id)) == "solo"
                    val need = if (alone && g != null) remember(day, g.id, picked) {
                        picked?.takeIf { it.first == "$day|${g.id}" }?.let { SoloTargets.Need.Go(it.second) } ?: SoloTargets.resolve(ctx, app.repo, day, g.id)
                    } else null
                    val target = (need as? SoloTargets.Need.Go)?.t
                    val guest = target?.takeIf { !SoloTargets.isHome(it, app.repo) }
                    // At a friend's couch: the card follows what its TV says (the side arrived there; the others handed in).
                    androidx.compose.runtime.LaunchedEffect(guestChanges) {
                        if (solo != null && guest != null && g != null) GuestPlay.outcome(ctx, g, day, guest, pieces)?.let { solo = it }
                    }
                    if (o != null) {
                        SoloCard(o, other = when (me) { "a" -> nameB; "b" -> nameA; else -> null }.takeIf { guest == null }, reduceMotion = false, onDone = { finish() }, modifier = Modifier.padding(16.dp))
                    } else if (g == null) {
                        GamePicker(app, state, onPick = { game = it }, onCancel = { finish() })
                    } else if (askMode) {
                        PlayModeDialog(
                            gameName = g.n,
                            challengers = Solo.players(state, day, g.id).filter { it.who != me }
                                .map { s -> Challenger(People.name(state, s.who), pieces.styleOf(People.person(state, s.who)), s.who in People.WHO) },
                            onTogether = { relay.setMode(day, g.id, "together"); mode = "together" },
                            onSolo = { relay.setMode(day, g.id, "solo"); mode = "solo" },
                            onElsewhere = null,
                            onDismiss = { finish() },
                        )
                    } else if (need is SoloTargets.Need.Ask) {
                        // A visit is on: which couch this result is for, said before anything is saved.
                        CouchTargetDialog(
                            g.n, need.home, need.visit,
                            onPick = { t -> SoloTargets.choose(ctx, day, g.id, t); picked = "$day|${g.id}" to t },
                            onDismiss = { finish() },
                        )
                    } else androidx.compose.runtime.key(photoRound, day, target?.couch) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // A game played alone and reported later: which day's puzzle it was (yesterday's, the day after).
                            if (alone) DayPicker(day, today, yesterday) { day = it }
                            ResultSheet(
                                // (For a friend's couch the sheet is shown this phone's own side there, and nothing of the home's evening.)
                                support = ResultSupport(g, if (guest != null) guestState(guest, g, day) else state, day),
                                couple = couple,
                                who = if (alone) (target?.who ?: me) else who,
                                initialText = photoLine ?: text,
                                captured = !manualSolo,
                                onSave = { input ->
                                    if (alone && target != null) {
                                        SoloTargets.choose(ctx, day, g.id, target)
                                        if (guest != null) {
                                            // For the friend's couch only: kept here apart from the home, and handed to that couch's TV.
                                            GuestPlay.save(ctx, g, day, guest, input)
                                            solo = GuestPlay.outcome(ctx, g, day, guest, pieces) ?: run { finish(); null }
                                        } else {
                                            SoloPlay.save(app.repo, g, day, target.who, input)
                                            Thread({ runCatching { SoloRelay(applicationContext, app.repo).let { r -> r.send(target.who); r.receive(target.who) } } }, "solo-send").start()
                                            solo = SoloPlay.outcome(app.repo.snapshot(), g, day, target.who, pieces)?.copy(couch = target.name) ?: run { finish(); null }
                                        }
                                    } else { save(app, g, day, who, input, couple); finish() }
                                },
                                onClear = null,
                                onCancel = { finish() },
                                solo = alone,
                                // A game played elsewhere (1.11.1): its result from a photo of the screen, on a phone.
                                onPhoto = if (manualSolo && !BuildConfig.TV) ({ camera -> photoKind = ResultDraft.kindOf(g); askPhoto(camera) }) else null,
                                photoNote = photoNote?.let { stringResource(it) },
                                saveLabel = if (alone && target != null) stringResource(R.string.solo_save_in, Couches.inCouch(target.name)) else null,
                                whereNote = if (alone && target != null) stringResource(
                                    R.string.solo_who_where, target.whoName, target.name, stringResource(if (day == today) R.string.day_today else R.string.day_yesterday),
                                ) else null,
                            )
                        }
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

    /** What the sheet is shown of a game played alone for a friend's couch: this phone's own side there, if any. */
    private fun guestState(t: SoloTarget, game: Game, day: String): org.json.JSONObject =
        CouchState.empty().also { s -> GuestPlay.side(this, game, day, t)?.let { side -> Solo.put(s, day, game.id, side.copy(who = t.who)) } }

    // During a visit (2.0) the phone's connection to the friend's TV is open while this screen is in front: a result for that couch
    // reaches it at once.
    override fun onStart() {
        super.onStart()
        if (!BuildConfig.TV && app.couch.alhasapa.remote.CouchStore(this).visitNow() != null) app.couch.alhasapa.remote.Links.acquire(this, this)
    }

    override fun onStop() {
        super.onStop()
        if (!BuildConfig.TV) app.couch.alhasapa.remote.Links.release(this)
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

const val DAY_TODAY_TAG = "day-today"
const val DAY_YESTERDAY_TAG = "day-yesterday"

/** Which day's puzzle a result played alone is of: today's, or yesterday's (reported the day after). Said in words on each. */
@androidx.compose.runtime.Composable
private fun DayPicker(day: String, today: String, yesterday: String, onPick: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.day_pick), fontSize = 16.sp, color = Palette.Ink2)
        for ((k, label, tag) in listOf(Triple(today, R.string.day_today, DAY_TODAY_TAG), Triple(yesterday, R.string.day_yesterday, DAY_YESTERDAY_TAG))) {
            val on = k == day
            val text = stringResource(label)
            Box(
                Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Palette.Ink else Palette.Tint).clickable { onPick(k) }
                    .semantics { role = Role.RadioButton; contentDescription = text; this.selected = on }
                    .then(Modifier.testTagCompat(tag)).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (on) Palette.Cream else Palette.Ink) }
        }
    }
}

private fun Modifier.testTagCompat(tag: String): Modifier = this.then(Modifier.testTag(tag))

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
