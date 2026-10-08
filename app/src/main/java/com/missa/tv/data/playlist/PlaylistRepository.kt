package com.missa.tv.data.playlist

import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.result.AppResult
import com.missa.tv.domain.playlist.PlaylistFailoverPolicy
import com.missa.tv.domain.repository.PlaylistSourceStore
import kotlinx.coroutines.withContext

/**
 * Chargement des entrées de chaînes depuis les playlists configurées.
 */
interface PlaylistRepository {

    /**
     * Entrées des playlists, obtenues par téléchargement des sources dans
     * l'ordre de bascule, ou à défaut reprises du cache.
     */
    suspend fun entries(): AppResult<List<M3uEntry>>
}

/**
 * Chargement des playlists avec bascule entre sources et repli sur le cache.
 *
 * Stratégie, conforme à la migration M3U :
 *  1. les sources exploitables sont essayées dans l'ordre défini par
 *     [PlaylistFailoverPolicy] ; la première qui répond met fin à la boucle ;
 *  2. chaque succès est mémorisé dans [cache] ;
 *  3. si toutes les sources échouent — ou s'il n'y en a aucune — les entrées du
 *     cache sont renvoyées, afin de ne pas priver l'utilisateur des chaînes de
 *     la dernière synchronisation réussie ;
 *  4. sans cache, la dernière erreur rencontrée est renvoyée.
 */
class PlaylistRepositoryImpl(
    private val downloader: PlaylistDownloader,
    private val sourceStore: PlaylistSourceStore,
    private val cache: PlaylistCache,
    private val dispatchers: DispatcherProvider,
    private val failoverPolicy: PlaylistFailoverPolicy = PlaylistFailoverPolicy(),
) : PlaylistRepository {

    override suspend fun entries(): AppResult<List<M3uEntry>> =
        withContext(dispatchers.io) {
            val candidats = failoverPolicy.candidates(sourceStore.sources())

            if (candidats.isEmpty()) {
                return@withContext repliCache(erreurSinon = AppError.MissingConfig)
            }

            var derniereErreur: AppError = AppError.PlaylistUnreachable
            for (source in candidats) {
                when (val resultat = downloader.download(source.url)) {
                    is AppResult.Success -> {
                        cache.store(resultat.value)
                        return@withContext resultat
                    }

                    is AppResult.Failure -> {
                        derniereErreur = resultat.error
                        if (!failoverPolicy.shouldFailover(resultat.error)) break
                        MissaLog.w("Source de playlist en échec, tentative suivante")
                    }
                }
            }

            repliCache(erreurSinon = derniereErreur)
        }

    /** Entrées mémorisées si disponibles, sinon l'erreur [erreurSinon]. */
    private suspend fun repliCache(erreurSinon: AppError): AppResult<List<M3uEntry>> {
        val memorisees = cache.load()
        return if (memorisees.isNotEmpty()) {
            MissaLog.i("Repli sur les chaînes mémorisées")
            AppResult.success(memorisees)
        } else {
            AppResult.failure(erreurSinon)
        }
    }
}
