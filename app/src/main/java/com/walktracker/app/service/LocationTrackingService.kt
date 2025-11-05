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
import com.walktracker.app.util.CalorieCalculator
import com.walktracker.app.util.SharedPreferencesManager
import com.walktracker.app.util.AltitudeCalculator
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class LocationTrackingService : Service(), SensorEventListener {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val repository = FirebaseRepository()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // 센서 매니저
    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private var pressureSensor: Sensor? = null

    // 위치 클라이언트
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var activityRecognitionClient: ActivityRecognitionClient

    // SharedPreferences
    private lateinit var syncPrefs: SharedPreferencesManager

    // 고도 계산기
    private val altitudeCalculator = AltitudeCalculator()

    // 데이터
    private var stepsAtStartOfDay = 0L
    private var distanceAtStartOfDay = 0.0
    private var caloriesAtStartOfDay = 0.0
    private var altitudeAtStartOfDay = 0.0

    private var initialStepCount = 0L
    private var currentSteps = 0L
    private var totalDistance = 0.0
    private var totalCalories = 0.0
    private var totalAltitudeGain = 0.0
    private var currentSpeed = 0.0f
    private var isInitialDataLoaded = false
    private var lastProcessedDate: String = ""

    private var previousLocation: Location? = null
    private var currentActivityType = ActivityType.WALKING
    private var previousActivityType = ActivityType.UNKNOWN
    private var activityTransitionTime = 0L
    private var userWeight = 70.0
    private var userStride = 0.7

    private val routePoints = mutableListOf<RoutePoint>()

    private var lastPressureValue = 0f
    private var pressureAtPreviousLocation: Float? = null

    private var stepsSynced: Long = 0
    private var distanceSynced: Double = 0.0
    private var caloriesSynced: Double = 0.0
    private var altitudeSynced: Double = 0.0

    // 배터리 최적화
    private var lastActivityTime = System.currentTimeMillis()
    private var isStillMode = false
    private var transitionLocationBuffer = mutableListOf<Location>()

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "WalkTrackerChannel"

        // 배터리 최적화된 간격
        private const val SYNC_INTERVAL = 900000L // 15분
        private const val LOCATION_INTERVAL_WALKING = 30000L // 30초
        private const val LOCATION_INTERVAL_RUNNING = 15000L // 15초
        private const val LOCATION_INTERVAL_STILL = 300000L // 5분

        private const val MIN_DISTANCE_THRESHOLD = 10.0 // 10m
        private const val MAX_SPEED_MPS = 6.5
        private const val MIN_WALKING_SPEED_MPS = 0.75f
        private const val MAX_TIME_DIFFERENCE_SECONDS = 30L
        private const val MIN_ACCURACY_METERS = 30f

        // 센서 샘플링 간격
        private const val SENSOR_SAMPLING_INTERVAL = 5000000 // 5초

        // 정지 상태 감지
        private const val STILL_DETECTION_TIME = 180000L // 3분

        // 활동 전환
        private const val TRANSITION_STABILIZATION_TIME = 30000L // 30초
        private const val TRANSITION_BUFFER_SIZE = 5

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
    }

    private val activityTypeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val typeString = intent?.getStringExtra(ActivityRecognitionReceiver.EXTRA_ACTIVITY_TYPE)
            val newActivityType = when (typeString) {
                "WALKING" -> ActivityType.WALKING
                "RUNNING" -> ActivityType.RUNNING
                "VEHICLE" -> ActivityType.VEHICLE
                else -> ActivityType.STILL
            }
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
                Log.d(TAG, "UI로부터 위치 업데이트 요청 수신.")
                broadcastLocationUpdate()
            }
        }
    }

    private val resetDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RESET_TODAY_DATA) {
                Log.d(TAG, "오늘 데이터 리셋 요청 수신.")
                resetTodayData()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lastProcessedDate = dateFormat.format(Date())
        Log.d(TAG, "서비스 생성됨 (onCreate)")

        syncPrefs = SharedPreferencesManager(this)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepSensor == null) {
            Log.w(TAG, "걸음수 측정 센서가 없습니다.")
        }

        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (pressureSensor == null) {
            Log.w(TAG, "기압 센서가 없습니다.")
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        activityRecognitionClient = ActivityRecognition.getClient(this)

        loadInitialDailyData()
        loadUserData()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        stepSensor?.let {
            Log.d(TAG, "걸음수 센서 리스너 등록")
            sensorManager.registerListener(this, it, SENSOR_SAMPLING_INTERVAL)
        }

        pressureSensor?.let {
            Log.d(TAG, "기압 센서 리스너 등록")
            sensorManager.registerListener(this, it, SENSOR_SAMPLING_INTERVAL)
        }

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

        startLocationTracking()
        startActivityRecognition()
        startPeriodicSync()
        startStillDetection()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch { syncToFirebase() }
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)

        val localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.unregisterReceiver(activityTypeReceiver)
        localBroadcastManager.unregisterReceiver(locationRequestReceiver)
        localBroadcastManager.unregisterReceiver(resetDataReceiver)

        serviceScope.cancel()
    }

    private fun startPeriodicSync() {
        serviceScope.launch {
            Log.d(TAG, "주기적 동기화 루프 시작.")
            while (isActive) {
                delay(SYNC_INTERVAL)
                syncToFirebase()

                // 랭킹 배치 업데이트
                repository.flushRankingUpdates()
            }
        }
    }

    private suspend fun syncToFirebase(dateToSync: String? = null) {
        val userId = repository.getCurrentUserId()
        if (userId == null) {
            Log.e(TAG, "사용자 ID가 없어 동기화할 수 없습니다.")
            return
        }
        val date = dateToSync ?: dateFormat.format(Date())

        Log.d(TAG, "동기화 시작. 사용자 ID: $userId, 날짜: $date")

        val stepsIncrement = currentSteps - stepsSynced
        val distanceIncrement = totalDistance - distanceSynced
        val caloriesIncrement = totalCalories - caloriesSynced
        val altitudeIncrement = totalAltitudeGain - altitudeSynced

        Log.d(TAG, "계산된 증분: 걸음=$stepsIncrement, 거리=$distanceIncrement, 칼로리=$caloriesIncrement, 고도=$altitudeIncrement")

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
            val result = repository.incrementDailyActivity(
                userId = userId,
                date = date,
                steps = totalStepsToSync,
                distance = totalDistanceToSync,
                calories = totalCaloriesToSync,
                altitude = totalAltitudeToSync,
                routes = routesToSync
            )
            Log.d(TAG, "Firebase 동기화 시도")

            if (result.isSuccess) {
                Log.d(TAG, "Firebase 동기화 성공!")
                syncPrefs.clearUnsyncedData()
                routePoints.clear()
                stepsSynced = currentSteps
                distanceSynced = totalDistance
                caloriesSynced = totalCalories
                altitudeSynced = totalAltitudeGain
            } else {
                Log.e(TAG, "Firebase 동기화 실패", result.exceptionOrNull())
                syncPrefs.addUnsyncedData(stepsIncrement, distanceIncrement, caloriesIncrement, altitudeIncrement)
            }
        } else {
            Log.d(TAG, "동기화할 데이터가 없습니다.")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        lastActivityTime = System.currentTimeMillis()

        if (currentActivityType == ActivityType.VEHICLE || currentActivityType == ActivityType.STILL) {
            return
        }

        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val totalStepsFromBoot = event.values[0].toLong()
                if (initialStepCount == 0L) {
                    initialStepCount = totalStepsFromBoot
                    Log.d(TAG, "초기 걸음 수 설정: $initialStepCount")
                }
                currentSteps = totalStepsFromBoot - initialStepCount
                updateAndBroadcast()
            }
            Sensor.TYPE_PRESSURE -> {
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
            from == ActivityType.VEHICLE &&
                    (to == ActivityType.WALKING || to == ActivityType.RUNNING) -> {
                Log.d(TAG, "차량에서 도보 활동으로 전환: 위치 데이터 리셋")
                previousLocation = null
                transitionLocationBuffer.clear()
                currentSpeed = 0.0f
                altitudeCalculator.reset()
                pressureAtPreviousLocation = null
            }

            from == ActivityType.STILL &&
                    (to == ActivityType.WALKING || to == ActivityType.RUNNING) -> {
                Log.d(TAG, "정지 상태에서 활동 시작")
                transitionLocationBuffer.clear()
            }

            (from == ActivityType.WALKING && to == ActivityType.RUNNING) ||
                    (from == ActivityType.RUNNING && to == ActivityType.WALKING) -> {
                Log.d(TAG, "도보 활동 간 전환: 데이터 유지")
                transitionLocationBuffer.clear()
            }
        }
    }

    private fun isInTransitionPeriod(): Boolean {
        return System.currentTimeMillis() - activityTransitionTime < TRANSITION_STABILIZATION_TIME
    }

    private fun handleNewLocation(location: Location) {
        if (location.hasAccuracy() && location.accuracy > MIN_ACCURACY_METERS) {
            if (location.speed < 0.3f) {
                Log.d(TAG, "GPS 정확도가 낮고 속도가 느려 무시")
                previousLocation = location
                return
            }
        }

        if (currentActivityType == ActivityType.VEHICLE || currentActivityType == ActivityType.STILL) {
            previousLocation = location
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
            Log.d(TAG, "전환 안정화 기간: 위치 버퍼링 중")
            previousLocation = location
            return
        }

        if (previousLocation == null) {
            previousLocation = location
            Log.d(TAG, "시작 위치 설정")
            return
        }

        val prev = previousLocation!!
        val timeDifferenceSeconds = (location.time - prev.time) / 1000
        if (timeDifferenceSeconds <= 0) return

        if (timeDifferenceSeconds > MAX_TIME_DIFFERENCE_SECONDS) {
            Log.d(TAG, "시간 간격 초과: ${timeDifferenceSeconds}초")
            previousLocation = location
            return
        }

        val distance = prev.distanceTo(location)
        if (distance < MIN_DISTANCE_THRESHOLD) return

        val speed = calculateSpeed(location, prev, distance, timeDifferenceSeconds)

        if (speed > MAX_SPEED_MPS) {
            Log.d(TAG, "최대 속도 초과: ${String.format("%.2f", speed)}m/s")
            previousLocation = location
            return
        }

        currentSpeed = speed

        if (currentActivityType == ActivityType.WALKING && speed < MIN_WALKING_SPEED_MPS) {
            Log.d(TAG, "걷기 속도 미달")
            previousLocation = location
            return
        }

        lastActivityTime = System.currentTimeMillis()
        totalDistance += distance / 1000.0

        val isMoving = speed > MIN_WALKING_SPEED_MPS
        if (pressureSensor != null) {
            val altitudeGain = altitudeCalculator.calculateAltitudeGain(
                currentPressure = lastPressureValue,
                currentTime = location.time,
                isMoving = isMoving
            )
            if (altitudeGain > 0) {
                totalAltitudeGain += altitudeGain
            }
        }

        if (stepSensor == null && userStride > 0) {
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

        updateAndBroadcast()
        previousLocation = location
        broadcastLocationUpdate()
    }

    private fun calculateSpeed(
        location: Location,
        previousLocation: Location,
        distance: Float,
        timeDiffSeconds: Long
    ): Float {
        if (location.hasSpeed() && location.speed > 0) {
            return location.speed
        }

        val calculatedSpeed = distance / timeDiffSeconds

        if (currentSpeed > 0) {
            val speedDiff = abs(calculatedSpeed - currentSpeed)
            val maxSpeedChange = 2.0f

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
        Log.d(TAG, "오늘 데이터 리셋")
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
        updateAndBroadcast()
    }

    private fun updateAndBroadcast() {
        if (!isInitialDataLoaded) return
        updateNotification()
        broadcastActivityUpdate()
    }

    private fun broadcastActivityUpdate() {
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
            }
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
                    Log.d(TAG, "초기 데이터 로드: 걸음=${it.steps}, 거리=${it.distance}")
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
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val totalStepsToday = stepsAtStartOfDay + currentSteps
        val totalDistanceToday = distanceAtStartOfDay + totalDistance

        val contentText = if (isStillMode) {
            "절전 모드 - 오늘: ${totalStepsToday}걸음"
        } else {
            String.format(Locale.US, "오늘: %d걸음 • %.2fkm", totalStepsToday, totalDistanceToday)
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

    private fun updateLocationRequest() {
        if (currentActivityType == ActivityType.VEHICLE) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            enterStillMode()
            return
        }

        val (priority, interval) = when (currentActivityType) {
            ActivityType.RUNNING -> Priority.PRIORITY_HIGH_ACCURACY to LOCATION_INTERVAL_RUNNING
            ActivityType.WALKING -> Priority.PRIORITY_BALANCED_POWER_ACCURACY to LOCATION_INTERVAL_WALKING
            ActivityType.STILL -> Priority.PRIORITY_LOW_POWER to LOCATION_INTERVAL_STILL
            else -> Priority.PRIORITY_LOW_POWER to LOCATION_INTERVAL_STILL
        }

        val locationRequest = LocationRequest.Builder(priority, interval).apply {
            setMinUpdateIntervalMillis(interval)
            setMaxUpdateDelayMillis(interval * 3)
            setWaitForAccurateLocation(false)
            if (currentActivityType != ActivityType.RUNNING) {
                setMinUpdateDistanceMeters(MIN_DISTANCE_THRESHOLD.toFloat())
            }
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            Log.d(TAG, "위치 요청 업데이트: $currentActivityType")
        } catch (e: SecurityException) {
            Log.e(TAG, "위치 정보 접근 권한 없음", e)
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
        val request = ActivityTransitionRequest(listOf(
            ActivityTransition.Builder().setActivityType(DetectedActivity.WALKING).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
            ActivityTransition.Builder().setActivityType(DetectedActivity.RUNNING).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
            ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
            ActivityTransition.Builder().setActivityType(DetectedActivity.STILL).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()
        ))
        val intent = Intent(this, ActivityRecognitionReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        try {
            activityRecognitionClient.requestActivityTransitionUpdates(request, pendingIntent)
        } catch (e: SecurityException) {
            Log.e(TAG, "활동 인식 권한 없음", e)
        }
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
        Log.d(TAG, "절전 모드 진입")

        sensorManager.unregisterListener(this)

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_LOW_POWER, 600000L).build()
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "위치 권한 오류", e)
        }

        updateNotification()
    }

    private fun exitStillMode() {
        if (!isStillMode) return
        isStillMode = false
        Log.d(TAG, "절전 모드 해제")

        stepSensor?.let {
            sensorManager.registerListener(this, it, SENSOR_SAMPLING_INTERVAL)
        }
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SENSOR_SAMPLING_INTERVAL)
        }

        updateNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}