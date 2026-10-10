package com.missa.tv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme
import androidx.tv.material3.lightColorScheme as tvLightColorScheme
import com.missa.tv.ui.adaptive.DeviceProfile
import com.missa.tv.ui.adaptive.rememberDeviceProfile

/** Palette sombre : thème par défaut de l'application (accents du logo). */
private val DarkColorScheme = darkColorScheme(
    primary = MissaYellow,
    onPrimary = Color(0xFF201C00),
    primaryContainer = Color(0xFF4A4400),
    onPrimaryContainer = Color(0xFFFFEC62),
    secondary = MissaGreenClair,
    onSecondary = Color(0xFF003912),
    secondaryContainer = Color(0xFF0E5227),
    onSecondaryContainer = Color(0xFF9CF2B4),
    tertiary = MissaBlueClair,
    onTertiary = Color(0xFF003062),
    tertiaryContainer = Color(0xFF00468C),
    onTertiaryContainer = Color(0xFFD3E3FF),
    background = MissaBackground,
    onBackground = MissaOnSurface,
    surface = MissaSurface,
    onSurface = MissaOnSurface,
    surfaceVariant = MissaSurfaceVariant,
    onSurfaceVariant = MissaOnSurfaceVariant,
    error = MissaError,
    onError = MissaOnError,
)

/**
 * Palette sombre des composants TV (`androidx.tv.material3`).
 *
 * Les cartes et pastilles de télévision lisent leur propre `MaterialTheme`,
 * distinct de celui de `androidx.compose.material3` : sans ce thème jumeau,
 * elles retombent sur la palette claire par défaut et s'affichent blanches sur
 * le fond sombre — d'où ce thème appliqué en miroir du thème principal.
 */
private val TvDarkColorScheme = tvDarkColorScheme(
    primary = MissaYellow,
    onPrimary = Color(0xFF201C00),
    primaryContainer = Color(0xFF4A4400),
    onPrimaryContainer = Color(0xFFFFEC62),
    secondary = MissaGreenClair,
    onSecondary = Color(0xFF003912),
    secondaryContainer = Color(0xFF0E5227),
    onSecondaryContainer = Color(0xFF9CF2B4),
    tertiary = MissaBlueClair,
    onTertiary = Color(0xFF003062),
    tertiaryContainer = Color(0xFF00468C),
    onTertiaryContainer = Color(0xFFD3E3FF),
    background = MissaBackground,
    onBackground = MissaOnSurface,
    surface = MissaSurface,
    onSurface = MissaOnSurface,
    surfaceVariant = MissaSurfaceVariant,
    onSurfaceVariant = MissaOnSurfaceVariant,
    error = MissaError,
    onError = MissaOnError,
)

/** Palette claire des composants TV, miroir de la palette claire classique. */
private val TvLightColorScheme = tvLightColorScheme(
    primary = MissaBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001847),
    secondary = MissaGreenFonce,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBFF2CB),
    onSecondaryContainer = Color(0xFF002109),
    tertiary = MissaYellowFonce,
    onTertiary = Color.White,
    tertiaryContainer = MissaYellow,
    onTertiaryContainer = Color(0xFF211C00),
    background = MissaBackgroundLight,
    onBackground = MissaOnSurfaceLight,
    surface = MissaSurfaceLight,
    onSurface = MissaOnSurfaceLight,
    surfaceVariant = MissaSurfaceVariantLight,
    onSurfaceVariant = MissaOnSurfaceVariantLight,
    error = MissaErrorLight,
    onError = Color.White,
)

/** Palette claire : proposée sur téléphone et tablette (couleurs du logo). */
private val LightColorScheme = lightColorScheme(
    primary = MissaBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001847),
    secondary = MissaGreenFonce,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBFF2CB),
    onSecondaryContainer = Color(0xFF002109),
    tertiary = MissaYellowFonce,
    onTertiary = Color.White,
    tertiaryContainer = MissaYellow,
    onTertiaryContainer = Color(0xFF211C00),
    background = MissaBackgroundLight,
    onBackground = MissaOnSurfaceLight,
    surface = MissaSurfaceLight,
    onSurface = MissaOnSurfaceLight,
    surfaceVariant = MissaSurfaceVariantLight,
    onSurfaceVariant = MissaOnSurfaceVariantLight,
    error = MissaErrorLight,
    onError = Color.White,
)

/**
 * Thème de l'application.
 *
 * Deux adaptations automatiques selon l'appareil :
 *  - sur Android TV, le thème sombre est imposé et la typographie est agrandie
 *    de 30 % (interface « à trois mètres ») ;
 *  - ailleurs, le thème suit le réglage du système.
 *
 * L'agrandissement passe uniquement par `fontScale` : les tailles en `dp`
 * (zones tactiles, espacements) restent inchangées, ce qui évite de casser les
 * mises en page existantes.
 */
@Composable
fun MissaTvTheme(
    deviceProfile: DeviceProfile = rememberDeviceProfile(),
    darkTheme: Boolean = deviceProfile.isTv || isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val currentDensity = LocalDensity.current

    val adaptedDensity = remember(currentDensity, deviceProfile.isTv) {
        if (deviceProfile.isTv) {
            Density(
                density = currentDensity.density,
                fontScale = currentDensity.fontScale * TvFontScale,
            )
        } else {
            currentDensity
        }
    }

    val tvColorScheme = if (darkTheme) TvDarkColorScheme else TvLightColorScheme

    CompositionLocalProvider(LocalDensity provides adaptedDensity) {
        // Le thème TV est appliqué en miroir : les composants
        // `androidx.tv.material3` (cartes de chaînes, pastilles) lisent leur
        // propre MaterialTheme et ignoreraient la palette ci-dessous.
        TvMaterialTheme(colorScheme = tvColorScheme) {
            MaterialTheme(
                colorScheme = colorScheme,
                typography = MissaTypography,
                content = content,
            )
        }
    }
}
