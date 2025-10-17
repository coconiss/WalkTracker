package com.walktracker.app.util

import com.walktracker.app.model.ActivityType
import kotlin.math.roundToInt

object CalorieCalculator {

    /**
     * 활동 유형과 속도(m/s)에 따라 MET(Metabolic Equivalent of Task) 값을 반환합니다.
     *
     * @param activityType 활동 유형 (WALKING, RUNNING, STILL 등)
     * @param speedMps 초당 미터 속도
     * @return MET 값
     */
    private fun getMetValue(activityType: ActivityType, speedMps: Float): Double {
        return when (activityType) {
            ActivityType.STILL -> 1.0 // 휴식
            ActivityType.WALKING -> {
                when {
                    speedMps < 0.9 -> 2.0 // 매우 느린 걸음 (~3.2 km/h 미만)
                    speedMps < 1.3 -> 3.5 // 보통 걸음 (~4.7 km/h 미만)
                    speedMps < 1.8 -> 5.0 // 빠른 걸음 (~6.5 km/h 미만)
                    else -> 6.3           // 매우 빠른 걸음
                }
            }
            ActivityType.RUNNING -> {
                when {
                    speedMps < 2.2 -> 8.0  // 조깅 (~8 km/h 미만)
                    speedMps < 3.0 -> 11.0 // 달리기 (~10.8 km/h 미만)
                    speedMps < 4.5 -> 14.5 // 빠른 달리기 (~16 km/h 미만)
                    else -> 19.0           // 매우 빠른 달리기
                }
            }
            else -> 1.0 // 기타 활동은 휴식으로 처리
        }
    }

    /**
     * MET 공식을 사용하여 소모된 칼로리를 계산합니다.
     * 공식: (MET * 3.5 * 체중(kg)) / 200 * 활동 시간(분)
     *
     * @param weightKg 사용자 체중 (kg)
     * @param speedMps 초당 미터 속도
     * @param durationSeconds 활동 지속 시간 (초)
     * @param activityType 활동 유형
     * @return 소모 칼로리 (kcal)
     */
    fun calculate(
        weightKg: Double,
        speedMps: Float,
        durationSeconds: Long,
        activityType: ActivityType
    ): Double {
        if (durationSeconds <= 0 || weightKg <= 0) {
            return 0.0
        }

        val met = getMetValue(activityType, speedMps)
        val durationMinutes = durationSeconds / 60.0
        
        // 분당 소모 칼로리 = (MET * 3.5 * 체중) / 200
        val caloriesPerMinute = (met * 3.5 * weightKg) / 200.0

        return caloriesPerMinute * durationMinutes
    }
}