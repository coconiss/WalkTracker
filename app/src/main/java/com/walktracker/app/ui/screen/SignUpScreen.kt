
package com.walktracker.app.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.walktracker.app.viewmodel.AuthViewModel
import com.walktracker.app.viewmodel.SignUpState

@Composable
fun SignUpScreen(navController: NavController, onSignUpSuccess: () -> Unit) {
    val authViewModel: AuthViewModel = viewModel()
    val signUpState by authViewModel.signUpState.collectAsState()
    val context = LocalContext.current

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(signUpState) {
        when (val state = signUpState) {
            is SignUpState.Success -> {
                Toast.makeText(context, "회원가입에 성공했습니다!", Toast.LENGTH_SHORT).show()
                onSignUpSuccess()
                authViewModel.resetStates()
            }
            is SignUpState.Error -> {
                error = state.message
            }
            else -> { /* Idle or Loading */ }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "회원가입", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it; error = null },
            label = { Text("이메일") },
            modifier = Modifier.fillMaxWidth(),
            isError = error != null
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("비밀번호 (6자리 이상)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            isError = error != null
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it; error = null },
            label = { Text("비밀번호 확인") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            isError = error != null
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = weight,
            onValueChange = { weight = it; error = null },
            label = { Text("몸무게 (kg)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            isError = error != null
        )
        Spacer(modifier = Modifier.height(16.dp))

        error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (signUpState is SignUpState.Loading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    // Validation
                    if (password != confirmPassword) {
                        error = "비밀번호가 일치하지 않습니다."
                        return@Button
                    }
                    if (password.length < 6) {
                        error = "비밀번호는 6자리 이상이어야 합니다."
                        return@Button
                    }
                    val weightValue = weight.toDoubleOrNull()
                    if (weightValue == null || weightValue <= 0) {
                        error = "올바른 몸무게를 입력해주세요."
                        return@Button
                    }
                    authViewModel.signUp(email, password, weightValue)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = email.isNotBlank() && password.isNotBlank() && confirmPassword.isNotBlank() && weight.isNotBlank()
            ) {
                Text("회원가입")
            }
        }
    }
}
