package com.walktracker.app.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.WriteBatch
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

    fun getCurrentUserId(): String? = auth.currentUser?.uid

    suspend fun signUpWithEmailPassword(email: String, password: String, weight: Double): Result<Unit> = try {
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

    suspend fun loginWithEmailPassword(email: String, password: String): Result<Unit> = try {
        auth.signInWithEmailAndPassword(email, password).await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun signOut() {
        auth.signOut()
    }

    suspend fun deleteUserAccount(): Result<Unit> = try {
        val userId = getCurrentUserId() ?: throw IllegalStateException("User not logged in")
        val user = auth.currentUser!!

        // 1. 삭제할 문서를 먼저 조회합니다.
        val activitiesToDelete = activitiesCollection.whereEqualTo("userId", userId).get().await()
        val rankingsToDelete = rankingsCollection.whereEqualTo("userId", userId).get().await()

        // 2. Batch 작업을 통해 읽어온 문서들을 삭제합니다.
        firestore.runBatch { batch ->
            // 사용자 문서 삭제
            batch.delete(usersCollection.document(userId))

            // 활동 데이터 삭제
            activitiesToDelete.documents.forEach { doc ->
                batch.delete(doc.reference)
            }

            // 랭킹 데이터 삭제
            rankingsToDelete.documents.forEach { doc ->
                batch.delete(doc.reference)
            }
        }.await()

        // 3. Firebase Authentication에서 사용자를 최종 삭제합니다.
        user.delete().await()

        Result.success(Unit)
    } catch (e: Exception) {
        Log.e("FirebaseRepository", "Failed to delete user account", e)
        Result.failure(e)
    }


    suspend fun getCurrentUser(): User? {
        val userId = getCurrentUserId() ?: return null
        return try {
            usersCollection.document(userId).get().await().toObject(User::class.java)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveUser(user: User): Result<Unit> = try {
        usersCollection.document(user.userId).set(user).await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun updateUserWeight(userId: String, weight: Double): Result<Unit> = try {
        usersCollection.document(userId).update("weight", weight).await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun deleteDailyActivity(userId: String, date: String): Result<Unit> = try {
        activitiesCollection.document("${userId}_$date").delete().await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun incrementDailyActivity(
        userId: String, date: String, steps: Long, distance: Double,
        calories: Double, altitude: Double, routes: List<RoutePoint> = emptyList()
    ): Result<Unit> = try {
        firestore.runTransaction { transaction ->
            val userRef = usersCollection.document(userId)
            val user = transaction.get(userRef).toObject(User::class.java) ?: throw IllegalStateException("User not found")
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
            } else {
                val newActivity = DailyActivity(activityDocId, userId, date, steps, distance, calories, altitude, routes = routes)
                transaction.set(activityDocRef, newActivity)
            }

            // Ranking updates can also be batched if needed, but transaction is fine
        }.await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

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
        activitiesCollection.document("${userId}_$date").get()
            .addOnSuccessListener { document -> onComplete(document?.toObject(DailyActivity::class.java)) }
            .addOnFailureListener { onComplete(null) }
    }

    fun getWeeklyActivity(userId: String, dates: List<String>, onResult: (List<DailyActivity>) -> Unit) {
        activitiesCollection.whereEqualTo("userId", userId).whereIn("date", dates).get()
            .addOnSuccessListener { documents -> onResult(documents.toObjects(DailyActivity::class.java)) }
            .addOnFailureListener { onResult(emptyList()) }
    }

    suspend fun getRankings(period: String, periodKey: String, limit: Int = 100): List<RankingEntry> {
        return try {
            val snapshot = rankingsCollection
                .whereEqualTo("period", period)
                .whereEqualTo("periodKey", periodKey)
                .orderBy("distance", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get()
                .await()
            snapshot.documents.mapIndexedNotNull { index, doc ->
                doc.toObject(RankingEntry::class.java)?.apply { rank = index + 1 }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getUserRank(period: String, periodKey: String): Int? {
        val userId = getCurrentUserId() ?: return null
        return try {
            val docId = "${period}_${periodKey}_$userId"
            val userRanking = rankingsCollection.document(docId).get().await().toObject(RankingEntry::class.java) ?: return null
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