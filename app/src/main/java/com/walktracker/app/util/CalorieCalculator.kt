package com.walktracker.app.util

import com.walktracker.app.model.ActivityType

object CalorieCalculator {

    // MET (Metabolic Equivalent of Task) 값
    private const val MET_WALKING = 3.5
    private const val MET_RUNNING = 8.0

    /**
     * 칼로리 계산
     * @param weightKg 사용자 체중 (kg)
     * @param distanceKm 이동 거리 (km)
     * @param activityType 활동 유형
     * @return 소모 칼로리 (kcal)
     */
    fun calculate(
        weightKg: Double,
        distanceKm: Double,
        activityType: ActivityType
    ): Double {
        return when (activityType) {
            ActivityType.WALKING -> {
                // 걷기: 체중(kg) × 거리(km) × 1.05
                weightKg * distanceKm * 1.05
            }
            ActivityType.RUNNING -> {
                // 달리기: 체중(kg) × 거리(km) × 1.6
                weightKg * distanceKm * 1.6
            }
            else -> 0.0
        }
    }

    /**
     * 시간 기반 칼로리 계산 (보조 메서드)
     * @param weightKg 사용자 체중 (kg)
     * @param durationMinutes 활동 시간 (분)
     * @param activityType 활동 유형
     * @return 소모 칼로리 (kcal)
     */
    fun calculateByDuration(
        weightKg: Double,
        durationMinutes: Double,
        activityType: ActivityType
    ): Double {
        val met = when (activityType) {
            ActivityType.WALKING -> MET_WALKING
            ActivityType.RUNNING -> MET_RUNNING
            else -> 0.0
        }

        // 칼로리 = MET × 체중(kg) × 시간(시간)
        return met * weightKg * (durationMinutes / 60.0)
    }

    /**
     * 걸음수 기반 칼로리 추정
     * @param weightKg 사용자 체중 (kg)
     * @param steps 걸음수
     * @param strideLength 보폭 (미터, 기본값 0.7m)
     * @return 소모 칼로리 (kcal)
     */
    fun calculateFromSteps(
        weightKg: Double,
        steps: Long,
        strideLength: Double = 0.7
    ): Double {
        val distanceKm = (steps * strideLength) / 1000.0
        return calculate(weightKg, distanceKm, ActivityType.WALKING)
    }
}