package com.walktracker.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.walktracker.app.data.local.converter.RoutePointConverter
import com.walktracker.app.data.local.dao.DailyActivityDao
import com.walktracker.app.data.local.entity.LocalDailyActivityEntity

@Database(
    entities = [LocalDailyActivityEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(RoutePointConverter::class)
abstract class WalkTrackerDatabase : RoomDatabase() {

    abstract fun dailyActivityDao(): DailyActivityDao

    companion object {
        @Volatile
        private var INSTANCE: WalkTrackerDatabase? = null

        fun getDatabase(context: Context): WalkTrackerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    WalkTrackerDatabase::class.java,
                    "walk_tracker_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}