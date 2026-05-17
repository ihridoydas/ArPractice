package ar.hridoy.app.ar

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ar.core.*
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
import io.github.sceneview.ar.node.AugmentedImageNode
import io.github.sceneview.node.VideoNode
import io.github.sceneview.rememberViewNodeManager
import kotlinx.coroutines.delay
import timber.log.Timber

@Composable
fun ARVideoDemo(
    onBack: () -> Unit
) {
    var isArInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Wait for the navigation transition to complete (approx 700ms in NavHost)
        delay(800)
        isArInitialized = true
    }

    if (!isArInitialized) {
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
            Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Initializing AR Engine...", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    } else {
        ARVideoContent(onBack)
    }
}

@OptIn(ExperimentalSceneViewApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ARVideoContent(
    onBack: () -> Unit,
    viewModel: ARVideoViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val bitmaps by viewModel.bitmaps.collectAsState()

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

    val activeEnvironment = rememberHDREnvironment(
        environmentLoader, "environments/studio_warm_2k.hdr", createSkybox = false,
    ) ?: rememberEnvironment(environmentLoader)

    val onSessionConfiguration = remember(bitmaps.size, uiState) {
        { session: Session, config: Config ->
            config.planeFindingMode = Config.PlaneFindingMode.DISABLED
            config.augmentedImageDatabase = AugmentedImageDatabase(session).apply {
                bitmaps.forEach { (name, bitmap) ->
                    val target = (uiState as? ARUiState.Success)?.targets?.find { it.name == name }
                    val width = target?.widthInMeters ?: 0.2f
                    addImage(name, bitmap, width)
                }
            }
            config.focusMode = Config.FocusMode.AUTO
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        }
    }

    val onSessionUpdated = remember {
        { session: Session, _: Frame ->
            val allImages = session.getAllTrackables(AugmentedImage::class.java)
            allImages.forEach { image ->
                trackingStates[image.index] = image.trackingState
                trackingMethods[image.index] = image.trackingMethod
            }
            val trackingImages = allImages.filter {
                it.trackingState == TrackingState.TRACKING &&
                        it.trackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING
            }
            trackingImages.forEach { image ->
                if (detectedImages.none { it.index == image.index }) {
                    detectedImages.add(image)
                }
            }
            trackingImages.firstOrNull()?.let {
                if (activeImageIndex != it.index) activeImageIndex = it.index
            }
            Unit
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
        when (val state = uiState) {
            is ARUiState.Loading -> {
                Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is ARUiState.Error -> {
                Box(modifier = Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.message, color = Color.Red)
                }
            }
            is ARUiState.Success -> {
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
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
                                val videoTarget = state.targets.find { it.name == image.name } ?: return@forEach
                                key(image.index) {
                                    val isYouTube = remember(videoTarget.videoUrl) {
                                        videoTarget.videoUrl.contains("youtube.com") || videoTarget.videoUrl.contains("youtu.be")
                                    }
                                    var isReady by remember { mutableStateOf(false) }
                                    var isUserPaused by remember { mutableStateOf(false) }
                                    var youTubePlayerInstance by remember { mutableStateOf<YouTubePlayer?>(null) }

                                    val currentTrackingState = trackingStates[image.index] ?: TrackingState.STOPPED
                                    val currentTrackingMethod = trackingMethods[image.index] ?: AugmentedImage.TrackingMethod.NOT_TRACKING
                                    
                                    // Stability fix: Node stays drawn as long as ARCore is tracking the image path
                                    val isNodeVisible = currentTrackingState == TrackingState.TRACKING
                                    
                                    // Only play/focus if it's the primary fully tracked image
                                    val isInFocus = isNodeVisible &&
                                                   currentTrackingMethod == AugmentedImage.TrackingMethod.FULL_TRACKING &&
                                                   image.index == activeImageIndex

                                    val player = if (!isYouTube) {
                                        remember {
                                            MediaPlayer().apply {
                                                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                                                isLooping = true
                                                setVolume(0f, 0f)
                                                try {
                                                    setDataSource(videoTarget.videoUrl)
                                                    setOnPreparedListener { isReady = true; if (isPlaying && isInFocus && !isUserPaused) start() }
                                                    prepareAsync()
                                                } catch (e: Exception) { Timber.e(e) }
                                            }
                                        }
                                    } else null

                                    DisposableEffect(Unit) { onDispose { player?.release() } }

                                    LaunchedEffect(isInFocus, isPlaying, isUserPaused) {
                                        val shouldPlay = isPlaying && isInFocus && !isUserPaused
                                        if (isYouTube) {
                                            if (shouldPlay) youTubePlayerInstance?.play() else youTubePlayerInstance?.pause()
                                        } else {
                                            if (shouldPlay && isReady) player?.start() else player?.pause()
                                        }
                                    }

                                    LaunchedEffect(isMuted) {
                                        val v = if (isMuted) 0f else 1f
                                        player?.setVolume(v, v)
                                    }

                                    val videoAspect = 16f/9f
                                    val finalWidth = image.extentX.takeIf { it > 0 } ?: 0.2f
                                    val finalHeight = finalWidth / videoAspect

                                    AugmentedImageNode(
                                        augmentedImage = image,
                                        applyImageScale = false,
                                        apply = { isVisible = isNodeVisible }
                                    ) {
                                        if (isYouTube) {
                                            YouTubeNode(
                                                videoUrl = videoTarget.videoUrl,
                                                windowManager = viewNodeManager,
                                                autoPlay = isPlaying && isInFocus,
                                                mute = isMuted,
                                                size = Size(finalWidth, finalHeight),
                                                position = Float3(0f, 0.01f, 0f),
                                                rotation = Float3(-90f, 0f, 0f),
                                                apply = {
                                                    onReady = { player -> 
                                                        youTubePlayerInstance = player
                                                        isReady = true 
                                                    }
                                                    onSingleTapUp = { _ -> 
                                                        isUserPaused = !isUserPaused
                                                        true 
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
                                                apply = {
                                                    onSingleTapUp = { _ ->
                                                        isUserPaused = !isUserPaused
                                                        true
                                                    }
                                                }
                                            )
                                        }

                                        // 🔹 Loading Overlay
                                        if (!isReady) {
                                            ViewNode(
                                                windowManager = viewNodeManager,
                                                unlit = true,
                                                position = Float3(0f, 0.01f, 0f),
                                                rotation = Float3(-90f, 0f, 0f),
                                                apply = {
                                                    isTouchable = false
                                                    pxPerUnits = 2000f
                                                }
                                            ) {
                                                Box(
                                                    modifier = Modifier.size(40.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(24.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                }
                                            }
                                        }

                                        // 🔹 Pause Icon Overlay
                                        val shouldShowPause = (isUserPaused || !isPlaying) && isReady
                                        if (shouldShowPause) {
                                            ViewNode(
                                                windowManager = viewNodeManager,
                                                unlit = true,
                                                position = Float3(finalWidth / 2f - 0.012f, 0.015f, -finalHeight / 2f + 0.012f),
                                                rotation = Float3(-90f, 0f, 0f),
                                                apply = {
                                                    isTouchable = false
                                                    pxPerUnits = 2500f
                                                }
                                            ) {
                                                Box(
                                                    modifier = Modifier.size(20.dp),
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
                                    }
                                }
                            }
                        }
                    }

                    // Controls
                    PlaybackControls(
                        isPlaying = isPlaying,
                        onTogglePlay = { isPlaying = !isPlaying },
                        isMuted = isMuted,
                        onToggleMute = { isMuted = !isMuted },
                        useChromaKey = useChromaKey,
                        onToggleChroma = { useChromaKey = it }
                    )
                }
            }
        }
    }
}

@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    useChromaKey: Boolean,
    onToggleChroma: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Playback", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = onTogglePlay) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
            }
            IconButton(onClick = onToggleMute) {
                Icon(if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, null)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !useChromaKey, onClick = { onToggleChroma(false) }, label = { Text("Standard") })
            FilterChip(selected = useChromaKey, onClick = { onToggleChroma(true) }, label = { Text("Chroma") })
        }
    }
}
