package com.missa.tv.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.missa.tv.R
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.log.CrashRecorder
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.result.AppResult
import com.missa.tv.data.local.SettingsStore
import com.missa.tv.data.remote.portal.PortalEndpointResolver
import com.missa.tv.domain.model.PortalProfile
import com.missa.tv.domain.model.QualityMode
import com.missa.tv.domain.repository.PortalProfileSource
import com.missa.tv.domain.repository.PortalRepository
import com.missa.tv.domain.repository.RemoteConfigRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Résultat d'un test de connexion au portail. */
sealed interface ConnectionCheck {

    /** Connexion réussie : le portail a répondu et fourni ses chaînes. */
    data class Nombre(val chaines: Int) : ConnectionCheck

    /** Échec : le message est celui de l'erreur rencontrée. */
    data class Erreur(@StringRes val messageRes: Int) : ConnectionCheck
}

/** État des réglages. */
data class SettingsUiState(
    val profiles: List<PortalProfile> = emptyList(),
    val activeProfileId: String? = null,
    val qualityMode: QualityMode? = null,
    val qualityLocked: Boolean = false,
    val lastSyncMs: Long = 0L,
    val isSyncing: Boolean = false,
    /** Message d'erreur de saisie, exprimé en ressource de chaîne. */
    @StringRes val formError: Int? = null,
    val profileSaved: Boolean = false,
    val versionName: String = "",
    val versionCode: Int = 0,
    /**
     * Dernier plantage enregistré, en une ligne, ou `null` si l'application ne
     * s'est jamais arrêtée anormalement depuis son installation.
     *
     * La trace est masquée (adresses MAC, URL, jetons) : elle peut être
     * transmise telle quelle pour diagnostic.
     */
    val dernierIncident: String? = null,
    /**
     * Résultat de la dernière vérification manuelle de la configuration, exprimé
     * en ressource de chaîne. `null` tant qu'aucune vérification n'a échoué.
     */
    @StringRes val syncError: Int? = null,
    /** Vrai pendant un test de connexion au portail. */
    val isTestingConnection: Boolean = false,
    /**
     * Résultat du dernier test de connexion.
     *
     * `Nombre` = connexion réussie, avec le nombre de chaînes reçues ;
     * `Erreur` = échec, avec le message à afficher. C'est la réponse immédiate à
     * « j'ai configuré mon portail, que se passe-t-il ? ».
     */
    val connection: ConnectionCheck? = null,
) {
    val hasProfiles: Boolean get() = profiles.isNotEmpty()
}

/**
 * Réglages : profils de connexion, qualité par défaut, configuration distante.
 *
 * Les identifiants saisis ici ne sont jamais affichés à nouveau en clair : la
 * liste ne montre que le nom du profil et une adresse MAC masquée. C'est une
 * précaution délibérée — une capture d'écran de l'écran de réglages ne doit
 * jamais révéler l'accès de l'utilisateur.
 */
