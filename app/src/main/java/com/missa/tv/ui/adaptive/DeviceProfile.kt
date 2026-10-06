package com.missa.tv.ui.adaptive

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/** Type d'appareil sur lequel l'application s'exécute. */
enum class DeviceType {
    /** Android TV, boîtier ou clé HDMI : navigation à la télécommande. */
    TELEVISION,

    /** Tablette : mise en page à deux panneaux. */
    TABLET,

    /** Téléphone : navigation tactile, barre inférieure. */
    PHONE,

    /** Chromebook ou poste de bureau : fenêtres redimensionnables. */
    DESKTOP,
}

/** Classe de largeur de fenêtre, selon les seuils Material 3. */
enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED }

/**
 * Description de l'environnement d'exécution.
 *
 * Elle est recalculée à chaque recomposition : faire pivoter un téléphone,
 * redimensionner une fenêtre sur Chromebook ou passer en mode bureau déclenche
 * donc automatiquement la bonne mise en page.
 */
data class DeviceProfile(
    val type: DeviceType,
    val widthClass: WindowWidthClass,
    val isLandscape: Boolean,
    val hasTouchscreen: Boolean,
    val supportsPictureInPicture: Boolean,
    val fontScale: Float,
) {
    val isTv: Boolean get() = type == DeviceType.TELEVISION
    val isTablet: Boolean get() = type == DeviceType.TABLET
    val isPhone: Boolean get() = type == DeviceType.PHONE

    /** Sur TV, toute la navigation se fait au D-pad : le focus doit être visible. */
    val usesDpad: Boolean get() = isTv

    /** Affiche deux panneaux côte à côte (tablette, Chromebook, TV). */
    val usesTwoPane: Boolean get() = widthClass != WindowWidthClass.COMPACT
}

/**
 * Détection de l'appareil.
 *
 * Cette logique est volontairement séparée de Compose et sans dépendance
 * Android autre que [Context] : elle est ainsi directement testable.
 */
object DeviceProfileDetector {

    /** Seuils Material 3 : compact < 600 dp <= medium < 840 dp <= expanded. */
    fun windowWidthClass(widthDp: Int): WindowWidthClass = when {
        widthDp < 600 -> WindowWidthClass.COMPACT
        widthDp < 840 -> WindowWidthClass.MEDIUM
        else -> WindowWidthClass.EXPANDED
    }

    /**
     * Détecte un téléviseur ou un boîtier.
     *
     * Deux indices sont croisés : le mode d'interface déclaré par le système et
     * la présence de la fonctionnalité Leanback. Certains boîtiers n'exposent
     * que l'un des deux.
     */
    fun isTelevision(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val tvMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val leanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return tvMode || leanback
    }

    /**
     * Détecte un poste de bureau ou un Chromebook : grand écran avec pointeur
     * ou fenêtres redimensionnables.
     */
    fun isDesktop(context: Context, widthClass: WindowWidthClass): Boolean {
        if (widthClass == WindowWidthClass.COMPACT) return false
        val packageManager = context.packageManager
        val hasPc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.hasSystemFeature(PackageManager.FEATURE_PC)
        } else {
            false
        }
        return hasPc
    }

    fun detectType(context: Context, widthClass: WindowWidthClass): DeviceType = when {
        isTelevision(context) -> DeviceType.TELEVISION
        isDesktop(context, widthClass) -> DeviceType.DESKTOP
        widthClass == WindowWidthClass.COMPACT -> DeviceType.PHONE
        else -> DeviceType.TABLET
    }

    fun hasTouchscreen(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

    /**
     * Le Picture-in-Picture n'existe qu'à partir d'Android 7 et seulement si le
     * matériel le permet.
     */
    fun supportsPictureInPicture(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /**
     * Construit le profil à partir de valeurs simples plutôt que d'un objet
     * [Configuration] : la détection reste ainsi testable sans dépendre du
     * framework Android.
     */
    fun from(
        context: Context,
        screenWidthDp: Int,
        isLandscape: Boolean,
        fontScale: Float,
    ): DeviceProfile {
        val widthClass = windowWidthClass(screenWidthDp)
        return DeviceProfile(
            type = detectType(context, widthClass),
            widthClass = widthClass,
            isLandscape = isLandscape,
            hasTouchscreen = hasTouchscreen(context),
            supportsPictureInPicture = supportsPictureInPicture(context),
            fontScale = fontScale,
        )
    }
}

/**
 * Profil de l'appareil courant, recalculé dès que la configuration change
 * (rotation, redimensionnement de fenêtre, mode TV/bureau).
 */
@Composable
fun rememberDeviceProfile(): DeviceProfile {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(
        context,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        configuration.orientation,
        configuration.fontScale,
    ) {
        DeviceProfileDetector.from(
            context = context,
            screenWidthDp = configuration.screenWidthDp,
            isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            fontScale = configuration.fontScale,
        )
    }
}
