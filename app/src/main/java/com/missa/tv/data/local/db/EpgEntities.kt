package com.missa.tv.data.local.db

import androidx.room.Entity
import androidx.room.Index
import com.missa.tv.domain.model.EpgEvent

/**
 * Table du guide électronique des programmes (`epg_events`).
 *
 * Une ligne par programme publié par le portail. La clé primaire inclut la
 * chaîne : les identifiants d'événements ne sont garantis uniques que par
 * chaîne, et deux chaînes ne doivent jamais partager une ligne.
 *
 * Le guide se construit par **fusion** (insertion avec remplacement) : le
 * guide court (`get_short_epg`, programme en cours et suivant) et le guide
 * complet (`get_events`, la journée) décrivent les mêmes programmes avec les
 * mêmes identifiants, et les relire ne doit pas effacer l'un par l'autre. Les
 * programmes terminés sont supprimés à chaque écriture (voir
 * [EpgDao.deleteEndedEvents]) : la table reste bornée par ce qui est encore
 * diffusé, sans migration.
 */
@Entity(
    tableName = "epg_events",
    primaryKeys = ["portalId", "channelId", "id"],
    indices = [
        Index(value = ["portalId", "channelId", "startMs"]),
    ],
)
data class EpgEventEntity(
    /** Profil de portail propriétaire : deux portails ne partagent pas leur guide. */
    val portalId: String,
    val channelId: String,
    val id: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val endMs: Long,
    /** Horodatage de la dernière écriture, utilisé pour dater le cache. */
    val updatedAtMs: Long,
) {
    fun toDomain(): EpgEvent = EpgEvent(
        id = id,
        channelId = channelId,
        title = title,
        description = description,
        startMs = startMs,
        endMs = endMs,
    )

    companion object {
        fun fromDomain(portalId: String, event: EpgEvent, updatedAtMs: Long) = EpgEventEntity(
            portalId = portalId,
            channelId = event.channelId,
            id = event.id,
            title = event.title,
            description = event.description,
            startMs = event.startMs,
            endMs = event.endMs,
            updatedAtMs = updatedAtMs,
        )
    }
}
