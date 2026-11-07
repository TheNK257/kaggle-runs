package com.namankadam.kaggleruns

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Fixed (not dynamic) colours: the chart colours are validated against exactly these surfaces.
private val LightScheme = lightColorScheme(
    primary = Color(0xFF2A78D6),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFDCE8F8),
    onSecondaryContainer = Color(0xFF0B0B0B),
    background = Color(0xFFFCFCFB),
    onBackground = Color(0xFF0B0B0B),
    surface = Color(0xFFFCFCFB),
    onSurface = Color(0xFF0B0B0B),
    onSurfaceVariant = Color(0xFF52514E),
    surfaceContainer = Color(0xFFF3F2EE),
    surfaceContainerHigh = Color(0xFFECEBE6),
    outline = Color(0xFFD5D4CC),
    outlineVariant = Color(0xFFE1E0D9),
    error = Color(0xFFD03B3B),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF3987E5),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFF1F3550),
    onSecondaryContainer = Color(0xFFF0EFEC),
    background = Color(0xFF1A1A19),
    onBackground = Color(0xFFF0EFEC),
    surface = Color(0xFF1A1A19),
    onSurface = Color(0xFFF0EFEC),
    onSurfaceVariant = Color(0xFFC3C2B7),
    surfaceContainer = Color(0xFF242423),
    surfaceContainerHigh = Color(0xFF2C2C2A),
    outline = Color(0xFF3A3A37),
    outlineVariant = Color(0xFF2C2C2A),
    error = Color(0xFFD03B3B),
)

/** Chart and status tokens from the validated reference palette. */
@Immutable
data class AppColors(
    val series: Color,
    val grid: Color,
    val muted: Color,
    val good: Color,
    val goodText: Color,
    val warning: Color,
    val critical: Color,
    val neutral: Color,
)

private val LightAppColors = AppColors(
    series = Color(0xFF2A78D6), grid = Color(0xFFE1E0D9), muted = Color(0xFF898781),
    good = Color(0xFF0CA30C), goodText = Color(0xFF006300), warning = Color(0xFFFAB219),
    critical = Color(0xFFD03B3B), neutral = Color(0xFF898781),
)

private val DarkAppColors = AppColors(
    series = Color(0xFF3987E5), grid = Color(0xFF2C2C2A), muted = Color(0xFF898781),
    good = Color(0xFF0CA30C), goodText = Color(0xFF0CA30C), warning = Color(0xFFFAB219),
    critical = Color(0xFFD03B3B), neutral = Color(0xFF898781),
)

val LocalAppColors = staticCompositionLocalOf { LightAppColors }

@Composable
fun KaggleRunsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalAppColors provides if (dark) DarkAppColors else LightAppColors) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}

fun AppColors.forStatus(status: RunStatus, primary: Color): Color = when (status) {
    RunStatus.QUEUED -> warning
    RunStatus.RUNNING -> primary
    RunStatus.COMPLETE -> good
    RunStatus.ERROR -> critical
    RunStatus.CANCELLING, RunStatus.CANCELLED, RunStatus.UNKNOWN -> neutral
}
