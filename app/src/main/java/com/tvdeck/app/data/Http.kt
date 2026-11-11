package com.tvdeck.app.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object Http {
    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    lateinit var client: OkHttpClient
        private set

    fun init(ctx: Context) {
        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** [onProgress] gets the bytes received and the total, which is -1 when the server doesn't say. */
    suspend fun download(
        url: String,
        dest: File,
        userAgent: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val req = try {
            Request.Builder().url(url).header("User-Agent", userAgent.ifBlank { DEFAULT_UA }).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid URL: $url")
        }
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Server returned HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response from server")
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        received += n
                        onProgress?.invoke(received, total)
                    }
                }
            }
            if (tmp.length() == 0L) {
                tmp.delete()
                throw IOException("The server sent an empty response")
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        }
    }

    /**
     * Returns a cached copy of [url] if it's younger than [maxAgeMs], otherwise downloads it.
     * If the download fails but an older copy exists, the older copy is used (offline friendly).
     */
    suspend fun cached(
        ctx: Context,
        url: String,
        maxAgeMs: Long,
        force: Boolean,
        userAgent: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): File {
        val f = File(File(ctx.cacheDir, "http"), sha1(url))
        val fresh = f.exists() && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < maxAgeMs
        if (!force && fresh) return f
        return try {
            download(url, f, userAgent, onProgress)
            f
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (f.exists() && f.length() > 0) {
                staleFallbacks.incrementAndGet()
                f
            } else throw e
        }
    }

    /** Counts downloads that failed and were served from an older copy instead. */
    val staleFallbacks = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Opens a file, transparently un-gzipping it if needed (e.g. guide.xml.gz).
     * [onProgress] gets the fraction of the file read so far.
     */
    fun openStream(f: File, onProgress: ((Float) -> Unit)? = null): InputStream {
        val length = f.length().coerceAtLeast(1)
        val source = object : FilterInputStream(FileInputStream(f)) {
            private var count = 0L

            override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) advance(it) }

            private fun advance(n: Int) {
                count += n
                onProgress?.invoke(count.toFloat() / length)
            }
        }
        val b = BufferedInputStream(source, 64 * 1024)
        b.mark(4)
        val m1 = b.read()
        val m2 = b.read()
        b.reset()
        return if (m1 == 0x1f && m2 == 0x8b) GZIPInputStream(b, 64 * 1024) else b
    }

    fun clearCache(ctx: Context) {
        File(ctx.cacheDir, "http").deleteRecursively()
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
