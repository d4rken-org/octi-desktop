package eu.darken.octi.desktop.debug.rpc

/**
 * Parsed CLI configuration for the debug RPC server. Null means "feature disabled" (the default).
 *
 * Flag grammar (intentionally simple — no library, no GNU long-option overloading):
 *
 * ```
 * --enable-debug-rpc                   # required to enable the server at all
 * --debug-rpc-port <int>               # optional, otherwise the OS picks a free port
 * ```
 *
 * Other flags are forwarded untouched so Compose's `application { ... }` keeps working if it
 * ever starts parsing args. We only complain about *malformed* values for the debug flags.
 */
data class DebugRpcConfig(
    val enabled: Boolean,
    val explicitPort: Int?,
) {

    companion object {

        /**
         * Returns the parsed config and the leftover args (with the debug flags stripped).
         *
         * Throws [IllegalArgumentException] if a debug-related flag is malformed — the caller
         * (`Main`) should print the message and exit non-zero. We do *not* fall back to a
         * disabled state on bad input, because that would silently swallow typos.
         */
        fun parse(args: Array<String>): Parsed {
            var enabled = false
            var port: Int? = null
            var sawPortFlag = false
            val remaining = mutableListOf<String>()
            var i = 0
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--enable-debug-rpc" -> {
                        enabled = true
                        i++
                    }
                    "--debug-rpc-port" -> {
                        if (sawPortFlag) {
                            throw IllegalArgumentException("--debug-rpc-port specified more than once")
                        }
                        sawPortFlag = true
                        val valueIdx = i + 1
                        if (valueIdx >= args.size) {
                            throw IllegalArgumentException("--debug-rpc-port requires a value")
                        }
                        val raw = args[valueIdx]
                        val parsed = raw.toIntOrNull()
                            ?: throw IllegalArgumentException(
                                "--debug-rpc-port value must be an integer, got: $raw",
                            )
                        if (parsed !in 1..65535) {
                            throw IllegalArgumentException(
                                "--debug-rpc-port value must be in 1..65535, got: $parsed",
                            )
                        }
                        port = parsed
                        i += 2
                    }
                    else -> {
                        remaining += arg
                        i++
                    }
                }
            }
            if (!enabled && sawPortFlag) {
                throw IllegalArgumentException(
                    "--debug-rpc-port is meaningless without --enable-debug-rpc",
                )
            }
            val config = if (enabled) DebugRpcConfig(enabled = true, explicitPort = port) else null
            return Parsed(config = config, remainingArgs = remaining.toTypedArray())
        }
    }

    data class Parsed(
        val config: DebugRpcConfig?,
        val remainingArgs: Array<String>,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Parsed) return false
            return config == other.config && remainingArgs.contentEquals(other.remainingArgs)
        }

        override fun hashCode(): Int {
            var result = config?.hashCode() ?: 0
            result = 31 * result + remainingArgs.contentHashCode()
            return result
        }
    }
}
