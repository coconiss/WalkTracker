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
                    speedMps < 0.5 -> 1.0 // 사실상 가만히..(~1.8 km/h 미만)
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
     * 상승 고도를 고려하여 추가 칼로리를 더합니다.
     *
     * @param weightKg 사용자 체중 (kg)
     * @param speedMps 초당 미터 속도
     * @param durationSeconds 활동 지속 시간 (초)
     * @param activityType 활동 유형
     * @param elevationGainMeters 상승 고도 (미터)
     * @return 소모 칼로리 (kcal)
     */
    fun calculate(
        weightKg: Double,
        speedMps: Float,
        durationSeconds: Long,
        activityType: ActivityType,
        elevationGainMeters: Double = 0.0 // 기본값 0으로 호환성 유지
    ): Double {
        if (durationSeconds <= 0 || weightKg <= 0) {
            return 0.0
        }

        // 수평 이동에 대한 칼로리 계산
        val met = getMetValue(activityType, speedMps)
        val durationMinutes = durationSeconds / 60.0
        val caloriesPerMinute = (met * 3.5 * weightKg) / 200.0
        val horizontalCalories = caloriesPerMinute * durationMinutes

        // 상승에 대한 추가 칼로리 계산(고도계 고치기 전 까지는 사용 못함)
        // 일(J) = m * g * h. 칼로리(kcal) = J / 4184.
        // 인체 효율 20% 가정 시: (m * g * h) / (4184 * 0.20)
        // = (weightKg * 9.8 * elevationGainMeters) / 836.8
        // = weightKg * elevationGainMeters * 0.0117
        //val verticalCalories = if (elevationGainMeters > 0) {
        //    weightKg * elevationGainMeters * 0.0117
        //} else {
        //    0.0
        //}

        return horizontalCalories //+ verticalCalories
    }
}