// Maps service and connection state to concise user indicators.
package app.zenptt

fun networkIndicator(status: SessionStatus): String = when (status) {
    SessionStatus.Connecting -> "Connecting"
    SessionStatus.Reconnecting -> "Reconnecting"
    SessionStatus.ConnectionError -> "Offline"
    else -> "Connected"
}

fun headsetIndicator(status: String): String = status
    .removePrefix("BM008: ")
    .ifBlank { "inactive" }
    .replaceFirstChar(Char::uppercase)
