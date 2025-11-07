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
 * 걸음 수, 이동 거리, 칼로리, 고도, 이동 경로 등을 수집하고 주기적으로 Firebase에 동기화합니다.
 */
class LocationTrackingService : Service(), SensorEventListener {

    // 서비스의 생명주기와 연결된 코루틴 스코프
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Firebase와의 통신을 담당하는 저장소
    private val repository = FirebaseRepository()
    // 날짜 포맷을 관리
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    // 디바이스 센서 접근을 위한 센서 매니저
    private lateinit var sensorManager: SensorManager
    // 걸음 수 감지 센서
    private var stepSensor: Sensor? = null
    // 기압 감지 센서 (고도 계산용)
    private var pressureSensor: Sensor? = null

    // 위치 정보 제공 클라이언트
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    // 사용자 활동(걷기, 뛰기 등) 인식 클라이언트
    private lateinit var activityRecognitionClient: ActivityRecognitionClient

    // 동기화되지 않은 데이터를 임시 저장하기 위한 SharedPreferences
    private lateinit var syncPrefs: SharedPreferencesManager

    // 고도 변화를 계산하는 유틸리티
    private val altitudeCalculator = AltitudeCalculator()

    // --- 일일 누적 데이터 ---
    // 오늘 시작 시점의 걸음 수
    private var stepsAtStartOfDay = 0L
    // 오늘 시작 시점의 이동 거리 (km)
    private var distanceAtStartOfDay = 0.0
    // 오늘 시작 시점의 소모 칼로리
    private var caloriesAtStartOfDay = 0.0
    // 오늘 시작 시점의 상승 고도 (m)
    private var altitudeAtStartOfDay = 0.0

    // --- 현재 세션 데이터 ---
    // 센서에서 처음 감지된 걸음 수 (부팅 이후 누적값)
    private var initialStepCount = 0L
    // 현재 서비스 세션에서 기록된 걸음 수
    private var currentSteps = 0L
    // 현재 서비스 세션에서 기록된 총 이동 거리 (km)
    private var totalDistance = 0.0
    // 현재 서비스 세션에서 기록된 총 소모 칼로리
    private var totalCalories = 0.0
    // 현재 서비스 세션에서 기록된 총 상승 고도 (m)
    private var totalAltitudeGain = 0.0
    // 현재 속도 (m/s)
    private var currentSpeed = 0.0f
    // 초기 데이터 로드 완료 여부 플래그
    private var isInitialDataLoaded = false
    // 마지막으로 데이터가 처리된 날짜
    private var lastProcessedDate: String = ""

    // --- 위치 및 활동 관련 데이터 ---
    // 마지막으로 수신된 위치 정보
    private var previousLocation: Location? = null
    // 현재 감지된 활동 유형
    private var currentActivityType = ActivityType.WALKING
    // 이전에 감지된 활동 유형
    private var previousActivityType = ActivityType.UNKNOWN
    // 활동 유형이 변경된 시간
    private var activityTransitionTime = 0L
    // 사용자 체중 (kg) - 칼로리 계산용
    private var userWeight = 70.0
    // 사용자 보폭 (m) - 걸음 수 미지원 시 거리 기반 걸음 수 계산용
    private var userStride = 0.7

    // 이동 경로를 저장하는 리스트
    private val routePoints = mutableListOf<RoutePoint>()

    // 마지막으로 측정된 기압 값
    private var lastPressureValue = 0f
    // 이전 위치에서의 기압 값
    private var pressureAtPreviousLocation: Float? = null

    // --- 동기화 관련 데이터 ---
    // 마지막으로 Firebase에 동기화된 걸음 수
    private var stepsSynced: Long = 0
    // 마지막으로 Firebase에 동기화된 이동 거리
    private var distanceSynced: Double = 0.0
    // 마지막으로 Firebase에 동기화된 소모 칼로리
    private var caloriesSynced: Double = 0.0
    // 마지막으로 Firebase에 동기화된 상승 고도
    private var altitudeSynced: Double = 0.0

