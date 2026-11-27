package app.couch.alhasapa.remote

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A change was made on this device (1.12): whatever is connected to the other devices tells them (the TV to its phones, a phone to
 * the TV). Called from the state's own hook ([app.couch.alhasapa.data.StateRepository.onLocalChange]), on any thread.
 */
object ShareBus {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun add(l: () -> Unit) { listeners.addIfAbsent(l) }
    fun remove(l: () -> Unit) { listeners.remove(l) }
    fun changed() { for (l in listeners) runCatching { l() } }

    /** The days a connection brings up to date when it opens: the last two weeks of evenings. */
    const val DAYS = 14

    /** Today and the [n] - 1 days before it, newest first. */
    fun lastDays(n: Int, today: java.time.LocalDate = java.time.LocalDate.now()): List<String> =
        (0 until n).map { app.couch.alhasapa.data.CouchState.dayKey(today.minusDays(it.toLong())) }

    /**
     * What a connection sends when it opens: the last [DAYS] evenings and the rest, or with [all] every evening kept (the first time
     * a phone and the TV meet since 1.12, so the months before are alike on both too).
     */
    fun opening(book: CoupleBook, all: Boolean = false): List<org.json.JSONObject> = runCatching { book.shares(if (all) 0 else DAYS) }.getOrDefault(emptyList())

    /** What a change sends while connected: today's and yesterday's evenings, and the rest. */
    fun recent(book: CoupleBook): List<org.json.JSONObject> = runCatching { book.shares(2) }.getOrDefault(emptyList())
}

/**
 * The relay's side of [ShareBus] (1.12): a change made here is said through the relay a few seconds later (several changes made at
 * once go as one), from anywhere, on a background thread. Nothing on a device the TV has not given the relay to yet.
 */
object RelayOut {
    private val waiting = java.util.concurrent.atomic.AtomicBoolean(false)
    const val DELAY_MS = 4_000L

    fun schedule(context: android.content.Context) {
        val app = context.applicationContext as? app.couch.alhasapa.CouchApp ?: return
        if (RelayStore(app).config == null) return
        if (!waiting.compareAndSet(false, true)) return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Thread({
                waiting.set(false)
                runCatching { SoloRelay(app, app.repo).sendShare(sender(app)) }
            }, "share-out").start()
        }, DELAY_MS)
    }

    /** Who says it: whoever holds the phone, or the TV. */
    fun sender(context: android.content.Context): String =
        if (app.couch.alhasapa.BuildConfig.TV) "tv" else RemoteStore(context).me ?: "phone"
}
