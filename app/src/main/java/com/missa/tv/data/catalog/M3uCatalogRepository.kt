package com.missa.tv.data.catalog

import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.result.AppResult
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.local.CatalogCache
import com.missa.tv.data.playlist.M3uChannelMapper
import com.missa.tv.data.playlist.PlaylistRepository
import com.missa.tv.domain.model.Category
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.PortalCatalog
import com.missa.tv.domain.repository.CatalogRepository
import com.missa.tv.domain.repository.PlaylistSourceStore
import kotlinx.coroutines.withContext

/**
 * Catalogue construit à partir des playlists M3U.
 *
 * Enchaîne trois temps :
 *  1. téléchargement des entrées — la bascule entre sources et le repli sur le
 *     cache de playlists sont déjà gérés par [PlaylistRepository] ;
 *  2. conversion des entrées brutes en chaînes du domaine ;
 *  3. déduction des catégories depuis les groupes déclarés dans les playlists
 *     (`group-title`), puis mémorisation du résultat dans le catalogue local.
 *
 * La mémorisation locale sert l'ouverture immédiate et le repli hors ligne :
 * son échec n'a aucune conséquence visible, le prochain chargement la refera.
 */
class M3uCatalogRepository(
    private val playlistRepository: PlaylistRepository,
    private val sourceStore: PlaylistSourceStore,
    private val catalogCache: CatalogCache,
    private val dispatchers: DispatcherProvider,
    private val timeSource: TimeSource,
) : CatalogRepository {

    override suspend fun load(): AppResult<PortalCatalog> = withContext(dispatchers.io) {
        when (val resultat = playlistRepository.entries()) {
            is AppResult.Failure -> resultat

            is AppResult.Success -> {
                val channels = M3uChannelMapper.toChannels(resultat.value)
                val catalog = PortalCatalog(
                    categories = categoriesDe(channels),
                    channels = channels,
                    loadedAtMs = timeSource.nowMs(),
                )

                runCatching { catalogCache.save(cacheKey(), catalog) }
                    .onFailure { erreur ->
                        MissaLog.w("Catalogue local non enregistré : ${erreur.javaClass.simpleName}")
                    }

                AppResult.success(catalog)
            }
        }
    }

    override suspend fun cacheKey(): String =
        sourceStore.sources().firstOrNull { it.isComplete }?.id ?: CLE_PAR_DEFAUT

    /**
     * Catégories déduites des groupes des chaînes.
     *
     * Les playlists déclarent un groupe par chaîne (`group-title`) : l'identifiant
     * de catégorie reprend ce libellé tel quel, ce qui le fait correspondre au
     * `categoryId` posé par le convertisseur sur chaque chaîne.
     */
    private fun categoriesDe(channels: List<Channel>): List<Category> =
        channels
            .mapNotNull { it.categoryId?.takeIf(String::isNotBlank) }
            .distinct()
            .map { Category(id = it, title = it) }

    private companion object {
        /** Clé utilisée tant qu'aucune source complète n'est connue. */
        const val CLE_PAR_DEFAUT = "m3u"
    }
}
