package com.missa.tv.data.local

import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.local.db.EpgDao
import com.missa.tv.data.local.db.EpgEventEntity
import com.missa.tv.domain.model.ChannelEpg
import com.missa.tv.domain.model.EpgEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Guide local des programmes, en base Room.
 *
 * Le guide sert deux affichages : le programme en cours à côté d'une chaîne
 * (guide court, rafraîchi à l'ouverture) et le programme complet d'une chaîne
 * (guide sur 24 h, affiché à la demande). Dans les deux cas, la version
 * mémorisée est affichée **sans attendre le portail**, puis rafraîchie en
 * arrière-plan : sur une connexion lente, l'information est là immédiatement,
 * même si elle date de quelques minutes.
 *
 * L'enregistrement fusionne par identifiant (voir [EpgDao.upsertEvents]) :
 * le guide court et le guide complet décrivent les mêmes programmes, et les
 * relire ne doit pas effacer l'un par l'autre. Les programmes terminés sont
 * supprimés à chaque écriture, ce qui borne la table sans migration.
 */
@Singleton
class EpgCache @Inject constructor(
    private val dao: EpgDao,
    private val timeSource: TimeSource,
) {

    /**
     * Enregistre le guide d'une chaîne.
     *
     * La liste peut être vide : le portail ne publie alors aucun programme
     * pour cette chaîne. Les programmes mémorisés **encore en cours** sont
     * conservés — une réponse vide transitoire ne doit pas effacer un guide
     * vraisemblablement bon — et disparaissent d'eux-mêmes à leur fin, puisque
     * la lecture du guide ne retient que ce qui chevauche le présent.
     */
    suspend fun save(portalId: String, channelId: String, events: List<EpgEvent>) {
        val maintenant = timeSource.nowMs()
        dao.upsertEvents(
            events.map { EpgEventEntity.fromDomain(portalId, it, maintenant) },
        )
        dao.deleteEndedEvents(portalId, channelId, maintenant)
        MissaLog.d("Guide local enregistré : ${events.size} programmes pour la chaîne $channelId")
    }

    /** Programmes mémorisés d'une chaîne sur la fenêtre [fromMs, toMs]. */
    suspend fun events(
        portalId: String,
        channelId: String,
        fromMs: Long,
        toMs: Long,
    ): List<EpgEvent> = dao.eventsInWindow(portalId, channelId, fromMs, toMs).map { it.toDomain() }

    /**
     * Guide d'une chaîne à l'instant [nowMs] : programme en cours, suivant, à venir.
     *
     * La fenêtre de lecture commence à l'instant présent : le programme en
     * cours est retenu parce qu'il n'est pas terminé (`endMs > nowMs`), les
     * programmes passés sont ignorés par construction.
     */
    suspend fun guide(
        portalId: String,
        channelId: String,
        nowMs: Long,
        horizonMs: Long,
    ): ChannelEpg = ChannelEpg.of(
        channelId = channelId,
        events = events(portalId, channelId, nowMs, nowMs + horizonMs),
        nowMs = nowMs,
    )

    /** Chaînes dont un guide est mémorisé (pour le rafraîchissement périodique). */
    suspend fun channelsWithEvents(portalId: String): List<String> =
        dao.channelsWithEvents(portalId)

    /** Date du dernier enregistrement du guide d'une chaîne, ou `null` si elle n'en a pas. */
    suspend fun lastUpdatedMs(portalId: String, channelId: String): Long? =
        dao.lastUpdatedMs(portalId, channelId)

    /**
     * Vrai si le guide mémorisé d'une chaîne date de plus de [maxAgeMs].
     *
     * Une chaîne sans guide mémorisé est considérée comme périmée : elle est
     * redemandée au portail à la première occasion.
     */
    suspend fun isStale(
        portalId: String,
        channelId: String,
        nowMs: Long,
        maxAgeMs: Long,
    ): Boolean {
        val date = lastUpdatedMs(portalId, channelId) ?: return true
        return nowMs - date > maxAgeMs
    }

    /** Vide le guide d'un portail (profil supprimé, ou portail changé). */
    suspend fun clear(portalId: String) {
        dao.deletePortalEvents(portalId)
    }

    /** Vide tout le guide local, tous portails confondus. */
    suspend fun clearAll() {
        dao.clearAllEvents()
    }
}
