
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
    private val repository by lazy { FirebaseRepository(applicationContext) }
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

        // 주기적 동기화 간격 (10분)
        private const val SYNC_INTERVAL = 600000L

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
        private const val MIN_ACCURACY_METERS = 100f

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
        startSpeedMonitoring()

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

        Log.d(
            TAG,
            "센서 설정 로드 - GPS: $isGpsEnabled, 걸음: $isStepSensorEnabled, 기압: $isPressureSensorEnabled"
        )
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

    /**
     * Room에 먼저 저장 후 주기적으로 Firestore 동기화
     */
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

        if (stepsIncrement < 0 || distanceIncrement < 0) {
            Log.w(TAG, "동기화 증분값이 음수입니다. steps: $stepsIncrement, dist: $distanceIncrement. 동기화를 건너뜁니다.")
            // 필요하다면, 이 상황에 대한 추가적인 핸들링 (예: 데이터 재설정)
            return
        }

        Log.d(TAG, "[SYNC_DATA] 동기화 증분 - 걸음: $stepsIncrement, 거리: ${"%.3f".format(distanceIncrement)}km")

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
            Log.d(TAG, "[SYNC_DATA] >> Room 저장을 시작합니다.")
            Log.d(TAG, "[SYNC_DATA] >> 저장될 데이터: Steps=$totalStepsToSync, Dist=$totalDistanceToSync, Routes=${routesToSync.size}개")

            // 1. Room에 먼저 저장
            val localResult = repository.incrementDailyActivityLocal(
                userId = userId,
                date = date,
                steps = totalStepsToSync,
                distance = totalDistanceToSync,
                calories = totalCaloriesToSync,
                altitude = totalAltitudeToSync,
                routes = routesToSync
            )

            if (localResult.isSuccess) {
                Log.d(TAG, "[SYNC_DATA] ✓ Room 저장 성공")
                syncPrefs.clearUnsyncedData()
                routePoints.clear()
                // 동기화된 만큼 기준값 업데이트
                stepsSynced += totalStepsToSync
                distanceSynced += totalDistanceToSync
                caloriesSynced += totalCaloriesToSync
                altitudeSynced += totalAltitudeToSync
                Log.d(TAG, "[SYNC_DATA] 동기화 기준값 업데이트: Steps=$stepsSynced, Dist=$distanceSynced")


                // 2. Firestore 동기화 시도
                val firestoreResult = repository.syncUnsyncedActivities()
                if (firestoreResult.isSuccess) {
                    Log.d(TAG, "[SYNC_DATA] ✓ Firestore 동기화 성공")
                } else {
                    Log.w(TAG, "[SYNC_DATA] Firestore 동기화 실패 (다음 주기에 재시도)", firestoreResult.exceptionOrNull())
                }
            } else {
                Log.e(TAG, "[SYNC_DATA] ✗ Room 저장 실패", localResult.exceptionOrNull())
                // 실패한 경우, 이번 증분량을 unsynced 데이터에 누적
                syncPrefs.addUnsyncedData(
                    stepsIncrement,
                    distanceIncrement,
                    caloriesIncrement,
                    altitudeIncrement
                )
                Log.d(TAG, "[SYNC_DATA] 실패한 증분량을 SharedPreferences에 저장했습니다.")
            }
        } else {
            Log.d(TAG, "[SYNC_DATA] 동기화할 새로운 데이터 없음")

            // 데이터가 없어도 주기적으로 미동기화 항목 체크
            repository.syncUnsyncedActivities()
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

                if (initialStepCount == 0L && totalStepsFromBoot > 0) {
                    initialStepCount = totalStepsFromBoot
                    Log.d(TAG, "[SENSOR_STEP] >> 초기 걸음수 설정: $initialStepCount")
                }
                currentSteps = totalStepsFromBoot - initialStepCount
                Log.d(TAG, "[SENSOR_STEP] 센서값: $totalStepsFromBoot, 세션 걸음: $currentSteps")
                updateAndBroadcast()
            }

            Sensor.TYPE_PRESSURE -> {
                if (!isPressureSensorEnabled) return
                val pressure = event.values[0]
                if (pressure > 800f && pressure < 1100f) { // 유효한 기압 범위
                    Log.d(TAG, "[SENSOR_PRESSURE] 기압 센서값: $pressure hPa")
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

            to == ActivityType.VEHICLE -> {
                Log.d(TAG, "→차량: 데이터 리셋")
                previousLocation = null
                transitionLocationBuffer.clear()
                resetSpeed()
                altitudeCalculator.reset()
                pressureAtPreviousLocation = null
            }

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
            Log.d(TAG, "[LOCATION] GPS 비활성화 상태 - 위치 업데이트 무시")
            return
        }

        Log.d(TAG, "[LOCATION] >> 새 위치 수신: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}m, speed=${location.speed}m/s")

        if (location.hasAccuracy() && location.accuracy > MIN_ACCURACY_METERS) {
            if (location.speed < 0.2f) {
                Log.w(TAG, "[LOCATION] 정확도 낮음(${location.accuracy}m) & 저속(${location.speed}m/s) - 무시")
                previousLocation = location
                return
            }
        }

        if (currentActivityType == ActivityType.VEHICLE) {
            Log.d(TAG, "[LOCATION] 차량 모드 - 위치만 업데이트, 속도는 0으로 유지")
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
            Log.d(TAG, "[LOCATION] 날짜 변경: $lastProcessedDate -> $currentDate. 어제 데이터 동기화 및 오늘 데이터 리셋")
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
            Log.d(TAG, "[LOCATION] 활동 전환 안정화 기간 - 위치 버퍼링 (${transitionLocationBuffer.size}/${TRANSITION_BUFFER_SIZE})")
            previousLocation = location
            broadcastLocationUpdate()
            return
        }

        if (previousLocation == null) {
            Log.d(TAG, "[LOCATION] 시작 위치 설정됨")
            previousLocation = location
            pressureAtPreviousLocation = lastPressureValue
            broadcastLocationUpdate()
            return
        }

        val prev = previousLocation!!
        val timeDifferenceSeconds = (location.time - prev.time) / 1000

        if (timeDifferenceSeconds <= 0) {
            Log.w(TAG, "[LOCATION] 시간차가 0 또는 음수(${timeDifferenceSeconds}s) - 무시")
            return
        }

        if (timeDifferenceSeconds > MAX_TIME_DIFFERENCE_SECONDS) {
            Log.w(TAG, "[LOCATION] 시간차 너무 큼(${timeDifferenceSeconds}s) - 위치 리셋")
            previousLocation = null
            pressureAtPreviousLocation = null
            resetSpeed()
            updateAndBroadcast()
            previousLocation = location // 현재 위치를 다음 시작점으로 설정
            return
        }

        val distance = prev.distanceTo(location)
        Log.d(TAG, "[LOCATION] 이전 위치와의 거리: ${"%.2f".format(distance)}m, 시간차: ${timeDifferenceSeconds}s")

        if (distance < MIN_DISTANCE_THRESHOLD) {
            Log.d(TAG, "[LOCATION] 이동 거리 부족(${"%.2f".format(distance)}m) - 무시")
            previousLocation = location // 다음 계산을 위해 위치는 업데이트
            if (currentSpeed > 0) {
                val timeSinceLastMovement = System.currentTimeMillis() - lastValidMovementTime
                if (timeSinceLastMovement > SPEED_TIMEOUT_MS / 2) {
                    Log.d(TAG, "[LOCATION] 유효한 이동이 없어 속도를 0으로 설정")
                    resetSpeed()
                    updateAndBroadcast()
                }
            }
            return
        }

        val rawSpeed = if (timeDifferenceSeconds > 0) (distance / timeDifferenceSeconds) else 0f
        Log.d(TAG, "[LOCATION] 계산된 원시 속도: ${"%.2f".format(rawSpeed)}m/s")

        if (rawSpeed > MAX_SPEED_MPS) {
            Log.w(TAG, "[LOCATION] 최대 속도 초과 (GPS 점프 감지) - 무시하고 위치 리셋")
            previousLocation = null
            pressureAtPreviousLocation = null
            broadcastLocationUpdate()
            previousLocation = location
            return
        }
        
        // (걷기 모드 속도 제한 등 나머지 유효성 검사 생략)
        
        Log.d(TAG, "[LOCATION_UPDATE] >> 유효한 이동 감지 - 데이터 업데이트 시작")

        lastActivityTime = System.currentTimeMillis()
        lastValidMovementTime = System.currentTimeMillis()
        currentSpeed = smoothSpeed(rawSpeed) // 속도 보정
        lastSpeedUpdateTime = System.currentTimeMillis()
        
        val distanceKm = distance / 1000.0
        totalDistance += distanceKm

        Log.d(TAG, "[LOCATION_UPDATE] 거리 증가: +${"%.4f".format(distanceKm)}km -> 총 ${"%.3f".format(totalDistance)}km")
        Log.d(TAG, "[LOCATION_UPDATE] 현재 속도: ${"%.2f".format(currentSpeed)}m/s")
        
        // 고도 계산 로직
        val isMoving = currentSpeed > MIN_WALKING_SPEED_MPS
        if (isPressureSensorEnabled && pressureSensor != null && lastPressureValue > 0f && pressureAtPreviousLocation != null) {
            val threshold = when (currentActivityType) {
                ActivityType.RUNNING -> PRESSURE_CHANGE_THRESHOLD_RUNNING
                else -> PRESSURE_CHANGE_THRESHOLD_WALKING
            }
            val altitudeGain = altitudeCalculator.calculateAltitudeGain(
                currentPressure = lastPressureValue,
                currentTime = location.time,
                isMoving = isMoving,
                pressureChangeThreshold = threshold
            )
            if(altitudeGain != 0.0) {
                totalAltitudeGain += abs(altitudeGain)
                Log.d(TAG, "[LOCATION_UPDATE] 고도 증가: +${"%.2f".format(abs(altitudeGain))}m -> 총 ${"%.2f".format(totalAltitudeGain)}m")
            }
        }
        
        // 걸음 수 계산 (GPS 기반)
        if (!isStepSensorEnabled && userStride > 0) {
            val stepsFromDistance = (totalDistance * 1000 / userStride).toLong()
            Log.d(TAG, "[LOCATION_UPDATE] GPS 기반 걸음수: $stepsFromDistance (이전:${currentSteps})")
            currentSteps = stepsFromDistance
        }

        // 칼로리 계산
        val caloriesBurned = CalorieCalculator.calculate(
            weightKg = userWeight,
            speedMps = currentSpeed,
            durationSeconds = timeDifferenceSeconds,
            activityType = currentActivityType,
            elevationGainMeters = 0.0 // 고도에 따른 칼로리 계산은 일단 제외
        )
        totalCalories += caloriesBurned
        Log.d(TAG, "[LOCATION_UPDATE] 칼로리 소모: +${"%.2f".format(caloriesBurned)} -> 총 ${"%.2f".format(totalCalories)}kcal")

        // 경로 포인트 추가
        val newRoutePoint = RoutePoint(
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = location.time,
                activityType = currentActivityType.name,
                speed = currentSpeed.toDouble()
            )
        routePoints.add(newRoutePoint)
        Log.d(TAG, "[LOCATION_UPDATE] 경로 포인트 추가: ${newRoutePoint}, (총 ${routePoints.size}개)")

        updateAndBroadcast()
        previousLocation = location
        pressureAtPreviousLocation = lastPressureValue
        broadcastLocationUpdate()
    }

    private fun smoothSpeed(calculatedSpeed: Float): Float {
        if (currentSpeed > 0) {
            val speedDiff = abs(calculatedSpeed - currentSpeed)
            val maxSpeedChange = 1.0f

            if (speedDiff > maxSpeedChange) {
                val smoothed = if (calculatedSpeed > currentSpeed) {
                    currentSpeed + maxSpeedChange
                } else {
                    (currentSpeed - maxSpeedChange).coerceAtLeast(0f)
                }
                Log.d(TAG, "속도 보정: $calculatedSpeed -> $smoothed (이전: $currentSpeed)")
                return smoothed
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
        // initialStepCount는 재부팅 전까지 유지되어야 하므로 리셋하지 않음
        // 서비스가 재시작되면 onCreate에서 다시 값을 읽어오고, onSensorChanged에서 재설정됨
        lastPressureValue = 0f
        pressureAtPreviousLocation = null
        previousLocation = null
        routePoints.clear()
        syncPrefs.clearUnsyncedData()
        altitudeCalculator.reset()
        resetSpeed()
        updateAndBroadcast()
    }

    private fun updateAndBroadcast() {
        if (!isInitialDataLoaded) {
            Log.d(TAG, "초기 데이터 로딩 전이므로 브로드캐스트를 생략합니다.")
            return
        }
        updateNotification()
        broadcastActivityUpdate(forceImmediate = false)
    }

    private fun broadcastActivityUpdate(forceImmediate: Boolean) {
        val now = System.currentTimeMillis()

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

            Log.d(TAG, "[BROADCAST_UI] UI 업데이트 브로드캐스트 - Steps=$totalStepsToday, Dist=$totalDistanceToday, Speed=${"%.2f".format(currentSpeed)}m/s")
        }
    }

    private fun broadcastLocationUpdate() {
        previousLocation?.let {
            val intent = Intent(ACTION_LOCATION_UPDATE).apply {
                putExtra(EXTRA_LOCATION, it)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            Log.d(TAG, "[BROADCAST_MAP] 지도 위치 업데이트 브로드캐스트: lat=${it.latitude}, lon=${it.longitude}")
        }
    }

    private fun loadUserData() {
        serviceScope.launch {
            val user = repository.getCurrentUser()
            user?.let {
                userWeight = it.weight
                Log.d(TAG, "사용자 데이터 로드 - 체중:${userWeight}kg")
            }
            userStride = syncPrefs.getUserStride()
            Log.d(TAG, "사용자 데이터 로드 - 보폭:${userStride}m")
        }
    }

    private fun loadInitialDailyData() {
        serviceScope.launch {
            val userId = repository.getCurrentUserId()
            if (userId == null) {
                Log.w(TAG, "사용자 ID를 찾을 수 없어 초기 데이터를 로드할 수 없습니다.")
                isInitialDataLoaded = true
                return@launch
            }
            val date = dateFormat.format(Date())
            Log.d(TAG, "오늘($date)의 초기 데이터 로드를 시작합니다...")

            // Room에서 먼저 조회
            val localActivity = repository.getDailyActivityLocal(userId, date)
            if (localActivity != null) {
                Log.d(
                    TAG,
                    "[INIT_DATA] Room에서 초기 데이터 로드 성공: 걸음=${localActivity.steps}, 거리=${localActivity.distance}km"
                )
                stepsAtStartOfDay = localActivity.steps
                distanceAtStartOfDay = localActivity.distance
                caloriesAtStartOfDay = localActivity.calories
                altitudeAtStartOfDay = localActivity.altitude
                isInitialDataLoaded = true
                updateAndBroadcast()
            } else {
                // Room에 없으면 Firestore에서 조회
                Log.d(TAG, "[INIT_DATA] Room에 데이터 없음. Firestore에서 조회합니다.")
                repository.getDailyActivityOnce(userId, date) { activity ->
                    activity?.let {
                        Log.d(TAG, "[INIT_DATA] Firestore에서 초기 데이터 로드 성공: 걸음=${it.steps}, 거리=${it.distance}km")
                        stepsAtStartOfDay = it.steps
                        distanceAtStartOfDay = it.distance
                        caloriesAtStartOfDay = it.calories
                        altitudeAtStartOfDay = it.altitude
                    } ?: Log.d(TAG, "[INIT_DATA] Firestore에도 오늘 데이터 없음. 0에서 시작합니다.")
                    isInitialDataLoaded = true
                    updateAndBroadcast()
                }
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
            String.format(
                Locale.US, "오늘: %d걸음 • %.2fkm • %.1fkm/h",
                totalStepsToday, totalDistanceToday, currentSpeed * 3.6
            )
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
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            createNotification()
        )
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
            ActivityType.WALKING -> Priority.PRIORITY_BALANCED_POWER_ACCURACY to LOCATION_INTERVAL_WALKING
            ActivityType.STILL -> Priority.PRIORITY_BALANCED_POWER_ACCURACY to LOCATION_INTERVAL_STILL
            else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY to LOCATION_INTERVAL_WALKING
        }

        Log.d(TAG, "위치 요청 업데이트 - 활동:$currentActivityType, 우선순위:$priority, 간격:${interval / 1000}초")

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
                // LocationCallback 자체에서 로그를 찍기보다 handleNewLocation으로 넘겨서 일관되게 처리
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

    private fun startStillDetection() {
        serviceScope.launch {
            Log.d(TAG, "정지 감지 루프 시작")
            while (isActive) {
                delay(60000L) // 1분마다 확인
                val timeSinceLastActivity = System.currentTimeMillis() - lastActivityTime
                if (timeSinceLastActivity > STILL_DETECTION_TIME && !isStillMode) {
                    Log.d(TAG, "장시간 활동 없음(${timeSinceLastActivity / 1000}s) - 절전 모드 진입")
                    enterStillMode()
                }
            }
        }
    }

    private fun enterStillMode() {
        if (isStillMode) return
        isStillMode = true
        Log.d(TAG, "========== 절전 모드 진입 ==========")

        if (isPressureSensorEnabled) pressureSensor?.let {
            sensorManager.unregisterListener(this, it)
            Log.d(TAG, "절전 모드: 기압 센서 리스너 해제")
        }

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
            Log.d(TAG, "절전 모드: 저전력 위치 업데이트 요청")
        } catch (e: SecurityException) {
            Log.e(TAG, "절전 모드 위치 권한 오류", e)
        }

        updateNotification()
    }

    private fun exitStillMode() {
        if (!isStillMode) return
        isStillMode = false
        Log.d(TAG, "========== 절전 모드 해제 ==========")

        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand 호출 - Intent: $intent")
        return START_STICKY
    }
}
