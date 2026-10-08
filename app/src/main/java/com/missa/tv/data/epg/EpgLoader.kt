package com.missa.tv.data.epg

import com.missa.tv.core.error.AppError
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.result.AppResult
import com.missa.tv.data.local.EpgCache
import com.missa.tv.domain.model.ChannelEpg
import com.missa.tv.domain.model.PortalSession
import com.missa.tv.domain.repository.PortalRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chargement du guide court (programme en cours / à suivre) des chaînes.
 *
 * Partagé par l'accueil, la grille du programme, l'écran de lecture et le
 * rafraîchissement périodique : tous ont le même besoin — afficher le guide
 * mémorisé sans attendre, puis ne redemander au portail que ce qui est
 * périmé. La politique est commune pour que le nombre de requêtes reste
 * prévisible sur une connexion limitée.
 */
@Singleton
class EpgLoader @Inject constructor(
    private val portalRepository: PortalRepository,
    private val epgCache: EpgCache,
) {

    /** Résultat d'un rafraîchissement. */
    data class RefreshOutcome(
        /** Guides des chaînes demandées, mémorisés ou rafraîchis. */
        val guides: Map<String, ChannelEpg>,
        /** Renseignée si aucune session n'a pu être ouverte : l'appelant peut réessayer. */
        val connectError: AppError?,
    )

    /**
     * Guides mémorisés des chaînes indiquées, sans aucune requête réseau.
     *
     * Appelé avant tout rafraîchissement : l'affichage ne dépend jamais de la
     * disponibilité du portail.
     */
    suspend fun cachedGuides(
        portalId: String,
        channelIds: List<String>,
        nowMs: Long,
        horizonMs: Long = HORIZON_MS,
    ): Map<String, ChannelEpg> = channelIds.associateWith { channelId ->
        epgCache.guide(portalId, channelId, nowMs, horizonMs)
    }

    /**
     * Rafraîchit les guides courts des chaînes indiquées.
     *
     * Seules les chaînes dont le guide mémorisé date de plus de
     * [staleAfterMs] sont redemandées, dans la limite de [maxChannels]
     * requêtes : ouvrir l'accueil ne doit pas se transformer en rafale. La
     * première erreur d'accès au portail interrompt la boucle — un portail
     * qui ne répond pas ne recevra pas une rafale de requêtes — et les guides
     * mémorisés restent alors affichés tels quels.
     *
     * @param session session déjà ouverte, ou `null` pour en ouvrir une.
     */
    suspend fun refreshShortEpg(
        portalId: String,
        channelIds: List<String>,
        nowMs: Long,
        session: PortalSession? = null,
        maxChannels: Int = MAX_CHANNELS,
        staleAfterMs: Long = STALE_AFTER_MS,
    ): RefreshOutcome {
        val guides = cachedGuides(portalId, channelIds, nowMs).toMutableMap()

        val aRafraichir = channelIds
            .distinct()
            .filter { epgCache.isStale(portalId, it, nowMs, staleAfterMs) }
            .take(maxChannels)
        if (aRafraichir.isEmpty()) return RefreshOutcome(guides, null)

        val sessionOuverte = session ?: when (val resultat = portalRepository.connect()) {
            is AppResult.Success -> resultat.value
            is AppResult.Failure -> {
                MissaLog.w("Rafraîchissement du guide : aucune session ouverte")
                return RefreshOutcome(guides, resultat.error)
            }
        }

        var rafraichis = 0
        for (channelId in aRafraichir) {
            when (val resultat = portalRepository.shortEpg(sessionOuverte, channelId)) {
                is AppResult.Success -> {
                    epgCache.save(portalId, channelId, resultat.value)
                    guides[channelId] = ChannelEpg.of(channelId, resultat.value, nowMs)
                    rafraichis++
                }
                is AppResult.Failure -> {
                    // Un portail qui échoue sur une chaîne a de fortes chances
                    // d'échouer sur les suivantes : on s'arrête là.
                    MissaLog.w("Rafraîchissement du guide interrompu sur une chaîne")
                    break
                }
            }
        }

        if (rafraichis > 0) {
            MissaLog.d("Guide rafraîchi pour $rafraichis chaînes")
        }
        return RefreshOutcome(guides, null)
    }

    companion object {
        /** Fenêtre de lecture du guide court : le programme en cours et la suite. */
        const val HORIZON_MS: Long = 24 * 60 * 60 * 1000L

        /** Un guide mémorisé depuis plus de 30 minutes est redemandé au portail. */
        const val STALE_AFTER_MS: Long = 30 * 60 * 1000L

        /** Limite de requêtes par rafraîchissement, pour ménager la connexion. */
        const val MAX_CHANNELS: Int = 40
    }
}
