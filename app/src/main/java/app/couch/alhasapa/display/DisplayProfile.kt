package app.couch.alhasapa.display

import java.net.URI
import org.json.JSONObject

enum class DisplayMode(val id: String) {
    NATIVE("native"), WIDE("wide"), PORTRAIT_FIT("portrait-fit");

    companion object {
        fun of(id: String?): DisplayMode = entries.firstOrNull { it.id == id } ?: NATIVE
    }
}

/** Where the always-visible couch UI (menu, game, clock, evening so far, buttons) sits. */
enum class UiPlace(val id: String) {
    /** Two panels beside a phone-shaped game (portrait-fit only). */
    SIDES("sides"),
    TOP("top"),
    BOTTOM("bottom"),

    /** The game and clock on a top bar, the evening and the buttons on a bottom bar. */
    BOTH("both"),

    /** Only the menu button, floating in a corner: the most room for the game, and still never without a way in. */
    MINIMAL("minimal");

    companion object {
        fun of(id: String?): UiPlace? = entries.firstOrNull { it.id == id }
    }
}

/** How much room the couch UI takes: small bars are 48 dp tall (the touch minimum), large ones 76 dp. */
enum class UiSize(val id: String, val barDp: Float) {
    SMALL("s", 48f), MEDIUM("m", 60f), LARGE("l", 76f);

