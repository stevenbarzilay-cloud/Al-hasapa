package app.couch.alhasapa.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.diag.Diag
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import java.time.LocalDate
import org.json.JSONObject

/**
 * The isolated player: landscape (the phone sits sideways, cast to the TV), immersive full screen, screen kept on, the game in
 * its own browser profile. It is a separate activity so its orientation and system-bar rules never touch the hub, which
 * works in both orientations.
 */
class PlayerActivity : ComponentActivity() {
    private lateinit var session: PlaySession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CouchApp
        session = buildSession(app) ?: run { finish(); return }
        Diag.log("player: ${session.title} (${if (session.loginMode) "login" else "game"}) profile ${session.profile}")

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        hideSystemBars()

        setContent {
            AlHasapaTheme {
                val revision by app.repo.revision.collectAsState()
                val played = revision >= 0 && session.gameId != null &&
                    CouchState.entry(app.repo.snapshot(), today(), session.gameId!!) != null
                PlayerScreen(
                    session = session,
                    played = played,
                    onTogglePlayed = { togglePlayed(app) },
                    onClose = { finish() },
                    onOpenInChrome = { url -> ChromeTab.open(this, url) },
                )
            }
        }
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
            PlaySession.forGame(app.catalog, app.repo.snapshot(), game, intent.getStringExtra(EXTRA_WHO))
        } else {
            val site = app.catalog.sites[intent.getStringExtra(EXTRA_SITE)] ?: return null
            PlaySession.forLogin(site, intent.getStringExtra(EXTRA_TITLE) ?: site.name, intent.getStringExtra(EXTRA_PROFILE) ?: ProfileNames.GAMES)
        }
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

        fun gameIntent(context: Context, gameId: String, who: String?): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_GAME, gameId).apply { if (who != null) putExtra(EXTRA_WHO, who) }

        fun loginIntent(context: Context, siteId: String, title: String, profile: String): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_SITE, siteId).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_PROFILE, profile)
    }
}
