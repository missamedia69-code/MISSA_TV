package com.missa.tv.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.log.CrashRecorder
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.result.AppResult
import com.missa.tv.data.catalog.TestedCatalogRepository
import com.missa.tv.data.local.CatalogCache
import com.missa.tv.data.local.SettingsStore
import com.missa.tv.domain.model.PlaylistSource
import com.missa.tv.domain.model.QualityMode
import com.missa.tv.domain.repository.CatalogRepository
import com.missa.tv.domain.repository.RemoteConfigRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** État des réglages. */
data class SettingsUiState(
    /**
     * Sources de playlists déclarées par la configuration distante, en lecture
     * seule.
     *
     * La configuration est gérée en ligne (dépôt privé) : l'application ne
     * permet ni saisie ni modification. Seuls les noms sont montrés ; l'adresse
     * d'une playlist est un identifiant sensible et n'est jamais affichée.
     */
    val playlists: List<PlaylistSource> = emptyList(),
    /** Vrai si la liste affichée vient du catalogue testé plutôt que des playlists. */
    val fromTestedCatalog: Boolean = false,
    /** Nombre de diffusions mémorisées pour la source courante. */
    val channelCount: Int = 0,
    /** Date du dernier enregistrement du catalogue local, `null` s'il est vide. */
    val catalogUpdatedMs: Long? = null,
    /** Vrai pendant le retéléchargement du catalogue. */
    val isRefreshing: Boolean = false,
    /** Échec de la dernière actualisation des chaînes. */
    @StringRes val refreshError: Int? = null,
    val qualityMode: QualityMode? = null,
    val qualityLocked: Boolean = false,
    val lastSyncMs: Long = 0L,
    val isSyncing: Boolean = false,
    /** Vrai pendant la vérification de la configuration distante. */
    @StringRes val syncError: Int? = null,
    val versionName: String = "",
    val versionCode: Int = 0,
    /**
     * Dernier plantage enregistré, en une ligne, ou `null` si l'application ne
     * s'est jamais arrêtée anormalement depuis son installation.
     *
     * La trace est masquée (URL, jetons) : elle peut être transmise telle quelle
     * pour diagnostic.
     */
    val dernierIncident: String? = null,
) {
    val hasPlaylists: Boolean get() = playlists.isNotEmpty()
}

/**
 * Réglages : provenance des chaînes (source, fraîcheur, actualisation),
 * qualité d'image par défaut, vérification de la configuration et diagnostics.
 *
 * Les sources ne sont pas modifiables depuis l'application : elles sont publiées
 * dans le dépôt privé de configuration. L'écran n'en montre que le nom, jamais
 * l'adresse.
 */
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val configRepository: RemoteConfigRepository,
    private val catalogRepository: CatalogRepository,
    private val catalogCache: CatalogCache,
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

    /** Relit les sources, les préférences et la date de dernière synchronisation. */
    fun rafraichir() {
        viewModelScope.launch(dispatchers.io) {
            val preferences = settingsStore.playback()
            val config = configRepository.current()
            val cle = catalogRepository.cacheKey()
            _state.update {
                it.copy(
                    playlists = config.playlists,
                    fromTestedCatalog = cle == TestedCatalogRepository.CLE,
                    channelCount = catalogCache.channelCount(cle),
                    catalogUpdatedMs = catalogCache.lastUpdatedMs(cle),
                    qualityMode = preferences.qualityMode,
                    qualityLocked = preferences.qualityLocked,
                    lastSyncMs = configRepository.lastSyncMs(),
                    dernierIncident = crashRecorder.dernierIncident(),
                )
            }
        }
    }

    /**
     * Retélécharge le catalogue depuis les sources en ligne.
     *
     * La liste mémorisée est remplacée en cas de succès ; en cas d'échec, elle
     * reste en place et l'erreur est affichée.
     */
    fun actualiserCatalogue() {
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(isRefreshing = true, refreshError = null) }
            val resultat = catalogRepository.load()
            val erreur = when (resultat) {
                is AppResult.Failure -> {
                    MissaLog.w("Actualisation des chaînes sans succès")
                    resultat.error.messageRes
                }
                is AppResult.Success -> null
            }
            _state.update { it.copy(isRefreshing = false, refreshError = erreur) }
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
}
