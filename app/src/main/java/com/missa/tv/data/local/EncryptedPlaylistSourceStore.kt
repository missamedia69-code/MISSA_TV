package com.missa.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.security.SecretCipher
import com.missa.tv.domain.model.PlaylistSource
import com.missa.tv.domain.repository.PlaylistSourceStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Sources de playlists conservées chiffrées sur l'appareil.
 *
 * L'URL d'une playlist est un identifiant : elle n'est jamais écrite en clair
 * sur le disque. Le fichier de DataStore ne contient que du texte chiffré en
 * AES-GCM, avec une clé détenue par le magasin de clés d'Android — une clé qui
 * ne quitte jamais l'appareil.
 *
 * Le chiffrement s'appuie sur le même composant que la configuration distante
 * et les profils ([SecretCipher]) : une seule implémentation à auditer.
 */
@Singleton
class EncryptedPlaylistSourceStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: SecretCipher,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
) : PlaylistSourceStore {

    private val dataStore: DataStore<Preferences> get() = context.missaPlaylistSourcesStore

    override suspend fun sources(): List<PlaylistSource> =
        lire()?.sources?.map { it.toModel() }.orEmpty()

    override suspend fun saveAll(sources: List<PlaylistSource>): Unit =
        withContext(dispatchers.io) {
            ecrire(sources)
        }

    /** Contenu déchiffré et analysé, ou `null` s'il est absent ou illisible. */
    private suspend fun lire(): SourcesDto? {
        val chiffre = prefs()[KEY_SOURCES] ?: return null
        val document = cipher.decrypt(chiffre) ?: return null

        return runCatching { json.decodeFromString<SourcesDto>(document) }.getOrElse { erreur ->
            // Contenu illisible (changement de clé, fichier altéré) : on repart
            // d'une liste vide plutôt que d'empêcher l'application de démarrer.
            MissaLog.e("Sources enregistrées illisibles, liste réinitialisée", erreur)
            null
        }
    }

    private suspend fun ecrire(sources: List<PlaylistSource>) {
        val document = json.encodeToString(
            SourcesDto(sources.sortedBy { it.id }.map { it.toDto() }),
        )
        val chiffre = cipher.encrypt(document)
        if (chiffre == null) {
            // Sans chiffrement, rien n'est écrit : jamais d'identifiant en clair.
            MissaLog.w("Sources non enregistrées : chiffrement impossible")
            return
        }
        dataStore.edit { preferences -> preferences[KEY_SOURCES] = chiffre }
    }

    private suspend fun prefs(): Preferences = try {
        dataStore.data.first()
    } catch (erreur: Exception) {
        MissaLog.w("Lecture du magasin de sources impossible", erreur)
        emptyPreferences()
    }

    /** Liste de sources telle qu'elle est sérialisée avant chiffrement. */
    @Serializable
    private data class SourcesDto(val sources: List<PlaylistSourceDto> = emptyList())

    @Serializable
    private data class PlaylistSourceDto(
        val id: String,
        val name: String,
        val url: String,
        val epgUrl: String? = null,
        val enabled: Boolean = true,
    ) {
        fun toModel(): PlaylistSource = PlaylistSource(
            id = id,
            name = name,
            url = url,
            epgUrl = epgUrl,
            enabled = enabled,
        )
    }

    private fun PlaylistSource.toDto(): PlaylistSourceDto = PlaylistSourceDto(
        id = id,
        name = name,
        url = url,
        epgUrl = epgUrl,
        enabled = enabled,
    )

    private companion object {
        /** Contenu chiffré des sources. */
        val KEY_SOURCES = stringPreferencesKey("playlist_sources_payload")
    }
}

/** Un seul DataStore pour les sources de playlists, partagé par l'application. */
private val Context.missaPlaylistSourcesStore: DataStore<Preferences> by preferencesDataStore(
    name = "missa_playlist_sources",
)
