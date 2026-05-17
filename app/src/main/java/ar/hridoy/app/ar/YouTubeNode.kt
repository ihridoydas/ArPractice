package ar.hridoy.app.ar

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.google.android.filament.Engine
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import io.github.sceneview.SceneScope
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Size
import io.github.sceneview.node.ViewNode.WindowManager
import timber.log.Timber

/**
 * A [ViewNode] that renders a YouTube video using the Android YouTube Player library.
 * Fixed: Supports hardware-accelerated video rendering into the 3D scene with lazy initialization
 * to prevent graphics deadlocks during AR cold-starts.
 */
open class YouTubeNode(
    engine: Engine,
    windowManager: WindowManager,
    materialLoader: MaterialLoader,
    val youtubeVideoId: String,
    autoPlay: Boolean = true,
    mute: Boolean = false,
    size: Size? = null,
    unlit: Boolean = true,
    invertFrontFaceWinding: Boolean = false
) : ViewNode(
    engine = engine,
    windowManager = windowManager,
    materialLoader = materialLoader,
    view = FrameLayout(materialLoader.context).apply {
        // Initial dummy view to keep the constructor light and avoid UI thread freeze
        val width = 1024
        val height = if (size != null && size.x > 0) (1024 * size.y / size.x).toInt() else 576
        layoutParams = ViewGroup.LayoutParams(width, height)
    },
    unlit = unlit,
    invertFrontFaceWinding = invertFrontFaceWinding
) {
    private var isReleased = false
    private var isInitialized = false
    private var isPlaying = false
    private var youTubePlayer: YouTubePlayer? = null
    private var youTubePlayerView: YouTubePlayerView? = null

    var isMuted: Boolean = mute
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    youTubePlayer?.mute()
                } else {
                    youTubePlayer?.unMute()
                }
            }
        }

    var isAutoPlay: Boolean = autoPlay
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    youTubePlayer?.play()
                } else {
                    youTubePlayer?.pause()
                }
            }
        }

    var onReady: ((YouTubePlayer) -> Unit)? = null

    private val initRunnable = Runnable {
        if (isReleased || isInitialized) return@Runnable

        try {
            val context = view.context
            val playerView = YouTubePlayerView(context).apply {
                enableAutomaticInitialization = false
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            this.youTubePlayerView = playerView
            (view as FrameLayout).addView(playerView)

            val options = IFramePlayerOptions.Builder(context)
                .controls(0)
                .rel(0)
                .ivLoadPolicy(3)
                .ccLoadPolicy(0)
                .build()

            playerView.initialize(object : AbstractYouTubePlayerListener() {
                override fun onReady(youTubePlayer: YouTubePlayer) {
                    if (isReleased) return
                    this@YouTubeNode.youTubePlayer = youTubePlayer

                    if (isMuted) {
                        youTubePlayer.mute()
                    }

                    if (isAutoPlay) {
                        youTubePlayer.loadVideo(youtubeVideoId, 0f)
                    } else {
                        youTubePlayer.cueVideo(youtubeVideoId, 0f)
                    }

                    onReady?.invoke(youTubePlayer)
                    isInitialized = true
                }

                override fun onStateChange(
                    youTubePlayer: YouTubePlayer,
                    state: PlayerConstants.PlayerState
                ) {
                    if (isReleased) return
                    val wasPlaying = isPlaying
                    isPlaying = (state == PlayerConstants.PlayerState.PLAYING)
                    if (isPlaying && !wasPlaying) {
                        layout.removeCallbacks(refreshRunnable)
                        layout.post(refreshRunnable)
                    }
                }
            }, options)
        } catch (e: Exception) {
            Timber.w(e, "YouTubePlayerView delayed initialization failed: ${e.message}")
        }
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!isReleased && view.isAttachedToWindow && isPlaying) {
                layout.invalidate()
                view.postOnAnimation(this)
            }
        }
    }

    init {
        if (size != null && size.x > 0) {
            this.pxPerUnits = 1024f / size.x
        }
        useHardwareCanvas = true

        // Delay the initialization of YouTubePlayerView slightly to allow the AR session to stabilize.
        // Reduced delay for faster response.
        view.postDelayed(initRunnable, 300)
    }

    override fun destroy() {
        if (isReleased) return
        isReleased = true
        isPlaying = false
        view.removeCallbacks(initRunnable)
        view.removeCallbacks(refreshRunnable)
        layout.removeCallbacks(refreshRunnable)
        try {
            youTubePlayerView?.release()
        } catch (_: Exception) {
        }
        super.destroy()
    }

    companion object {
        fun extractVideoId(url: String): String? {
            val cleanUrl = url.trim()
            return when {
                cleanUrl.contains("youtu.be/") -> cleanUrl.substringAfter("youtu.be/").substringBefore("?").substringBefore("&")
                cleanUrl.contains("youtube.com/watch?v=") -> cleanUrl.substringAfter("v=").substringBefore("&")
                cleanUrl.contains("youtube.com/embed/") -> cleanUrl.substringAfter("embed/").substringBefore("?")
                cleanUrl.contains("youtube.com/v/") -> cleanUrl.substringAfter("v/").substringBefore("?")
                cleanUrl.contains("youtube.com/shorts/") -> cleanUrl.substringAfter("shorts/").substringBefore("?")
                cleanUrl.length == 11 && cleanUrl.all { it.isLetterOrDigit() || it == '-' || it == '_' } -> cleanUrl
                else -> null
            }
        }
    }
}

/**
 * A node that renders a YouTube video using the Android YouTube Player library.
 *
 * @param videoUrl       The YouTube video URL or ID.
 * @param windowManager  The [WindowManager] to attach the web view to.
 * @param autoPlay       Whether to start playback immediately. Default `true`.
 * @param mute           Whether to mute the video. Default `false`.
 * @param position       World-space position.
 * @param rotation       World-space rotation.
 * @param scale          World-space scale.
 * @param apply          Additional configuration on the [YouTubeNode] instance.
 */
@Composable
fun SceneScope.YouTubeNode(
    videoUrl: String,
    windowManager: WindowManager,
    autoPlay: Boolean = true,
    mute: Boolean = false,
    size: Size? = null,
    position: io.github.sceneview.math.Position = io.github.sceneview.math.Position(x = 0f),
    rotation: io.github.sceneview.math.Rotation = io.github.sceneview.math.Rotation(x = 0f),
    scale: io.github.sceneview.math.Scale = io.github.sceneview.math.Scale(1f),
    apply: YouTubeNode.() -> Unit = {},
    content: (@Composable io.github.sceneview.NodeScope.() -> Unit)? = null
) {
    val youtubeVideoId = remember(videoUrl) { YouTubeNode.extractVideoId(videoUrl) ?: videoUrl }
    val node = remember(engine, windowManager, youtubeVideoId) {
        YouTubeNode(
            engine = engine,
            windowManager = windowManager,
            materialLoader = materialLoader,
            youtubeVideoId = youtubeVideoId,
            autoPlay = autoPlay,
            mute = mute,
            size = size
        ).apply(apply)
    }
    DisposableEffect(node) {
        onDispose {
            node.parent?.removeChildNode(node)
            node.destroy()
        }
    }
    DisposableEffect(node, size?.x) {
        if (size != null && size.x > 0f) {
            node.pxPerUnits = 1024f / size.x
        }
        onDispose {}
    }
    SideEffect {
        node.isAutoPlay = autoPlay
        node.isMuted = mute
    }
    Node(
        position = position,
        rotation = rotation,
        scale = scale,
        apply = {
            addChildNode(node)
            node.apply(apply)
        },
        content = content
    )
}
