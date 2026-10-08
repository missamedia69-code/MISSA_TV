package com.missa.tv.data.playlist

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Lecture bornée d'une playlist depuis un flux d'octets.
 *
 * Les flux de test sont construits en mémoire ; les hôtes sont en `.invalid`.
 */
@DisplayName("Lecture bornée d'une playlist M3U")
class M3uPlaylistReaderTest {

    private fun flux(contenu: String): InputStream =
        ByteArrayInputStream(contenu.trimIndent().toByteArray(Charsets.UTF_8))

    @Test
    fun `lit une playlist depuis un flux`() {
        val lecteur = M3uPlaylistReader()

        val entrees = lecteur.read(
            flux(
                """
                #EXTM3U
                #EXTINF:-1,France Info
                http://flux.invalid/france-info
                """,
            ),
        )

        assertThat(entrees).hasSize(1)
        assertThat(entrees[0].title).isEqualTo("France Info")
    }

    @Test
    fun `signale un depassement du plafond d octets`() {
        // Plafond volontairement minuscule : le flux ci-dessous le dépasse.
        val lecteur = M3uPlaylistReader(maxBytes = 40)

        val exception = assertThrows(M3uParseException::class.java) {
            lecteur.read(
                flux(
                    """
                    #EXTM3U
                    #EXTINF:-1,Chaîne
                    http://flux.invalid/une-adresse-de-flux-assez-longue-pour-depasser
                    """,
                ),
            )
        }

        assertThat(exception.failure).isEqualTo(M3uFailure.TOO_LARGE)
    }

    @Test
    fun `accepte un flux juste sous le plafond`() {
        val contenu = "#EXTM3U\n#EXTINF:-1,A\nhttp://flux.invalid/a"
        val lecteur = M3uPlaylistReader(maxBytes = contenu.length.toLong() + 10)

        val entrees = lecteur.read(ByteArrayInputStream(contenu.toByteArray(Charsets.UTF_8)))

        assertThat(entrees).hasSize(1)
    }

    @Test
    fun `relaie le rejet d un contenu non reconnu`() {
        val lecteur = M3uPlaylistReader()

        val exception = assertThrows(M3uParseException::class.java) {
            lecteur.read(flux("Ceci n'a rien d'une playlist M3U."))
        }

        assertThat(exception.failure).isEqualTo(M3uFailure.INVALID)
    }

    @Test
    fun `relaie le rejet d une playlist vide`() {
        val lecteur = M3uPlaylistReader()

        val exception = assertThrows(M3uParseException::class.java) {
            lecteur.read(flux("#EXTM3U"))
        }

        assertThat(exception.failure).isEqualTo(M3uFailure.EMPTY)
    }
}
