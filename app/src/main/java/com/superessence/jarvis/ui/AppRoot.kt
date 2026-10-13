package com.superessence.jarvis.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.superessence.jarvis.JarvisApp
import com.superessence.jarvis.ui.screens.AboutScreen
import com.superessence.jarvis.ui.screens.ActionLogScreen
import com.superessence.jarvis.ui.screens.AiSettingsScreen
import com.superessence.jarvis.ui.screens.AppearanceScreen
import com.superessence.jarvis.ui.screens.AssistantScreen
import com.superessence.jarvis.ui.screens.BatteryScreen
import com.superessence.jarvis.ui.screens.DiagnosticsScreen
import com.superessence.jarvis.ui.screens.HomeScreen
import com.superessence.jarvis.ui.screens.OnboardingScreen
import com.superessence.jarvis.ui.screens.PermissionsScreen
import com.superessence.jarvis.ui.screens.PrivacyScreen
import com.superessence.jarvis.ui.screens.RoutinesScreen
import com.superessence.jarvis.ui.screens.SettingsHub
import com.superessence.jarvis.ui.screens.SplashScreen
import com.superessence.jarvis.ui.screens.VoiceSettingsScreen
import com.superessence.jarvis.ui.theme.JColors
import com.superessence.jarvis.util.Perms

enum class Tab(val label: String) { HOME("Accueil"), CHAT("Assistant"), ROUTINES("Routines"), SETTINGS("Réglages") }

@Composable
fun AppRoot(activity: MainActivity, app: JarvisApp) {
    var splash by remember { mutableStateOf(!MainActivity.splashShown) }
    val settings by app.settings.flow.collectAsState()

    Box(Modifier.fillMaxSize().background(JColors.Bg)) {
        AnimatedContent(
            targetState = when { splash -> 0; !settings.onboardingDone -> 1; else -> 2 },
            transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "root",
        ) { phase ->
            when (phase) {
                0 -> SplashScreen { MainActivity.splashShown = true; splash = false }
                1 -> OnboardingScreen(activity, app)
                else -> MainShell(activity, app)
            }
        }
    }
}

@Composable
private fun MainShell(activity: MainActivity, app: JarvisApp) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var sub by rememberSaveable { mutableStateOf<String?>(null) }
    val pendingTalk by activity.pendingTalk
    val requested by activity.requestedScreen

    LaunchedEffect(requested) {
        requested?.let { tab = Tab.SETTINGS; sub = if (it == "settings") null else it; activity.requestedScreen.value = null }
    }
    LaunchedEffect(pendingTalk) {
        if (pendingTalk) {
            activity.pendingTalk.value = false
            tab = Tab.HOME
            if (Perms.mic(activity)) app.session.startListening()
        }
    }
    BackHandler(enabled = sub != null || tab != Tab.HOME) {
        if (sub != null) sub = null else tab = Tab.HOME
    }

    Scaffold(
        containerColor = JColors.Bg,
        bottomBar = {
            if (sub == null) NavigationBar(containerColor = JColors.Surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = {
                            Icon(when (t) {
                                Tab.HOME -> Icons.Filled.AutoAwesome
                                Tab.CHAT -> Icons.AutoMirrored.Filled.Chat
                                Tab.ROUTINES -> Icons.Filled.Schedule
                                Tab.SETTINGS -> Icons.Filled.Settings
                            }, contentDescription = t.label)
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = JColors.SurfaceHigh, selectedIconColor = JColors.Cyan, selectedTextColor = JColors.Cyan),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val back = { sub = null }
            when {
                sub == "ai" -> AiSettingsScreen(app, back)
                sub == "voice" -> VoiceSettingsScreen(activity, app, back)
                sub == "appearance" -> AppearanceScreen(app, back)
                sub == "permissions" -> PermissionsScreen(activity, app, back)
                sub == "privacy" -> PrivacyScreen(app, back)
                sub == "log" -> ActionLogScreen(app, back)
                sub == "battery" -> BatteryScreen(activity, app, back)
                sub == "diagnostics" -> DiagnosticsScreen(activity, app, back)
                sub == "about" -> AboutScreen(back)
                tab == Tab.HOME -> HomeScreen(activity, app) { sub = it; tab = Tab.SETTINGS }
                tab == Tab.CHAT -> AssistantScreen(app)
                tab == Tab.ROUTINES -> RoutinesScreen(app)
                tab == Tab.SETTINGS -> SettingsHub { sub = it }
            }
        }
    }
}
