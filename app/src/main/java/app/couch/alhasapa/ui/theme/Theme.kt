package app.couch.alhasapa.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import app.couch.alhasapa.R

/*
 * Palette and roles come from the prototype (couch-artifact/index.html, :root):
 *   Ink  = text, rules, symbols, primary buttons
 *   Red  = Steve's piece (circle), the evening count, the one stamp
 *   Pink = Roni's piece (triangle), warm surfaces
 * Hierarchy on every screen: the game, then the action, then the small personal details.
 */
object Palette {
    val Cream = Color(0xFFF2EBDD)
    val Card = Color(0xFFF8F3E9)
    val Tint = Color(0xFFEAE1CF)
    val Ink = Color(0xFF30232D)
    val Ink2 = Color(0xFF67565F)
    val Red = Color(0xFFD94E38)
    val Pink = Color(0xFFE8A5B5)
    val Line = Color(0x2130232D) // rgba(48,35,45,.13)
    val Line2 = Color(0x4D30232D) // rgba(48,35,45,.30)
}

/** The one UI face. Bundled, never fetched. */
val PlexSansHebrew = FontFamily(
    Font(R.font.ibm_plex_sans_hebrew_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_hebrew_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_hebrew_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_hebrew_bold, FontWeight.Bold),
)

private fun Typography.withFamily(family: FontFamily) = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

private val AppColors = lightColorScheme(
    primary = Palette.Ink,
    onPrimary = Palette.Cream,
    secondary = Palette.Red,
    onSecondary = Palette.Cream,
    tertiary = Palette.Pink,
    onTertiary = Palette.Ink,
    background = Palette.Cream,
    onBackground = Palette.Ink,
    surface = Palette.Card,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Tint,
    onSurfaceVariant = Palette.Ink2,
    outline = Palette.Line2,
    outlineVariant = Palette.Line,
    error = Palette.Red,
    onError = Palette.Cream,
)

/** Light only (the prototype forces light), and always right-to-left: this is a Hebrew-only app. */
@Composable
fun AlHasapaTheme(content: @Composable () -> Unit) {
    // Referenced so a future dark scheme is a conscious decision, not an accident.
    @Suppress("UNUSED_VARIABLE") val systemDark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = AppColors,
            typography = Typography().withFamily(PlexSansHebrew),
            content = content,
        )
    }
}
