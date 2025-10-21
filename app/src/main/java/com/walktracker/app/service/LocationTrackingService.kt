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

    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private var pressureSensor: Sensor? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var activityRecognitionClient: ActivityRecognitionClient

    private lateinit var syncPrefs: SharedPreferencesManager

    private var stepsAtStartOfDay = 0L
    private var distanceAtStartOfDay = 0.0
    private var caloriesAtStartOfDay = 0.0
    private var altitudeAtStartOfDay = 0.0

    private var initialStepCount = 0L
    private var currentSteps = 0L
    private var lastStepsProcessed = 0L
    private var totalDistance = 0.0
    private var totalCalories = 0.0
    private var totalAltitudeGain = 0.0
    private var currentSpeed = 0.0f
    private var isInitialDataLoaded = false
    private var lastProcessedDate: String = ""

    private var previousLocation: Location? = null
    private var currentActivityType = ActivityType.STILL
    private var userWeight = 70.0
    private var userStride = 0.7

    private val routePoints = mutableListOf<RoutePoint>()
    private var lastPressureValue = 0f

    private var stepsSynced: Long = 0
    private var distanceSynced: Double = 0.0
    private var caloriesSynced: Double = 0.0
    private var altitudeSynced: Double = 0.0

    private val activityTypeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val typeString = intent?.getStringExtra(ActivityRecognitionReceiver.EXTRA_ACTIVITY_TYPE)
            val newActivity = when (typeString) {
                "WALKING" -> ActivityType.WALKING
                "RUNNING" -> ActivityType.RUNNING
                else -> ActivityType.STILL
            }
            if (newActivity != currentActivityType) {
                currentActivityType = newActivity
                updateLocationRequest()
            }
        }
    }

    private val locationRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REQUEST_LOCATION_UPDATE) {
                broadcastLocationUpdate()
            }
        }
    }
    
    private val resetDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RESET_TODAY_DATA) {
                serviceScope.launch {
                    resetAllData()
                    updateAndBroadcast()
                }
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "WalkTrackerChannel"
        private const val SYNC_INTERVAL = 60000L
        private const val MAX_SPEED_MPS = 20.0
        private const val TAG = "LocationTrackingService"

        const val ACTION_ACTIVITY_UPDATE = "com.walktracker.app.ACTIVITY_UPDATE"
        const val EXTRA_STEPS = "extra_steps"
        const val EXTRA_DISTANCE = "extra_distance"
        const val EXTRA_CALORIES = "extra_calories"
        const val EXTRA_ALTITUDE = "extra_altitude"
        const val EXTRA_SPEED = "extra_speed"

        const val ACTION_LOCATION_UPDATE = "com.walktracker.app.LOCATION_UPDATE"
        const val ACTION_REQUEST_LOCATION_UPDATE = "com.walktracker.app.REQUEST_LOCATION_UPDATE"
        const val ACTION_RESET_TODAY_DATA = "com.walktracker.app.RESET_TODAY_DATA"
        const val ACTION_ROUTE_UPDATE = "com.walktracker.app.ROUTE_UPDATE"
        const val EXTRA_LOCATION = "extra_location"
        const val EXTRA_ROUTE = "extra_route"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        lastProcessedDate = dateFormat.format(Date())
        syncPrefs = SharedPreferencesManager(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        activityRecognitionClient = ActivityRecognition.getClient(this)

        loadInitialDailyData()
        loadUserData()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        val localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.registerReceiver(activityTypeReceiver, IntentFilter(ActivityRecognitionReceiver.ACTION_ACTIVITY_TYPE_UPDATE))
        localBroadcastManager.registerReceiver(locationRequestReceiver, IntentFilter(ACTION_REQUEST_LOCATION_UPDATE))
        localBroadcastManager.registerReceiver(resetDataReceiver, IntentFilter(ACTION_RESET_TODAY_DATA))

        startLocationTracking()
        startActivityRecognition()
        startPeriodicSync()
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
        }
        serviceScope.cancel()
    }

    private fun startPeriodicSync() {
        serviceScope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL)
                syncToFirebase()
            }
        }
    }

    private suspend fun syncToFirebase() {
        val userId = repository.getCurrentUserId() ?: return

        val steps = currentSteps - stepsSynced
        val dist = totalDistance - distanceSynced
        val cals = totalCalories - caloriesSynced
        val alt = totalAltitudeGain - altitudeSynced

        val unsynced = syncPrefs.getUnsyncedData()
        val syncSteps = steps + (unsynced["steps"] as? Long ?: 0L)
        val syncDist = dist + (unsynced["distance"] as? Double ?: 0.0)
        val syncCals = cals + (unsynced["calories"] as? Double ?: 0.0)
        val syncAlt = alt + (unsynced["altitude"] as? Double ?: 0.0)
        
        val routesToSync = routePoints.toList()

        if (syncSteps > 0 || syncDist > 0 || syncAlt > 0) {
            if (repository.incrementDailyActivity(userId, dateFormat.format(Date()), syncSteps, syncDist, syncCals, syncAlt, routesToSync).isSuccess) {
                syncPrefs.clearUnsyncedData()
                routePoints.clear()
            } else {
                syncPrefs.addUnsyncedData(steps, dist, cals, alt)
            }
        }
        
        stepsSynced = currentSteps
        distanceSynced = totalDistance
        caloriesSynced = totalCalories
        altitudeSynced = totalAltitudeGain
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val totalStepsFromBoot = event.values[0].toLong()
                if (initialStepCount == 0L) initialStepCount = totalStepsFromBoot
                currentSteps = totalStepsFromBoot - initialStepCount
            }
            Sensor.TYPE_PRESSURE -> {
                val pressure = event.values[0]
                if (lastPressureValue > 0 && (currentActivityType == ActivityType.WALKING || currentActivityType == ActivityType.RUNNING)) {
                    val altitudeChange = SensorManager.getAltitude(lastPressureValue, pressure)
                    if (altitudeChange > 0.3f) {
                        totalAltitudeGain += altitudeChange
                    }
                }
                lastPressureValue = pressure
            }
        }
    }

    private fun handleNewLocation(location: Location) {
        val currentDate = dateFormat.format(Date())
        if (lastProcessedDate.isNotEmpty() && lastProcessedDate != currentDate) {
            serviceScope.launch {
                syncToFirebase()
                resetAllData()
                loadInitialDailyData()
            }
        }
        lastProcessedDate = currentDate

        previousLocation?.let { prev ->
            val timeDiff = (location.time - prev.time) / 1000
            if (timeDiff <= 0) return@let
            currentSpeed = if (location.hasSpeed()) location.speed else prev.distanceTo(location) / timeDiff

            if (currentSpeed > MAX_SPEED_MPS || (currentActivityType != ActivityType.WALKING && currentActivityType != ActivityType.RUNNING)) return@let
            val stepsSince = currentSteps - lastStepsProcessed
            if (stepSensor == null || stepsSince <= 0) return@let

            totalDistance += stepsSince * userStride / 1000.0
            lastStepsProcessed = currentSteps
            
            if (location.hasAltitude() && prev.hasAltitude()) {
                val altChange = location.altitude - prev.altitude
                if (altChange > 0.5 && pressureSensor == null) totalAltitudeGain += altChange
            }
        
            val caloriesBurned = CalorieCalculator.calculate(userWeight, currentSpeed, timeDiff, currentActivityType, if (location.hasAltitude() && prev.hasAltitude() && location.altitude - prev.altitude > 0) location.altitude - prev.altitude else 0.0)
            totalCalories += caloriesBurned
            
            val newRoutePoint = RoutePoint(location.latitude, location.longitude, location.time, currentActivityType.name, currentSpeed.toDouble())
            routePoints.add(newRoutePoint)
            broadcastRouteUpdate(newRoutePoint)

            updateAndBroadcast()
        }
        previousLocation = location
        broadcastLocationUpdate()
    }

    private fun resetAllData() {
        stepsAtStartOfDay = 0L
        distanceAtStartOfDay = 0.0
        caloriesAtStartOfDay = 0.0
        altitudeAtStartOfDay = 0.0
        initialStepCount = 0L
        currentSteps = 0L
        lastStepsProcessed = 0L
        totalDistance = 0.0
        totalCalories = 0.0
        totalAltitudeGain = 0.0
        stepsSynced = 0L
        distanceSynced = 0.0
        caloriesSynced = 0.0
        altitudeSynced = 0.0
        routePoints.clear()
    }
    
    private fun updateAndBroadcast() {
        if (!isInitialDataLoaded) return
        updateNotification()
        broadcastActivityUpdate()
    }

    private fun broadcastActivityUpdate() {
        val intent = Intent(ACTION_ACTIVITY_UPDATE).apply {
            putExtra(EXTRA_STEPS, stepsAtStartOfDay + currentSteps)
            putExtra(EXTRA_DISTANCE, distanceAtStartOfDay + totalDistance)
            putExtra(EXTRA_CALORIES, caloriesAtStartOfDay + totalCalories)
            putExtra(EXTRA_ALTITUDE, altitudeAtStartOfDay + totalAltitudeGain)
            putExtra(EXTRA_SPEED, currentSpeed)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastLocationUpdate() {
        previousLocation?.let { LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(ACTION_LOCATION_UPDATE).putExtra(EXTRA_LOCATION, it)) }
    }
    
    private fun broadcastRouteUpdate(routePoint: RoutePoint) {
        val intent = Intent(ACTION_ROUTE_UPDATE).apply {
            putExtra(EXTRA_ROUTE, routePoint)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun loadUserData() {
        serviceScope.launch {
            repository.getCurrentUser()?.let { 
                userWeight = it.weight
                userStride = it.stride
            }
        }
    }

    private fun loadInitialDailyData() {
        serviceScope.launch {
            val userId = repository.getCurrentUserId() ?: run { isInitialDataLoaded = true; return@launch }
            repository.getDailyActivityOnce(userId, dateFormat.format(Date())) { activity ->
                activity?.let {
                    stepsAtStartOfDay = it.steps
                    distanceAtStartOfDay = it.distance
                    caloriesAtStartOfDay = it.calories
                    altitudeAtStartOfDay = it.altitude
                    routePoints.clear() // 데이터 중복 적재 방지를 위해 리스트를 비웁니다.
                    routePoints.addAll(it.routes)
                } ?: resetAllData()
                isInitialDataLoaded = true
                updateAndBroadcast()
            }
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "걷기 추적", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("걷기 추적 중")
            .setContentText(String.format(Locale.US, "오늘: %d걸음 • %.2fkm", stepsAtStartOfDay + currentSteps, distanceAtStartOfDay + totalDistance))
            .setSmallIcon(R.drawable.ic_splash_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification())
    }

    private fun updateLocationRequest() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        startLocationTracking()
        // 센서 샘플링 속도를 SENSOR_DELAY_NORMAL로 통일하여 불필요한 배터리 소모를 줄입니다.
        val delay = SensorManager.SENSOR_DELAY_NORMAL
        stepSensor?.let { sensorManager.registerListener(this, it, delay) }
        pressureSensor?.let { sensorManager.registerListener(this, it, delay) }
    }

    private fun startLocationTracking() {
        // 활동 유형에 따라 위치 요청 우선순위와 간격을 세분화합니다.
        val (priority, interval) = when (currentActivityType) {
            ActivityType.RUNNING -> Priority.PRIORITY_HIGH_ACCURACY to 10000L      // 뛰기: 10초, 고정확도
            ActivityType.WALKING -> Priority.PRIORITY_BALANCED_POWER_ACCURACY to 30000L // 걷기: 30초, 균형
            ActivityType.STILL -> Priority.PRIORITY_LOW_POWER to 120000L          // 정지: 2분, 저전력
            else -> Priority.PRIORITY_LOW_POWER to 120000L
        }
        val request = LocationRequest.Builder(priority, interval).build()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "위치 권한이 없습니다. 서비스를 중지합니다.", e)
            stopSelf()
        }
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
            ActivityTransition.Builder().setActivityType(DetectedActivity.STILL).setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build()
        )
        val request = ActivityTransitionRequest(transitions)
        val pendingIntent = PendingIntent.getBroadcast(this, 0, Intent(this, ActivityRecognitionReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            activityRecognitionClient.requestActivityTransitionUpdates(request, pendingIntent)
        } catch (e: SecurityException) {
            Log.e(TAG, "활동 감지 권한이 없습니다.", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
}
