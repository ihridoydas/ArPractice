package ar.hridoy.app.storage.video

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

import androidx.room.Transaction

@Dao
interface VideoDao {
    @Query("SELECT * FROM augmented_videos")
    fun getAllVideos(): Flow<List<AugmentedVideoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideos(videos: List<AugmentedVideoEntity>)

    @Query("DELETE FROM augmented_videos")
    suspend fun clearVideos()

    @Transaction
    suspend fun refreshVideos(videos: List<AugmentedVideoEntity>) {
        clearVideos()
        insertVideos(videos)
    }
}
