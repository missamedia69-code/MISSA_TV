package com.missa.tv.domain.model

import androidx.annotation.StringRes
import com.missa.tv.R

/**
 * Modes de qualité proposés à l'utilisateur.
 *
 * L'ordre de déclaration correspond à l'échelle de qualité : `AUTO_ECONOMY` est
 * le mode par défaut, `MAX_QUALITY` le seul mode sans plafond, et `AUDIO_ONLY`
 * le dernier recours lorsque même la plus basse qualité ne passe pas.
 *
 * L'application ne transcode jamais : elle choisit la variante la plus adaptée
 * quand la chaîne en propose plusieurs, et sinon règle le lecteur pour qu'il
 * reste stable (tampon plus grand, résolution imposée sur les flux adaptatifs).
 */
enum class QualityMode(
    @StringRes val labelRes: Int,
    /** Plafond de hauteur vidéo en pixels ; 0 signifie « pas de piste vidéo ». */
    val defaultMaxHeight: Int,
    /** Plafond de débit vidéo en bits par seconde ; 0 signifie « pas de plafond ». */
    val defaultMaxBitrate: Int,
) {
    AUTO_ECONOMY(R.string.quality_auto_economy, 720, 900_000),
    ECONOMY_480(R.string.quality_economy_480, 480, 700_000),
    ULTRA_ECONOMY(R.string.quality_ultra_economy, 360, 400_000),
    MAX_QUALITY(R.string.quality_max, 2160, 0),
    AUDIO_ONLY(R.string.quality_audio_only, 0, 0),
    ;

    /** Vrai si le mode impose un plafond de résolution (hors qualité maximale). */
    val isCapped: Boolean get() = this != MAX_QUALITY

    /** Vrai si le mode désactive complètement la piste vidéo. */
    val isAudioOnly: Boolean get() = this == AUDIO_ONLY

    /**
     * Palier immédiatement inférieur, utilisé par la dégradation automatique.
     * `null` lorsque le mode est déjà le plus bas (`AUDIO_ONLY`).
     *
     * `MAX_QUALITY` n'appartient pas à l'échelle de dégradation : un utilisateur
     * qui a explicitement demandé la qualité maximale ne doit pas être rétrogradé.
     */
    fun lowerStep(): QualityMode? = when (this) {
        AUTO_ECONOMY -> ECONOMY_480
        ECONOMY_480 -> ULTRA_ECONOMY
        ULTRA_ECONOMY -> AUDIO_ONLY
        AUDIO_ONLY -> null
        MAX_QUALITY -> null
    }

    /**
     * Palier immédiatement supérieur, utilisé par la remontée automatique.
     * `null` lorsque le mode est déjà au plafond choisi par l'utilisateur.
     */
    fun higherStep(): QualityMode? = when (this) {
        AUDIO_ONLY -> ULTRA_ECONOMY
        ULTRA_ECONOMY -> ECONOMY_480
        ECONOMY_480 -> AUTO_ECONOMY
        AUTO_ECONOMY -> null
        MAX_QUALITY -> null
    }

    companion object {

        /** Mode appliqué lorsqu'aucune préférence n'est enregistrée. */
        val DEFAULT: QualityMode = AUTO_ECONOMY

        /**
         * Reconstruit un mode depuis son nom, en tolérant une valeur inconnue
         * (fichier de configuration plus récent que l'application, par exemple).
         */
        fun fromNameOrNull(name: String?): QualityMode? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

        /** Modes proposés dans le sélecteur, dans l'ordre d'affichage. */
        val selectable: List<QualityMode> = listOf(
            AUTO_ECONOMY,
            ECONOMY_480,
            ULTRA_ECONOMY,
            MAX_QUALITY,
            AUDIO_ONLY,
        )
    }
}
