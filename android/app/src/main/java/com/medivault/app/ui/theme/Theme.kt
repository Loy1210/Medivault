package com.medivault.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF6D8FB0),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF8AA399),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFA58FB8),
    background = Color(0xFFF7F4FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDE7F6),
    onSurface = Color(0xFF1F1C24),
    onSurfaceVariant = Color(0xFF5E5870)
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9CB7D4),
    secondary = Color(0xFFA8C1B6),
    tertiary = Color(0xFFC2AFD2),
    background = Color(0xFF17161B),
    surface = Color(0xFF201E26),
    surfaceVariant = Color(0xFF2A2632),
    onSurface = Color(0xFFE8E3F0),
    onSurfaceVariant = Color(0xFFC9C1D8)
)

@Composable
fun MediVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content
    )
}
