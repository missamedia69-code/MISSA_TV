package com.missa.tv.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Accès au guide local.
 *
 * Toutes les requêtes sont validées par Room à la compilation : une erreur de
 * SQL ne peut pas atteindre l'exécution. Les fenêtres de temps sont des
 * demi-ouvertes (`startMs < :toMs AND endMs > :fromMs`) : un programme en
 * cours à l'instant `fromMs` est retenu, un programme terminé exactement à
 * `toMs` ne l'est pas.
 */
@Dao
interface EpgDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvents(events: List<EpgEventEntity>)

    /** Supprime les programmes terminés d'une chaîne : le guide ne garde que le visible. */
    @Query(
        """
        DELETE FROM epg_events
        WHERE portalId = :portalId AND channelId = :channelId AND endMs <= :nowMs
        """,
    )
    suspend fun deleteEndedEvents(portalId: String, channelId: String, nowMs: Long)

    @Query("DELETE FROM epg_events WHERE portalId = :portalId")
    suspend fun deletePortalEvents(portalId: String)

    @Query("DELETE FROM epg_events")
    suspend fun clearAllEvents()

    /** Programmes d'une chaîne chevauchant la fenêtre, triés par heure de début. */
    @Query(
        """
        SELECT * FROM epg_events
        WHERE portalId = :portalId AND channelId = :channelId
          AND startMs < :toMs AND endMs > :fromMs
        ORDER BY startMs ASC
        """,
    )
    suspend fun eventsInWindow(
        portalId: String,
        channelId: String,
        fromMs: Long,
        toMs: Long,
    ): List<EpgEventEntity>

    /**
     * Identifiants des chaînes dont un guide est mémorisé, par ancienneté
     * croissante du plus ancien programme.
     *
     * Sert au rafraîchissement périodique : seules les chaînes déjà connues
     * sont redemandées, jamais le catalogue entier.
     */
    @Query(
        """
        SELECT channelId FROM epg_events
        WHERE portalId = :portalId
        GROUP BY channelId
        ORDER BY MIN(startMs) ASC
        """,
    )
    suspend fun channelsWithEvents(portalId: String): List<String>

    /** Date de la dernière écriture du guide d'une chaîne, ou `null` si elle n'en a pas. */
    @Query(
        """
        SELECT MAX(updatedAtMs) FROM epg_events
        WHERE portalId = :portalId AND channelId = :channelId
        """,
    )
    suspend fun lastUpdatedMs(portalId: String, channelId: String): Long?
}
