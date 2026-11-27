package app.couch.alhasapa.couches

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.R
import app.couch.alhasapa.data.CouchRef
import app.couch.alhasapa.data.Couches
import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.data.Person
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.diag.Flight
import app.couch.alhasapa.keyboard.KeyboardModeActivity
import app.couch.alhasapa.remote.CouchSetup
import app.couch.alhasapa.remote.CouchStore
import app.couch.alhasapa.remote.GuestSolo
import app.couch.alhasapa.remote.KeyClient
import app.couch.alhasapa.remote.KeyLink
import app.couch.alhasapa.remote.Links
import app.couch.alhasapa.remote.NsdFinder
import app.couch.alhasapa.remote.RemoteStore
import app.couch.alhasapa.remote.SavedCouch
import app.couch.alhasapa.remote.TvPeek
import app.couch.alhasapa.ui.theme.AlHasapaTheme

/**
 * "הספות שלי" on the phone (2.0): the home couch, the friends' couches kept for the next visits, the visit under way, and the TVs on
 * this network to choose from. Everything that connects the phone to a TV begins here with a press: a TV that is merely found is
 * asked who it is (its couch's name), and nothing more, until the person chooses what to do with it.
 */
class CouchesActivity : ComponentActivity() {
    /** What the person is in the middle of. */
    private sealed interface Step {
        data object None : Step
        /** A friend's TV was chosen: guest of the evening, kept for the next visits, or the home. */
        data class Options(val n: Nearby) : Step
        /** A TV with no couch yet, or the home's own: it joins the home couch. */
        data class ConnectHome(val n: Nearby) : Step
        /** A new phone joining a TV's couch. */
        data class Join(val n: Nearby) : Step
        data class SetHome(val to: CouchRef, val tv: String, val address: String?) : Step
        data class Remove(val c: SavedCouch) : Step
        /** A phone from before 2.0 that knew several TVs chose one of them: is it its home's? */
        data class AskHome(val n: Nearby) : Step
        data object Rename : Step
        data object Address : Step
    }

    /** What the connection being made is for, once the TV has let the phone in. */
    private enum class Purpose { VISIT_EVENING, VISIT_KEEP, HOME_TV, JOIN, NEW_HOME }
    /** [resume]: who the phone's holder was at that couch the last time (a saved couch): they are that player again, with no question. */
    private data class Pending(val tv: String, val couch: CouchRef?, val purpose: Purpose, val resume: String? = null)

    private var step by mutableStateOf<Step>(Step.None)
    private var pending by mutableStateOf<Pending?>(null)
    private var nearby by mutableStateOf<List<Nearby>>(emptyList())
    private var scanning by mutableStateOf(false)
    private var link by mutableStateOf<AppLink.Ready?>(null)
    private var tick by mutableIntStateOf(0)
    private val joining by lazy { intent.getBooleanExtra(EXTRA_JOIN, false) }
    /** Opened by "שליחת האפליקציה לחבר": the link is handed to the share sheet as soon as it is known to be ready. */
    private var shareWhenReady = false

    private val couches by lazy { CouchStore(this) }
    private val remote by lazy { RemoteStore(this) }
    private val guest by lazy { GuestSolo(this) }
    private val couch get() = application as CouchApp
    private lateinit var client: KeyClient

