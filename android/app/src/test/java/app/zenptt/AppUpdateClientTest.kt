package app.zenptt

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppUpdateClientTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun checksLatestRelease() {
        val requestedUrl = AtomicReference<String>()
        val httpClient = client { request ->
            requestedUrl.set(request.url.toString())
            response(
                request,
                """{"version_code":14,"version_name":"0.5.0","sha256":"${"a".repeat(64)}","size_bytes":123}""",
            )
        }
        val result = AtomicReference<Result<AppReleaseInfo>>()
        val completed = CountDownLatch(1)

        OkHttpAppUpdateClient(httpClient).check("ws://192.0.2.139:8000") {
            result.set(it)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals("http://192.0.2.139:8000/app/latest", requestedUrl.get())
        assertEquals(14, result.get().getOrThrow().versionCode)
        close(httpClient)
    }

    @Test
    fun rejectsMetadataAboveMaximumApkSize() {
        val httpClient = client { request ->
            response(
                request,
                """{"version_code":14,"version_name":"0.5.0","sha256":"${"a".repeat(64)}","size_bytes":209715201}""",
            )
        }
        val result = AtomicReference<Result<AppReleaseInfo>>()
        val completed = CountDownLatch(1)

        OkHttpAppUpdateClient(httpClient, sleep = {}).check("ws://server:8000") {
            result.set(it)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(result.get().isFailure)
        close(httpClient)
    }

    @Test
    fun downloadsVersionedApkAndShowsEtaAfterNewBytesArrive() {
        val apk = ByteArray(20_000) { (it % 251).toByte() }
        val hash = sha256(apk)
        val release = AppReleaseInfo(14, "0.5.0", hash, apk.size.toLong())
        val requestedUrl = AtomicReference<String>()
        val httpClient = client { request ->
            requestedUrl.set(request.url.toString())
            response(request, apk)
        }
        val progress = mutableListOf<UpdateDownloadProgress>()
        val result = AtomicReference<Result<DownloadedUpdate>>()
        val completed = CountDownLatch(1)
        val clock = AtomicLong()

        OkHttpAppUpdateClient(httpClient, nowMs = { clock.addAndGet(1_000) }).download(
            address = "ws://192.0.2.139:8000",
            release = release,
            directory = temporaryFolder.newFolder("updates"),
            onProgress = { progress += it },
        ) {
            result.set(it)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        val downloaded = result.get().getOrThrow()
        assertEquals("http://192.0.2.139:8000/app/releases/14/download", requestedUrl.get())
        assertEquals("zenptt-update.apk", downloaded.file.name)
        assertTrue(downloaded.file.readBytes().contentEquals(apk))
        assertEquals(0, progress.first().downloadedBytes)
        assertEquals(null, progress.first().etaSeconds)
        assertTrue(progress.any { it.downloadedBytes in 1 until release.sizeBytes && it.etaSeconds != null })
        assertEquals(null, progress.last().etaSeconds)
        close(httpClient)
    }

    @Test
    fun resumesPartialDownloadWithRangeAndRestartsEtaMeasurement() {
        val apk = ByteArray(24_000) { (it % 251).toByte() }
        val release = AppReleaseInfo(14, "0.5.0", sha256(apk), apk.size.toLong())
        val directory = temporaryFolder.newFolder("resume")
        val initialBytes = 4_000
        directory.resolve(".zenptt-14.tmp").writeBytes(apk.copyOfRange(0, initialBytes))
        val range = AtomicReference<String>()
        val httpClient = client { request ->
            range.set(request.header("Range"))
            response(
                request = request,
                body = apk.copyOfRange(initialBytes, apk.size),
                code = 206,
                headers = mapOf("Content-Range" to "bytes $initialBytes-${apk.lastIndex}/${apk.size}"),
            )
        }
        val progress = mutableListOf<UpdateDownloadProgress>()
        val result = AtomicReference<Result<DownloadedUpdate>>()
        val completed = CountDownLatch(1)
        val clock = AtomicLong()

        OkHttpAppUpdateClient(httpClient, nowMs = { clock.addAndGet(1_000) }).download(
            address = "ws://server:8000",
            release = release,
            directory = directory,
            onProgress = { progress += it },
        ) {
            result.set(it)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals("bytes=$initialBytes-", range.get())
        assertTrue(result.get().getOrThrow().file.readBytes().contentEquals(apk))
        assertEquals(initialBytes.toLong(), progress.first().downloadedBytes)
        assertEquals(null, progress.first().etaSeconds)
        assertTrue(progress.any { it.downloadedBytes > initialBytes && it.downloadedBytes < apk.size && it.etaSeconds != null })
        close(httpClient)
    }

    @Test
    fun restartsFromZeroWhenServerIgnoresRange() {
        val apk = ByteArray(20_000) { (it % 251).toByte() }
        val release = AppReleaseInfo(14, "0.5.0", sha256(apk), apk.size.toLong())
        val directory = temporaryFolder.newFolder("range-ignored")
        directory.resolve(".zenptt-14.tmp").writeBytes(apk.copyOfRange(0, 4_000))
        val range = AtomicReference<String>()
        val httpClient = client { request ->
            range.set(request.header("Range"))
            response(request, apk)
        }
        val progress = mutableListOf<UpdateDownloadProgress>()
        val result = AtomicReference<Result<DownloadedUpdate>>()
        val completed = CountDownLatch(1)

        OkHttpAppUpdateClient(httpClient).download(
            address = "ws://server:8000",
            release = release,
            directory = directory,
            onProgress = { progress += it },
        ) {
            result.set(it)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals("bytes=4000-", range.get())
        assertEquals(0, progress.first().downloadedBytes)
        assertEquals(null, progress.first().etaSeconds)
        assertTrue(result.get().getOrThrow().file.readBytes().contentEquals(apk))
        close(httpClient)
    }

    @Test
    fun retryAfterPartialResponseKeepsPercentAndRestartsEta() {
        val apk = ByteArray(30_000) { (it % 251).toByte() }
        val release = AppReleaseInfo(14, "0.5.0", sha256(apk), apk.size.toLong())
        val attempts = AtomicInteger()
        val resumedRange = AtomicReference<String>()
        val httpClient = client { request ->
            if (attempts.incrementAndGet() == 1) {
                response(request, failingBody(apk, 9_000))
            } else {
                resumedRange.set(request.header("Range"))
                response(
                    request = request,
                    body = apk.copyOfRange(9_000, apk.size),
                    code = 206,
                    headers = mapOf("Content-Range" to "bytes 9000-${apk.lastIndex}/${apk.size}"),
                )
            }
        }
        val progress = mutableListOf<UpdateDownloadProgress>()
        val result = AtomicReference<Result<DownloadedUpdate>>()
        val completed = CountDownLatch(1)
        val clock = AtomicLong()

        OkHttpAppUpdateClient(
            httpClient,
            sleep = {},
            nowMs = { clock.addAndGet(1_000) },
        ).download(
            address = "ws://server:8000",
            release = release,
            directory = temporaryFolder.newFolder("partial-retry"),
            onProgress = { progress += it },
        ) {
            result.set(it)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(result.get().isSuccess)
        assertEquals("bytes=9000-", resumedRange.get())
        val retryIndex = progress.indexOfFirst { it.retryNumber == 1 }
        assertTrue(retryIndex >= 0)
        assertEquals(9_000L, progress[retryIndex].downloadedBytes)
        assertEquals(null, progress[retryIndex].etaSeconds)
        assertEquals(9_000L, progress[retryIndex + 1].downloadedBytes)
        assertEquals(null, progress[retryIndex + 1].etaSeconds)
        assertTrue(progress.drop(retryIndex + 2).any { it.downloadedBytes < apk.size && it.etaSeconds != null })
        close(httpClient)
    }
    @Test
    fun retriesTemporaryNetworkFailure() {
        val apk = "verified-apk".encodeToByteArray()
        val release = AppReleaseInfo(14, "0.5.0", sha256(apk), apk.size.toLong())
        val attempts = AtomicInteger()
        val httpClient = client { request ->
            if (attempts.incrementAndGet() == 1) throw IOException("connection lost")
            response(request, apk)
        }
        val progress = mutableListOf<UpdateDownloadProgress>()
        val result = AtomicReference<Result<DownloadedUpdate>>()
        val completed = CountDownLatch(1)

        OkHttpAppUpdateClient(httpClient, sleep = {}).download(
            address = "ws://server:8000",
            release = release,
            directory = temporaryFolder.newFolder("retry"),
            onProgress = { progress += it },
        ) {
            result.set(it)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(result.get().isSuccess)
        assertEquals(2, attempts.get())
        assertTrue(progress.any { it.retryNumber == 1 })
        close(httpClient)
    }

    @Test
    fun rejectsResponseLargerThanDeclaredSize() {
        val expected = byteArrayOf(1, 2, 3)
        val release = AppReleaseInfo(14, "0.5.0", sha256(expected), expected.size.toLong())
        val attempts = AtomicInteger()
        val directory = temporaryFolder.newFolder("oversized-response")
        val httpClient = client { request ->
            attempts.incrementAndGet()
            response(request, byteArrayOf(1, 2, 3, 4))
        }
        val result = AtomicReference<Result<DownloadedUpdate>>()
        val completed = CountDownLatch(1)

        OkHttpAppUpdateClient(httpClient, sleep = {}).download(
            "ws://server:8000",
            release,
            directory,
        ) {
            result.set(it)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(result.get().isFailure)
        assertEquals(1, attempts.get())
        assertFalse(directory.resolve(".zenptt-14.tmp").exists())
        close(httpClient)
    }

    @Test
    fun rejectsInvalidContentRangeWithoutCorruptingPartialDownload() {
        val apk = "verified-apk".encodeToByteArray()
        val release = AppReleaseInfo(14, "0.5.0", sha256(apk), apk.size.toLong())
        val directory = temporaryFolder.newFolder("invalid-content-range")
        val initial = apk.copyOfRange(0, 3)
        val temporary = directory.resolve(".zenptt-14.tmp")
        temporary.writeBytes(initial)
        val attempts = AtomicInteger()
        val httpClient = client { request ->
            attempts.incrementAndGet()
            response(
                request,
                apk.copyOfRange(initial.size, apk.size),
                code = 206,
                headers = mapOf("Content-Range" to "bytes 2-${apk.lastIndex}/${apk.size}"),
            )
        }
        val result = AtomicReference<Result<DownloadedUpdate>>()
        val completed = CountDownLatch(1)

        OkHttpAppUpdateClient(httpClient, sleep = {}).download(
            "ws://server:8000",
            release,
            directory,
        ) {
            result.set(it)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(result.get().isFailure)
        assertEquals(1, attempts.get())
        assertTrue(temporary.readBytes().contentEquals(initial))
        close(httpClient)
    }

    @Test
    fun doesNotRetryClientHttpFailure() {
        val attempts = AtomicInteger()
        val httpClient = client { request ->
            attempts.incrementAndGet()
            response(request, "not found", code = 404)
        }
        val result = AtomicReference<Result<AppReleaseInfo>>()
        val completed = CountDownLatch(1)

        OkHttpAppUpdateClient(httpClient, sleep = {}).check("ws://server:8000") {
            result.set(it)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(result.get().isFailure)
        assertEquals(1, attempts.get())
        close(httpClient)
    }

    @Test
    fun retriesTemporaryHttpFailures() {
        listOf(408, 429, 500).forEach { status ->
            val attempts = AtomicInteger()
            val httpClient = client { request ->
                if (attempts.incrementAndGet() == 1) {
                    response(request, "temporary", code = status)
                } else {
                    response(
                        request,
                        """{"version_code":14,"version_name":"0.5.0","sha256":"${"a".repeat(64)}","size_bytes":123}""",
                    )
                }
            }
            val result = AtomicReference<Result<AppReleaseInfo>>()
            val completed = CountDownLatch(1)

            OkHttpAppUpdateClient(httpClient, sleep = {}).check("ws://server:8000") {
                result.set(it)
                completed.countDown()
            }

            assertTrue("HTTP $status did not complete", completed.await(2, TimeUnit.SECONDS))
            assertTrue("HTTP $status was not retried successfully", result.get().isSuccess)
            assertEquals(2, attempts.get())
            close(httpClient)
        }
    }

    @Test
    fun rejectsInvalidMetadataAndDoesNotRetryWrongHash() {
        val invalidClient = client { request ->
            response(
                request,
                """{"version_code":14,"version_name":"../bad","sha256":"${"a".repeat(64)}","size_bytes":3}""",
            )
        }
        val invalidResult = AtomicReference<Result<AppReleaseInfo>>()
        val invalidCompleted = CountDownLatch(1)
        OkHttpAppUpdateClient(invalidClient, sleep = {}).check("ws://server:8000") {
            invalidResult.set(it)
            invalidCompleted.countDown()
        }
        assertTrue(invalidCompleted.await(2, TimeUnit.SECONDS))
        assertTrue(invalidResult.get().isFailure)
        close(invalidClient)

        val apk = "wrong".encodeToByteArray()
        val release = AppReleaseInfo(14, "0.5.0", "0".repeat(64), apk.size.toLong())
        val attempts = AtomicInteger()
        val downloadClient = client { request ->
            attempts.incrementAndGet()
            response(request, apk)
        }
        val downloadResult = AtomicReference<Result<DownloadedUpdate>>()
        val downloadCompleted = CountDownLatch(1)
        val directory = temporaryFolder.newFolder("unverified")
        OkHttpAppUpdateClient(downloadClient, sleep = {}).download("ws://server:8000", release, directory) {
            downloadResult.set(it)
            downloadCompleted.countDown()
        }
        assertTrue(downloadCompleted.await(2, TimeUnit.SECONDS))
        assertTrue(downloadResult.get().isFailure)
        assertEquals(1, attempts.get())
        assertFalse(directory.resolve("zenptt-update.apk").exists())
        assertFalse(directory.resolve(".zenptt-14.tmp").exists())
        close(downloadClient)
    }

    private fun failingBody(bytes: ByteArray, failAfter: Int): ResponseBody = object : ResponseBody() {
        override fun contentType(): MediaType = "application/vnd.android.package-archive".toMediaType()
        override fun contentLength(): Long = bytes.size.toLong()
        override fun source() = object : InputStream() {
            private var position = 0

            override fun read(): Int {
                val single = ByteArray(1)
                return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= failAfter) throw IOException("connection lost")
                val count = minOf(length, failAfter - position)
                bytes.copyInto(buffer, offset, position, position + count)
                position += count
                return count
            }
        }.source().buffer()
    }
    private fun client(responder: (okhttp3.Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain -> responder(chain.request()) }.build()

    private fun response(
        request: okhttp3.Request,
        body: String,
        code: Int = 200,
    ): Response = response(
        request,
        body.toResponseBody("application/json".toMediaType()),
        code,
    )

    private fun response(
        request: okhttp3.Request,
        body: ByteArray,
        code: Int = 200,
        headers: Map<String, String> = emptyMap(),
    ): Response = response(
        request,
        body.toResponseBody("application/vnd.android.package-archive".toMediaType()),
        code,
        headers,
    )

    private fun response(
        request: okhttp3.Request,
        body: okhttp3.ResponseBody,
        code: Int = 200,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Partial Content")
            .body(body)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun close(client: OkHttpClient) {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
