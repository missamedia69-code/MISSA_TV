package com.missa.tv.ui.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.core.result.AppResult
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.epg.EpgLoader
import com.missa.tv.data.local.EpgCache
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.EpgEvent
import com.missa.tv.domain.repository.PortalProfileSource
import com.missa.tv.domain.repository.PortalRepository
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
 * Le guide mémorisé est affiché sans attendre le portail ; le portail n'est
 * sollicité que si le guide est périmé ou absent, ou si l'utilisateur demande
 * un rafraîchissement. Un portail qui ne répond pas laisse le guide mémorisé
 * en place, signalé par un bandeau : l'information reste disponible, même en
 * retard.
 */
class ChannelGuideViewModel @Inject constructor(
    private val channel: Channel,
    private val portalRepository: PortalRepository,
    private val profileSource: PortalProfileSource,
    private val epgCache: EpgCache,
    private val timeSource: TimeSource,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(ChannelGuideUiState(channel = channel))
    val state: StateFlow<ChannelGuideUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(dispatchers.io) {
            charger(force = false)
        }
    }

    /** Recharge le programme depuis le portail, même si le guide mémorisé est récent. */
    fun refresh() {
        viewModelScope.launch(dispatchers.io) {
            charger(force = true)
        }
    }

    private suspend fun charger(force: Boolean) {
        val portalId = profileSource.activePortalKey()
        val maintenant = timeSource.nowMs()
        val fenetreMs = maintenant + FENETRE_MS

        // Le guide mémorisé est affiché sans attendre le portail.
        val memorises = epgCache.events(portalId, channel.id, maintenant, fenetreMs)
        _state.update {
            it.copy(
                events = memorises,
                // Tant qu'aucun programme n'est disponible, l'écran de
                // chargement reste affiché, même pendant un rafraîchissement.
                isLoading = memorises.isEmpty(),
                isRefreshing = force,
                isFromCache = true,
                error = null,
            )
        }

        val aRafraichir = force ||
            memorises.isEmpty() ||
            epgCache.isStale(portalId, channel.id, maintenant, EpgLoader.STALE_AFTER_MS)
        if (!aRafraichir) {
            _state.update { it.copy(isLoading = false) }
            return
        }

        val session = when (val resultat = portalRepository.connect()) {
            is AppResult.Success -> resultat.value
            is AppResult.Failure -> {
                terminerEnEchec(resultat.error, force, memorises)
                return
            }
        }

        when (val resultat = portalRepository.epg(session, channel.id, maintenant, fenetreMs)) {
            is AppResult.Success -> {
                epgCache.save(portalId, channel.id, resultat.value)
                _state.update {
                    it.copy(
                        events = resultat.value,
                        isLoading = false,
                        isRefreshing = false,
                        isFromCache = false,
                        error = null,
                    )
                }
            }
            is AppResult.Failure -> terminerEnEchec(resultat.error, force, memorises)
        }
    }

    /** Termine sur un échec : le guide mémorisé reste affiché, avec un bandeau. */
    private fun terminerEnEchec(erreur: AppError, force: Boolean, memorises: List<EpgEvent>) {
        _state.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                isFromCache = memorises.isNotEmpty(),
                // Un guide mémorisé reste affiché : l'échec n'est signalé que
                // s'il ne reste rien à montrer.
                error = if (memorises.isEmpty() || force) erreur else null,
            )
        }
    }

    private companion object {
        /** Fenêtre du programme affiché : les 24 prochaines heures. */
        const val FENETRE_MS: Long = 24 * 60 * 60 * 1000L
    }
}
