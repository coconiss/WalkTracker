package com.walktracker.app.util

import com.walktracker.app.model.ActivityType

object CalorieCalculator {

    private fun getMetValue(activityType: ActivityType, speedMps: Float): Double {
        return when (activityType) {
            ActivityType.STILL -> 1.0
            ActivityType.WALKING -> {
                when {
                    speedMps < 0.5 -> 1.0
                    speedMps < 0.9 -> 2.0
                    speedMps < 1.3 -> 3.5
                    speedMps < 1.8 -> 5.0
                    else -> 6.3
                }
            }
            ActivityType.RUNNING -> {
                when {
                    speedMps < 2.2 -> 8.0
                    speedMps < 3.0 -> 11.0
                    speedMps < 4.5 -> 14.5
                    else -> 19.0
                }
            }
            else -> 1.0
        }
    }

    /**
     * MET 공식을 사용하여 소모된 칼로리 계산
     * 공식: (MET * 3.5 * 체중(kg)) / 200 * 활동 시간(분)
     *
     * @param weightKg 사용자 체중 (kg)
     * @param speedMps 초당 미터 속도
     * @param durationSeconds 활동 지속 시간 (초)
     * @param activityType 활동 유형
     * @param elevationGainMeters 상승 고도 (현재 미사용 - 정확도 문제로 비활성화)
     * @return 소모 칼로리 (kcal)
     */
    fun calculate(
        weightKg: Double,
        speedMps: Float,
        durationSeconds: Long,
        activityType: ActivityType,
        elevationGainMeters: Double = 0.0
    ): Double {
        if (durationSeconds <= 0 || weightKg <= 0) {
            return 0.0
        }

        val met = getMetValue(activityType, speedMps)
        val durationMinutes = durationSeconds / 60.0
        val caloriesPerMinute = (met * 3.5 * weightKg) / 200.0
        val horizontalCalories = caloriesPerMinute * durationMinutes

        // 고도 기반 칼로리는 현재 비활성화 (정확도 개선 후 활성화 예정)
        // val verticalCalories = if (elevationGainMeters > 0) {
        //     weightKg * elevationGainMeters * 0.0117
        // } else {
        //     0.0
        // }

        return horizontalCalories
    }
}