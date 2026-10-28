package com.spacexjr.saturnbmo.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val BmoGreen = Color(0xFFBDFFCB)
val BmoGreenDeep = Color(0xFF8EE3B1)
val BmoInk = Color(0xFF173D2A)
val BmoInkSoft = Color(0xFF345C46)
val BmoPanel = Color(0xFFF0FDF4)
val BmoPanelStrong = Color(0xFFE0F8E8)
val BmoError = Color(0xFFB3261E)
val BmoErrorSoft = Color(0xFFFFDAD6)
val BmoWarning = Color(0xFF8A5A00)
val BmoWarningSoft = Color(0xFFFFE7AE)
val BmoWhite = Color(0xFFFFFFFF)

private val Colors = lightColorScheme(
    primary = BmoInk,
    onPrimary = BmoGreen,
    primaryContainer = BmoGreenDeep,
    onPrimaryContainer = BmoInk,
    secondary = BmoInkSoft,
    onSecondary = BmoWhite,
    background = BmoGreen,
    onBackground = BmoInk,
    surface = BmoPanel,
    onSurface = BmoInk,
    surfaceVariant = BmoPanelStrong,
    onSurfaceVariant = BmoInkSoft,
    error = BmoError,
    onError = BmoWhite,
    errorContainer = BmoErrorSoft,
    onErrorContainer = BmoError,
    outline = Color(0xFF5A7A66),
)

private val BmoTypography = Typography(
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun BmoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        typography = BmoTypography,
        content = content,
    )
}
