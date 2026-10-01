package app.zenptt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerDraftInvalidationTest {
    @Test
    fun draftEditClearsPingAndIgnoresLateResponse() {
        val health = DeferredHealth()
        val viewModel = model(health)
        viewModel.pingServer("wss://old.example")
        assertEquals("Checking server...", viewModel.state.value.serverCheckStatus)

        viewModel.setServerAddress("wss://edited.example")
        health.complete(0, Result.success(Unit))

        assertNull(viewModel.state.value.serverCheckStatus)
    }

    @Test
    fun resetOrUndoInvalidationClearsPingWithoutApplyingDraft() {
        val health = DeferredHealth()
        val viewModel = model(health)
        val savedAddress = viewModel.state.value.serverAddress
        viewModel.pingServer("wss://draft.example")

        viewModel.invalidateServerCheck()
        health.complete(0, Result.failure(IllegalStateException("late")))

        assertEquals(savedAddress, viewModel.state.value.serverAddress)
        assertNull(viewModel.state.value.serverCheckStatus)
    }

    private fun model(health: DeferredHealth) = ChannelViewModel(
        Store(),
        Connection(),
        healthClient = health,
    )

    private class DeferredHealth : ServerHealthClient {
        private val callbacks = mutableListOf<(Result<Unit>) -> Unit>()
        override fun check(address: String, callback: (Result<Unit>) -> Unit) { callbacks += callback }
        fun complete(index: Int, result: Result<Unit>) = callbacks[index](result)
    }

    private class Store : ConnectionPreferences {
        override fun load() = DEFAULT_SERVER_ADDRESS
        override fun save(value: String) = Unit
        override fun loadLastChannel(): String? = null
        override fun saveLastChannel(value: String) = Unit
        override fun loadPowerSaveTimeoutMinutes() = 10
        override fun savePowerSaveTimeoutMinutes(value: Int) = Unit
    }

    private class Connection : ConnectionClient {
        override fun connect(address: String, channel: String, listener: ConnectionListener) = Unit
        override fun requestPtt(requestId: String) = true
        override fun releasePtt(requestId: String) = true
        override fun sendAudio(message: ByteArray) = true
        override fun disconnect() = Unit
    }
}
