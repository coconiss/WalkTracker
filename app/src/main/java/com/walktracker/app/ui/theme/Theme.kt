package com.walktracker.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// 라이트 테마 색상
private val LightPrimary = Color(0xFF4CAF50)
private val LightSecondary = Color(0xFF8BC34A)
private val LightTertiary = Color(0xFF03A9F4)
private val LightError = Color(0xFFFF5252)
private val LightBackground = Color(0xFFFAFAFA)
private val LightSurface = Color(0xFFFFFFFF)

// 다크 테마 색상
private val DarkPrimary = Color(0xFF66BB6A)
private val DarkSecondary = Color(0xFF9CCC65)
private val DarkTertiary = Color(0xFF29B6F6)
private val DarkError = Color(0xFFEF5350)
private val DarkBackground = Color(0xFF121212)
private val DarkSurface = Color(0xFF1E1E1E)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimary.copy(alpha = 0.2f),
    onPrimaryContainer = Color(0xFF1B5E20),
    secondary = LightSecondary,
    onSecondary = Color.White,
    tertiary = LightTertiary,
    onTertiary = Color.White,
    error = LightError,
    onError = Color.White,
    background = LightBackground,
    onBackground = Color(0xFF212121),
    surface = LightSurface,
    onSurface = Color(0xFF212121),
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF757575)
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color(0xFF1B5E20),
    primaryContainer = DarkPrimary.copy(alpha = 0.3f),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = DarkSecondary,
    onSecondary = Color(0xFF33691E),
    tertiary = DarkTertiary,
    onTertiary = Color(0xFF01579B),
    error = DarkError,
    onError = Color(0xFFB71C1C),
    background = DarkBackground,
    onBackground = Color(0xFFE0E0E0),
    surface = DarkSurface,
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFBDBDBD)
)

@Composable
fun WalkTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}