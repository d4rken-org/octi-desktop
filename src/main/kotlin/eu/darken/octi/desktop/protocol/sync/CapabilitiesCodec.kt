package eu.darken.octi.desktop.protocol.sync

import eu.darken.octi.desktop.common.log.Logging.Priority.WARN
import eu.darken.octi.desktop.common.log.log
import eu.darken.octi.desktop.common.log.logTag
import eu.darken.octi.desktop.protocol.serialization.Serialization
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared wire-format encoder/decoder for the `Octi-Device-Capabilities` HTTP header and the
 * `capabilities` field on the `GET /v1/devices` response. Port of `app-main`'s
 * `sync-core/CapabilitiesCodec.kt` — limits and regex MUST match upstream byte-for-byte so the
 * server (which uses identical limits) accepts our header and so we interpret peer-supplied
 * capabilities the same way Android does.
 *
 * Validation limits (applied on encode AND decode):
 *   - at most [MAX_TAGS] tags
 *   - each tag at most [MAX_TAG_LENGTH] chars
 *   - each tag matches [TAG_REGEX] (ASCII `namespace:value` form)
 *   - header strings at most [MAX_HEADER_LENGTH] bytes
 *
 * Decode failures return null + WARN log (don't throw — untrusted input). Encode failures throw
 * (local producer drift is a bug, not a runtime degradation).
 *
 * The encoder canonicalises ordering by sorting tags so two equivalent capability sets produce
 * byte-identical wire output (matches upstream — enables stable hashing / cache comparison if
 * we ever want it on this side).
 */
class CapabilitiesCodec(
    private val json: Json = Serialization.json,
) {

    /** Encodes a capability set as a JSON array string suitable for an HTTP header. */
    fun encodeToHeader(caps: Set<String>): String {
        validateOrThrow(caps)
        return json.encodeToString(stringListSerializer, caps.sorted())
    }

    /**
     * Decodes a server- or manifest-supplied [JsonElement]. Inspects the element BEFORE
     * materialising into a Set so a malicious oversized array doesn't allocate. Returns
     * null on any validation failure.
     */
    fun decode(element: JsonElement?): Set<String>? {
        if (element == null || element is JsonNull) return null
        val array = element as? JsonArray ?: run {
            log(TAG, WARN) { "decode: not a JSON array" }
            return null
        }
        if (array.size > MAX_TAGS) {
            log(TAG, WARN) { "decode: too many tags (${array.size})" }
            return null
        }
        val result = LinkedHashSet<String>(array.size.coerceAtLeast(1))
        for (item in array) {
            val str = (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: run {
                log(TAG, WARN) { "decode: non-string element" }
                return null
            }
            if (str.length > MAX_TAG_LENGTH || !TAG_REGEX.matches(str)) {
                log(TAG, WARN) { "decode: invalid tag shape '$str'" }
                return null
            }
            result.add(str)
        }
        return result
    }

    /**
     * Convenience for the HTTP-header path. Parses the string into a [JsonElement] and
     * delegates to [decode]. Enforces [MAX_HEADER_LENGTH] before parsing.
     */
    fun decodeFromString(value: String?): Set<String>? {
        if (value.isNullOrBlank()) return null
        if (value.length > MAX_HEADER_LENGTH) {
            log(TAG, WARN) { "decode: header too long (${value.length})" }
            return null
        }
        val element = try {
            json.parseToJsonElement(value)
        } catch (e: SerializationException) {
            log(TAG, WARN) { "decode: malformed header JSON: ${e.message}" }
            return null
        }
        return decode(element)
    }

    private fun validateOrThrow(caps: Set<String>) {
        require(caps.size <= MAX_TAGS) { "too many tags (${caps.size}, max $MAX_TAGS)" }
        caps.forEach {
            require(it.length <= MAX_TAG_LENGTH && TAG_REGEX.matches(it)) {
                "invalid tag '$it' (length/charset)"
            }
        }
    }

    companion object {
        const val MAX_TAGS = 64
        const val MAX_TAG_LENGTH = 128
        const val MAX_HEADER_LENGTH = 4096
        val TAG_REGEX = Regex("""[a-z][a-z0-9]*:[A-Za-z0-9._\-]+""")
        private val TAG = logTag("Sync", "CapabilitiesCodec")
        private val stringListSerializer = ListSerializer(String.serializer())
    }
}
