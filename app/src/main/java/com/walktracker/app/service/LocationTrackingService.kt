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
    private var currentSteps = 0L  // 최종 걸음 수 (센서 + GPS 기반 모두 포함)
    private var sensorBasedSteps = 0L  // 센서(가속도계) 기반 걸음
    private var gpsBasedSteps = 0L  // GPS 거리 기반 걸음
    private var totalDistance = 0.0
    private var totalCalories = 0.0
    private var totalAltitudeGain = 0.0
    private var currentSpeed = 0.0f
    private var isInitialDataLoaded = false
    private var lastProcessedDate: String = ""
    private var lastDistanceForStepCalculation = 0.0  // GPS 기반 걸음 계산용 이전 거리
    private var lastStepCountForDistanceCalc = 0L  // 거리 계산용 이전 걸음 수

    // --- 속도 추적 개선 ---
    private var lastSpeedUpdateTime = 0L

    // --- 위치 및 활동 관련 데이터 ---
    private var previousLocation: Location? = null
    private var currentActivityType = ActivityType.WALKING
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
        private const val LOCATION_INTERVAL_WALKING = 10000L
        private const val LOCATION_INTERVAL_RUNNING = 5000L
        private const val LOCATION_INTERVAL_STILL = 120000L

        // 거리/속도 임계값
        private const val MIN_DISTANCE_THRESHOLD = 3.0
        private const val MAX_SPEED_MPS = 5.5  // 약 20km/h

        // 최소 유효 속도
        private const val MIN_VALID_SPEED_MPS = 0.3f

        private const val MIN_WALKING_SPEED_MPS = 0.5f
        private const val MAX_TIME_DIFFERENCE_SECONDS = 60L

        // 정확도 기준 강화(가상머신 테스트용도로 100으로 바꿈. 원래 25f)
        private const val MIN_ACCURACY_METERS = 25f

        // 속도 타임아웃
        private const val SPEED_TIMEOUT_MS = 10000L

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
                loadUserData()
                Log.d(TAG, "사용자 데이터 변경 감지: userStride 갱신됨")
            }
        }
    }

    private val sensorSettingsChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SENSOR_SETTINGS_CHANGED) {
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
                currentActivityType = newActivityType
                activityTransitionTime = System.currentTimeMillis()
                updateLocationRequest()
            }
        }
    }

    private val locationRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REQUEST_LOCATION_UPDATE) {
                broadcastLocationUpdate()
                broadcastActivityUpdate(forceImmediate = true)
            }
        }
    }

    private val resetDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RESET_TODAY_DATA) {
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

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        activityRecognitionClient = ActivityRecognition.getClient(this)

        loadInitialDailyData()
        loadUserData()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        updateSensorRegistrations()

        val localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.registerReceiver(activityTypeReceiver, IntentFilter(ActivityRecognitionReceiver.ACTION_ACTIVITY_TYPE_UPDATE))
        localBroadcastManager.registerReceiver(locationRequestReceiver, IntentFilter(ACTION_REQUEST_LOCATION_UPDATE))
        localBroadcastManager.registerReceiver(resetDataReceiver, IntentFilter(ACTION_RESET_TODAY_DATA))
        localBroadcastManager.registerReceiver(sensorSettingsChangeReceiver, IntentFilter(ACTION_SENSOR_SETTINGS_CHANGED))
        localBroadcastManager.registerReceiver(userDataChangeReceiver, IntentFilter(ACTION_USER_DATA_CHANGED))

        startLocationTracking()
        startActivityRecognition()
        startPeriodicSync()
        startStillDetection()
        startSpeedMonitoring()
    }

    private fun loadSensorSettings() {
        val previousGpsEnabled = isGpsEnabled
        val previousStepSensorEnabled = isStepSensorEnabled
        
        isGpsEnabled = syncPrefs.isGpsEnabled()
        isStepSensorEnabled = syncPrefs.isStepSensorEnabled()
        isPressureSensorEnabled = syncPrefs.isPressureSensorEnabled()

        if (!isGpsEnabled && !isStepSensorEnabled) {
            isGpsEnabled = true
            syncPrefs.setGpsEnabled(true)
        }
        
        // 센서 설정 변경 시 계산 기준점 재설정
        if (previousGpsEnabled != isGpsEnabled) {
            Log.d(TAG, "[SENSOR_TOGGLE] GPS 설정 변경: $previousGpsEnabled -> $isGpsEnabled")
            lastDistanceForStepCalculation = totalDistance
        }
        if (previousStepSensorEnabled != isStepSensorEnabled) {
            Log.d(TAG, "[SENSOR_TOGGLE] 걸음 센서 설정 변경: $previousStepSensorEnabled -> $isStepSensorEnabled")
            if (isStepSensorEnabled) {
                // 걸음 센서 활성화 시: 현재 가속도계 걸음을 기준점으로
                initialStepCount = 0L
                lastStepCountForDistanceCalc = 0L
                Log.d(TAG, "[SENSOR_TOGGLE] 걸음 센서 활성화: 기준점 재설정")
            } else {
                // 걸음 센서 비활성화 시: GPS 기반 계산 준비
                lastDistanceForStepCalculation = totalDistance
                Log.d(TAG, "[SENSOR_TOGGLE] 걸음 센서 비활성화: GPS 기반 계산 준비")
            }
        }
    }

    private fun updateSensorRegistrations() {
        stepSensor?.let {
            if (isStepSensorEnabled) {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            } else {
                sensorManager.unregisterListener(this, it)
            }
        }
        pressureSensor?.let {
            if (isPressureSensorEnabled) {
                val samplingPeriodUs = (getUpdateInterval() * 1000).toInt()
                sensorManager.registerListener(this, it, samplingPeriodUs)
            } else {
                sensorManager.unregisterListener(this, it)
            }
        }
        updateLocationRequest()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch { syncToFirebase() }
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        LocalBroadcastManager.getInstance(this).apply {
            unregisterReceiver(activityTypeReceiver)
            unregisterReceiver(locationRequestReceiver)
            unregisterReceiver(resetDataReceiver)
            unregisterReceiver(sensorSettingsChangeReceiver)
            unregisterReceiver(userDataChangeReceiver)
        }
        resetAllData()
        serviceScope.cancel()
    }

    private fun resetAllData() {
        stepsAtStartOfDay = 0L
        distanceAtStartOfDay = 0.0
        caloriesAtStartOfDay = 0.0
        altitudeAtStartOfDay = 0.0
        currentSteps = 0L
        sensorBasedSteps = 0L
        gpsBasedSteps = 0L
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
        lastDistanceForStepCalculation = 0.0  // GPS 기반 걸음 계산 초기화
        lastStepCountForDistanceCalc = 0L  // 거리 계산 기준점 초기화
        isInitialDataLoaded = false
    }

    private fun resetSpeed() {
        currentSpeed = 0.0f
        lastSpeedUpdateTime = System.currentTimeMillis()
    }

    private fun startSpeedMonitoring() {
        serviceScope.launch {
            while (isActive) {
                delay(2000L)
                checkSpeedTimeout()
            }
        }
    }

    private fun checkSpeedTimeout() {
        if (currentSpeed > 0) {
            val timeSinceLastUpdate = System.currentTimeMillis() - lastSpeedUpdateTime
            if (timeSinceLastUpdate > SPEED_TIMEOUT_MS) {
                resetSpeed()
                updateAndBroadcast()
            }
        }
    }

    private fun startPeriodicSync() {
        serviceScope.launch {
            while (isActive) {
                Log.d(TAG, "주기적 동기화 실행 대기... (${SYNC_INTERVAL / 1000}초 후)")
                delay(SYNC_INTERVAL)
                Log.d(TAG, "주기적 동기화 실행!")
                syncToFirebase()
            }
        }
    }

    private suspend fun syncToFirebase(dateToSync: String? = null) {
        val userId = repository.getCurrentUserId()
        if (userId == null) {
            Log.w(TAG, "syncToFirebase: 사용자 ID가 없어 동기화를 건너뜁니다.")
            return
        }

        val date = dateToSync ?: dateFormat.format(Date())
        Log.i(TAG, "=========================================")
        Log.i(TAG, "Firebase 동기화 시작 (날짜: $date)")
        Log.i(TAG, "=========================================")


        val stepsIncrement = currentSteps - stepsSynced
        val distanceIncrement = totalDistance - distanceSynced
        val caloriesIncrement = totalCalories - caloriesSynced
        val altitudeIncrement = totalAltitudeGain - altitudeSynced

        Log.d(TAG, "동기화 데이터 분석:")
        Log.d(TAG, "  - 현재 걸음: $currentSteps (센서=$sensorBasedSteps + GPS=$gpsBasedSteps), 동기화된 걸음: $stepsSynced -> 증분: $stepsIncrement")
        Log.d(TAG, "  - 현재 거리: $totalDistance, 동기화된 거리: $distanceSynced -> 증분: $distanceIncrement")
        Log.d(TAG, "  - 현재 칼로리: $totalCalories, 동기화된 칼로리: $caloriesSynced -> 증분: $caloriesIncrement")
        Log.d(TAG, "  - 현재 고도: $totalAltitudeGain, 동기화된 고도: $altitudeSynced -> 증분: $altitudeIncrement")
        Log.d(TAG, "  - 새로운 경로 포인트 수: ${routePoints.size}")


        if (stepsIncrement <= 0 && distanceIncrement <= 0.0 && caloriesIncrement <= 0.0 && altitudeIncrement <= 0.0 && routePoints.isEmpty()) {
            Log.i(TAG, "syncToFirebase: 동기화할 새로운 활동 데이터가 없습니다. 미동기화된 데이터만 동기화를 시도합니다.")
            repository.syncUnsyncedActivities()
            return
        }

        val unsyncedData = syncPrefs.getUnsyncedData()
        val totalStepsToSync = stepsIncrement + (unsyncedData["steps"] as? Long ?: 0L)
        val totalDistanceToSync = distanceIncrement + (unsyncedData["distance"] as? Double ?: 0.0)
        val totalCaloriesToSync = caloriesIncrement + (unsyncedData["calories"] as? Double ?: 0.0)
        val totalAltitudeToSync = altitudeIncrement + (unsyncedData["altitude"] as? Double ?: 0.0)
        val routesToSync = routePoints.toList()

        Log.i(TAG, "총 동기화 데이터:")
        Log.d(TAG, "  - 걸음: $totalStepsToSync (현재 증분: $stepsIncrement + 이전 미동기화: ${unsyncedData["steps"]})")
        Log.d(TAG, "  - 거리: $totalDistanceToSync (현재 증분: $distanceIncrement + 이전 미동기화: ${unsyncedData["distance"]})")
        Log.d(TAG, "  - 칼로리: $totalCaloriesToSync (현재 증분: $caloriesIncrement + 이전 미동기화: ${unsyncedData["calories"]})")
        Log.d(TAG, "  - 고도: $totalAltitudeToSync (현재 증분: $altitudeIncrement + 이전 미동기화: ${unsyncedData["altitude"]})")
        Log.d(TAG, "  - 경로 포인트: ${routesToSync.size}개")


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
            Log.i(TAG, "syncToFirebase: 로컬 DB 저장 성공. 동기화 상태를 업데이트합니다.")
            syncPrefs.clearUnsyncedData()
            routePoints.clear()
            stepsSynced += stepsIncrement
            distanceSynced += distanceIncrement
            caloriesSynced += caloriesIncrement
            altitudeSynced += altitudeIncrement
            Log.d(TAG, "  - 동기화 후 상태: 걸음=$stepsSynced, 거리=$distanceSynced")

            Log.d(TAG, "미동기화된 활동을 Firebase와 동기화합니다.")
            repository.syncUnsyncedActivities()
        } else {
            Log.e(TAG, "syncToFirebase: 로컬 DB 저장 실패! 현재 증분 데이터를 미동기화 데이터로 저장합니다.", localResult.exceptionOrNull())
            syncPrefs.addUnsyncedData(stepsIncrement, distanceIncrement, caloriesIncrement, altitudeIncrement)
            Log.d(TAG, "  - 미동기화 데이터로 저장된 값: 걸음=$stepsIncrement, 거리=$distanceIncrement")
        }
        Log.i(TAG, "=========================================")
        Log.i(TAG, "Firebase 동기화 종료")
        Log.i(TAG, "=========================================")
    }

    override fun onSensorChanged(event: SensorEvent) {
        lastActivityTime = System.currentTimeMillis()

        if (currentActivityType == ActivityType.VEHICLE) return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                if (!isStepSensorEnabled) return
                val totalStepsFromBoot = event.values[0].toLong()

                if (initialStepCount == 0L && totalStepsFromBoot > 0) {
                    initialStepCount = totalStepsFromBoot
                }

                val calculatedSteps = totalStepsFromBoot - initialStepCount
                if (calculatedSteps >= 0) {
                    currentSteps = calculatedSteps
                }

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

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun handleActivityTransition(from: ActivityType, to: ActivityType) {
        Log.d(TAG, "활동 전환: $from -> $to")

        when {
            to == ActivityType.STILL -> {
                resetSpeed()
                broadcastActivityUpdate(forceImmediate = true)
            }
            to == ActivityType.VEHICLE -> {
                previousLocation = null
                transitionLocationBuffer.clear()
                resetSpeed()
                altitudeCalculator.reset()
                pressureAtPreviousLocation = null
            }
            from == ActivityType.VEHICLE && (to == ActivityType.WALKING || to == ActivityType.RUNNING) -> {
                previousLocation = null
                transitionLocationBuffer.clear()
                resetSpeed()
                altitudeCalculator.reset()
                pressureAtPreviousLocation = null
            }
            from == ActivityType.STILL && (to == ActivityType.WALKING || to == ActivityType.RUNNING) -> {
                transitionLocationBuffer.clear()
                resetSpeed()
            }
        }
    }

    private fun isInTransitionPeriod(): Boolean {
        return System.currentTimeMillis() - activityTransitionTime < TRANSITION_STABILIZATION_TIME
    }

    private fun handleNewLocation(location: Location) {
        if (!isGpsEnabled) return

        if (location.hasAccuracy() && location.accuracy > MIN_ACCURACY_METERS) {
            Log.d(TAG, "[LOCATION] 정확도 낮음(${location.accuracy}m) - 무시")
            return
        }

        if (currentActivityType == ActivityType.STILL) {
            Log.d(TAG, "[LOCATION] 정지(STILL) 상태임. 위치 업데이트만 수행하고 거리 누적 안 함.")
            previousLocation = location
            resetSpeed()
            broadcastLocationUpdate()
            return
        }

        if (currentActivityType == ActivityType.VEHICLE) {
            previousLocation = location
            resetSpeed()
            broadcastLocationUpdate()
            return
        }

        if (isInTransitionPeriod()) {
            transitionLocationBuffer.add(location)
            if (transitionLocationBuffer.size > TRANSITION_BUFFER_SIZE) {
                transitionLocationBuffer.removeAt(0)
            }
            previousLocation = location
            broadcastLocationUpdate()
            return
        }

        if (previousLocation == null) {
            previousLocation = location
            pressureAtPreviousLocation = lastPressureValue
            broadcastLocationUpdate()
            return
        }

        val prev = previousLocation!!
        val timeDifferenceSeconds = (location.time - prev.time) / 1000.0

        if (timeDifferenceSeconds <= 0) return

        if (timeDifferenceSeconds > MAX_TIME_DIFFERENCE_SECONDS) {
            previousLocation = location
            pressureAtPreviousLocation = null
            resetSpeed()
            return
        }

        val distance = prev.distanceTo(location)

        if (distance < MIN_DISTANCE_THRESHOLD) {
            return
        }

        val rawSpeed = (distance / timeDifferenceSeconds).toFloat()

        if (rawSpeed < MIN_VALID_SPEED_MPS) {
            Log.d(TAG, "[LOCATION] 속도 너무 느림(${"%.2f".format(rawSpeed)}m/s) - 노이즈로 간주")
            return
        }

        if (rawSpeed > MAX_SPEED_MPS) {
            Log.w(TAG, "[LOCATION] 과속(${"%.2f".format(rawSpeed)}m/s) - GPS 점프 감지됨. 무시")
            previousLocation = location
            return
        }

        Log.d(TAG, "[LOCATION_UPDATE] 유효 이동: ${"%.2f".format(distance)}m, 속도: ${"%.2f".format(rawSpeed)}m/s")

        lastActivityTime = System.currentTimeMillis()
        currentSpeed = smoothSpeed(rawSpeed)
        lastSpeedUpdateTime = System.currentTimeMillis()

        val distanceKm = distance / 1000.0
        totalDistance += distanceKm

        val isMoving = currentSpeed > MIN_WALKING_SPEED_MPS
        if (isPressureSensorEnabled && lastPressureValue > 0f && pressureAtPreviousLocation != null) {
            val threshold = if (currentActivityType == ActivityType.RUNNING) PRESSURE_CHANGE_THRESHOLD_RUNNING else PRESSURE_CHANGE_THRESHOLD_WALKING
            val altitudeGain = altitudeCalculator.calculateAltitudeGain(
                currentPressure = lastPressureValue,
                currentTime = location.time,
                isMoving = isMoving,
                pressureChangeThreshold = threshold
            )
            if (altitudeGain != 0.0) {
                totalAltitudeGain += abs(altitudeGain)
            }
        }

        // 걸음 센서가 비활성화되었을 때 GPS 거리 기반으로 걸음 수 계산
        if (!isStepSensorEnabled && userStride > 0.0) {
            val distanceIncrementKm = totalDistance - lastDistanceForStepCalculation
            val distanceIncrementMeters = distanceIncrementKm * 1000.0
            val stepsFromIncrement = (distanceIncrementMeters / userStride).toLong()
            if (stepsFromIncrement > 0) {
                gpsBasedSteps += stepsFromIncrement
                lastDistanceForStepCalculation = totalDistance
                currentSteps = sensorBasedSteps + gpsBasedSteps  // 최종 = 센서 + GPS
                Log.d(TAG, "[GPS_STEP_CALC] GPS 거리증분=${String.format("%.3f", distanceIncrementKm)}km, 보폭=${userStride}m -> GPS걸음=$stepsFromIncrement, GPS누계=$gpsBasedSteps, 최종=$currentSteps")
            }
        }

        val caloriesBurned = CalorieCalculator.calculate(
            weightKg = userWeight,
            speedMps = currentSpeed,
            durationSeconds = timeDifferenceSeconds.toLong(),
            activityType = currentActivityType,
            elevationGainMeters = 0.0
        )
        totalCalories += caloriesBurned

        val newRoutePoint = RoutePoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timestamp = location.time,
            activityType = currentActivityType.name,
            speed = currentSpeed.toDouble()
        )
        routePoints.add(newRoutePoint)

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
        Log.d(TAG, "오늘 데이터 초기화 (resetTodayData) 실행")
        isInitialDataLoaded = false // 데이터 로드 전까지 UI 업데이트 방지

        // 일일 누적 데이터 초기화
        stepsAtStartOfDay = 0L
        distanceAtStartOfDay = 0.0
        caloriesAtStartOfDay = 0.0
        altitudeAtStartOfDay = 0.0

        // 현재 세션 데이터 초기화
        currentSteps = 0L
        sensorBasedSteps = 0L
        gpsBasedSteps = 0L
        totalDistance = 0.0
        totalCalories = 0.0
        totalAltitudeGain = 0.0
        initialStepCount = 0L // [핵심 수정] 걸음수 계산 기준 초기화
        lastDistanceForStepCalculation = 0.0  // GPS 기반 걸음 계산 초기화
        lastStepCountForDistanceCalc = 0L

        // 동기화 데이터 초기화
        stepsSynced = 0L
        distanceSynced = 0.0
        caloriesSynced = 0.0
        altitudeSynced = 0.0

        // 기타 상태 초기화
        lastPressureValue = 0f
        pressureAtPreviousLocation = null
        previousLocation = null
        routePoints.clear()
        syncPrefs.clearUnsyncedData()
        altitudeCalculator.reset()
        resetSpeed()

        serviceScope.launch {
            repository.resetTodayData(dateFormat.format(Date()))
            withContext(Dispatchers.Main) {
                loadInitialDailyData()
            }
        }

        // 초기화된 상태 즉시 브로드캐스트
        broadcastActivityUpdate(forceImmediate = true)
        updateNotification()
    }

    private fun updateAndBroadcast() {
        if (!isInitialDataLoaded) return
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
            user?.let { userWeight = it.weight }
            userStride = syncPrefs.getUserStride()
            Log.d(TAG, "사용자 데이터 로드: weight=$userWeight, stride=$userStride")
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

            val localActivity = repository.getDailyActivityLocal(userId, date)
            if (localActivity != null) {
                stepsAtStartOfDay = localActivity.steps
                distanceAtStartOfDay = localActivity.distance
                caloriesAtStartOfDay = localActivity.calories
                altitudeAtStartOfDay = localActivity.altitude
                isInitialDataLoaded = true
                updateAndBroadcast()
            } else {
                repository.getDailyActivityOnce(userId, date) { activity ->
                    activity?.let {
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
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "걷기 추적", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val totalStepsToday = stepsAtStartOfDay + currentSteps
        val totalDistanceToday = distanceAtStartOfDay + totalDistance

        val contentText = if (isStillMode) {
            "절전 모드 - 오늘: ${totalStepsToday}걸음"
        } else {
            String.format(Locale.US, "오늘: %d걸음 • %.2fkm • %.1fkm/h", totalStepsToday, totalDistanceToday, currentSpeed * 3.6)
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
            if (currentActivityType == ActivityType.VEHICLE || (!isGpsEnabled && !isStepSensorEnabled)) {
                enterStillMode()
            }
            return
        }

        val (priority, interval) = when (currentActivityType) {
            ActivityType.RUNNING -> Priority.PRIORITY_HIGH_ACCURACY to LOCATION_INTERVAL_RUNNING
            ActivityType.WALKING -> Priority.PRIORITY_HIGH_ACCURACY to LOCATION_INTERVAL_WALKING
            ActivityType.STILL -> Priority.PRIORITY_BALANCED_POWER_ACCURACY to LOCATION_INTERVAL_STILL
            else -> Priority.PRIORITY_BALANCED_POWER_ACCURACY to LOCATION_INTERVAL_WALKING
        }

        val locationRequest = LocationRequest.Builder(priority, interval).apply {
            setMinUpdateIntervalMillis(interval / 2)
            setMaxUpdateDelayMillis(interval * 2)
            setWaitForAccurateLocation(false)
            if (currentActivityType != ActivityType.RUNNING) {
                setMinUpdateDistanceMeters(MIN_DISTANCE_THRESHOLD.toFloat())
            }
        }.build()

        // 안전하게 위치 업데이트 요청
        safeRequestLocationUpdates(locationRequest)
        exitStillMode()
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { handleNewLocation(it) }
        }
    }

    private fun startActivityRecognition() {
        val transitions = listOf(
            ActivityTransition.Builder().setActivityType(DetectedActivity.WALKING).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
            ActivityTransition.Builder().setActivityType(DetectedActivity.RUNNING).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
            ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
            ActivityTransition.Builder().setActivityType(DetectedActivity.STILL).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()
        )
        val request = ActivityTransitionRequest(transitions)
        val intent = Intent(this, ActivityRecognitionReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            activityRecognitionClient.requestActivityTransitionUpdates(request, pendingIntent)
        } catch (e: SecurityException) {}
    }

    private fun checkDateChanged() {
        val currentDate = dateFormat.format(Date())
        if (lastProcessedDate.isNotEmpty() && lastProcessedDate != currentDate) {
            Log.d(TAG, "날짜 변경 감지: $lastProcessedDate -> $currentDate. 데이터 동기화 및 초기화 실행.")
            serviceScope.launch {
                syncToFirebase(lastProcessedDate) // 어제 날짜로 데이터 동기화
                withContext(Dispatchers.Main) {
                    resetTodayData() // 오늘 데이터 초기화
                    loadInitialDailyData() // 새로 초기화된 오늘 데이터 로드
                }
            }
        }
        lastProcessedDate = currentDate
    }

    private fun startStillDetection() {
        serviceScope.launch {
            while (isActive) {
                delay(60000L) // 1분마다 실행
                checkDateChanged() // 날짜 변경 확인

                val timeSinceLastActivity = System.currentTimeMillis() - lastActivityTime
                if (timeSinceLastActivity > STILL_DETECTION_TIME && !isStillMode) {
                    enterStillMode()
                }
            }
        }
    }

    private fun enterStillMode() {
        if (isStillMode) return
        isStillMode = true

        pressureSensor?.let { sensorManager.unregisterListener(this, it) }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_LOW_POWER, LOCATION_INTERVAL_STILL).apply {
            setMinUpdateIntervalMillis(LOCATION_INTERVAL_STILL / 2)
            setMaxUpdateDelayMillis(LOCATION_INTERVAL_STILL * 2)
        }.build()

        safeRequestLocationUpdates(locationRequest)
        updateNotification()
    }

    // Helper to centralize try/catch for requesting location updates
    private fun safeRequestLocationUpdates(request: LocationRequest) {
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.w(TAG, "safeRequestLocationUpdates: SecurityException - stopping service or ignoring", e)
            // If it's a fatal security exception (missing runtime perms), stop service to avoid repeated exceptions
            try {
                stopSelf()
            } catch (_: Exception) {}
        }
    }

    private fun exitStillMode() {
        if (!isStillMode) return
        isStillMode = false
        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
