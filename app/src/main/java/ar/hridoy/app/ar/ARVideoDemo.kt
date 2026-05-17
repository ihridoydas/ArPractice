package ar.hridoy.app.ar

import ar.hridoy.app.common.model.AugmentedVideo
import ar.hridoy.app.network.GoogleSheetsApi
import ar.hridoy.app.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import timber.log.Timber

private const val TAG = "ARVideoDemo"

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
    val trackingMethods = remember { mutableStateMapOf<Int, AugmentedImage.TrackingMethod>() }
    var activeImageIndex by remember { mutableIntStateOf(-1) }

    val augmentedVideoTargets = remember { mutableStateListOf<AugmentedVideo>() }
    var isLoadingData by remember { mutableStateOf(true) }
    var fetchError by remember { mutableStateOf<String?>(null) }

    val activeEnvironment = rememberHDREnvironment(
        environmentLoader, "environments/studio_warm_2k.hdr", createSkybox = false,
    ) ?: rememberEnvironment(environmentLoader)

    LaunchedEffect(Unit) {
        isLoadingData = true
        fetchError = null
        try {
            withTimeout(10000) { // 10 second timeout
                Timber.tag(TAG).d("Fetching spreadsheet data from ${BuildConfig.SPREADSHEET_ID}")
                val moshi = Moshi.Builder()
                    .add(KotlinJsonAdapterFactory())
                    .build()
                val retrofit = Retrofit.Builder()
                    .baseUrl("https://sheets.googleapis.com/")
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
                val api = retrofit.create(GoogleSheetsApi::class.java)
                
                val response = api.getSheetValues(BuildConfig.SPREADSHEET_ID, "Sheet1!A2:E20", BuildConfig.GOOGLE_API_KEY)
                
                val values = response.values
                if (values == null || values.isEmpty()) {
                    Timber.tag(TAG).w("No values found in spreadsheet")
                    fetchError = "No data found in spreadsheet. Check if 'Sheet1' exists and has content."
                } else {
                    Timber.tag(TAG).d("Found %d rows", values.size)
                    values.forEachIndexed { index, row ->
                        Timber.tag(TAG).v("Row %d: %s", index, row.joinToString())
                        // Ensure row has enough columns and column E (index 4) is "true"
                        if (row.size >= 5 && row[4].trim().lowercase() == "true") {
                            augmentedVideoTargets.add(
                                AugmentedVideo(
                                    id = row[0].toIntOrNull() ?: 0,
                                    name = row[1],
                                    imageAssetPath = row[2],
                                    videoUrl = row[3],
                                    active = true
                                )
                            )
                        }
                    }
                    if (augmentedVideoTargets.isEmpty()) {
                        Timber.tag(TAG).w("No active videos found in sheet matching 'true' in column E")
                        fetchError = "No active videos found in sheet. Make sure Column E has 'true'."
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to fetch spreadsheet data")
            fetchError = "Error: ${e.localizedMessage ?: "Connection timed out or failed"}. Check Internet and API Key."
        } finally {
            isLoadingData = false
        }
    }

    if (isLoadingData) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return@ARVideoDemo
    }

    if (fetchError != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text(fetchError!!, style = MaterialTheme.typography.bodyLarge, color = Color.Red)
            }
        }
        return@ARVideoDemo
    }

    val bitmaps = remember(context, augmentedVideoTargets.size) {
        augmentedVideoTargets.associate { target ->
            target.name to try {
                context.assets.open(target.imageAssetPath)
                    .use { inputStream -> BitmapFactory.decodeStream(inputStream) }
            } catch (_: Exception) {
                null
            }
        }.filterValues { it != null }.mapValues { it.value!! }
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
                trackingMethods[image.index] = image.trackingMethod
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

            // Update the active image index to the one currently being tracked.
            // If multiple are tracked, we pick the first one to avoid "overtaking".
            val currentTrackedImage = trackingImages.firstOrNull()
            if (currentTrackedImage != null) {
                if (activeImageIndex != currentTrackedImage.index) {
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
                            var showLoading by remember(image.index) { mutableStateOf(false) }
                            var retryCount by remember(image.index) { mutableIntStateOf(0) }

                            var isNodePlaying by remember(image.index) { mutableStateOf(isPlaying) }
                            var isTimedOut by remember(image.index) { mutableStateOf(false) }
                            var isUserPaused by remember(image.index) { mutableStateOf(false) }
                            var youTubePlayerInstance by remember(image.index) { mutableStateOf<YouTubePlayer?>(null) }

                            val currentTrackingState = trackingStates[image.index] ?: TrackingState.STOPPED
                            val currentTrackingMethod = trackingMethods[image.index] ?: AugmentedImage.TrackingMethod.NOT_TRACKING
                            
                            // "In Focus" means it's actively tracked by the camera and is the primary image
                            val isInFocus = currentTrackingState == TrackingState.TRACKING && 
                                           currentTrackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING &&
                                           image.index == activeImageIndex

                            LaunchedEffect(isInFocus, isPlaying, isUserPaused, isTimedOut) {
                                val shouldPlay = isPlaying && 
                                               isInFocus && 
                                               !isUserPaused && 
                                               !isTimedOut
                                
                                isNodePlaying = shouldPlay
                            }

                            LaunchedEffect(image.index) {
                                showLoading = true
                            }

                            val player = if (!isYouTube) {
                                remember(image.index, retryCount) {
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
                                                error = null
                                                start()
                                            }
                                            prepareAsync()
                                        } catch (e: Exception) {
                                            Timber.tag(TAG).e(e, "Player failed")
                                            error = "Fetch Failed: ${e.localizedMessage}"
                                        }
                                    }
                                }
                            } else null
                            DisposableEffect(player) { onDispose { player?.release() } }

                            // Timeout & Stop Logic: If not in focus for 15 seconds, reset the video
                            LaunchedEffect(isInFocus) {
                                if (!isInFocus) {
                                    Timber.tag(TAG).d("Image %d lost focus, waiting for 15s timeout", image.index)
                                    delay(15000)
                                    if (!isInFocus) {
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
                                    isVisible = isInFocus // Only show the node if it's in focus
                                    isTouchable = false
                                    onSingleTapUp = { false }
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
                                if (!isNodePlaying && isReady && isInFocus) {
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
                                            isTouchable = true
                                            onSingleTapUp = {
                                                if (error != null) {
                                                    error = null
                                                    retryCount++
                                                    true
                                                } else false
                                            }
                                            pxPerUnits = 2000f
                                        }
                                    ) {
                                        Box(
                                            modifier = Modifier.size(40.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (error != null) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Icon(
                                                        imageVector = Icons.Default.Error,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                    Text(
                                                        "Tap to retry",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            } else {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(24.dp),
                                                    strokeWidth = 2.dp
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
