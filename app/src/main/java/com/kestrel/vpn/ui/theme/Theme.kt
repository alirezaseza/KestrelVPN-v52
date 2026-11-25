package com.kestrel.vpn.ui.theme
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.kestrel.vpn.data.local.AppThemeMode
import java.util.Locale
// 2026 "Obsidian & Ember": warm falcon-copper on near-black, mint reserved for "protected".
// 2026 Liquid Glass: icy periwinkle idle, mint protected, vivid orbs behind translucent glass.
val KestrelTeal = Color(0xFF2F6BFF)
val KestrelTealLight = Color(0xFFA5BEFF)
val KestrelEmerald = Color(0xFF3DDC97)
val KestrelAmber = Color(0xFFFFB84D)
val KestrelRose = Color(0xFFFF6B6B)
val KestrelOfflineRed = Color(0xFFFF3347)
val KestrelViolet = Color(0xFFC4B5FF)
val KestrelInk = Color(0xFF070B18)

private val LightColors = lightColorScheme(
    primary = KestrelTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF0A1233),
    secondary = Color(0xFF5B4FD6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E3FF),
    onSecondaryContainer = Color(0xFF1B1550),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    background = Color(0xFFE6EAF6),
    onBackground = Color(0xFF0B1220),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B1220),
    surfaceVariant = Color(0xFFDCE3F3),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF9AA8C4),
    error = Color(0xFFE0264B),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6C9BFF),
    onPrimary = Color(0xFF06103A),
    primaryContainer = Color(0xFF1B2447),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = KestrelViolet,
    onSecondary = Color(0xFF14103D),
    secondaryContainer = Color(0xFF241F57),
    onSecondaryContainer = Color(0xFFE3DFFF),
    tertiary = KestrelAmber,
    onTertiary = Color(0xFF2A1A00),
    background = KestrelInk,
    onBackground = Color(0xFFF4F6FF),
    surface = Color(0xFF131938),
    onSurface = Color(0xFFF4F6FF),
    surfaceVariant = Color(0xFF212958),
    onSurfaceVariant = Color(0xFFB9C0DC),
    outline = Color(0xFF3B4578),
    error = KestrelRose,
    onError = Color(0xFF3F0010)
)

val KestrelShapes = Shapes(
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(44.dp)
)

private fun loadVazirmatnOrFallback(context: Context): FontFamily {
    val am = context.assets
    val files = runCatching { am.list("fonts")?.toSet().orEmpty() }.getOrDefault(emptySet())
    val required = listOf(
        "Vazirmatn-Regular.ttf" to FontWeight.Normal,
        "Vazirmatn-Medium.ttf" to FontWeight.Medium,
        "Vazirmatn-SemiBold.ttf" to FontWeight.SemiBold,
        "Vazirmatn-Bold.ttf" to FontWeight.Bold
    )
    if (required.any { it.first !in files }) {
        return FontFamily.SansSerif
    }
    return runCatching {
        FontFamily(
            required.map { (name, weight) ->
                Font(path = "fonts/$name", assetManager = am, weight = weight)
            }
        )
    }.getOrDefault(FontFamily.SansSerif)
}
private fun typographyWithFont(fontFamily: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = fontFamily),
        displayMedium = base.displayMedium.copy(fontFamily = fontFamily),
        displaySmall = base.displaySmall.copy(fontFamily = fontFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = fontFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = fontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.8).sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = fontFamily, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = fontFamily),
        titleMedium = base.titleMedium.copy(fontFamily = fontFamily),
        titleSmall = base.titleSmall.copy(fontFamily = fontFamily, fontFeatureSettings = "tnum"),
        bodyLarge = base.bodyLarge.copy(fontFamily = fontFamily),
        bodyMedium = base.bodyMedium.copy(fontFamily = fontFamily),
        bodySmall = base.bodySmall.copy(fontFamily = fontFamily),
        labelLarge = base.labelLarge.copy(fontFamily = fontFamily, letterSpacing = 0.4.sp),
        labelMedium = base.labelMedium.copy(fontFamily = fontFamily),
        labelSmall = base.labelSmall.copy(fontFamily = fontFamily, letterSpacing = 1.0.sp)
    )
}
@Composable
fun KestrelTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val locale = Locale.getDefault()
    val fontFamily = remember(locale.language) {
        if (locale.language == "fa") loadVazirmatnOrFallback(context) else FontFamily.SansSerif
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = KestrelShapes,
        typography = remember(fontFamily) { typographyWithFont(fontFamily) },
        content = content
    )
}
