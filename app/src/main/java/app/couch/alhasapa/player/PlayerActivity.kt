package app.couch.alhasapa.player

import app.couch.alhasapa.results.SoloPlay
import app.couch.alhasapa.results.SoloOutcome
import app.couch.alhasapa.results.ResultInput
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.data.Solo
import app.couch.alhasapa.remote.SoloRelay
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import app.couch.alhasapa.tv.TvHost
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.display.DisplayStore
import app.couch.alhasapa.results.ResultStore
import app.couch.alhasapa.diag.Flight
import app.couch.alhasapa.scoring.Scoring
import app.couch.alhasapa.results.ResultDraft
import app.couch.alhasapa.results.Cheers
import app.couch.alhasapa.results.Cheer
import app.couch.alhasapa.results.ResultSupport
import app.couch.alhasapa.remote.FrameSource
import android.widget.Toast
import app.couch.alhasapa.R
import app.couch.alhasapa.diag.Diag
import app.couch.alhasapa.ui.animationsRemoved
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import java.time.LocalDate
import org.json.JSONObject
import app.couch.alhasapa.tv.NoteView
import app.couch.alhasapa.data.Notes

/**
 * The isolated player: landscape (the phone sits sideways, cast to the TV), immersive full screen, screen kept on, the game in
 * its own browser profile. It is a separate activity so its orientation and system-bar rules never touch the hub, which
 * works in both orientations.
 */
class PlayerActivity : ComponentActivity() {
    private lateinit var session: PlaySession
    private lateinit var tv: TvHost

