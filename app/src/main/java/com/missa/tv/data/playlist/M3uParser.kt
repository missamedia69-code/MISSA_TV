package com.missa.tv.data.playlist

import com.missa.tv.core.error.AppError

/** Nature d'un échec d'analyse d'une playlist M3U. */
enum class M3uFailure {
    /** Le contenu n'est pas une playlist M3U exploitable. */
    INVALID,

    /** La playlist est bien formée mais ne contient aucune chaîne exploitable. */
    EMPTY,

    /** La playlist dépasse le nombre maximal d'entrées accepté. */
    TOO_LARGE,
}

/**
 * Échec d'analyse d'une playlist M3U.
 *
 * Cette exception ne traverse jamais la couche « data » telle quelle : elle est
 * convertie en [com.missa.tv.core.error.AppError] avec un message destiné à
 * l'utilisateur. Le message technique ne contient jamais l'URL de la playlist ni
 * celle d'un flux : ce sont des identifiants sensibles, potentiellement
 * recopiés dans les journaux.
 */
class M3uParseException(
    val failure: M3uFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Convertit l'échec d'analyse en erreur destinée à l'utilisateur. */
    fun toAppError(): AppError = when (failure) {
        M3uFailure.INVALID -> AppError.PlaylistInvalid
        M3uFailure.EMPTY -> AppError.PlaylistEmpty
        M3uFailure.TOO_LARGE -> AppError.PlaylistTooLarge
    }
}

/**
 * Analyseur de playlists au format M3U / M3U8.
 *
 * L'analyse est réalisée ligne par ligne, en flux tendu : la playlist n'est pas
 * chargée entièrement en mémoire, ce qui autorise de très gros fichiers. Deux
 * garde-fous bornent le travail :
 *
 *  - le nombre d'entrées ([maxEntries], par défaut [MAX_ENTRIES]) : au-delà,
 *    l'analyse s'interrompt immédiatement et signale [M3uFailure.TOO_LARGE] ;
 *  - la mémoire : seule l'entrée en cours est conservée d'une ligne à l'autre.
 *
 * Le parseur est tolérant aux variantes courantes (majuscules/minuscules des
 * directives, lignes CRLF, attributs dans le désordre, `#EXTGRP`, options
 * `#EXTVLCOPT`) mais strict sur ce qui constitue une URL de flux : une ligne
 * n'est retenue que si elle contient un schéma (`://`) et aucune espace, ce qui
 * écarte les contenus manifestement étrangers (HTML, JSON…).
 */
