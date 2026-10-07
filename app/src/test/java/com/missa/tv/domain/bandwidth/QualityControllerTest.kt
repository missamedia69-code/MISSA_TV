package com.missa.tv.domain.bandwidth

import com.google.common.truth.Truth.assertThat
import com.missa.tv.domain.model.BandwidthSettings
import com.missa.tv.domain.model.QualityMode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests de la politique de qualité adaptative.
 *
 * L'horloge du contrôleur est remplacée par une horloge manuelle : les règles
 * de fenêtre temporelle (60 s, 90 s) sont donc vérifiées sans faire attendre les
 * tests, et de façon déterministe.
 */
class QualityControllerTest {

    private var maintenant = 1_000L

    private fun controller(settings: BandwidthSettings = BandwidthSettings()) =
        QualityController(settings = settings, clockMs = { maintenant })

    @Nested
    @DisplayName("Choix du palier au démarrage")
    inner class Demarrage {

        @Test
        fun `conserve le mode choisi quand la connexion est bonne`() {
            val controleur = controller()
            val mode = controleur.initialMode(QualityMode.AUTO_ECONOMY, ConnectionClass.GOOD)
            assertThat(mode).isEqualTo(QualityMode.AUTO_ECONOMY)
        }

        @Test
        fun `applique le profil economie sur une connexion faible`() {
            val controleur = controller()
            val mode = controleur.initialMode(QualityMode.AUTO_ECONOMY, ConnectionClass.LOW)
            assertThat(mode).isEqualTo(QualityMode.ECONOMY_480)
        }

        @Test
        fun `respecte la qualite maximale meme sur connexion faible`() {
            // Choix explicite de l'utilisateur : l'application ne le corrige pas.
            val controleur = controller()
            val mode = controleur.initialMode(QualityMode.MAX_QUALITY, ConnectionClass.LOW)
            assertThat(mode).isEqualTo(QualityMode.MAX_QUALITY)
        }

        @Test
        fun `demarre prudemment quand le debit est inconnu`() {
            val controleur = controller()
            val mode = controleur.initialMode(QualityMode.AUTO_ECONOMY, ConnectionClass.UNKNOWN)
            assertThat(mode).isEqualTo(QualityMode.AUTO_ECONOMY)
        }
    }

    @Nested
    @DisplayName("Dégradation automatique")
    inner class Degradation {

        @Test
        fun `une seule saccade ne change pas le palier`() {
            val controleur = controller()
            controleur.initialMode(QualityMode.AUTO_ECONOMY, ConnectionClass.GOOD)

            val resultat = controleur.onRebuffering(locked = false)

            assertThat(resultat).isNull()
        }

        @Test
        fun `deux saccades en moins de 60 s descendent d'un palier`() {
            val controleur = controller()
            controleur.initialMode(QualityMode.AUTO_ECONOMY, ConnectionClass.GOOD)

            controleur.onRebuffering(locked = false)
            maintenant += 10_000
            val resultat = controleur.onRebuffering(locked = false)

            assertThat(resultat).isEqualTo(QualityMode.ECONOMY_480)
        }

        @Test
        fun `deux saccades espacees de plus de 60 s ne declenchent rien`() {
            val controleur = controller()
            controleur.initialMode(QualityMode.AUTO_ECONOMY, ConnectionClass.GOOD)

            controleur.onRebuffering(locked = false)
            maintenant += QualityController.REBUFFERING_WINDOW_MS + 1
            val resultat = controleur.onRebuffering(locked = false)

            assertThat(resultat).isNull()
        }

        @Test
        fun `le mode verrouille desactive la degradation`() {
            val controleur = controller()
            controleur.initialMode(QualityMode.ECONOMY_480, ConnectionClass.LOW)

            controleur.onRebuffering(locked = true)
            val resultat = controleur.onRebuffering(locked = true)

            assertThat(resultat).isNull()
        }

        @Test
        fun `descend palier par palier jusqu'au son seul`() {
            val controleur = controller()
            controleur.initialMode(QualityMode.AUTO_ECONOMY, ConnectionClass.LOW)

            val premier = controleur.onRebuffering(locked = false).let {
                controleur.onRebuffering(locked = false)
            }
            val deuxieme = controleur.onRebuffering(locked = false).let {
                controleur.onRebuffering(locked = false)
            }
            val troisieme = controleur.onRebuffering(locked = false).let {
                controleur.onRebuffering(locked = false)
            }

            // ECONOMY_480 -> ULTRA_ECONOMY -> AUDIO_ONLY
            assertThat(premier).isEqualTo(QualityMode.ULTRA_ECONOMY)
            assertThat(deuxieme).isEqualTo(QualityMode.AUDIO_ONLY)
            // On ne descend pas plus bas que le son seul.
            assertThat(troisieme).isNull()
        }
    }

