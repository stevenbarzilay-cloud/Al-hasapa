package app.couch.alhasapa.remote

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.MainActivity
import app.couch.alhasapa.R
import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.diag.Flight

/**
 * The phone looks at the relay now and then by itself (1.11), so "רוני כבר שיחק היום ב-Bandle — מה איתך?" reaches whoever holds it
 * with the app closed: every quarter of an hour at most (Android's own floor; a sleeping phone looks less often), only with a network,
 * and only on a phone the TV has given the relay to. Nothing runs on the TV, which reads the relay while it is open.
 */
class SoloJob : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread({
            runCatching { SoloNews.check(applicationContext) }.onFailure { Flight.log("solo: the background look failed (${it.javaClass.simpleName})") }
            jobFinished(params, false)
        }, "solo-job").also { it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }

    companion object {
        const val JOB_ID = 4711
        const val EVERY_MS = 15 * 60 * 1000L

        /**
         * On a phone with the relay: the look, every quarter of an hour, kept across restarts. Once is enough (it is kept). The phone's
         * manifest asks for the restart permission; the TV's has none and never gets here (hence the lint note).
         */
        @android.annotation.SuppressLint("MissingPermission")
        fun schedule(context: Context) {
            if (app.couch.alhasapa.BuildConfig.TV || RelayStore(context).config == null) return
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.allPendingJobs.any { it.id == JOB_ID }) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, SoloJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(EVERY_MS)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(job) }.onSuccess { Flight.log("solo: the phone will look at the relay by itself") }
        }
    }
}

/** What the relay brings, said to whoever holds the phone: a notification for each game someone else played alone, or finished. */
object SoloNews {
    private const val CHANNEL = "solo"

    /** Reads the relay (sending what waits first) and says what is new. On a background thread. */
    fun check(context: Context): List<SoloRelay.News> {
        val app = context.applicationContext as? CouchApp ?: return emptyList()
        val me = RemoteStore(context).me
        val news = SoloRelay(context, app.repo).sync(me)
        if (news.isNotEmpty()) notify(context, app, news)
        return news
    }

    /** Whether the phone lets the app say things when it is closed (asked once, the first time a game is played alone). */
    fun allowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Only on a phone, whose manifest asks for the permission it checks here first (the TV's has none and never says anything). */
    @android.annotation.SuppressLint("MissingPermission", "NotificationPermission")
    fun notify(context: Context, app: CouchApp, news: List<SoloRelay.News>) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.solo_channel), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.solo_channel_desc) },
        )
        val state = app.repo.snapshot()
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        for (n in news) {
            val game = app.catalog.game(n.game)?.n ?: continue
            val name = Couple.name(state, n.who)
            val two = n.who in Couple.WHO
            val title = if (n.reveal) context.getString(R.string.solo_news_reveal, game)
            else context.getString(if (two) R.string.solo_news_played else R.string.solo_news_played_other, name, game)
            val text = if (n.reveal) context.getString(if (two) R.string.solo_news_reveal_text else R.string.solo_news_reveal_text_other, name)
            else context.getString(R.string.solo_news_played_text)
            val note = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_solo)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            // One per game and day: the reveal takes the place of the challenge.
            runCatching { NotificationManagerCompat.from(context).notify("${n.day}|${n.game}".hashCode(), note) }
        }
        Flight.log("solo: ${news.size} said on the phone")
    }
}
