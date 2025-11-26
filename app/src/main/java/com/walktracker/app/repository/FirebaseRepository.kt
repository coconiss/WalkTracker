package com.walktracker.app.repository

import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.walktracker.app.data.local.WalkTrackerDatabase
import com.walktracker.app.data.local.entity.LocalDailyActivityEntity
import com.walktracker.app.model.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class FirebaseRepository(context: Context) {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val database = WalkTrackerDatabase.getDatabase(context)
    private val dailyActivityDao = database.dailyActivityDao()
    private val gson = Gson()

    private val usersCollection = firestore.collection("users")
    private val activitiesCollection = firestore.collection("daily_activities")
    private val rankingsCollection = firestore.collection("rankings")

    companion object {
        private const val TAG = "FirebaseRepository"
    }

    // --- Authentication --- //

    fun getCurrentUserId(): String? = auth.currentUser?.uid

    suspend fun signUpWithEmailPassword(email: String, password: String, weight: Double): Result<Unit> {
        return try {
            val authResult = auth.createUserWithEmailAndPassword(email, password).await()
            val firebaseUser = authResult.user ?: throw IllegalStateException("FirebaseUser is null")

            val newUser = User(
                userId = firebaseUser.uid,
                email = email,
                displayName = email.substringBefore('@'),
                weight = weight,
                createdAt = Timestamp.now()
            )
            saveUser(newUser).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loginWithEmailPassword(email: String, password: String): Result<Unit> {
        return try {
            auth.signInWithEmailAndPassword(email, password).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signOut() {
        auth.signOut()
    }

    suspend fun deleteAccount(): Result<Unit> {
        val userId = getCurrentUserId() ?: return Result.failure(Exception("User not signed in"))
        return try {
            // Room 데이터 삭제
            dailyActivityDao.deleteAllForUser(userId)

            // Firestore 데이터 삭제
            val activitiesSnapshot = activitiesCollection.whereEqualTo("userId", userId).get().await()
            val rankingsSnapshot = rankingsCollection.whereEqualTo("userId", userId).get().await()

            firestore.runBatch { batch ->
                batch.delete(usersCollection.document(userId))
                for (document in activitiesSnapshot.documents) {
                    batch.delete(document.reference)
                }
                for (document in rankingsSnapshot.documents) {
                    batch.delete(document.reference)
                }
            }.await()

            auth.currentUser?.delete()?.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete account", e)
            Result.failure(e)
        }
    }

    // --- User Data --- //

    suspend fun getCurrentUser(): User? {
        val userId = getCurrentUserId() ?: return null
        return try {
            usersCollection.document(userId).get().await().toObject(User::class.java)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveUser(user: User): Result<Unit> {
        return try {
            usersCollection.document(user.userId).set(user).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateUserWeight(userId: String, weight: Double): Result<Unit> {
        return try {
            usersCollection.document(userId).update("weight", weight).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Activity Data (Room 중심) --- //

    /**
     * Room에 데이터 증분 저장 (로컬 우선)
     */
    suspend fun incrementDailyActivityLocal(
        userId: String,
        date: String,
        steps: Long,
        distance: Double,
        calories: Double,
        altitude: Double,
        routes: List<RoutePoint> = emptyList()
    ): Result<Unit> {
        return try {
            dailyActivityDao.incrementActivity(
                userId = userId,
                date = date,
                stepsIncrement = steps,
                distanceIncrement = distance,
                caloriesIncrement = calories,
                altitudeIncrement = altitude,
                newRoutes = routes
            )
            Log.d(TAG, "Room 저장 성공: steps=$steps, distance=$distance")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Room 저장 실패", e)
            Result.failure(e)
        }
    }

    /**
     * 미동기화된 데이터를 Firestore로 동기화
     */
    suspend fun syncUnsyncedActivities(): Result<Unit> {
        return try {
            val unsyncedList = dailyActivityDao.getUnsyncedActivities()
            if (unsyncedList.isEmpty()) {
                Log.d(TAG, "동기화할 데이터 없음")
                return Result.success(Unit)
            }

            Log.d(TAG, "Firestore 동기화 시작: ${unsyncedList.size}개 항목")

            for (localActivity in unsyncedList) {
                try {
                    // Firestore에 업데이트
                    val docId = localActivity.id
                    val routes: List<RoutePoint> = gson.fromJson(
                        localActivity.routes,
                        object : TypeToken<List<RoutePoint>>() {}.type
                    )

                    val activityDocRef = activitiesCollection.document(docId)
                    val activitySnapshot = activityDocRef.get().await()

                    if (activitySnapshot.exists()) {
                        // 기존 문서 업데이트
                        activityDocRef.update(
                            mapOf(
                                "steps" to FieldValue.increment(localActivity.steps),
                                "distance" to FieldValue.increment(localActivity.distance),
                                "calories" to FieldValue.increment(localActivity.calories),
                                "altitude" to FieldValue.increment(localActivity.altitude),
                                "routes" to FieldValue.arrayUnion(*routes.toTypedArray()),
                                "updatedAt" to Timestamp.now()
                            )
                        ).await()
                    } else {
                        // 새 문서 생성
                        val newActivity = DailyActivity(
                            id = docId,
                            userId = localActivity.userId,
                            date = localActivity.date,
                            steps = localActivity.steps,
                            distance = localActivity.distance,
                            calories = localActivity.calories,
                            altitude = localActivity.altitude,
                            routes = routes,
                            updatedAt = Timestamp.now()
                        )
                        activityDocRef.set(newActivity).await()
                    }

                    // 동기화 완료 표시
                    dailyActivityDao.markAsSynced(localActivity.id)
                    Log.d(TAG, "Firestore 동기화 완료: ${localActivity.id}")
                } catch (e: Exception) {
                    Log.e(TAG, "개별 항목 동기화 실패: ${localActivity.id}", e)
                    // 계속 진행
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Firestore 동기화 실패", e)
            Result.failure(e)
        }
    }

    /**
     * 로컬 Room에서 특정 날짜 데이터 조회
     */
    suspend fun getDailyActivityLocal(userId: String, date: String): DailyActivity? {
        return try {
            val local = dailyActivityDao.getActivityByUserAndDate(userId, date)
            local?.toDailyActivity()
        } catch (e: Exception) {
            Log.e(TAG, "로컬 데이터 조회 실패", e)
            null
        }
    }

    /**
     * Firestore에서 데이터 직접 조회 (UI 초기화용)
     */
    suspend fun getDailyActivityOnce(userId: String, date: String, onComplete: (DailyActivity?) -> Unit) {
        val docId = "${userId}_$date"
        activitiesCollection.document(docId).get()
            .addOnSuccessListener { document -> onComplete(document?.toObject(DailyActivity::class.java)) }
            .addOnFailureListener { onComplete(null) }
    }

    fun getWeeklyActivity(userId: String, dates: List<String>, onResult: (List<DailyActivity>) -> Unit) {
        firestore.collection("daily_activities")
            .whereEqualTo("userId", userId)
            .whereIn("date", dates)
            .get()
            .addOnSuccessListener { documents ->
                val activities = documents.toObjects(DailyActivity::class.java)
                onResult(activities)
            }
            .addOnFailureListener {
                onResult(emptyList())
            }
    }

    fun getUserActivitiesFlow(userId: String, limit: Int = 30): Flow<List<DailyActivity>> {
        return callbackFlow {
            val listener = activitiesCollection
                .whereEqualTo("userId", userId)
                .orderBy("date", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        close(error)
                        return@addSnapshotListener
                    }
                    val activities = snapshot?.documents?.mapNotNull {
                        it.toObject(DailyActivity::class.java)
                    } ?: emptyList()
                    trySend(activities)
                }
            awaitClose { listener.remove() }
        }
    }

    /**
     * Room 데이터를 Flow로 제공 (실시간 업데이트용)
     */
    fun getDailyActivityFlow(userId: String, date: String): Flow<DailyActivity?> {
        return dailyActivityDao.getActivitiesFlow(userId, 30)
            .map { list -> list.find { it.date == date }?.toDailyActivity() }
    }

    // --- Rankings --- //

    suspend fun getRankings(period: String, periodKey: String, limit: Int = 100): List<RankingEntry> {
        Log.d(TAG, "getRankings: period=$period, periodKey=$periodKey")
        return try {
            val snapshot = rankingsCollection
                .whereEqualTo("period", period)
                .whereEqualTo("periodKey", periodKey)
                .orderBy("distance", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get()
                .await()

            val rankings = snapshot.documents
                .mapIndexedNotNull { index, doc ->
                    try {
                        doc.toObject(RankingEntry::class.java)?.apply { rank = index + 1 }
                    } catch (e: Exception) {
                        Log.e(TAG, "문서 변환 실패: docId=${doc.id}", e)
                        null
                    }
                }

            Log.d(TAG, "랭킹 목록 크기: ${rankings.size}")
            rankings
        } catch (e: Exception) {
            Log.e(TAG, "getRankings 실패", e)
            emptyList()
        }
    }

    suspend fun getUserRank(period: String, periodKey: String): Int? {
        val userId = getCurrentUserId() ?: return null
        return try {
            val docId = "${period}_${periodKey}_${userId}"
            val userRanking = rankingsCollection.document(docId).get().await()
                .toObject(RankingEntry::class.java) ?: return null

            val higherCount = rankingsCollection
                .whereEqualTo("period", period)
                .whereEqualTo("periodKey", periodKey)
                .whereGreaterThan("distance", userRanking.distance)
                .get()
                .await()
                .size()

            higherCount + 1
        } catch (e: Exception) {
            null
        }
    }

    // --- Extension Functions --- //

    private fun LocalDailyActivityEntity.toDailyActivity(): DailyActivity {
        val routes: List<RoutePoint> = gson.fromJson(
            this.routes,
            object : TypeToken<List<RoutePoint>>() {}.type
        )
        return DailyActivity(
            id = this.id,
            userId = this.userId,
            date = this.date,
            steps = this.steps,
            distance = this.distance,
            calories = this.calories,
            altitude = this.altitude,
            activeMinutes = this.activeMinutes,
            routes = routes,
            updatedAt = Timestamp(this.lastModified / 1000, 0)
        )
    }
}