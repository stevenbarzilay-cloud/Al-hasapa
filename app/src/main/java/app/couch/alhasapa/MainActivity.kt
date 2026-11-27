package app.couch.alhasapa

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.LifecycleEventObserver
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.couch.alhasapa.home.HomeModel
import app.couch.alhasapa.home.HomeNav
import app.couch.alhasapa.home.TvHome
import app.couch.alhasapa.home.warmHomeArt
import app.couch.alhasapa.tv.TvPanel
import app.couch.alhasapa.remote.RemoteKeys
import java.time.LocalDate
import app.couch.alhasapa.accounts.AccountsActivity
import app.couch.alhasapa.keyboard.KeyboardModeActivity
import app.couch.alhasapa.bridge.HubBridge
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.player.PlayerActivity
import app.couch.alhasapa.ui.WhoPlaysDialog
import app.couch.alhasapa.diag.CrashReport
import app.couch.alhasapa.diag.Diag
import app.couch.alhasapa.diag.Diagnostics
import app.couch.alhasapa.diag.Flight
import app.couch.alhasapa.diag.Startup
import app.couch.alhasapa.ui.CrashScreen
import app.couch.alhasapa.diag.DiagOverlay
import app.couch.alhasapa.tv.TvHost
import app.couch.alhasapa.ui.AppRoot
import app.couch.alhasapa.ui.animationsRemoved
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import app.couch.alhasapa.tv.NoteCard
import app.couch.alhasapa.tv.NoteView
import app.couch.alhasapa.data.Notes
import app.couch.alhasapa.tv.TvMode
import app.couch.alhasapa.keyboard.noteFor
import app.couch.alhasapa.keyboard.NoteGame
import app.couch.alhasapa.keyboard.NoteDialog
import app.couch.alhasapa.keyboard.IdentityDialog
import app.couch.alhasapa.data.Note
import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.remote.PhoneSync
import app.couch.alhasapa.remote.RemoteStore
import app.couch.alhasapa.remote.RelayStore
import app.couch.alhasapa.remote.SoloJob
import app.couch.alhasapa.remote.SoloRelay
import app.couch.alhasapa.data.Solo
import app.couch.alhasapa.ui.Challenger
import app.couch.alhasapa.ui.PlayModeDialog
import app.couch.alhasapa.results.ShareReceiverActivity
import app.couch.alhasapa.results.SoloPlay
import app.couch.alhasapa.tv.SoloTvCard
import app.couch.alhasapa.couches.CouchTargetDialog
import app.couch.alhasapa.couches.CouchesActivity
import app.couch.alhasapa.couches.OpenContest
import app.couch.alhasapa.couches.VisitBar
import app.couch.alhasapa.couches.WelcomeScreen
import app.couch.alhasapa.data.Couches
import app.couch.alhasapa.remote.CouchStore
import app.couch.alhasapa.remote.KeyClient
import app.couch.alhasapa.remote.GuestSolo
import app.couch.alhasapa.remote.CouchSetup
import app.couch.alhasapa.remote.Links
import app.couch.alhasapa.remote.SoloTarget
import app.couch.alhasapa.results.SoloTargets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars

class MainActivity : ComponentActivity() {
    private lateinit var tv: TvHost


    /** The head-to-head game waiting for "who plays?" to be answered. */
    private var whoPlaysFor by mutableStateOf<String?>(null)

    /** On a phone (1.10): the who-am-I and note dialogs over the home, and a nudge for the hub to read the state again after them. */
    private var meOpen by mutableStateOf(false)
    private var noteOpen by mutableStateOf(false)
    private var hubReloads by androidx.compose.runtime.mutableIntStateOf(0)

