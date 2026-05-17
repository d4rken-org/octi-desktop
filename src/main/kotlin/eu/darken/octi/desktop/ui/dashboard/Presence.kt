package eu.darken.octi.desktop.ui.dashboard

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Codex review #5: peer "online" can't be derived from WS connection state — the server
 * broadcasts module changes, not presence. We use a lastSeen heuristic instead.
 *
 * Threshold mirrors a reasonable upper bound on regular Android sync cadence so that a peer
 * which checked in within the window is treated as fresh enough to call "online".
 */
object Presence {

    val ONLINE_THRESHOLD: Duration = 5.minutes

    /** True if [lastSeen] is within [ONLINE_THRESHOLD] of [now]. Null lastSeen → offline. */
    fun isOnline(lastSeen: Instant?, now: Instant = Clock.System.now()): Boolean {
        if (lastSeen == null) return false
        return (now - lastSeen) <= ONLINE_THRESHOLD
    }

    /** Human-readable "X ago" or "online" for a lastSeen timestamp. */
    fun describe(lastSeen: Instant?, now: Instant = Clock.System.now()): String {
        if (lastSeen == null) return "Never seen"
        val delta = now - lastSeen
        return when {
            delta <= 30.seconds -> "Just now"
            delta < 1.minutes -> "${delta.inWholeSeconds}s ago"
            delta < 60.minutes -> "${delta.inWholeMinutes} min ago"
            delta < (24 * 60).minutes -> "${delta.inWholeHours}h ago"
            else -> "${delta.inWholeDays}d ago"
        }
    }
}
