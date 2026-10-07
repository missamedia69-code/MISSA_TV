package com.missa.tv.data.remote.portal

import com.missa.tv.core.json.array
import com.missa.tv.core.json.asArrayOrNull
import com.missa.tv.core.json.asObjectOrNull
import com.missa.tv.core.json.boolean
import com.missa.tv.core.json.booleanTolerant
import com.missa.tv.core.json.int
import com.missa.tv.core.json.string
import com.missa.tv.domain.model.Category
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.PortalAccount
import com.missa.tv.domain.model.StreamLink
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Analyse des réponses du portail.
 *
 * Toutes les réponses Stalker sont enveloppées dans un champ `js`, qui peut
 * contenir :
 *  - un objet (`handshake`, `get_profile`, `create_link`, `get_all_channels`) ;
 *  - un tableau (`get_genres`) ;
 *  - une chaîne d'erreur (`"error"`) ou `false` lorsque le portail refuse.
 *
 * Aucune donnée sensible (URL de flux, MAC) n'est incluse dans les messages
 * d'erreur : ceux-ci remontent parfois jusqu'aux journaux.
 */
class StalkerResponseParser(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) {

    /**
     * Extrait le contenu utile de l'enveloppe `js`.
     *
     * @throws PortalProtocolException si la réponse est illisible ou signale un refus.
     */
    fun payload(body: String): JsonElement {
        val root = try {
            json.parseToJsonElement(body)
        } catch (error: SerializationException) {
            throw PortalProtocolException(
                PortalFailure.MALFORMED,
                "Réponse illisible du portail",
                error,
            )
        }

        val js = (root as? JsonObject)?.get("js")
            ?: throw PortalProtocolException(PortalFailure.MALFORMED, "Réponse sans champ js")

        if (js is JsonPrimitive) {
            if (js === JsonNull) {
                throw PortalProtocolException(PortalFailure.MALFORMED, "Réponse vide")
            }
            val contenu = js.content
            if (!js.isString || contenu.equals("error", ignoreCase = true) || contenu == "false") {
                throw PortalProtocolException(
                    // Un refus qui parle d'expiration est un refus explicite du
                    // portail : il autorise le message « abonnement inactif »,
                    // sans jamais le présumer.
                    if (refusViseLeCompte(contenu)) {
                        PortalFailure.EXPIRED
                    } else {
                        PortalFailure.UNAUTHORIZED
                    },
                    "Session refusée par le portail",
                )
            }
        }
        return js
    }

    /**
     * Jeton de session renvoyé par le handshake.
     *
     * @throws PortalProtocolException si le portail ne fournit pas de jeton, ce
     *   qui correspond en pratique à un appareil non autorisé.
     */
    fun token(body: String): String {
        val js = payload(body) as? JsonObject
            ?: throw PortalProtocolException(PortalFailure.MALFORMED, "Handshake inattendu")
        return js.string("token")?.takeIf { it.isNotBlank() }
            ?: throw PortalProtocolException(PortalFailure.UNAUTHORIZED, "Aucun jeton renvoyé")
    }

    /**
     * État du compte annoncé par le portail.
     *
     * Cette fonction **ne refuse jamais** : elle lit ce que le portail dit, et
     * laisse tout champ absent ou mal formé à `null` (*inconnu*). Refuser ici
     * reviendrait à bloquer des abonnements valides sur la base de champs dont le
     * sens varie d'un portail à l'autre (voir [PortalAccount]).
     *
     * Seule une réponse sans enveloppe exploitable lève une erreur : là, c'est la
     * session elle-même qui n'est pas utilisable.
     */
    fun account(body: String): PortalAccount {
        val js = payload(body) as? JsonObject
            ?: throw PortalProtocolException(PortalFailure.MALFORMED, "Profil inattendu")

        return PortalAccount(
            isActive = js.booleanTolerant("status"),
            isSubscribed = js.booleanTolerant("subscribed"),
            expiresAtRaw = js.string("expire_billing_date")
                ?: js.string("tariff_expired_date")
                ?: js.string("end_date"),
            isTrial = js.booleanTolerant("trial") ?: false,
        )
    }

    /** Liste des catégories (`get_genres`). */
    fun categories(body: String): List<Category> {
        val js = payload(body)
        val elements = when (js) {
            is JsonArray -> js
            // Certaines variantes enveloppent la liste dans un objet.
            is JsonObject -> js.array("data")
            else -> throw PortalProtocolException(PortalFailure.MALFORMED, "Catégories inattendues")
        }

        return elements.mapNotNull { element ->
            val objet = element.asObjectOrNull() ?: return@mapNotNull null
            val identifiant = objet.string("id") ?: return@mapNotNull null
            Category(
                id = identifiant,
                title = objet.string("title") ?: objet.string("name") ?: identifiant,
            )
        }
    }

