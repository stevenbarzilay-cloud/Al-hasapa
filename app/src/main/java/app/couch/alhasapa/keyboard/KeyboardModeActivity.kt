package app.couch.alhasapa.keyboard

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.couch.alhasapa.diag.Flight
import app.couch.alhasapa.remote.KeyClient
import app.couch.alhasapa.remote.KeyLink
import app.couch.alhasapa.remote.LoginHandoff
import app.couch.alhasapa.remote.LoginReceiver
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.ui.theme.AlHasapaTheme

/**
 * Keyboard mode on the phone: while it is open the phone is the TV app's keyboard. It connects when it comes to the front and lets go when it
 * leaves (the TV then shows the phone is gone). "משחק" at the top goes back to playing on the phone.
 */
class KeyboardModeActivity : ComponentActivity() {
    private lateinit var client: KeyClient
    /** What touch mode remembers for each game (2.0.2: upright or on its side). */
    private val touchPrefs by lazy { getSharedPreferences("keyboard_mode", MODE_PRIVATE) }
    /** Which of us holds this phone (1.9.9), as the screen shows it. */
    private val me = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** The visit under way (2.0), if any: the keyboard then is the friend's TV's, and who holds the phone is who they are there. */
    private val visit = androidx.compose.runtime.mutableStateOf<app.couch.alhasapa.remote.Visit?>(null)

