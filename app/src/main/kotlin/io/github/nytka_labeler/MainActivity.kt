package io.github.nytka_labeler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import io.github.nytka_labeler.settings.SettingsSource
import io.github.nytka_labeler.ui.SessionScreen
import io.github.nytka_labeler.ui.SetupScreen
import io.github.nytka_labeler.ui.TasksScreen
import io.github.nytka_labeler.ui.VoiceScreen
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var settings: SettingsSource

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LabelerTheme {
                Surface { Root(settings) }
            }
        }
    }
}

@Composable
private fun Root(settings: SettingsSource) {
    var start by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { start = if (settings.current().configured) "tasks" else "setup" }
    val first = start ?: return
    val nav = rememberNavController()
    NavHost(nav, startDestination = first) {
        composable("setup") {
            SetupScreen(hiltViewModel(), onDone = {
                nav.navigate("tasks") { popUpTo("setup") { inclusive = true } }
            })
        }
        composable("tasks") {
            TasksScreen(
                hiltViewModel(),
                onStart = { nav.navigate("session/$it") },
                onSetup = { nav.navigate("setup") },
                onVoice = { nav.navigate("voice") },
            )
        }
        composable("voice") {
            VoiceScreen(hiltViewModel(), onExit = { nav.popBackStack() })
        }
        composable("session/{task}", arguments = listOf(navArgument("task") { type = NavType.StringType })) {
            SessionScreen(hiltViewModel(), onExit = { nav.popBackStack() })
        }
    }
}

@Composable
private fun LabelerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme =
        when {
            dark -> runCatching { dynamicDarkColorScheme(context) }.getOrDefault(darkColorScheme())
            else -> runCatching { dynamicLightColorScheme(context) }.getOrDefault(lightColorScheme())
        }
    MaterialTheme(colorScheme = scheme, content = content)
}
