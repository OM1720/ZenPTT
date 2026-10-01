package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputValidatorTest {
    @Test
    fun convertsWebSocketServerAddressToHttp() {
        assertEquals("http://example.test:8080/health", serverHttpUrl("ws://example.test:8080", "/health"))
        assertEquals("https://example.test/diagnostics", serverHttpUrl("wss://example.test/", "/diagnostics"))
    }

    @Test
    fun validatesServerAddress() {
        listOf(
            "ws://192.168.1.10:8000",
            "wss://example.test:443",
            "ws://example.test:1",
            "wss://example.test:65535",
            "ws://example.test/",
        ).forEach { address ->
            assertEquals(address, InputValidator.serverAddress(address))
        }

        listOf(
            "http://example.test:8000",
            "ftp://example.test",
            "ws://user@example.test",
            "ws://example.test/path",
            "ws://example.test?query=1",
            "ws://example.test#fragment",
            "ws://example.test:0",
            "ws://example.test:65536",
            "ws:///",
        ).forEach { address ->
            assertNull(address, InputValidator.serverAddress(address))
        }
    }

    @Test
    fun normalizesAndValidatesChannelCode() {
        assertEquals("ROOM42", InputValidator.channelCode("room42"))
        assertEquals("446.00625", InputValidator.channelCode("446.00625"))
        assertEquals("ROOM.1", InputValidator.channelCode("room.1"))
        assertEquals("A.B.C", InputValidator.channelCode("a.b.c"))
        listOf("", ".", "...", ".ROOM", "ROOM.", "ROOM..1", "room-42", "room 42", "room,42")
            .forEach { value -> assertNull(value, InputValidator.channelCode(value)) }
        assertNull(InputValidator.channelCode("A".repeat(257)))
    }

    @Test
    fun validatesPowerSaveTimeoutMinutes() {
        assertEquals(1, InputValidator.powerSaveTimeoutMinutes("1"))
        assertEquals(1_440, InputValidator.powerSaveTimeoutMinutes("1440"))
        listOf("", "ten", "0", "1441").forEach { value ->
            assertNull(value, InputValidator.powerSaveTimeoutMinutes(value))
        }
    }

}

