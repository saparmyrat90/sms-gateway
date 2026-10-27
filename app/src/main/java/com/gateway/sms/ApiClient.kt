package com.gateway.sms

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class SmsJob(val id: Long, val phone: String, val text: String)

@Serializable
private data class ClaimResponse(val items: List<SmsJob> = emptyList())

@Serializable
private data class ReportRequest(val id: Long, val ok: Boolean, val error: String? = null)

@Serializable
private data class ReportResponse(val id: Long, @SerialName("status") val status: String)

sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class BadConfig(message: String) : ApiException(message)
    class Unauthorized : ApiException("Wrong gateway key (401)")
    class GatewayDisabled : ApiException("Gateway is disabled on the server (503)")
    class Server(val code: Int) : ApiException("Server error $code")
    class BadResponse(cause: Throwable) : ApiException("Unexpected server response", cause)
    class Network(cause: Throwable) : ApiException("Network error: ${cause.message ?: cause.javaClass.simpleName}", cause)
}

interface GatewayApi {
    suspend fun claim(limit: Int): List<SmsJob>

    /** Returns the job status from the server: "sent", "pending" or "failed". */
    suspend fun report(id: Long, ok: Boolean, error: String?): String
}

/** HTTPS is mandatory; plain HTTP only for loopback, emulator and private LAN hosts (local development). */
object UrlPolicy {
    /** Returns an error message, or null when the URL is acceptable. */
    fun check(raw: String): String? {
        val url = raw.trim().toHttpUrlOrNull() ?: return "Invalid server URL"
        if (url.isHttps) return null
        return if (isLocalHost(url.host)) null else "HTTPS is required (plain HTTP only for local hosts)"
    }

    internal fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 127 || p[0] == 10 ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 172 && p[1] in 16..31)
    }
}

class OkHttpGatewayApi(
    baseUrl: String,
    private val gatewayKey: String,
    private val client: OkHttpClient = defaultClient(),
) : GatewayApi {
    private val base: HttpUrl

    init {
        UrlPolicy.check(baseUrl)?.let { throw ApiException.BadConfig(it) }
        if (gatewayKey.isBlank()) throw ApiException.BadConfig("Gateway key is empty")
        base = baseUrl.trim().toHttpUrlOrNull()!!
    }

    override suspend fun claim(limit: Int): List<SmsJob> {
        val url = base.newBuilder()
            .addPathSegments("api/v1/sms/gateway/claim")
            .addQueryParameter("limit", limit.toString())
            .build()
        val req = Request.Builder().url(url)
            .header("X-Gateway-Key", gatewayKey)
            .post(ByteArray(0).toRequestBody())
            .build()
        val body = execute(req)
        return decode<ClaimResponse>(body).items
    }

    override suspend fun report(id: Long, ok: Boolean, error: String?): String {
        val url = base.newBuilder().addPathSegments("api/v1/sms/gateway/report").build()
        val payload = json.encodeToString(ReportRequest.serializer(), ReportRequest(id, ok, error?.take(200)))
        val req = Request.Builder().url(url)
            .header("X-Gateway-Key", gatewayKey)
            .post(payload.toRequestBody(JSON))
            .build()
        return decode<ReportResponse>(execute(req)).status
    }

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text)
    } catch (e: Exception) {
        throw ApiException.BadResponse(e)
    }

    private suspend fun execute(req: Request): String {
        val response = call(client.newCall(req))
        response.use {
            when (it.code) {
                200 -> return try {
                    it.body.string()
                } catch (e: IOException) {
                    throw ApiException.Network(e)
                }
                401 -> throw ApiException.Unauthorized()
                503 -> throw ApiException.GatewayDisabled()
                else -> throw ApiException.Server(it.code)
            }
        }
    }

    private suspend fun call(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(ApiException.Network(e))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
