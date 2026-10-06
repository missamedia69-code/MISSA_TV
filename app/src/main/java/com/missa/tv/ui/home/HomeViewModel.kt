package com.missa.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.result.AppResult
import com.missa.tv.data.local.CatalogCache
import com.missa.tv.data.local.SettingsStore
import com.missa.tv.domain.channel.ChannelVariantGrouper
import com.missa.tv.domain.model.Category
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.ChannelGroup
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

/** État de la liste des chaînes. */
data class HomeUiState(
    val isLoading: Boolean = true,
    val error: AppError? = null,
    val categories: List<Category> = emptyList(),
    val groups: List<ChannelGroup> = emptyList(),
    val selectedCategoryId: String? = null,
    val qualityMode: QualityMode = QualityMode.DEFAULT,
    /** Vrai si la configuration distante demande une version plus récente. */
    val requiresAppUpdate: Boolean = false,
    /**
     * Vrai quand la liste affichée vient du catalogue local, et non du portail.
     *
     * L'écran le signale à l'utilisateur : une liste mémorisée peut être
     * légèrement en retard, il doit le savoir avant de conclure qu'une chaîne a
     * disparu du portail.
     */
    val isFromCache: Boolean = false,
) {
    /** Chaînes à afficher, filtrées par la catégorie choisie. */
    val visibleGroups: List<ChannelGroup>
        get() = selectedCategoryId?.let { identifiant ->
            groups.filter { groupe ->
                groupe.variants.any { variante -> variante.channel.categoryId == identifiant }
            }
        } ?: groups

    /** Vrai si le portail n'a renvoyé aucune chaîne (ou aucun portail configuré). */
    val isEmpty: Boolean get() = !isLoading && error == null && groups.isEmpty()
}

/**
 * Liste des chaînes du portail.
 *
 * Le portail publie souvent la même chaîne plusieurs fois, une fois par qualité.
 * Les diffusions sont regroupées : l'utilisateur voit « TF1 » une seule fois, et
 * l'application choisit la variante correspondant à son mode de qualité. C'est ce
 * regroupement qui rend le mode économie utile — sans lui, choisir « la bonne
 * qualité » reviendrait à chercher à la main dans la liste.
 */
