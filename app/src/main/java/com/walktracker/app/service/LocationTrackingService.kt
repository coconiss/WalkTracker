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
    private var initialStepCount = 0L
    private var currentSteps = 0L
    private var previousLocation: Location? = null
    private var totalDistance = 0.0
    private var totalCalories = 0.0
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

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "WalkTrackerChannel"
        private const val SYNC_INTERVAL = 3600000L // 60분
        private const val MIN_DISTANCE_THRESHOLD = 1.0 // 1m
        private const val MAX_SPEED_MPS = 15.0
        private const val ERROR_NOTIFICATION_ID = 1002
        private const val TAG = "LocationTrackingService"

        const val ACTION_ACTIVITY_UPDATE = "com.walktracker.app.ACTIVITY_UPDATE"
        const val EXTRA_STEPS = "extra_steps"
        const val EXTRA_DISTANCE = "extra_distance"
        const val EXTRA_CALORIES = "extra_calories"
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "서비스 생성됨 (onCreate)")

        syncPrefs = SharedPreferencesManager(this)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepSensor == null) {
            Log.w(TAG, "걸음수 측정 센서(STEP_COUNTER)가 이 기기에 없습니다.")
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        activityRecognitionClient = ActivityRecognition.getClient(this)

        loadUserData()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        stepSensor?.let {
            Log.d(TAG, "걸음수 센서 리스너 등록")
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        LocalBroadcastManager.getInstance(this).registerReceiver(
            activityTypeReceiver,
            IntentFilter(ActivityRecognitionReceiver.ACTION_ACTIVITY_TYPE_UPDATE)
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
        LocalBroadcastManager.getInstance(this).unregisterReceiver(activityTypeReceiver)
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
            Log.e(TAG, "사용자 ID가 없어 동기화할 수 없습니다.")
            return
        }

        // 현재까지의 증분 계산
        val stepsIncrement = currentSteps - stepsSynced
        val distanceIncrement = totalDistance - distanceSynced
        val caloriesIncrement = totalCalories - caloriesSynced

        // 이전에 동기화 실패한 데이터 가져오기
        val (unsyncedSteps, unsyncedDistance, unsyncedCalories) = syncPrefs.getUnsyncedData()

        // 총 동기화할 데이터 = 현재 증분 + 이월된 데이터
        val totalStepsToSync = stepsIncrement + unsyncedSteps
        val totalDistanceToSync = distanceIncrement + unsyncedDistance
        val totalCaloriesToSync = caloriesIncrement + unsyncedCalories
        val routesToSync = routePoints.toList()

        if (totalStepsToSync > 0 || totalDistanceToSync > 0) {
            Log.d(TAG, "Firebase 동기화 시도: 걸음=$totalStepsToSync, 거리=${String.format(Locale.US, "%.5f", totalDistanceToSync)}km")

            val result = repository.incrementDailyActivity(
                userId = userId,
                date = dateFormat.format(Date()),
                steps = totalStepsToSync,
                distance = totalDistanceToSync,
                calories = totalCaloriesToSync,
                routes = routesToSync
            )

            if (result.isSuccess) {
                Log.d(TAG, "Firebase 동기화 성공!")
                syncPrefs.clearUnsyncedData() // 이월 데이터 삭제
                routePoints.clear()
            } else {
                Log.e(TAG, "!!! Firebase 동기화 실패 !!!", result.exceptionOrNull())
                // 실패한 경우, 현재 증분 데이터를 이월 데이터에 추가
                syncPrefs.addUnsyncedData(stepsIncrement, distanceIncrement, caloriesIncrement)
            }
        }

        // 동기화 성공 여부와 관계없이, 마지막 동기화 시점의 데이터는 현재 값으로 갱신
        stepsSynced = currentSteps
        distanceSynced = totalDistance
        caloriesSynced = totalCalories
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            if (initialStepCount == 0L) {
                initialStepCount = event.values[0].toLong()
            }
            currentSteps = event.values[0].toLong() - initialStepCount
            totalDistance = (currentSteps * userStride) / 1000.0
            totalCalories = CalorieCalculator.calculate(userWeight, totalDistance, currentActivityType)
            updateAndBroadcast()
        }
    }

    private fun handleNewLocation(location: Location) {
        previousLocation?.let { prev ->
            val distance = prev.distanceTo(location)
            if (distance < MIN_DISTANCE_THRESHOLD) return@let

            val speed = if (location.hasSpeed()) location.speed else (distance / ((location.time - prev.time)/1000.0)).toFloat()
            if (speed > MAX_SPEED_MPS) {
                Log.d(TAG, "최대 속도($MAX_SPEED_MPS)를 초과하여 무시합니다: $speed m/s")
                return@let
            }

            if (stepSensor == null) {
                totalDistance += distance / 1000.0
                currentSteps = (totalDistance * 1000 / userStride).toLong()
                totalCalories = CalorieCalculator.calculate(userWeight, totalDistance, currentActivityType)
                updateAndBroadcast()
            }

            routePoints.add(RoutePoint(location.latitude, location.longitude, location.time, currentActivityType.name))
            updateNotification()
        }
        previousLocation = location
    }
    
    private fun updateAndBroadcast() {
        updateNotification()
        broadcastActivityUpdate()
    }

    private fun broadcastActivityUpdate() {
        val intent = Intent(ACTION_ACTIVITY_UPDATE).apply {
            putExtra(EXTRA_STEPS, currentSteps)
            putExtra(EXTRA_DISTANCE, totalDistance)
            putExtra(EXTRA_CALORIES, totalCalories)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun loadUserData() {
        serviceScope.launch {
            val user = repository.getCurrentUser()
            user?.let { 
                userWeight = it.weight
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("걷기 추적 중")
            .setContentText(String.format(Locale.US, "오늘: %d걸음 • %.2fkm", currentSteps, totalDistance))
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
            //showErrorNotification(getString(R.string.location_permission_denied_error))
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
