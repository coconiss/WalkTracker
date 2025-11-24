package com.walktracker.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import kotlin.system.exitProcess

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
            Log.w(TAG, "일부 권한이 거부되었습니다. 권한 안내 다이얼로그를 표시합니다.")
            showPermissionDialog()
        }
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Log.d(TAG, "설정 화면에서 돌아왔습니다. 권한을 다시 확인합니다.")
        requestPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: 액티비티 생성")

        auth = FirebaseAuth.getInstance()
        MobileAds.initialize(this) {}

        setContent {
            WalkTrackerTheme {
                val navController = rememberNavController()
                val (startDestination, setStartDestination) = remember { mutableStateOf<String?>(null) }

                LaunchedEffect(auth) {
                    val currentUser = auth.currentUser
                    setStartDestination(if (currentUser != null) "main" else "login")
                }

                if (startDestination != null) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
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
                                        // 서비스 중지 및 데이터 초기화
                                        stopTrackingService()
                                        clearAllData()
                                        auth.signOut()

                                        navController.navigate("login") {
                                            popUpTo("main") { inclusive = true }
                                        }
                                    },
                                    onDeleteAccount = { 
                                        mainViewModel.deleteAccount()
                                        stopTrackingService()
                                        clearAllData()
                                        navController.navigate("login") {
                                            popUpTo("main") { inclusive = true }
                                        }
                                    },
                                    onForceQuit = {
                                        stopTrackingService()
                                        finishAffinity()
                                        exitProcess(0)
                                    },
                                    navController = navController
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (auth.currentUser != null) {
            requestPermissions()
        }
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("권한 안내 (필수)")
            .setMessage("앱의 핵심 기능을 사용하려면 필수 권한을 허용해야 합니다. \'허용\' 버튼을 눌러 설정 화면으로 이동한 후, 모든 권한을 허용해주세요.")
            .setPositiveButton("허용") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                settingsLauncher.launch(intent)
            }
            .setNegativeButton("거부") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            //Manifest.permission.BODY_SENSORS,
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

        getSharedPreferences("WalkTrackerPrefs", MODE_PRIVATE)
            .edit()
            .putBoolean("tracking_enabled", true)
            .apply()
    }

    private fun stopTrackingService() {
        Log.d(TAG, "stopTrackingService: 서비스 중지")
        stopService(Intent(this, LocationTrackingService::class.java))

        getSharedPreferences("WalkTrackerPrefs", MODE_PRIVATE)
            .edit()
            .putBoolean("tracking_enabled", false)
            .apply()
    }

    /**
     * 로그아웃 시 모든 로컬 데이터를 초기화합니다.
     */
    private fun clearAllData() {
        Log.d(TAG, "clearAllData: 모든 로컬 데이터 초기화")

        // WalkTrackerPrefs 초기화
        getSharedPreferences("WalkTrackerPrefs", MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        // WalkTrackerSyncPrefs 초기화
        getSharedPreferences("WalkTrackerSyncPrefs", MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        // osmdroid 캐시 초기화 (선택사항)
        getSharedPreferences("osmdroid", MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

@Composable
fun MainApp(
    viewModel: MainViewModel,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onForceQuit: () -> Unit,
    navController: NavHostController
) {
    val appNavController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val rankingState by viewModel.rankingState.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            Column {
                AdMobBanner()
                BottomNavigationBar(navController = appNavController)
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = appNavController,
            startDestination = "home",
            modifier = Modifier.padding(paddingValues)
        ) {
            composable("home") {
                MainScreen(
                    uiState = uiState,
                    onRefresh = { viewModel.refreshData() },
                    onNavigateToDetails = { appNavController.navigate("activity_details") },
                    onResetTodayActivity = { viewModel.resetTodayActivity() }
                )
            }
            composable("activity_details") {
                ActivityDetailsScreen(onNavigateBack = { appNavController.navigateUp() })
            }
            composable("map") {
                MapScreen(
                    routePoints = emptyList(),
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
                    onWeightUpdate = {
                        viewModel.updateUserWeight(it)
                    },
                    onSignOut = onSignOut,
                    onDeleteAccount = onDeleteAccount,
                    notificationEnabled = uiState.notificationEnabled,
                    onNotificationChange = {
                        viewModel.setNotificationEnabled(it)
                    },
                    onForceQuit = onForceQuit
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
                icon = { Icon(imageVector = item.icon, contentDescription = item.label) },
                label = { Text(item.label) },
                selected = currentRoute == item.route,
                onClick = {
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
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
                //adUnitId = "ca-app-pub-3245182432537705/5154593094" //실제 key
                adUnitId = "ca-app-pub-3940256099942544/6300978111"
                loadAd(AdRequest.Builder().build())
            }
        },
        modifier = Modifier.fillMaxWidth().height(50.dp)
    )
}

data class NavigationItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)