    private val askLocalNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { Links.acquire(this, this); scan() } else client.noPermission()
    }

    private fun home(): CouchRef? = couch.repo.peek { Couches.of(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        client = Links.client(this)
        shareWhenReady = savedInstanceState == null && intent.getBooleanExtra(EXTRA_SHARE, false)
        val pieces = PiecesConfig.load(this)
        val actions = object : CouchesActions {
            override fun back() = finish()
            override fun rename() { step = Step.Rename }
            override fun endVisit() = this@CouchesActivity.endVisit()
            override fun keyboard() { startActivity(Intent(this@CouchesActivity, KeyboardModeActivity::class.java)) }
            override fun sendPending() { Links.refresh(this@CouchesActivity); client.retry(); client.flushGuest() }
            override fun startVisit(c: SavedCouch) = visitSaved(c)
            override fun setHome(c: SavedCouch) { step = Step.SetHome(CouchRef(c.id, c.name), c.tvs.firstOrNull().orEmpty(), null) }
            override fun remove(c: SavedCouch) { step = Step.Remove(c) }
            override fun pick(n: Nearby) = this@CouchesActivity.pick(n)
            override fun scan() = this@CouchesActivity.scan()
            override fun typeAddress() { step = Step.Address }
            override fun shareApp() = this@CouchesActivity.shareApp()
            override fun checkLink() = this@CouchesActivity.checkLink()
        }
        setContent {
            AlHasapaTheme {
                val state by client.state.collectAsState()
                val tv by client.tv.collectAsState()
                val revision by couch.repo.revision.collectAsState()
                val shared by client.shared.collectAsState()
                val guestChanges by GuestSolo.changes.collectAsState()
                val connected = (state as? KeyClient.State.Connected)?.let { tv }
                val view = remember(state, tv, revision, shared, guestChanges, tick, nearby, scanning, link) {
                    CouchesView(
                        home = home() ?: couch.repo.peek { CouchRef("", Couches.name(it)) }.takeIf { !joining },
                        homeTvs = couches.homeTvs.size, homeTvIds = couches.homeTvs,
                        saved = couches.saved().filter { it.kept },
                        visit = couches.visitNow(),
                        connected = connected,
                        looking = state is KeyClient.State.Looking || state is KeyClient.State.Connecting,
                        nearby = nearby, scanning = scanning,
                        pending = guest.pending().map { it to (couch.catalog.game(it.game)?.n ?: it.game) },
                        link = link, joining = joining,
                    )
                }
                CouchesScreen(view, actions)
                // (Drawn again when the TV names its players, so the choice of who the holder is there shows them.)
                Dialogs(state, tv, pieces, shared + tick)
                // The TV let the phone in: what the connection was made for goes on.
                LaunchedEffect(state, shared, revision) { onConnection(state) }
            }
        }
        Flight.log("couches: the list was opened${if (joining) " (to join a couch)" else ""}")
    }

    @androidx.compose.runtime.Composable
    private fun Dialogs(state: KeyClient.State, tv: KeyClient.Tv?, pieces: PiecesConfig, @Suppress("UNUSED_PARAMETER") round: Int) {
        val p = pending
        when (val s = step) {
            Step.None -> {}
            is Step.Options -> s.n.tv.couch?.let { c ->
                VisitOptionsDialog(
                    c, s.n.tv.name,
                    onEvening = { step = Step.None; connect(s.n, KeyLink.AS_EVENING, Purpose.VISIT_EVENING) },
                    onKeep = { step = Step.None; connect(s.n, KeyLink.AS_GUEST, Purpose.VISIT_KEEP) },
                    onHome = { step = Step.SetHome(c, s.n.tv.id, s.n.address) },
                    onDismiss = { step = Step.None },
                )
            }
            is Step.ConnectHome -> ConnectHomeDialog(
                home() ?: CouchRef("", couch.repo.peek { Couches.name(it) }), s.n.tv.name,
                onConnect = { step = Step.None; connect(s.n, KeyLink.AS_HOME, Purpose.HOME_TV) }, onDismiss = { step = Step.None },
            )
            is Step.Join -> JoinDialog(s.n.tv.couch, s.n.tv.name, onJoin = { step = Step.None; connect(s.n, KeyLink.AS_HOME, Purpose.JOIN) }, onDismiss = { step = Step.None })
            is Step.SetHome -> SetHomeDialog(s.to, home(), onConfirm = { step = Step.None; setHome(s) }, onDismiss = { step = Step.None })
            is Step.AskHome -> DialogCard({ step = Step.None }) {
                androidx.compose.material3.Text(
                    stringResource(R.string.kb_ask_home, s.n.tv.name, s.n.tv.couch?.name ?: s.n.tv.name), fontSize = 19.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = app.couch.alhasapa.ui.theme.Palette.Ink,
                )
                Choice(stringResource(R.string.kb_ask_home_yes), null, dark = true, tag = "ask-home-yes") {
                    CouchSetup.answerHome(this@CouchesActivity, s.n.tv, home = true)
                    step = Step.None; connect(s.n, KeyLink.AS_HOME, Purpose.HOME_TV)
                }
                Choice(stringResource(R.string.kb_ask_home_no), null, dark = false, tag = "ask-home-no") {
                    CouchSetup.answerHome(this@CouchesActivity, s.n.tv, home = false)
                    Links.refresh(this@CouchesActivity)
                    step = if (s.n.tv.couch != null) Step.Options(s.n) else Step.None
                    tick++
                }
            }
            is Step.Remove -> RemoveCouchDialog(
                s.c, pending = guest.pending(s.c.id).isNotEmpty(),
                onConfirm = {
                    if (couches.visit?.couch == s.c.id) endVisit()
                    CouchSetup.remove(this, s.c.id); step = Step.None; tick++
                },
                onDismiss = { step = Step.None },
            )
            Step.Rename -> TextDialog(
                stringResource(R.string.couch_rename_title), home()?.name.orEmpty(), null, stringResource(R.string.couch_save),
                onOk = { name ->
                    if (name.isNotEmpty()) couch.repo.update { st -> Couches.rename(st, name, System.currentTimeMillis()) }
                    step = Step.None
                },
                onDismiss = { step = Step.None },
            )
            Step.Address -> TextDialog(
                stringResource(R.string.couch_typed_address), "", "192.168.1.20", stringResource(R.string.couch_address_find), max = 60,
                note = stringResource(R.string.couch_address_hint), tag = "couch-address",
                onOk = { a -> step = Step.None; askAddress(a) }, onDismiss = { step = Step.None },
            )
        }
        if (p == null) return
        when (state) {
            // The four digits the chosen TV shows.
            is KeyClient.State.NeedCode -> TextDialog(
                stringResource(R.string.couch_code_title), "", "0000", stringResource(R.string.couch_code_ok), digits = true, max = 4,
                note = state.left?.let { stringResource(R.string.couch_code_wrong, it) } ?: p.couch?.name, tag = COUCH_CODE_TAG,
                onOk = { code -> if (code.length == 4) client.sendCode(code) }, onDismiss = { giveUp() },
            )
            // The TV closed pairing for a while (too many wrong codes): said once, and the connection is given up on.
            KeyClient.State.Wait -> LaunchedEffect(p) { Toast.makeText(this@CouchesActivity, R.string.couch_wait, Toast.LENGTH_LONG).show(); giveUp() }
            // The chosen TV is not on this network: said, with another try or a way out. Nothing was changed.
            is KeyClient.State.NotFound -> DialogCard({ giveUp() }) {
                androidx.compose.material3.Text(
                    stringResource(R.string.couch_tv_not_found, p.couch?.name ?: ""), fontSize = 18.sp, color = app.couch.alhasapa.ui.theme.Palette.Ink,
                )
                SmallButton(stringResource(R.string.couch_tv_retry), dark = true) { client.retry() }
                SmallButton(stringResource(R.string.couch_cancel), dark = false) { giveUp() }
            }
            is KeyClient.State.Connected -> if (p.purpose == Purpose.VISIT_EVENING || p.purpose == Purpose.VISIT_KEEP) {
                // The guest says who they are there: one of that couch's players, or someone new.
                val c = p.couch ?: tv?.couch
                if (c != null && tv?.id == p.tv) {
                    val evening = Couple.evening()
                    val people = couches.people(c.id).filter { !it.removed && (!it.guest || it.day == evening) }
                    val kept = p.purpose == Purpose.VISIT_KEEP
                    // A saved couch: who they were there, if that couch still has them, and no question again.
                    val again = p.resume?.let { w -> people.firstOrNull { it.who == w } }
                    if (again != null) LaunchedEffect(p, again.who) { beginVisit(c, p.tv, again, kept = true, added = false) }
                    else if (p.resume == null || people.isNotEmpty()) GuestWhoDialog(
                        c, people, couches.saved(c.id)?.me, pieces,
                        onPick = { person -> beginVisit(c, p.tv, person, kept, added = false) },
                        onNew = { name ->
                            // New there: a guest of this evening, or, when the couch is kept, someone who comes often.
                            val me = Person(Couple.newId(), name, "", "", System.currentTimeMillis(), guest = !kept, day = if (kept) null else evening)
                            beginVisit(c, p.tv, me, kept, added = true)
                        },
                        onDismiss = { giveUp() },
                    )
                }
            }
            else -> {}
        }
    }

    /** The TV let the phone in (main thread): a TV of the home is written down as one; a new phone that joined goes back to its home. */
    private fun onConnection(state: KeyClient.State) {
        val p = pending ?: return
        val st = state as? KeyClient.State.Connected ?: return
        val tv = client.tv.value ?: return
        if (tv.id != p.tv) return
        when (p.purpose) {
            Purpose.HOME_TV, Purpose.NEW_HOME, Purpose.JOIN -> {
                if (st.role != KeyLink.AS_HOME) return
                // (A phone that joins has the couch only once the TV handed it over, players and all.)
                val home = home() ?: return
                if (tv.couch != null && tv.couch.id != home.id) return
                if (p.purpose != Purpose.HOME_TV && client.awaitingAll) return
                couches.addHomeTv(tv.id)
                pending = null
                Links.unchoose(this)
                Flight.log("couches: a TV of the home was connected")
                // One of this couch's own from now on: who plays from this phone is asked at its home.
                if (p.purpose != Purpose.HOME_TV && remote.me == null) couches.askMe = true
                if (p.purpose == Purpose.JOIN) { setResult(RESULT_OK); finish() }
                tick++
            }
            else -> {}
        }
    }

    private fun pick(n: Nearby) {
        val home = home()
        val c = n.tv.couch
        step = when {
            joining -> Step.Join(n)
            // A phone from before 2.0 that knew several TVs, and this is one of them: asked once whether it is its home's.
            n.tv.id in couches.askHome -> Step.AskHome(n)
            // One of the home's own TVs already: connected to, with nothing to ask.
            n.tv.id in couches.homeTvs && (c == null || home == null || c.id == home.id) -> { connect(n, KeyLink.AS_HOME, Purpose.HOME_TV); Step.None }
            // A TV with no couch yet, or one of the home's couch that this phone has not met: it joins the home.
            c == null || c.id == home?.id -> Step.ConnectHome(n)
            // A couch saved before: the visit begins as it was set up, without the choices again.
            couches.saved(c.id)?.kept == true -> { visitSaved(couches.saved(c.id)!!, n); Step.None }
            else -> Step.Options(n)
        }
    }

    /** The chosen TV is connected to, as [role]; the code is asked for if that TV wants one. */
    private fun connect(n: Nearby, role: String, purpose: Purpose) {
        pending = Pending(n.tv.id, n.tv.couch, purpose)
        Links.choose(this, n.tv.id, n.address, role)
        client.retry()
    }

    /** A visit to a couch saved before: its TV is looked for, and the phone's holder is who they were there. */
    private fun visitSaved(c: SavedCouch, n: Nearby? = null) {
        val tv = n?.tv?.id ?: nearby.firstOrNull { it.tv.couch?.id == c.id }?.tv?.id ?: c.tvs.firstOrNull() ?: return
        val address = n?.address ?: nearby.firstOrNull { it.tv.id == tv }?.address
        // (The visit begins once that TV has let the phone in: who they were there is taken up again then.)
        pending = Pending(tv, CouchRef(c.id, c.name), Purpose.VISIT_KEEP, resume = c.me)
        Links.choose(this, tv, address, KeyLink.AS_GUEST)
        client.retry()
    }

    private fun beginVisit(c: CouchRef, tv: String, me: Person, kept: Boolean, added: Boolean) {
        // (A visit to another couch that was still on ends first: one visit at a time.)
        if (couches.visit?.let { it.couch != c.id } == true) CouchSetup.endVisit(this)
        CouchSetup.startVisit(this, c, tv, me, kept, added = added)
        client.sendGuest(if (added) me else null)
        pending = null
        Links.unchoose(this)
        tick++
        Toast.makeText(this, getString(R.string.visit_bar, Couches.inCouch(c.name)), Toast.LENGTH_SHORT).show()
    }

    /** The visit ends now: the control of the friend's TV is let go of at once, and the phone is at home again. */
    private fun endVisit() {
        CouchSetup.endVisit(this)
        pending = null
        Links.unchoose(this)
        Links.refresh(this)
        tick++
        Toast.makeText(this, R.string.visit_ended, Toast.LENGTH_SHORT).show()
    }

    private fun setHome(s: Step.SetHome) {
        CouchSetup.setHome(this, couch.repo, s.to, listOfNotNull(s.tv.takeIf { it.isNotEmpty() }))
        Toast.makeText(this, getString(R.string.couch_home_changed, s.to.name), Toast.LENGTH_LONG).show()
        if (s.tv.isNotEmpty()) {
            // Let in as one of the new home's own (its TV asks for the code): its players and history come from it.
            pending = Pending(s.tv, s.to, Purpose.NEW_HOME)
            Links.choose(this, s.tv, s.address, KeyLink.AS_HOME)
            client.retry()
        } else {
            // A couch that was the home before and has no TV: what was kept of it here is the home again.
            pending = null
            Links.unchoose(this); Links.refresh(this)
        }
        tick++
    }

    /** The connection being made is given up on: nothing was changed. */
    private fun giveUp() {
        pending = null
        Links.unchoose(this)
        tick++
    }

    /** The TVs on this network are asked who they are (their couch's name), and nothing more. In the background. */
    private fun scan() {
        if (scanning) return
        scanning = true
        val ctx = applicationContext
        Thread({
            val found = runCatching { scanner?.invoke(ctx) ?: NsdFinder(ctx).scan(4_000).mapNotNull { f -> TvPeek.ask(f.address)?.let { Nearby(f.address, it) } } }.getOrDefault(emptyList())
            runOnUiThread { nearby = (found + nearby.filter { old -> found.none { it.tv.id == old.tv.id } && old.address in typed }).distinctBy { it.tv.id }; scanning = false }
        }, "couch-scan").start()
    }

    private val typed = HashSet<String>()

    private fun askAddress(a: String) {
        val address = a.trim().ifEmpty { return }.let { if (':' in it) it else "$it:${KeyLink.PORT}" }
        Thread({
            val tv = runCatching { peek?.invoke(address) ?: TvPeek.ask(address) }.getOrNull()
            runOnUiThread {
                if (tv == null) Toast.makeText(this, R.string.couch_address_none, Toast.LENGTH_LONG).show()
                else { typed += address; nearby = (nearby.filter { it.tv.id != tv.id } + Nearby(address, tv)) }
            }
        }, "couch-peek").start()
    }

    private fun checkLink() {
        link = null
        Thread({
            val r = runCatching { AppLink.check(probe ?: AppLink.UrlProbe) }.getOrDefault(AppLink.Ready.UNKNOWN)
            runOnUiThread {
                link = r
                if (shareWhenReady) {
                    shareWhenReady = false
                    if (r == AppLink.Ready.YES) shareApp()
                    else Toast.makeText(this, if (r == AppLink.Ready.NO) R.string.share_app_not_ready else R.string.share_app_offline, Toast.LENGTH_LONG).show()
                }
            }
        }, "app-link").start()
    }

    /** The install link, by whatever the person chooses to send it with. Offered only once both downloads are there. */
    private fun shareApp() {
        if (link != AppLink.Ready.YES) return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, getString(R.string.share_app_text, AppLink.PAGE))
        startActivity(Intent.createChooser(send, getString(R.string.share_app)))
        Flight.log("couches: the install link was handed to the share sheet")
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(KeyboardModeActivity.LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            askLocalNetwork.launch(KeyboardModeActivity.LOCAL_NETWORK)
        } else { Links.acquire(this, this); scan() }
        if (!joining && link == null) checkLink()
        tick++
    }

    override fun onStop() {
        super.onStop()
        // A pairing left in the middle is given up on: the phone goes back to where it belongs.
        if (isFinishing && pending != null) { pending = null; Links.unchoose(this) }
        Links.release(this)
    }

    companion object {
        /** Opened from a new phone's first screen: only to join the couch of a TV on this network. */
        const val EXTRA_JOIN = "join"

        fun joinIntent(context: Context): Intent = Intent(context, CouchesActivity::class.java).putExtra(EXTRA_JOIN, true)

        /** Opened by "שליחת האפליקציה לחבר": the install link goes to the share sheet as soon as both downloads are known to be there. */
        const val EXTRA_SHARE = "share"

        fun shareIntent(context: Context): Intent = Intent(context, CouchesActivity::class.java).putExtra(EXTRA_SHARE, true)

        /** In the tests: the TVs "on this network", a TV "at an address", and whether the downloads are there. */
        @Volatile var scanner: ((Context) -> List<Nearby>)? = null
        @Volatile var peek: ((String) -> KeyClient.Tv?)? = null
        @Volatile var probe: AppLink.Probe? = null
    }
}
