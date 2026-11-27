package app.couch.alhasapa

import androidx.test.core.app.ApplicationProvider
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.Couches
import org.json.JSONObject

/**
 * The couch most tests play on: ours, as it was before 2.0 (the two of us by name) with its couch's id. A new device has no couch
 * and opens on the first screen's choice (see CouchesFlowTest); the tests of everything after that begin here.
 */
object TestCouch {
    const val ID = "c0123456789abcde"
    const val NAME = "הספה של סטיב ורוני"

    /** A state of our couch, nothing played. */
    fun state(): JSONObject = CouchState.empty().also { ours(it) }

    fun ours(s: JSONObject) {
        s.put("names", JSONObject().put("a", "סטיב").put("b", "רוני"))
        Couches.set(s, ID, "", 0L)
    }

    /** The app's own state is our couch's (before any screen opens). */
    fun ours(app: CouchApp = ApplicationProvider.getApplicationContext()) {
        if (app.repo.peek { Couches.of(it)?.id } == ID) return
        app.repo.update { s -> ours(s) }
    }
}
