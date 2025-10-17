package com.walktracker.app.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    val isLoading: Boolean = false,
    val error: String? = null,
    val notificationEnabled: Boolean = true
)

data class RankingUiState(
    val rankings: List<RankingEntry> = emptyList(),
    val userRank: Int? = null,
    val isLoading: Boolean = false,
    val selectedPeriod: RankingPeriod = RankingPeriod.DAILY
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

                val currentActivity = _uiState.value.todayActivity
                _uiState.update {
                    it.copy(
                        todayActivity = (currentActivity ?: DailyActivity()).copy(
                            steps = steps,
                            distance = distance,
                            calories = calories
                        )
                    )
                }
            }
        }
    }

    init {
        loadUserData()
        loadInitialTodayActivity()
        loadNotificationSetting()

        LocalBroadcastManager.getInstance(application).registerReceiver(
            activityUpdateReceiver,
            IntentFilter(LocationTrackingService.ACTION_ACTIVITY_UPDATE)
        )
    }

    override fun onCleared() {
        super.onCleared()
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(activityUpdateReceiver)
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

            repository.getDailyActivityOnce(userId, today) { activity ->
                _uiState.update { it.copy(todayActivity = activity) }
            }
        }
    }

    fun updateUserWeight(weight: Double) {
        viewModelScope.launch {
            val userId = repository.getCurrentUserId() ?: return@launch
            repository.updateUserWeight(userId, weight)
            loadUserData() // 사용자 정보는 별도로 다시 로드
        }
    }

    fun loadRankings(period: RankingPeriod) {
        viewModelScope.launch {
            _rankingState.update { it.copy(isLoading = true, selectedPeriod = period) }
            val (periodStr, periodKey) = getPeriodInfo(period)
            val rankings = repository.getRankings(periodStr, periodKey, 100)

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
                it.copy(rankings = rankings, userRank = userRank, isLoading = false)
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
        loadRankings(_rankingState.value.selectedPeriod)
    }
}
