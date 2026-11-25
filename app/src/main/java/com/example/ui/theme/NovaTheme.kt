/*
 * Derived from the JARVIS project (com.jarvis.assistant.util.ThemeManager and the Theme.JarvisAssistant.* styles in
 * res/values/themes.xml: dark / light / red / sunset token values). Original attribution and license information:
 * see THIRD_PARTY_NOTICES.md (JARVIS AI by Tech GPTx, TAMCL v1.0).
 * Rebuilt for NOVA AI as Compose tokens; text/accent colours are contrast-corrected to WCAG AA where the original
 * values fell short. New code © ARPON.
 */
package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

enum class NovaThemeId(val key: String, val isLight: Boolean) {
    DARK("dark", false), LIGHT("light", true), RED("red", false), SUNSET("sunset", true)
}

/** What the user picked: follow the app's light/dark/system mode, or force one of the four looks. */
enum class NovaThemeChoice(val key: String) {
    AUTO("auto"), DARK("dark"), LIGHT("light"), RED("red"), SUNSET("sunset");

    companion object {
        fun fromKey(k: String?): NovaThemeChoice = entries.firstOrNull { it.key == k } ?: AUTO
    }
}

/** Pure colour maths (no Compose runtime needed → JVM-testable). */
object NovaColor {
    private fun channel(c: Float): Double { val v = c.toDouble(); return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }

    fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    /** WCAG contrast ratio (1..21) of two opaque colours. */
    fun contrast(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        val hi = maxOf(la, lb); val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** Alpha-composites [fg] over the opaque [bg]. */
    fun over(fg: Color, bg: Color): Color {
        val a = fg.alpha
        return Color(fg.red * a + bg.red * (1 - a), fg.green * a + bg.green * (1 - a), fg.blue * a + bg.blue * (1 - a), 1f)
    }

    /** Moves [fg] toward white (dark bg) or black (light bg) in small steps until it reaches [min] contrast on [bg]. */
    fun ensureContrast(fg: Color, bg: Color, min: Double): Color {
        if (contrast(fg, bg) >= min) return fg
        val target = if (luminance(bg) > 0.5) Color.Black else Color.White
        var t = 0.04f
        var c = fg
        while (t <= 1f) {
            c = Color(fg.red + (target.red - fg.red) * t, fg.green + (target.green - fg.green) * t, fg.blue + (target.blue - fg.blue) * t, 1f)
            if (contrast(c, bg) >= min) return c
            t += 0.04f
        }
        return target
    }

    private val NEAR_BLACK = Color(0xFF0F172A)

    /** White or near-black, whichever reads better on [bg]. */
    fun bestOn(bg: Color): Color = if (contrast(Color.White, bg) >= contrast(NEAR_BLACK, bg)) Color.White else NEAR_BLACK
}

@Immutable
data class NovaTokens(
    val id: NovaThemeId,
    val isLight: Boolean,
    val bg: Color,
    val surface: Color,          // opaque card/surface colour (glass composited over bg)
    val cardBorder: Color,
    val cardGlassStart: Color,   // translucent, for glass gradients
    val cardGlassEnd: Color,
    val dockBg: Color,
    val dockGlassStart: Color,
    val dockGlassEnd: Color,
    val dockBorder: Color,
    val glassSpecular: Color,
    val chipBg: Color,
    val inputBg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent1: Color,          // brand accent for fills
    val accent2: Color,
    val accent3: Color,
    val accentLight: Color,
    val onAccent: Color,
    val accentText: Color        // accent usable as TEXT/icon colour on bg (>= 4.5:1)
)

object NovaThemes {
    private fun c(argb: Long) = Color(argb)

    private fun build(
        id: NovaThemeId, bg: Long, textP: Long, textS: Long, textM: Long, a1: Long, a2: Long, a3: Long, aLight: Long,
        cardBg: Long, cardBorder: Long, glassS: Long, glassE: Long, dockBg: Long, dockS: Long, dockE: Long, dockBorder: Long,
        specular: Long, chip: Long, input: Long
    ): NovaTokens {
        val bgC = c(bg)
        val accent = c(a1)
        return NovaTokens(
            id = id, isLight = id.isLight, bg = bgC,
            surface = NovaColor.over(c(cardBg), bgC),
            cardBorder = c(cardBorder), cardGlassStart = c(glassS), cardGlassEnd = c(glassE),
            dockBg = c(dockBg), dockGlassStart = c(dockS), dockGlassEnd = c(dockE), dockBorder = c(dockBorder),
            glassSpecular = c(specular), chipBg = c(chip), inputBg = c(input),
            textPrimary = NovaColor.ensureContrast(c(textP), bgC, 7.0),
            textSecondary = NovaColor.ensureContrast(c(textS), bgC, 4.5),
            textMuted = NovaColor.ensureContrast(c(textM), bgC, 4.5),   // JARVIS muted text was ~4.2:1
            accent1 = accent, accent2 = c(a2), accent3 = c(a3), accentLight = c(aLight),
            onAccent = NovaColor.bestOn(accent),                         // JARVIS used white (3.2:1 / 2.8:1 on dark/red/sunset accents)
            accentText = NovaColor.ensureContrast(accent, bgC, 4.5)
        )
    }

