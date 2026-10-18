package fr.thermohabitat.pro.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import fr.thermohabitat.pro.data.repository.ThemeMode

private val Teal = Color(0xFF0B5D6B)
private val TealLight = Color(0xFF7FD3E2)
private val Ember = Color(0xFFE5642B)
private val EmberLight = Color(0xFFFFB693)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4ECF7),
    onPrimaryContainer = Color(0xFF001F25),
    secondary = Ember,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCB),
    onSecondaryContainer = Color(0xFF360F00),
    tertiary = Color(0xFF4F5F7D),
    background = Color(0xFFF8F6F4),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFDDE4E6),
    onSurfaceVariant = Color(0xFF3F484A),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF004E5A),
    onPrimaryContainer = Color(0xFFB4ECF7),
    secondary = EmberLight,
    onSecondary = Color(0xFF581E00),
    secondaryContainer = Color(0xFF7C2E0C),
    onSecondaryContainer = Color(0xFFFFDBCB),
    tertiary = Color(0xFFB7C7EA),
    background = Color(0xFF141414),
    surface = Color(0xFF191C1D),
    surfaceVariant = Color(0xFF3F484A),
    onSurfaceVariant = Color(0xFFBFC8CA),
    error = Color(0xFFFFB4AB),
)

/** Couleurs sémantiques du code de couverture (vert / orange / rouge). */
@Immutable
data class StatusColors(
    val sufficient: Color,
    val limit: Color,
    val insufficient: Color,
    val neutral: Color,
    val onStatus: Color,
)

private val LightStatus = StatusColors(Color(0xFF2E7D32), Color(0xFFEF8F00), Color(0xFFC62828), Color(0xFF78909C), Color.White)
private val DarkStatus = StatusColors(Color(0xFF66BB6A), Color(0xFFFFB74D), Color(0xFFEF5350), Color(0xFF90A4AE), Color(0xFF101010))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** Typographie légèrement agrandie pour une lecture confortable sur chantier. */
private val AppTypography = Typography().let { t ->
    t.copy(
        bodyLarge = t.bodyLarge.copy(fontSize = 17.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 15.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    )
}

val NumericStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp)

@Composable
fun ThermoHabitatTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
    }
}
