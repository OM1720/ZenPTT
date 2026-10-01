// Accounts for bounded playback frames by transmission generation and records queue peaks.
package app.zenptt

internal class PlaybackQueueState(private val maxFrames: Int) {
    data class Snapshot(val queuedFrames: Int, val maxQueuedFrames: Int)

    private val queuedByGeneration = mutableMapOf<Long, Int>()
    private var lastGeneration = 0L
    private var acceptingGeneration: Long? = null
    private var maxQueuedFrames = 0

    @Synchronized
    fun transmissionStarted(): Long {
        val generation = ++lastGeneration
        acceptingGeneration = generation
        maxQueuedFrames = queuedFrames()
        return generation
    }

    @Synchronized
    fun transmissionEnded(): Long? = acceptingGeneration.also { acceptingGeneration = null }

    @Synchronized
    fun reserveFrame(): Long? {
        val generation = acceptingGeneration ?: return null
        if (queuedFrames() >= maxFrames) return null
        queuedByGeneration[generation] = (queuedByGeneration[generation] ?: 0) + 1
        maxQueuedFrames = maxOf(maxQueuedFrames, queuedFrames())
        return generation
    }

    @Synchronized
    fun frameConsumed(generation: Long) {
        val remaining = (queuedByGeneration[generation] ?: return) - 1
        if (remaining > 0) queuedByGeneration[generation] = remaining
        else queuedByGeneration.remove(generation)
    }

    @Synchronized
    fun generationFinished(generation: Long) {
        queuedByGeneration.remove(generation)
    }

    @Synchronized
    fun hasQueuedFrames(): Boolean = queuedByGeneration.isNotEmpty()

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(queuedFrames(), maxQueuedFrames)

    @Synchronized
    fun clear() {
        acceptingGeneration = null
        queuedByGeneration.clear()
    }

    private fun queuedFrames(): Int = queuedByGeneration.values.sum()
}
