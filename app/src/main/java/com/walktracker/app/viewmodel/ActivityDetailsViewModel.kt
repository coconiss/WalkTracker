package com.walktracker.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.walktracker.app.model.RoutePoint
import com.walktracker.app.ui.screen.ActivityDetailData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class ChartType {
    BAR,
    LINE
}

data class ActivityDetailsUiState(
    val isLoading: Boolean = false,
    val startDate: Long = System.currentTimeMillis(),
    val endDate: Long = System.currentTimeMillis(),
    val activityData: List<ActivityDetailData> = emptyList(),
    val error: String? = null,
    val chartType: ChartType = ChartType.BAR
)

data class FirestoreDailyActivity(
    val steps: Long = 0L,
    val distance: Double = 0.0,
    val calories: Double = 0.0,
    val date: String = "",
    val routes: List<RoutePoint> = emptyList()
)

class ActivityDetailsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ActivityDetailsUiState())
    val uiState: StateFlow<ActivityDetailsUiState> = _uiState.asStateFlow()

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    companion object {
        private const val TAG = "ActivityDetailsVM"
    }

    init {
        val calendar = Calendar.getInstance()
        val endDate = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_YEAR, -6)
        val startDate = calendar.timeInMillis
        _uiState.update { it.copy(startDate = startDate, endDate = endDate) }
        loadActivityData()
    }

    fun onDateRangeSelected(startDate: Long, endDate: Long) {
        if (startDate > endDate) {
            _uiState.update { it.copy(startDate = endDate, endDate = startDate) }
        } else {
            _uiState.update { it.copy(startDate = startDate, endDate = endDate) }
        }
    }

    fun onChartTypeSelected(chartType: ChartType) {
        _uiState.update { it.copy(chartType = chartType) }
    }

    fun loadActivityData() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Log.w(TAG, "User not logged in.")
            _uiState.update { it.copy(error = "로그인이 필요합니다.", isLoading = false) }
            return
        }
        Log.d(TAG, "Loading activity data for user: ${currentUser.uid}")

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val startDateString = dateFormat.format(Date(_uiState.value.startDate))
                val endDateString = dateFormat.format(Date(_uiState.value.endDate))

                val startDocId = "${currentUser.uid}_$startDateString"
                val endDocId = "${currentUser.uid}_$endDateString"

                Log.d(TAG, "Querying 'daily_activities' for doc IDs from $startDocId to $endDocId")

                val querySnapshot = firestore.collection("daily_activities")
                    .orderBy(FieldPath.documentId())
                    .startAt(startDocId)
                    .endAt(endDocId)
                    .get()
                    .await()

                Log.d(TAG, "Firestore query returned ${querySnapshot.size()} documents.")

                val activityList = querySnapshot.documents.mapNotNull { doc ->
                    if (!doc.id.startsWith(currentUser.uid)) return@mapNotNull null

                    val firestoreData = doc.toObject(FirestoreDailyActivity::class.java)
                    if (firestoreData == null) {
                        Log.w(TAG, "Failed to parse document: ${doc.id}")
                        null
                    } else {
                        val avgSpeed = if (firestoreData.routes.isNotEmpty()) {
                            firestoreData.routes.map { it.speed }.average()
                        } else {
                            0.0
                        }

                        val date = dateFormat.parse(firestoreData.date)
                        date?.let {
                            ActivityDetailData(
                                date = it,
                                steps = firestoreData.steps,
                                distance = firestoreData.distance,
                                calories = firestoreData.calories,
                                avgSpeed = avgSpeed
                            )
                        }
                    }
                }.sortedByDescending { it.date }

                Log.d(TAG, "Parsed ${activityList.size} activity items.")
                _uiState.update { it.copy(isLoading = false, activityData = activityList) }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading activity data", e)
                _uiState.update { it.copy(isLoading = false, error = "데이터를 불러오는 데 실패했습니다: ${e.message}") }
            }
        }
    }
}

fun Long.toFormattedDate(): String {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return format.format(this)
}