    /** On a phone (1.11): the game waiting for "together or alone?", and a game to play alone once who holds the phone is said. */
    private var playModeFor by mutableStateOf<String?>(null)
    private var soloAfterMe: Pair<String, Boolean>? = null
    /** A game to play alone once the phone has asked whether it may say things when the app is closed (asked once, ever). */
    private var soloAfterAsk: Pair<String, Boolean>? = null
    private val notifyAsk = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        Flight.log("solo: notifications ${if (ok) "allowed" else "not allowed"}")
        soloAfterAsk?.let { (g, e) -> soloAfterAsk = null; launchSolo(g, e) }
        hubReloads++
    }

    /** Notes and changes waiting for the TV are handed over when the app comes to the front (see PhoneSync). */
    private val phoneSync by lazy { PhoneSync(this, onDone = { hubReloads++ }) }

    /** On a TV: the full web hub, opened from the native home ("הגדרות ועוד"); Back from it returns to the home. */
    private var webHub by mutableStateOf(false)

    /** How the remote moves on the TV's home, kept here so the home finds its place again after the hub or a game. */
    private val homeNav = HomeNav()

    /**
     * Opens a game, from the hub or the TV's home. Head to head (LinkedIn): each person plays alone, in their own account, so it asks
     * whose turn it is first. On the main thread.
     */
    private fun openGame(gameId: String, mode: String? = null) {
        val game = (application as CouchApp).catalog.game(gameId) ?: return
        if (game.isDuel) { whoPlaysFor = gameId; return }
        // On a phone (1.11): together or alone, asked once a game and a day.
        if (tv.enabled) { startActivity(PlayerActivity.gameIntent(this, gameId, null)); return }
        when (mode ?: RelayStore(this).mode(today(), gameId)) {
            SOLO -> playSolo(gameId, elsewhere = false)
            TOGETHER -> startActivity(PlayerActivity.gameIntent(this, gameId, null))
            else -> playModeFor = gameId
        }
    }

    private fun today(): String = CouchState.dayKey(LocalDate.now())

    /** Who holds this phone, if they are here this evening (a guest of another evening holds it no longer). */
    private fun heldBy(): String? {
        val me = RemoteStore(this).me ?: return null
        return me.takeIf { w -> Couple.everyone((application as CouchApp).repo.snapshot(), Couple.evening()).any { it.who == w } }
    }

    /**
     * A game played alone (1.11): who holds the phone is asked first when it is not known; the first time ever, whether the app may say
     * things with the app closed ("רוני כבר שיחק..."); then the game opens in that person's corner, or the result sheet for one played
     * [elsewhere] (a computer).
     */
    private fun playSolo(gameId: String, elsewhere: Boolean) {
        if (heldBy() == null) { soloAfterMe = gameId to elsewhere; meOpen = true; return }
        RelayStore(this).setMode(today(), gameId, SOLO)
        val relay = RelayStore(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 && !relay.notifyAsked &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            relay.notifyAsked = true
            soloAfterAsk = gameId to elsewhere
            notifyAsk.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchSolo(gameId, elsewhere)
    }

    /**
     * Couches (2.0): the game waiting for "which couch is this result for?", asked before a game is played alone during a visit; and
     * a nudge for what shows the visit to read it again.
     */
    private var targetAsk by mutableStateOf<Pair<String, SoloTargets.Need.Ask>?>(null)
    private var couchTick by androidx.compose.runtime.mutableIntStateOf(0)
    /** A new phone joined the couch of a TV it chose: who holds it is asked next. */
    private val joinCouch = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK && RemoteStore(this).me == null) meOpen = true
        hubReloads++; couchTick++
    }

    private fun launchSolo(gameId: String, elsewhere: Boolean) {
        heldBy() ?: return
        // Played elsewhere: the sheet asks which day's puzzle it was, and with it where the result goes.
        if (elsewhere) { Flight.log("solo: a game played alone, elsewhere"); startActivity(ShareReceiverActivity.soloIntent(this, gameId)); return }
        // Where it goes (2.0): the home couch, with no question; during a visit, the person says, before the game opens.
        when (val need = SoloTargets.resolve(this, (application as CouchApp).repo, today(), gameId)) {
            SoloTargets.Need.Who -> { soloAfterMe = gameId to false; meOpen = true }
            is SoloTargets.Need.Ask -> targetAsk = gameId to need
            is SoloTargets.Need.Go -> openSolo(gameId, need.t)
        }
    }

    private fun openSolo(gameId: String, t: SoloTarget) {
        SoloTargets.choose(this, today(), gameId, t)
        Flight.log("solo: a game played alone${if (t.home) "" else ", for a friend's couch"}")
        startActivity(PlayerActivity.gameIntent(this, gameId, t.who, solo = true))
    }

    /** "להצטרף לתחרות ... בספה של דני": the game opens for that couch's contest; which couch is already said. */
    private fun joinContest(gameId: String) {
        val t = SoloTargets.visit(this) ?: return
        RelayStore(this).setMode(today(), gameId, SOLO)
        openSolo(gameId, t)
    }

    /** The visit ends now: the friend's TV is let go of at once, and the phone is at home again. */
    private fun endVisit() {
        CouchSetup.endVisit(this)
        Links.refresh(this)
        Links.release(this)
        couchTick++
        android.widget.Toast.makeText(this, R.string.visit_ended, android.widget.Toast.LENGTH_SHORT).show()
        phoneSync.start()
    }

    /** On a phone (1.11): what the others played alone comes in, and the home redraws with it. In the background. */
    private fun soloLook() {
        val app = application as CouchApp
        SoloJob.schedule(this)
        Thread({
            val before = app.repo.revision.value
            runCatching { SoloRelay(applicationContext, app.repo).sync(heldBy()) }
            if (app.repo.revision.value != before) runOnUiThread { hubReloads++ }
        }, "solo-look").start()
    }

    /**
     * While the app is in front, the relay is read every couple of minutes (1.11, 1.12): on a TV for the reveals and what the phones
     * changed; on a phone for the challenges and what the TV and the other phone changed (the home's points with them).
     */
    private val tvLook = object : Runnable {
        override fun run() {
            val app = application as CouchApp
            if (TvMode.isTelevision(this@MainActivity)) Thread({ runCatching { SoloRelay(applicationContext, app.repo).sync(null) } }, "solo-look").start()
            else {
                soloLook()
                // A visit ends with its evening, also while the app is open: the phone is at home again.
                if (CouchStore(this@MainActivity).visit != null && CouchStore(this@MainActivity).visitNow() == null) { Links.refresh(this@MainActivity); Links.release(this@MainActivity); couchTick++ }
            }
            tvLookHandler.postDelayed(this, TV_LOOK_MS)
        }
    }
    private val tvLookHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * What the hub's page can ask of the app (it calls from its own thread). A property, so a test can press what the page presses.
     *
     * 1.9.0 crashed here on every game opened from the hub: the activity's function was also called `play`, and inside this object
     * `play(gameId)` meant the object's own `play`, which called itself until the stack ran out. Hence the different name, and the
     * explicit `this@MainActivity`.
     */
    internal val hubActions = object : HubBridge.Actions {
        override fun play(gameId: String) { runOnUiThread { this@MainActivity.openGame(gameId) } }

        override fun openAccounts() {
            runOnUiThread { startActivity(Intent(this@MainActivity, AccountsActivity::class.java)) }
        }

        override fun openKeyboardMode() {
            runOnUiThread { startActivity(Intent(this@MainActivity, KeyboardModeActivity::class.java)) }
        }

        override fun openMe() { runOnUiThread { meOpen = true } }

        // A note needs a writer: who holds the phone is asked first when it is not known.
        override fun openNote() { runOnUiThread { if (RemoteStore(this@MainActivity).me == null) meOpen = true else noteOpen = true } }

        override fun playSolo(gameId: String) { runOnUiThread { this@MainActivity.openGame(gameId, SOLO) } }

        override fun allowNotifications() {
            runOnUiThread {
                if (android.os.Build.VERSION.SDK_INT >= 33) notifyAsk.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        override fun openCouches() { runOnUiThread { startActivity(Intent(this@MainActivity, CouchesActivity::class.java)) } }

        override fun shareApp() { runOnUiThread { startActivity(CouchesActivity.shareIntent(this@MainActivity)) } }
    }

    override fun onStart() {
        super.onStart()
        if (!TvMode.isTelevision(this)) {
            // During a visit (2.0) the phone holds its connection to the friend's TV while the app is in front, so the visit's line
            // says at once when it is lost; at home it only hands over what waits for the home's TV, and lets go.
            val couches = CouchStore(this)
            if (couches.visitNow() != null) Links.acquire(this, this) else { Links.release(this); phoneSync.start() }
            // The phone became one of a couch's own a moment ago (it joined it, or its home was changed): who plays from it is asked.
            if (couches.askMe) { couches.askMe = false; if (RemoteStore(this).me == null) meOpen = true }
            couchTick++
        }
        tvLookHandler.post(tvLook)
    }

    override fun onStop() {
        super.onStop()
        phoneSync.stop()
        if (!TvMode.isTelevision(this)) Links.release(this)
        tvLookHandler.removeCallbacks(tvLook)
    }

    /**
     * Who holds this phone, and a note for one of us (1.10), over the phone's home: the same dialogs keyboard mode has. A change is kept
     * here at once, and handed to the TV now if it can be reached, or the next time the app comes to the front (PhoneSync).
     */
    @androidx.compose.runtime.Composable
    private fun PhoneHomeDialogs(couch: CouchApp, pieces: PiecesConfig, @Suppress("UNUSED_PARAMETER") stateRev: Long = 0L) {
        val store = RemoteStore(this)
        val day = Couple.evening()
        if (meOpen) {
            val people = Couple.everyone(couch.repo.snapshot(), day)
            IdentityDialog(
                people, store.me, pieces, today = day,
                onSave = { p ->
                    store.me = p.who
                    couch.repo.update { Couple.put(it, p) }
                    meOpen = false; hubReloads++
                    Flight.log("me: this phone is held by ${if (p.who in Couple.WHO) "one of the two of us" else if (p.guest) "a guest" else "someone else"}")
                    phoneSync.start()
                    // A game was waiting to be played alone until it was known who plays it.
                    soloAfterMe?.let { (g, e) -> soloAfterMe = null; playSolo(g, e) }
                },
                onRemove = { w ->
                    couch.repo.update { Couple.remove(it, w, System.currentTimeMillis()) }
                    if (store.me == w) store.me = null
                    meOpen = false; hubReloads++
                    phoneSync.start()
                },
                onDismiss = { meOpen = false; soloAfterMe = null },
            )
        }
        playModeFor?.let { id -> couch.catalog.game(id)?.let { game ->
            val state = couch.repo.snapshot()
            val me = heldBy()
            val challengers = Solo.players(state, today(), id).filter { it.who != me }
                .map { s -> Challenger(Couple.name(state, s.who), pieces.styleOf(Couple.person(state, s.who)), s.who in Couple.WHO) }
            PlayModeDialog(
                gameName = game.n, challengers = challengers,
                onTogether = {
                    playModeFor = null
                    RelayStore(this).setMode(today(), id, TOGETHER)
                    startActivity(PlayerActivity.gameIntent(this, id, null))
                },
                onSolo = { playModeFor = null; playSolo(id, elsewhere = false) },
                onElsewhere = { playModeFor = null; playSolo(id, elsewhere = true) },
                onDismiss = { playModeFor = null },
            )
        } }
        targetAsk?.let { (id, need) -> couch.catalog.game(id)?.let { game ->
            CouchTargetDialog(
                game.n, need.home, need.visit,
                onPick = { t -> targetAsk = null; openSolo(id, t) },
                onDismiss = { targetAsk = null },
            )
        } }
        if (noteOpen) {
            val me = store.me
            val state = couch.repo.snapshot()
            val people = Couple.everyone(state, day)
            val mine = people.firstOrNull { it.who == me }
            if (mine == null) { noteOpen = false; meOpen = true; return }
            val games = couch.catalog.games.filter { couch.catalog.inLib(it, state) }.map { NoteGame(it.id, it.n) }
            NoteDialog(
                recipients = people.filter { it.who != me }, initialTo = noteFor(me), pieces = pieces,
                color = pieces.styleOf(mine).accent, games = games, initialGame = null,
                onSend = { to, game, text, doodle, ink ->
                    store.keepNote(Note(RemoteStore.randomHex(8), mine.who, game, text, doodle, System.currentTimeMillis(), to = to, ink = ink))
                    noteOpen = false; hubReloads++
                    android.widget.Toast.makeText(this, R.string.note_kept, android.widget.Toast.LENGTH_LONG).show()
                    Flight.log("notes: one written on the home${if (game != null) " for a game" else ""}; it goes to the TV when it is reached")
                    phoneSync.start()
                },
                onDismiss = { noteOpen = false },
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must come before super.onCreate. The system splash is the first instants of a cold start;
        // the animated in-app splash continues from it.
        Startup.mark("activity created")
        installSplashScreen()
        super.onCreate(savedInstanceState)
        tv = TvHost(this)
        tv.title = getString(R.string.remote_screen_home)
        // The home's logo and picture are read while the rest starts, so they are there with its first frame (see HomeArt).
        if (tv.enabled) Flight.background { warmHomeArt(applicationContext) }
        Diag.log("activity: onCreate, build ${BuildConfig.VERSION_NAME}, android ${Build.VERSION.RELEASE} (api ${Build.VERSION.SDK_INT})")
        Diag.log("webview package: " + (WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "NONE"))
        val dm = resources.displayMetrics
        Diag.log("screen ${dm.widthPixels}x${dm.heightPixels} density ${dm.density}, animator scale ${Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)}")
        lifecycle.addObserver(LifecycleEventObserver { _, event -> Diag.log("lifecycle: $event") })
        // Light UI on a cream background: dark system-bar icons, transparent bars.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // Lets `chrome://inspect` attach to the hub on a debug build. Never in release.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val app = application as CouchApp
        val pieces = PiecesConfig.load(this)
        val bridge = HubBridge(this, app.repo, { app.catalog }, hubActions)
        val reduceMotion = animationsRemoved(contentResolver)
        // Only on a real cold start (not after rotation or coming back), and never with "remove animations" on.
        // On a TV the splash always plays: some boxes ship with the animation scale at zero, and the splash does not depend on it.
        val playSplash = savedInstanceState == null && (!reduceMotion || tv.enabled)
        // If the last run crashed, or stopped while in use without a crash of its own (frozen, out of memory, a crash inside the
        // WebView), say so first, with what happened in text that can be photographed.
        var crash by mutableStateOf(CrashReport.pending(this))
        var stopped by mutableStateOf(if (crash == null) Diagnostics.unexpectedEnd(this) else null)
        if (crash != null || stopped != null) Flight.log("showing the report of the previous run (${if (crash != null) "crash" else "stopped in use"})")
        setContent {
            AlHasapaTheme {
              // What the TV or the other phone changed arrived (1.12): the hub's page reads the state again (the points with it).
              val brought by app.repo.merged.collectAsState()
              LaunchedEffect(brought) { if (brought > 0) hubReloads++ }
              tv.Layer {
                val report = crash ?: stopped
                if (report != null) {
                    CrashScreen(report, stopped = crash == null) {
                        CrashReport.clear(this@MainActivity); Flight.acknowledgePrevious(); crash = null; stopped = null
                    }
                    return@Layer
                }
                // A new device (2.0): a couch of one's own, or the one that already is in this home. Nothing of anyone's is shown before.
                val stateRev by app.repo.revision.collectAsState()
                val needsSetup = remember(stateRev) { app.repo.peek { Couches.needsSetup(it) } }
                if (needsSetup) {
                    WelcomeScreen(
                        tv = tv.enabled, tvName = Build.MODEL.orEmpty().ifBlank { "TV" },
                        onCreate = { c, a, b ->
                            CouchSetup.create(this@MainActivity, app.repo, c, a, b)
                            if (!tv.enabled) meOpen = true
                        },
                        onJoin = { joinCouch.launch(CouchesActivity.joinIntent(this@MainActivity)) },
                    )
                    if (!tv.enabled) PhoneHomeDialogs(app, pieces, stateRev)
                    return@Layer
                }
                if (tv.enabled && !webHub) {
                    // The TV opens on its own native home: there at once, and the remote moves between real buttons.
                    val revision by app.repo.revision.collectAsState()
                    val model = remember(revision) { HomeModel.from(app.repo.snapshot(), app.catalog, pieces, LocalDate.now()) }
                    val remote by RemoteKeys.ui.collectAsState()
                    LaunchedEffect(Unit) {
                        Startup.mark("home ready")
                        Flight.background { Startup.save(this@MainActivity) }
                        Flight.log("start: " + Startup.summary().replace("\n", "; "))
                    }
                    TvHome(
                        model = model,
                        phoneConnected = remote.phones.isNotEmpty(),
                        nav = homeNav,
                        phoneOlder = remote.older, phoneNewer = remote.newer,
                        onPlay = { id -> openGame(id) },
                        onAccounts = { startActivity(Intent(this@MainActivity, AccountsActivity::class.java)) },
                        // The hub is a page: the arrows begin inside it, as they did when the TV opened on it.
                        onMore = { Flight.log("home: the full hub"); tv.controller.pageFirst(); webHub = true },
                        onInfo = { tv.controller.openPanel(TvPanel.INFO) },
                        onSettings = { tv.controller.openPanel(TvPanel.SETTINGS) },
                    )
                    // A note one of us left the other (1.9.9): one for no game, or for a game played since it was written.
                    val homeNotes = remember(revision) { Notes.forHome(app.repo.snapshot(), CouchState.dayKey(LocalDate.now())) }
                    homeNotes.firstOrNull()?.let { n ->
                        val view = remember(n.id, revision) { NoteView.of(n, app.repo.snapshot(), pieces, app.catalog) }
                        NoteCard(view) {
                            app.repo.update { s -> Notes.markSeen(s, n.id, System.currentTimeMillis()) }
                            Flight.log("notes: one was read on the home")
                        }
                    }
                    // A game both of us played alone today (1.11): its reveal, once, when no note is up.
                    if (homeNotes.isEmpty()) {
                        val relay = remember { RelayStore(this@MainActivity) }
                        var revealsSeen by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                        val day = CouchState.dayKey(LocalDate.now())
                        val reveal = remember(revision, revealsSeen) {
                            SoloPlay.tvReveal(app.repo.snapshot(), app.catalog.games, day, pieces) { relay.told("$day|$it|tv|reveal") }
                        }
                        reveal?.let { (gameId, o) ->
                            SoloTvCard(o, reduceMotion) {
                                relay.tell("$day|$gameId|tv|reveal", CouchState.dayKey(LocalDate.now().minusDays(1)))
                                revealsSeen++
                                Flight.log("solo: a reveal on the home")
                            }
                        }
                    }
                } else {
                    // On a TV the web hub is a step away from the home (the cats cover it until it is ready), and Back from it goes
                    // back to the home.
                    val guestChanges by GuestSolo.changes.collectAsState()
                    val visit = remember(couchTick, guestChanges, hubReloads) { if (tv.enabled) null else CouchStore(this@MainActivity).visitNow() }
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
                        // A visit (2.0): its line stays at the top for as long as it lasts, with the way out of it.
                        if (visit != null) {
                            val link by remember { Links.client(this@MainActivity) }.state.collectAsState()
                            val linkTv by remember { Links.client(this@MainActivity) }.tv.collectAsState()
                            val guestStore = remember { GuestSolo(this@MainActivity) }
                            val contests = remember(guestChanges, visit) {
                                guestStore.contests(visit.couch, today()).mapNotNull { id ->
                                    val game = app.catalog.game(id) ?: return@mapNotNull null
                                    if (guestStore.entry(visit.couch, today(), id) != null) return@mapNotNull null
                                    val people = CouchStore(this@MainActivity).people(visit.couch).associateBy { it.who }
                                    val players = guestStore.contest(visit.couch, today(), id).filter { !it.out && it.who != visit.me }.map { people[it.who]?.name ?: "אורח" }
                                    if (players.isEmpty()) null else OpenContest(id, game.n, players.joinToString(", "))
                                }
                            }
                            VisitBar(
                                visit, connected = link is KeyClient.State.Connected && linkTv?.couch?.id == visit.couch,
                                pending = guestStore.pending(visit.couch).map { app.catalog.game(it.game)?.n ?: it.game },
                                contests = contests,
                                onEnd = { endVisit() },
                                onOpen = { startActivity(Intent(this@MainActivity, CouchesActivity::class.java)) },
                                onJoin = { joinContest(it) },
                            )
                        }
                        androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier.weight(1f).then(
                                if (visit != null) androidx.compose.ui.Modifier.consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.statusBars) else androidx.compose.ui.Modifier,
                            ),
                        ) {
                            AppRoot(
                                playSplash = if (tv.enabled) true else playSplash, reduceMotion = reduceMotion,
                                onExit = { if (tv.enabled) webHub = false else finish() }, bridge = bridge, reloads = hubReloads,
                            )
                        }
                    }
                }
                // (Drawn again with the state: the players a couch just handed over are the ones to choose from.)
                if (!tv.enabled) PhoneHomeDialogs(app, pieces, stateRev)
                whoPlaysFor?.let { id ->
                    val game = app.catalog.game(id)
                    if (game != null) {
                        val state = app.repo.snapshot()
                        val (nameA, nameB) = CouchState.names(state)
                        WhoPlaysDialog(
                            gameName = game.n, nameA = nameA, nameB = nameB,
                            pieceA = pieces.styleFor(state, "a"), pieceB = pieces.styleFor(state, "b"),
                            onPick = { who -> whoPlaysFor = null; startActivity(PlayerActivity.gameIntent(this, id, who)) },
                            onDismiss = { whoPlaysFor = null },
                        )
                    }
                }
              }
            }
        }
        // Plain views, no Compose and no WebView: readable even if either of those fails. Debug builds only.
        if (BuildConfig.DEBUG) DiagOverlay.install(this)
    }

    companion object {
        /** Together or alone, as kept for a game and a day (see [RelayStore.mode]). */
        const val SOLO = "solo"
        const val TOGETHER = "together"
        /** How often the app in front reads the relay (the TV for its reveals, both for what the others changed). */
        const val TV_LOOK_MS = 2 * 60 * 1000L
    }
}
