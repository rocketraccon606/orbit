package app.orbit.tv.player

import android.content.Context
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import app.orbit.tv.BuildConfig
import app.orbit.tv.catalog.ChannelCard
import app.orbit.tv.catalog.ChannelDetail
import app.orbit.tv.catalog.StreamRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

enum class PlaybackPhase { Idle, Preparing, Playing, Failed }

data class PlaybackState(
    val channel: ChannelCard? = null,
    val phase: PlaybackPhase = PlaybackPhase.Idle,
    val streamIndex: Int = 0,
    val streamCount: Int = 0,
    val message: String? = null,
    val firstFrame: Boolean = false,
)

class PlaybackController(
    private val context: Context,
    private val okHttp: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var streams: List<StreamRef> = emptyList()
    private var card: ChannelCard? = null
    private var index = 0
    private var attempt = 0
    private var startedAtNanos = 0L
    private var watchdog: Job? = null
    private var stallWatchdog: Job? = null
    private var firstFrameAt = 0L

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            val token = attempt
            scope.launch {
                delay(120)
                if (token == attempt) onFirstFrame()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING) startStallWatchdog() else stallWatchdog?.cancel()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (BuildConfig.DEBUG) {
                // Full stack traces flood the log ring buffer on HTTPS failures.
                Log.w(TAG, "stream failed: ${error.errorCodeName} / ${error.cause?.message}")
            }
            fallback("Stream unavailable")
        }
    }

    fun play(detail: ChannelDetail, startedAtNanos: Long) {
        streams = detail.streams.filter {
            it.state != "dead" && it.url.startsWith("http", ignoreCase = true)
        }
        if (streams.isEmpty()) {
            card = detail.card
            _state.value = PlaybackState(
                channel = detail.card,
                phase = PlaybackPhase.Failed,
                message = "No working stream for this channel",
            )
            return
        }
        card = detail.card
        index = 0
        this.startedAtNanos = startedAtNanos
        attemptStream()
    }

    fun retry() {
        val current = streams
        if (current.isEmpty()) return
        index = 0
        attemptStream()
    }

    private fun attemptStream() {
        val stream = streams.getOrNull(index) ?: return exhausted()
        val activeCard = card ?: return
        watchdog?.cancel()
        stallWatchdog?.cancel()
        attempt++
        firstFrameAt = 0L

        val exo = ensurePlayer()
        exo.stop()
        exo.playWhenReady = true

        trackSelector?.let {
            it.setParameters(
                it.buildUponParameters()
                    .setMaxVideoSize(960, 540)
                    .setMaxVideoBitrate(1_200_000)
                    .build(),
            )
        }

        val httpFactory = OkHttpDataSource.Factory(okHttp)
            .setUserAgent(headerSafe(stream.userAgent) ?: DEFAULT_UA)
            .setDefaultRequestProperties(
                buildMap { headerSafe(stream.referrer)?.let { put("Referer", it) } },
            )

        val item = MediaItem.Builder()
            .setUri(stream.url)
            .setTag(stream.id)
            .build()
        val source = if (stream.kind == "hls" || stream.url.contains(".m3u8", true)) {
            HlsMediaSource.Factory(httpFactory).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(httpFactory).createMediaSource(item)
        }

        _state.value = PlaybackState(
            channel = activeCard,
            phase = PlaybackPhase.Preparing,
            streamIndex = index,
            streamCount = streams.size,
            message = if (index > 0) "Trying another stream (${index + 1}/${streams.size})" else null,
            firstFrame = false,
        )
        exo.setMediaSource(source)
        exo.prepare()

        val token = attempt
        watchdog = scope.launch {
            delay(START_TIMEOUT_MS)
            if (token == attempt && firstFrameAt == 0L) {
                if (BuildConfig.DEBUG) Log.w(TAG, "${activeCard.id} start timeout on stream $index")
                fallback("Stream is slow to start")
            }
        }
    }

    private fun onFirstFrame() {
        if (firstFrameAt != 0L) return
        firstFrameAt = System.nanoTime()
        watchdog?.cancel()
        stallWatchdog?.cancel()
        trackSelector?.let {
            it.setParameters(
                it.buildUponParameters()
                    .clearVideoSizeConstraints()
                    .setMaxVideoBitrate(Int.MAX_VALUE)
                    .build(),
            )
        }
        val ms = (firstFrameAt - startedAtNanos) / 1_000_000
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "OrbitPerf switch channel=${card?.id} first_frame_ms=$ms stream=$index")
        }
        _state.value = _state.value.copy(
            phase = PlaybackPhase.Playing,
            message = null,
            firstFrame = true,
        )
    }

    private fun startStallWatchdog() {
        if (firstFrameAt == 0L) return
        stallWatchdog?.cancel()
        stallWatchdog = scope.launch {
            delay(STALL_TIMEOUT_MS)
            if (player?.playbackState == Player.STATE_BUFFERING) {
                fallback("Stream stalled")
            }
        }
    }

    private fun fallback(reason: String) {
        watchdog?.cancel()
        stallWatchdog?.cancel()
        if (index + 1 < streams.size) {
            index++
            if (BuildConfig.DEBUG) Log.i(TAG, "falling back to stream $index ($reason)")
            attemptStream()
        } else {
            exhausted()
        }
    }

    private fun exhausted() {
        player?.stop()
        _state.value = _state.value.copy(
            phase = PlaybackPhase.Failed,
            message = "This channel is not available right now.",
        )
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val selector = DefaultTrackSelector(context)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_500, 8_000, 500, 1_000)
            .setTargetBufferBytes(8 * 1024 * 1024)
            .build()
        val exo = ExoPlayer.Builder(context)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .build()
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        exo.addListener(listener)
        player = exo
        trackSelector = selector
        return exo
    }

    fun attach(playerView: androidx.media3.ui.PlayerView) {
        ensurePlayer()
        playerView.player = player
    }

    fun detach() {
        player?.let { exo ->
            exo.stop()
            exo.clearMediaItems()
        }
        _state.value = PlaybackState()
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun stopAndRelease() {
        watchdog?.cancel()
        stallWatchdog?.cancel()
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        trackSelector = null
        streams = emptyList()
        card = null
        _state.value = PlaybackState()
    }

    fun shutdown() {
        stopAndRelease()
        scope.cancel()
    }

    private fun headerSafe(value: String?): String? =
        value?.replace('\r', ' ')?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val TAG = "OrbitPerf"
        const val DEFAULT_UA = "Orbit/0.1"
        const val START_TIMEOUT_MS = 9_000L
        const val STALL_TIMEOUT_MS = 12_000L
    }
}
