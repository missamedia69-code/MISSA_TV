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
import com.missa.tv.domain.model.PortalProfile
import com.missa.tv.domain.repository.PortalProfileSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Profils de portail conservés chiffrés sur l'appareil.
 *
 * L'URL du portail et l'adresse MAC sont des identifiants : ils ne sont jamais
 * écrits en clair sur le disque. Le fichier de DataStore ne contient que du
 * texte chiffré en AES-GCM, avec une clé détenue par le magasin de clés
 * d'Android — une clé qui ne quitte jamais l'appareil et qu'aucune autre
 * application ne peut lire.
 *
 * Le chiffrement s'appuie sur le même composant que la configuration distante
 * ([SecretCipher]) : une seule implémentation à auditer, un seul endroit où la
 * clé est gérée.
 */
@Singleton
class EncryptedPortalProfileSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: SecretCipher,
    private val json: Json,
    private val dispatchers: DispatcherProvider,
) : PortalProfileSource {

    private val dataStore: DataStore<Preferences> get() = context.missaProfilesStore

    override suspend fun profiles(): List<PortalProfile> =
        lire()?.profiles?.map { it.toModel() }.orEmpty()

    override suspend fun activeProfileId(): String? = prefs()[KEY_ACTIVE]

    override suspend fun setActiveProfileId(id: String) = withContext(dispatchers.io) {
        dataStore.edit { preferences -> preferences[KEY_ACTIVE] = id }
    }

    override suspend fun save(profile: PortalProfile) = withContext(dispatchers.io) {
        val existants = profiles().filterNot { it.id == profile.id }
        ecrire(existants + profile)
        if (activeProfileId() == null) {
            dataStore.edit { preferences -> preferences[KEY_ACTIVE] = profile.id }
        }
    }

    override suspend fun delete(id: String) = withContext(dispatchers.io) {
        val restants = profiles().filterNot { it.id == id }
        ecrire(restants)

        if (activeProfileId() == id) {
            val remplacant = restants.firstOrNull { it.enabled && it.isComplete }?.id
            dataStore.edit { preferences ->
                if (remplacant == null) {
                    preferences.remove(KEY_ACTIVE)
                } else {
                    preferences[KEY_ACTIVE] = remplacant
                }
            }
        }
    }

    override suspend fun syncRemote(
        remoteProfiles: List<PortalProfile>,
        defaultProfileId: String?,
    ) = withContext(dispatchers.io) {
        if (remoteProfiles.isEmpty()) return@withContext

        // Les profils distants font autorité sur ceux de même identifiant : ils
        // sont publiés par l'administrateur du déploiement. Les profils saisis à
        // la main et non concernés sont conservés.
        val distants = remoteProfiles.associateBy { it.id }
        val manuels = profiles().filterNot { distants.containsKey(it.id) }
        ecrire(manuels + remoteProfiles)

        val choisi = defaultProfileId?.takeIf { id -> remoteProfiles.any { it.id == id } }
        if (choisi != null && activeProfileId() != choisi) {
            dataStore.edit { preferences -> preferences[KEY_ACTIVE] = choisi }
            MissaLog.i("Profil par défaut défini par la configuration distante")
        }
    }

    /** Contenu déchiffré et analysé, ou `null` s'il est absent ou illisible. */
    private suspend fun lire(): ProfilsDto? {
        val chiffre = prefs()[KEY_PROFILES] ?: return null
        val document = cipher.decrypt(chiffre) ?: return null

        return runCatching { json.decodeFromString<ProfilsDto>(document) }.getOrElse { erreur ->
            // Contenu illisible (changement de clé, fichier altéré) : on repart
            // d'une liste vide plutôt que d'empêcher l'application de démarrer.
            MissaLog.e("Profils enregistrés illisibles, liste réinitialisée", erreur)
            null
        }
    }

    private suspend fun ecrire(profiles: List<PortalProfile>) {
        val document = json.encodeToString(
            ProfilsDto(profiles.sortedBy { it.id }.map { it.toDto() }),
        )
        val chiffre = cipher.encrypt(document)
        if (chiffre == null) {
            // Sans chiffrement, rien n'est écrit : jamais d'identifiant en clair.
            MissaLog.w("Profils non enregistrés : chiffrement impossible")
            return
        }
        dataStore.edit { preferences -> preferences[KEY_PROFILES] = chiffre }
    }

    private suspend fun prefs(): Preferences = dataStore.data.first()

    /** Liste de profils telle qu'elle est sérialisée avant chiffrement. */
    @Serializable
    private data class ProfilsDto(val profiles: List<PortalProfileDto> = emptyList())

    @Serializable
    private data class PortalProfileDto(
        val id: String,
        val name: String,
        val portalUrl: String,
        val mac: String,
        val enabled: Boolean = true,
    ) {
        fun toModel(): PortalProfile = PortalProfile(
            id = id,
            name = name,
            portalUrl = portalUrl,
            mac = mac,
            enabled = enabled,
        )
    }

    private fun PortalProfile.toDto(): PortalProfileDto = PortalProfileDto(
        id = id,
        name = name,
        portalUrl = portalUrl,
        mac = mac,
        enabled = enabled,
    )

    private companion object {
        /** Contenu chiffré des profils. */
        val KEY_PROFILES = stringPreferencesKey("profiles_payload")

        /**
         * Identifiant du profil utilisé en priorité.
         *
         * Conservé en clair : c'est un simple libellé technique, sans valeur
         * d'authentification (l'URL et la MAC, elles, sont chiffrées).
         */
        val KEY_ACTIVE = stringPreferencesKey("active_profile")
    }
}

/** Un seul DataStore pour les profils de connexion, partagé par l'application. */
private val Context.missaProfilesStore: DataStore<Preferences> by preferencesDataStore(
    name = "missa_profiles",
)
