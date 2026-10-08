package com.missa.tv.data.repository

import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.result.AppResult
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.local.ConfigStore
import com.missa.tv.data.remote.config.ConfigFetchResult
import com.missa.tv.data.remote.config.ConfigRemoteDataSource
import com.missa.tv.data.remote.config.PortalConfigParser
import com.missa.tv.domain.model.RemoteConfig
import com.missa.tv.domain.repository.PlaylistSourceStore
import com.missa.tv.domain.repository.RemoteConfigRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Synchronisation de la configuration distante.
 *
 * Règles de prudence appliquées, dans cet ordre :
 *  1. le document reçu est analysé avant d'être enregistré : une configuration
 *     illisible ou de schéma inconnu est refusée en bloc ;
 *  2. la requête est conditionnelle (empreinte ETag) : un document n'est
 *     téléchargé et appliqué que s'il a changé ;
 *  3. en cas d'échec réseau, la configuration en place reste utilisée.
 *
 * Après une configuration acceptée, les sources de playlists qu'elle déclare
 * sont enregistrées dans le magasin chiffré des sources.
 */
@Singleton
class RemoteConfigRepositoryImpl @Inject constructor(
    private val remote: ConfigRemoteDataSource,
    private val store: ConfigStore,
    private val parser: PortalConfigParser,
    private val sourceStore: PlaylistSourceStore,
    private val dispatchers: DispatcherProvider,
    private val time: TimeSource,
) : RemoteConfigRepository {

    override val config: Flow<RemoteConfig> = store.observe()

    override suspend fun current(): RemoteConfig = store.stored().config

    override suspend fun lastSyncMs(): Long = store.stored().syncedAtMs

    override suspend fun refresh(): AppResult<RemoteConfig> = withContext(dispatchers.io) {
        val actuel = store.stored()

        when (val resultat = remote.fetch(actuel.etag)) {
            is ConfigFetchResult.NotModified -> {
                // Le fichier n'a pas changé : l'empreinte reste valable et le
                // document conservé reste la référence. Seule la date de
                // vérification est rafraîchie.
                store.touch(time.nowMs())
                MissaLog.d("Configuration distante inchangée")
                AppResult.success(actuel.config)
            }

            is ConfigFetchResult.Fetched -> appliquer(resultat, actuel.config)

            is ConfigFetchResult.Failed -> {
                MissaLog.w("Configuration distante non rafraîchie, configuration en place conservée")
                AppResult.failure(resultat.error)
            }
        }
    }

    private suspend fun appliquer(
        resultat: ConfigFetchResult.Fetched,
        actuelle: RemoteConfig,
    ): AppResult<RemoteConfig> {
        val nouvelle = parser.parse(resultat.document)
        if (nouvelle == null) {
            MissaLog.w("Configuration distante refusée, précédente conservée")
            return AppResult.failure(AppError.InvalidConfig)
        }

        // Le rafraîchissement est conditionnel : ce document n'est reçu que si
        // son empreinte a changé ; il fait donc autorité et remplace la version
        // en place, sans numéro de version à comparer.
        store.save(
            document = resultat.document,
            etag = resultat.etag,
            syncedAtMs = time.nowMs(),
        )

        if (nouvelle.playlists.isNotEmpty()) {
            sourceStore.saveAll(nouvelle.playlists)
        }

        MissaLog.i("Configuration distante appliquée (${nouvelle.playlists.size} playlist(s))")
        return AppResult.success(nouvelle)
    }
}
