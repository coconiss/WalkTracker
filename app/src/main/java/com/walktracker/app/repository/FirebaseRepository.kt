package com.walktracker.app.repository

import android.content.Context
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.walktracker.app.data.local.WalkTrackerDatabase
import com.walktracker.app.data.local.entity.LocalDailyActivityEntity
import com.walktracker.app.model.*
import com.walktracker.app.model.RankingLeaderboard
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
    private val usernamesCollection = firestore.collection("usernames") // 닉네임 컬렉션 추가
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

            val displayName = email.substringBefore('@')

            val newUser = User(
                userId = firebaseUser.uid,
                email = email,
                displayName = displayName,
                weight = weight,
                createdAt = Timestamp.now()
            )

            // Batch Write로 사용자 생성과 닉네임 생성을 원자적으로 처리
            firestore.runBatch { batch ->
                batch.set(usersCollection.document(firebaseUser.uid), newUser)
                batch.set(usernamesCollection.document(displayName), mapOf("userId" to firebaseUser.uid))
            }.await()

            Result.success(Unit)
        } catch (e: Exception) {
            // 회원가입 실패 시 생성된 Firebase Auth 계정 롤백
            auth.currentUser?.delete()?.await()
            Log.e(TAG, "signUpWithEmailPassword failed, rolled back auth user.", e)
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
            // 문서를 삭제하기 전에 현재 닉네임을 먼저 가져옵니다.
            val user = getCurrentUser()
            val displayName = user?.displayName

            // Room 데이터 삭제
            dailyActivityDao.deleteAllForUser(userId)

            // Firestore 데이터 삭제 (사용자, 닉네임, 활동 기록)
            val activitiesSnapshot = activitiesCollection.whereEqualTo("userId", userId).get().await()

            firestore.runBatch { batch ->
                // 사용자 문서 삭제
                batch.delete(usersCollection.document(userId))

                // 닉네임 문서 삭제
                if (displayName != null) {
                    batch.delete(usernamesCollection.document(displayName))
                }

                // 모든 활동 기록 문서 삭제
                for (document in activitiesSnapshot.documents) {
                    batch.delete(document.reference)
                }
            }.await()

            // 마지막으로 Auth 계정 삭제
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

    suspend fun isDisplayNameAvailable(displayName: String): Boolean {
        Log.d(TAG, "Checking if displayName is available in 'usernames' collection: $displayName")
        return try {
            val doc = usernamesCollection.document(displayName).get().await()
            val isAvailable = !doc.exists()
            Log.d(TAG, "Display name '$displayName' is available: $isAvailable")
            isAvailable
        } catch (e: Exception) {
            Log.e(TAG, "Error checking display name availability", e)
            false // 오류 발생 시 안전하게 '사용 불가'로 처리
        }
    }

    suspend fun updateUserDisplayName(userId: String, newDisplayName: String): Result<Unit> {
        Log.d(TAG, "Attempting to update displayName for user $userId to $newDisplayName using a transaction.")
        return try {
            firestore.runTransaction { transaction ->
                val userDocRef = usersCollection.document(userId)
                val newUsernameDocRef = usernamesCollection.document(newDisplayName)

                // 1. 현재 사용자 문서에서 이전 닉네임 가져오기
                val userSnapshot = transaction.get(userDocRef)
                val oldDisplayName = userSnapshot.getString("displayName")
                    ?: throw FirebaseFirestoreException("User has no display name", FirebaseFirestoreException.Code.ABORTED)

                // 닉네임이 변경되지 않았다면 아무것도 하지 않음
                if (oldDisplayName == newDisplayName) {
                    return@runTransaction null
                }

                // 2. 새로운 닉네임이 이미 사용 중인지 확인 (트랜잭션 내에서)
                val newUsernameSnapshot = transaction.get(newUsernameDocRef)
                if (newUsernameSnapshot.exists()) {
                    throw FirebaseFirestoreException("Display name '$newDisplayName' is already taken.", FirebaseFirestoreException.Code.ALREADY_EXISTS)
                }

                // [수정] 이전 닉네임 문서 참조 및 존재 확인
                val oldUsernameDocRef = usernamesCollection.document(oldDisplayName)
                val oldUsernameSnapshot = transaction.get(oldUsernameDocRef)

                // 3. 사용자 문서의 닉네임 업데이트
                transaction.update(userDocRef, "displayName", newDisplayName)

                // 4. 이전 닉네임 문서가 존재할 경우에만 삭제
                if (oldUsernameSnapshot.exists()) {
                    transaction.delete(oldUsernameDocRef)
                }

                // 5. 새로운 닉네임 문서 생성
                transaction.set(newUsernameDocRef, mapOf("userId" to userId))

                null // 트랜잭션 성공 시 null 반환
            }.await()

            Log.d(TAG, "Successfully updated displayName for user $userId to $newDisplayName")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update displayName for user $userId", e)
            Result.failure(e)
        }
    }

    // --- Activity Data (Room 중심) --- //

    suspend fun incrementDailyActivityLocal(
        userId: String,
        date: String,
        steps: Long,
        distance: Double,
        calories: Double,
        altitude: Double,
        routes: List<RoutePoint> = emptyList()
    ): Result<Unit> {
        Log.d(TAG, "Room 활동 업데이트 시도 - userId: $userId, date: $date, steps 증분: $steps")
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
            val updatedActivity = dailyActivityDao.getActivityByUserAndDate(userId, date)
            Log.d(TAG, "Room 활동 업데이트 성공 후 데이터: $updatedActivity")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Room 활동 업데이트 실패 - userId: $userId, date: $date", e)
            Result.failure(e)
        }
    }

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
                    val activityToSync = localActivity.toDailyActivity()
                    val activityDocRef = activitiesCollection.document(activityToSync.id)

                    // 항상 set()을 사용하여 문서를 덮어씁니다.
                    activityDocRef.set(activityToSync).await()

                    dailyActivityDao.markAsSynced(localActivity.id)
                    Log.d(TAG, "Firestore 동기화 완료: ${localActivity.id}")
                } catch (e: Exception) {
                    Log.e(TAG, "개별 항목 동기화 실패: ${localActivity.id}", e)
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Firestore 동기화 실패", e)
            Result.failure(e)
        }
    }

    suspend fun syncLocalActivityToFirestore(userId: String, date: String): Result<Unit> {
        return try {
            val localActivityEntity = dailyActivityDao.getActivityByUserAndDate(userId, date)

            if (localActivityEntity != null) {
                val activityToSync = localActivityEntity.toDailyActivity()
                val docId = activityToSync.id
                val activityDocRef = activitiesCollection.document(docId)
                activityDocRef.set(activityToSync).await()
                Log.d(TAG, "Successfully synced local activity for date $date to Firestore.")
            } else {
                Log.d(TAG, "No local activity found for date $date to sync.")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync local activity for date $date to Firestore.", e)
            Result.failure(e)
        }
    }

    suspend fun getDailyActivityLocal(userId: String, date: String): DailyActivity? {
        return try {
            val local = dailyActivityDao.getActivityByUserAndDate(userId, date)
            local?.toDailyActivity()
        } catch (e: Exception) {
            Log.e(TAG, "로컬 데이터 조회 실패", e)
            null
        }
    }
    
    suspend fun resetTodayFirestoreActivity(userId: String, date: String): Result<Unit> {
        val docId = "${userId}_$date"
        Log.d(TAG, "Firestore 활동 초기화 시도 - docId: $docId")
        return try {
            val resetData = mapOf(
                "steps" to 0,
                "distance" to 0.0,
                "calories" to 0.0,
                "altitude" to 0.0,
                "routes" to emptyList<RoutePoint>(),
                "updatedAt" to Timestamp.now()
            )
            activitiesCollection.document(docId).set(resetData, com.google.firebase.firestore.SetOptions.merge()).await()
            Log.d(TAG, "Firestore 활동 초기화 성공 - docId: $docId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Firestore 활동 초기화 실패 - docId: $docId", e)
            Result.failure(e)
        }
    }

    private suspend fun resetDailyActivityLocal(userId: String, date: String) {
        Log.d(TAG, "Room 활동 초기화 시도 - userId: $userId, date: $date")
        dailyActivityDao.resetActivity(userId, date)
        Log.d(TAG, "Room 활동 초기화 성공 - userId: $userId, date: $date")
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

    suspend fun resetTodayData(date: String): Result<Unit> {
        val userId = getCurrentUserId() ?: return Result.failure(Exception("User not logged in"))
        Log.d(TAG, "오늘 데이터 초기화 실행 - userId: $userId, date: $date")
        return try {
            // Room 데이터 초기화
            resetDailyActivityLocal(userId, date)
            // Firestore 데이터 초기화
            resetTodayFirestoreActivity(userId, date)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "오늘 데이터 초기화 실패 - userId: $userId, date: $date", e)
            Result.failure(e)
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

    fun getDailyActivityFlow(userId: String, date: String): Flow<DailyActivity?> {
        return dailyActivityDao.getActivitiesFlow(userId, 30)
            .map { list -> list.find { it.date == date }?.toDailyActivity() }
    }

    // --- Rankings (개선된 구조) --- //

    suspend fun getRankingLeaderboard(period: String, periodKey: String): RankingLeaderboard? {
        val docId = "${period}_${periodKey}"
        Log.d(TAG, "getRankingLeaderboard: docId=$docId")
        return try {
            val document = rankingsCollection.document(docId).get().await()
            if (document.exists()) {
                document.toObject(RankingLeaderboard::class.java)
            } else {
                Log.w(TAG, "Ranking document not found: $docId")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getRankingLeaderboard failed for $docId", e)
            null
        }
    }

    // --- Extension Functions --- //

    private fun LocalDailyActivityEntity.toDailyActivity(): DailyActivity {
        val routes: List<RoutePoint> = try {
            gson.fromJson(this.routes, object : TypeToken<List<RoutePoint>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
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
