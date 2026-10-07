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
     * Aucun plafond n'est déduit de la résolution : le mode « qualité maximale »
     * doit rester sans limite, sinon une connexion rapide serait bridée à son
     * insu. La résolution est déjà contrainte séparément, par le plafond de
     * hauteur transmis au moteur de lecture.
     */
    val effectiveMaxBitrateBps: Int
        get() = if (videoDisabled) 0 else maxBitrateBps

    companion object {

        /** Contraintes correspondant à un mode, compte tenu des surcharges. */
        fun forMode(mode: QualityMode, settings: BandwidthSettings): PlaybackCaps = PlaybackCaps(
            maxHeightPx = settings.maxHeightFor(mode),
            maxBitrateBps = settings.maxBitrateFor(mode),
            videoDisabled = mode.isAudioOnly,
        )
    }
}
