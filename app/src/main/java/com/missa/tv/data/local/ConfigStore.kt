package com.missa.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.security.SecretCipher
import com.missa.tv.data.remote.config.PortalConfigParser
import com.missa.tv.domain.model.RemoteConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Configuration telle qu'elle est conservée sur l'appareil. */
data class StoredConfig(
    val config: RemoteConfig,
    /** Empreinte du document reçu, réutilisée pour la requête conditionnelle. */
    val etag: String?,
    /** Date de la dernière synchronisation réussie, en millisecondes. */
    val syncedAtMs: Long,
)

/**
 * Conservation de la configuration distante sur l'appareil.
 *
 * Le document reçu est stocké **chiffré** : le fichier de DataStore ne contient
 * que du texte chiffré, inexploitable hors de l'application, car la clé est
 * détenue par le magasin de clés d'Android.
 *
 * C'est le document d'origine qui est conservé, pas une reconstruction : la
 * relecture passe donc par le même analyseur que la réception, et un fichier
 * modifié à la main ne peut pas injecter de réglages.
 */
interface ConfigStore {

    /** Configuration courante ; valeurs par défaut si rien n'est stocké. */
    fun observe(): Flow<RemoteConfig>

    /** Configuration stockée, avec son empreinte et sa date de synchronisation. */
    suspend fun stored(): StoredConfig

    /** Enregistre un document reçu et l'empreinte associée. */
    suspend fun save(document: String, etag: String?, syncedAtMs: Long)

    /**
     * Met à jour la seule date de synchronisation.
     *
     * Utilisé lorsque le serveur répond « non modifié » : le document conservé
     * reste la référence, il ne doit surtout pas être réécrit.
     */
    suspend fun touch(syncedAtMs: Long)
}

@Singleton
class DataStoreConfigStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: SecretCipher,
    private val parser: PortalConfigParser,
    private val dispatchers: DispatcherProvider,
) : ConfigStore {

    private val dataStore: DataStore<Preferences> get() = context.missaConfigStore

    override fun observe(): Flow<RemoteConfig> = preferences()
        .map { decode(it[KEY_PAYLOAD]) ?: RemoteConfig.DEFAULTS }
        .flowOn(dispatchers.io)

    override suspend fun stored(): StoredConfig = withContext(dispatchers.io) {
        val preferences = preferences().first()
        StoredConfig(
            config = decode(preferences[KEY_PAYLOAD]) ?: RemoteConfig.DEFAULTS,
            etag = preferences[KEY_ETAG],
            syncedAtMs = preferences[KEY_SYNCED_AT] ?: 0L,
        )
    }

    override suspend fun save(document: String, etag: String?, syncedAtMs: Long) {
        val chiffre = cipher.encrypt(document)
        if (chiffre == null) {
            // Sans chiffrement, rien n'est écrit : mieux vaut conserver la
            // configuration précédente que d'écrire des identifiants en clair.
            MissaLog.w("Configuration non enregistrée : chiffrement impossible")
            return
        }

        withContext(dispatchers.io) {
            dataStore.edit { preferences ->
                preferences[KEY_PAYLOAD] = chiffre
                // Une empreinte sans document valide provoquerait une requête
                // conditionnelle qui ne renverrait jamais de contenu.
                if (etag.isNullOrBlank()) {
                    preferences.remove(KEY_ETAG)
                } else {
                    preferences[KEY_ETAG] = etag
                }
                preferences[KEY_SYNCED_AT] = syncedAtMs
            }
        }
    }

    override suspend fun touch(syncedAtMs: Long) {
        withContext(dispatchers.io) {
            dataStore.edit { preferences -> preferences[KEY_SYNCED_AT] = syncedAtMs }
        }
    }

    /** Flux des préférences, tolérant aux erreurs de lecture. */
    private fun preferences(): Flow<Preferences> = dataStore.data.catch { erreur ->
        MissaLog.e("Lecture de la configuration impossible, valeurs par défaut utilisées", erreur)
        emit(emptyPreferences())
    }

    private fun decode(payload: String?): RemoteConfig? =
        payload?.let { cipher.decrypt(it) }?.let { parser.parse(it) }

    private companion object {
        val KEY_PAYLOAD = stringPreferencesKey("config_payload")
        val KEY_ETAG = stringPreferencesKey("config_etag")
        val KEY_SYNCED_AT = longPreferencesKey("config_synced_at")
    }
}

/** Un seul DataStore pour la configuration, partagé par toute l'application. */
private val Context.missaConfigStore: DataStore<Preferences> by preferencesDataStore(
    name = "missa_config",
)
