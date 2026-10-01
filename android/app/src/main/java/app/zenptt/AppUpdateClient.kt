// Downloads versioned APK releases with bounded retries, resume support, and verification.
package app.zenptt

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@Serializable
data class AppReleaseInfo(
    @SerialName("version_code") val versionCode: Long,
    @SerialName("version_name") val versionName: String,
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
)

data class DownloadedUpdate(val release: AppReleaseInfo, val file: File)

data class UpdateDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val etaSeconds: Long? = null,
    val retryNumber: Int = 0,
)

enum class UpdateFailureKind { Network, Http, Metadata, Verification, Storage }

class UpdateFailure(
    val kind: UpdateFailureKind,
    message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)

interface AppUpdateClient {
    fun check(address: String, callback: (Result<AppReleaseInfo>) -> Unit)
    fun download(
        address: String,
        release: AppReleaseInfo,
        directory: File,
        onProgress: (UpdateDownloadProgress) -> Unit = {},
        callback: (Result<DownloadedUpdate>) -> Unit,
    )

    fun diagnosticReport(): String = ""
}

class OkHttpAppUpdateClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(OPERATION_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .build(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMs: () -> Long = System::currentTimeMillis,
) : AppUpdateClient {
    private val diagnostics = ArrayDeque<String>()

    override fun check(address: String, callback: (Result<AppReleaseInfo>) -> Unit) {
        submit(callback) {
            withRetries("metadata") { _, deadlineMs ->
                val request = request(address, "/app/latest")
                execute(request, deadlineMs) { response ->
                    val json = response.body?.string().orEmpty()
                    try {
                        validatedRelease(json)
                    } catch (error: SerializationException) {
                        throw UpdateFailure(UpdateFailureKind.Metadata, "Invalid update metadata", cause = error)
                    }
                }
            }
        }
    }

    override fun download(
        address: String,
        release: AppReleaseInfo,
        directory: File,
        onProgress: (UpdateDownloadProgress) -> Unit,
        callback: (Result<DownloadedUpdate>) -> Unit,
    ) {
        submit(callback) {
            withRetries(
                operation = "download",
                onRetry = { retry ->
                    onProgress(
                        UpdateDownloadProgress(
                            downloadedBytes = File(directory, ".zenptt-${release.versionCode}.tmp").length(),
                            totalBytes = release.sizeBytes,
                            retryNumber = retry,
                        ),
                    )
                },
            ) { _, deadlineMs ->
                downloadAttempt(address, release, directory, deadlineMs, onProgress)
            }
        }
    }

    override fun diagnosticReport(): String = synchronized(diagnostics) {
        if (diagnostics.isEmpty()) "update=idle" else diagnostics.joinToString("\n")
    }

    private fun downloadAttempt(
        address: String,
        release: AppReleaseInfo,
        directory: File,
        deadlineMs: Long,
        onProgress: (UpdateDownloadProgress) -> Unit,
    ): DownloadedUpdate {
        ensureDirectory(directory)
        val temporary = File(directory, ".zenptt-${release.versionCode}.tmp")
        val target = File(directory, "zenptt-update.apk")
        if (temporary.length() > release.sizeBytes) {
            temporary.delete()
            throw UpdateFailure(UpdateFailureKind.Verification, "APK size mismatch")
        }
        if (temporary.length() == release.sizeBytes) {
            return verifyAndFinalize(temporary, target, release)
        }

        val existingBytes = temporary.length()
        ensureStorage(directory, release.sizeBytes - existingBytes)
        val requestBuilder = request(address, "/app/releases/${release.versionCode}/download").newBuilder()
        if (existingBytes > 0) requestBuilder.header("Range", "bytes=$existingBytes-")
        val call = client.newCall(requestBuilder.build())
        call.timeout().timeout((deadlineMs - nowMs()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            ensureSuccessful(response)
            val append = resumeMode(response, existingBytes, release.sizeBytes)
            val initialBytes = if (append) existingBytes else 0L
            saveResponse(response, temporary, initialBytes, release, onProgress)
        }
        return verifyAndFinalize(temporary, target, release)
    }

    private fun saveResponse(
        response: Response,
        temporary: File,
        initialBytes: Long,
        release: AppReleaseInfo,
        onProgress: (UpdateDownloadProgress) -> Unit,
    ) {
        val body = response.body ?: throw UpdateFailure(UpdateFailureKind.Network, "Empty APK response", true)
        val expectedResponseBytes = release.sizeBytes - initialBytes
        if (body.contentLength() >= 0 && body.contentLength() != expectedResponseBytes) {
            throw UpdateFailure(UpdateFailureKind.Verification, "APK size mismatch")
        }
        val attemptStartedAt = nowMs()
        var total = initialBytes
        emitProgress(total, release.sizeBytes, initialBytes, attemptStartedAt, onProgress)
        val output = try {
            FileOutputStream(temporary, initialBytes > 0)
        } catch (error: IOException) {
            throw UpdateFailure(UpdateFailureKind.Storage, "Update storage unavailable", cause = error)
        }
        body.byteStream().use { input ->
            output.use {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > release.sizeBytes) {
                        throw UpdateFailure(UpdateFailureKind.Verification, "APK size mismatch")
                    }
                    try {
                        output.write(buffer, 0, read)
                    } catch (error: IOException) {
                        throw UpdateFailure(UpdateFailureKind.Storage, "Not enough storage", cause = error)
                    }
                    emitProgress(total, release.sizeBytes, initialBytes, attemptStartedAt, onProgress)
                }
            }
        }
        if (total != release.sizeBytes) {
            throw UpdateFailure(UpdateFailureKind.Network, "Incomplete APK response", true)
        }
    }

    private fun resumeMode(response: Response, existingBytes: Long, totalBytes: Long): Boolean {
        if (existingBytes == 0L) return false
        if (response.code == 200) return false
        if (response.code != 206) {
            throw UpdateFailure(UpdateFailureKind.Http, "HTTP ${response.code}", retryableHttp(response.code))
        }
        val match = CONTENT_RANGE.matchEntire(response.header("Content-Range").orEmpty())
            ?: throw UpdateFailure(UpdateFailureKind.Verification, "Invalid Content-Range")
        if (match.groupValues[1].toLong() != existingBytes || match.groupValues[3].toLong() != totalBytes) {
            throw UpdateFailure(UpdateFailureKind.Verification, "Invalid Content-Range")
        }
        return true
    }

    private fun verifyAndFinalize(
        temporary: File,
        target: File,
        release: AppReleaseInfo,
    ): DownloadedUpdate {
        val hash = try {
            val digest = MessageDigest.getInstance("SHA-256")
            temporary.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (error: IOException) {
            throw UpdateFailure(UpdateFailureKind.Storage, "Update storage unavailable", cause = error)
        }
        if (temporary.length() != release.sizeBytes || hash != release.sha256) {
            temporary.delete()
            throw UpdateFailure(UpdateFailureKind.Verification, "APK verification failed")
        }
        if (target.exists() && !target.delete()) {
            throw UpdateFailure(UpdateFailureKind.Storage, "Unable to replace downloaded update")
        }
        if (!temporary.renameTo(target)) {
            throw UpdateFailure(UpdateFailureKind.Storage, "Unable to finalize APK")
        }
        return DownloadedUpdate(release, target)
    }

    private fun ensureDirectory(directory: File) {
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw UpdateFailure(UpdateFailureKind.Storage, "Update storage unavailable")
        }
    }

    private fun ensureStorage(directory: File, remainingBytes: Long) {
        val available = directory.usableSpace
        if (remainingBytes > 0 && available > 0 && available < remainingBytes) {
            throw UpdateFailure(UpdateFailureKind.Storage, "Not enough storage")
        }
    }

    private fun emitProgress(
        downloaded: Long,
        total: Long,
        initialBytes: Long,
        startedAt: Long,
        callback: (UpdateDownloadProgress) -> Unit,
    ) {
        val attemptBytes = downloaded - initialBytes
        val elapsedMs = (nowMs() - startedAt).coerceAtLeast(1)
        val eta = if (attemptBytes <= 0 || downloaded >= total) null else {
            ((total - downloaded) * elapsedMs / attemptBytes / 1000).coerceAtLeast(1)
        }
        callback(UpdateDownloadProgress(downloaded, total, eta))
    }

    private fun request(address: String, path: String): Request = try {
        Request.Builder().url(serverHttpUrl(address, path)).get().build()
    } catch (error: IllegalArgumentException) {
        throw UpdateFailure(UpdateFailureKind.Metadata, "Invalid server address", cause = error)
    }

    private fun <T> execute(request: Request, deadlineMs: Long, block: (Response) -> T): T {
        val call = client.newCall(request)
        call.timeout().timeout((deadlineMs - nowMs()).coerceAtLeast(1), TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            ensureSuccessful(response)
            return block(response)
        }
    }

    private fun ensureSuccessful(response: Response) {
        if (!response.isSuccessful) {
            throw UpdateFailure(
                UpdateFailureKind.Http,
                "HTTP ${response.code}",
                retryableHttp(response.code),
            )
        }
    }

    private fun validatedRelease(json: String): AppReleaseInfo {
        val release = Json.decodeFromString<AppReleaseInfo>(json)
        if (
            release.versionCode < 1 ||
            !VERSION_NAME.matches(release.versionName) ||
            !SHA256.matches(release.sha256) ||
            release.sizeBytes !in 1..MAX_APK_SIZE_BYTES
        ) {
            throw UpdateFailure(UpdateFailureKind.Metadata, "Invalid update metadata")
        }
        return release.copy(sha256 = release.sha256.lowercase(Locale.ROOT))
    }

    private fun <T> withRetries(
        operation: String,
        onRetry: (retry: Int) -> Unit = {},
        block: (attempt: Int, deadlineMs: Long) -> T,
    ): T {
        val deadlineMs = nowMs() + TimeUnit.MINUTES.toMillis(OPERATION_TIMEOUT_MINUTES)
        for (attempt in 0..RETRY_DELAYS_MS.size) {
            try {
                return block(attempt, deadlineMs)
            } catch (error: IOException) {
                val failure = if (error is UpdateFailure) error else {
                    UpdateFailure(UpdateFailureKind.Network, "Network error", true, error)
                }
                record("$operation attempt=${attempt + 1} ${failure.kind}: ${failure.message}")
                if (!failure.retryable || attempt == RETRY_DELAYS_MS.size) throw failure
                val delayMs = RETRY_DELAYS_MS[attempt]
                if (nowMs() + delayMs >= deadlineMs) {
                    throw UpdateFailure(UpdateFailureKind.Network, "Update operation timed out", cause = failure)
                }
                onRetry(attempt + 1)
                sleep(delayMs)
            }
        }
        throw UpdateFailure(UpdateFailureKind.Network, "Update operation failed")
    }

    private fun retryableHttp(code: Int): Boolean = code == 408 || code == 429 || code >= 500

    private fun <T> submit(callback: (Result<T>) -> Unit, operation: () -> T) {
        try {
            client.dispatcher.executorService.execute { callback(runCatching(operation)) }
        } catch (error: RuntimeException) {
            callback(Result.failure(UpdateFailure(UpdateFailureKind.Network, "Update worker unavailable", cause = error)))
        }
    }

    private fun record(message: String) = synchronized(diagnostics) {
        diagnostics.addLast("update.${nowMs()} $message".take(240))
        while (diagnostics.size > MAX_DIAGNOSTIC_LINES) diagnostics.removeFirst()
    }

    private companion object {
        const val READ_TIMEOUT_SECONDS = 60L
        const val OPERATION_TIMEOUT_MINUTES = 10L
        const val MAX_APK_SIZE_BYTES = 200L * 1024 * 1024
        const val MAX_DIAGNOSTIC_LINES = 20
        val RETRY_DELAYS_MS = longArrayOf(1_000, 3_000, 7_000)
        val VERSION_NAME = Regex("[0-9A-Za-z._-]{1,40}")
        val SHA256 = Regex("[0-9a-fA-F]{64}")
        val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    }
}
