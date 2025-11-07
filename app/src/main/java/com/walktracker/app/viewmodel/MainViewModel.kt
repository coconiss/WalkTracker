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
import com.walktracker.app.model.DailyActivity
import com.walktracker.app.model.RankingEntry
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
    val weeklyActivity: List<DailyActivity> = emptyList(), // 주간 활동 데이터 추가
    val lastKnownLocation: Location? = null,
    val currentSpeed: Float = 0.0f, // 현재 속도 (m/s)
    val isLoading: Boolean = true,
    val error: String? = null,
    val notificationEnabled: Boolean = true,
)

data class RankingUiState(
    val rankings: List<RankingEntry> = emptyList(),
    val userRank: Int? = null,
    val isLoading: Boolean = false,
    val selectedPeriod: RankingPeriod = RankingPeriod.DAILY,
    val error: String? = null,
    val lastUpdated: Long = 0L // 데이터 마지막 업데이트 시간
)

enum class RankingPeriod {
    DAILY, MONTHLY, YEARLY
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FirebaseRepository()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val prefs = application.getSharedPreferences("WalkTrackerPrefs", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _rankingState = MutableStateFlow(RankingUiState())
    val rankingState: StateFlow<RankingUiState> = _rankingState.asStateFlow()

    // 5분 캐시
    private val CACHE_DURATION_MS = 5 * 60 * 1000

    private val activityUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationTrackingService.ACTION_ACTIVITY_UPDATE) {
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
        loadUserData()
        loadInitialTodayActivity() // 삭제: 서비스에서 보내주는 실시간 데이터로만 상태를 업데이트하여 데이터 덮어쓰기 방지
        loadWeeklyActivity()
        loadNotificationSetting()
        registerReceivers()
        requestLocationUpdate()
    }

    override fun onCleared() {
        super.onCleared()
        unregisterReceivers()
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
        // 이미 데이터가 있다면 실행하지 않음 (실시간 데이터 보호)
        if (_uiState.value.todayActivity != null) {
            return
        }

        viewModelScope.launch {
            val userId = repository.getCurrentUserId() ?: return@launch
            val today = dateFormat.format(Date())

            repository.getDailyActivityOnce(userId, today) { activity ->
                // 이 함수는 이제 새로고침 시에만 사용
                if (activity != null) {
                    _uiState.update { it.copy(todayActivity = activity) }
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

            repository.updateDailyActivity(userId, today, resetActivity)

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

    fun loadRankings(period: RankingPeriod) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val lastUpdated = _rankingState.value.lastUpdated
            val isCacheValid = (now - lastUpdated) < CACHE_DURATION_MS

            // 캐시가 유효하고, 요청 기간이 현재 기간과 같으면 로직을 실행하지 않음
            if (isCacheValid && _rankingState.value.selectedPeriod == period) {
                return@launch
            }

            _rankingState.update { it.copy(isLoading = true, selectedPeriod = period, error = null) }

            try {
                val (periodStr, periodKey) = getPeriodInfo(period)
                val rankings = repository.getRankings(periodStr, periodKey, 100)
                Log.d("MainViewModel", "Rankings for $periodStr ($periodKey): $rankings")

                val userId = repository.getCurrentUserId()
                var userRank: Int? = null

                if (userId != null) {
                    val userInList = rankings.find { it.userId == userId }
                    userRank = if (userInList != null) {
                        userInList.rank
                    } else {
                        repository.getUserRank(periodStr, periodKey)
                    }
                }

                _rankingState.update {
                    it.copy(
                        rankings = rankings,
                        userRank = userRank,
                        isLoading = false,
                        error = null,
                        lastUpdated = System.currentTimeMillis() // 업데이트 시간 기록
                    )
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to load rankings for $period", e)
                _rankingState.update {
                    it.copy(
                        isLoading = false,
                        error = "랭킹 데이터를 불러오는데 실패했습니다: ${e.message}"
                    )
                }
            }
        }
    }

    private fun getPeriodInfo(period: RankingPeriod): Pair<String, String> {
        val calendar = Calendar.getInstance()
        return when (period) {
            RankingPeriod.DAILY -> {
                calendar.add(Calendar.DATE, -1) // 어제 날짜
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
        loadInitialTodayActivity() // 사용자가 직접 새로고침할 때만 동기화된 데이터 로드
        loadWeeklyActivity()
        // 랭킹 새로고침 시에는 캐시를 무시하고 새로 불러오도록 lastUpdated를 0으로 설정
        _rankingState.update { it.copy(lastUpdated = 0L) }
        loadRankings(_rankingState.value.selectedPeriod)
    }

    fun requestLocationUpdate() {
        val intent = Intent(LocationTrackingService.ACTION_REQUEST_LOCATION_UPDATE)
        LocalBroadcastManager.getInstance(getApplication()).sendBroadcast(intent)
    }
}
