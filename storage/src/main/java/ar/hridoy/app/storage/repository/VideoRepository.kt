package ar.hridoy.app.storage.repository

import ar.hridoy.app.common.model.AugmentedVideo
import ar.hridoy.app.storage.network.BridgeRequest
import ar.hridoy.app.storage.network.GoogleSheetsApi
import ar.hridoy.app.storage.video.VideoDao
import ar.hridoy.app.storage.video.toEntity
import ar.hridoy.app.storage.video.toModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoRepository @Inject constructor(
    private val api: GoogleSheetsApi,
    private val dao: VideoDao
) {
    val videos: Flow<List<AugmentedVideo>> = dao.getAllVideos().map { entities ->
        entities.map { it.toModel() }
    }

    suspend fun syncVideos(scriptUrl: String) = withContext(Dispatchers.IO) {
        try {
            val response = api.getSheetValues(scriptUrl)
            val targets = response.values?.mapIndexed { index, row ->
                AugmentedVideo(
                    id = row.getOrNull(0)?.toIntOrNull() ?: 0,
                    name = row.getOrNull(1) ?: "",
                    imageAssetPath = row.getOrNull(2) ?: "",
                    videoUrl = row.getOrNull(3) ?: "",
                    active = row.getOrNull(4)?.trim()?.lowercase() == "true",
                    widthInMeters = row.getOrNull(5)?.toFloatOrNull() ?: 0.2f,
                    rowIndex = index + 2
                )
            } ?: emptyList()

            if (targets.isNotEmpty()) {
                dao.refreshVideos(targets.map { it.toEntity() })
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync videos")
        }
    }

    suspend fun addVideo(scriptUrl: String, video: AugmentedVideo) = withContext(Dispatchers.IO) {
        api.executeAction(
            scriptUrl,
            BridgeRequest(
                action = "add",
                id = video.id,
                name = video.name,
                imageAssetPath = video.imageAssetPath,
                videoUrl = video.videoUrl,
                active = video.active
            )
        )
        syncVideos(scriptUrl)
    }

    suspend fun updateVideo(scriptUrl: String, video: AugmentedVideo) = withContext(Dispatchers.IO) {
        val rowIndex = video.rowIndex ?: return@withContext
        api.executeAction(
            scriptUrl,
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
        syncVideos(scriptUrl)
    }

    suspend fun deleteVideo(scriptUrl: String, video: AugmentedVideo) = withContext(Dispatchers.IO) {
        val rowIndex = video.rowIndex ?: return@withContext
        api.executeAction(
            scriptUrl,
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
        syncVideos(scriptUrl)
    }
}
