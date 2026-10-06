package com.missa.tv.domain.playback

import com.missa.tv.domain.model.BandwidthSettings
import com.missa.tv.domain.model.QualityMode

/**
 * Contraintes à appliquer au lecteur pour un mode de qualité.
 *
 * C'est la traduction d'un mode ([QualityMode]) en valeurs compréhensibles par
 * le moteur de lecture : plafond de résolution, plafond de débit, et suppression
 * éventuelle de la vidéo (mode audio seul).
 *
 * Classe volontairement indépendante du moteur : la règle métier
 * « quel plafond pour quel mode » est ainsi vérifiable par des tests qui ne
 * dépendent pas de Media3.
 */
data class PlaybackCaps(
    /** Hauteur maximale de la vidéo en pixels ; 0 signifie « aucune limite ». */
    val maxHeightPx: Int,
    /** Débit vidéo maximal en bits par seconde ; 0 signifie « aucune limite ». */
    val maxBitrateBps: Int,
    /** Vrai si la piste vidéo doit être désactivée (mode audio seul). */
    val videoDisabled: Boolean,
) {

    /**
     * Plafond de débit retenu pour le moteur.
     *
     * Un mode sans plafond explicite reçoit une limite déduite de sa résolution :
     * laisser le lecteur choisir librement sur une connexion faible est
     * exactement ce qui provoque les coupures.
     */
    val effectiveMaxBitrateBps: Int
        get() = when {
            videoDisabled -> 0
            maxBitrateBps > 0 -> maxBitrateBps
            maxHeightPx <= 0 -> 0
            else -> maxHeightPx * ESTIMATED_BPS_PER_PIXEL_ROW
        }

    companion object {

        /**
         * Débit approximatif par rangée de pixels de hauteur, en bits par
         * seconde. Ordre de grandeur constaté sur les flux H.264 diffusés par
         * ces portails.
         */
        const val ESTIMATED_BPS_PER_PIXEL_ROW = 2_400

        /** Contraintes correspondant à un mode, compte tenu des surcharges. */
        fun forMode(mode: QualityMode, settings: BandwidthSettings): PlaybackCaps = PlaybackCaps(
            maxHeightPx = settings.maxHeightFor(mode),
            maxBitrateBps = settings.maxBitrateFor(mode),
            videoDisabled = mode.isAudioOnly,
        )
    }
}
