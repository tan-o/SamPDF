package com.samreader.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF173A3A)
val Paper = Color(0xFFF4F0E8)
val Terracotta = Color(0xFFC96B45)
val Moss = Color(0xFF557166)

private val SamReaderColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E7E3),
    onPrimaryContainer = Color(0xFF082B2B),
    secondary = Terracotta,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCD),
    onSecondaryContainer = Color(0xFF4B1808),
    tertiary = Moss,
    background = Paper,
    onBackground = Color(0xFF202521),
    surface = Color(0xFFFFFDF8),
    onSurface = Color(0xFF202521),
    surfaceVariant = Color(0xFFE4E6DF),
    onSurfaceVariant = Color(0xFF454A46),
    outline = Color(0xFF747A75),
    error = Color(0xFFB3261E),
)

private val SamReaderDarkColors = darkColorScheme(
    primary = Color(0xFFA8D2CC),
    onPrimary = Color(0xFF0A3735),
    primaryContainer = Color(0xFF244E4B),
    onPrimaryContainer = Color(0xFFC3ECE6),
    secondary = Color(0xFFFFB69B),
    onSecondary = Color(0xFF5B1F0A),
    secondaryContainer = Color(0xFF78351F),
    onSecondaryContainer = Color(0xFFFFDBCD),
    tertiary = Color(0xFFB9CCBF),
    background = Color(0xFF111513),
    onBackground = Color(0xFFE1E5E0),
    surface = Color(0xFF171B19),
    onSurface = Color(0xFFE1E5E0),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),
    outline = Color(0xFF89938F),
    error = Color(0xFFFFB4AB),
)

@Composable
fun SamReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) SamReaderDarkColors else SamReaderColors,
        typography = Typography(),
        content = content,
    )
}
