package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Test

class FrequencyHistoryTest {
    @Test
    fun keepsFourRecentOrdinaryFrequenciesAndEchoLast() {
        assertEquals(
            listOf("ROOM5", "ROOM4", "ROOM3", "ROOM2", ECHO_CHANNEL),
            frequencyChoices(listOf("room5", "ROOM4", "ROOM3", "ROOM2", "ROOM1")),
        )
    }

    @Test
    fun removesInvalidDuplicatesAndEchoFromOrdinaryHistory() {
        assertEquals(
            listOf("ROOM2", "ROOM1", ECHO_CHANNEL),
            frequencyChoices(
                listOf("room2", ECHO_CHANNEL, "ROOM2", "invalid room", "room1"),
            ),
        )
    }

    @Test
    fun emptyHistoryStillOffersEcho() {
        assertEquals(
            listOf(ECHO_CHANNEL),
            frequencyChoices(emptyList()),
        )
    }

    @Test
    fun keepsValidDottedFrequencies() {
        assertEquals(
            listOf("446.00625", "ROOM.1", ECHO_CHANNEL),
            frequencyChoices(listOf("446.00625", "room.1")),
        )
    }
}
