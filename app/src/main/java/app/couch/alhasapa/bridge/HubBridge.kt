package app.couch.alhasapa.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.JavascriptInterface
import app.couch.alhasapa.R
import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.data.Backup
import app.couch.alhasapa.data.StateRepository
import app.couch.alhasapa.diag.Diagnostics
import app.couch.alhasapa.tv.TvMode
import org.json.JSONObject

/**
 * What the hub page may ask of the app, exposed to it as `window.couchNative`. Methods run on a background thread.
 *
 * This is safe to expose because the hub WebView only ever loads our own bundled page: every other origin is refused at the
 * network layer, and external pages (the games) get a different, origin-restricted channel, never this one.
 */
class HubBridge(
    private val context: Context,
    private val repo: StateRepository,
    private val catalogOf: () -> Catalog,
    private val actions: Actions,
) {
    /** For a fixed catalogue (tests). */
    constructor(context: Context, repo: StateRepository, catalog: Catalog, actions: Actions) : this(context, repo, { catalog }, actions)

    /** The catalogue as it is now: it grows when a game is added in the settings page. */
    private val catalog: Catalog get() = catalogOf()

    interface Actions {
        /** Open this game in the isolated player. */
        fun play(gameId: String)

        /** Open the accounts screen. */
        fun openAccounts()

        /** Open keyboard mode: the phone becomes the TV app's keyboard. */
        fun openKeyboardMode() {}
    }

    /** `{rev, fresh, state}`: `fresh` is true on the very first run, when the page may bring over what it stored itself. */
    @JavascriptInterface
    fun loadState(): String {
        val fresh = repo.isFresh()
        return JSONObject().put("rev", repo.currentRevision()).put("fresh", fresh).apply {
            if (!fresh) put("state", repo.snapshot())
        }.toString()
    }

    /** Returns the new revision, or "-1" if the page's copy was stale (it then reloads). */
    @JavascriptInterface
    fun saveState(json: String, baseRevision: String): String =
        repo.saveFromHub(json, baseRevision.toLongOrNull() ?: -2).toString()

    @JavascriptInterface
    fun migrateState(json: String): String = repo.migrateFromHub(json).toString()

    @JavascriptInterface
    fun loadCatalog(): String = catalog.toHubJson(repo.snapshot())

    @JavascriptInterface
    fun play(gameId: String) {
        if (catalog.game(gameId) != null) actions.play(gameId)
    }

    @JavascriptInterface
    fun openAccounts() = actions.openAccounts()

    /** "yes" on the phone, where the hub offers the switch to keyboard mode (the phone as the TV's keyboard); "no" on the TV itself. */
    @JavascriptInterface
    fun keyboardMode(): String = if (TvMode.isTelevision(context)) "no" else "yes"

    @JavascriptInterface
    fun openKeyboardMode() { if (!TvMode.isTelevision(context)) actions.openKeyboardMode() }

    /** What the settings page shows about the app: versions, device, how long the last start took, and the last crash if there was one. */
    @JavascriptInterface
    fun diagnostics(): String = Diagnostics.text(context)

    @JavascriptInterface
    fun exportBackup(): String = repo.exportBackup()

    @JavascriptInterface
    fun copyText(text: String): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
        return "ok"
    }

    /** `{ok, message}` (+ counts): the message is ready to show. */
    @JavascriptInterface
    fun importBackup(text: String): String = when (val outcome = repo.importBackup(text)) {
        is Backup.Outcome.Merged -> JSONObject().put("ok", true)
            .put("message", context.getString(R.string.import_ok, outcome.stats.added, outcome.stats.updated, outcome.stats.kept))
            .put("added", outcome.stats.added).put("updated", outcome.stats.updated).put("kept", outcome.stats.kept)
            .toString()
        is Backup.Outcome.Rejected -> JSONObject().put("ok", false).put("message", context.getString(messageFor(outcome.reason))).toString()
    }

    private fun messageFor(reason: Backup.Reason): Int = when (reason) {
        Backup.Reason.EMPTY -> R.string.import_empty
        Backup.Reason.NOT_JSON -> R.string.import_not_json
        Backup.Reason.NOT_OURS -> R.string.import_not_ours
        Backup.Reason.WRONG_VERSION -> R.string.import_version
        Backup.Reason.TOO_BIG -> R.string.import_too_big
        Backup.Reason.NO_HISTORY_OBJECT -> R.string.import_no_log
    }

    companion object {
        const val NAME = "couchNative"
    }
}
