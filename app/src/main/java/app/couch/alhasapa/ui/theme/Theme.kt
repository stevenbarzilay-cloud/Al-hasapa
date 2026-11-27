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
    val Line = Color(0x2130232D) // rgba(48,35,45,.13)
    val Line2 = Color(0x4D30232D) // rgba(48,35,45,.30)

    private val red = androidx.compose.runtime.mutableStateOf(Color(0xFFD94E38))
    private val pink = androidx.compose.runtime.mutableStateOf(Color(0xFFE8A5B5))

    /**
     * The first person's colour, their piece's accent (the hub's --red): the focus, the evening count, the stamps. It follows the pieces
     * (1.12), on every screen at once (a screen reading it draws again when it changes); the first person's default is this red.
     */
    val Red: Color get() = red.value

    /** The second person's soft tone (the hub's --pink): warm surfaces. Follows the pieces too; the second's default is this pink. */
    val Pink: Color get() = pink.value

    /** The colours of the first ([a]) and the second ([b]) of us, as the hub takes them (see its applyTheme). Main thread. */
    fun follow(a: app.couch.alhasapa.data.PieceStyle, b: app.couch.alhasapa.data.PieceStyle) {
        if (red.value != a.accent) red.value = a.accent
        if (pink.value != b.soft) pink.value = b.soft
    }
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

/** The scheme for the two people's colours as they are now ([red], [pink]: see [Palette.follow]). */
private fun appColors(red: Color, pink: Color) = lightColorScheme(
    primary = Palette.Ink,
    onPrimary = Palette.Cream,
    secondary = red,
    onSecondary = Palette.Cream,
    tertiary = pink,
    onTertiary = Palette.Ink,
    background = Palette.Cream,
    onBackground = Palette.Ink,
    surface = Palette.Card,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Tint,
    onSurfaceVariant = Palette.Ink2,
    outline = Palette.Line2,
    outlineVariant = Palette.Line,
    error = red,
    onError = Palette.Cream,
)

/** Light only (the prototype forces light), and always right-to-left: this is a Hebrew-only app. */
@Composable
fun AlHasapaTheme(content: @Composable () -> Unit) {
    // Referenced so a future dark scheme is a conscious decision, not an accident.
    @Suppress("UNUSED_VARIABLE") val systemDark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        // Read here, so every screen draws again when one of us picks another colour (1.12).
        val red = Palette.Red
        val pink = Palette.Pink
        MaterialTheme(
            colorScheme = androidx.compose.runtime.remember(red, pink) { appColors(red, pink) },
            typography = Typography().withFamily(PlexSansHebrew),
            content = content,
        )
    }
}
