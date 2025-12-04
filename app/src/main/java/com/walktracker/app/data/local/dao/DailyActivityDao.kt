package com.walktracker.app.data.local.dao

import androidx.room.*
import com.walktracker.app.data.local.entity.LocalDailyActivityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyActivityDao {

    @Query("SELECT * FROM daily_activities WHERE id = :id")
    suspend fun getActivityById(id: String): LocalDailyActivityEntity?

    @Query("SELECT * FROM daily_activities WHERE userId = :userId AND date = :date")
    suspend fun getActivityByUserAndDate(userId: String, date: String): LocalDailyActivityEntity?

    @Query("SELECT * FROM daily_activities WHERE userId = :userId ORDER BY date DESC LIMIT :limit")
    fun getActivitiesFlow(userId: String, limit: Int): Flow<List<LocalDailyActivityEntity>>

    @Query("SELECT * FROM daily_activities WHERE isSynced = 0")
    suspend fun getUnsyncedActivities(): List<LocalDailyActivityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActivity(activity: LocalDailyActivityEntity)

    @Update
    suspend fun updateActivity(activity: LocalDailyActivityEntity)

    @Query("UPDATE daily_activities SET isSynced = 1 WHERE id = :id")
    suspend fun markAsSynced(id: String)

    @Query("DELETE FROM daily_activities WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    @Query("""
        UPDATE daily_activities 
        SET steps = 0, distance = 0.0, calories = 0.0, altitude = 0.0, routes = '[]', isSynced = 0, lastModified = :currentTime
        WHERE userId = :userId AND date = :date
    """)
    suspend fun resetActivity(userId: String, date: String, currentTime: Long = System.currentTimeMillis())

    @Transaction
    suspend fun incrementActivity(
        userId: String,
        date: String,
        stepsIncrement: Long,
        distanceIncrement: Double,
        caloriesIncrement: Double,
        altitudeIncrement: Double,
        newRoutes: List<com.walktracker.app.model.RoutePoint>
    ) {
        val id = "${userId}_$date"
        val existing = getActivityById(id)

        if (existing != null) {
            // 기존 데이터 업데이트
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<List<com.walktracker.app.model.RoutePoint>>() {}.type
            val existingRoutes: List<com.walktracker.app.model.RoutePoint> = gson.fromJson(existing.routes, type)
            val allRoutes = existingRoutes + newRoutes

            val updated = existing.copy(
                steps = existing.steps + stepsIncrement,
                distance = existing.distance + distanceIncrement,
                calories = existing.calories + caloriesIncrement,
                altitude = existing.altitude + altitudeIncrement,
                routes = gson.toJson(allRoutes),
                isSynced = false,
                lastModified = System.currentTimeMillis()
            )
            updateActivity(updated)
        } else {
            // 새로운 데이터 생성
            val gson = com.google.gson.Gson()
            val newActivity = LocalDailyActivityEntity(
                id = id,
                userId = userId,
                date = date,
                steps = stepsIncrement,
                distance = distanceIncrement,
                calories = caloriesIncrement,
                altitude = altitudeIncrement,
                routes = gson.toJson(newRoutes),
                isSynced = false,
                lastModified = System.currentTimeMillis()
            )
            insertActivity(newActivity)
        }
    }
}