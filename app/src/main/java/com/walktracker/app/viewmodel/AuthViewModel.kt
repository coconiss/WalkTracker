
package com.walktracker.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.walktracker.app.repository.FirebaseRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AuthViewModel : ViewModel() {

    private val repository = FirebaseRepository()

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
                LoginState.Error(result.exceptionOrNull()?.message ?: "알 수 없는 오류가 발생했습니다.")
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
                SignUpState.Error(result.exceptionOrNull()?.message ?: "알 수 없는 오류가 발생했습니다.")
            }
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
