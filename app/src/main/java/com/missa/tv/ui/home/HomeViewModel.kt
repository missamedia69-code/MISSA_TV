package com.missa.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.result.AppResult
import com.missa.tv.data.local.CatalogCache
import com.missa.tv.data.local.FavoriteCache
import com.missa.tv.data.local.SettingsStore
import com.missa.tv.domain.channel.ChannelVariantGrouper
import com.missa.tv.domain.model.Category
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.ChannelGroup
import com.missa.tv.domain.model.EpgEvent
import com.missa.tv.domain.model.QualityMode
import com.missa.tv.domain.repository.CatalogRepository
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
    /** Clés des groupes mis en favori par l'utilisateur. */
    val favoriteKeys: Set<String> = emptySet(),
    /** Vrai quand la liste n'affiche que les favoris. */
    val showFavoritesOnly: Boolean = false,
    /** Texte de recherche saisi ; vide pour tout afficher. */
    val query: String = "",
    /** Ordre d'affichage choisi (numérotation, A–Z, groupes, pays). */
    val sortMode: SortMode = SortMode.NUMERATION,
    /** Pays filtré ; `null` affiche tous les pays. */
    val selectedCountry: String? = null,
    /** Pays présents au catalogue, pour le bandeau de filtre. */
    val countries: List<String> = emptyList(),
    /**
     * Programme en cours de chaque groupe visible, indexé par clé de groupe.
     *
     * Alimenté par le guide de programmes ; tant qu'aucune source de guide n'est
     * disponible, la carte reste vide et l'information est simplement absente.
     */
    val nowPlaying: Map<String, EpgEvent> = emptyMap(),
    /** Vrai si la configuration distante demande une version plus récente. */
    val requiresAppUpdate: Boolean = false,
    /**
     * Vrai quand la liste affichée vient du catalogue local, et non d'un
     * téléchargement frais.
     *
     * L'écran le signale à l'utilisateur : une liste mémorisée peut être
     * légèrement en retard, il doit le savoir avant de conclure qu'une chaîne a
     * disparu.
     */
    val isFromCache: Boolean = false,
) {
    /**
     * Chaînes à afficher, filtrées par la catégorie choisie, le pays, les
     * favoris et la recherche, puis triées selon le mode courant. La recherche
     * porte sur le nom affiché du groupe.
     */
    val visibleGroups: List<ChannelGroup>
        get() {
            val parCategorie = selectedCategoryId?.let { identifiant ->
                groups.filter { groupe ->
                    groupe.variants.any { variante -> variante.channel.categoryId == identifiant }
                }
            } ?: groups
            val parPays = selectedCountry?.let { pays ->
                parCategorie.filter { groupe ->
                    groupe.variants.any { variante -> variante.channel.country == pays }
                }
            } ?: parCategorie
            val parFavoris = if (showFavoritesOnly) {
                parPays.filter { groupe -> groupe.key in favoriteKeys }
            } else {
                parPays
            }
            val parRecherche = if (query.isBlank()) {
                parFavoris
            } else {
                parFavoris.filter { groupe -> correspond(groupe.displayName, query) }
            }
            return trier(parRecherche)
        }

    /** Applique l'ordre d'affichage choisi ; les valeurs absentes vont en fin de liste. */
    private fun trier(groupes: List<ChannelGroup>): List<ChannelGroup> = when (sortMode) {
        SortMode.NUMERATION -> groupes.sortedBy { it.lowest.channel.number }
        SortMode.ALPHABETIQUE ->
            groupes.sortedBy { sansAccents(it.displayName).lowercase() }
        SortMode.GROUPES ->
            groupes.sortedWith(
                compareBy(
                    { sansAccents(it.lowest.channel.categoryId ?: FIN_DE_LISTE).lowercase() },
                    { sansAccents(it.displayName).lowercase() },
                ),
            )
        SortMode.PAYS ->
            groupes.sortedWith(
                compareBy(
                    { sansAccents(it.lowest.channel.country ?: FIN_DE_LISTE).lowercase() },
                    { sansAccents(it.displayName).lowercase() },
                ),
            )
    }

    /** Vrai si aucune chaîne n'est disponible (liste vide et aucun échec). */
    val isEmpty: Boolean get() = !isLoading && error == null && groups.isEmpty()
}

/**
 * Vrai si [nom] correspond à la [requete], sans tenir compte de la casse ni
 * des accents : « tele » retrouve « Télé ».
 */
private fun correspond(nom: String, requete: String): Boolean {
    if (requete.isBlank()) return true
    return sansAccents(nom).contains(sansAccents(requete), ignoreCase = true)
}

