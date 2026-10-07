package com.missa.tv.domain.playback

import com.google.common.truth.Truth.assertThat
import com.missa.tv.domain.model.BandwidthSettings
import com.missa.tv.domain.model.QualityMode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Contraintes transmises au lecteur.
 *
 * C'est la traduction concrète de l'exigence « mode faible débit » : ces valeurs
 * sont celles qui plafonnent réellement ce qui est téléchargé.
 */
@DisplayName("Contraintes de lecture par mode")
class PlaybackCapsTest {

    private val settings = BandwidthSettings()

    @Test
    @DisplayName("le mode économie automatique plafonne à 720p et 900 kb/s")
    fun `mode par defaut`() {
        val caps = PlaybackCaps.forMode(QualityMode.AUTO_ECONOMY, settings)

        assertThat(caps.maxHeightPx).isEqualTo(720)
        assertThat(caps.maxBitrateBps).isEqualTo(900_000)
        assertThat(caps.effectiveMaxBitrateBps).isEqualTo(900_000)
        assertThat(caps.videoDisabled).isFalse()
    }

    @Test
    @DisplayName("le mode 480p descend à 700 kb/s")
    fun `mode 480`() {
        val caps = PlaybackCaps.forMode(QualityMode.ECONOMY_480, settings)

        assertThat(caps.maxHeightPx).isEqualTo(480)
        assertThat(caps.effectiveMaxBitrateBps).isEqualTo(700_000)
    }

    @Test
    @DisplayName("le mode extrême descend à 360p et 400 kb/s")
    fun `mode extreme`() {
        val caps = PlaybackCaps.forMode(QualityMode.ULTRA_ECONOMY, settings)

        assertThat(caps.maxHeightPx).isEqualTo(360)
        assertThat(caps.effectiveMaxBitrateBps).isEqualTo(400_000)
    }

    @Test
    @DisplayName("le mode audio seul désactive entièrement la vidéo")
    fun `mode audio seul`() {
        val caps = PlaybackCaps.forMode(QualityMode.AUDIO_ONLY, settings)

        // C'est le seul mode dont la consommation ne dépend plus de l'image :
        // la piste vidéo n'est pas téléchargée du tout.
        assertThat(caps.videoDisabled).isTrue()
        assertThat(caps.effectiveMaxBitrateBps).isEqualTo(0)
    }

    @Test
    @DisplayName("le mode qualité maximale ne plafonne rien")
    fun `mode maximal`() {
        val caps = PlaybackCaps.forMode(QualityMode.MAX_QUALITY, settings)

        assertThat(caps.videoDisabled).isFalse()
        assertThat(caps.maxBitrateBps).isEqualTo(0)
        assertThat(caps.effectiveMaxBitrateBps).isEqualTo(0)
    }

    @Test
    @DisplayName("une résolution surchargée ne crée pas de plafond de débit implicite")
    fun `pas de plafond deduit`() {
        val surcharges = BandwidthSettings(maxVideoHeightByMode = mapOf(QualityMode.AUTO_ECONOMY to 576))

        val caps = PlaybackCaps.forMode(QualityMode.AUTO_ECONOMY, surcharges)

        // La résolution baisse, le débit reste celui du mode : c'est la
        // sélection de piste qui fait le travail, sans bride arbitraire.
        assertThat(caps.maxHeightPx).isEqualTo(576)
        assertThat(caps.effectiveMaxBitrateBps).isEqualTo(QualityMode.AUTO_ECONOMY.defaultMaxBitrate)
    }

    @Test
    @DisplayName("la surcharge de débit de la configuration distante est respectée")
    fun `surcharge distante`() {
        val surcharges = BandwidthSettings(
            maxVideoHeightByMode = mapOf(QualityMode.ECONOMY_480 to 576),
            maxVideoBitrateByMode = mapOf(QualityMode.ECONOMY_480 to 650_000),
        )

        val caps = PlaybackCaps.forMode(QualityMode.ECONOMY_480, surcharges)

        assertThat(caps.maxHeightPx).isEqualTo(576)
        assertThat(caps.effectiveMaxBitrateBps).isEqualTo(650_000)
    }

    @ParameterizedTest
    @EnumSource(QualityMode::class)
    @DisplayName("chaque mode produit des contraintes cohérentes")
    fun `tous les modes`(mode: QualityMode) {
        val caps = PlaybackCaps.forMode(mode, settings)

        if (mode.isAudioOnly) {
            assertThat(caps.videoDisabled).isTrue()
        } else {
            assertThat(caps.videoDisabled).isFalse()
            assertThat(caps.maxHeightPx).isAtLeast(0)
        }
    }

    @Test
    @DisplayName("aucun mode interactif ne dépasse son plafond de débit")
    fun `plafonds coherents`() {
        // Une inversion de plafonds enverrait au lecteur une contrainte absurde,
        // par exemple « 900 kb/s » pour le mode le plus économe.
        val parMode = QualityMode.selectable.associateWith { mode ->
            PlaybackCaps.forMode(mode, settings).effectiveMaxBitrateBps
        }

        val economes = listOf(
            QualityMode.AUDIO_ONLY,
            QualityMode.ULTRA_ECONOMY,
            QualityMode.ECONOMY_480,
            QualityMode.AUTO_ECONOMY,
        )
        val debits = economes.map { parMode.getValue(it) }
        assertThat(debits).isInOrder()
    }
}