    @Nested
    @DisplayName("Remontée automatique")
    inner class Remontee {

        @Test
        fun `remonte apres 90 s de debit confortable`() {
            val controleur = controller()
            controleur.initialMode(QualityMode.ECONOMY_480, ConnectionClass.LOW)

            // Premier échantillon : la remontée est candidate mais pas encore acquise.
            val premier = controleur.onBandwidthSample(measuredKbps = 5_000, locked = false)
            assertThat(premier).isNull()

            maintenant += QualityController.UPGRADE_STABLE_DURATION_MS
            val second = controleur.onBandwidthSample(measuredKbps = 5_000, locked = false)

            assertThat(second).isEqualTo(QualityMode.AUTO_ECONOMY)
        }

        @Test
        fun `ne remonte pas si le debit reste juste suffisant`() {
            val controleur = controller()
            controleur.initialMode(QualityMode.ECONOMY_480, ConnectionClass.LOW)

            controleur.onBandwidthSample(measuredKbps = 1_000, locked = false)
            maintenant += QualityController.UPGRADE_STABLE_DURATION_MS
            val resultat = controleur.onBandwidthSample(measuredKbps = 1_000, locked = false)

            assertThat(resultat).isNull()
        }

        @Test
        fun `une saccade annule la remontee en cours`() {
            val controleur = controller()
            controleur.initialMode(QualityMode.ECONOMY_480, ConnectionClass.LOW)

            controleur.onBandwidthSample(measuredKbps = 5_000, locked = false)
            maintenant += 30_000
            controleur.onRebuffering(locked = false)
            maintenant += QualityController.UPGRADE_STABLE_DURATION_MS
            val resultat = controleur.onBandwidthSample(measuredKbps = 5_000, locked = false)

            assertThat(resultat).isNull()
        }

        @Test
        fun `le mode verrouille desactive la remontee`() {
            val controleur = controller()
            controleur.initialMode(QualityMode.ECONOMY_480, ConnectionClass.LOW)

            controleur.onBandwidthSample(measuredKbps = 9_000, locked = true)
            maintenant += QualityController.UPGRADE_STABLE_DURATION_MS
            val resultat = controleur.onBandwidthSample(measuredKbps = 9_000, locked = true)

            assertThat(resultat).isNull()
        }

        @Test
        fun `ne remonte jamais au-dela du plafond choisi`() {
            // AUTO_ECONOMY est le plus haut palier proposé : il n'y a rien au-dessus.
            val controleur = controller()
            controleur.initialMode(QualityMode.AUTO_ECONOMY, ConnectionClass.GOOD)

            controleur.onBandwidthSample(measuredKbps = 50_000, locked = false)
            maintenant += QualityController.UPGRADE_STABLE_DURATION_MS
            val resultat = controleur.onBandwidthSample(measuredKbps = 50_000, locked = false)

            assertThat(resultat).isNull()
        }
    }

    @Nested
    @DisplayName("Débit nécessaire par palier")
    inner class DebitNecessaire {

        @Test
        fun `le debit necessaire croit avec la qualite`() {
            val reglages = BandwidthSettings()

            val audioSeul = reglages.requiredKbpsFor(QualityMode.AUDIO_ONLY)
            val ultra = reglages.requiredKbpsFor(QualityMode.ULTRA_ECONOMY)
            val economie = reglages.requiredKbpsFor(QualityMode.ECONOMY_480)
            val auto = reglages.requiredKbpsFor(QualityMode.AUTO_ECONOMY)

            assertThat(audioSeul).isLessThan(ultra)
            assertThat(ultra).isLessThan(economie)
            assertThat(economie).isLessThan(auto)
        }

        @Test
        fun `le debit necessaire tient compte des surcharges distantes`() {
            val reglages = BandwidthSettings(
                maxVideoBitrateByMode = mapOf(QualityMode.ECONOMY_480 to 200_000),
            )

            val necessaire = reglages.requiredKbpsFor(QualityMode.ECONOMY_480)

            // 200 kb/s majorés de la marge de sécurité (1 / 0,7).
            assertThat(necessaire).isEqualTo(285)
        }
    }
}
