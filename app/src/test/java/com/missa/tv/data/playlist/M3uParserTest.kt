package com.missa.tv.data.playlist

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Analyse des playlists M3U.
 *
 * Les playlists de test utilisent exclusivement des hôtes en `.invalid` (RFC
 * 2606) : aucune URL réelle ni aucun identifiant ne figure dans ce fichier.
 */
@DisplayName("Analyse des playlists M3U")
class M3uParserTest {

    private val parser = M3uParser()

    private fun analyser(contenu: String): List<M3uEntry> =
        parser.parse(contenu.trimIndent().lineSequence())

    @Nested
    @DisplayName("Playlists valides")
    inner class Valides {

        @Test
        fun `extrait une entree simple`() {
            val entrees = analyser(
                """
                #EXTM3U
                #EXTINF:-1,France Info
                http://flux.invalid/france-info
                """,
            )

            assertThat(entrees).hasSize(1)
            assertThat(entrees[0].title).isEqualTo("France Info")
            assertThat(entrees[0].streamUrl).isEqualTo("http://flux.invalid/france-info")
        }

        @Test
        fun `extrait les attributs tvg et le groupe`() {
            val entrees = analyser(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="fr.news" tvg-logo="http://logo.invalid/n.png" group-title="Info",France News HD
                http://flux.invalid/fr-news
                """,
            )

            val entree = entrees.single()
            assertThat(entree.tvgId).isEqualTo("fr.news")
            assertThat(entree.logoUrl).isEqualTo("http://logo.invalid/n.png")
            assertThat(entree.groupTitle).isEqualTo("Info")
            assertThat(entree.title).isEqualTo("France News HD")
        }

        @Test
        fun `lit plusieurs entrees a la suite`() {
            val entrees = analyser(
                """
                #EXTM3U
                #EXTINF:-1,Première
                http://flux.invalid/premiere
                #EXTINF:-1,Deuxième
                http://flux.invalid/deuxieme
                #EXTINF:-1,Troisième
                http://flux.invalid/troisieme
                """,
            )

            assertThat(entrees.map { it.title })
                .containsExactly("Première", "Deuxième", "Troisième").inOrder()
        }

        @Test
        fun `une entree n herite pas des metadonnees de la precedente`() {
            val entrees = analyser(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="a.un" group-title="Groupe A",Chaîne A
                http://flux.invalid/a
                #EXTINF:-1,Chaîne B
                http://flux.invalid/b
                """,
            )

            assertThat(entrees[1].tvgId).isNull()
            assertThat(entrees[1].groupTitle).isNull()
        }

        @Test
        fun `accepte une URL sans EXTINF en lui donnant l adresse comme nom`() {
            val entrees = analyser(
                """
                #EXTM3U
                http://flux.invalid/seule
                """,
            )

            val entree = entrees.single()
            assertThat(entree.streamUrl).isEqualTo("http://flux.invalid/seule")
            assertThat(entree.title).isEqualTo("http://flux.invalid/seule")
        }

        @Test
        fun `tolere BOM et fins de ligne CRLF`() {
            val entrees = parser.parse(
                sequenceOf(
                    "\uFEFF#EXTM3U\r",
                    "#EXTINF:-1,Chaîne\r",
                    "http://flux.invalid/crlf\r",
                ),
            )

            assertThat(entrees).hasSize(1)
            assertThat(entrees[0].streamUrl).isEqualTo("http://flux.invalid/crlf")
        }

        @Test
        fun `accepte une majuscule ou minuscule sur les directives`() {
            val entrees = analyser(
                """
                #EXTM3U
                #extinf:-1,Mixe
                http://flux.invalid/mixe
                """,
            )

            assertThat(entrees.single().title).isEqualTo("Mixe")
        }
    }

