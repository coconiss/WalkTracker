package com.walktracker.app.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.walktracker.app.model.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class FirebaseRepository {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private val usersCollection = firestore.collection("users")
    private val activitiesCollection = firestore.collection("daily_activities")
    private val rankingsCollection = firestore.collection("rankings")

    // 랭킹 배치 업데이트를 위한 큐
    // private val pendingRankingUpdates = mutableMapOf<String, RankingUpdate>()

    data class RankingUpdate(
        val userId: String,
        val displayName: String,
        val distance: Double,
        val period: String,
        val periodKey: String
    )

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

    // --- Activity Data --- //

    suspend fun updateDailyActivity(userId: String, date: String, activity: DailyActivity): Result<Unit> {
        return try {
            val docId = "${userId}_$date"
            activitiesCollection.document(docId).set(activity).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun incrementDailyActivity(
        userId: String,
        date: String,
        steps: Long,
        distance: Double,
        calories: Double,
        altitude: Double,
        routes: List<RoutePoint> = emptyList()
    ): Result<Unit> = try {
        var totalDistance = 0.0
        var userName = ""

        firestore.runTransaction { transaction ->
            val userRef = usersCollection.document(userId)
            val user = transaction.get(userRef).toObject(User::class.java)
                ?: throw IllegalStateException("User not found")
            userName = user.displayName

            val activityDocId = "${userId}_$date"
            val activityDocRef = activitiesCollection.document(activityDocId)
            val activitySnapshot = transaction.get(activityDocRef)

            if (activitySnapshot.exists()) {
                val updates = mutableMapOf<String, Any>(
                    "steps" to FieldValue.increment(steps),
                    "distance" to FieldValue.increment(distance),
                    "calories" to FieldValue.increment(calories),
                    "altitude" to FieldValue.increment(altitude),
                    "updatedAt" to Timestamp.now()
                )
                if (routes.isNotEmpty()) {
                    updates["routes"] = FieldValue.arrayUnion(*routes.toTypedArray())
                }
                transaction.update(activityDocRef, updates)
                totalDistance = (activitySnapshot.getDouble("distance") ?: 0.0) + distance
            } else {
                val newActivity = DailyActivity(
                    activityDocId, userId, date, steps, distance, calories,
                    altitude = altitude, routes = routes
                )
                transaction.set(activityDocRef, newActivity)
                totalDistance = distance
            }
        }.await()

        // 랭킹 업데이트를 큐에 추가 (서버에서 처리하므로 주석 처리)
        // queueRankingUpdate(userId, userName, totalDistance, date)

        Result.success(Unit)
    } catch (e: Exception) {
        Log.e(TAG, "incrementDailyActivity 실패", e)
        Result.failure(e)
    }

    /* 서버에서 랭킹을 집계하므로 클라이언트의 업데이트 로직은 주석 처리합니다.
    private fun queueRankingUpdate(userId: String, displayName: String, distance: Double, date: String) {
        try {
            val calendar = Calendar.getInstance().apply {
                time = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date)!!
            }

            val periods = listOf(
                "daily" to date,
                "monthly" to String.format(
                    "%04d-%02d",
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH) + 1
                ),
                "yearly" to calendar.get(Calendar.YEAR).toString()
            )

            periods.forEach { (period, periodKey) ->
                val key = "${period}_${periodKey}_${userId}"
                pendingRankingUpdates[key] = RankingUpdate(
                    userId = userId,
                    displayName = displayName,
                    distance = distance,
                    period = period,
                    periodKey = periodKey
                )
            }

            Log.d(TAG, "랭킹 업데이트 큐에 추가: ${pendingRankingUpdates.size}개")
        } catch (e: Exception) {
            Log.e(TAG, "랭킹 큐 추가 실패", e)
        }
    }

    suspend fun flushRankingUpdates(): Result<Unit> {
        if (pendingRankingUpdates.isEmpty()) {
            return Result.success(Unit)
        }

        return try {
            firestore.runBatch { batch ->
                pendingRankingUpdates.forEach { (key, update) ->
                    val docRef = rankingsCollection.document(key)
                    batch.set(docRef, mapOf(
                        "userId" to update.userId,
                        "displayName" to update.displayName,
                        "distance" to update.distance,
                        "period" to update.period,
                        "periodKey" to update.periodKey,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ), SetOptions.merge())
                }
            }.await()

            Log.d(TAG, "랭킹 배치 업데이트 완료: ${pendingRankingUpdates.size}개")
            pendingRankingUpdates.clear()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "랭킹 배치 업데이트 실패", e)
            Result.failure(e)
        }
    }
    */

    fun getDailyActivity(userId: String, date: String): Flow<DailyActivity?> {
        return callbackFlow {
            val docId = "${userId}_$date"
            val listener = activitiesCollection.document(docId).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.toObject(DailyActivity::class.java))
            }
            awaitClose { listener.remove() }
        }
    }

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
}