package com.walktracker.app.util

import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs

class AltitudeCalculator {

    companion object {
        private const val PRESSURE_TO_ALTITUDE_RATIO = 8.3
        private const val MIN_PRESSURE_CHANGE = 1.0f
        private const val MIN_ALTITUDE_CHANGE = 3.0
        private const val PRESSURE_FILTER_SIZE = 5
        private const val MAX_ALTITUDE_CHANGE_PER_SECOND = 3.0
        private const val TAG = "AltitudeCalculator"
    }

    private val pressureHistory = ArrayDeque<Float>(PRESSURE_FILTER_SIZE)
    private var lastValidAltitude: Double? = null
    private var lastValidTime: Long = 0L

    fun calculateAltitudeGain(
        currentPressure: Float,
        currentTime: Long,
        isMoving: Boolean
    ): Double {
        if (!isMoving) {
            return 0.0
        }

        if (currentPressure < 950f || currentPressure > 1050f) {
            Log.w(TAG, "비정상 기압 값: $currentPressure hPa")
            return 0.0
        }

        pressureHistory.addLast(currentPressure)
        if (pressureHistory.size > PRESSURE_FILTER_SIZE) {
            pressureHistory.removeFirst()
        }

        if (pressureHistory.size < PRESSURE_FILTER_SIZE) {
            return 0.0
        }

        val filteredPressure = pressureHistory.average().toFloat()

        if (lastValidAltitude == null) {
            lastValidAltitude = SensorManager.getAltitude(
                SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
                filteredPressure
            ).toDouble()
            lastValidTime = currentTime
            return 0.0
        }

        val previousPressure = pressureHistory.first()
        val pressureChange = abs(filteredPressure - previousPressure)

        if (pressureChange < MIN_PRESSURE_CHANGE) {
            return 0.0
        }

        val currentAltitude = SensorManager.getAltitude(
            SensorManager.PRESSURE_STANDARD_ATMOSPHERE,
            filteredPressure
        ).toDouble()

        val altitudeChange = currentAltitude - lastValidAltitude!!

        if (altitudeChange < MIN_ALTITUDE_CHANGE) {
            return 0.0
        }

        val timeDiffSeconds = (currentTime - lastValidTime) / 1000.0
        if (timeDiffSeconds > 0) {
            val changeRate = altitudeChange / timeDiffSeconds
            if (changeRate > MAX_ALTITUDE_CHANGE_PER_SECOND) {
                Log.w(TAG, "비정상적인 고도 변화율: ${changeRate}m/s")
                return 0.0
            }
        }

        lastValidAltitude = currentAltitude
        lastValidTime = currentTime

        Log.d(TAG, "고도 변화: +${String.format("%.1f", altitudeChange)}m")

        return altitudeChange
    }

    fun reset() {
        pressureHistory.clear()
        lastValidAltitude = null
        lastValidTime = 0L
    }
}