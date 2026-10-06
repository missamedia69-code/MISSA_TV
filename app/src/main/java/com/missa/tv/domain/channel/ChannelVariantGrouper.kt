package com.missa.tv.domain.channel

import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.ChannelGroup
import com.missa.tv.domain.model.ChannelVariant
import com.missa.tv.domain.model.VideoQuality
import java.text.Normalizer
import java.util.Locale

/**
 * Regroupe les diffusions d'une même chaîne (SD / HD / FHD / 4K…).
 *
 * Les portails Stalker exposent très souvent plusieurs entrées pour une seule
 * chaîne. Or ces portails ne proposent aucun flux à variantes : le seul moyen de
 * réduire la qualité est de choisir la diffusion la plus légère. Ce regroupement
 * est donc la brique qui rend le mode économie réellement efficace sur les
 * chaînes à qualité unique.
 *
 * L'analyse se fait sur le libellé, faute d'information de qualité fiable dans
 * la réponse du portail (`get_all_channels` fournit rarement la définition).
 */
object ChannelVariantGrouper {

    /**
     * Marqueurs de qualité reconnus dans un libellé.
     * L'ordre n'a pas d'importance : le marqueur le plus élevé gagne.
     */
    private val QUALITY_MARKERS: List<Pair<Regex, VideoQuality>> = listOf(
        Regex("\\b(UHD|4K|2160P?)\\b") to VideoQuality.UHD,
        Regex("\\b(FHD|FULL ?HD|1080P?)\\b") to VideoQuality.FHD,
        Regex("\\b(HD|720P?|HQ)\\b") to VideoQuality.HD,
        Regex("\\b(SD|576P?|480P?)\\b") to VideoQuality.SD,
        Regex("\\b(LQ|360P?|240P?)\\b") to VideoQuality.LQ,
    )

    /**
     * Marqueurs retirés du libellé pour obtenir le nom de base.
     * Inclut les mentions techniques qui ne changent pas l'identité de la chaîne.
     */
    private val STRIPPED_MARKERS: List<Regex> = listOf(
        Regex("[\\s\\-_\\[\\]()]*\\b(UHD|4K|2160P?|FHD|FULL ?HD|1080P?|HD|720P?|HQ|SD|576P?|480P?|LQ|360P?|240P?)\\b[\\s\\-_\\[\\]()]*"),
        Regex("\\b(HEVC|H\\.?265|H\\.?264|MPEG ?4|AAC)\\b"),
    )

    private val SQUEEZED_SEPARATORS = Regex("[\\s\\-_]{2,}")

    /** Qualité reconnue dans un libellé ; [VideoQuality.UNKNOWN] si aucune. */
    fun qualityOf(name: String): VideoQuality {
        val upper = normalize(name)
        return QUALITY_MARKERS
            .mapNotNull { (regex, quality) -> if (regex.containsMatchIn(upper)) quality else null }
            .maxByOrNull { it.rank }
            ?: VideoQuality.UNKNOWN
    }

    /**
     * Nom de base d'une chaîne, débarrassé des marqueurs de qualité.
     * « TF1 HD » et « TF1 FHD » donnent tous deux « TF1 ».
     */
    fun baseName(name: String): String {
        var result = normalize(name)
        STRIPPED_MARKERS.forEach { regex -> result = result.replace(regex, " ") }
        result = SQUEEZED_SEPARATORS.replace(result, " ")
        return result.trim().trim('-', '_', '|', ':').trim()
    }

    /**
     * Clé de regroupement : nom de base et catégorie d'origine.
     *
     * La catégorie est incluse pour éviter de fusionner deux chaînes homonymes
     * appartenant à des genres différents.
     */
    fun groupKey(channel: Channel): String =
        "${channel.categoryId.orEmpty()}|${baseName(channel.name)}"

    /**
     * Regroupe une liste de chaînes.
     *
     * Les variantes sont triées par qualité croissante, puis par numéro de
     * chaîne pour garantir un ordre stable ; le nom affiché est celui de la
     * variante la mieux classée, ou le nom le plus court si les qualités sont
     * égales.
     */
    fun group(channels: List<Channel>): List<ChannelGroup> {
        if (channels.isEmpty()) return emptyList()

        return channels
            .groupBy(::groupKey)
            .map { (key, members) ->
                val variants = members
                    .map { channel -> ChannelVariant(channel, qualityOf(channel.name)) }
                    .sortedWith(
                        compareBy<ChannelVariant> { it.quality.rank }
                            .thenBy { it.channel.number },
                    )
                ChannelGroup(
                    key = key,
                    displayName = displayNameOf(variants),
                    variants = variants,
                )
            }
            // Ordre d'affichage : par numéro de la meilleure diffusion de chaque groupe.
            .sortedWith(
                compareBy<ChannelGroup> { it.variants.first().channel.number }
                    .thenBy { it.displayName },
            )
    }

    private fun displayNameOf(variants: List<ChannelVariant>): String {
        val best = variants.maxByOrNull { it.quality.rank } ?: return ""
        val candidates = variants.filter { it.quality.rank == best.quality.rank }
        return candidates.minByOrNull { it.channel.name.length }?.channel?.name.orEmpty()
    }

    /** Met le libellé sous forme comparable : majuscules, sans accents, espaces simples. */
    private fun normalize(name: String): String {
        val withoutAccents = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return withoutAccents
            .uppercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
