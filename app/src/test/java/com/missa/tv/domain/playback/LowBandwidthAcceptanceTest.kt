package com.missa.tv.domain.playback

import com.google.common.truth.Truth.assertThat
import com.missa.tv.domain.bandwidth.ConnectionClass
import com.missa.tv.domain.bandwidth.QualityController
import com.missa.tv.domain.model.BandwidthSettings
import com.missa.tv.domain.model.QualityMode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Vérification de bout en bout du critère d'acceptation v1.0 :
 * **l'application doit rester utilisable sous 1 Mb/s**.
 *
 * Ces tests enchaînent les trois maillons réellement mis en œuvre à la lecture :
 * la mesure de débit, le choix du palier, puis les contraintes transmises au
 * moteur de lecture. Un test qui ne vérifierait que le premier maillon ne
 * prouverait pas que la consommation baisse réellement.
 */
@DisplayName("Critère d'acceptation : lecture sous 1 Mb/s")
class LowBandwidthAcceptanceTest {

    private val settings = BandwidthSettings()

    /** Horloge variable : le temps est simulé, aucun test n'attend réellement. */
    private class Horloge {
        var valeur: Long = 0L

        fun avancer(ms: Long) {
            valeur += ms
        }
    }

    private fun controleurFrais(horloge: Horloge): QualityController = QualityController(
        settings = settings,
        clockMs = { horloge.valeur },
    )

    private fun capsPour(mode: QualityMode): PlaybackCaps = PlaybackCaps.forMode(mode, settings)

    @Test
    @DisplayName("sous 1 Mb/s, l'application démarre en 480p sous 700 kb/s")
    fun `demarrage sous 1 Mbit`() {
        val horloge = Horloge()
        val controleur = controleurFrais(horloge)

        // 800 kb/s mesurés : c'est le cas visé par l'exigence.
        val classe = ConnectionClass.from(800, settings)
        val mode = controleur.initialMode(userMode = QualityMode.DEFAULT, connection = classe)

        assertThat(classe).isEqualTo(ConnectionClass.LOW)
        assertThat(mode).isEqualTo(QualityMode.ECONOMY_480)
        assertThat(capsPour(mode).maxHeightPx).isEqualTo(480)
        // La contrainte de débit reste sous la capacité mesurée : la lecture
        // garde une marge, elle ne sature pas la connexion.
        assertThat(capsPour(mode).effectiveMaxBitrateBps).isAtMost(800 * 1_000)
    }

    @Test
    @DisplayName("à 400 kb/s, deux coupures font descendre jusqu'au 360p")
    fun `connexion tres lente`() {
        val horloge = Horloge()
        val controleur = controleurFrais(horloge)
        controleur.initialMode(QualityMode.ECONOMY_480, ConnectionClass.LOW)

        horloge.avancer(1_000)
        assertThat(controleur.onRebuffering(locked = false)).isNull()
        horloge.avancer(1_000)
        assertThat(controleur.onRebuffering(locked = false)).isEqualTo(QualityMode.ULTRA_ECONOMY)

        // Le palier obtenu tient dans la connexion mesurée.
        assertThat(capsPour(QualityMode.ULTRA_ECONOMY).effectiveMaxBitrateBps)
            .isAtMost(400 * 1_000)
    }

    @Test
    @DisplayName("les coupures répétées finissent en audio seul, sans jamais échouer")
    fun `degradation complete`() {
        val horloge = Horloge()
        val controleur = controleurFrais(horloge)
        // Connexion encore inconnue : aucun rabais initial, on part donc du mode
        // automatique et la dégradation parcourt toute l'échelle.
        controleur.initialMode(QualityMode.AUTO_ECONOMY, ConnectionClass.UNKNOWN)

        val paliers = mutableListOf<QualityMode>()
        repeat(6) {
            horloge.avancer(500)
            controleur.onRebuffering(locked = false)?.let { palier -> paliers += palier }
        }

        // La dégradation s'arrête sur le mode audio seul : il n'y a pas d'échec,
        // l'utilisateur continue d'écouter la chaîne.
        assertThat(paliers).containsExactly(
            QualityMode.ECONOMY_480,
            QualityMode.ULTRA_ECONOMY,
            QualityMode.AUDIO_ONLY,
        ).inOrder()
        assertThat(capsPour(QualityMode.AUDIO_ONLY).videoDisabled).isTrue()
    }

    @Test
    @DisplayName("sur une bonne connexion, le mode par défaut tient sans dégradation")
    fun `bonne connexion`() {
        val horloge = Horloge()
        val controleur = controleurFrais(horloge)
        val mode = controleur.initialMode(QualityMode.AUTO_ECONOMY, ConnectionClass.GOOD)

        assertThat(mode).isEqualTo(QualityMode.AUTO_ECONOMY)

        // Des mesures largement suffisantes ne déclenchent aucune dégradation.
        repeat(5) {
            horloge.avancer(5_000)
            assertThat(controleur.onBandwidthSample(5_000, locked = false)).isNull()
        }
        assertThat(capsPour(mode).maxHeightPx).isEqualTo(720)
    }

    @Test
    @DisplayName("le verrouillage de l'utilisateur arrête toute adaptation")
    fun `verrouillage respecte`() {
        val horloge = Horloge()
        val controleur = controleurFrais(horloge)
        controleur.initialMode(QualityMode.AUTO_ECONOMY, ConnectionClass.LOW)

        // Verrouillé : ni dégradation sur coupures, ni remontée sur bonne mesure.
        repeat(4) {
            horloge.avancer(500)
            assertThat(controleur.onRebuffering(locked = true)).isNull()
        }
        horloge.avancer(120_000)
        assertThat(controleur.onBandwidthSample(20_000, locked = true)).isNull()
    }

    @Test
    @DisplayName("une amélioration durable fait remonter la qualité")
    fun `remontee apres stabilisation`() {
        val horloge = Horloge()
        val controleur = controleurFrais(horloge)
        controleur.initialMode(QualityMode.ECONOMY_480, ConnectionClass.LOW)

        // Le double du débit requis, tenu pendant toute la durée de stabilité.
        assertThat(controleur.onBandwidthSample(3_000, locked = false)).isNull()
        horloge.avancer(30_000)
        assertThat(controleur.onBandwidthSample(3_000, locked = false)).isNull()
        horloge.avancer(61_000)
        assertThat(controleur.onBandwidthSample(3_000, locked = false))
            .isEqualTo(QualityMode.AUTO_ECONOMY)
    }

    @Test
    @DisplayName("une coupure annule une remontée en cours")
    fun `coupure annule la remontee`() {
        val horloge = Horloge()
        val controleur = controleurFrais(horloge)
        controleur.initialMode(QualityMode.ECONOMY_480, ConnectionClass.LOW)

        controleur.onBandwidthSample(3_000, locked = false)
        horloge.avancer(60_000)
        // Une coupure survient avant la fin de la fenêtre de stabilité.
        controleur.onRebuffering(locked = false)
        horloge.avancer(60_000)

        // La remontée doit repartir de zéro, jamais s'appliquer rétroactivement.
        assertThat(controleur.onBandwidthSample(3_000, locked = false)).isNull()
    }
}
