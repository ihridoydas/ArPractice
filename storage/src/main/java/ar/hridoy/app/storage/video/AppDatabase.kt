package ar.hridoy.app.storage.video

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [AugmentedVideoEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
}
