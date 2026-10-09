package com.missa.tv.data.catalog

import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.result.AppResult
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.local.CatalogCache
import com.missa.tv.data.remote.catalog.CatalogJsonParser
import com.missa.tv.data.remote.catalog.TestedCatalogSource
import com.missa.tv.domain.model.Catalog
import com.missa.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.withContext

/**
 * Catalogue des chaînes **testées** : seules les chaînes qui ont répondu au
 * contrôle publié par le workflow de configuration sont fournies.
 *
 * Le catalogue reçu est mémorisé localement sous sa propre clé, comme le
 * catalogue M3U : l'ouverture immédiate et le repli hors connexion profitent
 * ainsi de la dernière liste testée connue.
 */
class TestedCatalogRepository(
    private val dataSource: TestedCatalogSource,
    private val parser: CatalogJsonParser,
    private val catalogCache: CatalogCache,
    private val dispatchers: DispatcherProvider,
    private val timeSource: TimeSource,
) : CatalogRepository {

    override suspend fun load(): AppResult<Catalog> = withContext(dispatchers.io) {
        val document = dataSource.fetch()
            ?: return@withContext AppResult.failure(AppError.ConfigNotFound)

        val catalogue = parser.parse(document)?.copy(loadedAtMs = timeSource.nowMs())
        when {
            catalogue == null -> AppResult.failure(AppError.InvalidConfig)
            catalogue.channels.isEmpty() -> {
                MissaLog.w("Catalogue testé sans aucune chaîne")
                AppResult.failure(AppError.InvalidConfig)
            }
            else -> {
                runCatching { catalogCache.save(CLE, catalogue) }
                    .onFailure { erreur ->
                        MissaLog.w("Catalogue testé non enregistré : ${erreur.javaClass.simpleName}")
                    }
                AppResult.success(catalogue)
            }
        }
    }

    override suspend fun cacheKey(): String = CLE

    companion object {
        /** Clé du catalogue testé dans le cache local. */
        const val CLE = "catalogue-teste"
    }
}
