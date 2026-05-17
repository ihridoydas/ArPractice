package ar.hridoy.app.ar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ar.hridoy.app.BuildConfig
import ar.hridoy.app.common.model.AugmentedVideo
import ar.hridoy.app.network.BridgeRequest
import ar.hridoy.app.network.GoogleSheetsApi
import ar.hridoy.app.network.SheetResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class VideoManagementViewModel @Inject constructor() : ViewModel() {

    private val _videos = MutableStateFlow<List<AugmentedVideo>>(emptyList())
    val videos = _videos.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val api: GoogleSheetsApi by lazy {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        Retrofit.Builder()
            .baseUrl("https://sheets.googleapis.com/")
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GoogleSheetsApi::class.java)
    }

    init {
        fetchVideos()
    }

    fun fetchVideos() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val response = api.getSheetValues(BuildConfig.GOOGLE_SCRIPT_URL)
                val fetchedVideos = response.values?.mapIndexed { index, row ->
                    AugmentedVideo(
                        id = row.getOrNull(0)?.toIntOrNull() ?: 0,
                        name = row.getOrNull(1) ?: "",
                        imageAssetPath = row.getOrNull(2) ?: "",
                        videoUrl = row.getOrNull(3) ?: "",
                        active = row.getOrNull(4)?.trim()?.lowercase() == "true",
                        rowIndex = index + 2 // A2 is index 0 in response, which is row 2
                    )
                } ?: emptyList()
                _videos.value = fetchedVideos
            } catch (e: Exception) {
                Timber.e(e, "Failed to fetch videos")
                _error.value = "Failed to load data: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addVideo(video: AugmentedVideo) {
        val currentList = _videos.value.toMutableList()
        currentList.add(video)
        _videos.value = currentList

        viewModelScope.launch {
            try {
                api.executeAction(
                    BuildConfig.GOOGLE_SCRIPT_URL,
                    BridgeRequest(
                        action = "add",
                        id = video.id,
                        name = video.name,
                        imageAssetPath = video.imageAssetPath,
                        videoUrl = video.videoUrl,
                        active = video.active
                    )
                )
                fetchVideos()
            } catch (e: Exception) {
                _error.value = "Failed to add video: ${e.localizedMessage}"
                fetchVideos() // Rollback/Refresh
            }
        }
    }

    fun updateVideo(video: AugmentedVideo) {
        val rowIndex = video.rowIndex ?: return
        val currentList = _videos.value.toMutableList()
        val index = currentList.indexOfFirst { it.rowIndex == rowIndex }
        if (index != -1) {
            currentList[index] = video
            _videos.value = currentList
        }

        viewModelScope.launch {
            try {
                api.executeAction(
                    BuildConfig.GOOGLE_SCRIPT_URL,
                    BridgeRequest(
                        action = "update",
                        id = video.id,
                        name = video.name,
                        imageAssetPath = video.imageAssetPath,
                        videoUrl = video.videoUrl,
                        active = video.active,
                        rowIndex = rowIndex
                    )
                )
                fetchVideos()
            } catch (e: Exception) {
                _error.value = "Failed to update video: ${e.localizedMessage}"
                fetchVideos() // Rollback/Refresh
            }
        }
    }

    fun deleteVideo(video: AugmentedVideo) {
        val rowIndex = video.rowIndex ?: return
        val currentList = _videos.value.toMutableList()
        val videoToRemove = currentList.find { it.rowIndex == rowIndex }
        
        // Optimistic UI Update: Remove locally first
        if (videoToRemove != null) {
            currentList.remove(videoToRemove)
            _videos.value = currentList
        }

        viewModelScope.launch {
            try {
                api.executeAction(
                    BuildConfig.GOOGLE_SCRIPT_URL,
                    BridgeRequest(
                        action = "delete",
                        id = video.id,
                        name = video.name,
                        imageAssetPath = video.imageAssetPath,
                        videoUrl = video.videoUrl,
                        active = video.active,
                        rowIndex = rowIndex
                    )
                )
                // Refresh to get re-sequenced IDs from server
                fetchVideos()
            } catch (e: Exception) {
                _error.value = "Failed to delete: ${e.localizedMessage}"
                // Rollback on failure
                fetchVideos()
            }
        }
    }
}