    private val askLocalNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) app.couch.alhasapa.remote.Links.acquire(this, this) else client.noPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Sideways, and the whole screen: the phone is a keyboard (and a touchpad), and the typing shows on the TV.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
        // The phone's one connection (2.0): to the home's own TV, or, during a visit, to the friend's. Never to any other.
        client = app.couch.alhasapa.remote.Links.client(this)
        val couch = application as CouchApp
        val store = app.couch.alhasapa.remote.RemoteStore(this)
        val couches = app.couch.alhasapa.remote.CouchStore(this)
        visit.value = couches.visitNow()
        me.value = visit.value?.me ?: store.me
        val pieces = app.couch.alhasapa.data.PiecesConfig.load(this)
        val sink = object : KeySink {
            override fun text(s: String) = client.send(KeyLink.Msg.Text(s))
            override fun key(k: KeyLink.Key, a: KeyLink.Action) = client.send(KeyLink.Msg.Press(k, a))
            override fun code(c: String) = client.sendCode(c)
            override fun retry() = client.retry()
            override fun address(a: String) = client.useAddress(a)
            override fun permission() = askLocalNetwork.launch(LOCAL_NETWORK)
            override fun pointer(m: KeyLink.Msg) = client.send(m)

            // The sign-in handoff: what the phone's own browser holds (the shared "games" profile), read on the main thread.
            override fun logins(): List<LoginHandoff.Item> = (application as CouchApp).catalog.sites.values.filter(LoginHandoff::canHand)
                .map { LoginHandoff.Item(it.id, it.name, LoginHandoff.signedIn(it, LoginReceiver.jar(it.profile))) }

            override fun sendLogin(site: String) {
                // A login goes to the home's own TV only: never to a friend's (the client refuses it too).
                if (visit.value != null) { client.loginsFailed(site, "guest"); return }
                val s = (application as CouchApp).catalog.sites[site] ?: return
                val cookies = LoginHandoff.collect(s, LoginReceiver.jar(s.profile))
                if (cookies.isEmpty() || LoginHandoff.signedIn(s, LoginReceiver.jar(s.profile)) == false) { client.loginsFailed(site, "no-login"); return }
                Flight.log("logins: sending ${s.name} to the TV (${cookies.size} cookies)")
                client.sendLogins(site, LoginHandoff.payload(site, cookies))
            }

            override fun saveResult() = client.send(KeyLink.Msg.SaveResult)

            // Touch mode: the TV's game pictured here, and the fingers on it sent back.
            override fun mirror(on: Boolean, width: Int, crop: KeyLink.Crop?, up: Float) = client.mirror(on, width, crop, up)
            override fun frameShown(n: Int) = client.frameShown(n)
            override fun touch(a: KeyLink.TouchAction, x: Float, y: Float) = client.send(KeyLink.Msg.Touch(a, x, y))

            // Each of us on our own phone: who holds this one and how they look, kept here and told to the TV (which tells the other phone).
            override fun saveMe(p: app.couch.alhasapa.data.Person) {
                // At a friend's, who the phone's holder is there is chosen on "my couches", and never changes the home's players.
                if (visit.value != null) return
                store.me = p.who; me.value = p.who
                couch.repo.update { app.couch.alhasapa.data.Couple.put(it, p) }
                client.sendCouple()
                Flight.log("me: this phone is ${if (p.who == "a") "the first" else "the second"} of us")
            }

            override fun sendNote(to: String, game: String?, text: String, doodle: List<IntArray>, ink: List<Int>) {
                // Written as the player the holder is at this couch, and kept for this couch's TV only.
                val who = visit.value?.me ?: store.me ?: return
                client.sendNote(
                    app.couch.alhasapa.data.Note(app.couch.alhasapa.remote.RemoteStore.randomHex(8), who, game, text, doodle, System.currentTimeMillis(), to = to, ink = ink),
                    couch = visit.value?.couch,
                )
                Flight.log("notes: one written${if (game != null) " for a game" else " for the home"}")
            }

            override fun couches() { startActivity(android.content.Intent(this@KeyboardModeActivity, app.couch.alhasapa.couches.CouchesActivity::class.java)) }

            override fun answerHome(tv: KeyClient.Tv, home: Boolean) {
                app.couch.alhasapa.remote.CouchSetup.answerHome(this@KeyboardModeActivity, tv, home)
                app.couch.alhasapa.remote.Links.refresh(this@KeyboardModeActivity)
            }

            override fun removePerson(who: String) {
                if (visit.value != null) return
                couch.repo.update { app.couch.alhasapa.data.Couple.remove(it, who, System.currentTimeMillis()) }
                if (store.me == who) { store.me = null; me.value = null }
                client.sendCouple()
            }

            // Touch mode upright (2.0.2), kept for each game.
            override fun touchUpright(game: String): Boolean = touchPrefs.getBoolean("upright|$game", false)
            override fun setTouchUpright(game: String, upright: Boolean) { touchPrefs.edit { putBoolean("upright|$game", upright) } }
            override fun orient(upright: Boolean) {
                requestedOrientation = if (upright) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }

            // The site's own sign-in page, here on the phone, in the same browser the login is then read from.
            override fun signIn(site: String) {
                val s = (application as CouchApp).catalog.sites[site] ?: return
                Flight.log("logins: signing in to ${s.name} on the phone")
                startActivity(app.couch.alhasapa.player.PlayerActivity.loginIntent(this@KeyboardModeActivity, s.id, s.label, app.couch.alhasapa.player.ProfileNames.GAMES))
            }
        }
        val openLogins = intent.getBooleanExtra(EXTRA_LOGINS, false)
        setContent {
            AlHasapaTheme {
                val state by client.state.collectAsState()
                val handoff by client.handoff.collectAsState()
                val versions by client.versions.collectAsState()
                val frame by client.frame.collectAsState()
                val frameNone by client.frameNone.collectAsState()
                val revision by couch.repo.revision.collectAsState()
                val shared by client.shared.collectAsState()
                val v = visit.value
                // The players: the home's own; at a friend's, that couch's, as its TV names them (kept apart from the home's).
                val people = androidx.compose.runtime.remember(revision, shared, v) {
                    val evening = app.couch.alhasapa.data.Couple.evening()
                    if (v != null) couches.people(v.couch).filter { !it.removed && (!it.guest || it.day == evening) }
                    else app.couch.alhasapa.data.Couple.everyone(couch.repo.snapshot(), evening)
                }
                val noteGames = androidx.compose.runtime.remember(revision) {
                    val s = couch.repo.snapshot(); couch.catalog.games.filter { couch.catalog.inLib(it, s) }.map { NoteGame(it.id, it.n) }
                }
                val notesWaiting by client.notesWaiting.collectAsState()
                KeyboardModeScreen(
                    state, sink, onGame = { finish() }, handoff = handoff, versions = versions, openLogins = openLogins,
                    frame = frame, frameNone = frameNone,
                    people = people, me = me.value, pieces = pieces, noteGames = noteGames, notesWaiting = notesWaiting,
                    visiting = v != null,
                )
            }
        }
        Flight.log("keyboard mode opened")
    }

    override fun onStart() {
        super.onStart()
        // (The visit may have ended, or begun, while this screen was behind another.)
        val v = app.couch.alhasapa.remote.CouchStore(this).visitNow()
        visit.value = v
        me.value = v?.me ?: app.couch.alhasapa.remote.RemoteStore(this).me
        if (needsLocalNetwork()) askLocalNetwork.launch(LOCAL_NETWORK) else app.couch.alhasapa.remote.Links.acquire(this, this)
    }

    override fun onStop() {
        super.onStop()
        app.couch.alhasapa.remote.Links.release(this)
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

    /** Android 17 asks the person before an app may reach devices on the home network (the TV). Earlier versions do not. */
    private fun needsLocalNetwork(): Boolean =
        Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED

    companion object {
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

        /** Open with the sign-in list showing (from the accounts screen's "send to the TV"). */
        const val EXTRA_LOGINS = "logins"

        fun loginsIntent(context: android.content.Context): android.content.Intent =
            android.content.Intent(context, KeyboardModeActivity::class.java).putExtra(EXTRA_LOGINS, true)
    }
}
