package com.flopster101.usbaudiobridge

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    state: MainUiState,
    onToggleGadget: (Boolean) -> Unit,
    onToggleCapture: () -> Unit,
    onBufferSizeChange: (Float) -> Unit,
    onPeriodSizeChange: (Int) -> Unit,
    onEngineTypeChange: (Int) -> Unit,
    onSampleRateChange: (Int) -> Unit,
    onKeepAdbChange: (Boolean) -> Unit,
    onAutoRestartChange: (Boolean) -> Unit,
    onActiveDirectionsChange: (Int) -> Unit,
    onMicSourceChange: (Int) -> Unit,
    onNotificationEnabledChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onScreensaverEnabledChange: (Boolean) -> Unit,
    onScreensaverTimeoutChange: (Int) -> Unit,
    onScreensaverRepositionIntervalChange: (Int) -> Unit,
    onScreensaverFullscreenChange: (Boolean) -> Unit,
    onScreensaverActivate: () -> Unit,
    onScreensaverDeactivate: () -> Unit,
    onToggleSpeakerMute: () -> Unit,
    onToggleMicMute: () -> Unit,
    onMuteOnMediaButtonChange: (Boolean) -> Unit,
    onResetSettings: () -> Unit,
    onToggleLogs: () -> Unit
) {
    val navController = rememberNavController()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Screensaver timer logic
    val screensaverEnabled = state.keepScreenOnOption && state.screensaverEnabled
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Reset timer on navigation
    LaunchedEffect(currentRoute) {
        lastInteractionTime = System.currentTimeMillis()
    }

    val myNestedScrollConnection = remember(scrollBehavior.nestedScrollConnection) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                lastInteractionTime = System.currentTimeMillis()
                return scrollBehavior.nestedScrollConnection.onPreScroll(available, source)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                lastInteractionTime = System.currentTimeMillis()
                return scrollBehavior.nestedScrollConnection.onPostScroll(consumed, available, source)
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(myNestedScrollConnection),
        topBar = {
            if (currentRoute != "about") {
                LargeTopAppBar(
                    title = { Text("USB Audio Bridge") },
                    scrollBehavior = scrollBehavior
                )
            }
        },
        bottomBar = {
            BottomNavBar(navController = navController)
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") {
                HomeScreen(
                    state = state,
                    onToggleGadget = onToggleGadget,
                    onToggleCapture = onToggleCapture,
                    onToggleSpeakerMute = onToggleSpeakerMute,
                    onToggleMicMute = onToggleMicMute,
                    onToggleLogs = onToggleLogs
                )
            }
            composable("settings") {
                SettingsScreen(
                    state = state,
                    onBufferSizeChange = onBufferSizeChange,
                    onPeriodSizeChange = onPeriodSizeChange,
                    onEngineTypeChange = onEngineTypeChange,
                    onSampleRateChange = onSampleRateChange,
                    onKeepAdbChange = onKeepAdbChange,
                    onAutoRestartChange = onAutoRestartChange,
                    onActiveDirectionsChange = onActiveDirectionsChange,
                    onMicSourceChange = onMicSourceChange,
                    onNotificationEnabledChange = onNotificationEnabledChange,
                    onKeepScreenOnChange = onKeepScreenOnChange,
                    onScreensaverEnabledChange = onScreensaverEnabledChange,
                    onScreensaverTimeoutChange = onScreensaverTimeoutChange,
                    onScreensaverRepositionIntervalChange = onScreensaverRepositionIntervalChange,
                    onScreensaverFullscreenChange = onScreensaverFullscreenChange,
                    onMuteOnMediaButtonChange = onMuteOnMediaButtonChange,
                    onResetSettings = onResetSettings
                )
            }
            composable("about") {
                AboutScreen()
            }
        }
    }

    // Screensaver timer
    LaunchedEffect(screensaverEnabled, state.screensaverTimeout) {
        if (!screensaverEnabled) return@LaunchedEffect
        
        lastInteractionTime = System.currentTimeMillis()  // Reset timer when enabled
        
        while (true) {
            delay(1000) // Check every second
            val timeSinceLastInteraction = System.currentTimeMillis() - lastInteractionTime
            val shouldActivate = timeSinceLastInteraction >= (state.screensaverTimeout * 1000L)
            
            if (shouldActivate && !state.screensaverActive) {
                onScreensaverActivate()
            }
        }
    }
    
    // Screensaver overlay
    if (state.screensaverActive) {
        ScreensaverOverlay(
            state = state,
            onDismiss = {
                onScreensaverDeactivate()
                lastInteractionTime = System.currentTimeMillis()
            }
        )
    }
}

@Composable
fun BottomNavBar(navController: NavController) {
    val items = listOf("Home", "Settings", "About")
    val icons = listOf(Icons.Default.Home, Icons.Default.Settings, Icons.Default.Info)
    val routes = listOf("home", "settings", "about")

    NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        items.forEachIndexed { index, item ->
            NavigationBarItem(
                icon = { Icon(icons[index], contentDescription = item) },
                label = { Text(item) },
                selected = currentRoute == routes[index],
                onClick = {
                    navController.navigate(routes[index]) {
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
