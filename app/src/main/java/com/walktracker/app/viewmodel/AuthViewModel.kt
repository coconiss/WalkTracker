package com.walktracker.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.walktracker.app.repository.FirebaseRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FirebaseRepository(application.applicationContext)

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState: StateFlow<LoginState> = _loginState

    private val _signUpState = MutableStateFlow<SignUpState>(SignUpState.Idle)
    val signUpState: StateFlow<SignUpState> = _signUpState

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _loginState.value = LoginState.Loading
            val result = repository.loginWithEmailPassword(email, password)
            _loginState.value = if (result.isSuccess) {
                LoginState.Success
            } else {
                LoginState.Error(getKoreanErrorMessage(result.exceptionOrNull()))
            }
        }
    }

    fun signUp(email: String, password: String, weight: Double) {
        viewModelScope.launch {
            _signUpState.value = SignUpState.Loading
            val result = repository.signUpWithEmailPassword(email, password, weight)
            _signUpState.value = if (result.isSuccess) {
                SignUpState.Success
            } else {
                SignUpState.Error(getKoreanErrorMessage(result.exceptionOrNull()))
            }
        }
    }

    private fun getKoreanErrorMessage(exception: Throwable?): String {
        val message = exception?.message ?: return "알 수 없는 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
        return when {
            message.contains("There is no user record corresponding to this identifier") -> "가입되지 않은 이메일입니다."
            message.contains("INVALID_LOGIN_CREDENTIALS") -> "이메일 또는 비밀번호가 일치하지 않습니다."
            message.contains("The email address is badly formatted") -> "올바르지 않은 이메일 형식입니다."
            message.contains("The email address is already in use by another account") -> "이미 사용 중인 이메일입니다."
            message.contains("Password should be at least 6 characters") -> "비밀번호는 6자리 이상이어야 합니다."
            else -> "알 수 없는 오류가 발생했습니다. 잠시 후 다시 시도해주세요."
        }
    }

    fun resetStates() {
        _loginState.value = LoginState.Idle
        _signUpState.value = SignUpState.Idle
    }
}

sealed class LoginState {
    object Idle : LoginState()
    object Loading : LoginState()
    object Success : LoginState()
    data class Error(val message: String) : LoginState()
}

sealed class SignUpState {
    object Idle : SignUpState()
    object Loading : SignUpState()
    object Success : SignUpState()
    data class Error(val message: String) : SignUpState()
}