package com.walktracker.app.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walktracker.app.model.DailyActivity
import com.walktracker.app.viewmodel.MainUiState
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MainScreen(
    uiState: MainUiState,
    onRefresh: () -> Unit,
    onNavigateToDetails: () -> Unit,
    onResetTodayActivity: () -> Unit // 오늘 데이터 초기화 콜백 추가
) {
    val scrollState = rememberScrollState()
    var showResetDialog by remember { mutableStateOf(false) }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("기록 초기화") },
            text = { Text("정말 오늘의 걸음 기록을 초기화 하시겠습니까? 이 작업은 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onResetTodayActivity()
                        showResetDialog = false
                    }
                ) {
                    Text("확인")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("취소")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        // 상단 인사말
        GreetingSection(userName = uiState.user?.displayName ?: "사용자")

        Spacer(modifier = Modifier.height(24.dp))

        // 메인 걸음수 카드
        StepsCard(
            steps = uiState.todayActivity?.steps ?: 0L,
            goal = 10000L,
            onResetClick = { showResetDialog = true } // 리셋 버튼 클릭 시 다이얼로그 표시
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 통계 카드들
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.DirectionsWalk,
                label = "이동 거리",
                value = String.format("%.2f", uiState.todayActivity?.distance ?: 0.0),
                unit = "km",
                color = MaterialTheme.colorScheme.tertiary
            )

            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.LocalFireDepartment,
                label = "소모 칼로리",
                value = (uiState.todayActivity?.calories ?: 0.0).roundToInt().toString(),
                unit = "kcal",
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(12.dp)) // 카드 사이 간격 추가

        // 고도 및 속도 정보 카드
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Terrain, // 고도 아이콘
                label = "상승 고도",
                value = String.format("%.1f", uiState.todayActivity?.altitude ?: 0.0),
                unit = "m",
                color = Color(0xFF3F51B5) // Indigo 색상
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Speed, // 속도 아이콘
                label = "현재 속도",
                value = String.format("%.1f", uiState.currentSpeed * 3.6),
                unit = "km/h",
                color = Color(0xFF009688) // Teal 색상
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 주간 요약
        WeeklySummaryCard(
            weeklyData = uiState.weeklyActivity,
            onDetailsClick = onNavigateToDetails
        )
    }
}

@Composable
private fun GreetingSection(userName: String) {
    Column {
        Text(
            text = "안녕하세요,",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = "${userName}님!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StepsCard(
    steps: Long,
    goal: Long,
    onResetClick: () -> Unit // 리셋 콜백 추가
) {
    val progress = (steps.toFloat() / goal.toFloat()).coerceIn(0f, 1f)

    // 애니메이션
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 1000, easing = EaseOutCubic),
        label = "progress"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // 리셋 버튼
            IconButton(
                onClick = onResetClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "오늘 기록 초기화",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center // 컨텐츠 중앙 정렬
            ) {
                // 원형 프로그레스
                CircularProgressIndicator(
                    progress = animatedProgress,
                    steps = steps,
                    goal = goal
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "오늘의 걸음",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun CircularProgressIndicator(
    progress: Float,
    steps: Long,
    goal: Long
) {
    Box(
        modifier = Modifier.size(180.dp),
        contentAlignment = Alignment.Center
    ) {
        // 배경 원
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                color = Color.Gray.copy(alpha = 0.1f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 20.dp.toPx(), cap = StrokeCap.Round),
                size = Size(size.width, size.height)
            )
        }

        // 진행 원
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color(0xFF4CAF50),
                        Color(0xFF8BC34A),
                        Color(0xFF4CAF50)
                    )
                ),
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = 20.dp.toPx(), cap = StrokeCap.Round),
                size = Size(size.width, size.height)
            )
        }

        // 중앙 텍스트
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = steps.toString(),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "/ $goal",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    unit: String,
    color: Color
) {
    Card(
        modifier = modifier.height(140.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(32.dp)
            )

            Column {
                Text(
                    text = value,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = unit,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun WeeklySummaryCard(
    weeklyData: List<DailyActivity>,
    onDetailsClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "이번 주 활동",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                TextButton(onClick = onDetailsClick) {
                    Text("상세보기")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bar chart
            WeeklyBarChart(data = weeklyData)
        }
    }
}

@Composable
fun WeeklyBarChart(data: List<DailyActivity>) {
    val maxValue = data.maxOfOrNull { it.steps }?.toFloat()?.takeIf { it > 0f } ?: 1f
    val dayFormat = remember { SimpleDateFormat("EEE", Locale.KOREAN) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp), // 차트의 전체 높이를 180dp로 설정
        horizontalArrangement = Arrangement.SpaceBetween,
        // Row의 기본 정렬을 사용하고 Column 내부에서 정렬을 제어합니다.
        verticalAlignment = Alignment.Top // Row의 정렬 기준을 상단으로 변경
    ) {
        data.forEach { activity ->
            // 각 막대와 텍스트를 담는 Column
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f) // 각 Column이 동일한 너비를 차지하도록 설정
                    .fillMaxHeight() // Column이 Row의 전체 높이를 채우도록 설정
            ) {
                // 1. 막대를 그리는 공간 (가변 높이)
                // Spacer에 weight(1f)를 주어 남은 공간을 모두 차지하게 하고,
                // 그 안에서 Box를 하단 정렬하여 막대가 아래에서부터 자라나도록 합니다.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(), // 너비 채우기
                    contentAlignment = Alignment.BottomCenter // Box 내부의 content를 하단 중앙에 배치
                ) {
                    val barHeightRatio = (activity.steps.toFloat() / maxValue).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .width(20.dp)
                            .fillMaxHeight(barHeightRatio) // 상위 Box 높이의 비율만큼 막대 높이 설정
                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }

                // 2. 텍스트를 위한 공간 (고정 높이)
                Spacer(modifier = Modifier.height(8.dp)) // 막대와 텍스트 사이의 고정 간격

                // 요일 텍스트
                Text(
                    text = dayFormat.format(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(activity.date)!!),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}
