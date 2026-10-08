package com.missa.tv.data.playlist

import java.io.InputStream

/**
 * Lecture d'une playlist M3U depuis un flux d'octets.
 *
 * Le flux est parcouru en flux tendu, ligne par ligne : la playlist n'est pas
 * chargée entièrement en mémoire. Un plafond d'octets ([maxBytes]) borne la
 * lecture : au-delà, l'analyse s'interrompt et signale
 * [M3uFailure.TOO_LARGE], ce qui protège l'application d'un fichier
 * anormalement volumineux (limite de téléchargement de la migration M3U).
 *
 * La taille est mesurée sur les octets réellement lus du flux, pas sur une
 * déclaration `Content-Length` qui peut être absente ou mensongère.
 */
class M3uPlaylistReader(
    private val parser: M3uParser = M3uParser(),
    private val maxBytes: Long = MAX_DOWNLOAD_BYTES,
) {

    /**
     * Analyse le flux [entree] et renvoie les entrées reconnues.
     *
     * Le flux est fermé par l'appelant ; cette méthode ne le ferme pas.
     *
     * @throws M3uParseException si le flux dépasse [maxBytes]
     * ([M3uFailure.TOO_LARGE]), s'il n'est pas une playlist
     * ([M3uFailure.INVALID]) ou s'il ne contient aucune chaîne
     * ([M3uFailure.EMPTY]).
     */
    fun read(entree: InputStream): List<M3uEntry> {
        val borne = FluxBorne(entree, maxBytes)
        return borne.bufferedReader(Charsets.UTF_8).use { lecteur ->
            parser.parse(lecteur.lineSequence())
        }
    }

    /**
     * Délégation de flux qui compte les octets lus et lève une erreur dès que
     * le plafond est dépassé.
     */
    private class FluxBorne(
        private val source: InputStream,
        private val maxBytes: Long,
    ) : InputStream() {

        private var octetsLus = 0L

        override fun read(): Int {
            val octet = source.read()
            if (octet != FIN) compter(1)
            return octet
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val lus = source.read(buffer, offset, length)
            if (lus > 0) compter(lus)
            return lus
        }

        override fun close() {
            source.close()
        }

        private fun compter(n: Int) {
            octetsLus += n
            if (octetsLus > maxBytes) {
                throw M3uParseException(
                    M3uFailure.TOO_LARGE,
                    "Playlist au-delà de la taille maximale de téléchargement",
                )
            }
        }
    }

    companion object {
        /** Plafond de téléchargement d'une playlist : 100 Mo. */
        const val MAX_DOWNLOAD_BYTES: Long = 100L * 1024L * 1024L

        private const val FIN = -1
    }
}
