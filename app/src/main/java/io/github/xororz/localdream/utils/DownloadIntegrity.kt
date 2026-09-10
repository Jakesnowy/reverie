package io.github.xororz.localdream.utils

import java.security.MessageDigest
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Download integrity for model archives (design notes:
 * memory-bank/design_download_integrity.md).
 *
 * Built-in sources are Hugging Face `resolve` URLs; HF publishes the
 * authoritative SHA-256 and exact size of every LFS file through the
 * `X-Linked-ETag` / `X-Linked-Size` headers on the resolve response. The
 * headers ride on the 302 redirect to the CDN, so the probe runs with
 * redirect-following disabled. Non-HF sources have no authoritative hash;
 * callers fall back to a TOFU pin recorded after the first successful
 * download, which still protects resume splices and upstream drift.
 */
object DownloadIntegrity {
    /** Authoritative integrity data for an archive, when the source exposes it. */
    data class Expected(val sha256: String, val size: Long)

    /** A downloaded archive did not match its expected hash/size. */
    class IntegrityException(message: String) : Exception(message)

    /**
     * One HEAD probe (redirects disabled) to read the LFS metadata headers.
     * Returns null when the URL is not an LFS-backed file or the source is
     * unreachable — callers then use their fallback strategy.
     */
    fun probeExpected(client: OkHttpClient, url: String): Expected? = runCatching {
        val noRedirect = client.newBuilder().followRedirects(false).build()
        noRedirect.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
            val etag = response.header("X-Linked-ETag")?.trim('"', ' ').orEmpty()
            val size = response.header("X-Linked-Size")?.toLongOrNull() ?: 0L
            if (etag.isEmpty() || size <= 0) return null
            Expected(sha256 = etag.lowercase(Locale.ROOT), size = size)
        }
    }.getOrNull()

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun toHex(digest: MessageDigest): String =
        digest.digest().joinToString("") { "%02x".format(it) }
}
