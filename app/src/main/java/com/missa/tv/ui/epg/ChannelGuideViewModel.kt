package com.missa.tv.ui.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.local.EpgCache
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.EpgEvent
import com.missa.tv.domain.repository.CatalogRepository
import com.missa.tv.domain.repository.EpgRepository
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
 * Les programmes sont lus dans le guide local sans attendre le réseau : ils
 * s'affichent immédiatement, même sur une connexion lente. Ils sont renouvelés
 * en arrière-plan quand ils datent de plus de [MAX_AGE_MS] ou quand
 * l'utilisateur force le rafraîchissement ; un échec réseau laisse le guide
 * mémorisé en place. L'absence de guide n'a aucune conséquence sur la lecture.
 */
class ChannelGuideViewModel @Inject constructor(
    private val channel: Channel,
    private val catalogRepository: CatalogRepository,
    private val epgCache: EpgCache,
    private val epgRepository: EpgRepository,
    private val timeSource: TimeSource,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(ChannelGuideUiState(channel = channel))
    val state: StateFlow<ChannelGuideUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(dispatchers.io) {
            charger(forcer = false)
        }
    }

    /** Recharge le programme et force le renouvellement du guide. */
    fun refresh() {
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(isRefreshing = true) }
            charger(forcer = true)
        }
    }

    private suspend fun charger(forcer: Boolean) {
        val cle = catalogRepository.cacheKey()
        val maintenant = timeSource.nowMs()

        afficherDepuisCache(cle, maintenant, deFraiche = false)

        val perime = epgCache.isStale(cle, channel.id, maintenant, MAX_AGE_MS)
        if (forcer || perime) {
            epgRepository.refresh()
            afficherDepuisCache(cle, timeSource.nowMs(), deFraiche = true)
        }
    }

    /** Lit les programmes mémorisés de la chaîne et met à jour l'écran. */
    private suspend fun afficherDepuisCache(cle: String, maintenant: Long, deFraiche: Boolean) {
        val evenements = epgCache.events(cle, channel.id, maintenant, maintenant + HORIZON_MS)
        _state.update {
            it.copy(
                events = evenements,
                isLoading = false,
                isRefreshing = false,
                // Le bandeau « guide mémorisé » ne s'affiche que quand on sert le
                // cache sans renouvellement : après un téléchargement, les données
                // sont fraîches.
                isFromCache = !deFraiche && evenements.isNotEmpty(),
                error = null,
            )
        }
    }

    companion object {
        /** Fenêtre de lecture du guide : les 24 prochaines heures. */
        private const val HORIZON_MS = 24L * 60 * 60 * 1000

        /** Fraîcheur maximale du guide avant renouvellement automatique. */
        private const val MAX_AGE_MS = 30L * 60 * 1000
    }
}
