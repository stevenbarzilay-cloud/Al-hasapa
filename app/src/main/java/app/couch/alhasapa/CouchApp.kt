package app.couch.alhasapa

import android.app.Application
import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.data.FileStateStore
import app.couch.alhasapa.data.StateRepository
import java.io.File

/** Owns the two things every screen shares: the state and the catalogue. Created lazily, so tests that never touch them pay nothing. */
class CouchApp : Application() {
    val repo: StateRepository by lazy { StateRepository(FileStateStore(File(filesDir, "state.json"))) }
    val catalog: Catalog by lazy { Catalog.load(this) }
}
