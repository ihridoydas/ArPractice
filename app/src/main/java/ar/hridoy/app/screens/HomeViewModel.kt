package ar.hridoy.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ar.hridoy.app.BuildConfig
import ar.hridoy.app.storage.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: VideoRepository
) : ViewModel() {

    fun syncData() {
        viewModelScope.launch {
            repository.syncVideos(BuildConfig.GOOGLE_SCRIPT_URL)
        }
    }
}
