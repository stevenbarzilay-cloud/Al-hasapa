package app.couch.alhasapa.player

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Separate browser profiles (cookies, storage, cache) inside this one app, via the AndroidX WebKit Profile API:
 * "games" for the shared sites (NYT, Haaretz...), and one per person for LinkedIn, so each stays logged in to their own account.
 * None of it is visible to, or shared with, the phone's Chrome.
 *
 * FALLBACK, documented as the brief asks: if the installed Android System WebView is too old for profiles
 * (MULTI_PROFILE unsupported), every page uses the app's single default profile. NYT and Haaretz still work and stay logged in;
 * two separate LinkedIn logins are then impossible, and the accounts screen says so and asks for a WebView update.
 */
object WebProfiles {
    const val DEFAULT = "default"

    fun supported(): Boolean = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    /**
     * Must run right after the WebView is created and BEFORE it loads anything.
     * Returns the profile actually in use ([DEFAULT] when profiles are unsupported).
     */
    @SuppressLint("RequiresFeature") // guarded by supported() just below (lint cannot see through the helper)
    fun attach(view: WebView, name: String): String {
        if (!supported()) return DEFAULT
        return runCatching {
            ProfileStore.getInstance().getOrCreateProfile(name)
            WebViewCompat.setProfile(view, name)
            name
        }.getOrDefault(DEFAULT)
    }

    /** The cookie jar of a profile (the app's default one when profiles are unsupported). */
    @SuppressLint("RequiresFeature") // guarded by supported() just below
    fun cookies(name: String): CookieManager {
        if (!supported() || name == DEFAULT) return CookieManager.getInstance()
        return runCatching { ProfileStore.getInstance().getOrCreateProfile(name).cookieManager }
            .getOrElse { CookieManager.getInstance() }
    }
}