    /** For the tests: the remote of this screen. */
    internal val remote get() = tv.controller


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TvHost(this)
        val app = application as CouchApp
        session = buildSession(app) ?: run { finish(); return }
        tv.title = session.title
        Diag.log("player: ${session.title} (${if (session.loginMode) "login" else "game"}) profile ${session.profile}")
        // Touch mode (1.9.8): a game played by dragging turns the phone to touch mode by itself, and the phone is shown the game's page.
        if (tv.enabled) {
            remote.touchGame = !session.loginMode && session.gameId?.let { app.catalog.game(it)?.touch } == true
            remote.frameSource = FrameSource { w, crop, done -> picturePage(w, crop, done) }
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Cut-outs (the Pixel's camera hole) exist from Android 9; the TV build runs on older versions too.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        hideSystemBars()

        val reduceMotion = animationsRemoved(contentResolver)
        val profiles = DisplayStore.load(this)
        val pieces = PiecesConfig.load(this)
        setContent {
            AlHasapaTheme {
                val revision by app.repo.revision.collectAsState()
                // Re-read whenever the state changes (a tick, a saved display profile, a result).
                val state = remember(revision) { app.repo.snapshot() }
                val played = session.gameId != null && (
                    if (session.solo) Solo.side(state, today(), session.gameId!!, session.who.orEmpty()) != null
                    else CouchState.entry(state, today(), session.gameId!!) != null
                )
                val info = remember(revision) { PlayerInfo.from(state, app.catalog, pieces, today(), session.who) }
              tv.Layer {
               androidx.compose.runtime.CompositionLocalProvider(LocalSolo provides session.solo) {
                PlayerScreen(
                    session = session,
                    played = played,
                    info = info,
                    profiles = profiles,
                    overrides = DisplayStore.overrides(state),
                    onSaveDisplay = { key, p -> DisplayStore.save(app.repo, key, p) },
                    onResetDisplay = { key -> DisplayStore.reset(app.repo, key) },
                    result = session.gameId?.let { id -> app.catalog.game(id) }
                        ?.takeIf { !(it.isDuel && session.who == null) }
                        ?.let { ResultSupport(it, state, today()) },
                    onSaveResult = { input -> saveResult(app, input) },
                    onSaveSolo = if (session.solo) ({ input -> saveSolo(app, pieces, input) }) else null,
                    soloAgain = { soloOutcome(app, pieces) },
                    revision = revision,
                    soloOther = when (session.who) { "a" -> info.nameB; "b" -> info.nameA; else -> null },
                    onAutoPlayed = { if (session.solo) markSolo(app) else markPlayed(app) },
                    // The notes one of us left the other for this game (shown after its result is saved and celebrated).
                    notesFor = {
                        val id = session.gameId
                        if (id == null) emptyList() else {
                            val s = app.repo.snapshot()
                            Notes.waitingFor(s, id).map { NoteView.of(it, s, pieces, app.catalog) }
                        }
                    },
                    onNoteSeen = { id ->
                        app.repo.update { s -> Notes.markSeen(s, id, System.currentTimeMillis()) }
                        Flight.log("notes: one was read after its game")
                    },
                    onClearResult = { if (session.solo) clearSolo(app) else clearResult(app) },
                    onTogglePlayed = { if (session.solo) toggleSolo(app) else togglePlayed(app) },
                    reduceMotion = reduceMotion,
                    onClose = { finish() },
                    onOpenInChrome = { url -> ChromeTab.open(this, url) },
                )
               }
              }
            }
        }
    }

    /** Touch mode: one bitmap, drawn into again for each picture (the link reads it before it asks for the next). */
    private var pictureBitmap: android.graphics.Bitmap? = null

    /**
     * A picture of the game's page for a phone in touch mode: the window's own pixels where the page is (PixelCopy, so whatever the
     * graphics chip drew, a game's canvas included), made at most [maxWidth] wide by the copy itself.
     */
    private fun picturePage(maxWidth: Int, crop: app.couch.alhasapa.remote.KeyLink.Crop?, done: (android.graphics.Bitmap?) -> Unit) {
        val box = remote.pageBox
        if (box == null || isFinishing || isDestroyed) { done(null); return }
        // A phone zoomed in: only that part of the page, so it is as sharp there as the TV's own pixels allow.
        val bw = box.r - box.l
        val bh = box.b - box.t
        val src = if (crop == null) android.graphics.Rect(box.l.toInt(), box.t.toInt(), box.r.toInt(), box.b.toInt())
        else android.graphics.Rect((box.l + crop.x0 * bw).toInt(), (box.t + crop.y0 * bh).toInt(), (box.l + crop.x1 * bw).toInt(), (box.t + crop.y1 * bh).toInt())
        if (src.width() < 16 || src.height() < 16) { done(null); return }
        val w = minOf(maxWidth, src.width()).coerceAtLeast(16)
        val h = (src.height().toLong() * w / src.width()).toInt().coerceAtLeast(16)
        val bmp = pictureBitmap?.takeIf { it.width == w && it.height == h }
            ?: androidx.core.graphics.createBitmap(w, h).also { pictureBitmap = it }
        runCatching {
            android.view.PixelCopy.request(
                window, src, bmp, { result -> done(if (result == android.view.PixelCopy.SUCCESS) bmp else null) },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
        }.onFailure { Flight.log("touch mode: the TV could not picture the page (${it.javaClass.simpleName})"); done(null) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun buildSession(app: CouchApp): PlaySession? {
        val gameId = intent.getStringExtra(EXTRA_GAME)
        return if (gameId != null) {
            val game = app.catalog.game(gameId) ?: return null
            PlaySession.forGame(app.catalog, app.repo.snapshot(), game, intent.getStringExtra(EXTRA_WHO), intent.getBooleanExtra(EXTRA_SOLO, false))
        } else {
            val site = app.catalog.sites[intent.getStringExtra(EXTRA_SITE)] ?: return null
            PlaySession.forLogin(site, intent.getStringExtra(EXTRA_TITLE) ?: site.name, intent.getStringExtra(EXTRA_PROFILE) ?: ProfileNames.GAMES)
        }
    }

    /**
     * Saves a result. On a TV a saved result is celebrated on the screen (what this returns); everywhere else, and for a head-to-head
     * side, a short message says how it went.
     */
    private fun saveResult(app: CouchApp, input: ResultInput): Cheer? {
        val game = session.gameId?.let { app.catalog.game(it) } ?: return null
        val before = app.repo.snapshot()
        val names = CouchState.names(before)
        val kind = ResultDraft.kindOf(game)
        val hadBest = Scoring.bestTime(before.optJSONObject("log") ?: JSONObject(), game.id, today()) != null
        val o = ResultStore.save(app.repo, game, today(), session.who, input)
        if (tv.enabled) {
            val evening = Scoring.dayPts(app.repo.snapshot().optJSONObject("log") ?: JSONObject(), today())
            Cheers.of(o, kind, hadBest, evening)?.let { return it }
        }
        val msg = when (o) {
            is ResultStore.Outcome.Saved -> getString(R.string.result_saved_pts, o.points)
            ResultStore.Outcome.MarkedOnly -> getString(R.string.result_marked)
            ResultStore.Outcome.Nothing -> getString(R.string.result_nothing)
            is ResultStore.Outcome.DuelSide -> when (o.winner) {
                null -> getString(R.string.result_duel_wait)
                "t" -> getString(R.string.result_duel_tie)
                else -> getString(R.string.result_duel_win, if (o.winner == "a") names.first else names.second, game.n)
            }
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        return null
    }

    /** A result of a game played alone (1.11): kept, sent to the relay, and answered with its card (sealed, or the reveal). */
    private fun saveSolo(app: CouchApp, pieces: PiecesConfig, input: ResultInput): SoloOutcome? {
        val game = session.gameId?.let { app.catalog.game(it) } ?: return null
        val who = session.who ?: return null
        SoloPlay.save(app.repo, game, today(), who, input)
        Flight.log("solo: ${game.n} played alone, its result sealed")
        tellRelay(app, who)
        return soloOutcome(app, pieces)
    }

    /** The solo card as the state has it now (the other's result may have come in the meantime: the envelope opens). */
    private fun soloOutcome(app: CouchApp, pieces: PiecesConfig): SoloOutcome? {
        val game = session.gameId?.let { app.catalog.game(it) } ?: return null
        val who = session.who ?: return null
        return SoloPlay.outcome(app.repo.snapshot(), game, today(), who, pieces)
    }

    /** What changed alone goes to the relay, and what the others did comes back (a background thread; nothing waits for it). */
    private fun tellRelay(app: CouchApp, who: String) {
        val ctx = applicationContext
        Thread({ runCatching { val r = SoloRelay(ctx, app.repo); r.send(who); r.receive(who) } }, "solo-send").start()
    }

    private fun markSolo(app: CouchApp) {
        val game = session.gameId?.let { app.catalog.game(it) } ?: return
        val who = session.who ?: return
        if (SoloPlay.markPlayed(app.repo, game, today(), who)) {
            Flight.log("played: ${session.title} marked by itself, alone")
            Toast.makeText(this, R.string.solo_marked_toast, Toast.LENGTH_SHORT).show()
            tellRelay(app, who)
        }
    }

    private fun clearSolo(app: CouchApp) {
        val game = session.gameId?.let { app.catalog.game(it) } ?: return
        val who = session.who ?: return
        SoloPlay.clear(app.repo, game, today(), who)
        Toast.makeText(this, R.string.result_cleared, Toast.LENGTH_SHORT).show()
        tellRelay(app, who)
    }

    /** Alone, "played" is ticked by hand like the couch's tick; un-ticking it takes the result back, "played" stays (it travelled). */
    private fun toggleSolo(app: CouchApp) {
        val game = session.gameId?.let { app.catalog.game(it) } ?: return
        val who = session.who ?: return
        if (Solo.side(app.repo.snapshot(), today(), game.id, who) == null) SoloPlay.markPlayed(app.repo, game, today(), who)
        else SoloPlay.clear(app.repo, game, today(), who)
        tellRelay(app, who)
    }

    /** Marks today's "we played" by itself (after a while in the game); a tick that is already there stays as it is. */
    private fun markPlayed(app: CouchApp) {
        val id = session.gameId ?: return
        val day = today()
        var marked = false
        app.repo.update { s -> if (CouchState.entry(s, day, id) == null) { CouchState.setEntry(s, day, id, JSONObject().put("w", "").put("t", System.currentTimeMillis())); marked = true } }
        if (marked) {
            Flight.log("played: ${session.title} marked by itself")
            if (!tv.enabled) Toast.makeText(this, R.string.played_auto_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearResult(app: CouchApp) {
        val game = session.gameId?.let { app.catalog.game(it) } ?: return
        ResultStore.clear(app.repo, game, today(), session.who)
        Toast.makeText(this, R.string.result_cleared, Toast.LENGTH_SHORT).show()
    }

    /** Ticks or un-ticks today's "we played", like the tick in the hub. */
    private fun togglePlayed(app: CouchApp) {
        val id = session.gameId ?: return
        val day = today()
        app.repo.update { s ->
            if (CouchState.entry(s, day, id) != null) CouchState.setEntry(s, day, id, null)
            else CouchState.setEntry(s, day, id, JSONObject().put("w", "").put("t", System.currentTimeMillis()))
        }
    }

    private fun today(): String = CouchState.dayKey(LocalDate.now())

    companion object {
        const val EXTRA_GAME = "game"
        const val EXTRA_WHO = "who"
        const val EXTRA_SITE = "site"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PROFILE = "profile"
        /** Played alone by [EXTRA_WHO] (1.11). */
        const val EXTRA_SOLO = "solo"

        fun gameIntent(context: Context, gameId: String, who: String?, solo: Boolean = false): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_GAME, gameId).apply {
                if (who != null) putExtra(EXTRA_WHO, who)
                if (solo && who != null) putExtra(EXTRA_SOLO, true)
            }

        fun loginIntent(context: Context, siteId: String, title: String, profile: String): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_SITE, siteId).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_PROFILE, profile)
    }
}
