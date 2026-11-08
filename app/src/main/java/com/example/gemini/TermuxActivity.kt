package com.shriram.cinecode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.shriram.cinecode.data.preferences.AuthPreferences
import com.shriram.cinecode.theme.GeminiTheme
import com.shriram.cinecode.theme.isSystemInDarkThemeRobust
import com.shriram.cinecode.ui.components.LocalTerminalDialog

class TermuxActivity : ComponentActivity() {

    private val authPreferences: AuthPreferences by lazy { AuthPreferences(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.shriram.cinecode.data.service.TermuxService.start(this)

        setContent {
            val initialThemeMode = remember { authPreferences.getThemeModeSync() }
            val themeMode by authPreferences.themeMode.collectAsState(initial = initialThemeMode)
            val isSystemDark = isSystemInDarkThemeRobust()
            val useDarkTheme = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemDark
            }

            GeminiTheme(darkTheme = useDarkTheme) {
                LocalTerminalDialog(
                    onDismiss = { finish() }
                )
            }
        }
    }
}
