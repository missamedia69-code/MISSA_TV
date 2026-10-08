package com.missa.tv.data.remote.portal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Analyse des réponses de guide du portail.
 *
 * Les réponses reproduisent les formes réellement rencontrées : tableau
 * d'événements, tableau de chaînes imbriquées, réponse paginée, horodatages en
 * secondes ou en millisecondes, heures « HH:MM » inexploitables. Aucune adresse
 * ni URL réelle n'y figure.
 */
@DisplayName("Analyse du guide (EPG)")
class StalkerResponseParserEpgTest {

    private val parser = StalkerResponseParser()
    private val canal = "42"

    @Test
    fun `guide court en tableau direct d événements`() {
        val corps = """
            {"js":{"data":[
                {"id":"7","name":"Journal","start_timestamp":1700000000,"end_timestamp":1700003600,
                 "descr":"édition du soir"},
                {"id":"8","name":"Film","start_timestamp":1700003600,"end_timestamp":1700007200}
            ]}}
        """.trimIndent()

        val evenements = parser.shortEpg(corps, canal)

        assertThat(evenements).hasSize(2)
        val journal = evenements[0]
        assertThat(journal.id).isEqualTo("7")
        assertThat(journal.channelId).isEqualTo(canal)
        assertThat(journal.title).isEqualTo("Journal")
        assertThat(journal.description).isEqualTo("édition du soir")
        // Les secondes publiées par le portail sont converties en millisecondes.
        assertThat(journal.startMs).isEqualTo(1_700_000_000_000L)
        assertThat(journal.endMs).isEqualTo(1_700_003_600_000L)
    }

    @Test
    fun `guide court en tableau de chaînes imbriquées`() {
        // Forme renvoyée quand le portail répond pour toutes les chaînes d'un coup.
        val corps = """
            {"js":{"data":[
                {"id":"42","name":"Chaîne une","epg":[
                    {"id":"7","name":"Journal","start_timestamp":1700000000,"end_timestamp":1700003600}
                ]},
                {"id":"43","name":"Chaîne deux","epg":[
                    {"id":"9","name":"Match","start_timestamp":1700003600,"end_timestamp":1700007200}
                ]}
            ]}}
        """.trimIndent()

        val evenements = parser.shortEpg(corps, canal)

        assertThat(evenements.map { it.id to it.channelId })
            .containsExactly("7" to "42", "9" to "43")
            .inOrder()
    }

    @Test
    fun `guide complet en réponse paginée`() {
        val corps = """
            {"js":{"data":{"total_items":2,"max_page_items":1,"data":[
                {"id":"7","name":"Journal","start_timestamp":1700000000,"end_timestamp":1700003600}
            ]}}}
        """.trimIndent()

        val evenements = parser.events(corps, canal)

        assertThat(evenements).hasSize(1)
        assertThat(evenements[0].title).isEqualTo("Journal")
        assertThat(evenements[0].channelId).isEqualTo(canal)
    }

    @Test
    fun `guide en tableau direct au niveau de js`() {
        val corps = """
            {"js":[
                {"id":"7","name":"Journal","start_timestamp":1700000000,"end_timestamp":1700003600}
            ]}
        """.trimIndent()

        assertThat(parser.shortEpg(corps, canal)).hasSize(1)
    }

    @Test
    fun `les horodatages déjà en millisecondes ne sont pas convertis`() {
        val corps = """
            {"js":{"data":[
                {"id":"7","name":"Journal","start_timestamp":1700000000000,"end_timestamp":1700003600000}
            ]}}
        """.trimIndent()

        val evenement = parser.shortEpg(corps, canal).single()

        assertThat(evenement.startMs).isEqualTo(1_700_000_000_000L)
        assertThat(evenement.endMs).isEqualTo(1_700_003_600_000L)
    }

    @Test
    fun `les champs start et end en secondes servent de secours`() {
        val corps = """
            {"js":{"data":[
                {"id":"7","name":"Journal","start":1700000000,"end":1700003600}
            ]}}
        """.trimIndent()

        val evenement = parser.shortEpg(corps, canal).single()

        assertThat(evenement.startMs).isEqualTo(1_700_000_000_000L)
        assertThat(evenement.endMs).isEqualTo(1_700_003_600_000L)
    }

    @Test
    fun `les heures HH-mm sans timestamp sont ignorées`() {
        // « 20:00 » sans la date du jour est inexploitable : deviner la date
        // produirait un programme affiché au mauvais moment.
        val corps = """
            {"js":{"data":[
                {"id":"7","name":"Journal","start":"20:00","end":"21:00"}
            ]}}
        """.trimIndent()

        assertThat(parser.shortEpg(corps, canal)).isEmpty()
    }

    @Test
    fun `les événements inutilisables sont écartés`() {
        val corps = """
            {"js":{"data":[
                {"id":"7","start_timestamp":1700000000,"end_timestamp":1700003600},
                {"id":"8","name":"Sans fin","start_timestamp":1700003600},
                {"id":"9","name":"Inversé","start_timestamp":1700003600,"end_timestamp":1700000000},
                {"id":"10","name":"Valide","start_timestamp":1700000000,"end_timestamp":1700003600}
            ]}}
        """.trimIndent()

        val evenements = parser.shortEpg(corps, canal)

        assertThat(evenements.map { it.id }).containsExactly("10")
    }

    @Test
    fun `un identifiant absent est reconstruit depuis la chaîne et le début`() {
        val corps = """
            {"js":{"data":[
                {"name":"Journal","start_timestamp":1700000000,"end_timestamp":1700003600}
            ]}}
        """.trimIndent()

        assertThat(parser.shortEpg(corps, canal).single().id).isEqualTo("42-1700000000000")
    }

    @Test
    fun `le champ title est accepté comme libellé`() {
        val corps = """
            {"js":{"data":[
                {"id":"7","title":"Journal","start_timestamp":1700000000,"end_timestamp":1700003600}
            ]}}
        """.trimIndent()

        assertThat(parser.shortEpg(corps, canal).single().title).isEqualTo("Journal")
    }

    @Test
    fun `le champ description est accepté comme description`() {
        val corps = """
            {"js":{"data":[
                {"id":"7","name":"Journal","start_timestamp":1700000000,"end_timestamp":1700003600,
                 "description":"résumé de l édition"}
            ]}}
        """.trimIndent()

        assertThat(parser.shortEpg(corps, canal).single().description).isEqualTo("résumé de l édition")
    }

    @Test
    fun `une réponse sans guide est une liste vide`() {
        assertThat(parser.shortEpg("""{"js":{"data":[]}}""", canal)).isEmpty()
        assertThat(parser.events("""{"js":{"data":{"total_items":0,"max_page_items":25,"data":[]}}}""", canal))
            .isEmpty()
    }

    @Test
    fun `une réponse illisible est signalée`() {
        val erreur = assertThrows(PortalProtocolException::class.java) {
            parser.shortEpg("<html>page introuvable</html>", canal)
        }

        assertThat(erreur.failure).isEqualTo(PortalFailure.MALFORMED)
    }

    @Test
    fun `un refus du portail est signalé`() {
        val erreur = assertThrows(PortalProtocolException::class.java) {
            parser.events("""{"js":"error"}""", canal)
        }

        assertThat(erreur.failure).isEqualTo(PortalFailure.UNAUTHORIZED)
    }
}
