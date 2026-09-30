package app.gyro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.gyro.ui.common.gyroViewModel
import app.gyro.ui.dashboard.DashboardScreen
import app.gyro.ui.dashboard.DashboardViewModel
import app.gyro.ui.dashboard.HandlebarScreen
import app.gyro.ui.tabs.BatteryTab
import app.gyro.ui.tabs.MoreTab
import app.gyro.ui.tabs.TripsTab
import app.gyro.ui.tabs.WheelSettingsTab
import app.gyro.ui.theme.GyroColors
import app.gyro.ui.theme.GyroIcons
import app.gyro.ui.theme.GyroTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GyroTheme { GyroRoot() } }
    }
}

private enum class Tab(val route: String, val label: String, val icon: () -> ImageVector) {
    DASHBOARD("dashboard", "Дашборд", { GyroIcons.Speed }),
    BATTERY("battery", "Батарея", { GyroIcons.Battery }),
    TRIPS("trips", "Поездки", { GyroIcons.Route }),
    WHEEL("wheel", "Колесо", { GyroIcons.Tune }),
    MORE("more", "Ещё", { GyroIcons.More }),
}

private const val HANDLEBAR_ROUTE = "handlebar"

private fun blePermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
private fun GyroRoot() {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dashboard = gyroViewModel { DashboardViewModel(it) }
    val showMessage: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

    // Runs the pending action once Bluetooth permissions are granted.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val bluetoothOk = result.filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }.values.all { it }
        if (bluetoothOk) pending?.invoke() else showMessage("Без доступа к Bluetooth колесо не найти")
        pending = null
    }
    val ensurePermissions: (() -> Unit) -> Unit = { action ->
        val missing = blePermissions().filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            action()
        } else {
            pending = action
            launcher.launch(missing.toTypedArray())
        }
    }

    LaunchedEffect(Unit) { dashboard.messages.collect { showMessage(it) } }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    Scaffold(
        containerColor = GyroColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (route != HANDLEBAR_ROUTE) {
                NavigationBar(containerColor = GyroColors.Surface) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon(), contentDescription = null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = GyroColors.Accent,
                                selectedTextColor = GyroColors.Accent,
                                indicatorColor = GyroColors.SurfaceHigh,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(if (route == HANDLEBAR_ROUTE) androidx.compose.foundation.layout.PaddingValues() else padding)) {
            NavHost(nav, startDestination = Tab.DASHBOARD.route) {
                composable(Tab.DASHBOARD.route) {
                    DashboardScreen(
                        vm = dashboard,
                        onOpenHandlebar = { nav.navigate(HANDLEBAR_ROUTE) },
                        ensurePermissions = ensurePermissions,
                        showMessage = showMessage,
                    )
                }
                composable(Tab.BATTERY.route) { BatteryTab() }
                composable(Tab.TRIPS.route) { TripsTab() }
                composable(Tab.WHEEL.route) { WheelSettingsTab() }
                composable(Tab.MORE.route) { MoreTab() }
                composable(HANDLEBAR_ROUTE) { HandlebarScreen(dashboard, onExit = { nav.popBackStack() }) }
            }
        }
    }
}