class M3uParser(
    private val maxEntries: Int = MAX_ENTRIES,
) {

    /**
     * Analyse les lignes d'une playlist et renvoie les entrées exploitablement
     * reconnues.
     *
     * @throws M3uParseException si le contenu n'est pas une playlist
     * ([M3uFailure.INVALID]), s'il ne contient aucune chaîne
     * ([M3uFailure.EMPTY]) ou s'il dépasse [maxEntries] entrées
     * ([M3uFailure.TOO_LARGE]).
     */
    fun parse(lines: Sequence<String>): List<M3uEntry> {
        val entrees = ArrayList<M3uEntry>()
        var vuExtM3U = false
        var vuExtInf = false

        // Métadonnées en attente, rattachées à la prochaine URL rencontrée.
        var titreEnAttente: String? = null
        var attributsEnAttente: Map<String, String> = emptyMap()
        var groupeEnAttente: String? = null
        var userAgentEnAttente: String? = null
        var referrerEnAttente: String? = null

        for (brute in lines) {
            val ligne = brute.trim().removePrefix("\uFEFF")
            if (ligne.isEmpty()) continue

            when {
                ligne.startsWith("#EXTM3U", ignoreCase = true) -> {
                    vuExtM3U = true
                }

                ligne.startsWith("#EXTINF", ignoreCase = true) -> {
                    vuExtInf = true
                    val (titre, attributs) = lireExtInf(ligne)
                    titreEnAttente = titre
                    attributsEnAttente = attributs
                }

                ligne.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val corps = ligne.substringAfter(":")
                    val cle = corps.substringBefore('=').trim().lowercase()
                    val valeur = corps.substringAfter('=', "").trim()
                    if (valeur.isNotEmpty()) {
                        when (cle) {
                            "http-user-agent" -> userAgentEnAttente = valeur
                            "http-referrer", "http-referer" -> referrerEnAttente = valeur
                        }
                    }
                }

                ligne.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    val valeur = ligne.substringAfter(":").trim()
                    if (valeur.isNotEmpty()) groupeEnAttente = valeur
                }

                ligne.startsWith("#") -> {
                    // Autre directive ou commentaire : sans effet sur l'analyse.
                }

                else -> {
                    if (!estUrl(ligne)) continue
                    if (entrees.size >= maxEntries) {
                        throw M3uParseException(
                            M3uFailure.TOO_LARGE,
                            "Playlist au-delà du nombre maximal d'entrées",
                        )
                    }
                    entrees += construireEntree(
                        url = ligne,
                        titre = titreEnAttente,
                        attributs = attributsEnAttente,
                        groupe = groupeEnAttente,
                        userAgent = userAgentEnAttente,
                        referrer = referrerEnAttente,
                    )
                    // Les métadonnées viennent d'être consommées.
                    titreEnAttente = null
                    attributsEnAttente = emptyMap()
                    groupeEnAttente = null
                    userAgentEnAttente = null
                    referrerEnAttente = null
                }
            }
        }

        if (entrees.isEmpty()) {
            if (!vuExtM3U && !vuExtInf) {
                throw M3uParseException(
                    M3uFailure.INVALID,
                    "Contenu non reconnu comme playlist M3U",
                )
            }
            throw M3uParseException(
                M3uFailure.EMPTY,
                "Playlist sans aucune chaîne exploitable",
            )
        }
        return entrees
    }

    /** Reconstruit une entrée à partir des métadonnées en attente. */
    private fun construireEntree(
        url: String,
        titre: String?,
        attributs: Map<String, String>,
        groupe: String?,
        userAgent: String?,
        referrer: String?,
    ): M3uEntry = M3uEntry(
        title = titre?.takeIf { it.isNotBlank() } ?: url,
        streamUrl = url,
        logoUrl = nonVide(attributs["tvg-logo"]),
        groupTitle = nonVide(attributs["group-title"]) ?: nonVide(groupe),
        country = nonVide(attributs["tvg-country"]),
        tvgId = nonVide(attributs["tvg-id"]),
        userAgent = nonVide(userAgent),
        referrer = nonVide(referrer),
    )

    /**
     * Analyse une ligne `#EXTINF` : sépare les attributs du titre, en tenant
     * compte des virgules présentes à l'intérieur des valeurs entre guillemets.
     */
    private fun lireExtInf(ligne: String): Pair<String?, Map<String, String>> {
        val deuxPoints = ligne.indexOf(':')
        val corps = if (deuxPoints >= 0) ligne.substring(deuxPoints + 1).trim() else ""

        // Dernière virgule hors guillemets : elle sépare les attributs du titre.
        var entreGuillemets = false
        var derniereVirgule = -1
        for (indice in corps.indices) {
            when (corps[indice]) {
                '"' -> entreGuillemets = !entreGuillemets
                ',' -> if (!entreGuillemets) derniereVirgule = indice
            }
        }

        val zoneAttributs: String
        val titre: String?
        if (derniereVirgule >= 0) {
            zoneAttributs = corps.substring(0, derniereVirgule)
            titre = corps.substring(derniereVirgule + 1).trim().takeIf { it.isNotEmpty() }
        } else {
            zoneAttributs = corps
            titre = null
        }

        val attributs = ATTRIBUT_REGEX.findAll(zoneAttributs)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        return titre to attributs
    }

    /** Une ligne est une URL de flux si elle porte un schéma et aucune espace. */
    private fun estUrl(ligne: String): Boolean =
        "://" in ligne && ligne.none { it.isWhitespace() }

    private fun nonVide(valeur: String?): String? = valeur?.takeIf { it.isNotBlank() }

    companion object {
        /** Nombre maximal d'entrées acceptées dans une playlist. */
        const val MAX_ENTRIES = 50_000

        /** Attribut `cle="valeur"` d'une ligne `#EXTINF`. */
        private val ATTRIBUT_REGEX = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)""")
    }
}
