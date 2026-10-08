package com.missa.tv.ui.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.local.EpgCache
import com.missa.tv.data.local.SettingsStore
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.ChannelEpg
import com.missa.tv.domain.model.ChannelGroup
import com.missa.tv.domain.model.EpgEvent
import com.missa.tv.domain.model.QualityMode
import com.missa.tv.domain.repository.CatalogRepository
import com.missa.tv.domain.repository.EpgRepository
import com.missa.tv.domain.repository.RemoteConfigRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Une ligne de la grille : une chaîne et son guide à l'instant présent. */
data class EpgRow(
    val group: ChannelGroup,
    /** Identifiant de la diffusion dont le guide est affiché (celle qui sera lue). */
    val channelId: String,
    val current: EpgEvent?,
    val next: EpgEvent?,
)

/** État de la grille du programme. */
data class EpgUiState(
    val rows: List<EpgRow> = emptyList(),
    /** Vrai pendant un rafraîchissement demandé par l'utilisateur. */
    val isRefreshing: Boolean = false,
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

/**
 * Grille du programme : pour chaque chaîne de la sélection courante, le
 * programme en cours et le suivant.
 *
 * La grille porte sur les groupes reçus à l'ouverture (catégorie comprise) :
 * elle décrit ce que l'utilisateur voyait, pas un catalogue figé.
 *
 * Le guide est lu dans le cache local sans attendre le réseau : sur une
 * connexion lente, l'information est là immédiatement, même si elle date de
 * quelques heures. Il est ensuite renouvelé en arrière-plan — un échec réseau
 * laisse le guide mémorisé en place, il n'est jamais effacé par une absence de
 * connexion.
 */
class EpgViewModel @Inject constructor(
    private val groups: List<ChannelGroup>,
    private val settingsStore: SettingsStore,
    private val configRepository: RemoteConfigRepository,
    private val catalogRepository: CatalogRepository,
    private val epgCache: EpgCache,
    private val epgRepository: EpgRepository,
    private val timeSource: TimeSource,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(EpgUiState())
    val state: StateFlow<EpgUiState> = _state.asStateFlow()

    /** Mode de qualité en vigueur : il détermine la diffusion dont le guide est lu. */
    private var qualityMode: QualityMode = QualityMode.DEFAULT

    init {
        viewModelScope.launch(dispatchers.io) {
            charger(forcer = false)
        }
    }

    /** Recharge la grille entière et force le renouvellement du guide. */
    fun refresh() {
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(isRefreshing = true) }
            charger(forcer = true)
        }
    }

    /**
     * Rafraîchit les guides des chaînes rendues visibles au défilement.
     *
     * Le guide XMLTV est renouvelé globalement par source (un téléchargement
     * complet), il n'y a donc pas de rafraîchissement par chaîne au défilement :
     * l'appel est conservé pour que l'écran n'ait pas à connaître ce détail.
     */
    fun rafraichirPour(visibles: List<ChannelGroup>) {
        // Rien à faire : le guide est renouvelé globalement, pas par chaîne.
    }

    /** Chaîne à lire pour un groupe — et dont le programme est affiché. */
    fun channelToPlay(group: ChannelGroup): Channel = group.bestFor(qualityMode).channel

    private suspend fun charger(forcer: Boolean) {
        qualityMode = settingsStore.playback().qualityMode
            ?: configRepository.current().bandwidth.defaultMode

        val cle = catalogRepository.cacheKey()
        afficherDepuisCache(cle, timeSource.nowMs())

        // Le guide est renouvelé si l'utilisateur le demande, ou si le cache ne
        // contient encore rien à afficher (premier lancement).
        val rienAafficher = _state.value.rows.none { it.current != null || it.next != null }
        if (forcer || rienAafficher) {
            epgRepository.refresh()
            afficherDepuisCache(cle, timeSource.nowMs())
        }
    }

    /** Lit le guide mémorisé de chaque chaîne et met à jour la grille. */
    private suspend fun afficherDepuisCache(cle: String, maintenant: Long) {
        val lignes = groups.map { groupe ->
            val idChaine = channelToPlay(groupe).id
            rangee(groupe, epgCache.guide(cle, idChaine, maintenant, HORIZON_MS))
        }
        _state.update { it.copy(rows = lignes, isRefreshing = false) }
    }

    private fun rangee(groupe: ChannelGroup, guide: ChannelEpg?): EpgRow = EpgRow(
        group = groupe,
        channelId = channelToPlay(groupe).id,
        current = guide?.current,
        next = guide?.next,
    )

    companion object {
        /** Fenêtre de lecture du guide : les 24 prochaines heures. */
        private const val HORIZON_MS = 24L * 60 * 60 * 1000
    }
}
