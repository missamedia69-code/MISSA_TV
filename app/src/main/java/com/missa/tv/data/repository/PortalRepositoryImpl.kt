package com.missa.tv.data.repository

import com.missa.tv.core.dispatchers.DispatcherProvider
import com.missa.tv.core.error.AppError
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.log.Secrets
import com.missa.tv.core.result.AppResult
import com.missa.tv.data.remote.portal.PortalFailure
import com.missa.tv.data.remote.portal.PortalProtocolException
import com.missa.tv.data.remote.portal.StalkerClient
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.EpgEvent
import com.missa.tv.domain.model.PortalCatalog
import com.missa.tv.domain.model.PortalProfile
import com.missa.tv.domain.model.PortalSession
import com.missa.tv.domain.model.StreamLink
import com.missa.tv.domain.portal.PortalFailoverPolicy
import com.missa.tv.domain.repository.PortalProfileSource
import com.missa.tv.domain.repository.PortalRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Implémentation de l'accès au portail, avec bascule automatique.
 *
 * Deux responsabilités :
 *  - traduire les échecs techniques en erreurs compréhensibles par
 *    l'utilisateur ([AppError]) ;
 *  - essayer un autre profil quand le portail refuse l'appareil ou signale un
 *    abonnement inactif, sans faire intervenir l'utilisateur.
 *
 * Le profil retenu est conservé en mémoire pour la session en cours, ce qui
 * évite d'exposer à nouveau la MAC dans les couches supérieures.
 */
