package app.zenptt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val oldRelease = AppReleaseInfo(14, "0.5.0", "a".repeat(64), 10)
    private val newRelease = AppReleaseInfo(15, "0.6.0", "b".repeat(64), 20)

    @Test
    fun addressChangeClearsCheckingAndIgnoresOldResponse() {
        val client = FakeAppUpdateClient()
        val coordinator = coordinator(client)
        coordinator.check("ws://old:8000", 13, "0.4.0")

        coordinator.addressChanged()
        client.completeCheck(0, Result.success(oldRelease))

        assertEquals(AppUpdateUiState(), coordinator.state.value)
    }

    @Test
    fun lateResponseCannotReplaceReleaseFromCurrentAddress() {
        val client = FakeAppUpdateClient()
        val coordinator = coordinator(client)
        coordinator.check("ws://old:8000", 13, "0.4.0")
        coordinator.addressChanged()
        coordinator.check("ws://new:8000", 13, "0.4.0")

        client.completeCheck(1, Result.success(newRelease))
        client.completeCheck(0, Result.success(oldRelease))

        assertSame(newRelease, coordinator.state.value.available)
        assertEquals("New release detected · 0.6.0", coordinator.state.value.status)
    }

    @Test
    fun matchingReleaseShowsInstalledVersionOnly() {
        val client = FakeAppUpdateClient()
        val coordinator = coordinator(client)
        coordinator.check("ws://server:8000", 14, "0.5.0")

        client.completeCheck(0, Result.success(oldRelease))

        assertNull(coordinator.state.value.available)
        assertEquals("0.5.0", coordinator.state.value.status)
    }

    @Test
    fun newerInstalledReleaseShowsInstalledVersionOnly() {
        val client = FakeAppUpdateClient()
        val coordinator = coordinator(client)
        coordinator.check("ws://server:8000", 15, "0.6.0")

        client.completeCheck(0, Result.success(oldRelease))

        assertNull(coordinator.state.value.available)
        assertEquals("0.6.0", coordinator.state.value.status)
    }

    @Test
    fun checkFailureIncludesInstalledVersion() {
        val client = FakeAppUpdateClient()
        val coordinator = coordinator(client)
        coordinator.check("ws://server:8000", 14, "0.5.0")

        client.completeCheck(
            0,
            Result.failure(IllegalStateException("offline")),
        )

        assertNull(coordinator.state.value.available)
        assertEquals("0.5.0 \u00b7 Unable to check for updates", coordinator.state.value.status)
    }

    @Test
    fun downloadRequiresAddressUsedToCheckRelease() {
        val client = FakeAppUpdateClient()
        val coordinator = coordinator(client)
        coordinator.check("ws://old:8000", 13, "0.4.0")
        client.completeCheck(0, Result.success(oldRelease))

        coordinator.downloadAndVerify("ws://new:8000") {}

        assertTrue(client.downloads.isEmpty())
        assertNull(coordinator.state.value.available)
        assertEquals(
            "Server address changed. Check for updates again",
            coordinator.state.value.status,
        )
    }

    @Test
    fun downloadFailureRestoresActionableState() {
        val client = FakeAppUpdateClient()
        val coordinator = coordinator(client)
        coordinator.check("ws://server:8000", 13, "0.4.0")
        client.completeCheck(0, Result.success(oldRelease))

        coordinator.downloadAndVerify("ws://server:8000") {}
        assertTrue(coordinator.state.value.downloading)
        client.completeDownload(0, Result.failure(IllegalStateException("failed")))

        assertFalse(coordinator.state.value.downloading)
        assertSame(oldRelease, coordinator.state.value.available)
        assertEquals("Unable to download update", coordinator.state.value.status)
    }

    @Test
    fun verifiedDownloadIsDelivered() {
        val client = FakeAppUpdateClient()
        val coordinator = coordinator(client)
        val downloaded = DownloadedUpdate(oldRelease, temporaryFolder.newFile("update.apk"))
        var delivered: DownloadedUpdate? = null
        coordinator.check("ws://server:8000", 13, "0.4.0")
        client.completeCheck(0, Result.success(oldRelease))

        coordinator.downloadAndVerify("ws://server:8000") { delivered = it }
        client.completeDownload(0, Result.success(downloaded))

        assertSame(downloaded, delivered)
        assertFalse(coordinator.state.value.downloading)
        assertNull(coordinator.state.value.progress)
        assertEquals("Update verified. Opening installer...", coordinator.state.value.status)
    }

    @Test
    fun verifiedDownloadCanBeOpenedAgainWithoutAnotherDownload() {
        val client = FakeAppUpdateClient()
        val coordinator = coordinator(client)
        val downloaded = DownloadedUpdate(oldRelease, temporaryFolder.newFile("update.apk"))
        val delivered = mutableListOf<DownloadedUpdate>()
        coordinator.check("ws://server:8000", 13, "0.4.0")
        client.completeCheck(0, Result.success(oldRelease))

        coordinator.downloadAndVerify("ws://server:8000") { delivered += it }
        client.completeDownload(0, Result.success(downloaded))
        coordinator.downloadAndVerify("ws://server:8000") { delivered += it }

        assertEquals(1, client.downloads.size)
        assertEquals(listOf(downloaded, downloaded), delivered)
        assertFalse(coordinator.state.value.downloading)
    }

    @Test
    fun missingVerifiedDownloadIsDownloadedAgain() {
        val client = FakeAppUpdateClient()
        val coordinator = coordinator(client)
        val downloaded = DownloadedUpdate(oldRelease, temporaryFolder.newFile("update.apk"))
        coordinator.check("ws://server:8000", 13, "0.4.0")
        client.completeCheck(0, Result.success(oldRelease))
        coordinator.downloadAndVerify("ws://server:8000") {}
        client.completeDownload(0, Result.success(downloaded))
        assertTrue(downloaded.file.delete())

        coordinator.downloadAndVerify("ws://server:8000") {}

        assertEquals(2, client.downloads.size)
        assertTrue(coordinator.state.value.downloading)
    }

    @Test
    fun addressChangeClearsVerifiedDownload() {
        val client = FakeAppUpdateClient()
        val coordinator = coordinator(client)
        val downloaded = DownloadedUpdate(oldRelease, temporaryFolder.newFile("update.apk"))
        coordinator.check("ws://server:8000", 13, "0.4.0")
        client.completeCheck(0, Result.success(oldRelease))
        coordinator.downloadAndVerify("ws://server:8000") {}
        client.completeDownload(0, Result.success(downloaded))

        coordinator.addressChanged()
        coordinator.check("ws://server:8000", 13, "0.4.0")
        client.completeCheck(1, Result.success(oldRelease))
        coordinator.downloadAndVerify("ws://server:8000") {}

        assertEquals(2, client.downloads.size)
        assertTrue(coordinator.state.value.downloading)
    }

    private fun coordinator(client: FakeAppUpdateClient) = AppUpdateCoordinator(
        client,
        temporaryFolder.root.resolve("updates"),
    )

    private class FakeAppUpdateClient : AppUpdateClient {
        val checks = mutableListOf<(Result<AppReleaseInfo>) -> Unit>()
        val downloads = mutableListOf<(Result<DownloadedUpdate>) -> Unit>()
        val progressCallbacks = mutableListOf<(UpdateDownloadProgress) -> Unit>()

        override fun check(address: String, callback: (Result<AppReleaseInfo>) -> Unit) {
            checks += callback
        }

        override fun download(
            address: String,
            release: AppReleaseInfo,
            directory: File,
            onProgress: (UpdateDownloadProgress) -> Unit,
            callback: (Result<DownloadedUpdate>) -> Unit,
        ) {
            progressCallbacks += onProgress
            downloads += callback
        }

        fun completeCheck(index: Int, result: Result<AppReleaseInfo>) = checks[index](result)

        fun completeDownload(index: Int, result: Result<DownloadedUpdate>) = downloads[index](result)
    }
}
