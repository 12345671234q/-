package com.privatecompanion.chat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme

private val LightColors = lightColorScheme(
    primary = Color(0xFF6255C7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E1FF),
    secondary = Color(0xFF4F62A6),
    secondaryContainer = Color(0xFFF0EEFF),
    tertiary = Color(0xFF9B4E82),
    background = Color(0xFFF9F7FF),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFEAE7F2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC9BFFF),
    onPrimary = Color(0xFF2F227F),
    primaryContainer = Color(0xFF493D9A),
    secondary = Color(0xFFBBC4FF),
    secondaryContainer = Color(0xFF292943),
    tertiary = Color(0xFFFFACDC),
    background = Color(0xFF10101A),
    surface = Color(0xFF171720),
    surfaceVariant = Color(0xFF2A2935),
)

@Composable
fun MiCompanionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = androidx.compose.material3.Typography(),
        content = content,
    )
}
