package com.missa.tv.ui.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.data.local.SettingsStore
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.ChannelEpg
import com.missa.tv.domain.model.ChannelGroup
import com.missa.tv.domain.model.EpgEvent
import com.missa.tv.domain.model.QualityMode
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
 * Tant qu'aucune source de guide n'est branchée, les lignes sont affichées sans
 * programme : le guide XMLTV (étape suivante) viendra remplir ces cellules sans
 * changer la structure de l'écran.
 */
class EpgViewModel @Inject constructor(
    private val groups: List<ChannelGroup>,
    private val settingsStore: SettingsStore,
    private val configRepository: RemoteConfigRepository,
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

    /** Recharge la grille entière. */
    fun refresh() {
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(isRefreshing = true) }
            charger()
        }
    }

    /**
     * Rafraîchit les guides des chaînes rendues visibles au défilement.
     *
     * Sans source de guide branchée, il n'y a rien à demander : l'appel est
     * conservé pour que l'écran n'ait pas à connaître cette absence.
     */
    fun rafraichirPour(visibles: List<ChannelGroup>) {
        // Aucune source de guide pour l'instant : rien à rafraîchir.
    }

    /** Chaîne à lire pour un groupe — et dont le programme est affiché. */
    fun channelToPlay(group: ChannelGroup): Channel = group.bestFor(qualityMode).channel

    private suspend fun charger() {
        qualityMode = settingsStore.playback().qualityMode
            ?: configRepository.current().bandwidth.defaultMode

        _state.update {
            it.copy(
                rows = groups.map { groupe -> rangee(groupe, null) },
                isRefreshing = false,
            )
        }
    }

    private fun rangee(groupe: ChannelGroup, guide: ChannelEpg?): EpgRow = EpgRow(
        group = groupe,
        channelId = channelToPlay(groupe).id,
        current = guide?.current,
        next = guide?.next,
    )
}
