package com.missa.tv.data.epg

import com.google.common.truth.Truth.assertThat
import com.missa.tv.core.error.AppError
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Lecture d'un guide de programmes XMLTV.
 *
 * Les documents utilisés sont fictifs : aucun guide réel, aucune adresse réelle.
 * Sont vérifiés l'extraction des programmes, la conversion des horodatages avec
 * leur décalage horaire, le décodage des entités, la tolérance aux variantes et
 * les garde-fous (contenu étranger, guide vide, guide trop volumineux).
 */
@DisplayName("Lecture d'un guide XMLTV")
class XmltvParserTest {

    private val parser = XmltvParser()

    private fun programme(
        channel: String = "tf1.fr",
        start: String = "20261008120000 +0000",
        stop: String = "20261008130000 +0000",
        titre: String = "Journal",
        desc: String? = null,
    ): String {
        val description = if (desc == null) "" else "<desc lang=\"fr\">$desc</desc>"
        return """<programme start="$start" stop="$stop" channel="$channel">""" +
            """<title lang="fr">$titre</title>$description</programme>"""
    }

    private fun document(vararg corps: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<tv source-info-url="http://exemple.invalid/guide">""" +
            """<channel id="tf1.fr"><display-name lang="fr">TF1</display-name></channel>""" +
            corps.joinToString("") +
            """</tv>"""

    @Nested
    @DisplayName("Guide conforme")
    inner class Conforme {

        @Test
        fun `extrait les programmes avec leurs champs`() {
            val doc = document(
                programme(titre = "Journal", desc = "Les nouvelles du soir"),
                programme(start = "20261008130000 +0000", stop = "20261008140000 +0000", titre = "Film"),
            )

            val programmes = parser.parse(doc)

            assertThat(programmes).hasSize(2)
            val premier = programmes.first()
            assertThat(premier.channelId).isEqualTo("tf1.fr")
            assertThat(premier.title).isEqualTo("Journal")
            assertThat(premier.description).isEqualTo("Les nouvelles du soir")
            assertThat(programmes[1].title).isEqualTo("Film")
            assertThat(programmes[1].description).isNull()
        }

        @Test
        fun `les attributs dans le desordre sont acceptes`() {
            val doc = document(
                """<programme channel="tf1.fr" stop="20261008130000 +0000" start="20261008120000 +0000">""" +
                    """<title>Journal</title></programme>""",
            )

            val programmes = parser.parse(doc)

            assertThat(programmes.single().channelId).isEqualTo("tf1.fr")
        }

        @Test
        fun `les entites sont decodees dans le titre et la description`() {
            val doc = document(programme(titre = "Caf&#233; &amp; th&#233;", desc = "a &lt; b"))

            val premier = parser.parse(doc).single()

            assertThat(premier.title).isEqualTo("Café & thé")
            assertThat(premier.description).isEqualTo("a < b")
        }

        @Test
        fun `commentaires et balises auto-fermantes sont ignores`() {
            val doc = document(
                """<!-- un commentaire --><programme start="20261008120000 +0000" stop="20261008130000 +0000" channel="tf1.fr">""" +
                    """<icon src="http://exemple.invalid/img.png"/><title>Journal</title></programme>""",
            )

            assertThat(parser.parse(doc).single().title).isEqualTo("Journal")
        }
    }

    @Nested
    @DisplayName("Horodatages")
    inner class Horodatages {

        @Test
        fun `le decalage horaire est retranche pour obtenir l instant UTC`() {
            // 12:00 à +0200 = 10:00 UTC.
            val avecDecalage = parser.dateEnMillisecondes("20261008120000 +0200")
            val enUtc = parser.dateEnMillisecondes("20261008100000 +0000")

            assertThat(avecDecalage).isEqualTo(enUtc)
        }

        @Test
        fun `un decalage negatif est ajoute`() {
            // 08:00 à -0400 = 12:00 UTC.
            val negatif = parser.dateEnMillisecondes("20261008080000 -0400")
            val enUtc = parser.dateEnMillisecondes("20261008120000 +0000")

            assertThat(negatif).isEqualTo(enUtc)
        }

        @Test
        fun `une date sans decalage est lue comme UTC`() {
            val sansDecalage = parser.dateEnMillisecondes("20261008120000")
            val enUtc = parser.dateEnMillisecondes("20261008120000 +0000")

            assertThat(sansDecalage).isEqualTo(enUtc)
        }

        @Test
        fun `une date illisible est refusee`() {
            assertThat(parser.dateEnMillisecondes(null)).isNull()
            assertThat(parser.dateEnMillisecondes("")).isNull()
            assertThat(parser.dateEnMillisecondes("pas-une-date")).isNull()
            assertThat(parser.dateEnMillisecondes("2026")).isNull()
        }
    }

    @Nested
    @DisplayName("Entrées écartées")
    inner class EntreesEcartees {

        @Test
        fun `un programme sans identifiant de chaine est ignore`() {
            val doc = document(
                """<programme start="20261008120000 +0000" stop="20261008130000 +0000">""" +
                    """<title>Journal</title></programme>""",
            )

            val exception = assertThrows<XmltvParseException> { parser.parse(doc) }

            assertThat(exception.failure).isEqualTo(XmltvFailure.EMPTY)
        }

        @Test
        fun `un programme sans titre est ignore`() {
            val doc = document(
                """<programme start="20261008120000 +0000" stop="20261008130000 +0000" channel="tf1.fr"></programme>""",
            )

            val exception = assertThrows<XmltvParseException> { parser.parse(doc) }

            assertThat(exception.failure).isEqualTo(XmltvFailure.EMPTY)
        }

        @Test
        fun `un programme termine avant de commencer est ignore`() {
            val doc = document(
                programme(start = "20261008130000 +0000", stop = "20261008120000 +0000"),
            )

            val exception = assertThrows<XmltvParseException> { parser.parse(doc) }

            assertThat(exception.failure).isEqualTo(XmltvFailure.EMPTY)
        }

        @Test
        fun `un programme aux dates illisibles est ignore sans rejeter les autres`() {
            val doc = document(
                programme(start = "illisible", stop = "20261008130000 +0000", titre = "Mauvais"),
                programme(titre = "Bon"),
            )

            val programmes = parser.parse(doc)

            assertThat(programmes.map { it.title }).containsExactly("Bon")
        }
    }

    @Nested
    @DisplayName("Documents refusés en bloc")
    inner class Refus {

        @Test
        fun `un contenu non XMLTV est refuse`() {
            val exception = assertThrows<XmltvParseException> {
                parser.parse("ceci n'est pas du XMLTV")
            }

            assertThat(exception.failure).isEqualTo(XmltvFailure.INVALID)
            assertThat(exception.toAppError()).isEqualTo(AppError.EpgInvalid)
        }

        @Test
        fun `un guide sans programme est signale vide`() {
            val exception = assertThrows<XmltvParseException> { parser.parse(document()) }

            assertThat(exception.failure).isEqualTo(XmltvFailure.EMPTY)
            assertThat(exception.toAppError()).isEqualTo(AppError.EpgEmpty)
        }

        @Test
        fun `un guide au-dela de la limite est refuse`() {
            val petitParseur = XmltvParser(maxProgrammes = 2)
            val doc = document(
                programme(titre = "Un"),
                programme(start = "20261008130000 +0000", stop = "20261008140000 +0000", titre = "Deux"),
                programme(start = "20261008140000 +0000", stop = "20261008150000 +0000", titre = "Trois"),
            )

            val exception = assertThrows<XmltvParseException> { petitParseur.parse(doc) }

            assertThat(exception.failure).isEqualTo(XmltvFailure.TOO_LARGE)
            assertThat(exception.toAppError()).isEqualTo(AppError.EpgTooLarge)
        }
    }
}
