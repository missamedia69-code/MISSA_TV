package com.missa.tv.ui.adaptive

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Détection de l'environnement d'exécution.
 *
 * Ces tests portent sur la logique qui décide de l'interface : elle doit être
 * fiable, car une erreur de détection donne une application inutilisable (barre
 * tactile sur un téléviseur, ou grille non navigable au D-pad).
 */
class DeviceProfileDetectorTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var uiModeManager: UiModeManager

    @BeforeEach
    fun preparer() {
        context = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        uiModeManager = mockk(relaxed = true)

        every { context.packageManager } returns packageManager
        every { context.getSystemService(Context.UI_MODE_SERVICE) } returns uiModeManager
        every { uiModeManager.currentModeType } returns Configuration.UI_MODE_TYPE_NORMAL
        every { packageManager.hasSystemFeature(any()) } returns false
    }

    @Nested
    @DisplayName("Classes de largeur de fenêtre")
    inner class LargeurFenetre {

        @ParameterizedTest(name = "{0} dp -> {1}")
        @CsvSource(
            "320, COMPACT",
            "411, COMPACT",
            "599, COMPACT",
            "600, MEDIUM",
            "700, MEDIUM",
            "839, MEDIUM",
            "840, EXPANDED",
            "1280, EXPANDED",
        )
        fun `applique les seuils Material 3`(largeurDp: Int, attendu: WindowWidthClass) {
            assertThat(DeviceProfileDetector.windowWidthClass(largeurDp)).isEqualTo(attendu)
        }
    }

    @Nested
    @DisplayName("Type d'appareil")
    inner class TypeAppareil {

        @Test
        fun `un telegrapheur est detecte par le mode d_interface`() {
            every { uiModeManager.currentModeType } returns Configuration.UI_MODE_TYPE_TELEVISION

            assertThat(DeviceProfileDetector.isTelevision(context)).isTrue()
        }

        @Test
        fun `un boitier est detecte par la fonctionnalite leanback`() {
            // Certains boîtiers n'annoncent pas le mode télévision mais exposent
            // bien Leanback.
            every { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) } returns true

            assertThat(DeviceProfileDetector.isTelevision(context)).isTrue()
        }

        @Test
        fun `un telephone n_est pas une television`() {
            assertThat(DeviceProfileDetector.isTelevision(context)).isFalse()
        }

        @Test
        fun `un petit ecran est un telephone`() {
            val type = DeviceProfileDetector.detectType(context, WindowWidthClass.COMPACT)
            assertThat(type).isEqualTo(DeviceType.PHONE)
        }

        @Test
        fun `un grand ecran non televisuel est une tablette`() {
            val type = DeviceProfileDetector.detectType(context, WindowWidthClass.EXPANDED)
            assertThat(type).isEqualTo(DeviceType.TABLET)
        }

        @Test
        fun `la television prime sur la taille de l_ecran`() {
            every { uiModeManager.currentModeType } returns Configuration.UI_MODE_TYPE_TELEVISION

            val type = DeviceProfileDetector.detectType(context, WindowWidthClass.EXPANDED)

            assertThat(type).isEqualTo(DeviceType.TELEVISION)
        }
    }

    @Nested
    @DisplayName("Capacités de l'appareil")
    inner class Capacites {

        @Test
        fun `le picture-in-picture depend du materiel`() {
            every {
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            } returns true

            val profil = DeviceProfileDetector.from(
                context = context,
                screenWidthDp = 411,
                isLandscape = false,
                fontScale = 1f,
                sdkInt = Build.VERSION_CODES.TIRAMISU,
            )

            assertThat(profil.supportsPictureInPicture).isTrue()
        }

        @Test
        fun `pas de picture-in-picture avant Android 7 meme si le materiel le permet`() {
            // Le Picture-in-Picture n'existe qu'à partir de l'API 24 : sur une
            // version antérieure, l'application ne doit pas proposer l'option.
            every {
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            } returns true

            val profil = DeviceProfileDetector.from(
                context = context,
                screenWidthDp = 411,
                isLandscape = false,
                fontScale = 1f,
                sdkInt = Build.VERSION_CODES.M,
            )

            assertThat(profil.supportsPictureInPicture).isFalse()
        }

        @Test
        fun `un poste de bureau n_est reconnu qu_a partir d_Android 13`() {
            every { packageManager.hasSystemFeature(PackageManager.FEATURE_PC) } returns true

            assertThat(
                DeviceProfileDetector.detectType(
                    context = context,
                    widthClass = WindowWidthClass.EXPANDED,
                    sdkInt = Build.VERSION_CODES.TIRAMISU,
                ),
            ).isEqualTo(DeviceType.DESKTOP)

            assertThat(
                DeviceProfileDetector.detectType(
                    context = context,
                    widthClass = WindowWidthClass.EXPANDED,
                    sdkInt = Build.VERSION_CODES.M,
                ),
            ).isEqualTo(DeviceType.TABLET)
        }

        @Test
        fun `l_ecran tactile est rapporte tel quel`() {
            every { packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) } returns true

            assertThat(DeviceProfileDetector.hasTouchscreen(context)).isTrue()
        }

        @Test
        fun `le profil complet reprend la configuration fournie`() {
            val profil = DeviceProfileDetector.from(
                context = context,
                screenWidthDp = 1280,
                isLandscape = true,
                fontScale = 1.3f,
            )

            assertThat(profil.widthClass).isEqualTo(WindowWidthClass.EXPANDED)
            assertThat(profil.isLandscape).isTrue()
            assertThat(profil.fontScale).isEqualTo(1.3f)
            assertThat(profil.isTablet).isTrue()
        }
    }

    @Nested
    @DisplayName("Conséquences sur l'interface")
    inner class ConsequencesInterface {

        @Test
        fun `la television impose la navigation au d-pad`() {
            every { uiModeManager.currentModeType } returns Configuration.UI_MODE_TYPE_TELEVISION

            val profil = DeviceProfileDetector.from(context, 960, true, 1f)

            assertThat(profil.usesDpad).isTrue()
            assertThat(profil.isTv).isTrue()
        }

        @Test
        fun `un telephone compact n_utilise pas deux panneaux`() {
            val profil = DeviceProfileDetector.from(context, 411, false, 1f)

            assertThat(profil.usesTwoPane).isFalse()
        }

        @Test
        fun `une tablette utilise deux panneaux`() {
            val profil = DeviceProfileDetector.from(context, 800, true, 1f)

            assertThat(profil.usesTwoPane).isTrue()
        }
    }
}
