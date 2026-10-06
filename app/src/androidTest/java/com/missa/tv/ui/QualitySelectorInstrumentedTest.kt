package com.missa.tv.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.activity.ComponentActivity
import com.missa.tv.domain.bandwidth.ConnectionClass
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.QualityMode
import com.missa.tv.ui.adaptive.DeviceProfile
import com.missa.tv.ui.adaptive.DeviceType
import com.missa.tv.ui.adaptive.WindowWidthClass
import com.missa.tv.ui.player.PlayerUiState
import com.missa.tv.ui.player.QualitySelector
import com.missa.tv.ui.theme.MissaTvTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Test d'interface du sélecteur de qualité.
 *
 * Il s'exécute **sur un appareil ou un émulateur** : Compose a besoin d'une
 * fenêtre réelle, ce qu'un test JVM ne fournit pas. La CI compile ces tests pour
 * garantir qu'ils restent valides ; leur exécution fait partie de la recette
 * manuelle décrite dans `docs/TESTS_FAIBLE_DEBIT.md`.
 *
 * Ce qui est vérifié ici n'est pas cosmétique : c'est le parcours par lequel
 * l'utilisateur choisit réellement sa qualité, et le fait que l'application
 * annonce clairement les cas où réduire la qualité ne change rien à l'image.
 */
class QualitySelectorInstrumentedTest {

    @get:Rule
    val regle = createAndroidComposeRule<ComponentActivity>()

    private val appareilMobile = DeviceProfile(
        type = DeviceType.PHONE,
        widthClass = WindowWidthClass.COMPACT,
        isLandscape = false,
        hasTouchscreen = true,
        supportsPictureInPicture = true,
        fontScale = 1f,
    )

    private fun etat(
        mode: QualityMode = QualityMode.AUTO_ECONOMY,
        hauteurs: List<Int> = listOf(360, 720),
        verrouille: Boolean = false,
    ) = PlayerUiState(
        channel = Channel(id = "1", number = 1, name = "Chaîne de test", cmd = "x"),
        phase = PlayerUiState.Phase.Playing,
        qualityMode = mode,
        qualityLocked = verrouille,
        connectionClass = ConnectionClass.LOW,
        measuredKbps = 800,
        availableHeights = hauteurs,
    )

    @Test
    fun leSelecteurAfficheLesCinqModesEtLeDebitMesure() {
        var selection: Pair<QualityMode, Boolean>? = null
        regle.setContent {
            MissaTvTheme(deviceProfile = appareilMobile) {
                QualitySelector(
                    visible = true,
                    state = etat(),
                    device = appareilMobile,
                    onSelect = { mode, verrouille -> selection = mode to verrouille },
                    onClear = {},
                    onDismiss = {},
                )
            }
        }

        // Le débit réellement mesuré est affiché : l'utilisateur comprend les
        // décisions de l'application au lieu de les subir.
        regle.onNodeWithText("800", substring = true).assertIsDisplayed()

        listOf(
            QualityMode.AUTO_ECONOMY,
            QualityMode.ECONOMY_480,
            QualityMode.ULTRA_ECONOMY,
            QualityMode.MAX_QUALITY,
            QualityMode.AUDIO_ONLY,
        ).forEach { mode ->
            regle.onNodeWithText(libelle(mode), substring = true)
                .performScrollTo()
                .assertIsDisplayed()
        }

        regle.onNodeWithText(libelle(QualityMode.ULTRA_ECONOMY), substring = true).performClick()
        assertTrue("le mode choisi doit remonter à l'appelant", selection?.first == QualityMode.ULTRA_ECONOMY)
    }

    @Test
    fun uneChaineMonoqualiteEStAnnonceeCommeTelle() {
        regle.setContent {
            MissaTvTheme(deviceProfile = appareilMobile) {
                QualitySelector(
                    visible = true,
                    state = etat(hauteurs = listOf(720)),
                    device = appareilMobile,
                    onSelect = { _, _ -> },
                    onClear = {},
                    onDismiss = {},
                )
            }
        }

        // Promesse tenue : quand la source n'a qu'une qualité, l'application le
        // dit, plutôt que de laisser croire à une réduction de résolution.
        regle.onNodeWithText("une seule qualité", substring = true).assertIsDisplayed()
    }

    /** Libellé français attendu pour un mode, repris des ressources de l'application. */
    private fun libelle(mode: QualityMode): String = when (mode) {
        QualityMode.AUTO_ECONOMY -> "Auto"
        QualityMode.ECONOMY_480 -> "480"
        QualityMode.ULTRA_ECONOMY -> "360"
        QualityMode.MAX_QUALITY -> "maximale"
        QualityMode.AUDIO_ONLY -> "Audio"
    }
}
