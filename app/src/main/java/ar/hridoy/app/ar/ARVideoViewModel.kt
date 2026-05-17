package ar.hridoy.app.ar

import android.content.Context
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ar.hridoy.app.common.model.AugmentedVideo
import ar.hridoy.app.storage.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import io.github.sceneview.loaders.ModelLoader
import java.io.InputStream

@HiltViewModel
class ARVideoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: VideoRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ARUiState>(ARUiState.Loading)
    val uiState = _uiState.asStateFlow()

    init {
        observeVideos()
    }

    private fun observeVideos() {
        viewModelScope.launch {
            repository.videos.collectLatest { targets ->
                val activeTargets = targets.filter { it.active }
                
                if (activeTargets.isNotEmpty()) {
                    // Check if we actually need to reload (avoid reset loops)
                    val currentState = _uiState.value
                    if (currentState !is ARUiState.Success || currentState.targets != activeTargets) {
                        
                        // If we already have data, don't show full-screen loading to avoid flicker
                        if (currentState !is ARUiState.Success) {
                            _uiState.value = ARUiState.Loading
                        }

                        val loadedBitmaps = loadBitmaps(activeTargets)
                        _uiState.value = ARUiState.Success(activeTargets, loadedBitmaps)
                        Timber.tag("AR_DEBUG").d("AR Data Updated: %d targets", activeTargets.size)
                    }
                } else {
                    // Handle empty state...
                    val currentData = repository.videos.first()
                    if (currentData.isEmpty()) {
                        repository.syncVideos(ar.hridoy.app.BuildConfig.GOOGLE_SCRIPT_URL)
                    }
                    if (repository.videos.first().none { it.active }) {
                        _uiState.value = ARUiState.Error("No active videos found.")
                    }
                }
            }
        }
    }

    private suspend fun loadBitmaps(targets: List<AugmentedVideo>): Map<String, Bitmap> = withContext(Dispatchers.IO) {
        targets.map { target ->
            async {
                try {
                    val bitmap = when {
                        target.imageAssetPath.startsWith("http") -> {
                            java.net.URL(target.imageAssetPath).openStream().use { decodeAndResize(it) }
                        }
                        target.imageAssetPath.startsWith("/") || target.imageAssetPath.startsWith("file://") -> {
                            decodeAndResizeFile(target.imageAssetPath.removePrefix("file://"))
                        }
                        else -> {
                            context.assets.open(target.imageAssetPath).use { decodeAndResize(it) }
                        }
                    }
                    if (bitmap != null) target.name to bitmap else null
                } catch (e: Exception) {
                    Timber.e("Failed to load bitmap for ${target.name}: ${e.message}")
                    null
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    private fun decodeAndResize(inputStream: InputStream): Bitmap? {
        val original = BitmapFactory.decodeStream(inputStream) ?: return null
        return scaleBitmap(original)
    }

    private fun decodeAndResizeFile(path: String): Bitmap? {
        val original = BitmapFactory.decodeFile(path) ?: return null
        
        // Fix Rotation
        val orientation = try {
            val exif = ExifInterface(path)
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        
        val rotated = if (!matrix.isIdentity) {
            Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
        } else original
        
        return scaleBitmap(rotated)
    }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val maxSize = 1080
        if (bitmap.width <= maxSize && bitmap.height <= maxSize) return bitmap
        
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val width: Int
        val height: Int
        if (ratio > 1) {
            width = maxSize
            height = (maxSize / ratio).toInt()
        } else {
            height = maxSize
            width = (maxSize * ratio).toInt()
        }
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}

sealed class ARUiState {
    object Loading : ARUiState()
    data class Success(val targets: List<AugmentedVideo>, val bitmaps: Map<String, Bitmap>) : ARUiState()
    data class Error(val message: String) : ARUiState()
}
