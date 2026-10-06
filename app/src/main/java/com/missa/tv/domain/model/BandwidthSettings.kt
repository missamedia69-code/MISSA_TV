package com.missa.tv.domain.model

/**
 * Réglages du tampon de lecture pour le direct.
 *
 * Des valeurs généreuses sont volontaires : sur une connexion faible, mieux vaut
 * accumuler quelques secondes d'avance que s'arrêter toutes les dix secondes.
 */
data class BufferSettings(
    val minMs: Int = DEFAULT_MIN_MS,
    val maxMs: Int = DEFAULT_MAX_MS,
    val playbackMs: Int = DEFAULT_PLAYBACK_MS,
    val afterRebufferMs: Int = DEFAULT_AFTER_REBUFFER_MS,
) {
    companion object {
        const val DEFAULT_MIN_MS = 15_000
        const val DEFAULT_MAX_MS = 40_000
        const val DEFAULT_PLAYBACK_MS = 3_000
        const val DEFAULT_AFTER_REBUFFER_MS = 6_000

        /**
         * Valeurs raccourcies utilisées lorsque l'utilisateur a explicitement
         * demandé la qualité maximale : on suppose alors une bonne connexion et
         * on privilégie la faible latence.
         */
        val LOW_LATENCY = BufferSettings(
            minMs = 6_000,
            maxMs = 20_000,
            playbackMs = 1_500,
            afterRebufferMs = 3_000,
        )
    }
}

/**
 * Réglages d'adaptation au débit.
 *
 * Ces valeurs viennent du code par défaut, mais peuvent être surchargées par le
 * bloc `bandwidth` de la configuration distante (`remote-config/portal-config.json`),
 * ce qui permet de les ajuster sans republier l'application.
 */
data class BandwidthSettings(
    /** Seuil sous lequel la connexion est jugée faible (profil économie auto). */
    val lowBandwidthThresholdKbps: Int = DEFAULT_LOW_THRESHOLD_KBPS,
    /** Mode appliqué au premier lancement. */
    val defaultMode: QualityMode = QualityMode.DEFAULT,
    /** Plafond de hauteur vidéo par mode (surcharge les valeurs du modèle). */
    val maxVideoHeightByMode: Map<QualityMode, Int> = emptyMap(),
    /** Plafond de débit vidéo par mode (surcharge les valeurs du modèle). */
    val maxVideoBitrateByMode: Map<QualityMode, Int> = emptyMap(),
    /** Réglages du tampon. */
    val buffer: BufferSettings = BufferSettings(),
    /**
     * Au-delà de ce multiple du débit nécessaire, la remontée de palier est
     * autorisée. 2,0 signifie qu'il faut le double du débit requis.
     */
    val upgradeHeadroomFactor: Double = DEFAULT_UPGRADE_HEADROOM,
) {

    /** Hauteur maximale retenue pour un mode, en tenant compte des surcharges. */
    fun maxHeightFor(mode: QualityMode): Int =
        maxVideoHeightByMode[mode] ?: mode.defaultMaxHeight

    /** Débit maximal retenu pour un mode, en tenant compte des surcharges. */
    fun maxBitrateFor(mode: QualityMode): Int =
        maxVideoBitrateByMode[mode] ?: mode.defaultMaxBitrate

    /**
     * Débit nécessaire pour qu'un palier soit confortable : le plafond du mode
     * majoré de la marge de sécurité, ou une estimation par défaut si le mode
     * n'a pas de plafond de débit.
     */
    fun requiredKbpsFor(mode: QualityMode): Int {
        val bitrate = maxBitrateFor(mode)
        val height = maxHeightFor(mode)
        val base = when {
            bitrate > 0 -> bitrate
            height <= 0 -> AUDIO_ONLY_KBPS
            else -> height * APPROX_KBPS_PER_1000PX / 1_000
        }
        return (base / 1_000.0 * SAFETY_MARGIN).toInt().coerceAtLeast(AUDIO_ONLY_KBPS)
    }

    companion object {
        const val DEFAULT_LOW_THRESHOLD_KBPS = 1_000
        const val DEFAULT_UPGRADE_HEADROOM = 2.0

        /** Débit approximatif d'une piste audio seule, en kb/s. */
        const val AUDIO_ONLY_KBPS = 96

        /** Marge de sécurité appliquée aux débits calculés (70 % de la capacité). */
        const val SAFETY_MARGIN = 1.0 / 0.7

        /** Ordre de grandeur : kb/s nécessaires par tranche de 1 000 pixels de haut. */
        const val APPROX_KBPS_PER_1000PX = 1_500
    }
}
