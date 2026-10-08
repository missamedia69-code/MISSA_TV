package com.missa.tv.data.local

import com.google.common.truth.Truth.assertThat
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.local.db.FavoriteDao
import com.missa.tv.data.local.db.FavoriteEntity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Mémorisation des favoris de l'utilisateur.
 *
 * La doublure reproduit la sémantique des requêtes SQL (ajout avec
 * remplacement, comptage, lecture ordonnée) ; le SQL réel est vérifié par Room
 * à la compilation. Ce qui est testé ici : la bascule, la lecture ordonnée et
 * la purge par catalogue.
 */
@DisplayName("Favoris mémorisés localement")
class FavoriteCacheTest {

    /** Horloge qui avance à chaque lecture : chaque ajout a un horodatage distinct. */
    private class HorlogeIncrementale : TimeSource {
        private var suivant = 1_000L

        override fun nowMs(): Long = suivant++
    }

    /** Doublure en mémoire du DAO des favoris. */
    private class FakeFavoriteDao : FavoriteDao {
        private val favoris = mutableListOf<FavoriteEntity>()

        override suspend fun add(favorite: FavoriteEntity) {
            favoris.removeAll { it.portalId == favorite.portalId && it.groupKey == favorite.groupKey }
            favoris += favorite
        }

        override suspend fun remove(portalId: String, groupKey: String) {
            favoris.removeAll { it.portalId == portalId && it.groupKey == groupKey }
        }

        override suspend fun count(portalId: String, groupKey: String): Int =
            favoris.count { it.portalId == portalId && it.groupKey == groupKey }

        override suspend fun groupKeys(portalId: String): List<String> =
            favoris
                .filter { it.portalId == portalId }
                .sortedBy { it.addedAtMs }
                .map { it.groupKey }

        override suspend fun deletePortalFavorites(portalId: String) {
            favoris.removeAll { it.portalId == portalId }
        }
    }

    private fun cache(dao: FakeFavoriteDao = FakeFavoriteDao()): FavoriteCache =
        FavoriteCache(dao = dao, timeSource = HorlogeIncrementale())

    @Test
    @DisplayName("la bascule ajoute un favori absent")
    fun `bascule ajoute un favori`() = runTest {
        val cache = cache()

        val ajoute = cache.toggle("principale", "info|tf1")

        assertThat(ajoute).isTrue()
        assertThat(cache.isFavorite("principale", "info|tf1")).isTrue()
        assertThat(cache.favorites("principale")).containsExactly("info|tf1")
    }

    @Test
    @DisplayName("la bascule retire un favori déjà présent")
    fun `bascule retire un favori existant`() = runTest {
        val cache = cache()
        cache.add("principale", "info|tf1")

        val retire = cache.toggle("principale", "info|tf1")

        assertThat(retire).isFalse()
        assertThat(cache.isFavorite("principale", "info|tf1")).isFalse()
        assertThat(cache.favorites("principale")).isEmpty()
    }

    @Test
    @DisplayName("les favoris sont listés dans l'ordre d'ajout")
    fun `favoris dans l ordre d ajout`() = runTest {
        val cache = cache()
        cache.add("principale", "sport|match")
        cache.add("principale", "info|tf1")
        cache.add("principale", "cinema|film")

        assertThat(cache.favorites("principale"))
            .containsExactly("sport|match", "info|tf1", "cinema|film")
            .inOrder()
    }

    @Test
    @DisplayName("les favoris d'un autre catalogue ne se mélangent pas")
    fun `favoris isoles par catalogue`() = runTest {
        val cache = cache()
        cache.add("principale", "info|tf1")
        cache.add("secours", "info|france2")

        assertThat(cache.favorites("principale")).containsExactly("info|tf1")
        assertThat(cache.favorites("secours")).containsExactly("info|france2")
    }

    @Test
    @DisplayName("la purge retire tous les favoris d'un catalogue")
    fun `purge des favoris d un catalogue`() = runTest {
        val dao = FakeFavoriteDao()
        val cache = cache(dao)
        cache.add("principale", "info|tf1")
        cache.add("principale", "sport|match")

        dao.deletePortalFavorites("principale")

        assertThat(cache.favorites("principale")).isEmpty()
    }
}