    /**
     * Liste des chaînes.
     *
     * Gère les deux formes rencontrées : `js.data` (réponse paginée de
     * `get_all_channels` et `get_ordered_list`) et un tableau direct.
     */
    fun channels(body: String): List<Channel> {
        val js = payload(body)
        val elements = when (js) {
            is JsonArray -> js
            is JsonObject -> {
                val donnees = js.array("data")
                // Sans données, un message d'erreur qui vise le compte est un
                // refus explicite : le dire vaut mieux qu'afficher « aucune
                // chaîne », qui accuserait le portail à tort.
                if (donnees.isEmpty() && refusViseLeCompte(js.string("error"))) {
                    throw PortalProtocolException(
                        PortalFailure.EXPIRED,
                        "Abonnement refusé par le portail",
                    )
                }
                donnees
            }
            else -> throw PortalProtocolException(PortalFailure.MALFORMED, "Chaînes inattendues")
        }

        return elements.mapNotNull { element -> element.asObjectOrNull()?.toChannel() }
    }

    /**
     * Lien de lecture (`create_link`).
     *
     * La commande renvoyée est nettoyée du préfixe `ffmpeg`, puis vérifiée :
     * un portail peut répondre correctement tout en ne fournissant aucun flux.
     */
    fun streamLink(body: String, channelId: String, nowMs: Long): StreamLink {
        val js = payload(body) as? JsonObject
            ?: throw PortalProtocolException(PortalFailure.MALFORMED, "Lien de lecture inattendu")

        val brut = js.string("cmd")
            ?: throw PortalProtocolException(
                if (refusViseLeCompte(js.string("error"))) {
                    PortalFailure.EXPIRED
                } else {
                    PortalFailure.STREAM_UNAVAILABLE
                },
                "Aucun lien de lecture pour cette chaîne",
            )

        val url = StalkerProtocol.playableUrl(brut)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw PortalProtocolException(
                PortalFailure.STREAM_UNAVAILABLE,
                "Lien de lecture invalide",
            )
        }

        return StreamLink(
            channelId = channelId,
            url = url,
            isHls = url.contains(".m3u8", ignoreCase = true),
            createdAtMs = nowMs,
        )
    }

    /**
     * Le portail refuse-t-il en désignant l'état du compte ?
     *
     * Le texte du portail n'est **jamais** recopié : ni dans les messages, ni
     * dans les journaux (il peut contenir une adresse ou une MAC). Il sert
     * uniquement de classement. Les termes retenus désignent sans ambiguïté un
     * abonnement, jamais un incident technique : un portail qui parle d'expiration
     * ou d'inactivité dit exactement ce que l'utilisateur doit lire.
     */
    private fun refusViseLeCompte(texte: String?): Boolean {
        val normalise = texte
            ?.lowercase()
            ?.replace('é', 'e')
            ?.replace('è', 'e')
            ?.replace('ê', 'e')
            ?: return false
        // Un refus qui parle de session, de jeton ou d'authentification ne vise
        // pas le compte : il se répare en rouvrant une session, et l'annoncer
        // comme un abonnement inactif enverrait l'utilisateur au mauvais endroit.
        if (MARQUEURS_TECHNIQUES.any { marqueur -> normalise.contains(marqueur) }) return false
        return MARQUEURS_COMPTE_INACTIF.any { marqueur -> normalise.contains(marqueur) }
    }

    private companion object {
        /**
         * Termes qui, dans un message d'erreur de portail, désignent l'état du
         * compte. Volontairement courts et sans ambiguïté : « expire » couvre
         * *expired*, *expiré*, *expiration*.
         */
        val MARQUEURS_COMPTE_INACTIF = listOf(
            "expire",
            "inactive",
            "inactif",
            "not active",
            "not_active",
            "disabled",
            "deactivated",
            "no subscription",
            "not subscribed",
            "unsubscribed",
        )

        /**
         * Termes qui rattachent un refus à la session ou à l'appareil plutôt
         * qu'à l'abonnement. Ils disqualifient la classification précédente.
         */
        val MARQUEURS_TECHNIQUES = listOf(
            "session",
            "token",
            "jeton",
            "login",
            "authentif",
            "unauthor",
            "forbidden",
        )
    }

    private fun JsonObject.toChannel(): Channel? {
        val identifiant = string("id") ?: return null
        val nom = string("name")?.takeIf { it.isNotBlank() } ?: return null
        return Channel(
            id = identifiant,
            // `number` arrive indifféremment en chaîne ou en nombre.
            number = int("number") ?: 0,
            name = nom,
            cmd = string("cmd").orEmpty(),
            logoUrl = string("logo")?.takeIf { it.startsWith("http") },
            categoryId = string("tv_genre_id"),
            isCensored = boolean("censored") ?: false,
            // `status` vaut 1 pour une chaîne active, 0 pour une chaîne retirée.
            isAvailable = boolean("status") ?: true,
        )
    }
}
