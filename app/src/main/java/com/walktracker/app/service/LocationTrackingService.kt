package com.walktracker.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.*
import com.walktracker.app.MainActivity
import com.walktracker.app.R
import com.walktracker.app.model.ActivityType
import com.walktracker.app.model.RoutePoint
import com.walktracker.app.repository.FirebaseRepository
import com.walktracker.app.util.AltitudeCalculator
import com.walktracker.app.util.CalorieCalculator
import com.walktracker.app.util.SharedPreferencesManager
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/**
 * 백그라운드에서 위치 및 활동 데이터를 추적하는 서비스입니다.
 * 걸음 수, 이동 거리, 칼로리, 높이, 이동 경로 등을 수집하고 주기적으로 Firebase에 동기화합니다.
 */
class LocationTrackingService : Service(), SensorEventListener {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val repository = FirebaseRepository()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private var pressureSensor: Sensor? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var activityRecognitionClient: ActivityRecognitionClient

    private lateinit var syncPrefs: SharedPreferencesManager

    private val altitudeCalculator = AltitudeCalculator()

    // --- 센서 활성화 상태 ---
    private var isGpsEnabled = true
    private var isStepSensorEnabled = true
    private var isPressureSensorEnabled = true

    // --- 일일 누적 데이터 ---
    private var stepsAtStartOfDay = 0L
    private var distanceAtStartOfDay = 0.0
    private var caloriesAtStartOfDay = 0.0
    private var altitudeAtStartOfDay = 0.0

    // --- 현재 세션 데이터 ---
    private var initialStepCount = 0L
    private var currentSteps = 0L
    private var totalDistance = 0.0
    private var totalCalories = 0.0
    private var totalAltitudeGain = 0.0
    private var currentSpeed = 0.0f
    private var isInitialDataLoaded = false
    private var lastProcessedDate: String = ""

    // --- 속도 추적 개선 ---
    private var lastSpeedUpdateTime = 0L
    private var lastValidMovementTime = 0L

    // --- 위치 및 활동 관련 데이터 ---
    private var previousLocation: Location? = null
    private var currentActivityType = ActivityType.WALKING
    private var previousActivityType = ActivityType.UNKNOWN
    private var activityTransitionTime = 0L
    private var userWeight = 70.0
    private var userStride = 0.7

    private val routePoints = mutableListOf<RoutePoint>()

    private var lastPressureValue = 0f
    private var pressureAtPreviousLocation: Float? = null

    // --- 동기화 관련 데이터 ---
    private var stepsSynced: Long = 0
    private var distanceSynced: Double = 0.0
    private var caloriesSynced: Double = 0.0
    private var altitudeSynced: Double = 0.0

    // --- 배터리 최적화 관련 ---
    private var lastActivityTime = System.currentTimeMillis()
    private var isStillMode = false
    private var transitionLocationBuffer = mutableListOf<Location>()

    // 배터리 최적화: UI 브로드캐스트 throttling
    private var lastBroadcastTime = 0L

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "WalkTrackerChannel"

        // 주기적 동기화 간격 (5분) 실제 배포 시 변경 해야 함.
        private const val SYNC_INTERVAL = 300000L

        // 위치 업데이트 간격
        private const val LOCATION_INTERVAL_WALKING = 20000L
        private const val LOCATION_INTERVAL_RUNNING = 15000L
        private const val LOCATION_INTERVAL_STILL = 120000L

        // 거리/속도 임계값
        private const val MIN_DISTANCE_THRESHOLD = 5.0
        private const val MAX_SPEED_MPS = 5.0  // 18km/h - 달리기 최고 속도
        private const val MAX_WALKING_SPEED_MPS = 2.2f  // 8km/h - 빠른 걷기 최고 속도
        private const val MIN_WALKING_SPEED_MPS = 0.5f  // 2.9km/h - 최소 걷기 속도
        private const val MAX_TIME_DIFFERENCE_SECONDS = 60L
        private const val MIN_ACCURACY_METERS = 50f

        // 속도 타임아웃: 이 시간 동안 이동이 없으면 속도를 0으로 리셋
        private const val SPEED_TIMEOUT_MS = 10000L  // 10초

        // 절전 모드 진입 시간
        private const val STILL_DETECTION_TIME = 120000L

        // 활동 전환 안정화 시간
        private const val TRANSITION_STABILIZATION_TIME = 20000L
        private const val TRANSITION_BUFFER_SIZE = 3

