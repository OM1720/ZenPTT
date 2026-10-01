// Uploads a diagnostic report over HTTP and returns its server-issued reference code.
package app.zenptt

import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

interface DiagnosticUploadClient {
    fun upload(address: String, report: DiagnosticReport, callback: (Result<String>) -> Unit)
}

@Serializable
private data class DiagnosticReceipt(@SerialName("report_id") val reportId: String)

class OkHttpDiagnosticUploadClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build(),
) : DiagnosticUploadClient {
    override fun upload(
        address: String,
        report: DiagnosticReport,
        callback: (Result<String>) -> Unit,
    ) {
        val request = runCatching {
            Request.Builder()
                .url(serverHttpUrl(address, "/diagnostics"))
                .post(report.asJson().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }.getOrElse {
            callback(Result.failure(IOException("Invalid server address")))
            return
        }
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                callback(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        callback(Result.failure(IOException("HTTP ${it.code}")))
                        return
                    }
                    val receipt = runCatching {
                        Json.decodeFromString<DiagnosticReceipt>(it.body?.string().orEmpty())
                    }.getOrElse { error ->
                        callback(Result.failure(IOException("Invalid server response", error)))
                        return
                    }
                    val normalized = receipt.reportId.trim().uppercase(Locale.ROOT)
                    val compact = when {
                        REPORT_CODE.matches(normalized) -> normalized.replace("-", "")
                        COMPACT_REPORT_CODE.matches(normalized) -> normalized
                        else -> {
                            callback(Result.failure(IOException("Invalid report code")))
                            return
                        }
                    }
                    callback(Result.success(compact.take(6) + "-" + compact.drop(6)))
                }
            }
        })
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val REPORT_CODE = Regex("\\d{6}-[0-9A-HJKMNP-TV-Z]{4}")
        val COMPACT_REPORT_CODE = Regex("\\d{6}[0-9A-HJKMNP-TV-Z]{4}")
    }
}