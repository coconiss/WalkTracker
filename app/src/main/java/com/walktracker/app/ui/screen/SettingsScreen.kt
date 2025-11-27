package com.walktracker.app.ui.screen

import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.walktracker.app.model.User
import com.walktracker.app.service.LocationTrackingService
import com.walktracker.app.util.SharedPreferencesManager
import com.walktracker.app.viewmodel.MainUiState

@Composable
fun SettingsScreen(
    uiState: MainUiState,
    onClearError: () -> Unit,
    onWeightUpdate: (Double) -> Unit,
    onDisplayNameUpdate: (String) -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onNotificationChange: (Boolean) -> Unit,
    onForceQuit: () -> Unit // 강제 종료 콜백 추가
) {
    val context = LocalContext.current
    val prefs = remember { SharedPreferencesManager(context) }
    val user = uiState.user

    var showWeightDialog by remember { mutableStateOf(false) }
    var showDisplayNameDialog by remember { mutableStateOf(false) }
    var showStrideDialog by remember { mutableStateOf(false) } // 보폭 다이얼로그
    var showPrivacyPolicyDialog by remember { mutableStateOf(false) }
    var showAppInfoDialog by remember { mutableStateOf(false) }
    var showForceQuitDialog by remember { mutableStateOf(false) } // 강제 종료 확인 다이얼로그 상태
    var showDeleteAccountDialog by remember { mutableStateOf(false) }

    // 센서 설정 상태
    var gpsEnabled by remember { mutableStateOf(prefs.isGpsEnabled()) }
    var stepSensorEnabled by remember { mutableStateOf(prefs.isStepSensorEnabled()) }
    var pressureSensorEnabled by remember { mutableStateOf(prefs.isPressureSensorEnabled()) }

    // ViewModel의 에러 메시지를 감지하여 Toast로 표시
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            onClearError() // Toast를 표시한 후 에러 상태를 초기화
        }
    }

    fun sendSensorSettingsChangedBroadcast() {
        val intent = Intent(LocationTrackingService.ACTION_SENSOR_SETTINGS_CHANGED)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

    fun sendUserDataChangedBroadcast() {
        val intent = Intent(LocationTrackingService.ACTION_USER_DATA_CHANGED)
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
    }

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
            icon = Icons.Default.AccountCircle,
            title = "닉네임 변경",
            subtitle = user?.displayName ?: "",
            onClick = { showDisplayNameDialog = true }
        )

        SettingItem(
            icon = Icons.Default.MonitorWeight,
            title = "체중 설정",
            subtitle = "${user?.weight ?: 70.0} kg",
            onClick = { showWeightDialog = true }
        )

        SettingItem(
            icon = Icons.Default.SquareFoot,
            title = "보폭 설정",
            subtitle = "${String.format("%.1f", prefs.getUserStride())} m",
            onClick = { showStrideDialog = true }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 센서 설정
        Text(
            text = "센서 설정",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )

        SwitchSettingItem(
            icon = Icons.Default.LocationOn,
            title = "GPS",
            subtitle = "위치 기반 거리, 속도, 경로 추적",
            checked = gpsEnabled,
            onCheckedChange = {
                if (!it && !stepSensorEnabled) { // 둘 다 끄려고 할 때
                    // TODO: 사용자에게 알림 (Toast 등)
                } else {
                    gpsEnabled = it
                    prefs.setGpsEnabled(it)
                    sendSensorSettingsChangedBroadcast()
                }
            }
        )

        SwitchSettingItem(
            icon = Icons.Default.DirectionsWalk,
            title = "걸음 센서",
            subtitle = "걸음 수 및 활동 감지",
            checked = stepSensorEnabled,
            onCheckedChange = {
                if (!it && !gpsEnabled) { // 둘 다 끄려고 할 때
                    // TODO: 사용자에게 알림 (Toast 등)
                } else {
                    stepSensorEnabled = it
                    prefs.setStepSensorEnabled(it)
                    sendSensorSettingsChangedBroadcast()
                }
            }
        )
        Text(
            text = "GPS와 걸음 센서 둘 중 하나는 반드시 켜져 있어야 합니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
        )

        SwitchSettingItem(
            icon = Icons.Default.Compress,
            title = "기압 센서",
            subtitle = "고도 변화량 측정",
            checked = pressureSensorEnabled,
            onCheckedChange = {
                pressureSensorEnabled = it
                prefs.setPressureSensorEnabled(it)
                sendSensorSettingsChangedBroadcast()
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 앱 설정
        Text(
            text = "앱 설정",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        )

        // 알림 설정 (주석 처리)
//        SwitchSettingItem(
//            icon = Icons.Default.Notifications,
//            title = "목표 달성 알림",
//            subtitle = "일일 걸음 목표 달성 시 알림 받기",
//            checked = notificationEnabled,
//            onCheckedChange = onNotificationChange
//        )

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

        // 강제 종료 버튼
        Button(
            onClick = { showForceQuitDialog = true }, // 다이얼로그 표시
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Icon(imageVector = Icons.Default.PowerSettingsNew, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("백그라운드 강제 종료")
        }

        Spacer(modifier = Modifier.height(8.dp))

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

        Spacer(modifier = Modifier.height(8.dp))

        // 회원탈퇴
        OutlinedButton(
            onClick = { showDeleteAccountDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(imageVector = Icons.Default.PersonRemove, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("회원탈퇴")
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

    if (showDisplayNameDialog) {
        DisplayNameInputDialog(
            currentDisplayName = user?.displayName ?: "",
            onDismiss = { showDisplayNameDialog = false },
            onConfirm = {
                onDisplayNameUpdate(it)
                showDisplayNameDialog = false
            }
        )
    }

    if (showStrideDialog) {
        StrideInputDialog(
            currentStride = prefs.getUserStride(),
            onDismiss = { showStrideDialog = false },
            onConfirm = {
                prefs.setUserStride(it)
                sendUserDataChangedBroadcast()
                showStrideDialog = false
            }
        )
    }

    if (showPrivacyPolicyDialog) {
        PrivacyPolicyDialog(onDismiss = { showPrivacyPolicyDialog = false })
    }

    if (showAppInfoDialog) {
        AppInfoDialog(onDismiss = { showAppInfoDialog = false })
    }

    // 강제 종료 확인 다이얼로그
    if (showForceQuitDialog) {
        AlertDialog(
            onDismissRequest = { showForceQuitDialog = false },
            title = { Text("백그라운드 서비스 종료") },
            text = { Text("백그라운드에서 실행 중인 위치 및 활동 추적 서비스를 중지하고 앱을 완전히 종료하시겠습니까? 저장되지 않은 데이터가 유실될 수 있습니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onForceQuit()
                        showForceQuitDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("종료")
                }
            },
            dismissButton = {
                TextButton(onClick = { showForceQuitDialog = false }) {
                    Text("취소")
                }
            }
        )
    }

    // 회원 탈퇴 확인 다이얼로그
    if (showDeleteAccountDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAccountDialog = false },
            title = { Text("회원 탈퇴") },
            text = { Text("정말 탈퇴하시겠습니까? 탈퇴를 하면 모든 데이터가 지워지고 복구가 불가능합니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteAccount()
                        showDeleteAccountDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("탈퇴")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAccountDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
private fun DisplayNameInputDialog(
    currentDisplayName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var displayNameText by remember { mutableStateOf(currentDisplayName) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("닉네임 변경") },
        text = {
            Column {
                Text("다른 사용자가 나를 식별하는 이름입니다.")
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = displayNameText,
                    onValueChange = {
                        displayNameText = it
                        isError = false
                    },
                    label = { Text("닉네임") },
                    isError = isError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (displayNameText.isNotBlank()) {
                        onConfirm(displayNameText)
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
private fun StrideInputDialog(
    currentStride: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var strideText by remember { mutableStateOf(String.format("%.1f", currentStride)) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("보폭 설정") },
        text = {
            Column {
                Text("걸음 센서가 비활성화되었을 때 GPS 기반으로 걸음 수를 추정하는 데 사용됩니다.")
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = strideText,
                    onValueChange = {
                        strideText = it
                        isError = false
                    },
                    label = { Text("보폭 (m)") },
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
                    val stride = strideText.toDoubleOrNull()
                    if (stride != null && stride > 0.3 && stride < 1.5) { // 일반적인 보폭 범위
                        onConfirm(stride)
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
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = """
**WalkCord 개인정보 처리방침**

최종 수정일: 2025-11-24

WalkCord는 사용자의 개인정보를 소중히 다루며, 정보통신망 이용촉진 및 정보보호 등에 관한 법률 등 관련 법령을 준수합니다.

**1. 수집하는 개인정보 항목 및 수집 목적**

가. 수집 항목
- 이메일, 비밀번호, 체중, 보폭 
- 서비스 이용 중 자동 생성: 실시간 위치 정보(GPS), 신체 활동 데이터(가속도계, 자이로스코프), 기압 센서 데이터, 서비스 이용 기록, 쿠키, 광고 ID

나. 수집 목적
- 회원 식별 및 계정 관리
- 걸음 수, 이동 거리, 소모 칼로리, 이동 경로 등 핵심 기능 제공
- 개인 맞춤형 통계 및 서비스 제공
- 부정 이용 방지 및 비인가 사용 방지
- 고객 문의 응대 및 분쟁 해결
- 신규 서비스 개발 및 마케팅/광고에 활용

**2. 개인정보의 보유 및 이용기간**

수집된 개인정보는 **회원 탈퇴 시까지** 보유 및 이용됩니다. 회원 탈퇴 요청 시, 모든 개인정보는 관련 법령에 따른 보존 의무가 없는 한 지체 없이 파기되며 복구할 수 없습니다.

**3. 데이터 저장 및 국외 이전**

수집된 모든 데이터는 안전한 클라우드 인프라(Firebase)에 암호화되어 저장됩니다.

- **이전받는 자**: Google (Firebase)
- **이전되는 국가**: 미국
- **이전 항목**: 1항의 수집항목 전체
- **이전 목적**: 데이터의 안전한 저장 및 관리, 서비스 운영
- **보유 및 이용 기간**: 회원 탈퇴 시까지

**4. 제3자 제공에 관한 사항**

WalkCord는 사용자의 명시적인 동의 없이는 개인정보를 제3자에게 제공하지 않습니다. 단, 법령의 규정에 의거하거나, 수사 목적으로 법령에 정해진 절차와 방법에 따라 수사기관의 요구가 있는 경우는 예외로 합니다.

**5. 정보주체의 권리**

사용자는 언제든지 앱이 수집한 자신의 개인정보를 조회하거나 수정할 수 있으며, 회원 탈퇴를 통해 정보 수집 및 이용 동의를 철회할 수 있습니다.


**6. 개인정보 보호 문의처**

- 이메일: lsd9901@google.com
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodySmall
                )
            }
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
