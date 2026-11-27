package app.couch.alhasapa.player

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The time of day in a game's frame (1.9.6). Until then the frame counted how long the game had been open; we would rather know what
 * time it is.
 */
object Clock {
    private val FORMAT = DateTimeFormatter.ofPattern("HH:mm")

    fun now(): String = LocalTime.now().format(FORMAT)

    /** How long until the minute changes (and a moment more, so the new minute is the one read). */
    fun untilNextMinute(nowMs: Long = System.currentTimeMillis()): Long = 60_000L - nowMs % 60_000L + 20L
}
