package one.rarebit.cruciform.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Voidbind is **dark-first** and, for now, dark-only — the mockups are a single
 * committed dark design. The [MaterialTheme] colour scheme maps the two brand
 * accents (mint = primary/security, blue = secondary/brand) onto Material 3 roles;
 * components reach for [VbColors] directly for the brand specifics (amber recovery,
 * coral deny, tint washes) that do not fit a single scheme slot.
 */
private val CruciformDarkColorScheme = darkColorScheme(
    primary = VbColors.Mint,
    onPrimary = VbColors.OnMint,
    primaryContainer = VbColors.MintDim,
    onPrimaryContainer = VbColors.Mint,
    secondary = VbColors.Blue,
    onSecondary = VbColors.OnBlue,
    tertiary = VbColors.Amber,
    onTertiary = VbColors.OnAmber,
    background = VbColors.Background,
    onBackground = VbColors.TextPrimary,
    surface = VbColors.Surface,
    onSurface = VbColors.TextPrimary,
    surfaceVariant = VbColors.SurfaceElevated,
    onSurfaceVariant = VbColors.TextSecondary,
    outline = VbColors.Outline,
    outlineVariant = VbColors.OutlineSoft,
    error = VbColors.Coral,
    onError = Color(0xFF1A0505),
)

@Composable
fun CruciformTheme(
    // Kept for API symmetry; the app is dark-only today, so this is ignored.
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = CruciformDarkColorScheme,
        typography = CruciformTypography,
        content = content,
    )
}
