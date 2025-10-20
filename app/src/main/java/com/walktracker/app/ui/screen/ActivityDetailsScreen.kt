package com.walktracker.app.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.walktracker.app.viewmodel.ActivityDetailsViewModel
import com.walktracker.app.viewmodel.ChartType
import com.walktracker.app.viewmodel.toFormattedDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Dummy data class
data class ActivityDetailData(
    val date: Date, // 타입을 Date로 변경
    val steps: Long,
    val distance: Double,
    val calories: Double
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailsScreen(
    onNavigateBack: () -> Unit,
    viewModel: ActivityDetailsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("상세 활동 내역") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로가기")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            DateRangePicker(
                startDate = uiState.startDate,
                endDate = uiState.endDate,
                onDateRangeSelected = viewModel::onDateRangeSelected,
                onSearchClick = viewModel::loadActivityData
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Text("기간별 통계", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        ActivityGrid(data = uiState.activityData)

                        Spacer(modifier = Modifier.height(24.dp))

                        ChartTypeToggle( // 차트 유형 선택기
                            selectedType = uiState.chartType,
                            onTypeSelected = viewModel::onChartTypeSelected
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    item {
                        ChartSection(
                            title = "걸음 수",
                            data = uiState.activityData,
                            chartType = uiState.chartType,
                            valueSelector = { it.steps.toDouble() },
                            primaryColor = MaterialTheme.colorScheme.primary
                        )
                    }

                    item {
                        ChartSection(
                            title = "칼로리 (kcal)",
                            data = uiState.activityData,
                            chartType = uiState.chartType,
                            valueSelector = { it.calories },
                            primaryColor = Color(0xFFFFA726) // Orange
                        )
                    }

                    item {
                        ChartSection(
                            title = "거리 (km)",
                            data = uiState.activityData,
                            chartType = uiState.chartType,
                            valueSelector = { it.distance },
                            primaryColor = Color(0xFF42A5F5) // Blue
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChartTypeToggle(
    selectedType: ChartType,
    onTypeSelected: (ChartType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        ToggleButton(
            text = "막대 그래프",
            isSelected = selectedType == ChartType.BAR,
            onClick = { onTypeSelected(ChartType.BAR) },
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(4.dp))
        ToggleButton(
            text = "선 그래프",
            isSelected = selectedType == ChartType.LINE,
            onClick = { onTypeSelected(ChartType.LINE) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun ToggleButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        ),
        elevation = if (isSelected) ButtonDefaults.buttonElevation(2.dp) else null
    ) {
        Text(text, fontSize = 14.sp)
    }
}

@Composable
fun ChartSection(
    title: String,
    data: List<ActivityDetailData>,
    chartType: ChartType,
    valueSelector: (ActivityDetailData) -> Double,
    primaryColor: Color
) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(16.dp))
    when (chartType) {
        ChartType.BAR -> DetailedBarChart(data = data, valueSelector = valueSelector, primaryColor = primaryColor)
        ChartType.LINE -> DetailedLineChart(data = data, valueSelector = valueSelector, primaryColor = primaryColor)
    }
    Spacer(modifier = Modifier.height(24.dp))
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangePicker(
    startDate: Long,
    endDate: Long,
    onDateRangeSelected: (Long, Long) -> Unit,
    onSearchClick: () -> Unit
) {
    val showStartDatePicker = remember { mutableStateOf(false) }
    val showEndDatePicker = remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        OutlinedButton(onClick = { showStartDatePicker.value = true }) {
            Icon(Icons.Default.DateRange, contentDescription = "Start Date", modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(startDate.toFormattedDate())
        }
        Text("~")
        OutlinedButton(onClick = { showEndDatePicker.value = true }) {
            Icon(Icons.Default.DateRange, contentDescription = "End Date", modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(endDate.toFormattedDate())
        }
        Button(onClick = onSearchClick) {
            Text("조회")
        }
    }

    if (showStartDatePicker.value) {
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker.value = false },
            onDateSelected = { date ->
                onDateRangeSelected(date, endDate)
                showStartDatePicker.value = false
            },
            initialDate = startDate
        )
    }

    if (showEndDatePicker.value) {
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker.value = false },
            onDateSelected = { date ->
                onDateRangeSelected(startDate, date)
                showEndDatePicker.value = false
            },
            initialDate = endDate
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialog(
    onDismissRequest: () -> Unit,
    onDateSelected: (Long) -> Unit,
    initialDate: Long
) {
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialDate)
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            Button(onClick = {
                datePickerState.selectedDateMillis?.let { onDateSelected(it) }
            }) {
                Text("확인")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("취소")
            }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}


@Composable
fun ActivityGrid(data: List<ActivityDetailData>) {
    Card(elevation = CardDefaults.cardElevation(2.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // Header
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GridHeader(modifier = Modifier.weight(1.5f), text = "날짜")
                GridHeader(modifier = Modifier.weight(1f), text = "걸음")
                GridHeader(modifier = Modifier.weight(1f), text = "거리(km)")
                GridHeader(modifier = Modifier.weight(1f), text = "칼로리")
            }
            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Data Rows
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                items(data) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GridItem(modifier = Modifier.weight(1.5f), text = item.date.toFormattedDateString())
                        GridItem(modifier = Modifier.weight(1f), text = item.steps.toString())
                        GridItem(modifier = Modifier.weight(1f), text = String.format(Locale.US, "%.2f", item.distance))
                        GridItem(modifier = Modifier.weight(1f), text = item.calories.toInt().toString())
                    }
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                }
            }
        }
    }
}

@Composable
fun GridHeader(modifier: Modifier = Modifier, text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
        textAlign = TextAlign.Center
    )
}

