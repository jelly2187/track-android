package com.jelly.habittrack.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Forest = Color(0xFF347568)
private val Light = lightColorScheme(primary = Forest, onPrimary = Color.White, primaryContainer = Color(0xFFD9EBDF), secondary = Color(0xFF6B8178), background = Color(0xFFF6F8F5), surface = Color(0xFFF6F8F5), surfaceContainer = Color.White, surfaceVariant = Color(0xFFE9EEE8), outlineVariant = Color(0xFFDDE5DD))
private val Dark = darkColorScheme(primary = Color(0xFF9FD4C2), onPrimary = Color(0xFF123B31), primaryContainer = Color(0xFF244C41), background = Color(0xFF121B17), surface = Color(0xFF121B17), surfaceContainer = Color(0xFF1D2822), surfaceVariant = Color(0xFF2A3730), outlineVariant = Color(0xFF3B4B41))
@Composable fun HabitTheme(theme: String = "system", content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (theme == "dark" || (theme == "system" && isSystemInDarkTheme())) Dark else Light, content = content)
}