class HomeViewModel @Inject constructor(
    private val portalRepository: PortalRepository,
    private val configRepository: RemoteConfigRepository,
    private val settingsStore: SettingsStore,
    private val profileSource: PortalProfileSource,
    private val catalogCache: CatalogCache,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** Mode de qualité en vigueur, utilisé pour choisir la variante à lire. */
    private var qualityMode: QualityMode = QualityMode.DEFAULT

    /**
     * Signature des profils au dernier chargement.
     *
     * Elle distingue deux retours sur l'accueil : revenir depuis le lecteur ne
     * change rien et ne doit pas relancer une requête, tandis que revenir après
     * avoir configuré un portail doit **impérativement** recharger. Sans cette
     * distinction, l'application restait vide après une configuration : le
     * ViewModel survit (il appartient à l'activité) et ne rechargeait jamais.
     */
    private var signatureProfils: Int? = null

    /**
     * Recharge le catalogue si la configuration a changé, si la liste est vide ou
     * si le dernier chargement a échoué.
     *
     * Appelée à chaque affichage de l'accueil : c'est ce qui rend la
     * configuration visible immédiatement, sans redémarrer l'application.
     */
    fun rafraichirSiNecessaire() {
        viewModelScope.launch(dispatchers.io) {
            val signature = signatureDesProfils()
            val etat = _state.value
            val doitRecharger = signature != signatureProfils ||
                etat.groups.isEmpty() ||
                etat.error != null

            if (doitRecharger) {
                signatureProfils = signature
                charger()
            }
        }
    }

    /** Charge (ou recharge) les catégories et les chaînes. */
    fun load() {
        viewModelScope.launch(dispatchers.io) {
            signatureProfils = signatureDesProfils()
            charger()
        }
    }

    /** Signature des profils enregistrés, utilisée pour détecter un changement. */
    private suspend fun signatureDesProfils(): Int =
        profileSource.profiles().hashCode() * 31 +
            (profileSource.activeProfileId()?.hashCode() ?: 0)

    /** Chargement du catalogue, exécuté dans une coroutine d'entrée-sortie. */
    private suspend fun charger() {
        _state.update { it.copy(isLoading = true, error = null) }

        val config = configRepository.current()
        qualityMode = settingsStore.playback().qualityMode ?: config.bandwidth.defaultMode

        if (!profileSource.hasUsableProfile()) {
            // Aucun portail configuré : ce n'est pas une erreur, c'est le premier
            // lancement. L'écran propose la saisie manuelle.
            _state.update {
                it.copy(
                    isLoading = false,
                    error = null,
                    groups = emptyList(),
                    categories = emptyList(),
                    qualityMode = qualityMode,
                    isFromCache = false,
                )
            }
            return
        }

        val portalId = identifiantPortail()

        // Le catalogue mémorisé est affiché sans attendre : sur une connexion
        // lente, la liste apparaît immédiatement au lieu de laisser un écran de
        // chargement pendant toute la durée de l'interrogation du portail.
        val groupesMemorises = catalogCache.groups(portalId)
        if (groupesMemorises.isNotEmpty()) {
            _state.update {
                it.copy(
                    isLoading = false,
                    error = null,
                    categories = catalogCache.categories(portalId),
                    groups = groupesMemorises,
                    qualityMode = qualityMode,
                    isFromCache = true,
                )
            }
        }

        val session = when (val resultat = portalRepository.connect()) {
            is AppResult.Success -> resultat.value
            is AppResult.Failure -> {
                MissaLog.w("Ouverture de session impossible au chargement des chaînes")
                _state.update {
                    it.copy(
                        isLoading = false,
                        isFromCache = groupesMemorises.isNotEmpty(),
                        // Une liste mémorisée reste utilisable : l'échec est
                        // signalé sans effacer ce que l'utilisateur avait.
                        error = if (groupesMemorises.isEmpty()) resultat.error else null,
                    )
                }
                return
            }
        }

        when (val resultat = portalRepository.catalog(session)) {
            is AppResult.Success -> {
                val catalogue = resultat.value
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = null,
                        categories = catalogue.categories,
                        groups = ChannelVariantGrouper.group(catalogue.channels),
                        qualityMode = qualityMode,
                        requiresAppUpdate = config.requiresAppUpdate(CURRENT_VERSION_CODE),
                        isFromCache = false,
                    )
                }

                // Mise en cache après l'affichage : l'écriture ne retarde jamais la
                // liste, et son échec n'a aucune conséquence visible — la prochaine
                // ouverture refera simplement l'appel au portail.
                runCatching { catalogCache.save(portalId, catalogue) }
                    .onFailure { erreur ->
                        MissaLog.w("Catalogue local non enregistré : ${erreur.javaClass.simpleName}")
                    }
            }
            is AppResult.Failure -> {
                _state.update {
                    it.copy(
                        isLoading = false,
                        isFromCache = groupesMemorises.isNotEmpty(),
                        error = if (groupesMemorises.isEmpty()) resultat.error else null,
                    )
                }
            }
        }
    }

    /** Filtre la liste sur une catégorie ; `null` affiche tout. */
    fun selectCategory(categoryId: String?) {
        _state.update { it.copy(selectedCategoryId = categoryId) }
    }

    /**
     * Chaîne à lire pour un groupe.
     *
     * La variante retenue est celle qui correspond au mode courant, jamais une
     * autre : c'est ainsi que le mode économie réduit réellement la consommation.
     */
    fun channelToPlay(group: ChannelGroup): Channel = group.bestFor(qualityMode).channel

    /**
     * Identifiant du portail servant de clé au cache.
     *
     * C'est le profil actif, à défaut le premier profil complet enregistré : deux
     * profils ne partagent jamais leur catalogue, et un cache orphelin ne peut
     * donc pas afficher les chaînes d'un autre abonnement.
     */
    private suspend fun identifiantPortail(): String {
        val profils = profileSource.profiles()
        val actif = profileSource.activeProfileId()
        return profils.firstOrNull { it.id == actif }?.id
            ?: profils.firstOrNull { it.isComplete }?.id
            ?: CACHE_SANS_PROFIL
    }

    private companion object {
        /**
         * VersionCode de l'application, comparée à l'exigence de la configuration
         * distante. Renseignée par le build.
         */
        val CURRENT_VERSION_CODE: Int = com.missa.tv.BuildConfig.VERSION_CODE

        /** Clé de cache utilisée lorsqu'aucun profil n'est encore complet. */
        const val CACHE_SANS_PROFIL: String = "sans-profil"
    }
}
