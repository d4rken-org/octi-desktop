package eu.darken.octi.desktop.linking

/**
 * Outcome of a [LinkController.link] call. UI maps these to user-visible error strings.
 *
 * The validation stages are surfaced separately so users can be told *where* the link code
 * failed — and so the share code isn't consumed unless all local validation passed.
 */
sealed class LinkResult {
    /** Linking succeeded and credentials are persisted. */
    data object Success : LinkResult()

    /** Input wasn't valid base64. Local validation failure; share code untouched. */
    data object InvalidBase64 : LinkResult()

    /** Bytes decoded but couldn't be ungzipped. Local validation failure. */
    data object InvalidGzip : LinkResult()

    /** Gzipped bytes weren't valid JSON in the LinkingData schema. Local validation failure. */
    data class InvalidJson(val cause: Throwable) : LinkResult()

    /** JSON parsed but Tink rejected the keyset bytes. Local validation failure. */
    data class InvalidKeyset(val cause: Throwable) : LinkResult()

    /** Server rejected the share code as expired or already consumed. (HTTP 401/404 path.) */
    data object ShareCodeExpiredOrConsumed : LinkResult()

    /** Network or other server-side failure during the link call itself. */
    data class NetworkError(val cause: Throwable) : LinkResult()

    /**
     * Server consumed the share code and returned credentials, but persisting them to the
     * keystore failed. The link controller has already issued a rollback `DELETE
     * /v1/devices/{self}` so no orphaned device remains on the server.
     */
    data class KeystoreFailureRolledBack(val cause: Throwable) : LinkResult()

    /**
     * Server consumed the share code AND the rollback DELETE itself failed. The user's device
     * is now registered on the server with no local credentials. Settings UI must surface this
     * with a "remove the orphaned device manually" affordance.
     */
    data class OrphanedDevice(val keystoreCause: Throwable, val rollbackCause: Throwable) : LinkResult()
}
