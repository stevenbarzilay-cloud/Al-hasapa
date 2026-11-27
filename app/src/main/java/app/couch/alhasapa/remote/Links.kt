package app.couch.alhasapa.remote

import android.annotation.SuppressLint
import android.content.Context
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.data.Couches
import app.couch.alhasapa.diag.Flight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The phone's one connection to a TV (2.0), shared by the screens that need it: keyboard mode, "my couches", and, during a visit, the
 * home and the games (so the visit's line can say at once that the TV was lost, and a game played alone for the friend's couch
 * reaches it). It is open while any of them is in front, and goes to exactly one place: the TV of the friend's couch being visited,
 * else a TV of the home couch, else, while the person is pairing one they chose by hand, that one. Never to a TV that merely
 * happens to be on the same network.
 */
object Links {
    // The application's context only, which lives as long as the process.
    @SuppressLint("StaticFieldLeak")
    @Volatile private var client: KeyClient? = null
    private val owners = HashSet<Any>()
    private var watching: Job? = null

    /** A TV the person chose by hand to be paired with (its id, where it is, and as what), until that is done or let go of. */
    @Volatile var chosen: KeyClient.Target? = null
        private set

    private val _open = MutableStateFlow(false)
    /** Whether any screen holds the connection open now. */
    val open: StateFlow<Boolean> get() = _open

    /** In the tests: another client in place of the app's own. */
    @Volatile var factory: ((Context, KeyClient.Target?) -> KeyClient)? = null

    fun client(context: Context): KeyClient = client ?: synchronized(this) {
        client ?: (factory?.invoke(context.applicationContext, targetFor(context)) ?: KeyClient(context.applicationContext, target = targetFor(context))).also { client = it }
    }

    /**
     * Where the phone's connection goes now: the TV chosen by hand, else the visit's, else (for a phone from before 2.0 that must
     * ask which TV is its home's) the TVs to ask about, else the home's own TVs; null when there is none of these.
     */
    fun targetFor(context: Context): KeyClient.Target? {
        chosen?.let { return it }
        val couches = CouchStore(context)
        val remote = RemoteStore(context)
        couches.visitNow()?.let { v ->
            val role = if (couches.saved(v.couch)?.kept == true) KeyLink.AS_GUEST else KeyLink.AS_EVENING
            return KeyClient.Target(setOf(v.tv), role, remote.addressOf(v.tv))
        }
        if (couches.askHome.isNotEmpty()) return KeyClient.Target(couches.askHome, KeyClient.Target.ASK)
        val home = couches.homeTvs
        return if (home.isEmpty()) null else KeyClient.Target(home, KeyLink.AS_HOME)
    }

    /** The target is worked out again (a visit began or ended, the home's TV was chosen, a question was answered). */
    fun refresh(context: Context) { client?.retarget(targetFor(context)) }

    /** The person chose the TV [tv] (at [address], when it was just found) to be connected to, as [role]. */
    fun choose(context: Context, tv: String, address: String?, role: String) {
        val remote = RemoteStore(context)
        if (address != null) remote.setAddress(tv, address)
        chosen = KeyClient.Target(setOf(tv), role, address ?: remote.addressOf(tv))
        refresh(context)
    }

    /** The TV chosen by hand is let go of (paired and recorded, or given up on). */
    fun unchoose(context: Context) {
        if (chosen == null) return
        chosen = null
        refresh(context)
    }

    /** [owner] (a screen) needs the connection while it is in front. Main thread. */
    fun acquire(context: Context, owner: Any) {
        val c = client(context)
        owners += owner
        _open.value = true
        c.retarget(targetFor(context))
        c.start()
        watch(context.applicationContext, c)
    }

    /** [owner] left the front: the connection closes when no screen needs it any more. Main thread. */
    fun release(owner: Any) {
        owners -= owner
        if (owners.isEmpty()) { client?.stop(); _open.value = false; watching?.cancel(); watching = null }
    }

    /**
     * What the connection finds out is written down where it belongs: a TV of the home that answered as one of the home's is the
     * home's (the phone remembers it); a TV taken for the home's that is of another couch now is the home's no longer; a friend's
     * couch that was renamed is listed under its new name.
     */
    private fun watch(context: Context, c: KeyClient) {
        if (watching != null) return
        watching = CoroutineScope(Dispatchers.Main.immediate).launch {
            c.state.collect { st ->
                val couches = CouchStore(context)
                when (st) {
                    is KeyClient.State.Connected -> {
                        val tv = c.tv.value ?: return@collect
                        if (st.role == KeyLink.AS_HOME) {
                            if (tv.id.isNotEmpty() && tv.id !in couches.homeTvs && CouchStore(context).visitNow() == null) {
                                // (Only a TV of the home's own couch, or one with no couch yet, which takes the home's.)
                                val home = (context as? CouchApp)?.repo?.peek { Couches.of(it) }
                                if (tv.couch == null || home == null || tv.couch.id == home.id) couches.addHomeTv(tv.id)
                            }
                        } else tv.couch?.let { couch ->
                            couches.saved(couch.id)?.let { s -> if (s.name != couch.name || tv.id !in s.tvs) couches.save(s.copy(name = couch.name, tvs = (s.tvs + tv.id).distinct())) }
                            couches.visit?.takeIf { it.couch == couch.id && it.name != couch.name }?.let { couches.visit = it.copy(name = couch.name) }
                        }
                    }
                    is KeyClient.State.OtherCouch -> {
                        CouchSetup.otherCouch(context, st.tv)
                        Flight.log("couches: the home's TV list was corrected")
                    }
                    else -> {}
                }
            }
        }
    }

    /** In the tests: as if the app had just started. */
    fun reset() {
        client?.stop(); client = null; owners.clear(); chosen = null; _open.value = false; watching?.cancel(); watching = null; factory = null
    }
}
