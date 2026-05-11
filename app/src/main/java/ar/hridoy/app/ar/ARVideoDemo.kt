package ar.hridoy.app.ar

import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.math.Size
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberViewNodeManager
import kotlinx.coroutines.delay
import timber.log.Timber

private const val TAG = "ARVideoDemo"

data class AugmentedVideo(
    val name: String,
    val imageAssetPath: String,
    val videoUrl: String,
    val widthInMeters: Float = 0.2f
)

@OptIn(ExperimentalSceneViewApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ARVideoDemo(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val viewNodeManager = rememberViewNodeManager()

    var isPlaying by remember { mutableStateOf(true) }
    var isMuted by remember { mutableStateOf(false) }
    var useChromaKey by remember { mutableStateOf(false) }

    val detectedImages = remember { mutableStateListOf<AugmentedImage>() }
    val trackingStates = remember { mutableStateMapOf<Int, TrackingState>() }
    var activeImageIndex by remember { mutableIntStateOf(-1) }

    val activeEnvironment = rememberHDREnvironment(
        environmentLoader, "environments/studio_warm_2k.hdr", createSkybox = false,
    ) ?: rememberEnvironment(environmentLoader)

    val augmentedVideoTargets = remember {
        listOf(
            AugmentedVideo(
                name = "big_bunny",
                imageAssetPath = "augmented_images/big_bunny.jpg",
                videoUrl = "https://www.w3schools.com/html/mov_bbb.mp4"
            ),
            AugmentedVideo(
                name = "sakurahd",
                imageAssetPath = "augmented_images/sakurahd.jpg",
                videoUrl = "https://www.pexels.com/download/video/31313620/"
            ),
            AugmentedVideo(
                name = "cute",
                imageAssetPath = "augmented_images/cute.jpeg",
                videoUrl = "https://youtu.be/a7M4YuI-2yM"
            )
        )
    }

    val bitmaps = remember(context) {
        augmentedVideoTargets.associate { target ->
            target.name to context.assets.open(target.imageAssetPath)
                .use { inputStream -> BitmapFactory.decodeStream(inputStream)!! }
        }
    }

    val onSessionConfiguration = remember(bitmaps) {
        { session: Session, config: Config ->
            config.planeFindingMode = Config.PlaneFindingMode.DISABLED
            config.augmentedImageDatabase = AugmentedImageDatabase(session).apply {
                augmentedVideoTargets.forEach { target ->
                    bitmaps[target.name]?.let { bitmap ->
                        addImage(target.name, bitmap, target.widthInMeters)
                    }
                }
            }
            // Optimize for image tracking
            config.focusMode = Config.FocusMode.AUTO
        }
    }

    val onSessionUpdated = remember {
        { session: Session, _: Frame ->
            val allImages = session.getAllTrackables(AugmentedImage::class.java)

            // Update tracking states for all images
            allImages.forEach { image ->
                trackingStates[image.index] = image.trackingState
            }

            // Find images that are actually being tracked (FULL_TRACKING)
            val trackingImages = allImages.filter {
                it.trackingState == TrackingState.TRACKING &&
                        it.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
            }

            trackingImages.forEach { image ->
                if (detectedImages.none { it.index == image.index }) {
                    Timber.tag(TAG).d("New image detected: %s", image.name)
                    detectedImages.add(image)
                }
            }

            // The active image is the one currently being tracked.
            // If multiple are tracked, we pick the first one.
            // If NONE are tracked, we keep the last active one to allow the 1-min timeout.
            val currentTrackedImage = trackingImages.firstOrNull()
            if (currentTrackedImage != null) {
                if (activeImageIndex != currentTrackedImage.index) {
                    Timber.tag(TAG).d("Switching active video to: %d", currentTrackedImage.index)
                    activeImageIndex = currentTrackedImage.index
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AR Video Tracker") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                ARSceneView(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    environmentLoader = environmentLoader,
                    environment = activeEnvironment,
                    planeRenderer = false,
                    viewNodeWindowManager = viewNodeManager,
                    sessionConfiguration = onSessionConfiguration,
                    onSessionUpdated = onSessionUpdated
                ) {
                    detectedImages.forEach { image ->
                        val videoTarget =
                            augmentedVideoTargets.find { it.name == image.name } ?: return@forEach
                        key(image.index) {
                            val isYouTube = remember(videoTarget.videoUrl) {
                                videoTarget.videoUrl.contains("youtube.com") || videoTarget.videoUrl.contains(
                                    "youtu.be"
                                )
                            }
                            var isReady by remember(image.index) { mutableStateOf(false) }
                            var error by remember(image.index) { mutableStateOf<String?>(null) }
                            var isTrackingStable by remember(image.index) { mutableStateOf(false) }
                            var showLoading by remember(image.index) { mutableStateOf(false) }

                            var isNodePlaying by remember(image.index) { mutableStateOf(isPlaying) }
                            var isTimedOut by remember(image.index) { mutableStateOf(false) }
                            var isUserPaused by remember(image.index) { mutableStateOf(false) }
                            var youTubePlayerInstance by remember(image.index) { mutableStateOf<YouTubePlayer?>(null) }

                            val currentTrackingState = trackingStates[image.index] ?: TrackingState.STOPPED
                            val isCurrentlyTracking = currentTrackingState == TrackingState.TRACKING

                            LaunchedEffect(activeImageIndex, isCurrentlyTracking, isPlaying, isUserPaused, isTimedOut) {
                                val shouldPlay = isPlaying && 
                                               isCurrentlyTracking && 
                                               activeImageIndex == image.index && 
                                               !isUserPaused && 
                                               !isTimedOut
                                
                                isNodePlaying = shouldPlay
                            }

                            LaunchedEffect(image.index) {
                                // Reduced delays for faster feedback
                                delay(200)
                                showLoading = true
                                isTrackingStable = true
                            }

                            val player = if (!isYouTube && isTrackingStable) {
                                remember(image.index) {
                                    MediaPlayer().apply {
                                        setAudioAttributes(
                                            AudioAttributes.Builder()
                                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                                .build()
                                        )
                                        isLooping = true
                                        setVolume(0f, 0f)
                                        setOnInfoListener { _, _, _ ->
                                            true
                                        }
                                        setOnErrorListener { _, _, extra ->
                                            error = "Error: $extra"
                                            true
                                        }
                                        try {
                                            setDataSource(videoTarget.videoUrl)
                                            setOnPreparedListener {
                                                isReady = true
                                                start()
                                            }
                                            prepareAsync()
                                        } catch (e: Exception) {
                                            Timber.tag(TAG).e(e, "Player failed")
                                            error = e.localizedMessage
                                        }
                                    }
                                }
                            } else null
                            DisposableEffect(player) { onDispose { player?.release() } }

                            // Timeout & Stop Logic: If not tracked for 1 minute, reset the video to the beginning
                            LaunchedEffect(isCurrentlyTracking) {
                                if (!isCurrentlyTracking) {
                                    Timber.tag(TAG).d("Image %d tracking lost, waiting for 1min timeout", image.index)
                                    delay(60000)
                                    if (trackingStates[image.index] != TrackingState.TRACKING) {
                                        Timber.tag(TAG).d("Image %d timeout reached, resetting video", image.index)
                                        isTimedOut = true
                                        // Stop and seek back to 0
                                        if (isYouTube) {
                                            youTubePlayerInstance?.pause()
                                            youTubePlayerInstance?.seekTo(0f)
                                        } else {
                                            player?.pause()
                                            player?.seekTo(0)
                                        }
                                    }
                                } else {
                                    isTimedOut = false
                                }
                            }

                            LaunchedEffect(isNodePlaying, isReady) {
                                if (isReady && !isYouTube) {
                                    if (isNodePlaying) {
                                        player?.start()
                                    } else {
                                        player?.pause()
                                    }
                                }
                            }
                            LaunchedEffect(isMuted, isReady) {
                                if (isReady && !isYouTube) {
                                    val v = if (isMuted) 0f else 1f; player?.setVolume(v, v)
                                }
                            }

                            val videoScale = remember(image.index) { Animatable(0f) }
                            LaunchedEffect(isReady, image.trackingState) {
                                if (isReady && image.trackingState != TrackingState.STOPPED) {
                                    videoScale.animateTo(1f, tween(500))
                                } else {
                                    videoScale.animateTo(0f, tween(300))
                                }
                            }

                            val videoAspect = when {
                                isYouTube -> 16f / 9f
                                player != null && player.videoWidth > 0 && player.videoHeight > 0 ->
                                    player.videoWidth.toFloat() / player.videoHeight.toFloat()

                                else -> 16f / 9f
                            }

                            val imageWidth = image.extentX.takeIf { it > 0 } ?: videoTarget.widthInMeters
                            val imageHeight = image.extentZ.takeIf { it > 0 } ?: (imageWidth / videoAspect)

                            val finalWidth: Float
                            val finalHeight: Float

                            if (imageWidth / imageHeight > videoAspect) {
                                finalHeight = imageHeight
                                finalWidth = imageHeight * videoAspect
                            } else {
                                finalWidth = imageWidth
                                finalHeight = imageWidth / videoAspect
                            }

                            AugmentedImageNode(
                                augmentedImage = image,
                                applyImageScale = false,
                                apply = {
                                    isTouchable = false // Background image does not respond to touch
                                    onSingleTapUp = { false } // Explicitly ignore background taps
                                }
                            ) {
                                if (error == null) {
                                    key(useChromaKey, isYouTube) {
                                        if (isYouTube) {
                                            YouTubeNode(
                                                videoUrl = videoTarget.videoUrl,
                                                windowManager = viewNodeManager,
                                                autoPlay = isNodePlaying,
                                                mute = isMuted,
                                                size = Size(finalWidth, finalHeight),
                                                position = Float3(0f, 0.01f, 0f),
                                                rotation = Float3(-90f, 0f, 0f),
                                                scale = Float3(1f, 1f, 1f),
                                                apply = {
                                                    onReady = { player ->
                                                        youTubePlayerInstance = player
                                                        isReady = true
                                                    }
                                                    onSingleTapUp = { _ ->
                                                        // Tapping the video toggles manual pause
                                                        isUserPaused = !isUserPaused
                                                        true
                                                    }
                                                    onLongPress = { _ ->
                                                        isUserPaused = true
                                                        youTubePlayerInstance?.pause()
                                                        youTubePlayerInstance?.seekTo(0f)
                                                    }
                                                }
                                            )
                                        } else if (player != null) {
                                            VideoNode(
                                                player = player,
                                                chromaKeyColor = if (useChromaKey) 0xFF00FF00.toInt() else null,
                                                size = Size(finalWidth, finalHeight),
                                                position = Float3(0f, 0.01f, 0f),
                                                rotation = Float3(-90f, 0f, 0f),
                                                scale = Float3(1f, 1f, 1f),
                                                apply = {
                                                    onSingleTapUp = { _ ->
                                                        // Tapping the video toggles manual pause
                                                        isUserPaused = !isUserPaused
                                                        true
                                                    }
                                                    onLongPress = { _ ->
                                                        isUserPaused = true
                                                        player.pause()
                                                        player.seekTo(0)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                // 🔹 Pause Icon Overlay: Show when the video is paused
                                if (!isNodePlaying && isReady && isCurrentlyTracking) {
                                    ViewNode(
                                        windowManager = viewNodeManager,
                                        unlit = true,
                                        // Position it at the top-right corner of the video
                                        position = Float3(finalWidth / 2f - 0.012f, 0.015f, -finalHeight / 2f + 0.012f),
                                        rotation = Float3(-90f, 0f, 0f),
                                        scale = Float3(1f, 1f, 1f),
                                        apply = {
                                            isTouchable = false
                                            // Constrain the view size to avoid it expanding and covering the screen
                                            pxPerUnits = 2500f
                                        }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Pause,
                                                contentDescription = "Paused",
                                                tint = Color.Blue,
                                                modifier = Modifier.size(15.dp)
                                                    .background(color = Color.White)
                                            )
                                        }
                                    }
                                }

                                if (showLoading && !isReady) {
                                    ViewNode(
                                        windowManager = viewNodeManager,
                                        unlit = true,
                                        position = Float3(0f, 0.01f, 0f),
                                        rotation = Float3(-90f, 0f, 0f),
                                        scale = Float3(1f, 1f, 1f),
                                        apply = {
                                            isTouchable = false // Loading indicator is not touchable
                                            pxPerUnits = 2000f
                                        }
                                    ) {
                                        Box(
                                            modifier = Modifier.size(30.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (error != null) {
                                                Icon(
                                                    imageVector = Icons.Default.Error,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(30.dp)
                                                )
                                            } else {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(30.dp),
                                                    strokeWidth = 1.5.dp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Controls Panel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("Playback", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    IconButton(onClick = { isPlaying = !isPlaying }) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                    }
                    IconButton(onClick = { isMuted = !isMuted }) {
                        Icon(
                            if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("Video Style", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !useChromaKey,
                        onClick = { useChromaKey = false },
                        label = { Text("Standard") })
                    FilterChip(
                        selected = useChromaKey,
                        onClick = { useChromaKey = true },
                        label = { Text("Chroma Key") })
                }
            }
        }
    }
}
