package com.missa.tv.data.local.db

import androidx.room.Entity
import androidx.room.Index
import com.missa.tv.domain.channel.ChannelVariantGrouper
import com.missa.tv.domain.model.Category
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.ChannelGroup
import com.missa.tv.domain.model.ChannelVariant
import com.missa.tv.domain.model.VideoQuality

/**
 * Tables du catalogue local.
 *
 * Le regroupement SD/HD/FHD des diffusions est **calculé une fois**, à
 * l'enregistrement, puis conservé en base : `groupKey`, `baseName`, `qualityRank`
 * et `qualityLabel` sont des colonnes. Les requêtes de lecture n'ont donc plus
 * besoin de réanalyser les libellés — c'est ce qui permet d'afficher une liste
 * complète sans repasser sur plusieurs milliers de chaînes à chaque ouverture.
 *
 * Le regroupement lui-même reste décidé par [ChannelVariantGrouper] : la base ne
 * fait que mémoriser son résultat, jamais ne l'invente.
 */

/** Genre du portail (table `categories`). */
@Entity(
    tableName = "categories",
    primaryKeys = ["portalId", "id"],
)
data class CategoryEntity(
    /** Profil de portail propriétaire : deux portails ne partagent pas leur cache. */
    val portalId: String,
    val id: String,
    val title: String,
    /** Ordre d'affichage publié par le portail. */
    val sortIndex: Int,
) {
    fun toDomain(): Category = Category(id = id, title = title)

    companion object {
        fun fromDomain(portalId: String, category: Category, sortIndex: Int) = CategoryEntity(
            portalId = portalId,
            id = category.id,
            title = category.title,
            sortIndex = sortIndex,
        )
    }
}

/**
 * Diffusion (table `channels`).
 *
 * Une ligne par diffusion, donc plusieurs lignes pour une chaîne proposée en
 * plusieurs qualités : le regroupement se fait ensuite par `groupKey`.
 */
@Entity(
    tableName = "channels",
    primaryKeys = ["portalId", "id"],
    indices = [
        Index(value = ["portalId", "groupKey"]),
        Index(value = ["portalId", "number"]),
    ],
)
data class ChannelEntity(
    val portalId: String,
    val id: String,
    val number: Int,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val categoryId: String?,
    val isCensored: Boolean,
    val isAvailable: Boolean,
    val tvgId: String?,
    val userAgent: String?,
    val referrer: String?,
    /** Catégorie + nom de base : clé de regroupement des variantes. */
    val groupKey: String,
    /** Nom débarrassé des marqueurs de qualité (« TF1 HD » → « TF1 »). */
    val baseName: String,
    /** Rang de qualité, tel que défini par [VideoQuality.rank] : sert aux tris SQL. */
    val qualityRank: Int,
    /** Libellé de qualité affiché (« HD », « FHD », « 4K »…), vide si inconnu. */
    val qualityLabel: String,
    /** Horodatage de la dernière écriture, utilisé pour dater le cache. */
    val updatedAtMs: Long,
) {
    /** Qualité reconnue, retrouvée depuis son rang. */
    val quality: VideoQuality
        get() = VideoQuality.entries.firstOrNull { it.rank == qualityRank } ?: VideoQuality.UNKNOWN

    fun toDomain(): Channel = Channel(
        id = id,
        number = number,
        name = name,
        streamUrl = streamUrl,
        logoUrl = logoUrl,
        categoryId = categoryId,
        isCensored = isCensored,
        isAvailable = isAvailable,
        tvgId = tvgId,
        userAgent = userAgent,
        referrer = referrer,
    )

    companion object {
        fun fromDomain(portalId: String, channel: Channel, updatedAtMs: Long): ChannelEntity {
            val quality = ChannelVariantGrouper.qualityOf(channel.name)
            return ChannelEntity(
                portalId = portalId,
                id = channel.id,
                number = channel.number,
                name = channel.name,
                streamUrl = channel.streamUrl,
                logoUrl = channel.logoUrl,
                categoryId = channel.categoryId,
                isCensored = channel.isCensored,
                isAvailable = channel.isAvailable,
                tvgId = channel.tvgId,
                userAgent = channel.userAgent,
                referrer = channel.referrer,
                groupKey = ChannelVariantGrouper.groupKey(channel),
                baseName = ChannelVariantGrouper.baseName(channel.name),
                qualityRank = quality.rank,
                qualityLabel = quality.label,
                updatedAtMs = updatedAtMs,
            )
        }
    }
}

/**
 * Résumé d'un groupe, tel que le renvoie la requête d'agrégation SQL.
 *
 * `distinctQualityCount` vaut 1 exactement quand la chaîne n'est diffusée qu'en
 * une seule qualité : c'est l'information qui déclenche le message honnête
 * « cette chaîne n'est diffusée qu'en une seule qualité », aussi bien que les
 * optimisations de tampon plutôt qu'une réduction de résolution impossible.
 */
data class ChannelGroupSummary(
    val groupKey: String,
    val displayName: String,
    val firstNumber: Int,
    val variantCount: Int,
    val distinctQualityCount: Int,
    val lowestRank: Int,
    val highestRank: Int,
    val logoUrl: String?,
) {
    fun toDomain(variants: List<ChannelVariant>): ChannelGroup = ChannelGroup(
        key = groupKey,
        displayName = displayName,
        variants = variants,
    )
}
