// Tracks synchronized audio activity, route sleep, and the first-after-wake grant tone.
package app.zenptt

internal class AudioPowerSaveState(private val nowMs: () -> Long) {
    private var active = false
    private var sleeping = false
    private var doubleTonePending = false
    private var lastActivityAt = nowMs()

    @Synchronized
    fun setActive(value: Boolean): Boolean {
        if (active == value) return false
        active = value
        sleeping = false
        doubleTonePending = false
        lastActivityAt = nowMs()
        return true
    }

    @Synchronized
    fun touch() {
        lastActivityAt = nowMs()
    }

    @Synchronized
    fun activity(timeoutMs: Long, doubleToneOnWake: Boolean): Boolean {
        if (!active) return false
        val now = nowMs()
        val idleTimeoutExpired = now - lastActivityAt >= timeoutMs
        lastActivityAt = now
        if (!sleeping && !idleTimeoutExpired) return false
        sleeping = false
        doubleTonePending = doubleToneOnWake
        return true
    }

    @Synchronized
    fun clearDoubleTone() {
        doubleTonePending = false
    }

    @Synchronized
    fun consumeGrantToneCount(): Int = if (doubleTonePending) {
        doubleTonePending = false
        2
    } else {
        1
    }

    @Synchronized
    fun remainingUntilSleep(timeoutMs: Long): Long = timeoutMs - (nowMs() - lastActivityAt)

    @Synchronized
    fun tryEnterSleep(timeoutMs: Long, audioBusy: Boolean): Boolean {
        if (!active || sleeping || audioBusy || remainingUntilSleep(timeoutMs) > 0) return false
        sleeping = true
        doubleTonePending = false
        return true
    }

    @Synchronized
    fun isActive(): Boolean = active

    @Synchronized
    fun isSleeping(): Boolean = sleeping
}