        // 기압 변화 임계값 (hPa)
        private const val PRESSURE_CHANGE_THRESHOLD_WALKING = 0.2f
        private const val PRESSURE_CHANGE_THRESHOLD_RUNNING = 0.3f

        // UI 업데이트 throttle
        private const val BROADCAST_THROTTLE_MS = 3000L

        private const val TAG = "LocationTrackingService"

        const val ACTION_ACTIVITY_UPDATE = "com.walktracker.app.ACTIVITY_UPDATE"
        const val ACTION_RESET_TODAY_DATA = "com.walktracker.app.RESET_TODAY_DATA"
        const val EXTRA_STEPS = "extra_steps"
        const val EXTRA_DISTANCE = "extra_distance"
        const val EXTRA_CALORIES = "extra_calories"
        const val EXTRA_ALTITUDE = "extra_altitude"
        const val EXTRA_SPEED = "extra_speed"

        const val ACTION_LOCATION_UPDATE = "com.walktracker.app.LOCATION_UPDATE"
        const val ACTION_REQUEST_LOCATION_UPDATE = "com.walktracker.app.REQUEST_LOCATION_UPDATE"
        const val EXTRA_LOCATION = "extra_location"
        const val ACTION_SENSOR_SETTINGS_CHANGED = "com.walktracker.app.SENSOR_SETTINGS_CHANGED"
        const val ACTION_USER_DATA_CHANGED = "com.walktracker.app.USER_DATA_CHANGED"
    }

    private val userDataChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USER_DATA_CHANGED) {
                Log.d(TAG, "사용자 데이터 변경 수신")
                loadUserData()
            }
        }
    }

    private val sensorSettingsChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SENSOR_SETTINGS_CHANGED) {
                Log.d(TAG, "센서 설정 변경 수신")
                loadSensorSettings()
                updateSensorRegistrations()
            }
        }
    }

    private val activityTypeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val typeString = intent?.getStringExtra(ActivityRecognitionReceiver.EXTRA_ACTIVITY_TYPE)
            val newActivityType = when (typeString) {
                "WALKING" -> ActivityType.WALKING
                "RUNNING" -> ActivityType.RUNNING
                "VEHICLE" -> ActivityType.VEHICLE
                "STILL" -> ActivityType.STILL
                else -> ActivityType.WALKING
            }

            Log.d(TAG, "활동 유형 수신: $typeString -> $newActivityType")

            if (newActivityType != currentActivityType) {
                handleActivityTransition(currentActivityType, newActivityType)
                previousActivityType = currentActivityType
                currentActivityType = newActivityType
                activityTransitionTime = System.currentTimeMillis()
                updateLocationRequest()
            }
        }
    }

    private val locationRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REQUEST_LOCATION_UPDATE) {
                Log.d(TAG, "UI로부터 위치 업데이트 요청 수신")
                broadcastLocationUpdate()
                broadcastActivityUpdate(forceImmediate = true)
            }
        }
    }

    private val resetDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RESET_TODAY_DATA) {
                Log.d(TAG, "오늘 데이터 리셋 요청 수신")
                resetTodayData()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lastProcessedDate = dateFormat.format(Date())
        Log.d(TAG, "========== 서비스 생성 (onCreate) ==========")

        syncPrefs = SharedPreferencesManager(this)

        loadSensorSettings()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        Log.d(TAG, "걸음수 센서: ${if (stepSensor != null) "사용 가능" else "없음"}")
        Log.d(TAG, "기압 센서: ${if (pressureSensor != null) "사용 가능" else "없음"}")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        activityRecognitionClient = ActivityRecognition.getClient(this)

        loadInitialDailyData()
        loadUserData()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        updateSensorRegistrations()

        val localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.registerReceiver(
            activityTypeReceiver,
            IntentFilter(ActivityRecognitionReceiver.ACTION_ACTIVITY_TYPE_UPDATE)
        )
        localBroadcastManager.registerReceiver(
            locationRequestReceiver,
            IntentFilter(ACTION_REQUEST_LOCATION_UPDATE)
        )
        localBroadcastManager.registerReceiver(
            resetDataReceiver,
            IntentFilter(ACTION_RESET_TODAY_DATA)
        )
        localBroadcastManager.registerReceiver(
            sensorSettingsChangeReceiver,
            IntentFilter(ACTION_SENSOR_SETTINGS_CHANGED)
        )
        localBroadcastManager.registerReceiver(
            userDataChangeReceiver,
            IntentFilter(ACTION_USER_DATA_CHANGED)
        )

        Log.d(TAG, "브로드캐스트 리시버 등록 완료")

        startLocationTracking()
        startActivityRecognition()
        startPeriodicSync()
        startStillDetection()
        startSpeedMonitoring()  // 속도 모니터링 시작

        Log.d(TAG, "========== 서비스 초기화 완료 ==========")
    }

    private fun loadSensorSettings() {
        isGpsEnabled = syncPrefs.isGpsEnabled()
        isStepSensorEnabled = syncPrefs.isStepSensorEnabled()
        isPressureSensorEnabled = syncPrefs.isPressureSensorEnabled()

        // GPS와 걸음 센서 둘 다 꺼지는 것을 방지
        if (!isGpsEnabled && !isStepSensorEnabled) {
            isGpsEnabled = true
            syncPrefs.setGpsEnabled(true)
            Log.w(TAG, "GPS와 걸음 센서가 모두 비활성화되어 GPS를 강제로 활성화합니다.")
        }

        Log.d(TAG, "센서 설정 로드 - GPS: $isGpsEnabled, 걸음: $isStepSensorEnabled, 기압: $isPressureSensorEnabled")
    }

    private fun updateSensorRegistrations() {
        // 걸음 센서
        stepSensor?.let {
            if (isStepSensorEnabled) {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                Log.d(TAG, "걸음수 센서 리스너 등록")
            } else {
                sensorManager.unregisterListener(this, it)
                Log.d(TAG, "걸음수 센서 리스너 해제")
            }
        }

        // 기압 센서
        pressureSensor?.let {
            if (isPressureSensorEnabled) {
                val samplingPeriodUs = (getUpdateInterval() * 1000).toInt()
                sensorManager.registerListener(this, it, samplingPeriodUs)
                Log.d(TAG, "기압 센서 리스너 등록")
            } else {
                sensorManager.unregisterListener(this, it)
                Log.d(TAG, "기압 센서 리스너 해제")
            }
        }

        // 위치 업데이트 (GPS)
        updateLocationRequest()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "서비스 종료 (onDestroy)")

        serviceScope.launch { syncToFirebase() }

        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)

        val localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.unregisterReceiver(activityTypeReceiver)
        localBroadcastManager.unregisterReceiver(locationRequestReceiver)
        localBroadcastManager.unregisterReceiver(resetDataReceiver)
        localBroadcastManager.unregisterReceiver(sensorSettingsChangeReceiver)
        localBroadcastManager.unregisterReceiver(userDataChangeReceiver)

        resetAllData()

        serviceScope.cancel()
    }

    private fun resetAllData() {
        stepsAtStartOfDay = 0L
        distanceAtStartOfDay = 0.0
        caloriesAtStartOfDay = 0.0
        altitudeAtStartOfDay = 0.0
        currentSteps = 0L
        totalDistance = 0.0
        totalCalories = 0.0
        totalAltitudeGain = 0.0
        resetSpeed()
        initialStepCount = 0L
        lastPressureValue = 0f
        pressureAtPreviousLocation = null
        previousLocation = null
        routePoints.clear()
        syncPrefs.clearUnsyncedData()
        altitudeCalculator.reset()
        stepsSynced = 0L
        distanceSynced = 0.0
        caloriesSynced = 0.0
        altitudeSynced = 0.0
        isInitialDataLoaded = false
    }

    /**
     * 속도를 0으로 리셋하고 타임스탬프 업데이트
     */
    private fun resetSpeed() {
        currentSpeed = 0.0f
        lastSpeedUpdateTime = System.currentTimeMillis()
        lastValidMovementTime = 0L
        Log.d(TAG, "속도 리셋: 0.0 m/s")
    }

    /**
     * 주기적으로 속도를 모니터링하여 타임아웃 시 0으로 리셋
     */
    private fun startSpeedMonitoring() {
        serviceScope.launch {
            Log.d(TAG, "속도 모니터링 시작")
            while (isActive) {
                delay(2000L)  // 2초마다 체크
                checkSpeedTimeout()
            }
        }
    }

    /**
     * 속도 타임아웃 체크: 일정 시간 이동이 없으면 속도를 0으로
     */
    private fun checkSpeedTimeout() {
        if (currentSpeed > 0) {
            val timeSinceLastUpdate = System.currentTimeMillis() - lastSpeedUpdateTime
            if (timeSinceLastUpdate > SPEED_TIMEOUT_MS) {
                Log.d(TAG, "속도 타임아웃 (${timeSinceLastUpdate}ms) - 속도를 0으로 리셋")
                resetSpeed()
                updateAndBroadcast()
            }
        }
    }

    private fun startPeriodicSync() {
        serviceScope.launch {
            Log.d(TAG, "주기적 동기화 시작 (${SYNC_INTERVAL / 1000}초마다)")
            while (isActive) {
                delay(SYNC_INTERVAL)
                Log.d(TAG, "주기적 동기화 실행")
                syncToFirebase()
            }
        }
    }

    private suspend fun syncToFirebase(dateToSync: String? = null) {
        val userId = repository.getCurrentUserId()
        if (userId == null) {
            Log.e(TAG, "사용자 ID 없음 - 동기화 불가")
            return
        }
        val date = dateToSync ?: dateFormat.format(Date())

        val stepsIncrement = currentSteps - stepsSynced
        val distanceIncrement = totalDistance - distanceSynced
        val caloriesIncrement = totalCalories - caloriesSynced
        val altitudeIncrement = totalAltitudeGain - altitudeSynced

        Log.d(TAG, "동기화 증분 - 걸음:$stepsIncrement, 거리:${"%.3f".format(distanceIncrement)}km")

        val unsyncedData = syncPrefs.getUnsyncedData()
        val unsyncedSteps = unsyncedData["steps"] as? Long ?: 0L
        val unsyncedDistance = unsyncedData["distance"] as? Double ?: 0.0
        val unsyncedCalories = unsyncedData["calories"] as? Double ?: 0.0
        val unsyncedAltitude = unsyncedData["altitude"] as? Double ?: 0.0

        val totalStepsToSync = stepsIncrement + unsyncedSteps
        val totalDistanceToSync = distanceIncrement + unsyncedDistance
        val totalCaloriesToSync = caloriesIncrement + unsyncedCalories
        val totalAltitudeToSync = altitudeIncrement + unsyncedAltitude
        val routesToSync = routePoints.toList()

        if (totalStepsToSync > 0 || totalDistanceToSync > 0 || routesToSync.isNotEmpty()) {
            Log.d(TAG, "Firebase 동기화 시작")

            val result = repository.incrementDailyActivity(
                userId = userId,
                date = date,
                steps = totalStepsToSync,
                distance = totalDistanceToSync,
                calories = totalCaloriesToSync,
                altitude = totalAltitudeToSync,
                routes = routesToSync
            )

            if (result.isSuccess) {
                Log.d(TAG, "✓ Firebase 동기화 성공")
                syncPrefs.clearUnsyncedData()
                routePoints.clear()
                stepsSynced = currentSteps
                distanceSynced = totalDistance
                caloriesSynced = totalCalories
                altitudeSynced = totalAltitudeGain
            } else {
                Log.e(TAG, "✗ Firebase 동기화 실패", result.exceptionOrNull())
                syncPrefs.addUnsyncedData(stepsIncrement, distanceIncrement, caloriesIncrement, altitudeIncrement)
            }
        } else {
            Log.d(TAG, "동기화할 데이터 없음")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        lastActivityTime = System.currentTimeMillis()

        if (currentActivityType == ActivityType.VEHICLE) {
            return
        }

        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                if (!isStepSensorEnabled) return
                val totalStepsFromBoot = event.values[0].toLong()
                if (initialStepCount == 0L) {
                    initialStepCount = totalStepsFromBoot
                    Log.d(TAG, "초기 걸음수 설정: $initialStepCount")
                }
                currentSteps = totalStepsFromBoot - initialStepCount
                Log.d(TAG, "걸음 센서 - 현재: $currentSteps")
                updateAndBroadcast()
            }
            Sensor.TYPE_PRESSURE -> {
                if (!isPressureSensorEnabled) return
                val pressure = event.values[0]
                if (pressure > 800f && pressure < 1100f) {
                    lastPressureValue = pressure
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "센서 정확도 변경: ${sensor?.name}, accuracy=$accuracy")
    }

    private fun handleActivityTransition(from: ActivityType, to: ActivityType) {
        Log.d(TAG, "========== 활동 전환: $from -> $to ==========")

        when {
            to == ActivityType.STILL -> {
                resetSpeed()
                broadcastActivityUpdate(forceImmediate = true)
            }
            // 차량 모드로 전환 시 데이터 리셋
            to == ActivityType.VEHICLE -> {
                Log.d(TAG, "→차량: 데이터 리셋")
                previousLocation = null
                transitionLocationBuffer.clear()
                resetSpeed()
                altitudeCalculator.reset()
                pressureAtPreviousLocation = null
            }
            // 차량에서 도보로 전환 시 데이터 리셋
            from == ActivityType.VEHICLE &&
                    (to == ActivityType.WALKING || to == ActivityType.RUNNING) -> {
                Log.d(TAG, "차량→도보: 데이터 리셋")
                previousLocation = null
                transitionLocationBuffer.clear()
                resetSpeed()
                altitudeCalculator.reset()
                pressureAtPreviousLocation = null
            }
            from == ActivityType.STILL &&
                    (to == ActivityType.WALKING || to == ActivityType.RUNNING) -> {
                Log.d(TAG, "정지→활동: 전환 버퍼 리셋")
                transitionLocationBuffer.clear()
                resetSpeed()
            }
        }
    }

    private fun isInTransitionPeriod(): Boolean {
        return System.currentTimeMillis() - activityTransitionTime < TRANSITION_STABILIZATION_TIME
    }

    private fun handleNewLocation(location: Location) {
        if (!isGpsEnabled) {
            Log.d(TAG, "GPS 비활성화 상태 - 위치 업데이트 무시")
            return
        }

        Log.d(TAG, "---------- 새 위치 수신 ----------")
        Log.d(TAG, "위도:${location.latitude}, 경도:${location.longitude}, 정확도:${location.accuracy}m")

        if (location.hasAccuracy() && location.accuracy > MIN_ACCURACY_METERS) {
            if (location.speed < 0.2f) {
                Log.d(TAG, "정확도 낮고 속도 느림 - 무시")
                previousLocation = location
                return
            }
        }

        if (currentActivityType == ActivityType.VEHICLE) {
            Log.d(TAG, "차량 모드 - 위치만 업데이트, 속도는 0으로 유지")
            previousLocation = location
            if (currentSpeed != 0.0f) {
                resetSpeed()
                updateAndBroadcast()
            }
            broadcastLocationUpdate()
            return
        }

        val currentDate = dateFormat.format(Date())
        if (lastProcessedDate.isNotEmpty() && lastProcessedDate != currentDate) {
            Log.d(TAG, "날짜 변경: $lastProcessedDate -> $currentDate")
            serviceScope.launch {
                syncToFirebase(lastProcessedDate)
                resetTodayData()
                loadInitialDailyData()
            }
        }
        lastProcessedDate = currentDate

        if (isInTransitionPeriod()) {
            transitionLocationBuffer.add(location)
            if (transitionLocationBuffer.size > TRANSITION_BUFFER_SIZE) {
                transitionLocationBuffer.removeAt(0)
            }
            Log.d(TAG, "전환 안정화 중")
            previousLocation = location
            broadcastLocationUpdate()
            return
        }

        if (previousLocation == null) {
            previousLocation = location
            pressureAtPreviousLocation = lastPressureValue
            Log.d(TAG, "시작 위치 설정")
            broadcastLocationUpdate()
            return
        }

        val prev = previousLocation!!
        val timeDifferenceSeconds = (location.time - prev.time) / 1000

        Log.d(TAG, "시간차: ${timeDifferenceSeconds}초")

        if (timeDifferenceSeconds <= 0) return

        if (timeDifferenceSeconds > MAX_TIME_DIFFERENCE_SECONDS) {
            Log.d(TAG, "시간차 초과 - 리셋")
            previousLocation = null
            pressureAtPreviousLocation = null
            resetSpeed()
            broadcastLocationUpdate()
            updateAndBroadcast()
            previousLocation = location
            return
        }

        val distance = prev.distanceTo(location)
        Log.d(TAG, "이동 거리: ${"%.2f".format(distance)}m")

        if (distance < MIN_DISTANCE_THRESHOLD) {
            Log.d(TAG, "거리 부족 - 무시. 다음 계산을 위해 위치는 업데이트합니다.")
            previousLocation = location
            // 거리가 부족하면 정지 상태로 간주하고 속도 감소
            if (currentSpeed > 0) {
                val timeSinceLastMovement = System.currentTimeMillis() - lastValidMovementTime
                if (timeSinceLastMovement > SPEED_TIMEOUT_MS / 2) {  // 5초 후
                    Log.d(TAG, "유효한 이동 없음 - 속도를 0으로 설정")
                    resetSpeed()
                    updateAndBroadcast()
                }
            }
            return
        }

        // GPS 점프를 감지하기 위해 원시 속도를 먼저 계산합니다.
        val rawSpeed = if (timeDifferenceSeconds > 0) (distance / timeDifferenceSeconds) else 0f
        Log.d(TAG, "원시 속도: ${"%.2f".format(rawSpeed)}m/s")

        // GPS 점프 필터링 1단계: 물리적으로 불가능한 속도
        if (rawSpeed > MAX_SPEED_MPS) {
            Log.d(TAG, "최대 속도 초과 (GPS 점프 감지) - 데이터를 리셋합니다.")
            previousLocation = null
            pressureAtPreviousLocation = null
            broadcastLocationUpdate()
            previousLocation = location
            return
        }

        // GPS 점프 필터링 2단계: 활동 유형별 속도 검증
        if (currentActivityType == ActivityType.WALKING && rawSpeed > MAX_WALKING_SPEED_MPS) {
            Log.d(TAG, "걷기 모드에서 비정상적 속도 감지 (${"%.2f".format(rawSpeed)}m/s) - 무시")
            previousLocation = null
            pressureAtPreviousLocation = null
            broadcastLocationUpdate()
            previousLocation = location
            return
        }

        // GPS 점프가 아닌 것으로 확인된 후에만 속도를 보정(smoothing)합니다.
        val speed = smoothSpeed(rawSpeed)
        Log.d(TAG, "보정 속도: ${"%.2f".format(speed)}m/s")

        if (currentActivityType == ActivityType.WALKING && speed < MIN_WALKING_SPEED_MPS) {
            Log.d(TAG, "걷기 속도 미달 - 속도를 0으로 설정")
            previousLocation = null
            pressureAtPreviousLocation = null
            resetSpeed()
            broadcastLocationUpdate()
            updateAndBroadcast()
            previousLocation = location
            return
        }

        Log.d(TAG, "========== 유효한 이동 - 데이터 업데이트 ==========")

        lastActivityTime = System.currentTimeMillis()
        lastValidMovementTime = System.currentTimeMillis()
        currentSpeed = speed
        lastSpeedUpdateTime = System.currentTimeMillis()
        totalDistance += distance / 1000.0

        Log.d(TAG, "누적 거리: ${"%.3f".format(totalDistance)}km, 현재 속도: ${"%.2f".format(currentSpeed)}m/s")

        val isMoving = speed > MIN_WALKING_SPEED_MPS
        if (isPressureSensorEnabled && pressureSensor != null && lastPressureValue > 0f && pressureAtPreviousLocation != null) {
            val pressureChange = abs(lastPressureValue - pressureAtPreviousLocation!!)
            val threshold = when(currentActivityType) {
                ActivityType.RUNNING -> PRESSURE_CHANGE_THRESHOLD_RUNNING
                else -> PRESSURE_CHANGE_THRESHOLD_WALKING
            }

            if (pressureChange < threshold) {
                val altitudeGain = altitudeCalculator.calculateAltitudeGain(
                    currentPressure = lastPressureValue,
                    currentTime = location.time,
                    isMoving = isMoving
                )
                if (altitudeGain > 0) {
                    totalAltitudeGain += altitudeGain
                } else{
                    totalAltitudeGain += altitudeGain * -1
                }
            } else {
                Log.w(TAG, "급격한 기압 변화 감지, 높이 계산에서 제외: $pressureChange hPa")
            }
        }

        if (!isStepSensorEnabled && userStride > 0) {
            currentSteps = (totalDistance * 1000 / userStride).toLong()
        }

        val caloriesBurned = CalorieCalculator.calculate(
            weightKg = userWeight,
            speedMps = speed,
            durationSeconds = timeDifferenceSeconds,
            activityType = currentActivityType,
            elevationGainMeters = 0.0
        )
        totalCalories += caloriesBurned

        routePoints.add(RoutePoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = location.time,
            activityType = currentActivityType.name,
            speed = speed.toDouble()
        ))
        Log.d(TAG, "경로 포인트 추가 (총 ${routePoints.size}개)")

        updateAndBroadcast()
        previousLocation = location
        pressureAtPreviousLocation = lastPressureValue
        broadcastLocationUpdate()
    }

    private fun smoothSpeed(calculatedSpeed: Float): Float {
        // 속도 스무딩: 급격한 속도 변화를 제한하여 측정치를 안정화시킵니다.
        // 이 함수는 GPS 점프가 이미 필터링된 후에 호출되어야 합니다.
        if (currentSpeed > 0) {
            val speedDiff = abs(calculatedSpeed - currentSpeed)
            // 초당 1.0m/s (3.6km/h) 이상 속도 변화는 비정상으로 간주 (가속도 제한)
            val maxSpeedChange = 1.0f

            if (speedDiff > maxSpeedChange) {
                return if (calculatedSpeed > currentSpeed) {
                    currentSpeed + maxSpeedChange
                } else {
                    (currentSpeed - maxSpeedChange).coerceAtLeast(0f)
                }
            }
        }
        return calculatedSpeed
    }

    private fun resetTodayData() {
        Log.d(TAG, "========== 오늘 데이터 리셋 ==========")
        stepsAtStartOfDay = 0L
        distanceAtStartOfDay = 0.0
        caloriesAtStartOfDay = 0.0
        altitudeAtStartOfDay = 0.0
        currentSteps = 0L
        totalDistance = 0.0
        totalCalories = 0.0
        totalAltitudeGain = 0.0
        stepsSynced = 0L
        distanceSynced = 0.0
        caloriesSynced = 0.0
        altitudeSynced = 0.0
        initialStepCount = 0L
        lastPressureValue = 0f
        pressureAtPreviousLocation = null
        previousLocation = null
        routePoints.clear()
        syncPrefs.clearUnsyncedData()
        altitudeCalculator.reset()
        resetSpeed()
        updateAndBroadcast()
    }

    /**
     * 배터리 최적화: throttled broadcast
     */
    private fun updateAndBroadcast() {
        if (!isInitialDataLoaded) return
        updateNotification()
        broadcastActivityUpdate(forceImmediate = false)
    }

    /**
     * 배터리 최적화: UI 업데이트 throttling 적용
     */
    private fun broadcastActivityUpdate(forceImmediate: Boolean) {
        val now = System.currentTimeMillis()

        // 강제 즉시 전송이거나, throttle 시간이 지난 경우에만 브로드캐스트
        if (forceImmediate || (now - lastBroadcastTime >= BROADCAST_THROTTLE_MS)) {
            val totalStepsToday = stepsAtStartOfDay + currentSteps
            val totalDistanceToday = distanceAtStartOfDay + totalDistance
            val totalCaloriesToday = caloriesAtStartOfDay + totalCalories
            val totalAltitudeToday = altitudeAtStartOfDay + totalAltitudeGain

            val intent = Intent(ACTION_ACTIVITY_UPDATE).apply {
                putExtra(EXTRA_STEPS, totalStepsToday)
                putExtra(EXTRA_DISTANCE, totalDistanceToday)
                putExtra(EXTRA_CALORIES, totalCaloriesToday)
                putExtra(EXTRA_ALTITUDE, totalAltitudeToday)
                putExtra(EXTRA_SPEED, currentSpeed)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            lastBroadcastTime = now

            Log.d(TAG, "UI 업데이트 브로드캐스트 - 속도: ${"%.2f".format(currentSpeed)}m/s")
        }
    }

    private fun broadcastLocationUpdate() {
        previousLocation?.let {
            val intent = Intent(ACTION_LOCATION_UPDATE).apply {
                putExtra(EXTRA_LOCATION, it)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }

    private fun loadUserData() {
        serviceScope.launch {
            val user = repository.getCurrentUser()
            user?.let {
                userWeight = it.weight
                Log.d(TAG, "사용자 데이터 - 체중:${userWeight}kg")
            }
            userStride = syncPrefs.getUserStride()
            Log.d(TAG, "사용자 데이터 - 보폭:${userStride}m")
        }
    }

    private fun loadInitialDailyData() {
        serviceScope.launch {
            val userId = repository.getCurrentUserId()
            if (userId == null) {
                isInitialDataLoaded = true
                return@launch
            }
            val date = dateFormat.format(Date())

            repository.getDailyActivityOnce(userId, date) { activity ->
                activity?.let {
                    Log.d(TAG, "초기 데이터 로드: 걸음=${it.steps}, 거리=${it.distance}km")
                    stepsAtStartOfDay = it.steps
                    distanceAtStartOfDay = it.distance
                    caloriesAtStartOfDay = it.calories
                    altitudeAtStartOfDay = it.altitude
                }
                isInitialDataLoaded = true
                updateAndBroadcast()
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "걷기 추적", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val totalStepsToday = stepsAtStartOfDay + currentSteps
        val totalDistanceToday = distanceAtStartOfDay + totalDistance

        val contentText = if (isStillMode) {
            "절전 모드 - 오늘: ${totalStepsToday}걸음"
        } else {
            String.format(Locale.US, "오늘: %d걸음 • %.2fkm • %.1fkm/h",
                totalStepsToday, totalDistanceToday, currentSpeed * 3.6)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (isStillMode) "걷기 추적 (절전)" else "걷기 추적 중")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_splash_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification())
    }

    private fun startLocationTracking() {
        Log.d(TAG, "위치 추적 시작")
        updateLocationRequest()
    }

    private fun getUpdateInterval(): Long {
        return when (currentActivityType) {
            ActivityType.RUNNING -> LOCATION_INTERVAL_RUNNING
            ActivityType.WALKING -> LOCATION_INTERVAL_WALKING
            ActivityType.STILL -> LOCATION_INTERVAL_STILL
            else -> LOCATION_INTERVAL_WALKING
        }
    }

    /**
     * 배터리 최적화: 위치 업데이트 간격 조정
     */
    private fun updateLocationRequest() {
        fusedLocationClient.removeLocationUpdates(locationCallback)

        if (!isGpsEnabled || currentActivityType == ActivityType.VEHICLE) {
            Log.d(TAG, "GPS 비활성화 또는 차량 모드. 위치 업데이트 중지.")
            if (currentActivityType == ActivityType.VEHICLE || !isGpsEnabled && !isStepSensorEnabled) {
                enterStillMode()
            }
            return
        }

        val (priority, interval) = when (currentActivityType) {
            ActivityType.RUNNING -> Priority.PRIORITY_HIGH_ACCURACY to LOCATION_INTERVAL_RUNNING
            ActivityType.WALKING -> Priority.PRIORITY_HIGH_ACCURACY to LOCATION_INTERVAL_WALKING
            ActivityType.STILL -> Priority.PRIORITY_BALANCED_POWER_ACCURACY to LOCATION_INTERVAL_STILL
            else -> Priority.PRIORITY_HIGH_ACCURACY to LOCATION_INTERVAL_WALKING
        }

        Log.d(TAG, "위치 요청 - 활동:$currentActivityType, 간격:${interval / 1000}초")

        val locationRequest = LocationRequest.Builder(priority, interval).apply {
            setMinUpdateIntervalMillis(interval / 2)
            setMaxUpdateDelayMillis(interval * 2)
            setWaitForAccurateLocation(false)
            if (currentActivityType != ActivityType.RUNNING) {
                setMinUpdateDistanceMeters(MIN_DISTANCE_THRESHOLD.toFloat())
            }
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "✓ 위치 업데이트 요청 성공")
        } catch (e: SecurityException) {
            Log.e(TAG, "✗ 위치 권한 없음", e)
            stopSelf()
        }

        exitStillMode()
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                Log.d(TAG, "LocationCallback - 위치 수신")
                handleNewLocation(it)
            }
        }
    }

    private fun startActivityRecognition() {
        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.WALKING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.RUNNING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.STILL)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        )

        val request = ActivityTransitionRequest(transitions)
        val intent = Intent(this, ActivityRecognitionReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        try {
            activityRecognitionClient.requestActivityTransitionUpdates(request, pendingIntent)
            Log.d(TAG, "✓ 활동 인식 시작")
        } catch (e: SecurityException) {
            Log.e(TAG, "✗ 활동 인식 권한 없음", e)
        }
    }

    /**
     * 배터리 최적화: 절전 감지 시간 단축 (5분 -> 2분)
     */
    private fun startStillDetection() {
        serviceScope.launch {
            Log.d(TAG, "정지 감지 루프 시작")
            while (isActive) {
                delay(60000L) // 1분마다 확인
                val timeSinceLastActivity = System.currentTimeMillis() - lastActivityTime
                if (timeSinceLastActivity > STILL_DETECTION_TIME && !isStillMode) {
                    Log.d(TAG, "정지 상태 감지")
                    enterStillMode()
                }
            }
        }
    }

    /**
     * 배터리 최적화: 절전 모드 진입
     * - 위치 업데이트 간격 증가 (90초)
     * - 우선순위를 저전력 모드로 변경
     */
    private fun enterStillMode() {
        if (isStillMode) return
        isStillMode = true
        Log.d(TAG, "========== 절전 모드 진입 ==========")

        if(isPressureSensorEnabled) pressureSensor?.let { sensorManager.unregisterListener(this, it) }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_LOW_POWER,
            LOCATION_INTERVAL_STILL
        ).apply {
            setMinUpdateIntervalMillis(LOCATION_INTERVAL_STILL / 2)
            setMaxUpdateDelayMillis(LOCATION_INTERVAL_STILL * 2)
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "절전 모드 위치 권한 오류", e)
        }

        updateNotification()
    }

    /**
     * 배터리 최적화: 절전 모드 해제
     */
    private fun exitStillMode() {
        if (!isStillMode) return
        isStillMode = false
        Log.d(TAG, "========== 절전 모드 해제 ==========")

        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand 호출")
        return START_STICKY
    }
}