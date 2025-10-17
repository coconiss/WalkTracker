package com.walktracker.app.repository

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Transaction
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

    // --- Authentication --- //

    fun getCurrentUserId(): String? = auth.currentUser?.uid

    suspend fun signUpWithEmailPassword(email: String, password: String, weight: Double): Result<Unit> {
        return try {
            val authResult = auth.createUserWithEmailAndPassword(email, password).await()
            val firebaseUser = authResult.user ?: throw IllegalStateException("FirebaseUser is null after creation")

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

    suspend fun incrementDailyActivity(
        userId: String,
        date: String,
        steps: Long,
        distance: Double,
        calories: Double,
        routes: List<RoutePoint> = emptyList()
    ): Result<Unit> = try {
        firestore.runTransaction {
            val user = it.get(usersCollection.document(userId)).toObject(User::class.java) ?: return@runTransaction
            val docId = "${userId}_$date"
            val docRef = activitiesCollection.document(docId)
            val snapshot = it.get(docRef)

            if (snapshot.exists()) {
                val updates = mutableMapOf<String, Any>(
                    "steps" to FieldValue.increment(steps),
                    "distance" to FieldValue.increment(distance),
                    "calories" to FieldValue.increment(calories),
                    "updatedAt" to Timestamp.now()
                )
                if (routes.isNotEmpty()) {
                    updates["routes"] = FieldValue.arrayUnion(*routes.toTypedArray())
                }
                it.update(docRef, updates)
            } else {
                val newActivity = DailyActivity(docId, userId, date, steps, distance, calories, routes = routes)
                it.set(docRef, newActivity)
            }
            updateRankingsInTransaction(it, user, date, distance)
        }.await()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun updateRankingsInTransaction(transaction: Transaction, user: User, date: String, distance: Double) {
        val parsedDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date) ?: return
        val calendar = Calendar.getInstance().apply { time = parsedDate }

        val periods = mapOf(
            "daily" to date,
            "monthly" to String.format("%04d-%02d", calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1),
            "yearly" to calendar.get(Calendar.YEAR).toString()
        )

        periods.forEach { (period, periodKey) ->
            val rankingDocId = "${period}_${periodKey}_${user.userId}"
            val rankingRef = rankingsCollection.document(rankingDocId)
            val rankingSnapshot = transaction.get(rankingRef)

            if (rankingSnapshot.exists()) {
                transaction.update(rankingRef, "distance", FieldValue.increment(distance))
            } else {
                val newRanking = RankingEntry(
                    userId = user.userId,
                    displayName = user.displayName,
                    distance = distance,
                    period = period,
                    periodKey = periodKey
                )
                transaction.set(rankingRef, newRanking)
            }
        }
    }

    fun getDailyActivity(userId: String, date: String): Flow<DailyActivity?> {
        return callbackFlow {
            val docId = "${userId}_${date}"
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
        val docId = "${userId}_${date}"
        activitiesCollection.document(docId).get()
            .addOnSuccessListener { document -> onComplete(document?.toObject(DailyActivity::class.java)) }
            .addOnFailureListener { onComplete(null) }
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
                    val activities = snapshot?.documents?.mapNotNull { it.toObject(DailyActivity::class.java) } ?: emptyList()
                    trySend(activities)
                }
            awaitClose { listener.remove() }
        }
    }

    // --- Rankings --- //

    suspend fun getRankings(period: String, periodKey: String, limit: Int = 100): List<RankingEntry> {
        return try {
            rankingsCollection
                .whereEqualTo("period", period)
                .whereEqualTo("periodKey", periodKey)
                .orderBy("distance", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get()
                .await()
                .documents
                .mapIndexed { index, doc ->
                    doc.toObject(RankingEntry::class.java)?.copy(rank = index + 1)
                }
                .filterNotNull()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getUserRank(period: String, periodKey: String): Int? {
        val userId = getCurrentUserId() ?: return null
        return try {
            val docId = "${period}_${periodKey}_${userId}"
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