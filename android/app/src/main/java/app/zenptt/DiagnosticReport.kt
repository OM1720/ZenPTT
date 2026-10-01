// Builds a shareable diagnostic snapshot and serializes it as text or upload JSON.
package app.zenptt

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DiagnosticReport(
    val schemaVersion: Int,
    val createdAtMs: Long,
    val appVersion: String,
    val androidVersion: String,
    val networkStatus: String,
    val headsetStatus: String,
    val audioRoute: String,
    val details: String,
) {
    fun asText(): String = buildString {
        appendLine("ZenPTT diagnostic report")
        appendLine("schema=$schemaVersion")
        appendLine("created_at_ms=$createdAtMs")
        appendLine("app_version=$appVersion")
        appendLine("android_version=$androidVersion")
        appendLine("network=$networkStatus")
        appendLine("headset=$headsetStatus")
        appendLine("audio_route=$audioRoute")
        appendLine()
        append(details)
    }

    fun asJson(): String = JSON.encodeToString(this)

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_DETAILS_LENGTH = 48_000
        private val JSON = Json { encodeDefaults = true }

        fun create(
            createdAtMs: Long,
            appVersion: String,
            androidVersion: String,
            networkStatus: String,
            headsetStatus: String,
            audioRoute: String,
            details: String,
        ): DiagnosticReport = DiagnosticReport(
            schemaVersion = SCHEMA_VERSION,
            createdAtMs = createdAtMs,
            appVersion = appVersion.take(40),
            androidVersion = androidVersion.take(80),
            networkStatus = networkStatus.take(40),
            headsetStatus = headsetStatus.take(80),
            audioRoute = audioRoute.take(40),
            details = details.trim().take(MAX_DETAILS_LENGTH),
        )
    }
}