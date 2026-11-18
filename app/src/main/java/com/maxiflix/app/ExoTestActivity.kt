package com.maxiflix.app

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Experimental player only. The website's embedded player remains untouched.
 * Accepts a direct, authorized media URL (HLS, DASH or progressive HTTP media).
 */
@UnstableApi
class ExoTestActivity : AppCompatActivity() {

    companion object {
        private const val MAX_CACHE_BYTES = 200L * 1024L * 1024L
        private const val TAG = "MAXIFLIX_EXO_TEST"
    }

    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var playerView: PlayerView
    private lateinit var cache: SimpleCache
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cache = SimpleCache(
            File(cacheDir, "exo_video_cache"),
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            StandaloneDatabaseProvider(this)
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        val heading = TextView(this).apply {
            text = "MAXi-FLIX • TESTE EXOPLAYER"
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
        }
        root.addView(heading)

        val explanation = TextView(this).apply {
            text = "Cole um link direto e autorizado de vídeo (HLS .m3u8, DASH .mpd ou arquivo MP4). O player do site continua funcionando normalmente."
            textSize = 13f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, dp(6), 0, dp(8))
        }
        root.addView(explanation)

        urlInput = EditText(this).apply {
            hint = "https://servidor/video.m3u8"
            setSingleLine(true)
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(0xFFAAAAAA.toInt())
            setSelectAllOnFocus(false)
        }
        root.addView(urlInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ))

        val startButton = Button(this).apply {
            text = "Limpar cache anterior e reproduzir"
            setOnClickListener { startPlayback() }
        }
        root.addView(startButton)

        playerView = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        root.addView(playerView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val back10 = Button(this).apply {
            text = "↶ 10 s"
            setOnClickListener {
                player?.let { p -> p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0L)) }
            }
        }
        val forward10 = Button(this).apply {
            text = "10 s ↷"
            setOnClickListener {
                player?.let { p ->
                    val target = p.currentPosition + 10_000L
                    if (p.duration > 0L) p.seekTo(target.coerceAtMost(p.duration))
                    else p.seekTo(target)
                }
            }
        }
        seekRow.addView(back10, LinearLayout.LayoutParams(0, dp(52), 1f))
        seekRow.addView(forward10, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(seekRow)

        status = TextView(this).apply {
            text = "Cache máximo: 200 MB • Cache atual: 0 MB"
            textSize = 12f
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(status)

        val closeButton = Button(this).apply {
            text = "Voltar ao MAXi-FLIX"
            setOnClickListener { finish() }
        }
        root.addView(closeButton)

        setContentView(root)
        initializePlayer()
    }

    private fun initializePlayer() {
        val upstream = DefaultHttpDataSource.Factory()
            .setUserAgent("MAXi-FLIX Android")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)

        val cacheDataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSource))
            .build()
            .also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        status.text = "Erro de reprodução: ${error.errorCodeName}"
                        Toast.makeText(
                            this@ExoTestActivity,
                            "Este link não pôde ser reproduzido pelo ExoPlayer.",
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val state = when (playbackState) {
                            Player.STATE_BUFFERING -> "Carregando vídeo…"
                            Player.STATE_READY -> "Reprodução pronta"
                            Player.STATE_ENDED -> "Vídeo finalizado"
                            else -> "Aguardando link de vídeo"
                        }
                        updateCacheStatus(state)
                    }
                })
            }
    }

    private fun startPlayback() {
        val address = urlInput.text.toString().trim()
        val uri = runCatching { Uri.parse(address) }.getOrNull()
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            urlInput.error = "Informe um link HTTP/HTTPS direto e válido."
            return
        }

        val exo = player ?: return
        runCatching {
            // Each new title starts with an empty video cache; app cookies and WebView data are untouched.
            cache.keys.toList().forEach { key -> cache.removeResource(key) }
            exo.stop()
            exo.clearMediaItems()
            exo.setMediaItem(MediaItem.fromUri(uri))
            exo.prepare()
            exo.playWhenReady = true
            updateCacheStatus("Abrindo novo vídeo…")
        }.onFailure { error ->
            status.text = "Não foi possível iniciar: ${error.message ?: "erro desconhecido"}"
        }
    }

    private fun updateCacheStatus(message: String) {
        val usedMb = cache.cacheSpace / (1024L * 1024L)
        status.text = "$message • Cache: $usedMb MB / 200 MB"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        cache.release()
        super.onDestroy()
    }
}
