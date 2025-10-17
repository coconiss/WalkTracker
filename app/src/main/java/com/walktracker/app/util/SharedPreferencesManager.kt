package com.walktracker.app.util

import android.content.Context
import android.content.SharedPreferences

class SharedPreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("WalkTrackerSyncPrefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_UNSYNCED_STEPS = "unsynced_steps"
        private const val KEY_UNSYNCED_DISTANCE = "unsynced_distance"
        private const val KEY_UNSYNCED_CALORIES = "unsynced_calories"
    }

    fun addUnsyncedData(steps: Long, distance: Double, calories: Double) {
        val editor = prefs.edit()
        editor.putLong(KEY_UNSYNCED_STEPS, prefs.getLong(KEY_UNSYNCED_STEPS, 0L) + steps)
        editor.putFloat(KEY_UNSYNCED_DISTANCE, prefs.getFloat(KEY_UNSYNCED_DISTANCE, 0f) + distance.toFloat())
        editor.putFloat(KEY_UNSYNCED_CALORIES, prefs.getFloat(KEY_UNSYNCED_CALORIES, 0f) + calories.toFloat())
        editor.apply()
    }

    fun getUnsyncedData(): Triple<Long, Double, Double> {
        val steps = prefs.getLong(KEY_UNSYNCED_STEPS, 0L)
        val distance = prefs.getFloat(KEY_UNSYNCED_DISTANCE, 0f).toDouble()
        val calories = prefs.getFloat(KEY_UNSYNCED_CALORIES, 0f).toDouble()
        return Triple(steps, distance, calories)
    }

    fun clearUnsyncedData() {
        val editor = prefs.edit()
        editor.remove(KEY_UNSYNCED_STEPS)
        editor.remove(KEY_UNSYNCED_DISTANCE)
        editor.remove(KEY_UNSYNCED_CALORIES)
        editor.apply()
    }
}