package ar.hridoy.app.ar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ar.hridoy.app.BuildConfig
import ar.hridoy.app.common.model.AugmentedVideo
import ar.hridoy.app.storage.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class VideoManagementViewModel @Inject constructor(
    private val repository: VideoRepository
) : ViewModel() {

    val videos = repository.videos.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        fetchVideos()
    }

    fun fetchVideos() {
        viewModelScope.launch {
            _isLoading.value = true
            repository.syncVideos(BuildConfig.GOOGLE_SCRIPT_URL)
            _isLoading.value = false
        }
    }

    fun addVideo(video: AugmentedVideo) {
        viewModelScope.launch {
            try {
                repository.addVideo(BuildConfig.GOOGLE_SCRIPT_URL, video)
            } catch (e: Exception) {
                _error.value = "Failed to add video: ${e.localizedMessage}"
            }
        }
    }

    fun updateVideo(video: AugmentedVideo) {
        viewModelScope.launch {
            try {
                repository.updateVideo(BuildConfig.GOOGLE_SCRIPT_URL, video)
            } catch (e: Exception) {
                _error.value = "Failed to update video: ${e.localizedMessage}"
            }
        }
    }

    fun deleteVideo(video: AugmentedVideo) {
        viewModelScope.launch {
            try {
                repository.deleteVideo(BuildConfig.GOOGLE_SCRIPT_URL, video)
            } catch (e: Exception) {
                _error.value = "Failed to delete: ${e.localizedMessage}"
            }
        }
    }
}
