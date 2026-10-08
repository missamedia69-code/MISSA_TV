package com.missa.tv.data.local

import com.missa.tv.data.local.db.EpgDao
import com.missa.tv.data.local.db.EpgEventEntity

/**
 * Doublure en mémoire de [EpgDao], partagée par les tests du guide.
 *
 * Elle reproduit la sémantique des requêtes SQL : fenêtre de chevauchement,
 * tri par début, fusion par clé (portail, chaîne, identifiant), suppression
 * des programmes terminés. Le SQL réel est vérifié par Room à la compilation ;
 * cette doublure permet de tester ce qui s'assemble autour.
 */
internal class FakeEpgDao : EpgDao {

    private val evenements = mutableListOf<EpgEventEntity>()

    override suspend fun upsertEvents(events: List<EpgEventEntity>) {
        events.forEach { entite ->
            evenements.removeAll {
                it.portalId == entite.portalId && it.channelId == entite.channelId && it.id == entite.id
            }
            evenements += entite
        }
    }

    override suspend fun deleteEndedEvents(portalId: String, channelId: String, nowMs: Long) {
        evenements.removeAll {
            it.portalId == portalId && it.channelId == channelId && it.endMs <= nowMs
        }
    }

    override suspend fun deletePortalEvents(portalId: String) {
        evenements.removeAll { it.portalId == portalId }
    }

    override suspend fun clearAllEvents() {
        evenements.clear()
    }

    override suspend fun eventsInWindow(
        portalId: String,
        channelId: String,
        fromMs: Long,
        toMs: Long,
    ): List<EpgEventEntity> = evenements
        .filter { it.portalId == portalId && it.channelId == channelId }
        .filter { it.startMs < toMs && it.endMs > fromMs }
        .sortedBy { it.startMs }

    override suspend fun channelsWithEvents(portalId: String): List<String> = evenements
        .filter { it.portalId == portalId }
        .groupBy { it.channelId }
        .toList()
        .sortedBy { (_, membres) -> membres.minOf { it.startMs } }
        .map { it.first }

    override suspend fun lastUpdatedMs(portalId: String, channelId: String): Long? =
        evenements
            .filter { it.portalId == portalId && it.channelId == channelId }
            .maxOfOrNull { it.updatedAtMs }
}