    @Nested
    @DisplayName("Attributs de lecture")
    inner class AttributsLecture {

        @Test
        fun `retient l agent utilisateur et le referrent EXTVLCOPT`() {
            val entrees = analyser(
                """
                #EXTM3U
                #EXTINF:-1,Chaîne protégée
                #EXTVLCOPT:http-user-agent=Mozilla/5.0 (MISSA)
                #EXTVLCOPT:http-referrer=http://origine.invalid/
                http://flux.invalid/protegee
                """,
            )

            val entree = entrees.single()
            assertThat(entree.userAgent).isEqualTo("Mozilla/5.0 (MISSA)")
            assertThat(entree.referrer).isEqualTo("http://origine.invalid/")
        }

        @Test
        fun `retient le groupe declare par EXTGRP`() {
            val entrees = analyser(
                """
                #EXTM3U
                #EXTINF:-1,Chaîne Sport
                #EXTGRP:Sport
                http://flux.invalid/sport
                """,
            )

            assertThat(entrees.single().groupTitle).isEqualTo("Sport")
        }

        @Test
        fun `le group-title prime sur EXTGRP`() {
            val entrees = analyser(
                """
                #EXTM3U
                #EXTINF:-1 group-title="Direct",Chaîne
                #EXTGRP:Autre
                http://flux.invalid/chaine
                """,
            )

            assertThat(entrees.single().groupTitle).isEqualTo("Direct")
        }

        @Test
        fun `une virgule dans une valeur entre guillemets ne coupe pas le titre`() {
            val entrees = analyser(
                """
                #EXTM3U
                #EXTINF:-1 group-title="News, Sport",Ma Chaîne
                http://flux.invalid/machaine
                """,
            )

            val entree = entrees.single()
            assertThat(entree.groupTitle).isEqualTo("News, Sport")
            assertThat(entree.title).isEqualTo("Ma Chaîne")
        }
    }

    @Nested
    @DisplayName("Rejets")
    inner class Rejets {

        @Test
        fun `signale un contenu qui n est pas une playlist`() {
            val exception = assertThrows(M3uParseException::class.java) {
                analyser("Bonjour le monde, ceci n'a rien d'une playlist.")
            }

            assertThat(exception.failure).isEqualTo(M3uFailure.INVALID)
        }

        @Test
        fun `signale une playlist sans aucune entree`() {
            val exception = assertThrows(M3uParseException::class.java) {
                analyser(
                    """
                    #EXTM3U
                    #EXTINF:-1,Orpheline sans URL
                    """,
                )
            }

            assertThat(exception.failure).isEqualTo(M3uFailure.EMPTY)
        }

        @Test
        fun `signale un en-tete seul comme vide`() {
            val exception = assertThrows(M3uParseException::class.java) {
                analyser("#EXTM3U")
            }

            assertThat(exception.failure).isEqualTo(M3uFailure.EMPTY)
        }

        @Test
        fun `ignore les lignes qui ne sont pas des URL`() {
            val exception = assertThrows(M3uParseException::class.java) {
                analyser(
                    """
                    #EXTM3U
                    #EXTINF:-1,Promesse sans flux
                    pas une url du tout
                    """,
                )
            }

            assertThat(exception.failure).isEqualTo(M3uFailure.EMPTY)
        }

        @Test
        fun `rejette un html comme contenu invalide`() {
            val exception = assertThrows(M3uParseException::class.java) {
                analyser(
                    """
                    <html>
                    <body><a href="http://lien.invalid/page">accueil</a></body>
                    </html>
                    """,
                )
            }

            assertThat(exception.failure).isEqualTo(M3uFailure.INVALID)
        }

        @Test
        fun `s arrete au dela du nombre maximal d entrees`() {
            val petitParseur = M3uParser(maxEntries = 2)
            val lignes = buildList {
                add("#EXTM3U")
                repeat(3) { indice ->
                    add("#EXTINF:-1,Chaîne $indice")
                    add("http://flux.invalid/chaine-$indice")
                }
            }

            val exception = assertThrows(M3uParseException::class.java) {
                petitParseur.parse(lignes.asSequence())
            }

            assertThat(exception.failure).isEqualTo(M3uFailure.TOO_LARGE)
        }

        @Test
        fun `accepte exactement le nombre maximal d entrees`() {
            val petitParseur = M3uParser(maxEntries = 2)
            val lignes = buildList {
                add("#EXTM3U")
                repeat(2) { indice ->
                    add("#EXTINF:-1,Chaîne $indice")
                    add("http://flux.invalid/chaine-$indice")
                }
            }

            val entrees = petitParseur.parse(lignes.asSequence())

            assertThat(entrees).hasSize(2)
        }
    }
}
