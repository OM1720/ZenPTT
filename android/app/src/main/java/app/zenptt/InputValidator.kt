// Normalizes and validates user-entered server addresses and channel codes.
package app.zenptt

import java.net.URI

object InputValidator {
    const val MAX_CHANNEL_CODE_LENGTH = 256
    private val channelPattern = Regex("^[A-Z0-9]+(?:\\.[A-Z0-9]+)*$")

    fun serverAddress(value: String): String? {
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return null
        }
        if (uri.scheme !in setOf("ws", "wss")) return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.port != -1 && uri.port !in 1..65535) return null
        if (uri.userInfo != null || uri.path !in listOf("", "/") || uri.query != null || uri.fragment != null) {
            return null
        }
        return value
    }

    fun channelCode(value: String): String? {
        val normalized = value.uppercase()
        if (normalized.isEmpty() || normalized.length > MAX_CHANNEL_CODE_LENGTH) return null
        return normalized.takeIf(channelPattern::matches)
    }

    fun powerSaveTimeoutMinutes(value: String): Int? =
        value.toIntOrNull()?.takeIf {
            it in MIN_POWER_SAVE_TIMEOUT_MINUTES..MAX_POWER_SAVE_TIMEOUT_MINUTES
        }

}

const val CHANNEL_CODE_ERROR = "Use A-Z, 0-9, and single dots between characters"

fun serverHttpUrl(address: String, path: String): String = address
    .replaceFirst("ws://", "http://")
    .replaceFirst("wss://", "https://")
    .trimEnd('/') + path

