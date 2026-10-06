package com.missa.tv.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

/**
 * Typographie de l'application.
 *
 * Les tailles ne sont pas fixées ici : elles découlent de l'échelle de police
 * globale (voir `MissaTvTheme`). Sur Android TV, toute la typographie est
 * agrandie de 30 % pour rester lisible à trois mètres de l'écran. Seules les
 * graisses sont donc définies ci-dessous.
 */
val MissaTypography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
    )
}

/** Facteur d'agrandissement de la typographie sur téléviseur (interface à 3 m). */
const val TvFontScale: Float = 1.3f