    // --- 배터리 최적화 관련 ---
    // 마지막 활동이 감지된 시간
    private var lastActivityTime = System.currentTimeMillis()
    // 절전 모드(정지 상태) 활성화 여부
    private var isStillMode = false
    // 활동 전환 시 위치 정보를 임시 저장하는 버퍼
    private var transitionLocationBuffer = mutableListOf<Location>()

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "WalkTrackerChannel"

        // 주기적 동기화 간격 (5분)
        private const val SYNC_INTERVAL = 300000L
        // 걷기 시 위치 업데이트 간격 (15초)
        private const val LOCATION_INTERVAL_WALKING = 15000L
        // 뛰기 시 위치 업데이트 간격 (10초)
        private const val LOCATION_INTERVAL_RUNNING = 10000L
        // 정지 시 위치 업데이트 간격 (30초)
        private const val LOCATION_INTERVAL_STILL = 30000L

        // 최소 이동 거리 임계값 (5m)
        private const val MIN_DISTANCE_THRESHOLD = 5.0
        // 최대 속도 임계값 (m/s)
        private const val MAX_SPEED_MPS = 6.5
        // 최소 걷기 속도 임계값 (m/s)
        private const val MIN_WALKING_SPEED_MPS = 0.5f
        // 위치 업데이트 간 최대 시간 차이 (60초)
        private const val MAX_TIME_DIFFERENCE_SECONDS = 60L
        // 무시할 위치 정확도 임계값 (50m)
        private const val MIN_ACCURACY_METERS = 50f

        // 센서 샘플링 간격 (3초)
        private const val SENSOR_SAMPLING_INTERVAL = 3000000

        // 정지 상태 감지 시간 (5분)
        private const val STILL_DETECTION_TIME = 300000L

        // 활동 전환 안정화 시간 (20초)
        private const val TRANSITION_STABILIZATION_TIME = 20000L
        // 활동 전환 시 위치 버퍼 크기
        private const val TRANSITION_BUFFER_SIZE = 3

        private const val TAG = "LocationTrackingService"

        // Broadcast Action: 활동 데이터 업데이트
        const val ACTION_ACTIVITY_UPDATE = "com.walktracker.app.ACTIVITY_UPDATE"
        // Broadcast Action: 오늘 데이터 리셋
        const val ACTION_RESET_TODAY_DATA = "com.walktracker.app.RESET_TODAY_DATA"
        const val EXTRA_STEPS = "extra_steps"
        const val EXTRA_DISTANCE = "extra_distance"
        const val EXTRA_CALORIES = "extra_calories"
        const val EXTRA_ALTITUDE = "extra_altitude"
        const val EXTRA_SPEED = "extra_speed"

