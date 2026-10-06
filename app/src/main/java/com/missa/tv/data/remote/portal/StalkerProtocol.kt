package com.missa.tv.data.remote.portal

/**
 * Constantes du protocole Stalker / Ministra.
 *
 * Les portails de ce type n'ont pas de documentation officielle : ils
 * reconnaissent les boîtiers MAG par leur signature HTTP. L'application se
 * présente donc comme un MAG250, ce qui est la seule façon d'obtenir les mêmes
 * réponses que le décodeur d'origine.
 *
 * Rien d'autre n'est codé en dur ici : ni URL, ni adresse MAC, qui sont fournies
 * par la configuration ou saisies par l'utilisateur.
 */
object StalkerProtocol {

    /** Signature du boîtier, attendue telle quelle par les portails. */
    const val USER_AGENT =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) " +
            "MAG250 stbapp ver: 2 rev: 250 Safari/533.3"

    const val X_USER_AGENT = "Model: MAG250; Link: WiFi"

    /** Modèle annoncé lors du profil. */
    const val STB_TYPE = "MAG250"

    /** Format de réponse attendu : XML enveloppé, en pratique du JSON. */
    const val JS_HTTP_REQUEST = "1-xml"

    /**
     * Chemins d'API essayés dans l'ordre lors de la découverte de l'endpoint.
     * Le premier qui répond avec un jeton valide est mémorisé pour la session.
     */
    val ENDPOINT_PATHS = listOf(
        "server/load.php",
        "portal.php",
        "stalker_portal/server/load.php",
        "c/server/load.php",
    )

    // --- Types d'appel -------------------------------------------------------
    const val TYPE_STB = "stb"
    const val TYPE_ITV = "itv"

    // --- Actions -------------------------------------------------------------
    const val ACTION_HANDSHAKE = "handshake"
    const val ACTION_GET_PROFILE = "get_profile"
    const val ACTION_GET_GENRES = "get_genres"
    const val ACTION_GET_ALL_CHANNELS = "get_all_channels"
    const val ACTION_GET_ORDERED_LIST = "get_ordered_list"
    const val ACTION_CREATE_LINK = "create_link"
    const val ACTION_GET_EVENTS = "get_events"
    const val ACTION_GET_SHORT_EPG = "get_short_epg"

    /** Valeurs envoyées lors du profil, identiques à celles du décodeur. */
    val PROFILE_PARAMETERS: Map<String, String> = mapOf(
        "hd" to "1",
        "num_banks" to "2",
        "stb_type" to STB_TYPE,
        "client_type" to "STB",
        "image_version" to "218",
        "video_out" to "hdmi",
        "hw_version" to "1.7-BD-00",
        "not_valid_token" to "0",
        "api_signature" to "262",
        "device_id" to "",
        "device_id2" to "",
        "signature" to "",
        "sn" to "",
    )

    /**
     * En-têtes envoyés à chaque requête.
     *
     * L'adresse MAC est transmise dans un cookie `mac`, comme le fait le
     * décodeur, et le jeton dans `Authorization` une fois le handshake effectué.
     */
    fun headers(mac: String, token: String?, timezone: String): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "X-User-Agent" to X_USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Cookie" to "mac=$mac; stb_lang=fr; timezone=$timezone",
        )
        if (!token.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    /**
     * Nettoie la commande de lecture renvoyée par `create_link`.
     *
     * Le portail préfixe fréquemment l'URL par `ffmpeg `, ce qui n'est pas une
     * URL valide pour le lecteur.
     */
    fun playableUrl(rawCmd: String): String =
        rawCmd.trim()
            .removePrefix("ffmpeg ")
            .removePrefix("FFMPEG ")
            .trim()
}