    companion object {
        fun of(id: String?): UiSize? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How one site is shown. The virtual viewport (vw x vh, in CSS px) is what the page believes the screen is: 430 x 800 is a
 * phone held upright, 1280 is a desktop window. zoom is a percentage of that (more zoom = a smaller virtual viewport) up to
 * [DisplayLayout.REFLOW_MAX_ZOOM], the most a page can usefully be squeezed; beyond that the picture is magnified instead, which
 * crops the edges, and [panX]/[panY] say which part of the page stays in view.
 */
data class DisplayProfile(
    val mode: DisplayMode = DisplayMode.NATIVE,
    val zoom: Int = 100,
    val injectCss: Boolean = true,
    /** TV overscan margin, percent of the screen on each side. */
    val safeArea: Double = 2.5,
    /** false = the page is forced light (the TV reads best that way). */
    val dark: Boolean = false,
    val vw: Int = 430,
    val vh: Int = 800,
    /** CSS injected at document start to hide banners and the like. */
    val css: String = "",
    /** Where the always-visible UI sits; null = by mode (panels beside a phone-shaped game, a top bar otherwise). */
    val ui: UiPlace? = null,
    /** How big it is; null = by placement (medium for panels, small for bars, which cost the game height). */
    val uiSize: UiSize? = null,
    /** Where the view is centred on the page when zoomed in past what the page can re-fit (0..1 across and down). */
    val panX: Double = 0.5,
    val panY: Double = 0.5,
    /**
     * On a TV only: the site's OWN display preferences (the ones its settings page keeps in the browser's storage, by name), switched
     * on when the people have not chosen either way. Krillion's "Low-power mode": the game as its author made it for weak devices.
     * From the built-in table only; never part of what is saved for a site.
     */
    val tvStorage: Map<String, String> = emptyMap(),
    /**
     * On a TV only: the site's blurs and frosted glass are taken out of its style sheets as they load (see [DisplayScript]). For a site
     * whose effects are more than the box's graphics chip can draw: Dialed froze the TV's picture in its second game (November 2026).
     * From the built-in table only, like [tvStorage].
     */
    val tvLight: Boolean = false,
) {
    /** The placement actually used: panels only make sense beside a phone-shaped game, anything else gets a top bar. */
    val place: UiPlace
        get() = when (val u = ui) {
            null, UiPlace.SIDES -> if (mode == DisplayMode.PORTRAIT_FIT) UiPlace.SIDES else UiPlace.TOP
            else -> u
        }

    val size: UiSize get() = uiSize ?: if (place == UiPlace.SIDES) UiSize.MEDIUM else UiSize.SMALL

    fun toJson(): JSONObject = JSONObject().put("mode", mode.id).put("zoom", zoom).put("injectCss", injectCss)
        .put("safeArea", safeArea).put("dark", dark).put("vw", vw).put("vh", vh)
        .put("panX", panX).put("panY", panY).apply {
            ui?.let { put("ui", it.id) }
            uiSize?.let { put("uiSize", it.id) }
        }

    /** This profile with the fields present in [o] replaced (what a saved override or a profile entry says). */
    fun overlay(o: JSONObject): DisplayProfile = copy(
        mode = if (o.has("mode")) DisplayMode.of(o.optString("mode")) else mode,
        zoom = if (o.has("zoom")) o.optInt("zoom", zoom).coerceIn(MIN_ZOOM, MAX_ZOOM) else zoom,
        injectCss = if (o.has("injectCss")) o.optBoolean("injectCss", injectCss) else injectCss,
        safeArea = if (o.has("safeArea")) o.optDouble("safeArea", safeArea).coerceIn(0.0, 8.0) else safeArea,
        dark = if (o.has("dark")) o.optBoolean("dark", dark) else dark,
        vw = if (o.has("vw")) o.optInt("vw", vw).coerceIn(200, 3000) else vw,
        vh = if (o.has("vh")) o.optInt("vh", vh).coerceIn(200, 3000) else vh,
        css = if (o.has("css")) o.optString("css") else css,
        // "panels": false was how 1.0.2 turned the panels off; it means the minimal UI now.
        ui = if (o.has("ui")) UiPlace.of(o.optString("ui")) ?: ui
        else if (o.has("panels") && !o.optBoolean("panels", true)) UiPlace.MINIMAL else ui,
        uiSize = if (o.has("uiSize")) UiSize.of(o.optString("uiSize")) ?: uiSize else uiSize,
        panX = if (o.has("panX")) o.optDouble("panX", panX).coerceIn(0.0, 1.0) else panX,
        panY = if (o.has("panY")) o.optDouble("panY", panY).coerceIn(0.0, 1.0) else panY,
        tvStorage = o.optJSONObject("tvStorage")?.let { t -> t.keys().asSequence().associateWith { t.optString(it) } } ?: tvStorage,
        tvLight = if (o.has("tvLight")) o.optBoolean("tvLight", tvLight) else tvLight,
    )

    companion object {
        const val MIN_ZOOM = 50
        const val MAX_ZOOM = 300
    }
}

/** A profile together with the key it is saved under (host + path prefix). */
data class Resolved(val key: String, val profile: DisplayProfile, val fromUser: Boolean)

/**
 * Finds the display profile for a page: the built-in table (assets/display_profiles.json) and the person's own saved
 * overrides (state.displays), matched by host and path prefix, the longest match winning. A saved override of the same key
 * beats the built-in one.
 */
class DisplayProfiles(json: String) {
    private val doc = JSONObject(json)
    private val defaults: DisplayProfile = DisplayProfile().overlay(doc.getJSONObject("defaults"))
    private val builtIn: List<Pair<String, JSONObject>> = doc.getJSONArray("profiles").let { a ->
        (0 until a.length()).map { a.getJSONObject(it).getString("match").lowercase() to a.getJSONObject(it) }
    }

    fun resolve(url: String, userOverrides: JSONObject?): Resolved {
        val (host, path) = split(url)
        val candidates = LinkedHashMap<String, Pair<JSONObject, Boolean>>()
        for ((k, o) in builtIn) candidates[k] = o to false
        userOverrides?.keys()?.forEach { k -> userOverrides.optJSONObject(k)?.let { candidates[k.lowercase()] = it to true } }
        var best: String? = null
        for (k in candidates.keys) if (matches(k, host, path) && (best == null || k.length > best.length)) best = k
        if (best == null) return Resolved(defaultKey(host, path), defaults, false)
        val (o, user) = candidates.getValue(best)
        // A user override replaces fields of the built-in entry of the same key, which replaces the defaults.
        var p = defaults
        builtIn.firstOrNull { it.first == best }?.let { p = p.overlay(it.second) }
        if (user) p = p.overlay(o)
        // The TV's preferences for a site are not a matter of how one page is shown: they hold for every page of the site, whatever
        // was saved for a page of it (a saved, more specific key would otherwise hide the built-in entry that names them).
        val tvStorage = builtIn.filter { matches(it.first, host, path) }.sortedBy { it.first.length }
            .fold(emptyMap<String, String>()) { all, (_, entry) -> all + DisplayProfile().overlay(entry).tvStorage }
        val tvLight = builtIn.any { matches(it.first, host, path) && it.second.optBoolean("tvLight", false) }
        return Resolved(best, p.copy(tvStorage = tvStorage, tvLight = tvLight), user)
    }

    /** The key a new override is saved under when nothing matched: the host plus the first path segment. */
    fun defaultKey(host: String, path: String): String {
        val first = path.trim('/').substringBefore('/')
        return (host + if (first.isEmpty()) "" else "/$first").lowercase()
    }

    fun keyFor(url: String, resolved: Resolved): String =
        if (resolved.fromUser || builtIn.any { it.first == resolved.key }) resolved.key else split(url).let { defaultKey(it.first, it.second) }

    companion object {
        fun split(url: String): Pair<String, String> {
            val u = runCatching { URI(url.trim()) }.getOrNull()
            return ((u?.host ?: "").lowercase().removePrefix("www.")) to ((u?.path ?: "").lowercase())
        }

        /** `host/path-prefix` matches a page when the host is that host (or a subdomain) and the path starts with the prefix. */
        fun matches(key: String, host: String, path: String): Boolean {
            val keyHost = key.substringBefore('/')
            val keyPath = if (key.contains('/')) "/" + key.substringAfter('/') else ""
            val hostOk = host == keyHost || host.endsWith(".$keyHost")
            return hostOk && path.startsWith(keyPath)
        }
    }
}
