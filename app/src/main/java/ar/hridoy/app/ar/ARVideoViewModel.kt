package ar.hridoy.app.ar

import android.content.Context
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ar.hridoy.app.BuildConfig
import ar.hridoy.app.common.model.AugmentedVideo
import ar.hridoy.app.network.GoogleSheetsApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ARVideoViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<ARUiState>(ARUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _bitmaps = MutableStateFlow<Map<String, android.graphics.Bitmap>>(emptyMap())
    val bitmaps = _bitmaps.asStateFlow()

    private val api: GoogleSheetsApi by lazy {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        Retrofit.Builder()
            .baseUrl("https://sheets.googleapis.com/")
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(GoogleSheetsApi::class.java)
    }

    init {
        fetchData()
    }

    fun fetchData() {
        viewModelScope.launch {
            _uiState.value = ARUiState.Loading
            try {
                val response = api.getSheetValues(BuildConfig.GOOGLE_SCRIPT_URL)
                val targets = response.values?.filter { it.size >= 5 && it[4].trim().lowercase() == "true" }
                    ?.map { row ->
                        AugmentedVideo(
                            id = row[0].toIntOrNull() ?: 0,
                            name = row[1],
                            imageAssetPath = row[2],
                            videoUrl = row[3],
                            active = true,
                            widthInMeters = row.getOrNull(5)?.toFloatOrNull() ?: 0.2f
                        )
                    } ?: emptyList()

                if (targets.isEmpty()) {
                    _uiState.value = ARUiState.Error("No active videos found in spreadsheet")
                    return@launch
                }

                loadBitmaps(targets)
                _uiState.value = ARUiState.Success(targets)
            } catch (e: Exception) {
                Timber.e(e, "Failed to fetch AR data")
                _uiState.value = ARUiState.Error(e.localizedMessage ?: "Unknown network error")
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
