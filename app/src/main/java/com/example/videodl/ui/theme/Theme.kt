package com.example.videodl.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF0061A4),
    secondary = Color(0xFF535F70),
    background = Color(0xFFFDFCFF),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9FCBFF),
    secondary = Color(0xFFBBC7DB),
    background = Color(0xFF1A1C1E),
)

@Composable
fun VideoDLTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
