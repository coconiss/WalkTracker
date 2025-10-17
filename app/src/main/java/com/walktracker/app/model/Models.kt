package com.walktracker.app.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.GeoPoint

// 사용자 데이터
data class User(
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val weight: Double = 70.0, // kg
    val createdAt: Timestamp = Timestamp.now(),
    val totalDistance: Double = 0.0, // km
    val totalSteps: Long = 0L,
    val totalCalories: Double = 0.0
)

// 일일 활동 데이터
data class DailyActivity(
    val id: String = "",
    val userId: String = "",
    val date: String = "", // yyyy-MM-dd
    val steps: Long = 0L,
    val distance: Double = 0.0, // km
    val calories: Double = 0.0,
    val activeMinutes: Int = 0,
    val routes: List<RoutePoint> = emptyList(),
    val updatedAt: Timestamp = Timestamp.now()
)

// 경로 포인트
data class RoutePoint(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timestamp: Long = 0L,
    val activityType: String = "WALKING" // WALKING, RUNNING, STILL, VEHICLE
)

// 랭킹 데이터
data class RankingEntry(
    val userId: String = "",
    val displayName: String = "",
    val distance: Double = 0.0,
    var rank: Int = 0,
    val period: String = "", // daily, monthly, yearly
    val periodKey: String = "" // 2025-01-15, 2025-01, 2025
)

// 활동 유형
enum class ActivityType {
    WALKING,
    RUNNING,
    STILL,
    VEHICLE,
    UNKNOWN
}

// 활동 세션
data class ActivitySession(
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val activityType: ActivityType = ActivityType.WALKING,
    val steps: Int = 0,
    val distance: Double = 0.0
)

// 로컬 저장용 데이터
data class LocalActivityData(
    val currentSteps: Long = 0L,
    val currentDistance: Double = 0.0,
    val currentCalories: Double = 0.0,
    val lastSyncTime: Long = 0L,
    val isTracking: Boolean = false
)