package cn.edu.xxq.rosmastercontrol.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AppBlue = Color(0xFF1769C2)
val AppBlueDark = Color(0xFF0D4E99)
val AppBlueContainer = Color(0xFFD8E9FF)
val AppBackground = Color(0xFFF4F7FC)
val EmergencyRed = Color(0xFFC62828)

private val LightColors = lightColorScheme(
    primary = AppBlue,
    onPrimary = Color.White,
    primaryContainer = AppBlueContainer,
    onPrimaryContainer = Color(0xFF001C3B),
    secondary = Color(0xFF46617F),
    background = AppBackground,
    surface = Color.White,
    surfaceVariant = Color(0xFFE9EEF5),
    error = EmergencyRed,
)

@Composable
fun RosmasterControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}

