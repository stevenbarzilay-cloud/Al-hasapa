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

    init {
        val stored = CouchState.parse(store.read())
        hadStoredState = stored != null
        state = stored ?: CouchState.empty()
    }

    /** True until anything has ever been stored: the very first run. */
    fun isFresh(): Boolean = synchronized(lock) { rev == 0L && !hadStoredState }

    fun json(): String = synchronized(lock) { state.toString() }

    fun snapshot(): JSONObject = synchronized(lock) { CouchState.copy(state) }

    fun currentRevision(): Long = synchronized(lock) { rev }

    /**
     * The hub saves its whole in-memory state. Returns the new revision, or -1 if [baseRevision] is stale (something else changed
     * the state since the hub loaded it) or the document is invalid; the hub must then reload.
     */
    fun saveFromHub(raw: String, baseRevision: Long): Long = synchronized(lock) {
        if (baseRevision != rev) return -1
        val incoming = CouchState.parse(raw) ?: return -1
        commit(incoming)
    }

    /** First-run migration of what the hub kept in localStorage. Only accepted while nothing has been stored yet. */
    fun migrateFromHub(raw: String): Long = synchronized(lock) {
        if (hadStoredState || store.read() != null) return -1
        val incoming = CouchState.parse(raw) ?: return -1
        commit(incoming)
    }

    /** A native change (result saved from the player, a share received...). */
    fun update(block: (JSONObject) -> Unit): Long = synchronized(lock) {
        val next = CouchState.copy(state)
        block(next)
        commit(next)
    }

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

    private fun commit(next: JSONObject): Long {
        CouchState.prune(next, today())
        store.write(next.toString())
        state = next
        rev += 1
        _revision.value = rev
        return rev
    }
}
