package com.walktracker.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.walktracker.app.model.User

@Composable
fun SettingsScreen(
    user: User?,
    onWeightUpdate: (Double) -> Unit,
    onSignOut: () -> Unit,
    notificationEnabled: Boolean,
    onNotificationChange: (Boolean) -> Unit
) {
    var showWeightDialog by remember { mutableStateOf(false) }
    var showPrivacyPolicyDialog by remember { mutableStateOf(false) }
    var showAppInfoDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 프로필 섹션
        ProfileSection(user = user)

        Spacer(modifier = Modifier.height(24.dp))

        // 개인 설정
        Text(
            text = "개인 설정",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )

        SettingItem(
            icon = Icons.Default.MonitorWeight,
            title = "체중 설정",
            subtitle = "${user?.weight ?: 70.0} kg",
            onClick = { showWeightDialog = true }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 앱 설정
        Text(
            text = "앱 설정",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )

        // 알림 설정
        SwitchSettingItem(
            icon = Icons.Default.Notifications,
            title = "목표 달성 알림",
            subtitle = "일일 걸음 목표 달성 시 알림 받기",
            checked = notificationEnabled,
            onCheckedChange = onNotificationChange
        )

        // 개인정보 보호
        SettingItem(
            icon = Icons.Default.Security,
            title = "개인정보 보호",
            subtitle = "데이터 관리 및 권한",
            onClick = { showPrivacyPolicyDialog = true }
        )

        // 앱 정보
        SettingItem(
            icon = Icons.Default.Info,
            title = "앱 정보",
            subtitle = "버전 1.0.0",
            onClick = { showAppInfoDialog = true }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 로그아웃
        OutlinedButton(
            onClick = onSignOut,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(imageVector = Icons.Default.Logout, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("로그아웃")
        }
    }

    // 다이얼로그
    if (showWeightDialog) {
        WeightInputDialog(
            currentWeight = user?.weight ?: 70.0,
            onDismiss = { showWeightDialog = false },
            onConfirm = {
                onWeightUpdate(it)
                showWeightDialog = false
            }
        )
    }

    if (showPrivacyPolicyDialog) {
        PrivacyPolicyDialog(onDismiss = { showPrivacyPolicyDialog = false })
    }

    if (showAppInfoDialog) {
        AppInfoDialog(onDismiss = { showAppInfoDialog = false })
    }
}

@Composable
private fun ProfileSection(user: User?) { // ... (이전과 동일)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 프로필 아이콘
            Surface(
                modifier = Modifier.size(64.dp),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 사용자 정보
            Column {
                Text(
                    text = user?.displayName ?: "사용자",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = user?.email ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingItem( // ... (이전과 동일)
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwitchSettingItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        onClick = { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun WeightInputDialog( // ... (이전과 동일)
    currentWeight: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var weightText by remember { mutableStateOf(currentWeight.toString()) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("체중 설정") },
        text = {
            Column {
                Text("정확한 칼로리 계산을 위해 체중을 입력해주세요.")
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = weightText,
                    onValueChange = {
                        weightText = it
                        isError = false
                    },
                    label = { Text("체중 (kg)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = isError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val weight = weightText.toDoubleOrNull()
                    if (weight != null && weight > 0 && weight < 500) {
                        onConfirm(weight)
                    } else {
                        isError = true
                    }
                }
            ) {
                Text("확인")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}

@Composable
private fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("개인정보 처리방침") },
        text = {
            Text(
                "WalkTracker는 사용자의 위치 정보와 활동 데이터를 수집하여 걸음수, 이동 거리, 소모 칼로리를 계산하고, 이동 경로를 기록합니다. 이 정보는 개인 맞춤형 서비스 제공 및 통계 분석 목적으로만 사용되며, 사용자의 명시적인 동의 없이는 제3자에게 제공되지 않습니다. 수집된 데이터는 Firebase 서버에 안전하게 저장됩니다."
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("확인") }
        }
    )
}

@Composable
private fun AppInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("앱 정보") },
        text = {
            Column {
                Text("WalkTracker v1.0.0")
                Spacer(modifier = Modifier.height(8.dp))
                Text("개발자: sdlee")
                Spacer(modifier = Modifier.height(8.dp))
                Text("문의: lsd9901@google.com")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("확인") }
        }
    )
}
