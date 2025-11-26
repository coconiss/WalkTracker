package com.walktracker.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.walktracker.app.model.User
import com.walktracker.app.repository.FirebaseRepository
import com.walktracker.app.ui.theme.WalkTrackerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class LoginActivity : ComponentActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var repository: FirebaseRepository // lazy 대신 lateinit 사용

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = FirebaseAuth.getInstance()
        repository = FirebaseRepository(applicationContext) // Context 전달

        // 이미 로그인되어 있으면 메인으로
        if (auth.currentUser != null) {
            startMainActivity()
            return
        }

        setContent {
            WalkTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LoginScreen(
                        onLoginSuccess = { startMainActivity() },
                        onLogin = ::login,
                        onSignUp = ::signUp
                    )
                }
            }
        }
    }

    private fun login(email: String, password: String, onResult: (Result<Unit>) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    auth.signInWithEmailAndPassword(email, password).await()
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            onResult(result)
        }
    }

    private fun signUp(email: String, password: String, name: String, onResult: (Result<Unit>) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val authResult = auth.createUserWithEmailAndPassword(email, password).await()
                    val userId = authResult.user?.uid ?: throw Exception("사용자 ID를 가져올 수 없습니다")

                    // Firestore에 사용자 정보 저장
                    val user = User(
                        userId = userId,
                        email = email,
                        displayName = name,
                        weight = 70.0,
                        createdAt = Timestamp.now()
                    )

                    repository.saveUser(user).getOrThrow()
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            onResult(result)
        }
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onLogin: (String, String, (Result<Unit>) -> Unit) -> Unit,
    onSignUp: (String, String, String, (Result<Unit>) -> Unit) -> Unit
) {
    var isLoginMode by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.primaryContainer
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 로고 영역
            Icon(
                imageVector = Icons.Default.DirectionsWalk,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Walkcord",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
            )

            Text(
                text = "건강한 걷기 습관을 만들어보세요",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(48.dp))

            // 입력 카드
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = if (isLoginMode) "로그인" else "회원가입",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // 이름 입력 (회원가입 시에만)
                    if (!isLoginMode) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("이름") },
                            leadingIcon = {
                                Icon(Icons.Default.Person, contentDescription = null)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Next
                            )
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // 이메일 입력
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("이메일") },
                        leadingIcon = {
                            Icon(Icons.Default.Email, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 비밀번호 입력
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("비밀번호") },
                        leadingIcon = {
                            Icon(Icons.Default.Lock, contentDescription = null)
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible)
                                        Icons.Default.Visibility
                                    else
                                        Icons.Default.VisibilityOff,
                                    contentDescription = if (passwordVisible) "비밀번호 숨기기" else "비밀번호 보기"
                                )
                            }
                        },
                        visualTransformation = if (passwordVisible)
                            VisualTransformation.None
                        else
                            PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        )
                    )

                    // 에러 메시지
                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // 로그인/회원가입 버튼
                    Button(
                        onClick = {
                            isLoading = true
                            errorMessage = null

                            if (isLoginMode) {
                                onLogin(email, password) { result ->
                                    result.fold(
                                        onSuccess = {
                                            onLoginSuccess()
                                        },
                                        onFailure = { e ->
                                            errorMessage = when {
                                                e.message?.contains("password") == true ->
                                                    "비밀번호가 올바르지 않습니다"
                                                e.message?.contains("email") == true ->
                                                    "이메일 형식이 올바르지 않습니다"
                                                e.message?.contains("user-not-found") == true ->
                                                    "등록되지 않은 사용자입니다"
                                                else -> "오류가 발생했습니다: ${e.message}"
                                            }
                                            isLoading = false
                                        }
                                    )
                                }
                            } else {
                                if (name.isBlank()) {
                                    errorMessage = "이름을 입력해주세요"
                                    isLoading = false
                                    return@Button
                                }

                                onSignUp(email, password, name) { result ->
                                    result.fold(
                                        onSuccess = {
                                            onLoginSuccess()
                                        },
                                        onFailure = { e ->
                                            errorMessage = when {
                                                e.message?.contains("email-already-in-use") == true ->
                                                    "이미 사용 중인 이메일입니다"
                                                e.message?.contains("weak-password") == true ->
                                                    "비밀번호는 최소 6자 이상이어야 합니다"
                                                e.message?.contains("email") == true ->
                                                    "이메일 형식이 올바르지 않습니다"
                                                else -> "오류가 발생했습니다: ${e.message}"
                                            }
                                            isLoading = false
                                        }
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(
                                text = if (isLoginMode) "로그인" else "회원가입",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 전환 버튼
                    TextButton(
                        onClick = {
                            isLoginMode = !isLoginMode
                            errorMessage = null
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            text = if (isLoginMode)
                                "계정이 없으신가요? 회원가입"
                            else
                                "이미 계정이 있으신가요? 로그인"
                        )
                    }
                }
            }
        }
    }
}