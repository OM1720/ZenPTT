package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    @Test
    fun rendersVersionedTextAndJson() {
        val report = DiagnosticReport.create(
            createdAtMs = 123,
            appVersion = "0.3.0",
            androidVersion = "16 (SDK 36)",
            networkStatus = "Connected",
            headsetStatus = "Connected",
            audioRoute = "Bluetooth headset",
            details = "channel=normal (redacted)\nmetrics=RTT 10ms",
        )

        val text = report.asText()
        assertTrue(text.contains("schema=1"))
        assertTrue(text.contains("network=Connected"))
        assertTrue(text.contains("channel=normal (redacted)"))
        assertFalse(text.contains("SECRET42"))

        val json = report.asJson()
        assertTrue(json.contains("\"schemaVersion\":1"))
        assertTrue(json.contains("\"audioRoute\":\"Bluetooth headset\""))
    }

    @Test
    fun boundsDiagnosticDetails() {
        val report = DiagnosticReport.create(
            createdAtMs = 123,
            appVersion = "version",
            androidVersion = "android",
            networkStatus = "network",
            headsetStatus = "headset",
            audioRoute = "audio",
            details = "x".repeat(DiagnosticReport.MAX_DETAILS_LENGTH + 1_000),
        )

        assertEquals(DiagnosticReport.MAX_DETAILS_LENGTH, report.details.length)
    }
}