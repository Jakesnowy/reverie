package io.github.xororz.localdream.remote

import io.github.xororz.localdream.utils.Http
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Controller-side client for a host device's control API.
 *
 * Only covers the small JSON control endpoints; generation, tokenize and
 * health-check traffic goes straight to the host's native backend port via
 * [generationHost] using the same code paths as local generation.
 */
class RemoteApiClient(
    val host: String,
    val port: Int = RemoteProtocol.CONTROL_PORT,
    // Host-mode pairing token; sent with every request when configured.
    val token: String? = null,
) {
    /** The host rejected the pairing token (HTTP 401). */
    class UnauthorizedException :
        Exception("Host rejected the pairing token (401)")
    private val baseUrl = "http://$host:$port"

    /** host:port of the native backend on the host device. */
    val generationHost: String = "$host:${RemoteProtocol.GENERATION_PORT}"

    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchInfo(): RemoteHostInfo? = withContext(Dispatchers.IO) {
        runCatching {
            get(RemoteProtocol.PATH_INFO)?.let { RemoteHostInfo.fromJson(it) }
        }.getOrElse { exception ->
            // A 401 must not be swallowed into null (which the caller maps to
            // "unreachable"): surface it so the connect flow can say the
            // pairing code was rejected.
            if (exception is UnauthorizedException) throw exception
            null
        }
    }

    suspend fun fetchCatalog(): RemoteCatalog? = withContext(Dispatchers.IO) {
        runCatching {
            get(RemoteProtocol.PATH_MODELS)?.let { RemoteCatalog.fromJson(it) }
        }.getOrElse { exception ->
            if (exception is UnauthorizedException) throw exception
            null
        }
    }

    suspend fun selectModel(modelId: String, width: Int, height: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("model_id", modelId)
                put("width", width)
                put("height", height)
            }
            post(RemoteProtocol.PATH_SELECT, body) != null
        }.getOrDefault(false)
    }

    suspend fun fetchStatus(): RemoteHostStatus? = withContext(Dispatchers.IO) {
        runCatching {
            get(RemoteProtocol.PATH_STATUS)?.let { RemoteHostStatus.fromJson(it) }
        }.getOrNull()
    }

    /**
     * Asks the host to stop its backend, but only if [modelId] is still the
     * host's current selection; a stop that arrives after a newer /select is
     * ignored host-side.
     */
    suspend fun stopBackend(modelId: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            post(RemoteProtocol.PATH_STOP, JSONObject().put("model_id", modelId)) != null
        }.getOrDefault(false)
    }

    /** Direct health probe against the native backend on the host. */
    suspend fun checkGenerationHealth(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("http://$generationHost/health")
                .auth()
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    // Attaches the pairing token when one was configured.
    private fun Request.Builder.auth(): Request.Builder = apply {
        token?.let { header(RemoteProtocol.HEADER_AUTH, RemoteProtocol.bearer(it)) }
    }

    private fun get(path: String): JSONObject? {
        val request = Request.Builder()
            .url(baseUrl + path)
            .auth()
            .get()
            .build()
        return execute(request)
    }

    private fun post(path: String, body: JSONObject): JSONObject? {
        val request = Request.Builder()
            .url(baseUrl + path)
            .auth()
            .post(body.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        return execute(request)
    }

    private fun execute(request: Request): JSONObject? = client.newCall(request).execute().use { response ->
        // Distinct from "unreachable" so the connect flow can point at the
        // pairing code instead of the address.
        if (response.code == 401) throw UnauthorizedException()
        if (!response.isSuccessful) return null
        val payload = response.body?.string() ?: return null
        runCatching { JSONObject(payload) }.getOrNull()
    }

    companion object {
        private const val CONNECT_TIMEOUT_S = 4L
        private const val READ_TIMEOUT_S = 15L
    }
}
