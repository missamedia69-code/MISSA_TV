package com.missa.tv.core.log

/**
 * Masquage des données sensibles.
 *
 * Règle du projet : l'URL du portail, les adresses MAC et les jetons ne doivent
 * JAMAIS apparaître en clair dans un journal, une trace de plantage ou une
 * capture d'écran. Toute valeur de ce type passe par cet objet avant d'être
 * journalisée.
 *
 * Exemple de MAC masquée : `00:1A:79:**:**:**` — les trois premiers octets
 * suffisent à identifier grossièrement le matériel, le reste est inutile.
 */
object Secrets {

    private const val MASK = "**"

    private val MAC_REGEX =
        Regex(
            "([0-9A-Fa-f]{2})[:-]([0-9A-Fa-f]{2})[:-]([0-9A-Fa-f]{2})[:-]" +
                "([0-9A-Fa-f]{2})[:-]([0-9A-Fa-f]{2})[:-]([0-9A-Fa-f]{2})",
        )

    /** URL complète : schéma, hôte, port et chemin. */
    private val URL_REGEX = Regex("(https?)://([^:/\\s]+)(:\\d+)?([^\\s]*)")

    /** Jeton d'autorisation Stalker, renvoyé par le handshake. */
    private val TOKEN_REGEX = Regex("Bearer\\s+[A-Za-z0-9._~+/=-]{8,}")

    /** Jetons d'API GitHub, pour ne jamais les journaliser par accident. */
    private val GITHUB_TOKEN_REGEX = Regex("(gh[pousr]_|github_pat_)[A-Za-z0-9_]{8,}")

    /** Paramètre `mac=` dans une URL, très fréquent sur ce protocole. */
    private val MAC_QUERY_REGEX = Regex("mac=([0-9A-Fa-f%3A:-]{6,})")

    /**
     * Masque une adresse MAC : les trois premiers octets sont conservés.
     * Une valeur qui n'a pas le format attendu est entièrement remplacée, par
     * précaution plutôt que par omission.
     */
    fun maskMac(mac: String): String {
        val match = MAC_REGEX.find(mac) ?: return MASK
        val (first, second, third) = match.destructured
        return "$first:$second:$third:$MASK:$MASK:$MASK"
    }

    /**
     * Masque une URL : seuls le schéma et la présence d'un port sont conservés,
     * ce qui suffit au diagnostic sans révéler l'adresse du portail.
     */
    fun maskUrl(url: String): String {
        val match = URL_REGEX.find(url) ?: return "<url-masquee>"
        val scheme = match.groupValues[1]
        val host = match.groupValues[2]
        val port = match.groupValues[3]
        val maskedHost = if (host.isEmpty()) "***" else host.first() + "***"
        return "$scheme://$maskedHost$port/***"
    }

    /** Masque un jeton en n'en conservant que la longueur, aucun caractère. */
    fun maskToken(token: String): String =
        if (token.isEmpty()) "<vide>" else "<jeton de ${token.length} caracteres>"

    /**
     * Applique tous les masquages à un texte destiné au journal.
     *
     * C'est le moyen le plus sûr de journaliser un objet susceptible de
     * contenir des identifiants.
     */
    fun mask(text: String): String = text
        .replace(MAC_QUERY_REGEX, "mac=$MASK")
        .replace(TOKEN_REGEX, "Bearer $MASK")
        .replace(GITHUB_TOKEN_REGEX, MASK)
        .replace(URL_REGEX) { maskUrl(it.value) }
        .replace(MAC_REGEX) { maskMac(it.value) }
}
