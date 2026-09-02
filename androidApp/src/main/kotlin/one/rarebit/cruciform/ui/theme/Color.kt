package one.rarebit.cruciform.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The Voidbind palette, taken from the product mockups: a pure near-black ground,
 * layered dark cards, and a two-accent system —
 *
 *  - **mint/teal** = security, "protected", positive confirmation, primary CTAs;
 *  - **blue** = the brand mark and the Home identity;
 *  - **amber** = recovery (write-this-down) warnings;
 *  - **coral** = destructive / deny.
 *
 * These are referenced directly (a single fixed dark theme) as well as mapped into
 * a Material 3 [androidx.compose.material3.ColorScheme] in [CruciformTheme].
 */
object VbColors {
    // Ground + surfaces
    val Background = Color(0xFF060809)
    val Surface = Color(0xFF101418)
    val SurfaceElevated = Color(0xFF161B21)
    val SurfaceSunken = Color(0xFF0B0E11)
    val Outline = Color(0xFF232A31)
    val OutlineSoft = Color(0xFF1A2026)

    // Text
    val TextPrimary = Color(0xFFF4F7FA)
    val TextSecondary = Color(0xFFAAB4BD)
    val TextMuted = Color(0xFF747E87)

    // Accents
    val Mint = Color(0xFF5FE7C7)
    val MintDim = Color(0xFF2E5C51)
    val Blue = Color(0xFF3E9BFF)
    val BlueSoft = Color(0xFF8ED0F0) // the light "Approve" fill
    val Amber = Color(0xFFF5B301)
    val AmberDim = Color(0xFF4A3A0E)
    val Coral = Color(0xFFFF6B6B)
    val CoralDim = Color(0xFF4A2222)

    // Tint washes for status cards (semi-transparent over the ground)
    val MintWash = Color(0x145FE7C7)
    val AmberWash = Color(0x14F5B301)
    val BlueWash = Color(0x143E9BFF)

    // On-accent text
    val OnMint = Color(0xFF041712)
    val OnBlue = Color(0xFF03121F)
    val OnAmber = Color(0xFF241A00)
}
