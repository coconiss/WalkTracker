package com.walktracker.app.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.firestore.FirebaseFirestoreException
import com.walktracker.app.model.DailyActivity
import com.walktracker.app.model.RankingEntry
import com.walktracker.app.model.RankingLeaderboard
import com.walktracker.app.model.User
import com.walktracker.app.repository.FirebaseRepository
import com.walktracker.app.service.LocationTrackingService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class MainUiState(
    val user: User? = null,
    val todayActivity: DailyActivity? = null,
    val weeklyActivity: List<DailyActivity> = emptyList(),
    val lastKnownLocation: Location? = null,
    val currentSpeed: Float = 0.0f,
    val isLoading: Boolean = true,
    val error: String? = null,
    val notificationEnabled: Boolean = true,
)

data class RankingUiState(
    val leaderboard: List<RankingEntry> = emptyList(),
    val myRank: RankingEntry? = null, // 리더보드에 있는 내 순위 정보
    val totalParticipants: Int = 0,
    val isLoading: Boolean = false,
    val selectedPeriod: RankingPeriod = RankingPeriod.DAILY,
    val error: String? = null,
    val lastUpdated: Long = 0L
)

enum class RankingPeriod {
    DAILY, MONTHLY, YEARLY
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FirebaseRepository(application.applicationContext)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val prefs = application.getSharedPreferences("WalkTrackerPrefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _rankingState = MutableStateFlow(RankingUiState())
    val rankingState: StateFlow<RankingUiState> = _rankingState.asStateFlow()

    private var currentUserId: String? = null

    // 랭킹 캐시 유효 시간: 1시간
    private val CACHE_DURATION_MS = 60 * 60 * 1000

    private val activityUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationTrackingService.ACTION_ACTIVITY_UPDATE) {
                val serviceUserId = repository.getCurrentUserId()
                if (serviceUserId != currentUserId) {
                    Log.w("MainViewModel", "User mismatch detected. Ignoring broadcast.")
                    return
                }

                val steps = intent.getLongExtra(LocationTrackingService.EXTRA_STEPS, 0L)
                val distance = intent.getDoubleExtra(LocationTrackingService.EXTRA_DISTANCE, 0.0)
                val calories = intent.getDoubleExtra(LocationTrackingService.EXTRA_CALORIES, 0.0)
                val altitude = intent.getDoubleExtra(LocationTrackingService.EXTRA_ALTITUDE, 0.0)
                val speed = intent.getFloatExtra(LocationTrackingService.EXTRA_SPEED, 0.0f)
                updateActivityData(steps, distance, calories, altitude, speed)
            }
        }
    }

    private val locationUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationTrackingService.ACTION_LOCATION_UPDATE) {
                val location: Location? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(LocationTrackingService.EXTRA_LOCATION, Location::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(LocationTrackingService.EXTRA_LOCATION)
                }
                location?.let { updateLocation(it) }
            }
        }
    }

    init {
        currentUserId = repository.getCurrentUserId()
        loadUserData()
        loadInitialTodayActivity()
        loadWeeklyActivity()
        loadNotificationSetting()
        registerReceivers()
        requestLocationUpdate()
    }

    override fun onCleared() {
        super.onCleared()
        unregisterReceivers()
    }

    fun deleteAccount() {
        viewModelScope.launch {
            val result = repository.deleteAccount()
            if (result.isFailure) {
                _uiState.update { it.copy(error = "계정 삭제에 실패했습니다.") }
            }
        }
    }

    fun updateActivityData(steps: Long, distance: Double, calories: Double, altitude: Double, speed: Float) {
        _uiState.update { currentState ->
            val updatedActivity = (currentState.todayActivity ?: DailyActivity(
                userId = repository.getCurrentUserId() ?: "",
                date = dateFormat.format(Date())
            )).copy(
                steps = steps,
                distance = distance,
                calories = calories,
                altitude = altitude
            )
            currentState.copy(todayActivity = updatedActivity, currentSpeed = speed)
        }
    }

    fun updateLocation(location: Location) {
        _uiState.update { it.copy(lastKnownLocation = location) }
    }

    private fun registerReceivers() {
        val localBroadcastManager = LocalBroadcastManager.getInstance(getApplication())
        val activityFilter = IntentFilter(LocationTrackingService.ACTION_ACTIVITY_UPDATE)
        val locationFilter = IntentFilter(LocationTrackingService.ACTION_LOCATION_UPDATE)
        localBroadcastManager.registerReceiver(activityUpdateReceiver, activityFilter)
        localBroadcastManager.registerReceiver(locationUpdateReceiver, locationFilter)
    }

    private fun unregisterReceivers() {
        val localBroadcastManager = LocalBroadcastManager.getInstance(getApplication())
        localBroadcastManager.unregisterReceiver(activityUpdateReceiver)
        localBroadcastManager.unregisterReceiver(locationUpdateReceiver)
    }

    private fun loadNotificationSetting() {
        val enabled = prefs.getBoolean("notification_enabled", true)
        _uiState.update { it.copy(notificationEnabled = enabled) }
    }

    fun setNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("notification_enabled", enabled).apply()
        _uiState.update { it.copy(notificationEnabled = enabled) }
    }

    private fun loadUserData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val user = repository.getCurrentUser()

            val newUserId = user?.userId
            if (newUserId != currentUserId) {
                Log.d("MainViewModel", "User changed from $currentUserId to $newUserId. Clearing data.")
                currentUserId = newUserId
                clearAllData()
            }

            _uiState.update {
                it.copy(
                    user = user,
                    isLoading = false,
                    error = if (user == null) "사용자 정보를 불러올 수 없습니다" else null
                )
            }
        }
    }

    private fun loadInitialTodayActivity() {
        if (_uiState.value.todayActivity != null) {
            return
        }

        viewModelScope.launch {
            val userId = repository.getCurrentUserId() ?: return@launch
            val today = dateFormat.format(Date())

            // 먼저 로컬 Room에서 조회
            val localActivity = repository.getDailyActivityLocal(userId, today)
            if (localActivity != null) {
                _uiState.update { it.copy(todayActivity = localActivity) }
                Log.d("MainViewModel", "로컬 데이터 로드 성공")
            } else {
                // 로컬에 없으면 Firestore에서 조회
                repository.getDailyActivityOnce(userId, today) { activity ->
                    if (activity != null) {
                        _uiState.update { it.copy(todayActivity = activity) }
                        Log.d("MainViewModel", "Firestore 데이터 로드 성공")
                    }
                }
            }
        }
    }

    private fun loadWeeklyActivity() {
        viewModelScope.launch {
            val userId = repository.getCurrentUserId() ?: return@launch
            val calendar = Calendar.getInstance()
            val dateList = (0..6).map {
                dateFormat.format(calendar.time).also { calendar.add(Calendar.DATE, -1) }
            }.reversed()

            repository.getWeeklyActivity(userId, dateList) { activities ->
                _uiState.update {
                    val mergedActivities = dateList.map { date ->
                        activities.find { it.date == date } ?: DailyActivity(
                            userId = userId,
                            date = date
                        )
                    }
                    it.copy(weeklyActivity = mergedActivities)
                }
            }
        }
    }

    fun resetTodayActivity() {
        viewModelScope.launch {
            val userId = repository.getCurrentUserId() ?: return@launch
            val today = dateFormat.format(Date())

            val resetActivity = DailyActivity(
                userId = userId,
                date = today,
                steps = 0,
                distance = 0.0,
                calories = 0.0,
                altitude = 0.0
            )

            // Firestore가 아닌 Room에 저장 (동기화는 서비스에서 자동 처리)
            repository.incrementDailyActivityLocal(
                userId = userId,
                date = today,
                steps = 0,
                distance = 0.0,
                calories = 0.0,
                altitude = 0.0,
                routes = emptyList()
            )

            _uiState.update { it.copy(todayActivity = resetActivity, currentSpeed = 0f) }

            val intent = Intent(LocationTrackingService.ACTION_RESET_TODAY_DATA)
            LocalBroadcastManager.getInstance(getApplication()).sendBroadcast(intent)
        }
    }

    fun updateUserWeight(weight: Double) {
        viewModelScope.launch {
            val userId = repository.getCurrentUserId() ?: return@launch
            val result = repository.updateUserWeight(userId, weight)
            if (result.isSuccess) {
                loadUserData()
            }
        }
    }

    fun updateUserDisplayName(displayName: String) {
        viewModelScope.launch {
            val userId = repository.getCurrentUserId() ?: return@launch
            if (displayName.isBlank() || displayName == _uiState.value.user?.displayName) {
                return@launch
            }

            _uiState.update { it.copy(isLoading = true, error = null) }

            // [수정] 트랜잭션 전에 닉네임 중복 여부를 먼저 확인 (빠른 UI 피드백용)
            if (!repository.isDisplayNameAvailable(displayName)) {
                _uiState.update { it.copy(isLoading = false, error = "이미 사용 중인 닉네임입니다. 다른 닉네임을 입력해주세요.") }
                return@launch
            }

            // 닉네임 사용 가능 시, 안전한 트랜잭션 실행
            val result = repository.updateUserDisplayName(userId, displayName)

            if (result.isSuccess) {
                loadUserData() // 성공 시 사용자 정보 다시 로드
            } else {
                val errorMessage = when ((result.exceptionOrNull() as? FirebaseFirestoreException)?.code) {
                    // 이 체크는 사전 확인과 트랜잭션 사이의 레이스 컨디션 발생 시 여전히 필요합니다.
                    FirebaseFirestoreException.Code.ALREADY_EXISTS -> "이미 사용 중인 닉네임입니다. 다른 닉네임을 입력해주세요."
                    FirebaseFirestoreException.Code.PERMISSION_DENIED -> "닉네임을 변경할 권한이 없습니다. 다시 로그인 후 시도해주세요."
                    else -> "닉네임 변경 중 오류가 발생했습니다: ${result.exceptionOrNull()?.message}"
                }
                _uiState.update { it.copy(isLoading = false, error = errorMessage) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun loadRankings(period: RankingPeriod) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val lastUpdated = _rankingState.value.lastUpdated
            val isCacheValid = (now - lastUpdated) < CACHE_DURATION_MS

            if (isCacheValid && _rankingState.value.selectedPeriod == period) {
                Log.d("MainViewModel", "Ranking cache is still valid.")
                return@launch
            }

            _rankingState.update { it.copy(isLoading = true, selectedPeriod = period, error = null) }

            try {
                val (periodStr, periodKey) = getPeriodInfo(period)
                val leaderboardDoc = repository.getRankingLeaderboard(periodStr, periodKey)

                if (leaderboardDoc != null) {
                    val myRank = leaderboardDoc.leaderboard.find { it.userId == currentUserId }
                    _rankingState.update {
                        it.copy(
                            leaderboard = leaderboardDoc.leaderboard,
                            myRank = myRank,
                            totalParticipants = leaderboardDoc.totalParticipants,
                            isLoading = false,
                            error = null,
                            lastUpdated = leaderboardDoc.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                        )
                    }
                } else {
                    _rankingState.update {
                        it.copy(
                            leaderboard = emptyList(),
                            myRank = null,
                            totalParticipants = 0,
                            isLoading = false,
                            error = "랭킹 데이터가 아직 집계되지 않았습니다."
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load rankings for $period", e)
                _rankingState.update {
                    it.copy(
                        isLoading = false,
                        error = "랭킹 데이터를 불러오는 데 실패했습니다: ${e.message}"
                    )
                }
            }
        }
    }

    private fun getPeriodInfo(period: RankingPeriod): Pair<String, String> {
        val calendar = Calendar.getInstance()
        return when (period) {
            RankingPeriod.DAILY -> {
                // 어제 날짜의 랭킹을 요청
                calendar.add(Calendar.DATE, -1)
                "daily" to dateFormat.format(calendar.time)
            }
            RankingPeriod.MONTHLY -> {
                val key = String.format("%04d-%02d", calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
                "monthly" to key
            }
            RankingPeriod.YEARLY -> "yearly" to calendar.get(Calendar.YEAR).toString()
        }
    }

    fun refreshData() {
        loadUserData()
        loadInitialTodayActivity()
        loadWeeklyActivity()
        _rankingState.update { it.copy(lastUpdated = 0L) } // 캐시 무효화
        loadRankings(_rankingState.value.selectedPeriod)
    }

    fun requestLocationUpdate() {
        val intent = Intent(LocationTrackingService.ACTION_REQUEST_LOCATION_UPDATE)
        LocalBroadcastManager.getInstance(getApplication()).sendBroadcast(intent)
    }

    private fun clearAllData() {
        _uiState.update {
            MainUiState(
                user = it.user,
                isLoading = false
            )
        }
        _rankingState.update {
            RankingUiState()
        }
    }
}