class SettingsViewModel @Inject constructor(
    private val profileSource: PortalProfileSource,
    private val settingsStore: SettingsStore,
    private val configRepository: RemoteConfigRepository,
    private val portalRepository: PortalRepository,
    private val crashRecorder: CrashRecorder,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUiState(
            versionName = com.missa.tv.BuildConfig.VERSION_NAME,
            versionCode = com.missa.tv.BuildConfig.VERSION_CODE,
        ),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        rafraichir()
    }

    /** Relit profils, préférences et date de dernière synchronisation. */
    fun rafraichir() {
        viewModelScope.launch(dispatchers.io) {
            val preferences = settingsStore.playback()
            _state.update {
                it.copy(
                    profiles = profileSource.profiles(),
                    activeProfileId = profileSource.activeProfileId(),
                    qualityMode = preferences.qualityMode,
                    qualityLocked = preferences.qualityLocked,
                    lastSyncMs = configRepository.lastSyncMs(),
                    dernierIncident = crashRecorder.dernierIncident(),
                )
            }
        }
    }

    /**
     * Enregistre un profil saisi à la main.
     *
     * La validation reprend exactement les règles du schéma de configuration :
     * une adresse qui ne commence pas par `http` ou une MAC mal formée sont
     * refusées ici, avec un message précis, plutôt que d'échouer plus tard au
     * moment de regarder une chaîne.
     */
    fun enregistrerProfil(nom: String, url: String, mac: String) {
        val nomNettoye = nom.trim()
        val urlNettoyee = url.trim()
        val macNettoyee = mac.trim().uppercase()

        val erreur = when {
            nomNettoye.isBlank() -> R.string.validation_name_required
            urlNettoyee.isBlank() -> R.string.validation_url_required
            !PortalEndpointResolver.isValidPortalUrl(urlNettoyee) -> R.string.validation_url_invalid
            macNettoyee.isBlank() -> R.string.validation_mac_required
            !MAC_VALIDE.matches(macNettoyee) -> R.string.validation_mac_invalid
            else -> null
        }

        if (erreur != null) {
            _state.update { it.copy(formError = erreur, profileSaved = false) }
            return
        }

        viewModelScope.launch(dispatchers.io) {
            // L'identifiant est dérivé du nom : il reste lisible et stable, ce qui
            // évite d'accumuler des doublons à chaque enregistrement.
            val identifiant = nomNettoye
                .lowercase()
                .replace(IDENTIFIANT_INVALIDE, "-")
                .trim('-')
                .take(64)
                .ifBlank { "profil-${System.currentTimeMillis()}" }

            profileSource.save(
                PortalProfile(
                    id = identifiant,
                    name = nomNettoye,
                    portalUrl = urlNettoyee,
                    mac = macNettoyee,
                ),
            )
            MissaLog.i("Profil de connexion enregistré (identifiant $identifiant)")

            _state.update { it.copy(formError = null, profileSaved = true) }
            rafraichir()
        }
    }

    /** Supprime un profil. */
    fun supprimerProfil(id: String) {
        viewModelScope.launch(dispatchers.io) {
            profileSource.delete(id)
            rafraichir()
        }
    }

    /** Choisit le profil utilisé en priorité. */
    fun activerProfil(id: String) {
        viewModelScope.launch(dispatchers.io) {
            profileSource.setActiveProfileId(id)
            rafraichir()
        }
    }

    /** Définit le mode de qualité appliqué par défaut ; `null` rétablit l'automatique. */
    fun definirQualite(mode: QualityMode?) {
        viewModelScope.launch(dispatchers.io) {
            settingsStore.setQualityMode(mode, locked = false)
            rafraichir()
        }
    }

    /**
     * Teste réellement la connexion au portail configuré.
     *
     * L'application ouvre une session puis demande le catalogue : c'est le même
     * chemin que celui emprunté par l'écran d'accueil. Le résultat est donc
     * fiable, et non une simple vérification de forme de l'adresse.
     */
    fun testerConnexion() {
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(isTestingConnection = true, connection = null) }

            val resultat = when (val session = portalRepository.connect()) {
                is AppResult.Failure -> ConnectionCheck.Erreur(session.error.messageRes)
                is AppResult.Success -> when (val catalogue = portalRepository.catalog(session.value)) {
                    is AppResult.Failure -> ConnectionCheck.Erreur(catalogue.error.messageRes)
                    is AppResult.Success -> ConnectionCheck.Nombre(catalogue.value.channels.size)
                }
            }

            MissaLog.i(
                when (resultat) {
                    is ConnectionCheck.Nombre -> "Test de connexion réussi"
                    is ConnectionCheck.Erreur -> "Test de connexion en échec"
                },
            )

            _state.update { it.copy(isTestingConnection = false, connection = resultat) }
        }
    }

    /** Vérifie immédiatement la configuration distante. */
    fun verifierConfiguration() {
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(isSyncing = true, syncError = null) }
            val resultat = configRepository.refresh()
            val erreur = when (resultat) {
                is AppResult.Failure -> {
                    MissaLog.w("Vérification manuelle de la configuration sans succès")
                    resultat.error.messageRes
                }
                is AppResult.Success -> null
            }
            _state.update { it.copy(isSyncing = false, syncError = erreur) }
            rafraichir()
        }
    }

    /** Efface le message de saisie, dès que l'utilisateur corrige un champ. */
    fun effacerMessage() {
        _state.update { it.copy(formError = null, profileSaved = false) }
    }

    private companion object {
        /** Même règle que le schéma de configuration : six octets hexadécimaux. */
        val MAC_VALIDE = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

        /** Caractères interdits dans un identifiant de profil. */
        val IDENTIFIANT_INVALIDE = Regex("[^a-z0-9_-]+")
    }
}
