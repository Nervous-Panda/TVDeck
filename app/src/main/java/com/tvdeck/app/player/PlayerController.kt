package com.tvdeck.app.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import com.tvdeck.app.data.Http
import com.tvdeck.app.data.Store
import com.tvdeck.app.model.Channel

data class PlayItem(
    val key: String,
    val title: String,
    val url: String,
    val live: Boolean,
    val channel: Channel? = null,
    val subtitle: String = "",
    val resumeKey: String? = null,
    /** Quality versions to try in order (combined HD/FHD channels). Empty means just [url]. */
    val variants: List<Variant> = emptyList(),
) {
    val sources: List<Variant> get() = variants.ifEmpty { listOf(Variant("", url)) }
}

/** One version of a stream: its quality label ("FHD", "HD", or empty) and URL. */
data class Variant(val label: String, val url: String)

/** Hand-off between the browsing screens and the player activity. */
object PlaybackSession {
    var items: List<PlayItem> = emptyList()
    var index: Int = 0
    var userAgent: String = ""
    var startFromBeginning: Boolean = false
}

@OptIn(UnstableApi::class)
class PlayerController(activity: Context) {
    val items: List<PlayItem> = PlaybackSession.items
    var index by mutableIntStateOf(PlaybackSession.index.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        private set
    val current: PlayItem get() = items[index]

    var error by mutableStateOf<String?>(null)
    /** Technical detail about the last failure, shown only in troubleshooting mode. */
    var diagnostic by mutableStateOf<String?>(null)
        private set
    var buffering by mutableStateOf(true)
    var infoVisible by mutableStateOf(true)
    var infoToken by mutableIntStateOf(0)
    var channelListOpen by mutableStateOf(false)
    var numberEntry by mutableStateOf("")
    var finished by mutableStateOf(false)
    var playerView: PlayerView? = null

    /** Which of the current item's quality versions is playing. */
    var variant by mutableIntStateOf(0)
        private set
    /** Short message shown briefly at the bottom right, e.g. after switching to another version. */
    var notice by mutableStateOf<String?>(null)
        private set
    var noticeToken by mutableIntStateOf(0)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var forceHls = false
    private var retries = 0
    /** The current version has played at least once (a later failure is a drop, not a dead stream). */
    private var reachedReady = false
    /** Waiting for the first data after opening or reconnecting; the watchdog only fires while this is true. */
    private var awaitingData = false
    /** Versions of the current channel already tried in this attempt, and why each failed. */
    private val tried = HashSet<Int>()
    private val failures = ArrayList<String>()
    private var limitRounds = 0
    private var pendingRetry: Runnable? = null
    /** If a version with a backup sends nothing at all, give up on it after this long. */
    private val watchdog = Runnable {
        if (awaitingData && error == null && current.sources.size > 1) {
            fail(
                reason = "no response after ${WATCHDOG_MS / 1000}s",
                detail = "No data received within ${WATCHDOG_MS / 1000}s (watchdog)",
                friendly = "The stream didn't respond.",
                limit = false,
            )
        }
    }
    private var skipResumeOnce = PlaybackSession.startFromBeginning
    private val commitNumber = Runnable { commitNumberEntry() }

    val player: ExoPlayer

    init {
        val dataSource = OkHttpDataSource.Factory(Http.client)
            .setUserAgent(PlaybackSession.userAgent.ifBlank { Http.DEFAULT_UA })
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        val renderers = DefaultRenderersFactory(activity).setEnableDecoderFallback(true)
        // ExoPlayer quietly retries failed requests several times before reporting an error. For errors
        // that won't fix themselves (refused, not found, unknown host, connection refused) report at once,
        // so we can switch to the other version or apply our own retry rules without the hidden delay.
        // HLS segments mid-stream keep the default retries: a briefly missing segment is normal there.
        val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val segment = info.mediaLoadData.dataType == C.DATA_TYPE_MEDIA && streamIsHls
                if (!segment && isPermanent(info.exception)) return C.TIME_UNSET
                return super.getRetryDelayMsFor(info)
            }
        }
        player = ExoPlayer.Builder(activity, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource, extractors).setLoadErrorHandlingPolicy(loadErrorPolicy))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                when (state) {
                    Player.STATE_READY -> {
                        retries = 0
                        diagnostic = null
                        reachedReady = true
                        awaitingData = false
                        handler.removeCallbacks(watchdog)
                    }
                    Player.STATE_ENDED -> onEnded()
                }
            }

            override fun onPlayerError(e: PlaybackException) = onError(e)
        })
    }

    fun start() {
        if (items.isEmpty()) {
            finished = true
            return
        }
        play(index)
    }

    fun play(i: Int) {
        if (i !in items.indices) return
        saveResume()
        handler.removeCallbacksAndMessages(null)
        pendingRetry = null
        index = i
        retries = 0
        tried.clear()
        failures.clear()
        limitRounds = 0
        notice = null
        startVariant(0, hls = false)
        val item = items[i]
        val skipResume = skipResumeOnce
        skipResumeOnce = false
        if (!item.live && item.resumeKey != null && !skipResume) {
            val pos = Store.resume(item.resumeKey)
            if (pos > 0) player.seekTo(pos)
        }
        if (item.live) item.channel?.let { Store.addRecent(it.key) }
        showInfo()
    }

    /** Opens one version of the current item. setMediaItem drops the previous connection first,
     *  which matters for providers that allow a single stream at a time. */
    private fun startVariant(v: Int, hls: Boolean) {
        cancelRetry()
        handler.removeCallbacks(watchdog)
        variant = v
        tried += v
        error = null
        forceHls = hls
        buffering = true
        reachedReady = false
        awaitingData = true
        val url = current.sources[v].url
        val builder = MediaItem.Builder().setUri(url)
        streamIsHls = hls || looksHls(url)
        if (streamIsHls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(builder.build())
        player.prepare()
        player.playWhenReady = true
        if (current.sources.size > 1) handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    private val currentUrl: String get() = current.sources[variant].url

    /** Read from the loader thread by the error policy. */
    @Volatile private var streamIsHls = false

    private fun isPermanent(e: Throwable): Boolean {
        var c: Throwable? = e
        repeat(10) {
            when (c) {
                is HttpDataSource.InvalidResponseCodeException -> return true
                is java.net.UnknownHostException, is java.net.ConnectException -> return true
                null -> return false
            }
            c = c?.cause
        }
        return false
    }

    /** Label of the version playing, for the info bar ("FHD"), or empty for single-version streams. */
    val variantLabel: String get() = if (current.sources.size > 1) label(variant) else ""

    private fun label(v: Int) = current.sources[v].label.ifEmpty { "Version ${v + 1}" }

    fun retry() {
        play(index)
    }

    fun zap(delta: Int) {
        if (items.size <= 1) return
        play(((index + delta) % items.size + items.size) % items.size)
    }

    fun openChannelList() {
        channelListOpen = true
    }

    fun toggleInfo() {
        if (infoVisible) infoVisible = false else showInfo()
    }

    fun showInfo() {
        infoVisible = true
        infoToken++
    }

    private fun onEnded() {
        val item = current
        if (item.live) {
            // Live streams that "end" have usually just dropped; reconnect.
            retryLater()
            diagnostic = "Server ended the live stream (STATE_ENDED) · reconnecting, attempt $retries\n${redact(currentUrl)}"
            return
        }
        item.resumeKey?.let { Store.clearResume(it) }
        if (index < items.lastIndex) play(index + 1) else finished = true
    }

    private fun onError(e: PlaybackException) {
        val unsupportedContainer = e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        val detail = technical(e)
        val url = currentUrl
        if (!forceHls && !looksHls(url) && unsupportedContainer) {
            // URLs without an extension are often HLS; try that before giving up on this version.
            startVariant(variant, hls = true)
            diagnostic = "$detail · retrying as HLS\n${where()}"
            return
        }
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition()
            player.prepare()
            diagnostic = "$detail · jumping back to live edge\n${where()}"
            return
        }
        val status = httpStatus(e)
        fail(shortReason(e, status), detail, describe(e), limit = status == 403 || status == 429)
    }

    private fun where() = "${redact(currentUrl)}${if (forceHls) "  [forced HLS]" else ""}"

    /**
     * A version failed. Single-version streams keep the old behaviour (live: reconnect up to 3 times).
     * With backups: a version that never played is abandoned at once for the next one; one that was
     * playing gets a single reconnect first, since short drops are common.
     */
    private fun fail(reason: String, detail: String, friendly: String, limit: Boolean) {
        handler.removeCallbacks(watchdog)
        val item = current
        val sources = item.sources
        if (sources.size <= 1) {
            if (item.live && retries < 3) {
                retryLater()
                diagnostic = "$detail · reconnecting, attempt $retries of 3\n${where()}"
                return
            }
            diagnostic = "$detail\n${where()}"
            error = friendly
            return
        }
        if (reachedReady && retries < 1) {
            retryLater()
            diagnostic = "$detail · stream dropped, reconnecting once\n${where()}"
            return
        }
        if (reachedReady) {
            // It was playing and dropped again after a reconnect: start a fresh round of the other versions.
            tried.clear()
            tried += variant
            failures.clear()
            limitRounds = 0
        }
        failures += "${label(variant)}: $reason"
        val next = (1 until sources.size).map { (variant + it) % sources.size }.firstOrNull { it !in tried }
        if (next != null) {
            val from = label(variant)
            diagnostic = "$detail · switching from $from to ${label(next)}\n${where()}"
            retries = 0
            startVariant(next, hls = false)
            showNotice("$from: $reason — switched to ${label(next)}")
            return
        }
        // Every version failed. When they were all refused (403/429) it's most likely the account's
        // connection limit, with the previous channel still counted for a moment: wait and try once more.
        if (limit && limitRounds < 1) {
            limitRounds++
            tried.clear()
            retries = 0
            diagnostic = "$detail · all versions refused, likely the connection limit · retrying in 2s\n${where()}"
            showNotice("Connection limit reached — retrying…")
            schedule(2000) { startVariant(0, hls = false) }
            return
        }
        diagnostic = "$detail\n${where()}"
        error = friendly + "\n\nTried: " + failures.distinct().joinToString(" · ")
    }

    private fun showNotice(text: String) {
        notice = text
        noticeToken++
    }

    /** Short reason for the bottom-right notice and the "Tried:" list. */
    private fun shortReason(e: PlaybackException, status: Int?): String = when {
        status == 404 || status == 410 -> "stream not found ($status)"
        status == 401 -> "login refused (401)"
        status == 403 -> "refused by server (403)"
        status == 429 -> "too many connections (429)"
        status != null && status in 500..599 -> "server error ($status)"
        status != null -> "HTTP $status"
        else -> when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "couldn't connect"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "connection timed out"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "stream not found"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "unsupported format"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "codec not supported"
            else -> e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
        }
    }

    /** Error code, HTTP status (if any) and the root cause — the wrapper exceptions in between only repeat it. */
    private fun technical(e: PlaybackException): String {
        val parts = mutableListOf("${e.errorCodeName} (${e.errorCode})")
        var c: Throwable? = e.cause
        var root: Throwable? = null
        var depth = 0
        while (c != null && depth < 10) {
            when (c) {
                is HttpDataSource.InvalidResponseCodeException ->
                    parts += "HTTP ${c.responseCode}${c.responseMessage?.let { " $it" }.orEmpty()}"
                is HttpDataSource.InvalidContentTypeException -> parts += "Unexpected content type: ${c.contentType}"
            }
            root = c
            if (c.cause === c) break
            c = c.cause
            depth++
        }
        if (root != null && root !is HttpDataSource.InvalidResponseCodeException) {
            parts += root.javaClass.simpleName + (root.message?.takeIf { it.isNotBlank() }?.let { ": ${it.take(160)}" } ?: "")
        }
        return parts.joinToString(" · ")
    }

    private fun httpStatus(e: PlaybackException): Int? {
        var c: Throwable? = e.cause
        repeat(10) {
            if (c is HttpDataSource.InvalidResponseCodeException) return (c as HttpDataSource.InvalidResponseCodeException).responseCode
            c = c?.cause ?: return null
        }
        return null
    }

    /** Hides the account password (and URL credentials) so the overlay is safe to photograph. */
    private fun redact(url: String): String {
        var u = url.replace(Regex("//[^/@]+@"), "//***@")
        val pw = Store.activeProfile()?.password.orEmpty()
        if (pw.length >= 2) u = u.replace(pw, "***")
        // Xtream-style paths carry the password even in M3U playlists: /live/<user>/<pass>/<id>.ts or /<user>/<pass>/<id>
        u = u.replace(Regex("(?i)(/(?:live|movie|series|timeshift)/[^/]+/)[^/]+/"), "$1***/")
        u = u.replace(Regex("^(https?://[^/]+/[^/]+/)[^/]+(/\\d+(?:\\.\\w+)?(?:\\?.*)?)$"), "$1***$2")
        return u.replace(Regex("(?i)(password=)[^&]*"), "$1***")
    }

    private fun retryLater() {
        retries++
        schedule(2000L * retries) {
            if (error == null) {
                player.prepare()
                player.playWhenReady = true
                if (current.sources.size > 1) {
                    awaitingData = true
                    handler.removeCallbacks(watchdog)
                    handler.postDelayed(watchdog, WATCHDOG_MS)
                }
            }
        }
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        cancelRetry()
        val r = Runnable {
            pendingRetry = null
            block()
        }
        pendingRetry = r
        handler.postDelayed(r, delayMs)
    }

    private fun cancelRetry() {
        pendingRetry?.let { handler.removeCallbacks(it) }
        pendingRetry = null
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when (httpStatus(e)) {
            404, 410 -> "This stream doesn't exist on the server any more."
            in 500..599 -> "The provider's server is having problems. Try again later."
            else -> "The server refused the stream. Your subscription may have expired or reached its connection limit."
        }
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Couldn't connect to the stream server. Check your internet connection."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This stream doesn't exist on the server any more."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
            "This stream's format isn't supported."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            "This device can't decode this stream (codec not supported)."
        else -> "Playback failed (${e.errorCodeName})."
    }

    private fun looksHls(url: String): Boolean {
        val u = url.lowercase().substringBefore('?')
        return u.endsWith(".m3u8") || u.endsWith(".m3u") || "/hls/" in u || "type=m3u8" in url.lowercase()
    }

    private fun saveResume() {
        val item = items.getOrNull(index) ?: return
        val key = item.resumeKey ?: return
        if (item.live) return
        val duration = player.duration
        if (duration > 0) Store.saveResume(key, player.currentPosition, duration)
    }

    /** Remote-control handling. Returns true when the key was consumed. */
    fun handleKey(event: KeyEvent): Boolean {
        if (error != null || channelListOpen || items.isEmpty()) return false
        val code = event.keyCode
        if (current.live) {
            if (code !in liveKeys) return false
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> zap(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> zap(1)
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> zap(1)
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> zap(-1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> when {
                    numberEntry.isNotEmpty() -> commitNumberEntry()
                    infoVisible -> openChannelList()
                    else -> showInfo()
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU -> openChannelList()
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> showInfo()
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> digit(code - KeyEvent.KEYCODE_0)
            }
            return true
        }
        val pv = playerView ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && !pv.isControllerFullyVisible) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                    pv.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    player.seekTo(player.currentPosition + 30_000)
                    pv.showController()
                    return true
                }
            }
        }
        return pv.dispatchKeyEvent(event)
    }

    private fun digit(d: Int) {
        if (numberEntry.length >= 4) numberEntry = ""
        numberEntry += d.toString()
        handler.removeCallbacks(commitNumber)
        handler.postDelayed(commitNumber, 1500)
    }

    private fun commitNumberEntry() {
        handler.removeCallbacks(commitNumber)
        val n = numberEntry.toIntOrNull()
        numberEntry = ""
        if (n == null) return
        val i = items.indexOfFirst { it.channel?.number == n }
        if (i >= 0) play(i)
    }

    fun onBackground() {
        saveResume()
        player.playWhenReady = false
        if (items.isNotEmpty() && current.live) player.stop()
    }

    fun onForeground() {
        if (items.isEmpty()) return
        if (current.live) player.prepare()
        player.playWhenReady = true
    }

    fun release() {
        saveResume()
        handler.removeCallbacksAndMessages(null)
        playerView?.player = null
        player.release()
    }

    private companion object {
        const val WATCHDOG_MS = 6_000L
        val liveKeys = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_GUIDE,
        ) + (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)
    }
}
