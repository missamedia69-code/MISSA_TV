package com.missa.tv.data.local.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Favori de l'utilisateur (table `favorites`).
 *
 * Un favori porte sur un **groupe** de diffusions (sa clé `groupKey`), pas sur
 * une qualité isolée : quand l'utilisateur met « TF1 » en favori, il retrouve la
 * chaîne quelle que soit la qualité lue. La table ne contient ni adresse de
 * playlist ni jeton : elle peut être effacée sans conséquence sur la
 * configuration.
 */
@Entity(
    tableName = "favorites",
    primaryKeys = ["portalId", "groupKey"],
)
data class FavoriteEntity(
    /** Clé du catalogue auquel se rattache le favori. */
    val portalId: String,
    /** Clé du groupe de diffusions mis en favori. */
    val groupKey: String,
    /** Horodatage de l'ajout : il fixe l'ordre d'affichage des favoris. */
    val addedAtMs: Long,
)

/**
 * Accès aux favoris.
 *
 * Les requêtes sont validées par Room à la compilation : une erreur de SQL ne
 * peut pas atteindre l'exécution.
 */
@Dao
interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE portalId = :portalId AND groupKey = :groupKey")
    suspend fun remove(portalId: String, groupKey: String)

    /** Nombre de fois qu'un groupe est en favori : 0 ou 1 ici. */
    @Query("SELECT COUNT(*) FROM favorites WHERE portalId = :portalId AND groupKey = :groupKey")
    suspend fun count(portalId: String, groupKey: String): Int

    /** Clés des groupes en favori, dans l'ordre d'ajout. */
    @Query("SELECT groupKey FROM favorites WHERE portalId = :portalId ORDER BY addedAtMs ASC")
    suspend fun groupKeys(portalId: String): List<String>

    @Query("DELETE FROM favorites WHERE portalId = :portalId")
    suspend fun deletePortalFavorites(portalId: String)
}
