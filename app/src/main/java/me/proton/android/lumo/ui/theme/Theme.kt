package me.proton.android.lumo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = Purple,
    secondary = LightPurple,
    tertiary = Green,
    background = White,
    surface = White,
    onPrimary = White,
    onSecondary = DarkText,
    onTertiary = White,
    onBackground = DarkText,
    onSurface = DarkText,
    surfaceVariant = BorderGray,
    onSurfaceVariant = GrayText,
    error = ErrorRed,
    onError = White
)

@Composable
fun LumoTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}