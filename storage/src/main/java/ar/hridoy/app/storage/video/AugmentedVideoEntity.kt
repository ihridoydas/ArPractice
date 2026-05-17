package ar.hridoy.app.storage.video

import androidx.room.Entity
import androidx.room.PrimaryKey
import ar.hridoy.app.common.model.AugmentedVideo

@Entity(tableName = "augmented_videos")
data class AugmentedVideoEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val imageAssetPath: String,
    val videoUrl: String,
    val active: Boolean,
    val widthInMeters: Float,
    val rowIndex: Int?
)

fun AugmentedVideoEntity.toModel() = AugmentedVideo(
    id = id,
    name = name,
    imageAssetPath = imageAssetPath,
    videoUrl = videoUrl,
    active = active,
    widthInMeters = widthInMeters,
    rowIndex = rowIndex
)

fun AugmentedVideo.toEntity() = AugmentedVideoEntity(
    id = id,
    name = name,
    imageAssetPath = imageAssetPath,
    videoUrl = videoUrl,
    active = active,
    widthInMeters = widthInMeters,
    rowIndex = rowIndex
)
