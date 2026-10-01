// Converts a WebSocket endpoint to HTTP and probes the server health route.
package app.zenptt

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

interface ServerHealthClient {
    fun check(address: String, callback: (Result<Unit>) -> Unit)
}

class OkHttpServerHealthClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build(),
) : ServerHealthClient {
    override fun check(address: String, callback: (Result<Unit>) -> Unit) {
        val healthUrl = serverHttpUrl(address, "/health")

        val request = Request.Builder().url(healthUrl).get().build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                callback(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        callback(Result.success(Unit))
                    } else {
                        callback(Result.failure(IOException("HTTP ${it.code}")))
                    }
                }
            }
        })
    }
}

object NoOpServerHealthClient : ServerHealthClient {
    override fun check(address: String, callback: (Result<Unit>) -> Unit) {
        callback(Result.failure(IllegalStateException("Health check is unavailable")))
    }
}