    val Dark = build(NovaThemeId.DARK, 0xFF070914, 0xFFF8FAFC, 0xFF94A3B8, 0xFF64748B, 0xFF5B8CFF, 0xFFA78BFA, 0xFF22D3EE, 0xFF1E2858,
        0x33141B3D, 0x4D5B8CFF, 0x4D1B2654, 0x1A0F1733, 0x660F1535, 0x80172048, 0x4D0B1028, 0x665B8CFF, 0xFF80A5FF, 0x331A244E, 0x33141B3D)
    val Light = build(NovaThemeId.LIGHT, 0xFFEEF2F9, 0xFF0F172A, 0xFF475569, 0xFF64748B, 0xFF2F6BFF, 0xFF8B5CF6, 0xFF06B6D4, 0xFFE0E7FF,
        0xB8FFFFFF, 0xFFCBD5E1, 0xF0FFFFFF, 0xA6FFFFFF, 0xCCFFFFFF, 0xFAFFFFFF, 0xB8FFFFFF, 0xFFCBD5E1, 0xFFFFFFFF, 0x66FFFFFF, 0xB8FFFFFF)
    val Red = build(NovaThemeId.RED, 0xFF0E0307, 0xFFFFF1F2, 0xFFFDA4AF, 0xFFBE727E, 0xFFFF4D5E, 0xFFFB7185, 0xFFF97316, 0xFF3A0C16,
        0x332D0A13, 0x59FF4D5E, 0x4D3A0F1A, 0x1A1A050D, 0x6624060E, 0x80300A14, 0x4D170409, 0x66FF4D5E, 0xFFFFA6B0, 0x33380D17, 0x332D0A13)
    val Sunset = build(NovaThemeId.SUNSET, 0xFFFFF0EA, 0xFF2E1020, 0xFF783A5B, 0xFF9C6282, 0xFFFF6A3D, 0xFFF43F5E, 0xFF8B5CF6, 0xFFFFE6DD,
        0xCCFFF2EC, 0x402E1020, 0xF5FFFFFF, 0xB3FFE2D7, 0xE6FFF0EB, 0xFAFFFFFF, 0xCCFFE8DF, 0x402E1020, 0xFFFFFFFF, 0x80FFFFFF, 0xCCFFF2EC)

    fun tokens(id: NovaThemeId): NovaTokens = when (id) {
        NovaThemeId.DARK -> Dark; NovaThemeId.LIGHT -> Light; NovaThemeId.RED -> Red; NovaThemeId.SUNSET -> Sunset
    }

    /** AUTO follows the existing light/dark/system setting; explicit choices win. */
    fun resolve(choice: NovaThemeChoice, themeMode: String, systemDark: Boolean): NovaThemeId = when (choice) {
        NovaThemeChoice.DARK -> NovaThemeId.DARK
        NovaThemeChoice.LIGHT -> NovaThemeId.LIGHT
        NovaThemeChoice.RED -> NovaThemeId.RED
        NovaThemeChoice.SUNSET -> NovaThemeId.SUNSET
        NovaThemeChoice.AUTO -> when (themeMode) {
            "dark" -> NovaThemeId.DARK
            "light" -> NovaThemeId.LIGHT
            else -> if (systemDark) NovaThemeId.DARK else NovaThemeId.LIGHT
        }
    }

    /**
     * Material colour scheme for a token set, so every existing screen adopts the look without edits. All surfaces are
     * OPAQUE (glass composited over the background): translucent surfaces would make dialogs and sheets see-through.
     */
    fun colorScheme(t: NovaTokens): ColorScheme {
        val s = t.surface
        fun lift(f: Float): Color = NovaColor.over(t.accent1.copy(alpha = f), s)
        val primary = t.accentText
        val onPrimary = NovaColor.bestOn(primary)
        val error = NovaColor.ensureContrast(Color(0xFFEF4444), t.bg, 4.5)
        return if (t.isLight) lightColorScheme(
            primary = primary, onPrimary = onPrimary, primaryContainer = t.accentLight, onPrimaryContainer = NovaColor.bestOn(t.accentLight),
            secondary = NovaColor.ensureContrast(t.accent2, t.bg, 4.5), onSecondary = Color.White,
            tertiary = NovaColor.ensureContrast(t.accent3, t.bg, 4.5), onTertiary = Color.White,
            background = t.bg, onBackground = t.textPrimary, surface = t.bg, onSurface = t.textPrimary,
            surfaceVariant = s, onSurfaceVariant = t.textSecondary, outline = t.cardBorder, outlineVariant = t.cardBorder,
            surfaceContainerLowest = t.bg, surfaceContainerLow = s, surfaceContainer = s, surfaceContainerHigh = lift(0.04f), surfaceContainerHighest = lift(0.08f),
            error = error, onError = NovaColor.bestOn(error), errorContainer = NovaColor.over(error.copy(alpha = 0.16f), t.bg), onErrorContainer = t.textPrimary
        ) else darkColorScheme(
            primary = primary, onPrimary = onPrimary, primaryContainer = t.accentLight, onPrimaryContainer = t.textPrimary,
            secondary = t.accent2, onSecondary = NovaColor.bestOn(t.accent2),
            tertiary = t.accent3, onTertiary = NovaColor.bestOn(t.accent3),
            background = t.bg, onBackground = t.textPrimary, surface = t.bg, onSurface = t.textPrimary,
            surfaceVariant = s, onSurfaceVariant = t.textSecondary, outline = NovaColor.over(t.cardBorder, t.bg), outlineVariant = NovaColor.over(t.cardBorder, t.bg),
            surfaceContainerLowest = t.bg, surfaceContainerLow = s, surfaceContainer = s, surfaceContainerHigh = lift(0.06f), surfaceContainerHighest = lift(0.12f),
            error = error, onError = NovaColor.bestOn(error), errorContainer = NovaColor.over(error.copy(alpha = 0.2f), t.bg), onErrorContainer = t.textPrimary
        )
    }
}

val LocalNovaTokens = staticCompositionLocalOf { NovaThemes.Dark }