        // Broadcast Action: 위치 정보 업데이트
        const val ACTION_LOCATION_UPDATE = "com.walktracker.app.LOCATION_UPDATE"
        // Broadcast Action: UI에서 위치 정보 요청
        const val ACTION_REQUEST_LOCATION_UPDATE = "com.walktracker.app.REQUEST_LOCATION_UPDATE"
        const val EXTRA_LOCATION = "extra_location"
    }

    /**
     * 활동 인식 리시버로부터 활동 유형 변경 이벤트를 수신합니다.
     */
    private val activityTypeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val typeString = intent?.getStringExtra(ActivityRecognitionReceiver.EXTRA_ACTIVITY_TYPE)
            val newActivityType = when (typeString) {
                "WALKING" -> ActivityType.WALKING
                "RUNNING" -> ActivityType.RUNNING
                "VEHICLE" -> ActivityType.VEHICLE
                "STILL" -> ActivityType.STILL
                else -> ActivityType.WALKING // 기본값
            }

            Log.d(TAG, "활동 유형 수신: $typeString -> $newActivityType")

            if (newActivityType != currentActivityType) {
                handleActivityTransition(currentActivityType, newActivityType)
                previousActivityType = currentActivityType
                currentActivityType = newActivityType
                activityTransitionTime = System.currentTimeMillis()
                updateLocationRequest() // 활동 유형에 맞춰 위치 요청 업데이트
            }
        }
    }

    /**
     * UI(Activity/Fragment)로부터 현재 데이터 요청을 수신합니다.
     */
    private val locationRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_REQUEST_LOCATION_UPDATE) {
                Log.d(TAG, "UI로부터 위치 업데이트 요청 수신")
                broadcastLocationUpdate()
                broadcastActivityUpdate()
            }
        }
    }

    /**
     * 데이터 리셋 요청을 수신합니다. (예: 날짜 변경 시)
     */
    private val resetDataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RESET_TODAY_DATA) {
                Log.d(TAG, "오늘 데이터 리셋 요청 수신")
                resetTodayData()
            }
        }
    }

    /**
     * 서비스가 바인드될 때 호출됩니다. 이 서비스는 바인딩을 지원하지 않으므로 null을 반환합니다.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 서비스가 생성될 때 호출됩니다. 각종 초기화 작업을 수행합니다.
     */
    override fun onCreate() {
        super.onCreate()
        lastProcessedDate = dateFormat.format(Date())
        Log.d(TAG, "========== 서비스 생성 (onCreate) ==========")

        syncPrefs = SharedPreferencesManager(this)

        // 센서 매니저 및 센서 초기화
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

        Log.d(TAG, "걸음수 센서: ${if (stepSensor != null) "사용 가능" else "없음"}")
        Log.d(TAG, "기압 센서: ${if (pressureSensor != null) "사용 가능" else "없음"}")

        // 위치 및 활동 인식 클라이언트 초기화
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        activityRecognitionClient = ActivityRecognition.getClient(this)

        // 초기 데이터 로드
        loadInitialDailyData()
        loadUserData()

        // Foreground 서비스 설정
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // 센서 리스너 등록
        stepSensor?.let {
            sensorManager.registerListener(this, it, SENSOR_SAMPLING_INTERVAL)
            Log.d(TAG, "걸음수 센서 리스너 등록 완료")
        }
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SENSOR_SAMPLING_INTERVAL)
            Log.d(TAG, "기압 센서 리스너 등록 완료")
        }

        // 브로드캐스트 리시버 등록
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

        Log.d(TAG, "브로드캐스트 리시버 등록 완료")

        // 추적 시작
        startLocationTracking()
        startActivityRecognition()
        startPeriodicSync()
        startStillDetection()

        Log.d(TAG, "========== 서비스 초기화 완료 ==========")
    }

    /**
     * 서비스가 소멸될 때 호출됩니다. 리소스를 정리하고 마지막 데이터를 동기화합니다.
     */
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "서비스 종료 (onDestroy)")

        // 종료 전 마지막 데이터 동기화
        serviceScope.launch { syncToFirebase() }

        // 리스너 및 업데이트 해제
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)

        // 브로드캐스트 리시버 해제
        val localBroadcastManager = LocalBroadcastManager.getInstance(this)
        localBroadcastManager.unregisterReceiver(activityTypeReceiver)
        localBroadcastManager.unregisterReceiver(locationRequestReceiver)
        localBroadcastManager.unregisterReceiver(resetDataReceiver)

        // 코루틴 스코프 취소
        serviceScope.cancel()
    }

    /**
     * 주기적으로 Firebase에 데이터를 동기화하는 코루틴을 시작합니다.
     */
    private fun startPeriodicSync() {
        serviceScope.launch {
            Log.d(TAG, "주기적 동기화 시작 (${SYNC_INTERVAL / 1000}초마다)")
            while (isActive) {
                delay(SYNC_INTERVAL)
                Log.d(TAG, "주기적 동기화 실행")
                syncToFirebase()
                repository.flushRankingUpdates() // 랭킹 업데이트도 주기적으로 처리
            }
        }
    }

    /**
     * 수집된 활동 데이터를 Firebase Firestore에 동기화합니다.
     * @param dateToSync 동기화할 날짜 (기본값: 오늘)
     */
    private suspend fun syncToFirebase(dateToSync: String? = null) {
        val userId = repository.getCurrentUserId()
        if (userId == null) {
            Log.e(TAG, "사용자 ID 없음 - 동기화 불가")
            return
        }
        val date = dateToSync ?: dateFormat.format(Date())

        // 마지막 동기화 이후의 증분 계산
        val stepsIncrement = currentSteps - stepsSynced
        val distanceIncrement = totalDistance - distanceSynced
        val caloriesIncrement = totalCalories - caloriesSynced
        val altitudeIncrement = totalAltitudeGain - altitudeSynced

        Log.d(TAG, "동기화 증분 - 걸음:$stepsIncrement, 거리:${"%.3f".format(distanceIncrement)}km")

        // 이전에 동기화 실패했던 데이터 가져오기
        val unsyncedData = syncPrefs.getUnsyncedData()
        val unsyncedSteps = unsyncedData["steps"] as? Long ?: 0L
        val unsyncedDistance = unsyncedData["distance"] as? Double ?: 0.0
        val unsyncedCalories = unsyncedData["calories"] as? Double ?: 0.0
        val unsyncedAltitude = unsyncedData["altitude"] as? Double ?: 0.0

        // 최종 동기화할 데이터 계산
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
                // 동기화 성공 시 임시 데이터 초기화
                syncPrefs.clearUnsyncedData()
                routePoints.clear()
                stepsSynced = currentSteps
                distanceSynced = totalDistance
                caloriesSynced = totalCalories
                altitudeSynced = totalAltitudeGain
            } else {
                Log.e(TAG, "✗ Firebase 동기화 실패", result.exceptionOrNull())
                // 동기화 실패 시 증분 데이터를 SharedPreferences에 저장
                syncPrefs.addUnsyncedData(stepsIncrement, distanceIncrement, caloriesIncrement, altitudeIncrement)
            }
        } else {
            Log.d(TAG, "동기화할 데이터 없음")
        }
    }

    /**
     * 센서 값이 변경될 때 호출됩니다. (걸음 수, 기압)
     */
    override fun onSensorChanged(event: SensorEvent) {
        lastActivityTime = System.currentTimeMillis() // 센서 이벤트 발생 시 마지막 활동 시간 갱신

        if (currentActivityType == ActivityType.VEHICLE) {
            return // 차량 탑승 중에는 센서 데이터 무시
        }

        when (event.sensor.type) {
            // 걸음 수 센서 이벤트 처리
            Sensor.TYPE_STEP_COUNTER -> {
                val totalStepsFromBoot = event.values[0].toLong()
                if (initialStepCount == 0L) {
                    initialStepCount = totalStepsFromBoot // 최초 걸음 수 기록
                    Log.d(TAG, "초기 걸음수 설정: $initialStepCount")
                }
                // 현재 세션의 걸음 수 = 부팅 이후 총 걸음 수 - 최초 걸음 수
                currentSteps = totalStepsFromBoot - initialStepCount
                Log.d(TAG, "걸음 센서 - 현재: $currentSteps")
                updateAndBroadcast()
            }
            // 기압 센서 이벤트 처리
            Sensor.TYPE_PRESSURE -> {
                val pressure = event.values[0]
                // 유효한 기압 값 범위 내에서만 갱신 (이상치 제외)
                if (pressure > 800f && pressure < 1100f) {
                    lastPressureValue = pressure
                }
            }
        }
    }

    /**
     * 센서 정확도가 변경될 때 호출됩니다.
     */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "센서 정확도 변경: ${sensor?.name}, accuracy=$accuracy")
    }

    /**
     * 사용자 활동 유형이 변경될 때 호출되어 관련 상태를 조정합니다.
     * @param from 이전 활동 유형
     * @param to 새로운 활동 유형
     */
    private fun handleActivityTransition(from: ActivityType, to: ActivityType) {
        Log.d(TAG, "========== 활동 전환: $from -> $to ==========")

        when {
            // 정지 상태로 전환 시 속도를 0으로 설정하고 즉시 UI에 반영
            to == ActivityType.STILL -> {
                currentSpeed = 0.0f
                broadcastActivityUpdate()
            }

            // 차량 -> 걷기/뛰기: 위치 관련 데이터 리셋
            from == ActivityType.VEHICLE &&
                    (to == ActivityType.WALKING || to == ActivityType.RUNNING) -> {
                Log.d(TAG, "차량→도보: 데이터 리셋")
                previousLocation = null
                transitionLocationBuffer.clear()
                currentSpeed = 0.0f
                altitudeCalculator.reset()
                pressureAtPreviousLocation = null
            }
            // 정지 -> 걷기/뛰기: 전환 버퍼 리셋
            from == ActivityType.STILL &&
                    (to == ActivityType.WALKING || to == ActivityType.RUNNING) -> {
                Log.d(TAG, "정지→활동: 전환 버퍼 리셋")
                transitionLocationBuffer.clear()
            }
        }
    }

    /**
     * 활동 전환 후 안정화 기간인지 확인합니다.
     */
    private fun isInTransitionPeriod(): Boolean {
        return System.currentTimeMillis() - activityTransitionTime < TRANSITION_STABILIZATION_TIME
    }

    /**
     * 새로운 위치 정보를 처리하고 활동 데이터를 계산합니다.
     * @param location 새로 수신된 위치 정보
     */
    private fun handleNewLocation(location: Location) {
        Log.d(TAG, "---------- 새 위치 수신 ----------")
        Log.d(TAG, "위도:${location.latitude}, 경도:${location.longitude}, 정확도:${location.accuracy}m")

        // 정확도가 너무 낮고 속도가 거의 없는 경우 무시 (GPS 노이즈)
        if (location.hasAccuracy() && location.accuracy > MIN_ACCURACY_METERS) {
            if (location.speed < 0.2f) {
                Log.d(TAG, "정확도 낮고 속도 느림 - 무시")
                previousLocation = location
                return
            }
        }

        // 차량 탑승 중에는 위치만 업데이트
        if (currentActivityType == ActivityType.VEHICLE) {
            Log.d(TAG, "차량 모드 - 위치만 업데이트")
            previousLocation = location
            broadcastLocationUpdate()
            return
        }

        // 날짜가 변경되었는지 확인하고, 변경되었다면 이전 날짜 데이터 동기화 및 리셋
        val currentDate = dateFormat.format(Date())
        if (lastProcessedDate.isNotEmpty() && lastProcessedDate != currentDate) {
            Log.d(TAG, "날짜 변경: $lastProcessedDate -> $currentDate")
            serviceScope.launch {
                syncToFirebase(lastProcessedDate) // 어제 날짜로 동기화
                resetTodayData() // 오늘 데이터 리셋
                loadInitialDailyData() // 오늘 데이터 새로 로드
            }
        }
        lastProcessedDate = currentDate

        // 활동 전환 안정화 기간 중에는 위치를 버퍼에만 저장
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

        // 이전 위치가 없으면 현재 위치를 시작점으로 설정
        if (previousLocation == null) {
            previousLocation = location
            Log.d(TAG, "시작 위치 설정")
            broadcastLocationUpdate()
            return
        }

        val prev = previousLocation!!
        val timeDifferenceSeconds = (location.time - prev.time) / 1000

        Log.d(TAG, "시간차: ${timeDifferenceSeconds}초")

        if (timeDifferenceSeconds <= 0) return // 시간차가 없거나 역행하면 무시

        // 시간차가 너무 크면 위치가 끊긴 것으로 간주하고 리셋
        if (timeDifferenceSeconds > MAX_TIME_DIFFERENCE_SECONDS) {
            Log.d(TAG, "시간차 초과 - 리셋")
            previousLocation = null
            broadcastLocationUpdate()
            previousLocation = location
            return
        }

        val distance = prev.distanceTo(location)
        Log.d(TAG, "이동 거리: ${"%.2f".format(distance)}m")

        // 이동 거리가 너무 짧으면 무시 (GPS 노이즈)
        if (distance < MIN_DISTANCE_THRESHOLD) {
            Log.d(TAG, "거리 부족 - 무시")
            return
        }

        val speed = calculateSpeed(location, prev, distance, timeDifferenceSeconds)
        Log.d(TAG, "속도: ${"%.2f".format(speed)}m/s")

        // 최대 속도를 초과하면 무시 (비정상적인 이동)
        if (speed > MAX_SPEED_MPS) {
            Log.d(TAG, "최대 속도 초과")
            previousLocation = null
            broadcastLocationUpdate()
            previousLocation = location
            return
        }

        // 걷기 상태인데 최소 걷기 속도에 미달하면 무시
        if (currentActivityType == ActivityType.WALKING && speed < MIN_WALKING_SPEED_MPS) {
            Log.d(TAG, "걷기 속도 미달")
            previousLocation = null
            broadcastLocationUpdate()
            previousLocation = location
            return
        }

        Log.d(TAG, "========== 유효한 이동 - 데이터 업데이트 ==========")

        // 데이터 업데이트
        lastActivityTime = System.currentTimeMillis()
        currentSpeed = speed
        totalDistance += distance / 1000.0 // km 단위로 누적

        Log.d(TAG, "누적 거리: ${"%.3f".format(totalDistance)}km")

        // 고도 상승 계산
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

        // 걸음 수 센서가 없을 경우, 이동 거리를 기반으로 걸음 수 추정
        if (stepSensor == null && userStride > 0) {
            currentSteps = (totalDistance * 1000 / userStride).toLong()
        }

        // 칼로리 소모량 계산
        val caloriesBurned = CalorieCalculator.calculate(
            weightKg = userWeight,
            speedMps = speed,
            durationSeconds = timeDifferenceSeconds,
            activityType = currentActivityType,
            elevationGainMeters = 0.0 // 고도 기반 칼로리 계산은 추가 구현 필요
        )
        totalCalories += caloriesBurned

        // 이동 경로 포인트 추가
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
        broadcastLocationUpdate()
    }

    /**
     * 두 위치 정보 간의 속도를 계산합니다.
     * GPS 제공 속도를 우선 사용하되, 급격한 속도 변화를 완화하는 로직을 포함합니다.
     */
    private fun calculateSpeed(
        location: Location,
        previousLocation: Location,
        distance: Float,
        timeDiffSeconds: Long
    ): Float {
        // GPS에서 제공하는 속도 값이 있으면 우선적으로 사용
        if (location.hasSpeed() && location.speed > 0) {
            return location.speed
        }

        // 직접 계산한 속도
        val calculatedSpeed = distance / timeDiffSeconds

        // 이전 속도와 비교하여 급격한 변화를 보정 (Smoothing)
        if (currentSpeed > 0) {
            val speedDiff = abs(calculatedSpeed - currentSpeed)
            val maxSpeedChange = 2.0f // 최대 속도 변화량 (m/s)

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

    /**
     * 오늘의 활동 데이터를 모두 초기화합니다.
     */
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
        updateAndBroadcast()
    }

    /**
     * 알림(Notification)을 업데이트하고 활동 데이터를 UI에 브로드캐스트합니다.
     */
    private fun updateAndBroadcast() {
        if (!isInitialDataLoaded) return // 초기 데이터 로드 전에는 업데이트하지 않음
        updateNotification()
        broadcastActivityUpdate()
    }

    /**
     * 현재 활동 데이터를 LocalBroadcast를 통해 UI(Activity/Fragment)에 전달합니다.
     */
    private fun broadcastActivityUpdate() {
        // 오늘 총 데이터 = 시작 시점 데이터 + 현재 세션 데이터
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

    /**
     * 현재 위치 정보를 LocalBroadcast를 통해 UI에 전달합니다.
     */
    private fun broadcastLocationUpdate() {
        previousLocation?.let {
            val intent = Intent(ACTION_LOCATION_UPDATE).apply {
                putExtra(EXTRA_LOCATION, it)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }

    /**
     * Firebase에서 현재 사용자 정보를 로드합니다. (예: 체중)
     */
    private fun loadUserData() {
        serviceScope.launch {
            val user = repository.getCurrentUser()
            user?.let {
                userWeight = it.weight
                Log.d(TAG, "사용자 데이터 - 체중:${userWeight}kg")
            }
        }
    }

    /**
     * Firebase에서 오늘 날짜의 초기 활동 데이터를 로드합니다.
     * 앱이 재시작되거나 서비스가 다시 생성될 때 오늘 기록을 이어가기 위함입니다.
     */
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
                    // 오늘 시작 시점의 데이터를 설정
                    stepsAtStartOfDay = it.steps
                    distanceAtStartOfDay = it.distance
                    caloriesAtStartOfDay = it.calories
                    altitudeAtStartOfDay = it.altitude
                }
                isInitialDataLoaded = true // 초기 데이터 로드 완료
                updateAndBroadcast()
            }
        }
    }

    /**
     * Foreground 서비스를 위한 알림 채널을 생성합니다. (Android O 이상 필수)
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "걷기 추적", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Foreground 서비스의 알림(Notification)을 생성합니다.
     */
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
            String.format(Locale.US, "오늘: %d걸음 • %.2fkm", totalStepsToday, totalDistanceToday)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (isStillMode) "걷기 추적 (절전)" else "걷기 추적 중")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_splash_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // 사용자가 쉽게 지울 수 없도록 설정
            .setPriority(NotificationCompat.PRIORITY_LOW) // 중요도를 낮춰 사용자 방해 최소화
            .build()
    }

    /**
     * 알림 내용을 최신 정보로 업데이트합니다.
     */
    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification())
    }

    /**
     * 위치 추적을 시작합니다.
     */
    private fun startLocationTracking() {
        Log.d(TAG, "위치 추적 시작")
        updateLocationRequest()
    }

    /**
     * 현재 활동 유형에 따라 위치 요청 설정을 업데이트합니다.
     * (예: 뛰기 > 걷기 > 정지 순으로 업데이트 간격을 길게 하여 배터리 소모를 최적화)
     */
    private fun updateLocationRequest() {
        // 차량 탑승 시에는 위치 업데이트를 중지하고 절전 모드로 진입
        if (currentActivityType == ActivityType.VEHICLE) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            enterStillMode()
            return
        }

        // 활동 유형에 따라 위치 요청 우선순위와 간격 설정
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
            // 위치 업데이트 요청
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "✓ 위치 업데이트 요청 성공")
        } catch (e: SecurityException) {
            Log.e(TAG, "✗ 위치 권한 없음", e)
            stopSelf() // 권한 없으면 서비스 중지
        }

        exitStillMode() // 위치 업데이트 시작 시 절전 모드 해제
    }

    /**
     * FusedLocationProviderClient로부터 위치 결과를 수신하는 콜백입니다.
     */
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                Log.d(TAG, "LocationCallback - 위치 수신")
                handleNewLocation(it)
            }
        }
    }

    /**
     * 활동 인식(Activity Recognition)을 시작하여 사용자 활동 유형을 감지합니다.
     */
    private fun startActivityRecognition() {
        val transitions = listOf(
            // 걷기 시작 감지
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.WALKING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            // 뛰기 시작 감지
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.RUNNING)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            // 차량 탑승 시작 감지
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            // 정지 상태 시작 감지
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
            // 활동 전환 업데이트 요청
            activityRecognitionClient.requestActivityTransitionUpdates(request, pendingIntent)
            Log.d(TAG, "✓ 활동 인식 시작")
        } catch (e: SecurityException) {
            Log.e(TAG, "✗ 활동 인식 권한 없음", e)
        }
    }

    /**
     * 사용자가 장시간 움직이지 않을 경우를 감지하여 절전 모드로 전환하기 위한 루프를 시작합니다.
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
     * 배터리 소모를 줄이기 위한 절전 모드로 진입합니다.
     * 센서 리스너를 해제하고 위치 요청 빈도를 크게 낮춥니다.
     */
    private fun enterStillMode() {
        if (isStillMode) return
        isStillMode = true
        Log.d(TAG, "========== 절전 모드 진입 ==========")

        sensorManager.unregisterListener(this) // 센서 해제

        // 저전력 위치 요청 설정
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_LOW_POWER,
            600000L // 10분 간격
        ).build()

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "절전 모드 위치 권한 오류", e)
        }

        updateNotification() // 알림 내용 변경
    }

    /**
     * 절전 모드를 해제하고 다시 정상적인 추적 상태로 복귀합니다.
     */
    private fun exitStillMode() {
        if (!isStillMode) return
        isStillMode = false
        Log.d(TAG, "========== 절전 모드 해제 ==========")

        // 센서 리스너 재등록
        stepSensor?.let {
            sensorManager.registerListener(this, it, SENSOR_SAMPLING_INTERVAL)
        }
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SENSOR_SAMPLING_INTERVAL)
        }

        updateNotification() // 알림 내용 복원
    }

    /**
     * 서비스가 시작될 때 호출됩니다. START_STICKY를 반환하여 시스템에 의해 종료되어도 다시 시작되도록 합니다.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand 호출")
        return START_STICKY
    }
}