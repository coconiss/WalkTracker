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
import com.walktracker.app.model.RoutePoint
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
    val todayRoutes: List<RoutePoint> = emptyList(),
    val lastKnownLocation: Location? = null,
    val currentSpeed: Float = 0.0f,
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

    private val _accountDeleted = MutableSharedFlow<Boolean>()
    val accountDeleted: SharedFlow<Boolean> = _accountDeleted.asSharedFlow()

    private val activityUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationTrackingService.ACTION_ACTIVITY_UPDATE) {
                val steps = intent.getLongExtra(LocationTrackingService.EXTRA_STEPS, 0L)
                val distance = intent.getDoubleExtra(LocationTrackingService.EXTRA_DISTANCE, 0.0)
                val calories = intent.getDoubleExtra(LocationTrackingService.EXTRA_CALORIES, 0.0)
                val altitude = intent.getDoubleExtra(LocationTrackingService.EXTRA_ALTITUDE, 0.0)
                val speed = intent.getFloatExtra(LocationTrackingService.EXTRA_SPEED, 0.0f)

                _uiState.update { currentState ->
                    val updatedActivity = (currentState.todayActivity ?: DailyActivity()).copy(
                        steps = steps, distance = distance, calories = calories, altitude = altitude
                    )
                    currentState.copy(todayActivity = updatedActivity, currentSpeed = speed)
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
                location?.let { _uiState.update { cs -> cs.copy(lastKnownLocation = it) } }
            }
        }
    }
    
    private val routeUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationTrackingService.ACTION_ROUTE_UPDATE) {
                 val routePoint: RoutePoint? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(LocationTrackingService.EXTRA_ROUTE, RoutePoint::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(LocationTrackingService.EXTRA_ROUTE)
                }
                routePoint?.let {
                    _uiState.update { cs -> cs.copy(todayRoutes = cs.todayRoutes + it) }
                }
            }
        }
    }

    init {
        loadInitialData()
        registerReceivers()
    }

    private fun loadInitialData(){
        loadUserData()
        loadInitialTodayActivity()
        loadWeeklyActivity()
        loadNotificationSetting()
    }

    private fun registerReceivers(){
        val lm = LocalBroadcastManager.getInstance(getApplication())
        lm.registerReceiver(activityUpdateReceiver, IntentFilter(LocationTrackingService.ACTION_ACTIVITY_UPDATE))
        lm.registerReceiver(locationUpdateReceiver, IntentFilter(LocationTrackingService.ACTION_LOCATION_UPDATE))
        lm.registerReceiver(routeUpdateReceiver, IntentFilter(LocationTrackingService.ACTION_ROUTE_UPDATE))
        requestLocationUpdate()
    }

    override fun onCleared() {
        super.onCleared()
        val lm = LocalBroadcastManager.getInstance(getApplication())
        lm.unregisterReceiver(activityUpdateReceiver)
        lm.unregisterReceiver(locationUpdateReceiver)
        lm.unregisterReceiver(routeUpdateReceiver)
    }
    
    fun resetTodayActivity() {
        viewModelScope.launch {
            val userId = repository.getCurrentUserId() ?: return@launch
            val today = dateFormat.format(Date())
            if (repository.deleteDailyActivity(userId, today).isSuccess) {
                _uiState.update { it.copy(todayActivity = DailyActivity(userId = userId, date = today), todayRoutes = emptyList()) }
                LocalBroadcastManager.getInstance(getApplication()).sendBroadcast(Intent(LocationTrackingService.ACTION_RESET_TODAY_DATA))
            }
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            if (repository.deleteUserAccount().isSuccess) {
                _accountDeleted.emit(true)
            }
        }
    }

    private fun loadNotificationSetting() {
        _uiState.update { it.copy(notificationEnabled = prefs.getBoolean("notification_enabled", true)) }
    }

    fun setNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("notification_enabled", enabled).apply()
        _uiState.update { it.copy(notificationEnabled = enabled) }
    }

    private fun loadUserData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val user = repository.getCurrentUser()
            _uiState.update { it.copy(user = user, isLoading = false, error = if (user == null) "사용자 정보를 불러올 수 없습니다" else null) }
        }
    }

    private fun loadInitialTodayActivity() {
        viewModelScope.launch {
            val userId = repository.getCurrentUserId() ?: return@launch
            val today = dateFormat.format(Date())
            repository.getDailyActivityOnce(userId, today) { activity ->
                activity?.let {
                    _uiState.update { ui -> ui.copy(todayActivity = it, todayRoutes = it.routes) }
                }
            }
        }
    }
    
    private fun loadWeeklyActivity() {
        viewModelScope.launch {
            val userId = repository.getCurrentUserId() ?: return@launch
            val calendar = Calendar.getInstance()
            val dateList = (0..6).map { dateFormat.format(calendar.time).also { calendar.add(Calendar.DATE, -1) } }.reversed()
            repository.getWeeklyActivity(userId, dateList) { activities ->
                _uiState.update {
                    val mergedActivities = dateList.map { date ->
                        activities.find { it.date == date } ?: DailyActivity(userId = userId, date = date)
                    }
                    it.copy(weeklyActivity = mergedActivities)
                }
            }
        }
    }

    fun updateUserWeight(weight: Double) {
        viewModelScope.launch {
            val userId = repository.getCurrentUserId() ?: return@launch
            if (repository.updateUserWeight(userId, weight).isSuccess) {
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
                    userRank = rankings.find { it.userId == userId }?.rank ?: repository.getUserRank(periodStr, periodKey)
                }
                _rankingState.update { it.copy(rankings = rankings, userRank = userRank, isLoading = false, error = null) }
            } catch (e: Exception) {
                _rankingState.update { it.copy(isLoading = false, error = "랭킹 데이터를 불러오는데 실패했습니다: ${e.message}") }
            }
        }
    }

    private fun getPeriodInfo(period: RankingPeriod): Pair<String, String> {
        val calendar = Calendar.getInstance()
        return when (period) {
            RankingPeriod.DAILY -> "daily" to dateFormat.format(calendar.time)
            RankingPeriod.MONTHLY -> "monthly" to String.format("%04d-%02d", calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
            RankingPeriod.YEARLY -> "yearly" to calendar.get(Calendar.YEAR).toString()
        }
    }

    fun refreshData() {
        loadInitialData()
        if (_rankingState.value.selectedPeriod != null) {
            loadRankings(_rankingState.value.selectedPeriod)
        }
    }

    fun requestLocationUpdate() {
        LocalBroadcastManager.getInstance(getApplication()).sendBroadcast(Intent(LocationTrackingService.ACTION_REQUEST_LOCATION_UPDATE))
    }
}
