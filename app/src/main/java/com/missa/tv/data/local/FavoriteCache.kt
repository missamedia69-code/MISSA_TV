package com.missa.tv.data.local

import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.local.db.FavoriteDao
import com.missa.tv.data.local.db.FavoriteEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Favoris de l'utilisateur, en base Room.
 *
 * Un favori porte sur un groupe de diffusions (sa clé `groupKey`) : mettre
 * « TF1 » en favori rend la chaîne accessible quelle que soit la qualité lue.
 * L'ordre d'ajout est conservé afin d'afficher les favoris dans un ordre
 * stable.
 */
@Singleton
class FavoriteCache @Inject constructor(
    private val dao: FavoriteDao,
    private val timeSource: TimeSource,
) {

    /** Vrai si le groupe est en favori. */
    suspend fun isFavorite(portalId: String, groupKey: String): Boolean =
        dao.count(portalId, groupKey) > 0

    /** Clés des groupes en favori, dans l'ordre d'ajout. */
    suspend fun favorites(portalId: String): Set<String> =
        dao.groupKeys(portalId).toSet()

    /** Ajoute un groupe aux favoris. */
    suspend fun add(portalId: String, groupKey: String) {
        dao.add(FavoriteEntity(portalId, groupKey, timeSource.nowMs()))
    }

    /** Retire un groupe des favoris. */
    suspend fun remove(portalId: String, groupKey: String) {
        dao.remove(portalId, groupKey)
    }

    /**
     * Bascule un groupe : l'ajoute s'il est absent, le retire sinon.
     *
     * @return `true` si le groupe est désormais en favori, `false` s'il en a
     * été retiré.
     */
    suspend fun toggle(portalId: String, groupKey: String): Boolean =
        if (isFavorite(portalId, groupKey)) {
            remove(portalId, groupKey)
            false
        } else {
            add(portalId, groupKey)
            true
        }
}
