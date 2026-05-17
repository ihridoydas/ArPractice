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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ARVideoViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: VideoRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ARUiState>(ARUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _bitmaps = MutableStateFlow<Map<String, android.graphics.Bitmap>>(emptyMap())
    val bitmaps = _bitmaps.asStateFlow()

    init {
        observeVideos()
    }

    private fun observeVideos() {
        viewModelScope.launch {
            repository.videos.collectLatest { targets ->
                val activeTargets = targets.filter { it.active }
                if (activeTargets.isNotEmpty()) {
                    loadBitmaps(activeTargets)
                    _uiState.value = ARUiState.Success(activeTargets)
                } else if (_uiState.value is ARUiState.Loading) {
                    // Still loading or DB is empty
                    // Don't auto-sync here to avoid loops, let HomeScreen handle it
                    // But if we are still in Loading state and DB is empty, show a message
                    _uiState.value = ARUiState.Error("No videos found. Please wait for sync or check spreadsheet.")
                }
            }
        }
    }

    private suspend fun loadBitmaps(targets: List<AugmentedVideo>) = withContext(Dispatchers.IO) {
        val loadedBitmaps = targets.map { target ->
            async {
                try {
                    val bitmap = if (target.imageAssetPath.startsWith("http")) {
                        java.net.URL(target.imageAssetPath).openStream().use { 
                            BitmapFactory.decodeStream(it) 
                        }
                    } else {
                        context.assets.open(target.imageAssetPath).use { 
                            BitmapFactory.decodeStream(it) 
                        }
                    }
                    if (bitmap != null) target.name to bitmap else null
                } catch (e: Exception) {
                    Timber.e("Failed to load bitmap for ${target.name}")
                    null
                }
            }
        }.awaitAll().filterNotNull().toMap()
        
        _bitmaps.value = loadedBitmaps
    }
}

sealed class ARUiState {
    object Loading : ARUiState()
    data class Success(val targets: List<AugmentedVideo>) : ARUiState()
    data class Error(val message: String) : ARUiState()
}
