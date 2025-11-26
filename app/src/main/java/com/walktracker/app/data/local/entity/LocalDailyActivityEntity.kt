package com.walktracker.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.walktracker.app.data.local.converter.RoutePointConverter

@Entity(tableName = "daily_activities")
@TypeConverters(RoutePointConverter::class)
data class LocalDailyActivityEntity(
    @PrimaryKey
    val id: String, // userId_date 형식
    val userId: String,
    val date: String, // yyyy-MM-dd
    val steps: Long = 0L,
    val distance: Double = 0.0, // km
    val calories: Double = 0.0,
    val altitude: Double = 0.0, // m
    val activeMinutes: Int = 0,
    val routes: String = "[]", // JSON 문자열
    val isSynced: Boolean = false, // Firestore 동기화 여부
    val lastModified: Long = System.currentTimeMillis()
)