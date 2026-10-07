package com.missa.tv.domain.model

/** Catégorie (genre) de chaînes, telle que fournie par le portail. */
data class Category(
    val id: String,
    val title: String,
)

/**
 * Une chaîne du portail.
 *
 * [cmd] est la commande de lecture brute renvoyée par le portail (`ffmpeg ...`).
 * Elle n'est jamais utilisée directement : un lien de lecture temporaire est
 * demandé au portail au moment de regarder la chaîne.
 */
data class Channel(
    val id: String,
    val number: Int,
    val name: String,
    val cmd: String,
    val logoUrl: String? = null,
    val categoryId: String? = null,
    val isCensored: Boolean = false,
    val isAvailable: Boolean = true,
) {
    /** Nombre affiché à l'utilisateur (1, 2, 3…). */
    val displayNumber: String get() = number.toString()
}

/**
 * Qualité déduite du libellé d'une chaîne.
 *
 * Les portails Stalker publient souvent la même chaîne plusieurs fois, une fois
 * par qualité (« TF1 », « TF1 HD », « TF1 FHD »). Ce classement permet de
 * regrouper ces diffusions et de choisir automatiquement la plus légère en mode
 * économie de données.
 *
 * [rank] définit l'ordre de légèreté, du plus léger au plus lourd. Le cas
 * [UNKNOWN] est volontairement placé **entre SD et HD** : une diffusion sans
 * marqueur de qualité est la diffusion « de base » du portail, plus lourde
 * qu'une SD annoncée mais plus légère qu'une HD annoncée. Le placer en tête
 * ferait choisir une diffusion au débit inconnu alors qu'une SD explicite est
 * disponible — exactement l'inverse du but recherché en mode économie.
 */
enum class VideoQuality(
    val rank: Int,
    val label: String,
    /** Hauteur de vidéo attendue, utilisée pour appliquer un plafond de mode. */
    val approximateHeight: Int,
) {
    LQ(10, "LQ", 360),
    SD(20, "SD", 480),
    UNKNOWN(25, "", 720),
    HD(30, "HD", 720),
    FHD(40, "FHD", 1080),
    UHD(50, "4K", 2160),
}

/** Une diffusion d'une chaîne, avec la qualité qui lui a été reconnue. */
data class ChannelVariant(
    val channel: Channel,
    val quality: VideoQuality,
)

/**
 * Ensemble des diffusions d'une même chaîne.
 *
 * [variants] est trié par qualité croissante : le premier élément est donc la
 * diffusion la plus légère, utilisée en priorité en mode économie.
 */
data class ChannelGroup(
    val key: String,
    val displayName: String,
    val variants: List<ChannelVariant>,
) {
    val lowest: ChannelVariant get() = variants.first()

    val highest: ChannelVariant get() = variants.last()

    /** Vrai si la chaîne n'est diffusée qu'en une seule qualité. */
    val hasSingleVariant: Boolean get() = variants.size == 1

    /** Nombre de qualités réellement distinctes proposées. */
    val distinctQualityCount: Int get() = variants.map { it.quality }.distinct().size

    /**
     * Choisit la diffusion la mieux adaptée au mode de qualité demandé.
     *
     * - `AUDIO_ONLY` : la plus légère (l'audio est le même partout) ;
     * - `MAX_QUALITY` : la meilleure disponible ;
     * - mode plafonné : la meilleure diffusion qui reste sous le plafond, sinon
     *   la plus légère — on ne dépasse jamais le plafond choisi.
     *
     * Le résultat ne garantit pas un débit : il n'écarte que les qualités
     * manifestement hors plafond.
     */
    fun bestFor(mode: QualityMode): ChannelVariant {
        if (mode.isAudioOnly) return lowest
        if (mode == QualityMode.MAX_QUALITY) return highest

        val ceiling = mode.defaultMaxHeight
        return variants.lastOrNull { it.quality.approximateHeight <= ceiling } ?: lowest
    }
}
