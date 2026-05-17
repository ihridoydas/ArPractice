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

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@Singleton
class VideoRepository @Inject constructor(
    private val api: GoogleSheetsApi,
    private val dao: VideoDao
) {
    // Only emit when the list actually changes, and ignore intermediate empty states during sync
    val videos: Flow<List<AugmentedVideo>> = dao.getAllVideos()
        .distinctUntilChanged()
        .map { entities -> entities.map { it.toModel() } }

    suspend fun syncVideos(scriptUrl: String) = withContext(Dispatchers.IO) {
        try {
            Timber.tag("VideoRepository").d("Syncing videos from script...")
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
                // Atomic transaction: delete + insert
                dao.refreshVideos(targets.map { it.toEntity() })
                Timber.tag("VideoRepository").d("Sync complete. Found %d videos.", targets.size)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to sync videos")
        }
    }

    suspend fun addVideo(scriptUrl: String, video: AugmentedVideo) = withContext(Dispatchers.IO) {
        // Update local first
        dao.insertVideos(listOf(video.toEntity()))
        
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
        // Full sync once to get correct rowIndex from server
        syncVideos(scriptUrl)
    }

    suspend fun updateVideo(scriptUrl: String, video: AugmentedVideo) = withContext(Dispatchers.IO) {
        val rowIndex = video.rowIndex ?: return@withContext
        // Update local first for instant UI response
        dao.updateVideoLocal(video.toEntity())
        
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
    }

    suspend fun deleteVideo(scriptUrl: String, video: AugmentedVideo) = withContext(Dispatchers.IO) {
        val rowIndex = video.rowIndex ?: return@withContext
        // Update local first
        dao.deleteVideoLocal(video.id)
        
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
        // Full sync to re-calculate rowIndexes correctly
        syncVideos(scriptUrl)
    }
}
