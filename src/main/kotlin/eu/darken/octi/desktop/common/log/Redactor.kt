package eu.darken.octi.desktop.common.log

/**
 * Best-effort masking of sensitive substrings in log messages.
 *
 * This is a safety net, not a primary defence — the primary defence is "don't log secrets". Audit
 * the production log file in CI (see `WireCompatTest`) to confirm no plaintext sensitive data
 * leaks via formatting.
 */
internal object Redactor {

    // Order matters: most specific patterns first.
    private val patterns: List<Pair<Regex, String>> = listOf(
        // Tink proto keysets (base64'd, prefixed with one of the algorithm OIDs).
        Regex("""(\bAES256[A-Z_]+\b[:\s]*)([A-Za-z0-9+/=]{40,})""") to "$1<KEYSET_REDACTED>",
        // JSON values for keys named like "keyset", "password", "linkCode", "shareCode", "payload"
        // and a few more — case-insensitive.
        Regex("""("(?:keyset|password|linkCode|shareCode|devicePassword|encryptionKeyset|payload|documentBase64)"\s*:\s*")([^"]{4,})(")""", RegexOption.IGNORE_CASE) to "$1<REDACTED>$3",
        // Bearer-style basic auth strings.
        Regex("""(Authorization[:=]\s*Basic\s+)[A-Za-z0-9+/=]+""") to "$1<BASIC_REDACTED>",
    )

    fun redact(message: String): String {
        var result = message
        for ((pattern, replacement) in patterns) {
            result = pattern.replace(result, replacement)
        }
        return result
    }
}
