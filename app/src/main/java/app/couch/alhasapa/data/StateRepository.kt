package app.couch.alhasapa.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Where the state document lives. A file today; the seam is here so sync could be added later without touching callers. */
interface StateStore {
    fun read(): String?
    fun write(json: String)
}

/** One file, replaced atomically (write a temp file, then rename), so a crash can never leave half a state. */
class FileStateStore(private val file: File) : StateStore {
    override fun read(): String? = if (file.isFile) runCatching { file.readText() }.getOrNull() else null

    override fun write(json: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        runCatching {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/**
 * The single owner of the app state. Thread-safe: the hub (via its bridge, on a background thread), the player, the share
 * target and the settings all go through it. Every change bumps [revision]; the hub saves with the revision it last saw,
 * and a stale save is refused, so a change made natively while the hub was in the background is never overwritten.
 */
class StateRepository(
    private val store: StateStore,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private val lock = Any()
    private var state: JSONObject
    private var rev = 0L

    /** False on the very first run: the hub then migrates anything it kept in its own storage before this layer existed. */
    val hadStoredState: Boolean

    private val _revision = MutableStateFlow(0L)

    /** Emits whenever the state changes, so native screens can recompose. */
    val revision: StateFlow<Long> get() = _revision

    /**
     * Told after every change made on this device (not after what another device's copy brought, see [mergeShared]): the TV and the
     * phones are then told of it (1.12). Called outside the lock, on the thread that made the change.
     */
    @Volatile var onLocalChange: (() -> Unit)? = null

    /** The clock the changes are stamped with (1.12), replaceable in tests. */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    private val _merged = MutableStateFlow(0L)

    /** Counts the times another device's copy changed the state here (1.12): the hub's page then reads the state again. */
    val merged: StateFlow<Long> get() = _merged

    /**
     * The last few states by revision, and which of those revisions were another device's copy merged in: a save from the hub's page
     * that is behind only by such merges is not refused, its own changes are merged in as the newest (1.12).
     */
    private val history = ArrayDeque<Pair<Long, JSONObject>>()
    private val mergeRevs = HashSet<Long>()

    init {
        val stored = CouchState.parse(store.read())
        hadStoredState = stored != null
        state = stored ?: CouchState.empty()
        history.addLast(0L to state)
    }

    /** True until anything has ever been stored: the very first run. */
    fun isFresh(): Boolean = synchronized(lock) { rev == 0L && !hadStoredState }

    fun json(): String = synchronized(lock) { state.toString() }

    fun snapshot(): JSONObject = synchronized(lock) { CouchState.copy(state) }

    fun currentRevision(): Long = synchronized(lock) { rev }

    /** Reads something small out of the state without copying all of it ([block] must not change it, nor keep hold of it). */
    fun <T> peek(block: (JSONObject) -> T): T = synchronized(lock) { block(state) }

    /** What a save from the hub's page came to: the new revision ([rev], -1 when refused), and whether the page must read the state again. */
    data class HubSave(val rev: Long, val reload: Boolean)

    /**
     * The hub saves its whole in-memory state. Returns the new revision, or -1 if [baseRevision] is stale (something else changed
     * the state since the hub loaded it) or the document is invalid; the hub must then reload.
     */
    fun saveFromHub(raw: String, baseRevision: Long): Long = saveFromHubMerging(raw, baseRevision).rev

    /**
     * As [saveFromHub]. A page that is behind only by what other devices brought since it read the state (their changes arrive at any
     * moment, 1.12: while a result is being typed, say) is not refused: what it changed since is found against the state it read,
     * stamped as made now, and merged in as the newest; the page is then told to read the state again ([HubSave.reload]).
     */
    fun saveFromHubMerging(raw: String, baseRevision: Long): HubSave {
        var reload = false
        val r = synchronized(lock) {
            if (baseRevision != rev) {
                val then = history.firstOrNull { it.first == baseRevision }?.second
                if (then == null || !((baseRevision + 1)..rev).all { it in mergeRevs }) return HubSave(-1, true)
                val incoming = CouchState.parse(raw) ?: return HubSave(-1, true)
                val now = clock()
                Couple.stampChanges(then, incoming, now)
                Shared.stampChanges(then, incoming, now)
                val next = CouchState.copy(state)
                Shared.merge(next, Shared.share(incoming, null), now)
                reload = true
                commit(next, local = true)
            } else {
                val incoming = CouchState.parse(raw) ?: return HubSave(-1, true)
                // A name or a piece changed in the hub: stamped, so it travels to the TV and the other phone (see Couple).
                Couple.stampChanges(state, incoming, clock())
                commit(incoming, local = true)
            }
        }
        onLocalChange?.invoke()
        return HubSave(r, reload)
    }

    /** First-run migration of what the hub kept in localStorage. Only accepted while nothing has been stored yet. */
    fun migrateFromHub(raw: String): Long = synchronized(lock) {
        if (hadStoredState || store.read() != null) return -1
        val incoming = CouchState.parse(raw) ?: return -1
        commit(incoming)
    }

    /** A native change made on this device (a result saved in the player, a share received, a setting...). */
    fun update(block: (JSONObject) -> Unit): Long {
        val r = synchronized(lock) {
            val next = CouchState.copy(state)
            block(next)
            commit(next, local = true)
        }
        onLocalChange?.invoke()
        return r
    }

    /**
     * What another device's copy brought (1.12): [block] merges it and says whether anything changed; only then is the state written,
     * and nothing is stamped anew (the times are the other device's) nor told on.
     */
    fun mergeIf(block: (JSONObject) -> Boolean): Boolean = synchronized(lock) {
        val next = CouchState.copy(state)
        if (!block(next)) return false
        commit(next, local = false)
        true
    }

    /**
     * Another couch's document takes this one's place (2.0: the phone's home couch was changed, see CouchSetup; the caller has kept
     * the one before): [raw] is written and read, and every screen reads the state again. Nothing of the state before is merged in.
     */
    fun replace(raw: String) {
        synchronized(lock) {
            store.write((CouchState.parse(raw) ?: CouchState.empty()).toString())
            state = CouchState.parse(store.read()) ?: CouchState.empty()
            rev += 1
            history.clear(); mergeRevs.clear()
            history.addLast(rev to state)
            mergeRevs.add(rev)
            _merged.value = _merged.value + 1
            _revision.value = rev
        }
    }

    /** Another device's share (see [Shared.share]) into this state: the newer of each thing wins. True when anything changed. */
    fun mergeShared(incoming: JSONObject?): Boolean = mergeIf { s -> Shared.merge(s, incoming, clock()) }

    fun exportBackup(now: java.time.Instant = java.time.Instant.now()): String = Backup.export(snapshot(), now)

    /** Validates [text] as a backup and merges it into the current state (newest entry wins per day and game). */
    fun importBackup(text: String): Backup.Outcome = synchronized(lock) {
        when (val parsed = Backup.parse(text)) {
            is Backup.Parsed.Bad -> Backup.Outcome.Rejected(parsed.reason)
            is Backup.Parsed.Ok -> {
                val merged = Backup.merge(CouchState.copy(state), parsed.state)
                commit(merged.state)
                Backup.Outcome.Merged(merged.stats, rev)
            }
        }
    }

    private fun commit(next: JSONObject, local: Boolean = true): Long {
        // A change made here is stamped, so it wins where it should when the copies meet (1.12).
        if (local) Shared.stampChanges(state, next, clock())
        CouchState.prune(next, today())
        Shared.prune(next, CouchState.dayKey(today().minusDays(CouchState.KEEP_DAYS)))
        store.write(next.toString())
        state = next
        rev += 1
        history.addLast(rev to next)
        while (history.size > HISTORY) history.removeFirst().let { mergeRevs.remove(it.first) }
        if (!local) { mergeRevs.add(rev); _merged.value = _merged.value + 1 }
        _revision.value = rev
        return rev
    }

    private companion object {
        /** How many revisions back a page's copy may be and still be merged. */
        const val HISTORY = 24
    }
}
