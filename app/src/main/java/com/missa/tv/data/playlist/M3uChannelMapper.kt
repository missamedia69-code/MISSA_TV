package com.missa.tv.data.playlist

import com.missa.tv.domain.model.Channel
import java.security.MessageDigest

/**
 * Conversion des entrées brutes d'une playlist M3U en chaînes du domaine.
 *
 * C'est ici que la migration M3U rejoint le modèle de domaine : l'URL de flux
 * devient [Channel.streamUrl], et les attributs de la playlist (`tvg-id`,
 * `http-user-agent`, `http-referrer`) remplissent les champs correspondants.
 *
 * Les playlists ne fournissent ni identifiant stable ni numéro : l'identifiant
 * est dérivé de l'URL du flux (stable d'un rafraîchissement à l'autre, ce qui
 * évite de dédoubler une chaîne déjà en base) et le numéro suit l'ordre de la
 * playlist.
 */
object M3uChannelMapper {

    /** Convertit [entries] en chaînes, numérotées dans l'ordre de la playlist. */
    fun toChannels(entries: List<M3uEntry>): List<Channel> =
        entries.mapIndexed { indice, entree -> toChannel(entree, indice + 1) }

    private fun toChannel(entree: M3uEntry, numero: Int): Channel = Channel(
        id = identifiant(entree.streamUrl),
        number = numero,
        name = entree.title,
        streamUrl = entree.streamUrl,
        logoUrl = entree.logoUrl,
        categoryId = entree.groupTitle,
        isCensored = false,
        isAvailable = true,
        tvgId = entree.tvgId,
        userAgent = entree.userAgent,
        referrer = entree.referrer,
    )

    /** Identifiant stable, dérivé de l'URL du flux. */
    private fun identifiant(streamUrl: String): String {
        val empreinte = MessageDigest.getInstance("SHA-1").digest(streamUrl.encodeToByteArray())
        return "m3u-" + empreinte.joinToString("") { octet -> "%02x".format(octet) }.take(24)
    }
}
