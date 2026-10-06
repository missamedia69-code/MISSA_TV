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
import com.missa.tv.ui.adaptive.DeviceProfile
import com.missa.tv.ui.adaptive.rememberDeviceProfile

/** Palette sombre : thème par défaut de l'application. */
private val DarkColorScheme = darkColorScheme(
    primary = MissaRed,
    onPrimary = Color.White,
    primaryContainer = MissaRedDark,
    onPrimaryContainer = Color.White,
    secondary = MissaBlue,
    onSecondary = Color.White,
    background = MissaBackground,
    onBackground = MissaOnSurface,
    surface = MissaSurface,
    onSurface = MissaOnSurface,
    surfaceVariant = MissaSurfaceVariant,
    onSurfaceVariant = MissaOnSurfaceVariant,
    error = MissaError,
    onError = MissaOnError,
)

/** Palette claire : proposée sur téléphone et tablette. */
private val LightColorScheme = lightColorScheme(
    primary = MissaRedDark,
    onPrimary = Color.White,
    secondary = MissaBlue,
    onSecondary = Color.White,
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

    CompositionLocalProvider(LocalDensity provides adaptedDensity) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MissaTypography,
            content = content,
        )
    }
}
