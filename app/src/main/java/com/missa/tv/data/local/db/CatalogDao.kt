package com.missa.tv.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Accès au catalogue local.
 *
 * Le regroupement des variantes est fait par SQL :
 *  - [groups] agrège les diffusions par `groupKey` et calcule, pour chaque
 *    chaîne, le nombre de variantes et le nombre de qualités **distinctes** ;
 *  - [channelsOrderedByGroup] renvoie les diffusions déjà triées par groupe puis
 *    par qualité croissante, ce qui reconstruit chaque groupe sans tri côté
 *    Kotlin et place la diffusion la plus légère en tête.
 *
 * Toutes les requêtes sont validées par Room à la compilation : une erreur de
 * SQL ne peut pas atteindre l'exécution.
 */
@Dao
interface CatalogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannels(channels: List<ChannelEntity>)

    @Query("DELETE FROM categories WHERE portalId = :portalId")
    suspend fun deleteCategories(portalId: String)

    @Query("DELETE FROM channels WHERE portalId = :portalId")
    suspend fun deleteChannels(portalId: String)

    /**
     * Remplace le catalogue d'un portail d'un seul tenant.
     *
     * L'opération est transactionnelle : une coupure en cours d'écriture ne peut
     * pas laisser une liste de chaînes sans ses catégories.
     */
    @Transaction
    suspend fun replaceCatalog(
        portalId: String,
        categories: List<CategoryEntity>,
        channels: List<ChannelEntity>,
    ) {
        deleteChannels(portalId)
        deleteCategories(portalId)
        upsertCategories(categories)
        upsertChannels(channels)
    }

    @Query("SELECT * FROM categories WHERE portalId = :portalId ORDER BY sortIndex ASC, title ASC")
    suspend fun categories(portalId: String): List<CategoryEntity>

    /**
     * Regroupement des variantes, côté base.
     *
     * Le nom affiché est celui de la meilleure qualité disponible, en préférant
     * le libellé le plus court (« TF1 » plutôt que « TF1 FHD ») : la sous-requête
     * évite une fonction de fenêtrage, indisponible sur les boîtiers Android TV
     * les plus anciens (SQLite livré avec Android 6).
     */
    @Query(
        """
        SELECT c.groupKey AS groupKey,
               (
                   SELECT c2.name FROM channels c2
                   WHERE c2.portalId = c.portalId AND c2.groupKey = c.groupKey
                   ORDER BY c2.qualityRank DESC, LENGTH(c2.name) ASC, c2.number ASC
                   LIMIT 1
               ) AS displayName,
               MIN(c.number) AS firstNumber,
               COUNT(*) AS variantCount,
               COUNT(DISTINCT c.qualityRank) AS distinctQualityCount,
               MIN(c.qualityRank) AS lowestRank,
               MAX(c.qualityRank) AS highestRank,
               (
                   SELECT c3.logoUrl FROM channels c3
                   WHERE c3.portalId = c.portalId AND c3.groupKey = c.groupKey
                     AND c3.logoUrl IS NOT NULL AND c3.logoUrl <> ''
                   ORDER BY c3.qualityRank DESC, c3.number ASC
                   LIMIT 1
               ) AS logoUrl
        FROM channels c
        WHERE c.portalId = :portalId
        GROUP BY c.groupKey
        ORDER BY firstNumber ASC
        """,
    )
    suspend fun groups(portalId: String): List<ChannelGroupSummary>

    /**
     * Diffusions triées par groupe puis par qualité croissante.
     *
     * La première ligne d'un groupe est donc sa diffusion la plus légère : c'est
     * celle que privilégie le mode économie de données.
     */
    @Query(
        """
        SELECT * FROM channels
        WHERE portalId = :portalId
        ORDER BY groupKey ASC, qualityRank ASC, number ASC
        """,
    )
    suspend fun channelsOrderedByGroup(portalId: String): List<ChannelEntity>

    @Query(
        """
        SELECT * FROM channels
        WHERE portalId = :portalId AND groupKey = :groupKey
        ORDER BY qualityRank ASC, number ASC
        """,
    )
    suspend fun variants(portalId: String, groupKey: String): List<ChannelEntity>

    /** Nombre de diffusions enregistrées : sert à mesurer le cache disponible. */
    @Query("SELECT COUNT(*) FROM channels WHERE portalId = :portalId")
    suspend fun channelCount(portalId: String): Int

    /** Date de la dernière écriture, ou `null` si le cache est vide. */
    @Query("SELECT MAX(updatedAtMs) FROM channels WHERE portalId = :portalId")
    suspend fun lastUpdatedMs(portalId: String): Long?

    @Query("DELETE FROM channels")
    suspend fun clearAllChannels()

    @Query("DELETE FROM categories")
    suspend fun clearAllCategories()
}
