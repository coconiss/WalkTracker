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
import android.os.Build
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
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class LocationTrackingService : Service(), SensorEventListener {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val repository = FirebaseRepository()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // 센서 매니저
    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private var pressureSensor: Sensor? = null // 기압 센서

    // 위치 클라이언트
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var activityRecognitionClient: ActivityRecognitionClient

    // SharedPreferences
    private lateinit var syncPrefs: SharedPreferencesManager

    // 데이터
    private var stepsAtStartOfDay = 0L
    private var distanceAtStartOfDay = 0.0
    private var caloriesAtStartOfDay = 0.0
    private var altitudeAtStartOfDay = 0.0 // 오늘 시작 시점의 누적 고도

    // 이 서비스 세션에서만 유효한 값들
    private var initialStepCount = 0L // 재부팅 시 초기화되므로 영구 저장하지 않음
    private var currentSteps = 0L // 현재 서비스 세션 동안의 걸음 수
    private var totalDistance = 0.0 // 현재 서비스 세션 동안의 거리
    private var totalCalories = 0.0 // 현재 서비스 세션 동안의 칼로리
    private var totalAltitudeGain = 0.0 // 현재 서비스 세션 동안의 누적 상승 고도
    private var currentSpeed = 0.0f // 현재 속도(m/s)
    private var isInitialDataLoaded = false // Firestore에서 초기 데이터를 로드했는지 여부
    private var lastProcessedDate: String = "" // 날짜 변경을 감지하기 위한 변수

    private var previousLocation: Location? = null
    private var currentActivityType = ActivityType.WALKING
    private var userWeight = 70.0 // 기본값
    private var userStride = 0.7 // 기본 보폭

    private val routePoints = mutableListOf<RoutePoint>()

    private var lastPressureValue = 0f // 마지막으로 측정된 기압 값
    private var pressureAtPreviousLocation: Float? = null // 마지막으로 이동이 감지된 지점의 기압 값

    // 동기화 기준 값
    private var stepsSynced: Long = 0
    private var distanceSynced: Double = 0.0
    private var caloriesSynced: Double = 0.0
    private var altitudeSynced: Double = 0.0 // 동기화된 마지막 고도

    private val activityTypeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val typeString = intent?.getStringExtra(ActivityRecognitionReceiver.EXTRA_ACTIVITY_TYPE)
            currentActivityType = when (typeString) {
                "WALKING" -> ActivityType.WALKING
                "RUNNING" -> ActivityType.RUNNING
                "VEHICLE" -> ActivityType.VEHICLE
                else -> ActivityType.STILL
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


    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "WalkTrackerChannel"
        private const val SYNC_INTERVAL = 600000L // 10분 //이후 상황에 맞게 변경
        private const val MIN_DISTANCE_THRESHOLD = 1.0 // 1m
        private const val MAX_SPEED_MPS = 6.5 //(약 23.4km/h)
        private const val MAX_TIME_DIFFERENCE_SECONDS = 15L // 15초. 위치 업데이트 간 최대 허용 시간
        private const val ERROR_NOTIFICATION_ID = 1002
        private const val TAG = "LocationTrackingService"

        const val ACTION_ACTIVITY_UPDATE = "com.walktracker.app.ACTIVITY_UPDATE"
        const val ACTION_RESET_TODAY_DATA = "com.walktracker.app.RESET_TODAY_DATA"
        const val EXTRA_STEPS = "extra_steps"
        const val EXTRA_DISTANCE = "extra_distance"
        const val EXTRA_CALORIES = "extra_calories"
        const val EXTRA_ALTITUDE = "extra_altitude" // 고도 extra 추가
        const val EXTRA_SPEED = "extra_speed"

        const val ACTION_LOCATION_UPDATE = "com.walktracker.app.LOCATION_UPDATE"
        const val ACTION_REQUEST_LOCATION_UPDATE = "com.walktracker.app.REQUEST_LOCATION_UPDATE"
        const val EXTRA_LOCATION = "extra_location"
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        lastProcessedDate = dateFormat.format(Date()) // 서비스 생성 시점의 날짜로 초기화
        Log.d(TAG, "서비스 생성됨 (onCreate)")

        syncPrefs = SharedPreferencesManager(this)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepSensor == null) {
            Log.w(TAG, "걸음수 측정 센서(STEP_COUNTER)가 이 기기에 없습니다.")
        }
        // 기압 센서 초기화
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (pressureSensor == null) {
            Log.w(TAG, "기압 센서(PRESSURE)가 이 기기에 없습니다.")
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        activityRecognitionClient = ActivityRecognition.getClient(this)

        loadInitialDailyData()
        loadUserData()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        stepSensor?.let {
            Log.d(TAG, "걸음수 센서 리스너 등록")
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        // 기압 센서 리스너 등록
        pressureSensor?.let {
            Log.d(TAG, "기압 센서 리스너 등록")
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
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
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.launch { syncToFirebase() } // 서비스 종료 직전 마지막 동기화 시도
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
            }
        }
    }

    private suspend fun syncToFirebase(dateToSync: String? = null) {
        val userId = repository.getCurrentUserId()
        if (userId == null) {
            Log.e(TAG, "사용자 ID가 없어 동기화할 수 없습니다. 동기화를 건너뜁니다.")
            return
        }
        val date = dateToSync ?: dateFormat.format(Date())

        Log.d(TAG, "동기화 시작. 사용자 ID: $userId, 날짜: $date")

        val stepsIncrement = currentSteps - stepsSynced
        val distanceIncrement = totalDistance - distanceSynced
        val caloriesIncrement = totalCalories - caloriesSynced
        val altitudeIncrement = totalAltitudeGain - altitudeSynced // 고도 증가량

        Log.d(TAG, "계산된 증분: 걸음=$stepsIncrement, 거리=$distanceIncrement, 칼로리=$caloriesIncrement, 고도=$altitudeIncrement")

        val unsyncedData = syncPrefs.getUnsyncedData()
        val unsyncedSteps = unsyncedData["steps"] as? Long ?: 0L
        val unsyncedDistance = unsyncedData["distance"] as? Double ?: 0.0
        val unsyncedCalories = unsyncedData["calories"] as? Double ?: 0.0
        val unsyncedAltitude = unsyncedData["altitude"] as? Double ?: 0.0

        Log.d(TAG, "가져온 미동기 데이터: 걸음=$unsyncedSteps, 거리=$unsyncedDistance, 칼로리=$unsyncedCalories, 고도=$unsyncedAltitude")

        val totalStepsToSync = stepsIncrement + unsyncedSteps
        val totalDistanceToSync = distanceIncrement + unsyncedDistance
        val totalCaloriesToSync = caloriesIncrement + unsyncedCalories
        val totalAltitudeToSync = altitudeIncrement + unsyncedAltitude
        val routesToSync = routePoints.toList()

        Log.d(TAG, "총 동기화할 데이터: 걸음=$totalStepsToSync, 거리=$totalDistanceToSync, 칼로리=$totalCaloriesToSync, 고도=$totalAltitudeToSync")

        if (totalStepsToSync > 0 || routesToSync.isNotEmpty()) { // 조건 수정: 걸음수 또는 새로운 경로 지점이 있을 때만 동기화
            val result = repository.incrementDailyActivity(
                userId = userId,
                date = date,
                steps = totalStepsToSync,
                distance = totalDistanceToSync,
                calories = totalCaloriesToSync,
                altitude = totalAltitudeToSync,
                routes = routesToSync
            )
            Log.d(TAG, "Firebase 동기화 시도 날짜=$date,  걸음=$totalStepsToSync, 거리=${String.format(Locale.US, "%.5f", totalDistanceToSync)}km, 고도=${totalAltitudeToSync}m")

            if (result.isSuccess) {
                Log.d(TAG, "Firebase 동기화 성공!")
                syncPrefs.clearUnsyncedData()
                routePoints.clear()
            } else {
                Log.e(TAG, "!!! Firebase 동기화 실패 !!!", result.exceptionOrNull())
                syncPrefs.addUnsyncedData(stepsIncrement, distanceIncrement, caloriesIncrement, altitudeIncrement)
            }
        } else {
            Log.d(TAG, "동기화할 데이터가 없습니다. 동기화를 건너뜁니다.")
        }

        stepsSynced = currentSteps
        distanceSynced = totalDistance
        caloriesSynced = totalCalories
        altitudeSynced = totalAltitudeGain // 동기화된 고도 업데이트
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (currentActivityType == ActivityType.VEHICLE || currentActivityType == ActivityType.STILL) {
            Log.d(TAG, "차량 탑승 또는 정지 중이므로 센서 데이터를 무시합니다.")
            return
        }
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val totalStepsFromBoot = event.values[0].toLong()
                if (initialStepCount == 0L) {
                    initialStepCount = totalStepsFromBoot
                    Log.d(TAG, "초기 걸음 수 설정 (현재 세션): $initialStepCount")
                }
                currentSteps = totalStepsFromBoot - initialStepCount
                updateAndBroadcast()
            }
            Sensor.TYPE_PRESSURE -> {
                val pressure = event.values[0]
                if (pressure > 0f) {
                    lastPressureValue = pressure
                }
            }
        }
    }

    private fun handleNewLocation(location: Location) {
        if (currentActivityType == ActivityType.VEHICLE || currentActivityType == ActivityType.STILL) {
            Log.d(TAG, "차량 탑승 또는 정지 중이므로 위치 업데이트를 이용한 계산을 건너뜁니다.")
            previousLocation = location // 다음 계산을 위해 이전 위치는 업데이트
            return
        }

        val currentDate = dateFormat.format(Date())

        if (lastProcessedDate.isNotEmpty() && lastProcessedDate != currentDate) {
            Log.d(TAG, "날짜가 $lastProcessedDate 에서 $currentDate 로 변경되었습니다. 데이터 동기화 및 세션 초기화를 수행합니다.")
            serviceScope.launch {
                syncToFirebase(lastProcessedDate) // 이전 날짜로 데이터 동기화
                resetTodayData()
                loadInitialDailyData()
            }
        }
        lastProcessedDate = currentDate

        previousLocation?.let { prev ->
            val timeDifferenceSeconds = (location.time - prev.time) / 1000
            if (timeDifferenceSeconds <= 0) return@let

            // 시간 간격이 너무 길면(예: 지하철 이동) 위치를 무시
            if (timeDifferenceSeconds > MAX_TIME_DIFFERENCE_SECONDS) {
                Log.d(TAG, "위치 업데이트 시간 간격이 너무 깁니다(${timeDifferenceSeconds}초). 위치 처리를 건너뛰고 현재 위치를 시작점으로 설정합니다.")
                return@let
            }

            val distance = prev.distanceTo(location)
            if (distance < MIN_DISTANCE_THRESHOLD) return@let

            val speed = if (location.hasSpeed()) location.speed else (distance / timeDifferenceSeconds).toFloat()
            if (speed > MAX_SPEED_MPS) {
                Log.d(TAG, "최대 속도($MAX_SPEED_MPS)를 초과하여 무시합니다: $speed m/s")
                return@let
            }
            currentSpeed = speed

            totalDistance += distance / 1000.0

            var altitudeChange = 0.0
            // 고도 변화 계산: 기압 센서가 있으면 우선 사용하고, 없으면 GPS 고도를 사용합니다.
            // 실제 위치 이동이 감지되었을 때만 고도 변화를 계산합니다.
            if (pressureSensor != null && lastPressureValue > 0f) {
                // 기압 센서를 이용한 고도 계산
                pressureAtPreviousLocation?.let { prevPressure ->
                    val pressureDifference = Math.abs(lastPressureValue - prevPressure)
                    if (pressureDifference > 0.12f) { // 0.12hPa 이상 변화 시 고도 변화 계산
                        val currentAltitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, lastPressureValue)
                        val previousAltitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, prevPressure)
                        val change = (currentAltitude - previousAltitude).toDouble()
                        if (change > 0) { // 상승 고도만 반영
                            totalAltitudeGain += change
                            altitudeChange = change
                        }
                    }
                }
                pressureAtPreviousLocation = lastPressureValue
            } else if (location.hasAltitude() && prev.hasAltitude()) {
                // GPS를 이용한 고도 계산 (Fallback)
                val change = location.altitude - prev.altitude
                if (change > 0.5) { // GPS 고도 노이즈를 고려하여 0.5m 이상 상승 시에만 반영
                    totalAltitudeGain += change
                    altitudeChange = change
                }
            }

            if (stepSensor == null) {
                if (userStride > 0) {
                    currentSteps = (totalDistance * 1000 / userStride).toLong()
                }
            }

            val caloriesBurned = CalorieCalculator.calculate(
                weightKg = userWeight,
                speedMps = speed,
                durationSeconds = timeDifferenceSeconds,
                activityType = currentActivityType,
                elevationGainMeters = if (altitudeChange > 0) altitudeChange else 0.0
            )
            totalCalories += caloriesBurned

            routePoints.add(RoutePoint(location.latitude, location.longitude, location.time, currentActivityType.name, speed.toDouble()))
            updateAndBroadcast()
        }
        previousLocation = location
        broadcastLocationUpdate()
    }

    private fun resetTodayData() {
        Log.d(TAG, "오늘의 모든 활동 데이터를 리셋합니다.")

        // 세션 시작 시 Firestore에서 가져온 값
        stepsAtStartOfDay = 0L
        distanceAtStartOfDay = 0.0
        caloriesAtStartOfDay = 0.0
        altitudeAtStartOfDay = 0.0

        // 현재 세션에서 누적된 값
        currentSteps = 0L
        totalDistance = 0.0
        totalCalories = 0.0
        totalAltitudeGain = 0.0

        // 동기화 기준 값
        stepsSynced = 0L
        distanceSynced = 0.0
        caloriesSynced = 0.0
        altitudeSynced = 0.0

        // 센서 관련 값
        initialStepCount = 0L // 다음 onSensorChanged에서 다시 설정됨
        lastPressureValue = 0f
        pressureAtPreviousLocation = null // 고도 계산을 위한 이전 위치의 기압 리셋

        // 위치 및 경로
        previousLocation = null
        routePoints.clear()

        // 미동기화된 데이터 클리어
        syncPrefs.clearUnsyncedData()

        // UI에 즉시 반영
        updateAndBroadcast()
    }

    private fun updateAndBroadcast() {
        if (!isInitialDataLoaded) {
            Log.d(TAG, "초기 데이터 로딩 전이므로 UI 업데이트를 건너뜁니다.")
            return
        }
        updateNotification()
        broadcastActivityUpdate()
    }

    private fun broadcastActivityUpdate() {
        val totalStepsToday = stepsAtStartOfDay + currentSteps
        val totalDistanceToday = distanceAtStartOfDay + totalDistance
        val totalCaloriesToday = caloriesAtStartOfDay + totalCalories
        val totalAltitudeToday = altitudeAtStartOfDay + totalAltitudeGain // 오늘 총 상승 고도

        val intent = Intent(ACTION_ACTIVITY_UPDATE).apply {
            putExtra(EXTRA_STEPS, totalStepsToday)
            putExtra(EXTRA_DISTANCE, totalDistanceToday)
            putExtra(EXTRA_CALORIES, totalCaloriesToday)
            putExtra(EXTRA_ALTITUDE, totalAltitudeToday) // 고도 데이터 추가
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
                    Log.d(TAG, "초기 데이터 로드 성공: 걸음=${it.steps}, 거리=${it.distance}, 칼로리=${it.calories}, 고도=${it.altitude}")
                    stepsAtStartOfDay = it.steps
                    distanceAtStartOfDay = it.distance
                    caloriesAtStartOfDay = it.calories
                    altitudeAtStartOfDay = it.altitude
                }
                isInitialDataLoaded = true
                updateAndBroadcast()
                Log.d(TAG, "초기 데이터 로드 완료. UI 업데이트를 시작합니다.")
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "걷기 추적", NotificationManager.IMPORTANCE_LOW)
        val errorChannel = NotificationChannel("ErrorChannel", "오류 알림", NotificationManager.IMPORTANCE_HIGH)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        notificationManager.createNotificationChannel(errorChannel)
    }

    private fun createNotification(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val totalStepsToday = stepsAtStartOfDay + currentSteps
        val totalDistanceToday = distanceAtStartOfDay + totalDistance

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("걷기 추적 중")
            .setContentText(String.format(Locale.US, "오늘: %d걸음 • %.2fkm", totalStepsToday, totalDistanceToday))
            .setSmallIcon(R.drawable.ic_splash_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showErrorNotification(message: String) {
        val notification = NotificationCompat.Builder(this, "ErrorChannel")
            .setContentTitle("위치 추적 오류")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_splash_logo)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(ERROR_NOTIFICATION_ID, notification)
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification())
    }

    private fun startLocationTracking() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000L).apply {
            setMinUpdateIntervalMillis(5000L)
            setMaxUpdateDelayMillis(15000L)
        }.build()

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "위치 정보 접근 권한이 없습니다.", e)
            stopSelf()
        }
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
            Log.e(TAG, "활동 인식 권한이 없습니다.", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
