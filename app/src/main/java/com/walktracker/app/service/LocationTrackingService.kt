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

    // 위치 클라이언트
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var activityRecognitionClient: ActivityRecognitionClient

    // SharedPreferences
    private lateinit var syncPrefs: SharedPreferencesManager

    // 데이터
    private var stepsAtStartOfDay = 0L
    private var distanceAtStartOfDay = 0.0
    private var caloriesAtStartOfDay = 0.0
    
    // 이 서비스 세션에서만 유효한 값들
    private var initialStepCount = 0L // 재부팅 시 초기화되므로 영구 저장하지 않음
    private var currentSteps = 0L // 현재 서비스 세션 동안의 걸음 수
    private var totalDistance = 0.0 // 현재 서비스 세션 동안의 거리
    private var totalCalories = 0.0 // 현재 서비스 세션 동안의 칼로리
    private var isInitialDataLoaded = false // Firestore에서 초기 데이터를 로드했는지 여부
    private var lastProcessedDate: String = "" // 날짜 변경을 감지하기 위한 변수

    private var previousLocation: Location? = null
    private var currentActivityType = ActivityType.WALKING
    private var userWeight = 70.0 // 기본값
    private var userStride = 0.7 // 기본 보폭

    private val routePoints = mutableListOf<RoutePoint>()

    // 동기화 기준 값
    private var stepsSynced: Long = 0
    private var distanceSynced: Double = 0.0
    private var caloriesSynced: Double = 0.0

    private val activityTypeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val typeString = intent?.getStringExtra(ActivityRecognitionReceiver.EXTRA_ACTIVITY_TYPE)
            currentActivityType = when (typeString) {
                "WALKING" -> ActivityType.WALKING
                "RUNNING" -> ActivityType.RUNNING
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

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "WalkTrackerChannel"
        private const val SYNC_INTERVAL = 60000L // 1분
        private const val MIN_DISTANCE_THRESHOLD = 1.0 // 1m
        private const val MAX_SPEED_MPS = 15.0
        private const val ERROR_NOTIFICATION_ID = 1002
        private const val TAG = "LocationTrackingService"

        const val ACTION_ACTIVITY_UPDATE = "com.walktracker.app.ACTIVITY_UPDATE"
        const val EXTRA_STEPS = "extra_steps"
        const val EXTRA_DISTANCE = "extra_distance"
        const val EXTRA_CALORIES = "extra_calories"

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
        // initialStepCount는 영구 저장하지 않으므로 여기서 로드하는 코드를 제거합니다.

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepSensor == null) {
            Log.w(TAG, "걸음수 측정 센서(STEP_COUNTER)가 이 기기에 없습니다.")
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        activityRecognitionClient = ActivityRecognition.getClient(this)

        // 중요: UI 업데이트를 시작하기 전에 반드시 초기 데이터를 먼저 로드합니다.
        loadInitialDailyData()
        loadUserData()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        stepSensor?.let {
            Log.d(TAG, "걸음수 센서 리스너 등록")
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

    private suspend fun syncToFirebase() {
        val userId = repository.getCurrentUserId()
        if (userId == null) {
            Log.e(TAG, "사용자 ID가 없어 동기화할 수 없습니다. 동기화를 건너뜁니다.")
            return
        }
        Log.d(TAG, "동기화 시작. 사용자 ID: $userId")

        val stepsIncrement = currentSteps - stepsSynced
        val distanceIncrement = totalDistance - distanceSynced
        val caloriesIncrement = totalCalories - caloriesSynced
        
        Log.d(TAG, "계산된 증분: 걸음=$stepsIncrement, 거리=$distanceIncrement, 칼로리=$caloriesIncrement")

        val (unsyncedSteps, unsyncedDistance, unsyncedCalories) = syncPrefs.getUnsyncedData()
        Log.d(TAG, "가져온 미동기 데이터: 걸음=$unsyncedSteps, 거리=$unsyncedDistance, 칼로리=$unsyncedCalories")

        val totalStepsToSync = stepsIncrement + unsyncedSteps
        val totalDistanceToSync = distanceIncrement + unsyncedDistance
        val totalCaloriesToSync = caloriesIncrement + unsyncedCalories
        val routesToSync = routePoints.toList()
        
        Log.d(TAG, "총 동기화할 데이터: 걸음=$totalStepsToSync, 거리=$totalDistanceToSync, 칼로리=$totalCaloriesToSync")
        val dateSync = dateFormat.format(Date())

        if (totalStepsToSync > 0 || totalDistanceToSync > 0) {
            val result = repository.incrementDailyActivity(
                userId = userId,
                date = dateSync,
                steps = totalStepsToSync,
                distance = totalDistanceToSync,
                calories = totalCaloriesToSync,
                routes = routesToSync
            )
            Log.d(TAG, "Firebase 동기화 시도 날짜=$dateSync,  걸음=$totalStepsToSync, 거리=${String.format(Locale.US, "%.5f", totalDistanceToSync)}km")

            if (result.isSuccess) {
                Log.d(TAG, "Firebase 동기화 성공!")
                syncPrefs.clearUnsyncedData()
                routePoints.clear()
            } else {
                Log.e(TAG, "!!! Firebase 동기화 실패 !!!", result.exceptionOrNull())
                syncPrefs.addUnsyncedData(stepsIncrement, distanceIncrement, caloriesIncrement)
            }
        } else {
            Log.d(TAG, "동기화할 데이터가 없습니다. 동기화를 건너뜁니다.")
        }

        stepsSynced = currentSteps
        distanceSynced = totalDistance
        caloriesSynced = totalCalories
    }

    // In onSensorChanged method
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val totalStepsFromBoot = event.values[0].toLong()
            if (initialStepCount == 0L) {
                initialStepCount = totalStepsFromBoot
                Log.d(TAG, "초기 걸음 수 설정 (현재 세션): $initialStepCount")
            }
            currentSteps = totalStepsFromBoot - initialStepCount

            // 여기서 totalDistance를 계산하는 코드를 제거합니다.
            // totalDistance = (currentSteps * userStride) / 1000.0

            updateAndBroadcast() // 걸음수 변경을 UI에 알리기 위해 호출은 유지
        }
    }


    private fun handleNewLocation(location: Location) {
        val currentDate = dateFormat.format(Date())

        // ★★★★★ 날짜 변경 감지 및 처리 로직 ★★★★★
        if (lastProcessedDate.isNotEmpty() && lastProcessedDate != currentDate) {
            Log.d(TAG, "날짜가 $lastProcessedDate 에서 $currentDate 로 변경되었습니다. 데이터 동기화 및 세션 초기화를 수행합니다.")

            // 날짜가 변경되었으므로 즉시 동기화를 실행하여 이전 날짜의 남은 데이터를 마무리합니다.
            // (참고: syncToFirebase가 이전 날짜를 인자로 받도록 수정하면 더 완벽해집니다.)
            serviceScope.launch {
                syncToFirebase() // 이전 날짜의 남은 데이터 동기화

                // 동기화 후, 모든 세션 관련 변수들을 0 또는 초기 상태로 리셋합니다.
                Log.d(TAG, "세션 변수를 초기화합니다.")
                initialStepCount = 0L
                currentSteps = 0L
                totalDistance = 0.0
                totalCalories = 0.0
                stepsSynced = 0L
                distanceSynced = 0.0
                caloriesSynced = 0.0
                routePoints.clear()

                // DB로부터 새로운 날짜(오늘)의 데이터를 다시 로드합니다. (보통 0으로 시작)
                loadInitialDailyData()
            }
        }
        // 현재 처리 중인 날짜를 업데이트합니다.
        lastProcessedDate = currentDate

        previousLocation?.let { prev ->
            val timeDifferenceSeconds = (location.time - prev.time) / 1000
            if (timeDifferenceSeconds <= 0) return@let

            val distance = prev.distanceTo(location)
            if (distance < MIN_DISTANCE_THRESHOLD) return@let

            val speed = if (location.hasSpeed()) location.speed else distance / timeDifferenceSeconds
            if (speed > MAX_SPEED_MPS) {
                Log.d(TAG, "최대 속도($MAX_SPEED_MPS)를 초과하여 무시합니다: $speed m/s")
                return@let
            }

            // [수정됨] 센서 유무와 관계없이 항상 GPS 기반 거리를 누적합니다.
            totalDistance += distance / 1000.0

            // [수정됨] 걸음수 센서가 없을 경우에만, GPS 거리 기반으로 걸음수를 역산합니다.
            if (stepSensor == null) {
                // 사용자의 보폭(stride)이 설정되어 있어야 의미가 있습니다.
                if (userStride > 0) {
                    currentSteps = (totalDistance * 1000 / userStride).toLong()
                }
            }

            val caloriesBurned = CalorieCalculator.calculate(
                weightKg = userWeight,
                speedMps = speed,
                durationSeconds = timeDifferenceSeconds,
                activityType = currentActivityType
            )
            totalCalories += caloriesBurned

            routePoints.add(RoutePoint(location.latitude, location.longitude, location.time, currentActivityType.name))
            updateAndBroadcast()
        }
        previousLocation = location
        broadcastLocationUpdate() // 새 위치를 즉시 브로드캐스트합니다.
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

        val intent = Intent(ACTION_ACTIVITY_UPDATE).apply {
            putExtra(EXTRA_STEPS, totalStepsToday)
            putExtra(EXTRA_DISTANCE, totalDistanceToday)
            putExtra(EXTRA_CALORIES, totalCaloriesToday)
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
                isInitialDataLoaded = true // 데이터를 로드할 수 없으므로, 바로 진행합니다.
                return@launch
            }
            val date = dateFormat.format(Date())
            repository.getDailyActivityOnce(userId, date) { activity ->
                activity?.let {
                    Log.d(TAG, "초기 데이터 로드 성공: 걸음=${it.steps}, 거리=${it.distance}, 칼로리=${it.calories}")
                    stepsAtStartOfDay = it.steps
                    distanceAtStartOfDay = it.distance
                    caloriesAtStartOfDay = it.calories
                }
                // 데이터 로드가 완료되었으므로, UI 업데이트를 허용하고 현재 상태를 한번 전송합니다.
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
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showErrorNotification(message: String) {
        val notification = NotificationCompat.Builder(this, "ErrorChannel")
            .setContentTitle("위치 추적 오류")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
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
        val request = ActivityTransitionRequest(listOf(ActivityTransition.Builder().setActivityType(DetectedActivity.WALKING).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(), ActivityTransition.Builder().setActivityType(DetectedActivity.RUNNING).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(), ActivityTransition.Builder().setActivityType(DetectedActivity.IN_VEHICLE).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(), ActivityTransition.Builder().setActivityType(DetectedActivity.STILL).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()))
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