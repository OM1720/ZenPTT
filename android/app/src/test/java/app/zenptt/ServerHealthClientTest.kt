package app.zenptt

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ServerHealthClientTest {
    @Test
    fun checksHttpHealthEndpointDerivedFromWebSocketAddress() {
        val requestedUrl = AtomicReference<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestedUrl.set(chain.request().url.toString())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{\"status\":\"ok\"}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val result = AtomicReference<Result<Unit>>()
        val completed = CountDownLatch(1)

        OkHttpServerHealthClient(client).check("ws://192.0.2.139:8000") {
            result.set(it)
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals("http://192.0.2.139:8000/health", requestedUrl.get())
        assertTrue(result.get().isSuccess)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun reportsHttpAndNetworkFailures() {
        listOf<(okhttp3.Interceptor.Chain) -> Response>(
            { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Unavailable")
                    .body("not healthy".toResponseBody())
                    .build()
            },
            { throw IOException("offline") },
        ).forEach { interceptor ->
            val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
            val result = AtomicReference<Result<Unit>>()
            val completed = CountDownLatch(1)

            OkHttpServerHealthClient(client).check("ws://server.example:8000") {
                result.set(it)
                completed.countDown()
            }

            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertTrue(result.get().isFailure)
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
