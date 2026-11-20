package com.example.jellywrist.data.net

import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

/**
 * Media3 data source backed by OkHttp 5.
 *
 * Why not use an existing component:
 * - `DefaultHttpDataSource` goes through `HttpURLConnection`, whose TLS trust is
 *   only configurable globally (`HttpsURLConnection.setDefaultSSLSocketFactory`). Pinning
 *   a server's self-signed certificate this way would apply it to **all** hosts in the
 *   process, which DEC-006 explicitly forbids.
 * - `media3-datasource-okhttp` is compiled against OkHttp 4, while the Jellyfin SDK
 *   requires OkHttp 5 at runtime (incompatible `Response.body` signatures) — a risky mix.
 *
 * This adapter therefore receives the [Call.Factory] **scoped to the selected server** and
 * nothing more, which keeps the opt-in certificate pinning confined to that host.
 *
 * Range-based resume (REQ-003 / DEC-003) is carried by this class: Media3 expresses
 * resume by setting [DataSpec.position], translated here to a `Range` header. A server
 * that responds `200` to a partial request does not honor Range: we fail rather than
 * deliver misaligned bytes, which would silently corrupt the cached file.
 */
class JellyDataSource(
    private val callFactory: Call.Factory,
    private val headerProvider: () -> Map<String, String>,
) : BaseDataSource(/* isNetwork = */ true) {

    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var body: ResponseBody? = null
    private var stream: InputStream? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)

        val rangeRequested = dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()
        val request = Request.Builder()
            .url(dataSpec.uri.toString())
            .apply {
                headerProvider().forEach { (name, value) -> header(name, value) }
                dataSpec.httpRequestHeaders.forEach { (name, value) -> header(name, value) }
                if (rangeRequested) header("Range", rangeHeader(dataSpec))
            }
            .build()

        val response = callFactory.newCall(request).execute()
        this.response = response

        if (!response.isSuccessful) {
            response.closeQuietly()
            throw IOException("HTTP ${response.code} for ${dataSpec.uri}")
        }
        if (rangeRequested && response.code != HTTP_PARTIAL) {
            response.closeQuietly()
            throw IOException(
                "Server ignored Range (HTTP ${response.code}): resume impossible without " +
                    "risking a corrupted file",
            )
        }

        val body = response.body
        this.body = body
        stream = body.byteStream()

        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            else -> body.contentLength().takeIf { it != -1L } ?: C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            min(bytesRemaining, length.toLong()).toInt()
        }

        val read = stream?.read(buffer, offset, toRead) ?: throw IOException("Source closed")
        if (read == -1) {
            // An announced but undelivered length is a truncation: signaling it prevents
            // an incomplete track from being marked available offline.
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                throw EOFException("Truncated response: $bytesRemaining bytes missing")
            }
            return C.RESULT_END_OF_INPUT
        }

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri() = dataSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        response?.headers?.toMultimap() ?: emptyMap()

    override fun close() {
        stream = null
        body = null
        response?.closeQuietly()
        response = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun rangeHeader(dataSpec: DataSpec): String = buildString {
        append("bytes=").append(dataSpec.position).append('-')
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            append(dataSpec.position + dataSpec.length - 1)
        }
    }

    private fun Response.closeQuietly() = runCatching { close() }

    /**
     * Injectable factory.
     *
     * [headerProvider] is evaluated on every open — essential: a download
     * enqueued before a reconnection must restart with the current token, not with
     * the one captured at construction.
     */
    class Factory(
        private val callFactory: Call.Factory,
        private val headerProvider: () -> Map<String, String>,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = JellyDataSource(callFactory, headerProvider)
    }

    private companion object {
        const val HTTP_PARTIAL = 206
    }
}
