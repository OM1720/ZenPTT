package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun retriesForeverAtBoundedDelay() {
        val policy = ReconnectPolicy()

        assertEquals(
            listOf(
                ReconnectAttempt(1, 0),
                ReconnectAttempt(2, 250),
                ReconnectAttempt(3, 500),
                ReconnectAttempt(4, 1_000),
                ReconnectAttempt(5, 2_000),
                ReconnectAttempt(6, 5_000),
                ReconnectAttempt(7, 5_000),
            ),
            List(7) { policy.next() },
        )
    }

    @Test
    fun successfulJoinResetsRetrySequence() {
        val policy = ReconnectPolicy()
        policy.next()
        policy.next()
        policy.reset()

        assertEquals(ReconnectAttempt(1, 0), policy.next())
    }
}
