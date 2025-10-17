
package com.walktracker.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.firebase.auth.FirebaseAuth
import com.walktracker.app.service.LocationTrackingService
import com.walktracker.app.ui.screen.*
import com.walktracker.app.ui.theme.WalkTrackerTheme
import com.walktracker.app.viewmodel.MainViewModel
import com.walktracker.app.viewmodel.RankingPeriod

class MainActivity : ComponentActivity() {

    private lateinit var auth: FirebaseAuth

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.d(TAG, "모든 권한이 허용되었습니다. 추적 서비스를 시작합니다.")
            startTrackingService()
        } else {
            Log.w(TAG, "일부 권한이 거부되었습니다. 추적 서비스를 시작할 수 없습니다.")
            // 사용자에게 권한이 필요하다는 알림을 띄우는 것이 좋습니다.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: 액티비티 생성")

        auth = FirebaseAuth.getInstance()
        MobileAds.initialize(this) {}

        // 사용자가 이미 로그인했다면, 권한 확인 후 서비스 시작
        if (auth.currentUser != null) {
            requestPermissions()
        }

        setContent {
            WalkTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val startDestination = if (auth.currentUser != null) "main" else "login"

                    NavHost(navController = navController, startDestination = startDestination) {
                        composable("login") {
                            LoginScreen(
                                navController = navController,
                                onLoginSuccess = {
                                    requestPermissions()
                                    navController.navigate("main") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("signup") {
                            SignUpScreen(
                                navController = navController,
                                onSignUpSuccess = {
                                    navController.navigate("login") {
                                        popUpTo("signup") { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable("main") {
                            val mainViewModel: MainViewModel = viewModel()
                            MainApp(
                                viewModel = mainViewModel,
                                onSignOut = {
                                    auth.signOut()
                                    // Also stop the service
                                    stopService(Intent(this@MainActivity, LocationTrackingService::class.java))
                                    navController.navigate("login") {
                                        popUpTo("main") { inclusive = true }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needsPermission = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needsPermission) {
            Log.d(TAG, "필요한 권한이 없어 요청합니다.")
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            Log.d(TAG, "모든 권한이 이미 허용되어 있습니다. 바로 서비스를 시작합니다.")
            startTrackingService()
        }
    }

    private fun startTrackingService() {
        Log.d(TAG, "startTrackingService: 서비스 시작 시도")
        val serviceIntent = Intent(this, LocationTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // 추적 상태 저장
        getSharedPreferences("WalkTrackerPrefs", MODE_PRIVATE)
            .edit()
            .putBoolean("tracking_enabled", true)
            .apply()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
fun MainApp(
    viewModel: MainViewModel,
    onSignOut: () -> Unit
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val rankingState by viewModel.rankingState.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            Column {
                // AdMob 배너
                AdMobBanner()

                // 네비게이션 바
                BottomNavigationBar(navController = navController)
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(paddingValues)
        ) {
            composable("home") {
                MainScreen(
                    uiState = uiState,
                    onRefresh = { viewModel.refreshData() }
                )
            }

            composable("map") {
                MapScreen(
                    routePoints = emptyList(), // 더 이상 사용하지 않음 (내부에서 로드)
                    lastKnownLocation = uiState.lastKnownLocation,
                    onLocationRequest = { viewModel.requestLocationUpdate() }
                )
            }

            composable("ranking") {
                LaunchedEffect(Unit) {
                    viewModel.loadRankings(RankingPeriod.DAILY)
                }

                RankingScreen(
                    rankingState = rankingState,
                    onPeriodChange = { period ->
                        viewModel.loadRankings(period)
                    }
                )
            }

            composable("settings") {
                SettingsScreen(
                    user = uiState.user,
                    onWeightUpdate = { weight ->
                        viewModel.updateUserWeight(weight)
                    },
                    onSignOut = onSignOut,
                    notificationEnabled = uiState.notificationEnabled,
                    onNotificationChange = { enabled ->
                        viewModel.setNotificationEnabled(enabled)
                    }
                )
            }
        }
    }
}

@Composable
fun BottomNavigationBar(navController: NavHostController) {
    val items = listOf(
        NavigationItem("home", "홈", Icons.Default.Home),
        NavigationItem("map", "경로", Icons.Default.Map),
        NavigationItem("ranking", "랭킹", Icons.Default.EmojiEvents),
        NavigationItem("settings", "설정", Icons.Default.Settings)
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        items.forEach { item ->
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label
                    )
                },
                label = { Text(item.label) },
                selected = currentRoute == item.route,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
    }
}

@Composable
fun AdMobBanner() {
    val context = LocalContext.current

    AndroidView(
        factory = {
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                // 테스트 광고 ID (실제 배포 시 변경 필요)
                adUnitId = "ca-app-pub-3940256099942544/6300978111"
                loadAd(AdRequest.Builder().build())
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
    )
}

data class NavigationItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)
