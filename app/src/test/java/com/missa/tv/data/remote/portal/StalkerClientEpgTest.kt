package com.missa.tv.data.remote.portal

import com.google.common.truth.Truth.assertThat
import com.missa.tv.domain.model.PortalProfile
import com.missa.tv.domain.model.PortalSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Dialogue du guide avec le portail, sur des réponses simulées.
 *
 * La doublure d'API capture les paramètres de chaque requête : ce qui est
 * vérifié ici, c'est le contrat de l'application — action demandée,
 * identifiant de chaîne, format des dates, pagination. Aucune adresse réelle
 * n'est utilisée.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Guide (EPG) côté client Stalker")
class StalkerClientEpgTest {

    private val profil = PortalProfile(
        id = "p1",
        name = "Test",
        portalUrl = "http://example.invalid/",
        mac = listOf("00", "1A", "79", "00", "00", "01").joinToString(":"),
    )

    /** Doublure d'API : répond selon les paramètres et mémorise chaque requête. */
    private class FakeApi(
        private val reponses: (params: Map<String, String>) -> String,
    ) : StalkerApi {

        val requetes = mutableListOf<Map<String, String>>()

        override suspend fun load(
            url: String,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): ResponseBody {
            requetes += parameters
            return reponses(parameters).toResponseBody("application/json".toMediaType())
        }
    }

    private fun reponseHandshake() = """{"js":{"token":"jeton-de-test"}}"""

    private fun reponseProfil() = """{"js":{"status":1,"subscribed":1}}"""

    private fun reponseEvenement(id: String, titre: String, debut: Long, fin: Long) =
        """{"js":{"data":[{"id":"$id","name":"$titre","start_timestamp":$debut,"end_timestamp":$fin}]}}"""

    private suspend fun connecter(api: FakeApi): Pair<StalkerClient, PortalSession> {
        val client = StalkerClient(api)
        val session = client.connect(profil)
        return client to session
    }

    @Test
    fun `le guide court est demandé avec l identifiant de la chaîne`() = runTest {
        val api = FakeApi { params ->
            when (params["action"]) {
                "handshake" -> reponseHandshake()
                "get_profile" -> reponseProfil()
                else -> reponseEvenement("7", "Journal", 1700000000, 1700003600)
            }
        }
        val (client, session) = connecter(api)

        val evenements = client.shortEpg(session, profil, "42")

        assertThat(evenements).hasSize(1)
        assertThat(evenements[0].title).isEqualTo("Journal")
        val requete = api.requetes.last()
        assertThat(requete["action"]).isEqualTo(StalkerProtocol.ACTION_GET_SHORT_EPG)
        assertThat(requete["type"]).isEqualTo(StalkerProtocol.TYPE_ITV)
        assertThat(requete["ch_id"]).isEqualTo("42")
    }

    @Test
    fun `le guide complet transmet la fenêtre au format du portail`() = runTest {
        val api = FakeApi { params ->
            when (params["action"]) {
                "handshake" -> reponseHandshake()
                "get_profile" -> reponseProfil()
                else -> reponseEvenement("7", "Journal", 1700000000, 1700003600)
            }
        }
        val (client, session) = connecter(api)

        // 1 700 000 000 000 ms = 14/11/2023 23:13 à Paris ; le lendemain à la même heure.
        client.events(session, profil, "42", fromMs = 1_700_000_000_000L, toMs = 1_700_086_400_000L)

        val requete = api.requetes.last()
        assertThat(requete["action"]).isEqualTo(StalkerProtocol.ACTION_GET_EVENTS)
        assertThat(requete["ch_id"]).isEqualTo("42")
        assertThat(requete["date_from"]).isEqualTo("2023-11-14")
        assertThat(requete["date_to"]).isEqualTo("2023-11-15")
        assertThat(requete["p"]).isEqualTo("1")
    }

