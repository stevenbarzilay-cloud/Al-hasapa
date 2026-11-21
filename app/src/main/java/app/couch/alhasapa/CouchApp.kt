package app.couch.alhasapa

import android.app.Application
import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.data.FileStateStore
import app.couch.alhasapa.data.StateRepository
import app.couch.alhasapa.diag.CrashReport
import app.couch.alhasapa.diag.Startup
import java.io.File

/** Owns the two things every screen shares: the state and the catalogue. Created lazily, so tests that never touch them pay nothing. */
class CouchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Startup.mark("application")
        CrashReport.install(this)
    }

    val repo: StateRepository by lazy { StateRepository(FileStateStore(File(filesDir, "state.json"))) }

    private val base: Catalog by lazy { Catalog.load(this) }
    private var catalogRevision = -1L
    private var catalogCache: Catalog? = null

    /** The built-in games plus the ones added in the settings page. Rebuilt only when the state changes. */
    val catalog: Catalog
        get() = synchronized(this) {
            val rev = repo.currentRevision()
            val cached = catalogCache
            if (cached != null && rev == catalogRevision) return cached
            base.withCustom(repo.snapshot().optJSONArray("custom")).also { catalogCache = it; catalogRevision = rev }
        }
}
