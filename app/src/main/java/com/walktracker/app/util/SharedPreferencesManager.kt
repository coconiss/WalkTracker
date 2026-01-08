package com.walktracker.app.util

import android.content.Context
import android.content.SharedPreferences

class SharedPreferencesManager(private val context: Context) {

    private val syncPrefs: SharedPreferences = context.getSharedPreferences("WalkTrackerSyncPrefs", Context.MODE_PRIVATE)
    private val prefs: SharedPreferences = context.getSharedPreferences("WalkTrackerPrefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_UNSYNCED_STEPS = "unsynced_steps"
        private const val KEY_UNSYNCED_DISTANCE = "unsynced_distance"
        private const val KEY_UNSYNCED_CALORIES = "unsynced_calories"
        private const val KEY_UNSYNCED_ALTITUDE = "unsynced_altitude" // 고도 키 추가

        // 센서 설정 키
        private const val KEY_GPS_ENABLED = "gps_enabled"
        private const val KEY_STEP_SENSOR_ENABLED = "step_sensor_enabled"
        private const val KEY_PRESSURE_SENSOR_ENABLED = "pressure_sensor_enabled"

        // 사용자 데이터 키
        private const val KEY_USER_STRIDE = "user_stride"
    }

    fun addUnsyncedData(steps: Long, distance: Double, calories: Double, altitude: Double) { // 높이 파라미터 추가
        val editor = syncPrefs.edit()
        editor.putLong(KEY_UNSYNCED_STEPS, syncPrefs.getLong(KEY_UNSYNCED_STEPS, 0L) + steps)
        editor.putFloat(KEY_UNSYNCED_DISTANCE, syncPrefs.getFloat(KEY_UNSYNCED_DISTANCE, 0f) + distance.toFloat())
        editor.putFloat(KEY_UNSYNCED_CALORIES, syncPrefs.getFloat(KEY_UNSYNCED_CALORIES, 0f) + calories.toFloat())
        editor.putFloat(KEY_UNSYNCED_ALTITUDE, syncPrefs.getFloat(KEY_UNSYNCED_ALTITUDE, 0f) + altitude.toFloat()) // 고도 데이터 추가
        editor.apply()
    }

    fun getUnsyncedData(): Map<String, Number> { // 반환 타입을 Map으로 변경
        val steps = syncPrefs.getLong(KEY_UNSYNCED_STEPS, 0L)
        val distance = syncPrefs.getFloat(KEY_UNSYNCED_DISTANCE, 0f).toDouble()
        val calories = syncPrefs.getFloat(KEY_UNSYNCED_CALORIES, 0f).toDouble()
        val altitude = syncPrefs.getFloat(KEY_UNSYNCED_ALTITUDE, 0f).toDouble()
        return mapOf(
            "steps" to steps,
            "distance" to distance,
            "calories" to calories,
            "altitude" to altitude
        )
    }

    fun clearUnsyncedData() {
        val editor = syncPrefs.edit()
        editor.remove(KEY_UNSYNCED_STEPS)
        editor.remove(KEY_UNSYNCED_DISTANCE)
        editor.remove(KEY_UNSYNCED_CALORIES)
        editor.remove(KEY_UNSYNCED_ALTITUDE) // 고도 데이터 삭제
        editor.apply()
    }

    // 센서 설정 저장
    fun setGpsEnabled(enabled: Boolean) {
        syncPrefs.edit().putBoolean(KEY_GPS_ENABLED, enabled).apply()
    }

    fun setStepSensorEnabled(enabled: Boolean) {
        syncPrefs.edit().putBoolean(KEY_STEP_SENSOR_ENABLED, enabled).apply()
    }

    fun setPressureSensorEnabled(enabled: Boolean) {
        syncPrefs.edit().putBoolean(KEY_PRESSURE_SENSOR_ENABLED, enabled).apply()
    }

    // 센서 설정 불러오기 (기본값: true)
    fun isGpsEnabled(): Boolean = syncPrefs.getBoolean(KEY_GPS_ENABLED, true)

    fun isStepSensorEnabled(): Boolean = syncPrefs.getBoolean(KEY_STEP_SENSOR_ENABLED, true)

    fun isPressureSensorEnabled(): Boolean = syncPrefs.getBoolean(KEY_PRESSURE_SENSOR_ENABLED, true)

    // 사용자 보폭 저장 및 불러오기
    fun setUserStride(stride: Double) {
        syncPrefs.edit().putFloat(KEY_USER_STRIDE, stride.toFloat()).apply()
    }

    fun getUserStride(): Double = syncPrefs.getFloat(KEY_USER_STRIDE, 0.7f).toDouble()

    // --- WalkTrackerPrefs (일반 설정) ---
    fun setTrackingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tracking_enabled", enabled).apply()
    }

    fun isTrackingEnabled(): Boolean = prefs.getBoolean("tracking_enabled", false)

    fun setNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("notification_enabled", enabled).apply()
    }

    fun isNotificationEnabled(): Boolean = prefs.getBoolean("notification_enabled", true)

    fun clearAllLocalPrefs() {
        // Clear both prefs files used by the app (but not third-party ones like osmdroid)
        prefs.edit().clear().apply()
        syncPrefs.edit().clear().apply()
    }
}