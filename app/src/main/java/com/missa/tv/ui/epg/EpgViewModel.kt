package com.missa.tv.ui.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.epg.EpgLoader
import com.missa.tv.data.local.SettingsStore
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.ChannelEpg
import com.missa.tv.domain.model.ChannelGroup
import com.missa.tv.domain.model.EpgEvent
import com.missa.tv.domain.model.QualityMode
import com.missa.tv.domain.repository.PortalProfileSource
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
 * elle décrit ce que l'utilisateur voyait, pas un catalogue figé. Le guide
 * mémorisé est affiché sans attendre le portail ; les chaînes rendues
 * visibles au défilement sont rafraîchies à la demande ([rafraichirPour]),
 * dans la limite fixée par [EpgLoader].
 */
class EpgViewModel @Inject constructor(
    private val groups: List<ChannelGroup>,
    private val profileSource: PortalProfileSource,
    private val epgLoader: EpgLoader,
    private val settingsStore: SettingsStore,
    private val configRepository: RemoteConfigRepository,
    private val timeSource: TimeSource,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(EpgUiState())
    val state: StateFlow<EpgUiState> = _state.asStateFlow()

    /** Mode de qualité en vigueur : il détermine la diffusion dont le guide est lu. */
    private var qualityMode: QualityMode = QualityMode.DEFAULT

    init {
        viewModelScope.launch(dispatchers.io) {
            charger()
        }
    }

    /** Recharge la grille entière, guides mémorisés puis portail. */
    fun refresh() {
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(isRefreshing = true) }
            charger()
        }
    }

    /**
     * Rafraîchit les guides des chaînes rendues visibles au défilement.
     *
     * Appelé par l'écran à chaque changement de la fenêtre visible : les
     * chaînes hors champ ne sont jamais demandées au portail.
     */
    fun rafraichirPour(visibles: List<ChannelGroup>) {
        if (visibles.isEmpty()) return
        viewModelScope.launch(dispatchers.io) {
            val portalId = profileSource.activePortalKey()
            val ids = visibles.map { channelIdPour(it) }.distinct()
            val outcome = epgLoader.refreshShortEpg(portalId, ids, timeSource.nowMs())
            appliquer(outcome.guides)
        }
    }

    /** Chaîne à lire pour un groupe — et dont le programme est affiché. */
    fun channelToPlay(group: ChannelGroup): Channel = group.bestFor(qualityMode).channel

    private suspend fun charger() {
        qualityMode = settingsStore.playback().qualityMode
            ?: configRepository.current().bandwidth.defaultMode

        val portalId = profileSource.activePortalKey()
        val maintenant = timeSource.nowMs()
        val ids = groups.map { channelIdPour(it) }

        // Guides mémorisés, affichés sans attendre le portail.
        appliquer(epgLoader.cachedGuides(portalId, ids, maintenant))

        // Rafraîchissement des guides périmés.
        val outcome = epgLoader.refreshShortEpg(portalId, ids, maintenant)
        appliquer(outcome.guides)
        _state.update { it.copy(isRefreshing = false) }
    }

    /** Met à jour les lignes à partir des guides, par identifiant de diffusion. */
    private fun appliquer(guides: Map<String, ChannelEpg>) {
        _state.update { etat ->
            val lignes = etat.rows.ifEmpty { groups.map { rangee(it, null) } }
            etat.copy(
                rows = lignes.map { ligne ->
                    val guide = guides[ligne.channelId] ?: return@map ligne
                    ligne.copy(current = guide.current, next = guide.next)
                },
            )
        }
    }

    private fun channelIdPour(groupe: ChannelGroup): String = channelToPlay(groupe).id

    private fun rangee(groupe: ChannelGroup, guide: ChannelEpg?): EpgRow = EpgRow(
        group = groupe,
        channelId = channelIdPour(groupe),
        current = guide?.current,
        next = guide?.next,
    )
}
