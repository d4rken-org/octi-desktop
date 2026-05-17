package eu.darken.octi.desktop.common.log

import org.slf4j.LoggerFactory

/**
 * Desktop port of the Android app's `log(TAG) { ... }` API.
 *
 * Backed by slf4j/logback. Messages are routed through [Redactor] before reaching the appender, so
 * any code that accidentally logs sensitive content (keysets, link codes, passwords, decrypted
 * payloads) still has the sensitive substring masked.
 *
 * The Android side prefixes tags with 🐙. We do the same so log scraping tools can pattern-match
 * across platforms.
 */
object Logging {

    enum class Priority { VERBOSE, DEBUG, INFO, WARN, ERROR }

    @Volatile
    private var installed = false

    fun install() {
        installed = true
    }

    @PublishedApi
    internal fun hasReceivers(): Boolean = installed

    @PublishedApi
    internal fun emit(tag: String, priority: Priority, message: String, throwable: Throwable?) {
        // logback uses '.' as its logger-hierarchy separator. Our tags use ':' (Android visual
        // convention). Translate when looking up the slf4j logger so a single
        // `<logger name="🐙">` rule in logback.xml applies to all child tags. The original
        // `:`-separated tag still appears in the message body, preserving log output style.
        val logger = LoggerFactory.getLogger(tag.replace(':', '.'))
        val redacted = "[$tag] " + Redactor.redact(message)
        when (priority) {
            Priority.VERBOSE -> if (logger.isTraceEnabled) logger.trace(redacted, throwable)
            Priority.DEBUG -> if (logger.isDebugEnabled) logger.debug(redacted, throwable)
            Priority.INFO -> if (logger.isInfoEnabled) logger.info(redacted, throwable)
            Priority.WARN -> if (logger.isWarnEnabled) logger.warn(redacted, throwable)
            Priority.ERROR -> if (logger.isErrorEnabled) logger.error(redacted, throwable)
        }
    }
}

/** Build a tag string. Mirrors `app-common` `logTag()` so copied code Just Works. */
fun logTag(vararg parts: String): String = "🐙:" + parts.joinToString(":")

inline fun log(
    tag: String,
    priority: Logging.Priority = Logging.Priority.DEBUG,
    throwable: Throwable? = null,
    message: () -> String,
) {
    if (Logging.hasReceivers()) {
        Logging.emit(tag, priority, message(), throwable)
    }
}

fun Throwable.asLog(): String {
    val sw = java.io.StringWriter(256)
    val pw = java.io.PrintWriter(sw, false)
    printStackTrace(pw)
    pw.flush()
    return sw.toString()
}
