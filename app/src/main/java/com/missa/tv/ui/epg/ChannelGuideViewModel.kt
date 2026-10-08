package com.missa.tv.ui.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.EpgEvent
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** État du programme complet d'une chaîne. */
data class ChannelGuideUiState(
    val channel: Channel,
    /** Programmes des prochaines 24 heures, triés par heure de début. */
    val events: List<EpgEvent> = emptyList(),
    val isLoading: Boolean = true,
    /** Vrai pendant un rafraîchissement demandé par l'utilisateur. */
    val isRefreshing: Boolean = false,
    val error: AppError? = null,
    /** Vrai quand les programmes affichés viennent du guide mémorisé. */
    val isFromCache: Boolean = false,
)

/**
 * Programme complet d'une chaîne, sur les 24 prochaines heures.
 *
 * Tant qu'aucune source de guide n'est branchée, l'écran reste vide : le guide
 * XMLTV (étape suivante) remplira ces programmes sans changer la structure de
 * l'écran. L'absence de guide n'a aucune conséquence sur la lecture.
 */
class ChannelGuideViewModel @Inject constructor(
    private val channel: Channel,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(ChannelGuideUiState(channel = channel))
    val state: StateFlow<ChannelGuideUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(dispatchers.io) {
            charger()
        }
    }

    /** Recharge le programme. */
    fun refresh() {
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(isRefreshing = true) }
            charger()
        }
    }

    private suspend fun charger() {
        // Aucune source de guide pour l'instant : la liste des programmes est
        // vide et l'écran n'attend rien.
        _state.update {
            it.copy(
                events = emptyList(),
                isLoading = false,
                isRefreshing = false,
                isFromCache = false,
                error = null,
            )
        }
    }
}
