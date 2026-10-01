// Coordinates update checks and downloads while exposing race-safe state for the UI.
package app.zenptt

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AppUpdateUiState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val available: AppReleaseInfo? = null,
    val status: String? = null,
    val progress: Float? = null,
    val etaSeconds: Long? = null,
)

class AppUpdateCoordinator(
    private val client: AppUpdateClient,
    private val directory: File,
) {
    private data class VerifiedUpdate(
        val address: String,
        val downloaded: DownloadedUpdate,
    )

    private val generation = AtomicInteger()
    private val mutableState = MutableStateFlow(AppUpdateUiState())
    private var sourceAddress: String? = null
    @Volatile private var verifiedUpdate: VerifiedUpdate? = null

    val state: StateFlow<AppUpdateUiState> = mutableState

    fun addressChanged() {
        generation.incrementAndGet()
        sourceAddress = null
        verifiedUpdate = null
        mutableState.value = AppUpdateUiState()
    }

    fun showStatus(message: String) {
        mutableState.value = mutableState.value.copy(status = message)
    }

    fun check(address: String, installedVersionCode: Long, installedVersionName: String) {
        val requestGeneration = generation.incrementAndGet()
        sourceAddress = null
        verifiedUpdate = null
        mutableState.value = AppUpdateUiState(checking = true, status = "Checking for updates...")
        client.check(address) { result ->
            if (generation.get() != requestGeneration) return@check
            mutableState.value = result.fold(
                onSuccess = { release ->
                    if (release.versionCode > installedVersionCode) {
                        sourceAddress = address
                        AppUpdateUiState(
                            available = release,
                            status = "New release detected · ${release.versionName}",
                        )
                    } else AppUpdateUiState(status = installedVersionName)
                },
                onFailure = {
                    AppUpdateUiState(
                        status = "$installedVersionName \u00b7 ${userMessage(it, checking = true)}",
                    )
                },
            )
        }
    }

    fun downloadAndVerify(address: String, onDownloaded: (DownloadedUpdate) -> Unit) {
        val release = mutableState.value.available ?: return
        if (sourceAddress != address) {
            addressChanged()
            showStatus("Server address changed. Check for updates again")
            return
        }
        verifiedUpdate
            ?.takeIf {
                it.address == address &&
                    it.downloaded.release == release &&
                    it.downloaded.file.isFile
            }
            ?.let {
                mutableState.value = mutableState.value.copy(
                    status = "Update verified. Opening installer...",
                    progress = null,
                    etaSeconds = null,
                )
                onDownloaded(it.downloaded)
                return
            }
        verifiedUpdate = null
        val requestGeneration = generation.get()
        mutableState.value = mutableState.value.copy(
            downloading = true,
            status = "Downloading update...",
            progress = 0f,
            etaSeconds = null,
        )
        client.download(
            address = address,
            release = release,
            directory = directory,
            onProgress = { progress ->
                if (generation.get() != requestGeneration) return@download
                val fraction = if (progress.totalBytes > 0) {
                    (progress.downloadedBytes.toDouble() / progress.totalBytes).toFloat().coerceIn(0f, 1f)
                } else null
                mutableState.value = mutableState.value.copy(
                    status = if (progress.retryNumber > 0) {
                        "Connection lost · Retrying ${progress.retryNumber}/3"
                    } else {
                        downloadStatus(fraction, progress.etaSeconds)
                    },
                    progress = fraction,
                    etaSeconds = progress.etaSeconds,
                )
            },
        ) { result ->
            if (generation.get() != requestGeneration) return@download
            result.fold(
                onSuccess = { downloaded ->
                    verifiedUpdate = VerifiedUpdate(address, downloaded)
                    mutableState.value = mutableState.value.copy(
                        downloading = false,
                        status = "Update verified. Opening installer...",
                        progress = null,
                        etaSeconds = null,
                    )
                    onDownloaded(downloaded)
                },
                onFailure = { error ->
                    mutableState.value = mutableState.value.copy(
                        downloading = false,
                        status = userMessage(error, checking = false),
                        progress = null,
                        etaSeconds = null,
                    )
                },
            )
        }
    }

    fun diagnosticReport(): String = client.diagnosticReport()

    private fun downloadStatus(progress: Float?, etaSeconds: Long?): String {
        val percent = progress?.let { " · ${(it * 100).toInt()}%" }.orEmpty()
        val eta = etaSeconds?.let { " · ETA ${formatEta(it)}" }.orEmpty()
        return "Downloading update$percent$eta"
    }

    private fun formatEta(seconds: Long): String = when {
        seconds < 60 -> "${seconds}s"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }

    private fun userMessage(error: Throwable, checking: Boolean): String {
        val failure = error as? UpdateFailure
        return when (failure?.kind) {
            UpdateFailureKind.Storage -> "Not enough storage"
            UpdateFailureKind.Metadata -> "Update information is invalid"
            UpdateFailureKind.Verification -> "Downloaded update could not be verified"
            else -> if (checking) "Unable to check for updates" else "Unable to download update"
        }
    }
}
