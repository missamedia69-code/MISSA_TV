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
import com.missa.tv.domain.repository.PortalProfileSource
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
 *  2. une configuration plus ancienne que celle déjà appliquée est ignorée, ce
 *     qui empêche un retour en arrière de réglages ;
 *  3. en cas d'échec réseau, la configuration en place reste utilisée.
 *
 * Après une configuration acceptée, les profils de connexion qu'elle déclare
 * sont transmis à la source de profils, qui les fusionne avec ceux saisis à la
 * main.
 */
@Singleton
class RemoteConfigRepositoryImpl @Inject constructor(
    private val remote: ConfigRemoteDataSource,
    private val store: ConfigStore,
    private val parser: PortalConfigParser,
    private val profileSource: PortalProfileSource,
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

        if (!nouvelle.isNewerThan(actuelle)) {
            MissaLog.d(
                "Configuration distante version ${nouvelle.configVersion} " +
                    "non appliquée (déjà en version ${actuelle.configVersion})",
            )
            return AppResult.success(actuelle)
        }

        store.save(
            document = resultat.document,
            etag = resultat.etag,
            syncedAtMs = time.nowMs(),
        )

        if (nouvelle.profiles.isNotEmpty()) {
            profileSource.syncRemote(nouvelle.profiles, nouvelle.defaultProfileId)
        }

        MissaLog.i("Configuration distante appliquée (version ${nouvelle.configVersion})")
        return AppResult.success(nouvelle)
    }
}
