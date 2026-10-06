package com.missa.tv.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.log.MissaLog
import com.missa.tv.domain.model.PortalProfile
import com.missa.tv.domain.repository.PortalProfileSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Profils de portail chiffrés sur l'appareil.
 *
 * L'URL du portail et l'adresse MAC sont des identifiants : ils sont stockés
 * dans un fichier chiffré par une clé conservée dans le magasin de clés
 * matériel d'Android (Keystore), inaccessible aux autres applications et non
 * extractible de l'appareil.
 *
 * Le reste de la configuration (préférences d'affichage, cache) reste dans
 * DataStore ; seuls les identifiants justifient ce traitement particulier.
 */
@Singleton
class EncryptedPortalProfileSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) : PortalProfileSource {

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun profiles(): List<PortalProfile> = withContext(dispatchers.io) {
        val brut = prefs.getString(KEY_PROFILES, null) ?: return@withContext emptyList()
        runCatching {
            json.decodeFromString<List<PortalProfileDto>>(brut).map { it.toModel() }
        }.getOrElse { erreur ->
            // Un contenu illisible (mise à jour de format, fichier corrompu) ne
            // doit pas empêcher l'application de démarrer : on repart d'une
            // liste vide et l'utilisateur resaisit ses identifiants.
            MissaLog.e("Profils enregistrés illisibles, liste réinitialisée", erreur)
            emptyList()
        }
    }

    override suspend fun activeProfileId(): String? = withContext(dispatchers.io) {
        prefs.getString(KEY_ACTIVE, null)
    }

    override suspend fun setActiveProfileId(id: String) = withContext(dispatchers.io) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        Unit
    }

    override suspend fun save(profile: PortalProfile) = withContext(dispatchers.io) {
        val existants = profiles().filterNot { it.id == profile.id }
        val nouveaux = (existants + profile).sortedBy { it.id }
        ecrire(nouveaux)
        if (activeProfileId() == null) {
            prefs.edit().putString(KEY_ACTIVE, profile.id).apply()
        }
    }

    override suspend fun delete(id: String) = withContext(dispatchers.io) {
        val restants = profiles().filterNot { it.id == id }
        ecrire(restants)
        if (activeProfileId() == id) {
            val remplacant = restants.firstOrNull { it.enabled && it.isComplete }?.id
            prefs.edit().apply {
                if (remplacant == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, remplacant)
            }.apply()
        }
    }

    private fun ecrire(profiles: List<PortalProfile>) {
        val dto = profiles.map { it.toDto() }
        prefs.edit().putString(KEY_PROFILES, json.encodeToString(dto)).apply()
    }

    /** Représentation sérialisée d'un profil. */
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
        const val FILE_NAME = "missa_portal_profiles"

        /** Clé des profils ; la valeur est chiffrée par le fichier lui-même. */
        const val KEY_PROFILES = "profiles"

        /** Profil utilisé en priorité. */
        const val KEY_ACTIVE = "active_profile"
    }
}
