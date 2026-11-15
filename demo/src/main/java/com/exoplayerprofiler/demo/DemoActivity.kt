package com.exoplayerprofiler.demo

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.exoplayerprofiler.ExoPlayerProfiler
import com.exoplayerprofiler.ProfilerConfig

/**
 * Plays one DASH stream with the profiler attached. The "Ad break" button marks a
 * 5 second ad break from the app side, to check ad tagging without a real ad SDK.
 */
class DemoActivity : Activity() {

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ExoPlayerProfiler.install(this, ProfilerConfig(enabled = true, captureRawLocal = true))

        val playerView = PlayerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val adButton = Button(this).apply {
            text = "Ad break 5 s"
            setOnClickListener { playAdBreak() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(playerView)
            addView(adButton)
        }
        setContentView(root)

        // Public API calls a host app would make around a playback request.
        ExoPlayerProfiler.setSessionAttribute("app", "demo")
        ExoPlayerProfiler.setSessionAttribute("screen", "dash_demo")
        ExoPlayerProfiler.markPlaybackRequest(STREAM_URL, mapOf("source" to "demo_button"))
        ExoPlayerProfiler.markPlayClick()

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView.player = exo
        ExoPlayerProfiler.attach(exo)
        exo.setMediaItem(MediaItem.fromUri(STREAM_URL))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun playAdBreak() {
        ExoPlayerProfiler.markAdBreakStart()
        handler.postDelayed({ ExoPlayerProfiler.markAdBreakEnd() }, 5_000L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ExoPlayerProfiler.detach()
        player?.release()
        player = null
        super.onDestroy()
    }

    private companion object {
        const val STREAM_URL = "https://dash.akamaized.net/akamai/bbb_30fps/bbb_with_tiled_thumbnails.mpd"
    }
}