@Singleton
class PortalRepositoryImpl @Inject constructor(
    private val client: StalkerClient,
    private val profileSource: PortalProfileSource,
    private val dispatchers: DispatcherProvider,
    private val failoverPolicy: PortalFailoverPolicy = PortalFailoverPolicy(),
) : PortalRepository {

    /** Protège la bascule : deux lectures simultanées ne doivent pas se croiser. */
    private val mutex = Mutex()

    /** Profil réellement utilisé par les sessions ouvertes. */
    private var profilsParSession = mutableMapOf<String, PortalProfile>()

    override suspend fun connect(): AppResult<PortalSession> = withContext(dispatchers.io) {
        mutex.withLock {
            val profils = profileSource.profiles()
            val actif = profileSource.activeProfileId()
            val candidats = failoverPolicy.candidates(profils, actif)

            if (candidats.isEmpty()) {
                MissaLog.w("Aucun profil de portail utilisable")
                return@withLock AppResult.failure(AppError.MissingConfig)
            }

            var derniereErreur: AppError = AppError.PortalUnreachable

            for (profil in candidats) {
                try {
                    val session = client.connect(profil)
                    profilsParSession[session.token] = profil
                    if (profil.id != actif) {
                        // La bascule a réussi : ce profil devient le profil
                        // préféré pour les prochaines ouvertures.
                        profileSource.setActiveProfileId(profil.id)
                        MissaLog.i("Bascule vers le profil de secours réussi")
                    }
                    return@withLock AppResult.success(session)
                } catch (erreur: PortalProtocolException) {
                    derniereErreur = erreur.toAppError()
                    val motif = erreur.failure.toFailoverReason()
                    if (!failoverPolicy.shouldFailover(motif)) break

                    MissaLog.w(
                        "Profil refusé (${erreur.failure.name}), " +
                            "tentative suivante sur ${Secrets.maskMac(profil.mac)}",
                    )
                } catch (erreur: IOException) {
                    derniereErreur = AppError.PortalUnreachable
                    break
                }
            }

            AppResult.failure(derniereErreur)
        }
    }

    override suspend fun catalog(session: PortalSession): AppResult<PortalCatalog> =
        withContext(dispatchers.io) {
            val profil = profilsParSession[session.token]
            if (profil == null) {
                // Session inconnue : elle provient d'un autre lancement, ou bien
                // elle a expiré côté portail. Le dire ainsi est plus juste qu'un
                // « abonnement expiré », qui désigne tout autre chose.
                return@withContext AppResult.failure(AppError.SessionExpired)
            }
            try {
                AppResult.success(client.catalog(session, profil))
            } catch (erreur: PortalProtocolException) {
                // C'est le portail qui a refusé : si le compte s'est annoncé
                // inactif, ce refus s'explique, et le message le dit.
                AppResult.failure(
                    if (session.account.explicitlyInactive) {
                        AppError.SubscriptionExpired
                    } else {
                        erreur.toAppError()
                    },
                )
            } catch (erreur: IOException) {
                AppResult.failure(AppError.PortalUnreachable)
            }
        }

    override suspend fun createLink(
        session: PortalSession,
        channel: Channel,
    ): AppResult<StreamLink> = withContext(dispatchers.io) {
        val profil = profilsParSession[session.token]
            ?: return@withContext AppResult.failure(AppError.SessionExpired)
        try {
            AppResult.success(client.createLink(session, profil, channel))
        } catch (erreur: PortalProtocolException) {
            // Session expirée côté portail : l'appelant doit rouvrir une
            // session plutôt que de réessayer avec le même jeton.
            oublierSiSessionRefusee(session, erreur)
            AppResult.failure(erreur.toAppError())
        } catch (erreur: IOException) {
            AppResult.failure(AppError.StreamUnavailable)
        }
    }

    override suspend fun shortEpg(
        session: PortalSession,
        channelId: String,
    ): AppResult<List<EpgEvent>> = withContext(dispatchers.io) {
        val profil = profilsParSession[session.token]
            ?: return@withContext AppResult.failure(AppError.SessionExpired)
        try {
            AppResult.success(client.shortEpg(session, profil, channelId))
        } catch (erreur: PortalProtocolException) {
            oublierSiSessionRefusee(session, erreur)
            AppResult.failure(erreur.toAppError())
        } catch (erreur: IOException) {
            AppResult.failure(AppError.PortalUnreachable)
        }
    }

    override suspend fun epg(
        session: PortalSession,
        channelId: String,
        fromMs: Long,
        toMs: Long,
    ): AppResult<List<EpgEvent>> = withContext(dispatchers.io) {
        val profil = profilsParSession[session.token]
            ?: return@withContext AppResult.failure(AppError.SessionExpired)
        try {
            AppResult.success(client.events(session, profil, channelId, fromMs, toMs))
        } catch (erreur: PortalProtocolException) {
            oublierSiSessionRefusee(session, erreur)
            AppResult.failure(erreur.toAppError())
        } catch (erreur: IOException) {
            AppResult.failure(AppError.PortalUnreachable)
        }
    }

    override suspend fun keepAlive(session: PortalSession) = withContext(dispatchers.io) {
        val profil = profilsParSession[session.token] ?: return@withContext
        client.keepAlive(session, profil)
    }

    /**
     * Oublie une session que le portail a refusée.
     *
     * Un jeton refusé ne sera pas accepté au prochain appel : l'appelant doit
     * rouvrir une session plutôt que réessayer avec le même jeton.
     */
    private fun oublierSiSessionRefusee(session: PortalSession, erreur: PortalProtocolException) {
        if (erreur.failure == PortalFailure.UNAUTHORIZED) {
            profilsParSession.remove(session.token)
        }
    }

    private fun PortalFailure.toFailoverReason(): PortalFailoverPolicy.FailoverReason = when (this) {
        PortalFailure.UNAUTHORIZED -> PortalFailoverPolicy.FailoverReason.UNAUTHORIZED
        PortalFailure.EXPIRED -> PortalFailoverPolicy.FailoverReason.EXPIRED
        PortalFailure.MALFORMED -> PortalFailoverPolicy.FailoverReason.UNREACHABLE
        PortalFailure.STREAM_UNAVAILABLE -> PortalFailoverPolicy.FailoverReason.STREAM_UNAVAILABLE
    }
}
