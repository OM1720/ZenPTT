package app.zenptt

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticUploadClientTest {
    @Test
    fun postsJsonAndReturnsReportId() {
        val requestedUrl = AtomicReference<String>()
        val requestedBody = AtomicReference<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestedUrl.set(chain.request().url.toString())
                requestedBody.set(chain.request().body?.let { body ->
                    okio.Buffer().use { buffer ->
                        body.writeTo(buffer)
                        buffer.readUtf8()
                    }
                })
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body(
                        "{\"report_id\":\"260715k7m4\"}"
                            .toResponseBody("application/json".toMediaType()),
                    )
                    .build()
            }
            .build()
        val result = AtomicReference<Result<String>>()
        val completed = CountDownLatch(1)

        OkHttpDiagnosticUploadClient(client).upload("ws://192.0.2.139:8000", report()) {
            result.set(it)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals("http://192.0.2.139:8000/diagnostics", requestedUrl.get())
        assertTrue(requestedBody.get().contains("\"schemaVersion\":1"))
        assertEquals("260715-K7M4", result.get().getOrThrow())
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun rejectsInvalidReceipt() {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(201)
                    .message("Created")
                    .body(
                        "{\"report_id\":\"260715-ILOU\"}"
                            .toResponseBody("application/json".toMediaType()),
                    )
                    .build()
            }
            .build()
        val result = AtomicReference<Result<String>>()
        val completed = CountDownLatch(1)

        OkHttpDiagnosticUploadClient(client).upload("ws://192.0.2.139:8000", report()) {
            result.set(it)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(result.get().isFailure)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun rejectsMalformedResponse() {
        assertUploadFailure { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(201)
                .message("Created")
                .body("not-json".toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    @Test
    fun reportsHttpAndNetworkFailures() {
        assertUploadFailure { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .body("retry later".toResponseBody())
                .build()
        }
        assertUploadFailure { throw IOException("offline") }
    }

    private fun assertUploadFailure(interceptor: (okhttp3.Interceptor.Chain) -> Response) {
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val result = AtomicReference<Result<String>>()
        val completed = CountDownLatch(1)

        OkHttpDiagnosticUploadClient(client).upload("ws://server.example:8000", report()) {
            result.set(it)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(result.get().isFailure)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun report() = DiagnosticReport.create(
        createdAtMs = 123,
        appVersion = "0.4.0",
        androidVersion = "16",
        networkStatus = "Connected",
        headsetStatus = "Connected",
        audioRoute = "Bluetooth headset",
        details = "channel=normal (redacted)",
    )
}
