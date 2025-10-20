package com.walktracker.app.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.os.Build
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
    val isLoading: Boolean = false,
    val error: String? = null,
    val notificationEnabled: Boolean = true
)

data class RankingUiState(
    val rankings: List<RankingEntry> = emptyList(),
    val userRank: Int? = null,
    val isLoading: Boolean = false,
    val selectedPeriod: RankingPeriod = RankingPeriod.DAILY,
    val error: String? = null
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

    private val activityUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationTrackingService.ACTION_ACTIVITY_UPDATE) {
                val steps = intent.getLongExtra(LocationTrackingService.EXTRA_STEPS, 0L)
                val distance = intent.getDoubleExtra(LocationTrackingService.EXTRA_DISTANCE, 0.0)
                val calories = intent.getDoubleExtra(LocationTrackingService.EXTRA_CALORIES, 0.0)

                // 실시간으로 UI 업데이트 (Firebase와 무관)
                _uiState.update { currentState ->
                    val updatedActivity = (currentState.todayActivity ?: DailyActivity(
                        userId = repository.getCurrentUserId() ?: "",
                        date = dateFormat.format(Date())
                    )).copy(
                        steps = steps,
                        distance = distance,
                        calories = calories
                    )
                    currentState.copy(todayActivity = updatedActivity)
                }
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
                location?.let {
                    _uiState.update { currentState ->
                        currentState.copy(lastKnownLocation = it)
                    }
                }
            }
        }
    }

    init {
        loadUserData()
        loadInitialTodayActivity()
        loadWeeklyActivity()
        loadNotificationSetting()

        val localBroadcastManager = LocalBroadcastManager.getInstance(application)

        localBroadcastManager.registerReceiver(
            activityUpdateReceiver,
            IntentFilter(LocationTrackingService.ACTION_ACTIVITY_UPDATE)
        )
        localBroadcastManager.registerReceiver(
            locationUpdateReceiver,
            IntentFilter(LocationTrackingService.ACTION_LOCATION_UPDATE)
        )
        
        requestLocationUpdate()
    }

    override fun onCleared() {
        super.onCleared()
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
        viewModelScope.launch {
            val userId = repository.getCurrentUserId() ?: return@launch
            val today = dateFormat.format(Date())

            // Firebase에서 오늘 데이터 로드 (서비스 시작 전 마지막 동기화된 데이터)
            repository.getDailyActivityOnce(userId, today) { activity ->
                if (activity != null) {
                    _uiState.update { it.copy(todayActivity = activity) }
                }
            }

            // 이후로는 서비스에서 브로드캐스트하는 실시간 데이터를 사용
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
            _rankingState.update { it.copy(isLoading = true, selectedPeriod = period, error = null) }

            try {
                val (periodStr, periodKey) = getPeriodInfo(period)
                val rankings = repository.getRankings(periodStr, periodKey, 100)

                val userId = repository.getCurrentUserId()
                var userRank: Int? = null

                if (userId != null) {
                    val userInList = rankings.find { it.userId == userId }
                    userRank = if (userInList != null) {
                        userInList.rank
                    } else {
                        // 리스트에 없으면 별도로 조회
                        repository.getUserRank(periodStr, periodKey)
                    }
                }

                _rankingState.update {
                    it.copy(
                        rankings = rankings,
                        userRank = userRank,
                        isLoading = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
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
            RankingPeriod.DAILY -> "daily" to dateFormat.format(calendar.time)
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
        loadWeeklyActivity() // 새로고침 시 주간 데이터도 로드
        if (_rankingState.value.selectedPeriod != null) {
            loadRankings(_rankingState.value.selectedPeriod)
        }
    }

    fun requestLocationUpdate() {
        val intent = Intent(LocationTrackingService.ACTION_REQUEST_LOCATION_UPDATE)
        LocalBroadcastManager.getInstance(getApplication()).sendBroadcast(intent)
    }
}
