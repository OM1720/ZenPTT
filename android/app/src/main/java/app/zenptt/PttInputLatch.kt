// Merges touch, accessibility, and hardware PTT inputs into first-down and last-up edges.
package app.zenptt

internal enum class PttInputSource { Touch, Accessibility, Hardware }

internal class PttInputLatch(
    private val onFirstDown: () -> Unit,
    private val onLastUp: () -> Unit,
) {
    private val active = mutableSetOf<PttInputSource>()

    @Synchronized
    fun down(source: PttInputSource) {
        if (active.add(source) && active.size == 1) onFirstDown()
    }

    @Synchronized
    fun up(source: PttInputSource) {
        if (active.remove(source) && active.isEmpty()) onLastUp()
    }

    @Synchronized
    fun clear() = active.clear()
}
