package com.missa.tv.data.remote.portal

import com.missa.tv.core.json.int
import com.missa.tv.core.json.obj
import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.log.Secrets
import com.missa.tv.core.time.ClockFormat
import com.missa.tv.domain.model.Category
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.EpgEvent
import com.missa.tv.domain.model.PortalAccount
import com.missa.tv.domain.model.PortalCatalog
import com.missa.tv.domain.model.PortalProfile
import com.missa.tv.domain.model.PortalSession
import com.missa.tv.domain.model.StreamLink
import java.io.IOException
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * Client du protocole Stalker / Ministra.
 *
 * Il reproduit le dialogue d'un boîtier MAG250 : découverte de l'endpoint,
 * handshake, profil, catégories, chaînes, puis création d'un lien de lecture à
 * la demande. Le jeton obtenu au handshake est réutilisé pour toutes les
 * requêtes suivantes de la session.
 *
 * Aucune donnée sensible n'est journalisée : l'URL du portail et l'adresse MAC
 * sont masquées par [Secrets] avant toute écriture.
 */
class StalkerClient(
    private val api: StalkerApi,
    private val parser: StalkerResponseParser = StalkerResponseParser(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Endpoints déjà validés, par profil.
     *
     * La découverte coûte jusqu'à quatre requêtes : la mémoriser évite de la
     * refaire à chaque chargement, tout en la recommençant si le portail change
     * d'organisation, car une erreur ultérieure purge cette entrée.
     */
    private val endpoints = mutableMapOf<String, String>()

    /**
     * Ouvre une session : découverte de l'endpoint, handshake, puis lecture du
     * profil pour vérifier que l'abonnement autorise la lecture.
     *
     * @throws PortalProtocolException si le portail refuse l'appareil, si
     *   l'abonnement est inactif, ou si aucun endpoint ne répond.
     */
    suspend fun connect(profile: PortalProfile): PortalSession {
        val timezone = DEFAULT_TIMEZONE
        // La découverte de l'endpoint s'appuie sur un handshake réel : le jeton
        // obtenu sert directement de jeton de session, comme le fait le décodeur.
        val (endpoint, token) = resolveEndpoint(profile, timezone)

        // `get_profile` est demandé comme le fait le décodeur : certains portails
        // refusent les appels suivants tant qu'il n'a pas été effectué.
        //
        // Le résultat n'est **pas** un filtre : il est conservé pour expliquer,
        // plus tard, un refus du portail (voir [PortalAccount]).
        val profileBody = request(
            endpoint = endpoint,
            parameters = mapOf(
                "type" to StalkerProtocol.TYPE_STB,
                "action" to StalkerProtocol.ACTION_GET_PROFILE,
            ) + StalkerProtocol.PROFILE_PARAMETERS,
            profile = profile,
            token = token,
            timezone = timezone,
        )
        val compte = parser.account(profileBody)

        MissaLog.d("Session ouverte sur ${Secrets.maskUrl(endpoint)}")

        return PortalSession(
            profileId = profile.id,
            endpoint = endpoint,
            token = token,
            timezone = timezone,
            account = compte,
        )
    }

    /** État de l'abonnement pour une session déjà ouverte. */
    suspend fun account(session: PortalSession, profile: PortalProfile): PortalAccount {
        val body = request(
            endpoint = session.endpoint,
            parameters = mapOf(
                "type" to StalkerProtocol.TYPE_STB,
                "action" to StalkerProtocol.ACTION_GET_PROFILE,
            ) + StalkerProtocol.PROFILE_PARAMETERS,
            profile = profile,
            token = session.token,
            timezone = session.timezone,
        )
        return parser.account(body)
    }

    /** Catégories de chaînes proposées par le portail. */
    suspend fun categories(session: PortalSession, profile: PortalProfile): List<Category> {
        val body = request(
            endpoint = session.endpoint,
            parameters = mapOf(
                "type" to StalkerProtocol.TYPE_ITV,
                "action" to StalkerProtocol.ACTION_GET_GENRES,
            ),
            profile = profile,
            token = session.token,
            timezone = session.timezone,
        )
        return parser.categories(body)
    }

    /**
     * Toutes les chaînes du portail, page après page.
     *
     * Le portail pagine ses réponses (`total_items` / `max_page_items`) : il faut
     * donc boucler jusqu'à avoir tout récupéré, avec une borne de sécurité pour
     * ne jamais tourner indéfiniment si un portail renvoie des pages incohérentes.
     */
    suspend fun channels(session: PortalSession, profile: PortalProfile): List<Channel> {
        val collected = mutableListOf<Channel>()
        var page = 1

        while (page <= MAX_PAGES) {
            val body = request(
                endpoint = session.endpoint,
                parameters = mapOf(
                    "type" to StalkerProtocol.TYPE_ITV,
                    "action" to StalkerProtocol.ACTION_GET_ALL_CHANNELS,
                    "p" to page.toString(),
                ),
                profile = profile,
                token = session.token,
                timezone = session.timezone,
            )

            val pageChannels = parser.channels(body)
            if (pageChannels.isEmpty()) break
            collected += pageChannels

            if (!hasNextPage(body, page)) break
            page++
        }

        return collected.distinctBy { it.id }.sortedBy { it.number }
    }

    /** Catégories et chaînes en une seule opération. */
    suspend fun catalog(session: PortalSession, profile: PortalProfile): PortalCatalog =
        PortalCatalog(
            categories = categories(session, profile),
            channels = channels(session, profile),
            loadedAtMs = clockMs(),
        )

    /**
     * Demande un lien de lecture pour une chaîne.
     *
     * Le lien est temporaire : il doit être recréé à chaque lecture et après
     * toute erreur d'expiration côté lecteur.
     */
    suspend fun createLink(
        session: PortalSession,
        profile: PortalProfile,
        channel: Channel,
    ): StreamLink {
        val body = request(
            endpoint = session.endpoint,
            parameters = mapOf(
                "type" to StalkerProtocol.TYPE_ITV,
                "action" to StalkerProtocol.ACTION_CREATE_LINK,
                "cmd" to channel.streamUrl,
                "forced_storage" to "0",
                "disable_ad" to "0",
                "download" to "0",
                "series" to "0",
            ),
            profile = profile,
            token = session.token,
            timezone = session.timezone,
        )
        return parser.streamLink(body = body, channelId = channel.id, nowMs = clockMs())
    }

    /**
     * Guide court d'une chaîne : le programme en cours et les suivants.
     *
     * C'est l'action `get_short_epg`, demandée avec l'identifiant de la chaîne.
     * Le portail peut ne publier aucun guide pour certaines chaînes : la
     * réponse est alors une liste vide, ce qui n'est pas une erreur.
     */
    suspend fun shortEpg(
        session: PortalSession,
        profile: PortalProfile,
        channelId: String,
    ): List<EpgEvent> {
        val body = request(
            endpoint = session.endpoint,
            parameters = mapOf(
                "type" to StalkerProtocol.TYPE_ITV,
                "action" to StalkerProtocol.ACTION_GET_SHORT_EPG,
                "ch_id" to channelId,
            ),
            profile = profile,
            token = session.token,
            timezone = session.timezone,
        )
        return parser.shortEpg(body, channelId)
    }

    /**
     * Guide complet d'une chaîne sur la fenêtre [fromMs, toMs], page après page.
     *
     * C'est l'action `get_events`, paginée comme `get_all_channels` : la boucle
     * s'arrête sur une page vide ou quand la pagination annonce la fin. Les
     * dates sont transmises au format attendu par le portail (`yyyy-MM-dd`),
     * dans le fuseau de la session.
     */
    suspend fun events(
        session: PortalSession,
        profile: PortalProfile,
        channelId: String,
        fromMs: Long,
        toMs: Long,
    ): List<EpgEvent> {
        val fuseau = TimeZone.getTimeZone(session.timezone)
        val collected = mutableListOf<EpgEvent>()
        var page = 1

        while (page <= MAX_PAGES) {
            val body = request(
                endpoint = session.endpoint,
                parameters = mapOf(
                    "type" to StalkerProtocol.TYPE_ITV,
                    "action" to StalkerProtocol.ACTION_GET_EVENTS,
                    "ch_id" to channelId,
                    "date_from" to ClockFormat.dayParam(fromMs, fuseau),
                    "date_to" to ClockFormat.dayParam(toMs, fuseau),
                    "p" to page.toString(),
                ),
                profile = profile,
                token = session.token,
                timezone = session.timezone,
            )

            val pageEvents = parser.events(body, channelId)
            if (pageEvents.isEmpty()) break
            collected += pageEvents

            if (!hasNextPage(body, page)) break
            page++
        }

        return collected
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.startMs }, { it.endMs }))
    }

    /**
     * Maintient la session active.
     *
     * Le portail invalide les sessions inactives au bout de quelques minutes :
     * sans cet appel régulier, la lecture s'interrompt brutalement. La boucle
     * s'arrête d'elle-même si le portail ne répond plus, plutôt que de réessayer
     * indéfiniment en consommant de la batterie et des données.
     */
    suspend fun keepAlive(
        session: PortalSession,
        profile: PortalProfile,
        intervalMs: Long = KEEP_ALIVE_INTERVAL_MS,
    ) {
        while (true) {
            delay(intervalMs)
            val vivant = runCatching {
                request(
                    endpoint = session.endpoint,
                    parameters = mapOf(
                        "type" to StalkerProtocol.TYPE_STB,
                        "action" to StalkerProtocol.ACTION_HANDSHAKE,
                    ),
                    profile = profile,
                    token = session.token,
                    timezone = session.timezone,
                )
            }
            if (vivant.isFailure) {
                MissaLog.w("Maintien de session interrompu : portail injoignable")
                return
            }
        }
    }

    /**
     * Découvre l'endpoint de l'API pour un portail.
     *
     * Chaque candidat est testé par un handshake réel : un chemin qui répond
     * « page introuvable » n'est pas confondu avec un portail fonctionnel.
     */
    private suspend fun resolveEndpoint(
        profile: PortalProfile,
        timezone: String,
    ): Pair<String, String> {
        val connexion = handshake(profile, timezone)
        if (connexion != null) return connexion

        var derniereErreur: Throwable? = null
        for (candidat in PortalEndpointResolver.candidates(profile.portalUrl)) {
            try {
                val corps = request(
                    endpoint = candidat,
                    parameters = mapOf(
                        "type" to StalkerProtocol.TYPE_STB,
                        "action" to StalkerProtocol.ACTION_HANDSHAKE,
                    ),
                    profile = profile,
                    token = null,
                    timezone = timezone,
                )
                val jeton = parser.token(corps)
                if (jeton.isNotBlank()) {
                    endpoints[profile.id] = candidat
                    MissaLog.i("Endpoint retenu : ${Secrets.maskUrl(candidat)}")
                    return candidat to jeton
                }
            } catch (erreur: PortalProtocolException) {
                // Un refus d'autorisation est définitif : inutile d'essayer les
                // autres chemins, le portail a bien été trouvé.
                if (erreur.failure == PortalFailure.UNAUTHORIZED) throw erreur
                derniereErreur = erreur
            } catch (erreur: IOException) {
                derniereErreur = erreur
            }
        }

        throw PortalProtocolException(
            PortalFailure.MALFORMED,
            "Aucun endpoint n'a répondu : portail injoignable",
            derniereErreur,
        )
    }

    /** Réessaie directement l'endpoint déjà retenu pour ce profil, s'il existe. */
    private suspend fun handshake(
        profile: PortalProfile,
        timezone: String,
    ): Pair<String, String>? {
        val connu = endpoints[profile.id] ?: return null
        if (!PortalEndpointResolver.candidates(profile.portalUrl).contains(connu)) return null
        return try {
            val corps = request(
                endpoint = connu,
                parameters = mapOf(
                    "type" to StalkerProtocol.TYPE_STB,
                    "action" to StalkerProtocol.ACTION_HANDSHAKE,
                ),
                profile = profile,
                token = null,
                timezone = timezone,
            )
            connu to parser.token(corps)
        } catch (erreur: PortalProtocolException) {
            // L'endpoint mémorisé ne fonctionne plus : on repart en découverte
            // complète, sauf si le portail a explicitement refusé l'appareil.
            if (erreur.failure == PortalFailure.UNAUTHORIZED) throw erreur
            endpoints.remove(profile.id)
            null
        } catch (erreur: IOException) {
            endpoints.remove(profile.id)
            null
        }
    }

    /**
     * Détecte la présence d'une page suivante dans une réponse paginée.
     *
     * `get_all_channels` publie la pagination au niveau de `js` ; `get_events`
     * la publie à l'intérieur de l'objet `js.data`. Les deux niveaux sont donc
     * examinés.
     */
    private fun hasNextPage(body: String, currentPage: Int): Boolean {
        val js = runCatching { parser.payload(body) as? JsonObject }.getOrNull() ?: return false
        val source = js.obj("data") ?: js
        val total = source.int("total_items") ?: return false
        val parPage = source.int("max_page_items") ?: return false
        if (total <= 0 || parPage <= 0) return false
        return currentPage * parPage < total
    }

    /**
     * Exécute une requête et renvoie le corps brut.
     *
     * Les erreurs réseau sont converties en [IOException] afin que la couche
     * supérieure distingue « portail injoignable » de « portail qui refuse ».
     */
    private suspend fun request(
        endpoint: String,
        parameters: Map<String, String>,
        profile: PortalProfile,
        token: String?,
        timezone: String,
    ): String {
        val complet = parameters + mapOf("JsHttpRequest" to StalkerProtocol.JS_HTTP_REQUEST)
        val headers = StalkerProtocol.headers(mac = profile.mac, token = token, timezone = timezone)
        return try {
            api.load(endpoint, complet, headers).use { it.string() }
        } catch (erreur: PortalProtocolException) {
            throw erreur
        } catch (erreur: IOException) {
            throw erreur
        } catch (erreur: Exception) {
            throw IOException("Requête au portail impossible", erreur)
        }
    }

    companion object {
        /** Une page de chaînes par requête : le portail impose sa propre taille. */
        private const val MAX_PAGES = 200

        /** Le portail invalide une session inactive ; ce délai la maintient en vie. */
        private const val KEEP_ALIVE_INTERVAL_MS = 3 * 60 * 1000L

        /** Fuseau annoncé au portail, comme le décodeur en Europe de l'Ouest. */
        const val DEFAULT_TIMEZONE = "Europe/Paris"
    }
}
