package com.walktracker.app.ui.screen

import android.content.Context
import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.walktracker.app.model.RoutePoint
import com.walktracker.app.repository.FirebaseRepository
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MapScreen(
    routePoints: List<RoutePoint>, // 오늘 실시간 경로
    lastKnownLocation: Location?,
    onLocationRequest: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { FirebaseRepository() }
    var mapView by remember { mutableStateOf<MapView?>(null) }

    // --- UI 상태 복원 ---
    var selectedDate by remember { mutableStateOf(Date()) }
    var showCalendar by remember { mutableStateOf(false) }
    var loadedRoutes by remember { mutableStateOf<List<RoutePoint>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val displayFormat = remember { SimpleDateFormat("yyyy년 MM월 dd일", Locale.getDefault()) }
    val isTodaySelected = remember(selectedDate) { isSameDay(selectedDate, Date()) }

    // --- 데이터 로직 통합 ---
    LaunchedEffect(selectedDate) {
        if (!isTodaySelected) {
            val userId = repository.getCurrentUserId() ?: return@LaunchedEffect
            isLoading = true
            repository.getDailyActivityOnce(userId, dateFormat.format(selectedDate)) { activity ->
                loadedRoutes = activity?.routes?.sortedBy { it.timestamp } ?: emptyList()
                isLoading = false
            }
        } else {
            // 오늘 날짜가 선택되면, ViewModel의 실시간 데이터를 사용
            loadedRoutes = emptyList() 
        }
    }

    LaunchedEffect(Unit) {
        Configuration.getInstance().load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                MapView(it).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(15.0)
                    controller.setCenter(GeoPoint(37.5665, 126.9780))
                    mapView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                val currentRoutes = if (isTodaySelected) routePoints else loadedRoutes
                view.overlays.clear()
                if (currentRoutes.size > 1) {
                    val polylines = currentRoutes.windowed(2).map { (start, end) ->
                        Polyline().apply {
                            val speedKmh = end.speed * 3.6
                            outlinePaint.color = getSpeedColor(speedKmh)
                            outlinePaint.strokeWidth = 14f
                            setPoints(listOf(GeoPoint(start.latitude, start.longitude), GeoPoint(end.latitude, end.longitude)))
                        }
                    }
                    view.overlays.addAll(polylines)

                    if(view.mapCenter == GeoPoint(37.5665, 126.9780)){
                         view.zoomToBoundingBox(Polyline().apply{ setPoints(currentRoutes.map{GeoPoint(it.latitude, it.longitude)}) }.bounds, true, 100)
                    }
                }
                view.invalidate()
            }
        )

        // --- UI 요소 복원 ---
        MapHeader( 
            selectedDate = displayFormat.format(selectedDate),
            isLoading = isLoading && !isTodaySelected,
            routes = if (isTodaySelected) routePoints else loadedRoutes,
            onCalendarClick = { showCalendar = true } 
        )

        // 내 위치 버튼
        FloatingActionButton(
            onClick = {
                lastKnownLocation?.let {
                    mapView?.controller?.animateTo(GeoPoint(it.latitude, it.longitude), 17.0, 500L)
                } ?: onLocationRequest()
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(Icons.Default.MyLocation, "내 위치", tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }

    if (showCalendar) {
        CalendarDialog(
            selectedDate = selectedDate,
            onDateSelected = { date ->
                selectedDate = date
                showCalendar = false
            },
            onDismiss = { showCalendar = false }
        )
    }

    DisposableEffect(Unit) {
        onDispose { mapView?.onDetach() }
    }
}

@Composable
private fun MapHeader(
    selectedDate: String,
    isLoading: Boolean,
    routes: List<RoutePoint>,
    onCalendarClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("이동 경로", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(selectedDate, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onCalendarClick) {
                    Icon(Icons.Default.CalendarMonth, "날짜 선택", tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            } else if (routes.isNotEmpty()) {
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    RouteStatItem(icon = Icons.Default.Place, label = "포인트", value = "${routes.size}개")
                    RouteStatItem(icon = Icons.Default.AccessTime, label = "기간", value = formatDuration(routes))
                }
            }
        }
    }
}

private fun getSpeedColor(speedKmh: Double): Int {
    return when {
        speedKmh < 3.0 -> android.graphics.Color.parseColor("#4CAF50") // 느린 걸음 - 초록
        speedKmh < 6.0 -> android.graphics.Color.parseColor("#FFC107") // 빠른 걸음 - 노랑
        speedKmh < 10.0 -> android.graphics.Color.parseColor("#FF9800") // 조깅 - 주황
        else -> android.graphics.Color.parseColor("#F44336") // 달리기 - 빨강
    }
}

// CalendarDialog 및 기타 유틸 함수는 이전과 동일하게 유지

@Composable
private fun RouteStatItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun formatDuration(routes: List<RoutePoint>): String {
    if (routes.size < 2) return "0분"
    val duration = (routes.last().timestamp - routes.first().timestamp) / 1000 / 60
    return if (duration < 60) "${duration}분" else "${duration / 60}시간 ${duration % 60}분"
}

@Composable
fun CalendarDialog(selectedDate: Date, onDateSelected: (Date) -> Unit, onDismiss: () -> Unit) {
    val calendar = remember { Calendar.getInstance().apply { time = selectedDate } }
    var currentMonth by remember { mutableStateOf(calendar.get(Calendar.MONTH)) }
    var currentYear by remember { mutableStateOf(calendar.get(Calendar.YEAR)) }
    val monthFormat = remember { SimpleDateFormat("yyyy년 MM월", Locale.getDefault()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.padding(16.dp), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { calendar.add(Calendar.MONTH, -1); currentMonth = calendar.get(Calendar.MONTH); currentYear = calendar.get(Calendar.YEAR) }) {
                        Icon(Icons.Default.ChevronLeft, "이전 달")
                    }
                    Text(monthFormat.format(calendar.time), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { calendar.add(Calendar.MONTH, 1); currentMonth = calendar.get(Calendar.MONTH); currentYear = calendar.get(Calendar.YEAR) }) {
                        Icon(Icons.Default.ChevronRight, "다음 달")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("일", "월", "화", "수", "목", "금", "토").forEach { day ->
                        Text(day, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                val firstDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1

                LazyVerticalGrid(columns = GridCells.Fixed(7), modifier = Modifier.height(250.dp)) {
                    items(firstDayOfWeek) { Box(modifier = Modifier.size(40.dp)) }
                    items(daysInMonth) { day ->
                        val date = Calendar.getInstance().apply { time = calendar.time; set(Calendar.DAY_OF_MONTH, day + 1) }.time
                        val isSelected = isSameDay(date, selectedDate)
                        val isToday = isSameDay(date, Date())
                        Box(
                            modifier = Modifier.size(40.dp).padding(2.dp).clip(CircleShape)
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else if (isToday) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { onDateSelected(date) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${day + 1}", style = MaterialTheme.typography.bodyMedium, color = if (isSelected) MaterialTheme.colorScheme.onPrimary else if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("닫기") }
            }
        }
    }
}

private fun isSameDay(date1: Date, date2: Date): Boolean {
    val cal1 = Calendar.getInstance().apply { time = date1 }
    val cal2 = Calendar.getInstance().apply { time = date2 }
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) && cal1.get(Calendar.DAY_OF_MONTH) == cal2.get(Calendar.DAY_OF_MONTH)
}