@Composable
fun GridItem(modifier: Modifier = Modifier, text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
        textAlign = TextAlign.Center
    )
}

@Composable
fun DetailedBarChart(
    data: List<ActivityDetailData>,
    valueSelector: (ActivityDetailData) -> Double,
    primaryColor: Color
) {
    if (data.isEmpty()) return    // maxValue가 0이 되는 것을 방지하여 0으로 나누는 오류를 막습니다.
    val maxValue = (data.maxOfOrNull(valueSelector)?.toFloat() ?: 0f).coerceAtLeast(1f)

    Card(modifier = Modifier.fillMaxWidth().height(220.dp), elevation = CardDefaults.cardElevation(2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            data.reversed().forEach { activity ->
                val value = valueSelector(activity)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier.weight(1f)
                ) {
                    val textValue = if (valueSelector(activity) == activity.steps.toDouble()) {
                        activity.steps.toString()
                    } else {
                        String.format(Locale.US, "%.1f", value)
                    }
                    Text(text = textValue, fontSize = 10.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            // Float 값을 Dp로 변환하도록 수정
                            .height((120 * (value.toFloat() / maxValue)).dp)
                            .width(25.dp)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(primaryColor)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = activity.date.toFormattedDateString().substring(5),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
fun DetailedLineChart(
    data: List<ActivityDetailData>,
    valueSelector: (ActivityDetailData) -> Double,
    primaryColor: Color
) {
    if (data.isEmpty()) return
    val maxValue = (data.maxOfOrNull(valueSelector)?.toFloat() ?: 0f).coerceAtLeast(1f)
    val reversedData = data.reversed()

    Card(modifier = Modifier.fillMaxWidth().height(220.dp), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                val spacing = size.width / reversedData.size
                val stepY = size.height / maxValue

                val textPaint = android.graphics.Paint().apply {
                    color = primaryColor.toArgb()
                    textSize = 30f
                    textAlign = android.graphics.Paint.Align.CENTER
                }

                reversedData.forEachIndexed { i, activity ->
                    val x = spacing / 2 + i * spacing
                    val y = size.height - (valueSelector(activity).toFloat() * stepY)
                    val value = valueSelector(activity)
                    val textValue = if (value == activity.steps.toDouble()) {
                        activity.steps.toString()
                    } else {
                        String.format(Locale.US, "%.1f", value)
                    }
                    drawContext.canvas.nativeCanvas.drawText(textValue, x, y - 10, textPaint)
                }

                for (i in 0 until reversedData.size - 1) {
                    val startX = spacing / 2 + i * spacing
                    val startY = size.height - (valueSelector(reversedData[i]).toFloat() * stepY)
                    val endX = spacing / 2 + (i + 1) * spacing
                    val endY = size.height - (valueSelector(reversedData[i + 1]).toFloat() * stepY)
                    drawLine(primaryColor, Offset(startX, startY), Offset(endX, endY), strokeWidth = 5f, cap = StrokeCap.Round)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                reversedData.forEach {
                    Text(
                        text = it.date.toFormattedDateString().substring(5),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}


fun Date.toFormattedDateString(): String {
    val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return format.format(this)
}
