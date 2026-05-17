package ar.hridoy.app.common.model

data class AugmentedVideo(
    val id: Int,
    val name: String,
    val imageAssetPath: String,
    val videoUrl: String,
    val active: Boolean,
    val widthInMeters: Float = 0.2f,
    val rowIndex: Int? = null
)
