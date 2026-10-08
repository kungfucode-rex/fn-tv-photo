package com.tvphoto.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.io.InputStream
import okhttp3.Call
import okhttp3.Request
import okhttp3.Response

/**
 * The media3 data source the video player reads through.
 *
 * Media3's own [androidx.media3.datasource.DefaultHttpDataSource] opens a bare
 * `HttpURLConnection`: the **system** trust store, no cookies, no header beyond what the
 * caller sets. That is three separate ways to fail against a fnOS box:
 *
 *  - the NAS certificate is self-signed (`O=fnOS CN=fnOS`, no usable `subjectAltName`),
 *    so it cannot validate against any trust store and the stream dies with "Trust
 *    anchor for certification path not found" — while every photo around it, which goes
 *    through this same client, loads fine;
 *  - a device gated by an 访问码 is authorised by a cookie, and the video path is covered
 *    by that same gateway challenge;
 *  - the stream is authenticated by the `AccessToken` header, which
 *    [MediaAuthInterceptor] adds and a bare connection would not.
 *
 * So the player is given the app's own client instead — see `AppContainer.mediaHttp` —
 * and this is the thin [DataSource] over it: a GET (or HEAD) with the byte range the
 * caller asked for, and the response body handed over as a stream.
 *
 * Written here rather than taken from `media3-datasource-okhttp` because that artifact
 * is a dependency this build has never needed for anything else; what it does for a
 * progressive file is a request and a stream, which is all this is.
 */
class OkHttpDataSource(
    private val callFactory: Call.Factory,
    private val defaultRequestProperties: Map<String, String> = emptyMap(),
) : BaseDataSource(/* isNetwork = */ true) {

    private var dataSpec: DataSpec? = null
    private var response: Response? = null
    private var inputStream: InputStream? = null
    private var uri: Uri? = null
    private var responseHeaders: Map<String, List<String>> = emptyMap()
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        bytesRemaining = 0
        transferInitializing(dataSpec)

        val response = try {
            callFactory.newCall(buildRequest(dataSpec)).execute()
        } catch (io: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                io,
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }
        this.response = response
        // The URL after any redirect, which is what media3 records for the transfer.
        uri = Uri.parse(response.request.url.toString())
        responseHeaders = response.headers.toMultimap()

        if (response.code !in 200..299) {
            val code = response.code
            val message = "HTTP $code ${response.message}"
            val headers = responseHeaders
            response.close()
            this.response = null
            throw HttpDataSource.InvalidResponseCodeException(
                code,
                message,
                /* cause= */ null,
                headers,
                dataSpec,
                /* responseBody= */ ByteArray(0),
            )
        }

        val body = response.body
        val stream = body.byteStream()
        var contentLength = body.contentLength()
        var bytesToSkip = 0L

        if (response.code == 200) {
            // The server ignored the range and is sending the file from its start, so the
            // bytes before the requested position have to be read and dropped.
            bytesToSkip = dataSpec.position
        } else {
            val range = parseContentRange(response.header("Content-Range"))
            if (range == null) {
                response.close()
                this.response = null
                throw HttpDataSource.HttpDataSourceException(
                    "Missing or unreadable Content-Range on a partial response",
                    dataSpec,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }
            bytesToSkip = range.first - dataSpec.position
            contentLength = range.second
        }
        if (bytesToSkip < 0) {
            response.close()
            this.response = null
            throw HttpDataSource.HttpDataSourceException(
                "Server answered with a byte range before the one requested",
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }

        if (bytesToSkip > 0) {
            skipFully(stream, bytesToSkip, dataSpec)
        }

        inputStream = stream
        opened = true
        transferStarted(dataSpec)

        val available =
            if (contentLength == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong()
            else contentLength - bytesToSkip
        bytesRemaining = when {
            available == C.LENGTH_UNSET.toLong() -> dataSpec.length
            dataSpec.length == C.LENGTH_UNSET.toLong() -> available
            else -> minOf(available, dataSpec.length)
        }
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val spec = dataSpec ?: return C.RESULT_END_OF_INPUT
        val stream = inputStream
            ?: throw HttpDataSource.HttpDataSourceException(
                spec,
                HttpDataSource.HttpDataSourceException.TYPE_READ,
            )

        // `length` unless the caller asked for a bounded range: an unbounded response is
        // read as far as the parser wants, one with a known end never past it.
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(bytesRemaining, length.toLong()).toInt()
        }

        val read = try {
            stream.read(buffer, offset, toRead)
        } catch (io: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(
                io,
                spec,
                HttpDataSource.HttpDataSourceException.TYPE_READ,
            )
        }
        if (read == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun close() {
        val spec = dataSpec
        try {
            response?.close()
        } catch (io: IOException) {
            if (spec != null) {
                throw HttpDataSource.HttpDataSourceException(
                    io,
                    spec,
                    HttpDataSource.HttpDataSourceException.TYPE_CLOSE,
                )
            }
        } finally {
            response = null
            inputStream = null
            uri = null
            dataSpec = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

    /**
     * The request for one range of the stream.
     *
     * The range is what makes seeking work: the NAS answers a ranged GET with 206 and the
     * bytes asked for, so jumping to the middle of a clip costs the bytes from there
     * rather than the whole file.
     */
    private fun buildRequest(dataSpec: DataSpec): Request {
        val builder = Request.Builder().url(dataSpec.uri.toString())
        if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            builder.header("Range", rangeHeader(dataSpec))
        }
        defaultRequestProperties.forEach { (name, value) -> builder.header(name, value) }
        dataSpec.httpRequestHeaders.forEach { (name, value) -> builder.header(name, value) }
        return when (dataSpec.httpMethod) {
            DataSpec.HTTP_METHOD_GET -> builder.get()
            DataSpec.HTTP_METHOD_HEAD -> builder.head()
            else -> throw HttpDataSource.HttpDataSourceException(
                "Unsupported HTTP method ${dataSpec.getHttpMethodString()}",
                dataSpec,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        }.build()
    }

    /** Reads and discards [count] bytes, so the stream starts where the caller asked. */
    private fun skipFully(stream: InputStream, count: Long, dataSpec: DataSpec) {
        val buffer = ByteArray(SKIP_BUFFER_BYTES)
        var remaining = count
        while (remaining > 0) {
            val read = try {
                stream.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            } catch (io: IOException) {
                throw HttpDataSource.HttpDataSourceException.createForIOException(
                    io,
                    dataSpec,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }
            if (read == -1) {
                throw HttpDataSource.HttpDataSourceException(
                    "Stream ended before the requested position",
                    dataSpec,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN,
                )
            }
            remaining -= read
            bytesTransferred(read)
        }
    }

    /** Hands one data source per load, which is what a media source asks for. */
    class Factory(private val callFactory: Call.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = OkHttpDataSource(callFactory)
    }

    private companion object {
        const val SKIP_BUFFER_BYTES = 8 * 1024

        /** `bytes 120-240/1000` -> `120 to 121`, the first byte and how many follow. */
        val CONTENT_RANGE = Regex("^bytes (\\d+)-(\\d+)/(\\d+|\\*)$")

        fun rangeHeader(dataSpec: DataSpec): String = buildString {
            append("bytes=").append(dataSpec.position).append('-')
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                append(dataSpec.position + dataSpec.length - 1)
            }
        }

        fun parseContentRange(header: String?): Pair<Long, Long>? {
            val match = header?.trim()?.let(CONTENT_RANGE::find) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            // A range the caller asked past the end of comes back as `bytes */total`.
            if (end < start) return null
            return start to (end - start + 1)
        }
    }
}