    @Test
    fun `le guide complet est lu page après page`() = runTest {
        val api = FakeApi { params ->
            when (params["action"]) {
                "handshake" -> reponseHandshake()
                "get_profile" -> reponseProfil()
                else -> when (params["p"]) {
                    // Une page de un événement, deux pages au total.
                    "1" -> """
                        {"js":{"data":{"total_items":2,"max_page_items":1,"data":[
                            {"id":"7","name":"Journal","start_timestamp":1700000000,"end_timestamp":1700003600}
                        ]}}}
                    """.trimIndent()
                    else -> """
                        {"js":{"data":{"total_items":2,"max_page_items":1,"data":[
                            {"id":"8","name":"Film","start_timestamp":1700003600,"end_timestamp":1700007200}
                        ]}}}
                    """.trimIndent()
                }
            }
        }
        val (client, session) = connecter(api)

        val evenements = client.events(session, profil, "42", 0L, 1_700_086_400_000L)

        assertThat(evenements.map { it.id }).containsExactly("7", "8").inOrder()
        assertThat(api.requetes.count { it["action"] == StalkerProtocol.ACTION_GET_EVENTS })
            .isEqualTo(2)
    }

    @Test
    fun `la pagination s arrête sur une page vide`() = runTest {
        // La pagination annonce trois pages, mais la deuxième est vide : la
        // boucle s'arrête plutôt que de tourner dans le vide.
        val api = FakeApi { params ->
            when (params["action"]) {
                "handshake" -> reponseHandshake()
                "get_profile" -> reponseProfil()
                else -> when (params["p"]) {
                    "1" -> """
                        {"js":{"data":{"total_items":3,"max_page_items":1,"data":[
                            {"id":"7","name":"Journal","start_timestamp":1700000000,"end_timestamp":1700003600}
                        ]}}}
                    """.trimIndent()
                    else -> """{"js":{"data":{"total_items":3,"max_page_items":1,"data":[]}}}"""
                }
            }
        }
        val (client, session) = connecter(api)

        val evenements = client.events(session, profil, "42", 0L, 1_700_086_400_000L)

        assertThat(evenements.map { it.id }).containsExactly("7")
        assertThat(api.requetes.count { it["action"] == StalkerProtocol.ACTION_GET_EVENTS })
            .isEqualTo(2)
    }

    @Test
    fun `la pagination s arrête quand la dernière page est atteinte`() = runTest {
        // Deux pages de un événement : `total_items` ne laisse aucune page suivante.
        val api = FakeApi { params ->
            when (params["action"]) {
                "handshake" -> reponseHandshake()
                "get_profile" -> reponseProfil()
                else -> when (params["p"]) {
                    "1" -> """
                        {"js":{"data":{"total_items":2,"max_page_items":1,"data":[
                            {"id":"7","name":"Journal","start_timestamp":1700000000,"end_timestamp":1700003600}
                        ]}}}
                    """.trimIndent()
                    else -> """
                        {"js":{"data":{"total_items":2,"max_page_items":1,"data":[
                            {"id":"8","name":"Film","start_timestamp":1700003600,"end_timestamp":1700007200}
                        ]}}}
                    """.trimIndent()
                }
            }
        }
        val (client, session) = connecter(api)

        val evenements = client.events(session, profil, "42", 0L, 1_700_086_400_000L)

        assertThat(evenements.map { it.id }).containsExactly("7", "8").inOrder()
        // Page 2 atteinte : 2 * 1 >= 2, aucune troisième requête.
        assertThat(api.requetes.count { it["action"] == StalkerProtocol.ACTION_GET_EVENTS })
            .isEqualTo(2)
    }

    @Test
    fun `un guide vide du portail est une liste vide`() = runTest {
        val api = FakeApi { params ->
            when (params["action"]) {
                "handshake" -> reponseHandshake()
                "get_profile" -> reponseProfil()
                else -> """{"js":{"data":[]}}"""
            }
        }
        val (client, session) = connecter(api)

        assertThat(client.shortEpg(session, profil, "42")).isEmpty()
        assertThat(client.events(session, profil, "42", 0L, 1_700_086_400_000L)).isEmpty()
    }

    @Test
    fun `un refus du portail est propagé`() = runTest {
        val api = FakeApi { params ->
            when (params["action"]) {
                "handshake" -> reponseHandshake()
                "get_profile" -> reponseProfil()
                else -> """{"js":"error"}"""
            }
        }
        val (client, session) = connecter(api)

        val erreur = runCatching { client.shortEpg(session, profil, "42") }.exceptionOrNull()

        assertThat(erreur).isInstanceOf(PortalProtocolException::class.java)
        assertThat((erreur as PortalProtocolException).failure).isEqualTo(PortalFailure.UNAUTHORIZED)
    }
}
