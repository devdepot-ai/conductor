package io.devdepot.conductor.util

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Short human-friendly timestamps: "just now", "5m ago", "2h ago", "3d ago".
 * Falls back to `YYYY-MM-DD` once past 30 days since we stop caring about minute-
 * level precision.
 */
object RelativeTime {

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    fun format(instant: Instant?, now: Instant = Instant.now()): String {
        if (instant == null) return "—"
        val delta = Duration.between(instant, now)
        if (delta.isNegative) return "just now"
        val seconds = delta.seconds
        return when {
            seconds < 45 -> "just now"
            seconds < 3600 -> "${(seconds + 30) / 60}m ago"
            seconds < 86_400 -> "${seconds / 3600}h ago"
            seconds < 2_592_000 -> "${seconds / 86_400}d ago"
            else -> DATE.format(instant)
        }
    }
}
