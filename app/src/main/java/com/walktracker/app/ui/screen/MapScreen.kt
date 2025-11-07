package com.walktracker.app.ui.screen

import android.content.Context
import android.location.Location
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.SimpleDateFormat
import java.util.*

// 속도에 따른 색상을 반환하는 함수
private fun getColorForSpeed(speed: Double): Int {
    return when {
        speed < 0.8 -> android.graphics.Color.BLUE // 느림 (~3 km/h)
        speed < 1.25 -> android.graphics.Color.GREEN // 보통 (~4.5 km/h)
        speed < 1.80 -> android.graphics.Color.YELLOW // 빠름 (~5.5 km/h)
        speed < 2.2 -> android.graphics.Color.MAGENTA // 매우 빠름 (~8 km/h)
        else -> android.graphics.Color.RED // 달리기 (8 km/h ~)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    routePoints: List<RoutePoint>,
    lastKnownLocation: Location?,
    onLocationRequest: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { FirebaseRepository() }
    val scope = rememberCoroutineScope()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var myLocationOverlay by remember { mutableStateOf<MyLocationNewOverlay?>(null) }

    var selectedDate by remember { mutableStateOf(Date()) }
    var showCalendar by remember { mutableStateOf(false) }
    var loadedRoutes by remember { mutableStateOf<List<RoutePoint>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var showSpeedLegend by remember { mutableStateOf(false) } // 범례 표시 상태

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val displayFormat = remember { SimpleDateFormat("yyyy년 MM월 dd일", Locale.getDefault()) }

    // 선택된 날짜의 경로 로드
    LaunchedEffect(selectedDate) {
        val userId = repository.getCurrentUserId() ?: return@LaunchedEffect
        isLoading = true

        repository.getDailyActivityOnce(userId, dateFormat.format(selectedDate)) { activity ->
            loadedRoutes = activity?.routes ?: emptyList()
            isLoading = false
        }
    }

    LaunchedEffect(mapView) {
        mapView?.let { mapView ->
            val locationOverlay = object : MyLocationNewOverlay(GpsMyLocationProvider(context), mapView) {
                init {
                    // mDirectionArrowBitmap은 protected 멤버이므로 상속받은 클래스 내부에서 접근
                    mDirectionArrowBitmap = mPersonBitmap
                }
            }
            locationOverlay.enableMyLocation()
            mapView.overlays.add(locationOverlay)
            myLocationOverlay = locationOverlay

            // 초기 위치 및 줌 설정
            lastKnownLocation?.let { loc ->
                mapView.controller.setZoom(18.0) // 줌 레벨 확대
                mapView.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
            } ?: run {
                mapView.controller.setZoom(18.0)
                mapView.controller.setCenter(GeoPoint(37.5665, 126.9780)) // 서울 시청 기본 위치
            }
        }
    }

    LaunchedEffect(Unit) {
        Configuration.getInstance().load(
            context,
            context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // OSM 지도
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    mapView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                // 중복 추가를 막기 위해 myLocationOverlay를 제외한 오버레이만 클리어
                val overlaysToRemove = view.overlays.filter { it !is MyLocationNewOverlay }
                view.overlays.removeAll(overlaysToRemove)

                if (loadedRoutes.size > 1) {
                    loadedRoutes.windowed(2).forEach { (start, end) ->
                        val timeDifferenceSeconds = (end.timestamp - start.timestamp) / 1000
                        // 60초 이상 차이나는 경로는 그리지 않음 (LocationTrackingService.kt 참조)
                        if (timeDifferenceSeconds < 60) {
                            val segmentPolyline = Polyline().apply {
                                val speed = start.speed // 시작점의 속도를 기준으로 색상 결정
                                outlinePaint.color = getColorForSpeed(speed)
                                outlinePaint.strokeWidth = 12f
                            }
                            val segmentPoints =
                                listOf(GeoPoint(start.latitude, start.longitude), GeoPoint(end.latitude, end.longitude))
                            segmentPolyline.setPoints(segmentPoints)
                            view.overlays.add(segmentPolyline)
                        }
                    }

                    // 경로 중심으로 이동 (단, 사용자가 지도를 조작하지 않았을 때만)
                    if (!view.isAnimating && loadedRoutes.isNotEmpty()) {
                        val geoPoints = loadedRoutes.map { GeoPoint(it.latitude, it.longitude) }
                        val centerLat = geoPoints.map { it.latitude }.average()
                        val centerLon = geoPoints.map { it.longitude }.average()
                        view.controller.animateTo(GeoPoint(centerLat, centerLon))
                        view.controller.setZoom(14.0)
                    }
                } else if (loadedRoutes.isNotEmpty()) {
                    val startPoint = loadedRoutes.first()
                    val marker = org.osmdroid.views.overlay.Marker(view)
                    marker.position = GeoPoint(startPoint.latitude, startPoint.longitude)
                    marker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                    view.overlays.add(marker)
                    if (!view.isAnimating) {
                        view.controller.animateTo(marker.position)
                    }
                }
                view.invalidate()
            }
        )

        // 상단 UI 영역 (날짜 카드 + 범례)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 날짜 선택 카드
            Card(
                modifier = Modifier.fillMaxWidth(0.9f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "이동 경로",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = displayFormat.format(selectedDate),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // 아이콘 버튼들
                        Row {
                            IconButton(onClick = { showSpeedLegend = !showSpeedLegend }) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = "범례 표시/숨기기",
                                    tint = if (showSpeedLegend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            IconButton(onClick = { showCalendar = true }) {
                                Icon(
                                    imageVector = Icons.Default.CalendarMonth,
                                    contentDescription = "날짜 선택",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (isLoading) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else if (loadedRoutes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            RouteStatItem(
                                icon = Icons.Default.Place,
                                label = "포인트",
                                value = "${loadedRoutes.size}개"
                            )
                            RouteStatItem(
                                icon = Icons.Default.AccessTime,
                                label = "기간",
                                value = formatDuration(loadedRoutes)
                            )
                        }
                    }
                }
            }

            // 속도 범례
            AnimatedVisibility(
                visible = showSpeedLegend,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                SpeedLegend(modifier = Modifier.fillMaxWidth(0.9f))
            }
        }

        // 빈 경로 메시지
        if (!isLoading && loadedRoutes.isEmpty()) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.DirectionsWalk,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "경로 데이터 없음",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "이 날짜에는 이동 기록이 없습니다",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // 내 위치 버튼
        FloatingActionButton(
            onClick = {
                myLocationOverlay?.myLocation?.let {
                    mapView?.controller?.animateTo(it)
                    mapView?.controller?.setZoom(18.0) // 줌 레벨 수정
                }
                // 위치 권한이 없거나 위치를 못 잡을 경우를 대비
                if (myLocationOverlay?.myLocation == null) {
                    onLocationRequest()
                    lastKnownLocation?.let {
                        mapView?.controller?.animateTo(GeoPoint(it.latitude, it.longitude))
                        mapView?.controller?.setZoom(18.0) // 줌 레벨 수정
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(
                imageVector = Icons.Default.MyLocation,
                contentDescription = "내 위치"
            )
        }
    }

    // 달력 다이얼로그
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
        onDispose {
            myLocationOverlay?.disableMyLocation()
            mapView?.onDetach()
        }
    }
}

@Composable
private fun SpeedLegend(modifier: Modifier = Modifier) {
    val legendItems = listOf(
        "느림 (< 3 km/h)" to Color.Blue,
        "보통 (3-4.5 km/h)" to Color.Green,
        "빠름 (4.5-6.4 km/h)" to Color.Yellow,
        "매우 빠름 (6.4-8 km/h)" to Color.Magenta,
        "달리기 (8 km/h <)" to Color.Red
    )

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "속도 범례",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            legendItems.forEach { (label, color) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(color, CircleShape)
                            .border(1.dp, Color.Gray.copy(alpha = 0.5f), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}


@Composable
private fun RouteStatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun formatDuration(routes: List<RoutePoint>): String {
    if (routes.isEmpty()) return "0분"
    val duration = (routes.last().timestamp - routes.first().timestamp) / 1000 / 60
    return if (duration < 60) {
        "${duration}분"
    } else {
        val hours = duration / 60
        val minutes = duration % 60
        "${hours}시간 ${minutes}분"
    }
}

@Composable
fun CalendarDialog(
    selectedDate: Date,
    onDateSelected: (Date) -> Unit,
    onDismiss: () -> Unit
) {
    val calendar = remember { Calendar.getInstance().apply { time = selectedDate } }
    var currentMonth by remember { mutableStateOf(calendar.get(Calendar.MONTH)) }
    var currentYear by remember { mutableStateOf(calendar.get(Calendar.YEAR)) }

    val monthFormat = remember { SimpleDateFormat("yyyy년 MM월", Locale.getDefault()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // 월 선택 헤더
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (currentMonth == 0) {
                            currentMonth = 11
                            currentYear--
                        } else {
                            currentMonth--
                        }
                    }) {
                        Icon(Icons.Default.ChevronLeft, "이전 달")
                    }

                    Text(
                        text = monthFormat.format(
                            Calendar.getInstance().apply {
                                set(Calendar.YEAR, currentYear)
                                set(Calendar.MONTH, currentMonth)
                            }.time
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    IconButton(onClick = {
                        if (currentMonth == 11) {
                            currentMonth = 0
                            currentYear++
                        } else {
                            currentMonth++
                        }
                    }) {
                        Icon(Icons.Default.ChevronRight, "다음 달")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 요일 헤더
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf("일", "월", "화", "수", "목", "금", "토").forEach { day ->
                        Text(
                            text = day,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 날짜 그리드
                val daysInMonth = getDaysInMonth(currentYear, currentMonth)
                val firstDayOfWeek = getFirstDayOfWeek(currentYear, currentMonth)

                LazyVerticalGrid(
                    columns = GridCells.Fixed(7),
                    modifier = Modifier.height(250.dp)
                ) {
                    // 빈 칸
                    items(firstDayOfWeek) {
                        Box(modifier = Modifier.size(40.dp))
                    }

                    // 날짜
                    items(daysInMonth) { day ->
                        val date = Calendar.getInstance().apply {
                            set(Calendar.YEAR, currentYear)
                            set(Calendar.MONTH, currentMonth)
                            set(Calendar.DAY_OF_MONTH, day + 1)
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.time

                        val isSelected = isSameDay(date, selectedDate)
                        val isToday = isSameDay(date, Date())

                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .padding(2.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        isToday -> MaterialTheme.colorScheme.primaryContainer
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable {
                                    onDateSelected(date)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${day + 1}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                    isToday -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 닫기 버튼
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("닫기")
                }
            }
        }
    }
}

private fun getDaysInMonth(year: Int, month: Int): Int {
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.YEAR, year)
    calendar.set(Calendar.MONTH, month)
    return calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
}

private fun getFirstDayOfWeek(year: Int, month: Int): Int {
    val calendar = Calendar.getInstance()
    calendar.set(Calendar.YEAR, year)
    calendar.set(Calendar.MONTH, month)
    calendar.set(Calendar.DAY_OF_MONTH, 1)
    return calendar.get(Calendar.DAY_OF_WEEK) - 1
}

private fun isSameDay(date1: Date, date2: Date): Boolean {
    val cal1 = Calendar.getInstance().apply { time = date1 }
    val cal2 = Calendar.getInstance().apply { time = date2 }
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.MONTH) == cal2.get(Calendar.MONTH) &&
            cal1.get(Calendar.DAY_OF_MONTH) == cal2.get(Calendar.DAY_OF_MONTH)
}