/** Valeur de tri qui pousse les chaînes sans groupe ou sans pays en fin de liste. */
private val FIN_DE_LISTE = Char.MAX_VALUE.toString()
/** Décompose puis retire les marques diacritiques (é → e). */
private fun sansAccents(texte: String): String =
    java.text.Normalizer.normalize(texte, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")

/**
 * Liste des chaînes, chargée depuis le catalogue testé quand le workflow de
 * contrôle l'a publié (seules les chaînes fonctionnelles sont alors listées),
 * ou depuis les playlists M3U déclarées par la configuration distante en repli.
 *
 * Une même chaîne peut apparaître plusieurs fois, une fois par qualité : les
 * diffusions sont regroupées, l'utilisateur voit « TF1 » une seule fois et
 * l'application choisit la variante correspondant à son mode de qualité. C'est
 * ce regroupement qui rend le mode économie utile.
 */
class HomeViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val configRepository: RemoteConfigRepository,
    private val settingsStore: SettingsStore,
    private val catalogCache: CatalogCache,
    private val favoriteCache: FavoriteCache,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** Mode de qualité en vigueur, utilisé pour choisir la variante à lire. */
    private var qualityMode: QualityMode = QualityMode.DEFAULT

    /**
     * Recharge le catalogue si la liste est vide ou si le dernier chargement a
     * échoué.
     *
     * Appelée à chaque affichage de l'accueil : revenir depuis le lecteur ne
     * relance rien, mais revenir d'un écran qui a pu changer la configuration
     * recharge si besoin.
     */
    fun rafraichirSiNecessaire() {
        viewModelScope.launch(dispatchers.io) {
            val etat = _state.value
            if (etat.groups.isEmpty() || etat.error != null) charger()
        }
    }

    /** Charge (ou recharge) les catégories et les chaînes. */
    fun load() {
        viewModelScope.launch(dispatchers.io) { charger() }
    }

    /** Chargement du catalogue, exécuté dans une coroutine d'entrée-sortie. */
    private suspend fun charger() {
        _state.update { it.copy(isLoading = true, error = null) }

        val config = configRepository.current()
        qualityMode = settingsStore.playback().qualityMode ?: config.bandwidth.defaultMode

        // Le catalogue mémorisé est affiché sans attendre : sur une connexion
        // lente, la liste apparaît immédiatement au lieu de laisser un écran de
        // chargement pendant toute la durée du téléchargement.
        val cle = catalogRepository.cacheKey()
        val favoris = favoriteCache.favorites(cle)
        val groupesMemorises = catalogCache.groups(cle)
        if (groupesMemorises.isNotEmpty()) {
            _state.update {
                it.copy(
                    isLoading = false,
                    error = null,
                    categories = catalogCache.categories(cle),
                    groups = groupesMemorises,
                    qualityMode = qualityMode,
                    favoriteKeys = favoris,
                    countries = paysDe(groupesMemorises),
                    isFromCache = true,
                )
            }
        }

        when (val resultat = catalogRepository.load()) {
            is AppResult.Success -> {
                val catalogue = resultat.value
                val groupes = ChannelVariantGrouper.group(catalogue.channels)
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = null,
                        categories = catalogue.categories,
                        groups = groupes,
                        qualityMode = qualityMode,
                        favoriteKeys = favoris,
                        countries = paysDe(groupes),
                        requiresAppUpdate = config.requiresAppUpdate(CURRENT_VERSION_CODE),
                        isFromCache = false,
                    )
                }
            }

            is AppResult.Failure -> {
                MissaLog.w("Catalogue non chargé depuis les playlists")
                _state.update {
                    it.copy(
                        isLoading = false,
                        isFromCache = groupesMemorises.isNotEmpty(),
                        // Une liste mémorisée reste utilisable : l'échec est
                        // signalé sans effacer ce que l'utilisateur avait.
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

    /** Change l'ordre d'affichage des chaînes. */
    fun selectSort(mode: SortMode) {
        _state.update { it.copy(sortMode = mode) }
    }

    /** Filtre la liste sur un pays ; `null` affiche tous les pays. */
    fun selectCountry(country: String?) {
        _state.update { it.copy(selectedCountry = country) }
    }

    /** N'affiche que les favoris, ou revient à toutes les chaînes. */
    fun setShowFavoritesOnly(actif: Boolean) {
        _state.update { it.copy(showFavoritesOnly = actif) }
    }

    /** Filtre la liste sur le texte saisi ; une chaîne vide affiche tout. */
    fun search(query: String) {
        _state.update { it.copy(query = query) }
    }

    /** Ajoute un groupe aux favoris, ou l'en retire s'il y est déjà. */
    fun toggleFavorite(group: ChannelGroup) {
        viewModelScope.launch(dispatchers.io) {
            val cle = catalogRepository.cacheKey()
            val desormaisEnFavori = favoriteCache.toggle(cle, group.key)
            _state.update { etat ->
                val cles = etat.favoriteKeys.toMutableSet()
                if (desormaisEnFavori) cles.add(group.key) else cles.remove(group.key)
                etat.copy(favoriteKeys = cles)
            }
        }
    }

    /**
     * Chaîne à lire pour un groupe.
     *
     * La variante retenue est celle qui correspond au mode courant, jamais une
     * autre : c'est ainsi que le mode économie réduit réellement la consommation.
     */
    fun channelToPlay(group: ChannelGroup): Channel = group.bestFor(qualityMode).channel

    /** Pays présents dans la liste, triés sans tenir compte des accents. */
    private fun paysDe(groupes: List<ChannelGroup>): List<String> =
        groupes
            .flatMap { groupe -> groupe.variants.map { it.channel.country } }
            .filterNotNull()
            .distinct()
            .sortedWith(compareBy { sansAccents(it).lowercase() })

    private companion object {
        /**
         * VersionCode de l'application, comparée à l'exigence de la configuration
         * distante. Renseignée par le build.
         */
        val CURRENT_VERSION_CODE: Int = com.missa.tv.BuildConfig.VERSION_CODE
    }
}
