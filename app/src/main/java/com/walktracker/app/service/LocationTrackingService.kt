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
        private const val LOCATION_INTERVAL_WALKING = 10000L // [수정] 반응성을 위해 약간 단축 (기존 20s -> 10s)
        private const val LOCATION_INTERVAL_RUNNING = 5000L  // [수정] (기존 15s -> 5s)
        private const val LOCATION_INTERVAL_STILL = 120000L

        // 거리/속도 임계값
        private const val MIN_DISTANCE_THRESHOLD = 3.0 // [수정] 5.0 -> 3.0 (정밀한 필터링을 위해 낮추되, 아래 로직으로 보완)
        private const val MAX_SPEED_MPS = 5.5  // 약 20km/h

        // [수정] 최소 유효 속도: 이 속도보다 느리면 GPS 노이즈로 간주하고 거리에 합산하지 않음
        private const val MIN_VALID_SPEED_MPS = 0.3f

        private const val MAX_WALKING_SPEED_MPS = 2.5f
        private const val MIN_WALKING_SPEED_MPS = 0.5f
        private const val MAX_TIME_DIFFERENCE_SECONDS = 60L

        // [수정] 정확도 기준 강화: 50m -> 25m (실내 튀는 현상 방지 핵심)
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

    // ... (Receiver 등 기존 코드 동일) ...
    // BroadcastReceiver 등은 변경 사항 없음으로 생략 가능하나 전체 구조 유지를 위해 유지
    private val userDataChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USER_DATA_CHANGED) {
                loadUserData()
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

    // ... (loadSensorSettings, updateSensorRegistrations, onDestroy, resetAllData 등 기존 동일) ...
    private fun loadSensorSettings() {
        isGpsEnabled = syncPrefs.isGpsEnabled()
        isStepSensorEnabled = syncPrefs.isStepSensorEnabled()
        isPressureSensorEnabled = syncPrefs.isPressureSensorEnabled()

        if (!isGpsEnabled && !isStepSensorEnabled) {
            isGpsEnabled = true
            syncPrefs.setGpsEnabled(true)
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

    private fun resetSpeed() {
        currentSpeed = 0.0f
        lastSpeedUpdateTime = System.currentTimeMillis()
        lastValidMovementTime = 0L
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
                delay(SYNC_INTERVAL)
                syncToFirebase()
            }
        }
    }

    // ... (syncToFirebase 로직은 Repository 호출이므로 기존 유지) ...
    private suspend fun syncToFirebase(dateToSync: String? = null) {
        val userId = repository.getCurrentUserId() ?: return
        val date = dateToSync ?: dateFormat.format(Date())

        val stepsIncrement = currentSteps - stepsSynced
        val distanceIncrement = totalDistance - distanceSynced
        val caloriesIncrement = totalCalories - caloriesSynced
        val altitudeIncrement = totalAltitudeGain - altitudeSynced

        // [수정] 의미 없는 0 동기화 방지 및 마이너스 방지
        if (stepsIncrement <= 0 && distanceIncrement <= 0.0 && routePoints.isEmpty()) {
            // 동기화할 데이터가 없음.
            // 하지만 미동기화된(Unsynced) 이전 데이터가 있을 수 있으니 체크
            repository.syncUnsyncedActivities()
            return
        }

        val unsyncedData = syncPrefs.getUnsyncedData()
        val totalStepsToSync = stepsIncrement + (unsyncedData["steps"] as? Long ?: 0L)
        val totalDistanceToSync = distanceIncrement + (unsyncedData["distance"] as? Double ?: 0.0)
        val totalCaloriesToSync = caloriesIncrement + (unsyncedData["calories"] as? Double ?: 0.0)
        val totalAltitudeToSync = altitudeIncrement + (unsyncedData["altitude"] as? Double ?: 0.0)
        val routesToSync = routePoints.toList()

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
            syncPrefs.clearUnsyncedData()
            routePoints.clear()
            stepsSynced += stepsIncrement // [수정] 이번에 처리한 증가분만 더함 (unsynced는 이미 처리됨)
            distanceSynced += distanceIncrement
            caloriesSynced += caloriesIncrement
            altitudeSynced += altitudeIncrement

            repository.syncUnsyncedActivities()
        } else {
            syncPrefs.addUnsyncedData(stepsIncrement, distanceIncrement, caloriesIncrement, altitudeIncrement)
        }
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

                // [수정] 0 미만 방지
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

    // =========================================================================
    // [중요 수정 구간] 위치 처리 로직 강화
    // =========================================================================
    private fun handleNewLocation(location: Location) {
        if (!isGpsEnabled) return

        // 1. [수정] 정확도 필터 강화 (50m -> 25m)
        // 정확도가 매우 낮은 데이터는 아예 무시합니다.
        if (location.hasAccuracy() && location.accuracy > MIN_ACCURACY_METERS) {
            Log.d(TAG, "[LOCATION] 정확도 낮음(${location.accuracy}m) - 무시")
            return
        }

        // 2. [수정] 활동 상태 필터링 (가장 중요한 수정)
        // 현재 활동이 '정지(STILL)' 상태라면, GPS가 움직여도 거리를 누적하지 않습니다.
        // 단, 차량 모드는 별도 처리
        if (currentActivityType == ActivityType.STILL) {
            Log.d(TAG, "[LOCATION] 정지(STILL) 상태임. 위치 업데이트만 수행하고 거리 누적 안 함.")
            previousLocation = location
            resetSpeed()
            broadcastLocationUpdate() // 지도의 내 위치 마커는 업데이트 해줌
            return
        }

        if (currentActivityType == ActivityType.VEHICLE) {
            previousLocation = location
            resetSpeed()
            broadcastLocationUpdate()
            return
        }

        val currentDate = dateFormat.format(Date())
        if (lastProcessedDate.isNotEmpty() && lastProcessedDate != currentDate) {
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
        val timeDifferenceSeconds = (location.time - prev.time) / 1000.0 // [수정] double 연산

        if (timeDifferenceSeconds <= 0) return

        if (timeDifferenceSeconds > MAX_TIME_DIFFERENCE_SECONDS) {
            previousLocation = location
            pressureAtPreviousLocation = null
            resetSpeed()
            return
        }

        val distance = prev.distanceTo(location)

        // 3. [수정] 거리 노이즈 필터링 강화
        // 이동 거리가 임계값 미만이면 무시
        if (distance < MIN_DISTANCE_THRESHOLD) {
            // 위치가 거의 안 변했으므로 무시하되, 현재 위치로 갱신은 하지 않음(누적 오차 방지)
            // 단, 너무 오랫동안 갱신 안되면 점프할 수 있으니 time check 등은 필요하나
            // 여기서는 단순 무시가 GPS drift 방지에 효과적.
            return
        }

        val rawSpeed = (distance / timeDifferenceSeconds).toFloat()

        // 4. [수정] 속도 기반 노이즈 필터링
        // 계산된 속도가 너무 느리면(예: 0.2m/s) GPS가 살짝 튄 노이즈일 확률이 높음.
        // 실제 걷기는 보통 0.8m/s 이상임.
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
        lastValidMovementTime = System.currentTimeMillis()
        currentSpeed = smoothSpeed(rawSpeed)
        lastSpeedUpdateTime = System.currentTimeMillis()

        val distanceKm = distance / 1000.0
        totalDistance += distanceKm

        // 고도 계산
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

        // 5. [수정] GPS 기반 걸음 수 계산 조건 강화
        // 하드웨어 스텝 센서가 활성화되어 있고 정상 작동 중이면, GPS로 걸음 수를 덮어쓰지 않음.
        // 스텝 센서가 없거나(isStepSensorEnabled == false) 고장난 경우에만 GPS 거리를 사용.
        if (!isStepSensorEnabled && userStride > 0) {
            val stepsFromDistance = (totalDistance * 1000 / userStride).toLong()
            // 기존 걸음 수보다 줄어드는 경우 방지 (Max 처리)
            if (stepsFromDistance > currentSteps) {
                currentSteps = stepsFromDistance
            }
        }

        // 칼로리 계산
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
                // [수정] 이동 임계값을 살짝 낮춰서 FusedLocationProvider가 너무 자주 무시하지 않도록 하되,
                // handleNewLocation에서 직접 필터링 수행
                setMinUpdateDistanceMeters(MIN_DISTANCE_THRESHOLD.toFloat())
            }
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            stopSelf()
        }
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

    private fun startStillDetection() {
        serviceScope.launch {
            while (isActive) {
                delay(60000L)
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

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {}
        updateNotification()
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