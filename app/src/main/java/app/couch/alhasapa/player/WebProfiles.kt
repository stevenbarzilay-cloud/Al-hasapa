package app.couch.alhasapa.player

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.core.content.edit
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.couch.alhasapa.diag.Flight
import java.io.File

/**
 * Separate browser profiles (cookies, storage, cache) inside this one app, via the AndroidX WebKit Profile API:
 * "games" for the shared sites (NYT, Haaretz...), and one per person for LinkedIn, so each stays logged in to their own account.
 * None of it is visible to, or shared with, the phone's Chrome.
 *
 * FALLBACK, documented as the brief asks: if the installed Android System WebView is too old for profiles
 * (MULTI_PROFILE unsupported), every page uses the app's single default profile. NYT and Haaretz still work and stay logged in;
 * two separate LinkedIn logins are then impossible, and the accounts screen says so and asks for a WebView update.
 *
 * SAFETY NET (1.8): on a TV box in November 2026 the WebView never came back from creating a profile, the app froze and Android
 * closed it, so no game opened. Every call into the profile code now leaves a mark first and removes it after; a mark still there
 * at the next start (or a freeze inside that code in the flight recorder) means this device's WebView gets stuck there, and profiles
 * are then off on it: every site uses the default profile, games open. They come back by themselves when the WebView is updated, or
 * from the accounts screen ("לנסות שוב").
 */
object WebProfiles {
    const val DEFAULT = "default"

    private const val MARK = "profile-call.txt"
    private const val PREFS = "webview"
    private const val SEEN = "profiles_seen_freezes"

    @Volatile private var dir: File? = null
    @Volatile private var off = false

    /** Why profiles are off on this device (shown in the accounts screen and the information panel), or null. */
    @Volatile var offBecause: String? = null
        private set

    /** Once per process, before any WebView: was the WebView stuck in its profile code last time? */
    fun init(context: Context) {
        dir = context.filesDir
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val webview = runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull().orEmpty()
        val mark = File(context.filesDir, MARK)
        // A freeze stays in the recorder for two starts: each one is acted on once, so a retry by hand is not undone by the same freeze.
        val freezes = listOf("getOrCreateProfile", "ProfileStore", "setProfile").flatMap { Flight.freezes(it) }.map { it.hashCode().toString() }.toSet()
        val seen = prefs.getStringSet(SEEN, null).orEmpty()
        val stuck = when {
            mark.isFile -> runCatching { mark.readText() }.getOrDefault("a profile call").also { mark.delete() }
            freezes.any { it !in seen } -> "creating a profile (seen in the flight recorder)"
            else -> null
        }
        if (stuck != null) {
            prefs.edit { putString("profiles_off_for", webview); putString("profiles_off_why", stuck); putStringSet(SEEN, freezes) }
            Flight.log("profiles: the WebView got stuck in '$stuck'; separate logins are off on this device until its WebView changes")
        }
        val offFor = prefs.getString("profiles_off_for", null)
        when {
            offFor == null -> { off = false; offBecause = null }
            offFor == webview -> { off = true; offBecause = prefs.getString("profiles_off_why", null) ?: "a profile call" }
            else -> { prefs.edit { remove("profiles_off_for"); remove("profiles_off_why") }; off = false; offBecause = null; Flight.log("profiles: a new WebView ($webview), trying them again") }
        }
    }

    /** The person asked to try separate logins again (accounts screen). */
    fun retry(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove("profiles_off_for"); remove("profiles_off_why") }
        off = false; offBecause = null
        Flight.log("profiles: turned back on by hand")
    }

    fun supported(): Boolean = !off && runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    /**
     * Must run right after the WebView is created and BEFORE it loads anything.
     * Returns the profile actually in use ([DEFAULT] when profiles are unsupported or off).
     */
    @SuppressLint("RequiresFeature") // guarded by supported() just below (lint cannot see through the helper)
    fun attach(view: WebView, name: String): String {
        if (!supported() || name == DEFAULT) return DEFAULT
        return guarded("getOrCreateProfile $name") {
            ProfileStore.getInstance().getOrCreateProfile(name)
            WebViewCompat.setProfile(view, name)
            name
        } ?: DEFAULT
    }

    /** The cookie jar of a profile (the app's default one when profiles are unsupported or off). */
    @SuppressLint("RequiresFeature") // guarded by supported() just below
    fun cookies(name: String): CookieManager {
        if (!supported() || name == DEFAULT) return CookieManager.getInstance()
        return guarded("cookies of $name") { ProfileStore.getInstance().getOrCreateProfile(name).cookieManager } ?: CookieManager.getInstance()
    }

    /** Runs [block] (a call into the WebView's profile code) with a mark on disk for its whole length: see the safety net above. */
    private inline fun <T> guarded(what: String, block: () -> T): T? {
        val mark = dir?.let { File(it, MARK) }
        runCatching { mark?.writeText(what) }
        return try {
            block()
        } catch (t: Throwable) {
            Flight.log("profiles: $what failed: $t")
            null
        } finally {
            runCatching { mark?.delete() }
        }
    }

    /** Back to nothing (tests). */
    internal fun resetForTests() { off = false; offBecause = null; dir = null }
}
