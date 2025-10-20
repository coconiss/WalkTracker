package com.walktracker.app.util

import android.content.Context
import android.content.SharedPreferences

class SharedPreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("WalkTrackerSyncPrefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_UNSYNCED_STEPS = "unsynced_steps"
        private const val KEY_UNSYNCED_DISTANCE = "unsynced_distance"
        private const val KEY_UNSYNCED_CALORIES = "unsynced_calories"
        private const val KEY_UNSYNCED_ALTITUDE = "unsynced_altitude" // 고도 키 추가
    }

    fun addUnsyncedData(steps: Long, distance: Double, calories: Double, altitude: Double) { // 고도 파라미터 추가
        val editor = prefs.edit()
        editor.putLong(KEY_UNSYNCED_STEPS, prefs.getLong(KEY_UNSYNCED_STEPS, 0L) + steps)
        editor.putFloat(KEY_UNSYNCED_DISTANCE, prefs.getFloat(KEY_UNSYNCED_DISTANCE, 0f) + distance.toFloat())
        editor.putFloat(KEY_UNSYNCED_CALORIES, prefs.getFloat(KEY_UNSYNCED_CALORIES, 0f) + calories.toFloat())
        editor.putFloat(KEY_UNSYNCED_ALTITUDE, prefs.getFloat(KEY_UNSYNCED_ALTITUDE, 0f) + altitude.toFloat()) // 고도 데이터 추가
        editor.apply()
    }

    fun getUnsyncedData(): Map<String, Number> { // 반환 타입을 Map으로 변경
        val steps = prefs.getLong(KEY_UNSYNCED_STEPS, 0L)
        val distance = prefs.getFloat(KEY_UNSYNCED_DISTANCE, 0f).toDouble()
        val calories = prefs.getFloat(KEY_UNSYNCED_CALORIES, 0f).toDouble()
        val altitude = prefs.getFloat(KEY_UNSYNCED_ALTITUDE, 0f).toDouble()
        return mapOf(
            "steps" to steps,
            "distance" to distance,
            "calories" to calories,
            "altitude" to altitude
        )
    }

    fun clearUnsyncedData() {
        val editor = prefs.edit()
        editor.remove(KEY_UNSYNCED_STEPS)
        editor.remove(KEY_UNSYNCED_DISTANCE)
        editor.remove(KEY_UNSYNCED_CALORIES)
        editor.remove(KEY_UNSYNCED_ALTITUDE) // 고도 데이터 삭제
        editor.apply()
    }
}