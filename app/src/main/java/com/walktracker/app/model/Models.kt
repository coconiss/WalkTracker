package com.walktracker.app.model

import android.os.Parcelable
import com.google.firebase.Timestamp
import kotlinx.parcelize.Parcelize

// Parcelable 구현: Intent를 통해 객체를 전달하기 위함
@Parcelize
data class User(
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val weight: Double = 70.0, // kg
    val stride: Double = 0.7, // m
    // Timestamp는 Parcelable을 구현하므로 @Parcelize가 자동으로 처리합니다.
    val createdAt: Timestamp = Timestamp.now(),
    val totalDistance: Double = 0.0, // km
    val totalSteps: Long = 0L,
    val totalCalories: Double = 0.0
) : Parcelable

@Parcelize
data class DailyActivity(
    val id: String = "",
    val userId: String = "",
    val date: String = "", // yyyy-MM-dd
    val steps: Long = 0L,
    val distance: Double = 0.0, // km
    val calories: Double = 0.0,
    val altitude: Double = 0.0, // m
    val activeMinutes: Int = 0,
    val routes: List<RoutePoint> = emptyList(),
    val updatedAt: Timestamp = Timestamp.now()
) : Parcelable

@Parcelize
data class RoutePoint(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timestamp: Long = 0L,
    val activityType: String = "WALKING", // WALKING, RUNNING, STILL, VEHICLE
    val speed: Double = 0.0 // m/s
) : Parcelable

@Parcelize
data class RankingEntry(
    val userId: String = "",
    val displayName: String = "",
    val distance: Double = 0.0,
    var rank: Int = 0,
    val period: String = "", // daily, monthly, yearly
    val periodKey: String = "" // 2025-01-15, 2025-01, 2025
) : Parcelable

// 활동 유형
enum class ActivityType {
    WALKING,
    RUNNING,
    STILL,
    VEHICLE,
    UNKNOWN
}

// 활동 세션
@Parcelize
data class ActivitySession(
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val activityType: ActivityType = ActivityType.WALKING,
    val steps: Int = 0,
    val distance: Double = 0.0
) : Parcelable

// 로컬 저장용 데이터
@Parcelize
data class LocalActivityData(
    val currentSteps: Long = 0L,
    val currentDistance: Double = 0.0,
    val currentCalories: Double = 0.0,
    val lastSyncTime: Long = 0L,
    val isTracking: Boolean = false
) : Parcelable
