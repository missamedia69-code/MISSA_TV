package com.missa.tv.data.remote.portal

import com.google.common.truth.Truth.assertThat
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.PortalProfile
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Dialogue avec le portail, sur des réponses simulées.
 *
 * Le portail est remplacé par une doublure : les tests vérifient le
 * comportement de l'application (découverte de l'endpoint, pagination, maintien
 * de session), jamais un vrai serveur. Aucune adresse réelle n'est utilisée.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Client Stalker")
class StalkerClientTest {

    private val mac = listOf("00", "1A", "79", "00", "00", "01").joinToString(":")

    private val profil = PortalProfile(
        id = "p1",
        name = "Test",
        portalUrl = "http://example.invalid/",
        mac = mac,
    )

    /** Doublure d'API : répond selon l'URL et l'action demandées. */
    private class FakeApi(
        private val echecs: (url: String) -> Boolean = { false },
        private val reponses: (url: String, action: String, page: String?) -> String?,
    ) : StalkerApi {

        var appels = 0
            private set

        val urlsAppelees = mutableListOf<String>()

        override suspend fun load(
            url: String,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): ResponseBody {
            appels++
            urlsAppelees += url
            if (echecs(url)) throw IOException("hôte injoignable")
            val action = parameters["action"].orEmpty()
            val page = parameters["p"]
            val corps = reponses(url, action, page)
                ?: throw IOException("réponse absente pour $action")
            return corps.toResponseBody("application/json".toMediaType())
        }
    }

    private fun reponseHandshake(jeton: String = "jeton-de-test") =
        """{"js":{"token":"$jeton","not_valid":0}}"""

    private fun reponseProfil() = """{"js":{"status":1,"subscribed":1}}"""

    @Nested
    @DisplayName("Découverte de l'endpoint")
    inner class Decouverte {

        @Test
        fun `retient le premier chemin qui repond`() = runTest {
            val api = FakeApi { url, action, _ ->
                when {
                    url.contains("/server/load.php") ->
                        if (action == "handshake") reponseHandshake() else reponseProfil()
                    else -> null
                }
            }
            val client = StalkerClient(api)

            val session = client.connect(profil)

            assertThat(session.endpoint).isEqualTo("http://example.invalid/server/load.php")
            assertThat(session.token).isEqualTo("jeton-de-test")
        }

        @Test
        fun `essaie les chemins suivants quand le premier echoue`() = runTest {
            // Portail installé dans un sous-répertoire : seuls les chemins
            // profond répondent.
            val api = FakeApi { url, action, _ ->
                when {
                    url.contains("stalker_portal") ->
                        if (action == "handshake") reponseHandshake() else reponseProfil()
                    else -> null
                }
            }
            val client = StalkerClient(api)

            val session = client.connect(profil)

            assertThat(session.endpoint)
                .isEqualTo("http://example.invalid/stalker_portal/server/load.php")
            assertThat(api.urlsAppelees.first()).contains("/server/load.php")
        }

        @Test
        fun `memorise l'endpoint pour les connexions suivantes`() = runTest {
            val api = FakeApi { _, action, _ ->
                if (action == "handshake") reponseHandshake() else reponseProfil()
            }
            val client = StalkerClient(api)

            client.connect(profil)
            val appelsApresPremiere = api.appels
            client.connect(profil)

            // La seconde connexion ne refait pas la découverte : deux appels de
            // plus seulement (handshake + profil).
            assertThat(api.appels - appelsApresPremiere).isEqualTo(2)
        }

        @Test
        fun `signale un portail injoignable`() = runTest {
            val api = FakeApi(reponses = { _, _, _ -> null }, echecs = { true })
            val client = StalkerClient(api)

            val erreur = runCatching { client.connect(profil) }.exceptionOrNull()

            assertThat(erreur).isInstanceOf(PortalProtocolException::class.java)
            assertThat((erreur as PortalProtocolException).failure)
                .isEqualTo(PortalFailure.MALFORMED)
            // Les quatre chemins connus ont été essayés.
            assertThat(api.appels).isEqualTo(StalkerProtocol.ENDPOINT_PATHS.size)
        }

        @Test
        fun `s_arrete des le refus d_autorisation`() = runTest {
            val api = FakeApi { _, _, _ -> """{"js":"error"}""" }
            val client = StalkerClient(api)

            val erreur = runCatching { client.connect(profil) }.exceptionOrNull()

            assertThat((erreur as PortalProtocolException).failure)
                .isEqualTo(PortalFailure.UNAUTHORIZED)
            // Inutile d'essayer les autres chemins : le portail a bien été trouvé.
            assertThat(api.appels).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("Catalogue")
    inner class Catalogue {

        @Test
        fun `recupere toutes les pages de chaines`() = runTest {
            val api = FakeApi { _, action, page ->
                when (action) {
                    "handshake" -> reponseHandshake()
                    "get_profile" -> reponseProfil()
                    "get_genres" -> """{"js":[{"id":"2","title":"Toutes"}]}"""
                    "get_all_channels" -> when (page) {
                        "1" -> """{"js":{"total_items":3,"max_page_items":2,"data":[
                            {"id":"1","number":1,"name":"Une","cmd":"a"},
                            {"id":"2","number":2,"name":"Deux","cmd":"b"}]}}"""
                        "2" -> """{"js":{"total_items":3,"max_page_items":2,"data":[
                            {"id":"3","number":3,"name":"Trois","cmd":"c"}]}}"""
                        else -> """{"js":{"total_items":3,"max_page_items":2,"data":[]}}"""
                    }
                    else -> null
                }
            }
            val client = StalkerClient(api)
            val session = client.connect(profil)

            val catalogue = client.catalog(session, profil)

            assertThat(catalogue.categories.map { it.id }).containsExactly("2")
            assertThat(catalogue.channels.map { it.id }).containsExactly("1", "2", "3").inOrder()
            assertThat(catalogue.channels.map { it.number }).isInOrder()
        }

        @Test
        fun `s_arrete quand le portail ne pagine pas`() = runTest {
            val api = FakeApi { _, action, _ ->
                when (action) {
                    "handshake" -> reponseHandshake()
                    "get_profile" -> reponseProfil()
                    "get_genres" -> """{"js":[]}"""
                    "get_all_channels" -> """{"js":{"data":[
                        {"id":"1","number":1,"name":"Une","cmd":"a"}]}}"""
                    else -> null
                }
            }
            val client = StalkerClient(api)
            val session = client.connect(profil)

            val chaines = client.channels(session, profil)

            assertThat(chaines).hasSize(1)
        }

        @Test
        fun `cree un lien de lecture pour une chaine`() = runTest {
            val api = FakeApi { _, action, _ ->
                when (action) {
                    "handshake" -> reponseHandshake()
                    "get_profile" -> reponseProfil()
                    "create_link" ->
                        """{"js":{"cmd":"ffmpeg http://example.invalid/live/1.m3u8","id":"1"}}"""
                    else -> null
                }
            }
            val client = StalkerClient(api, clockMs = { 5_000L })
            val session = client.connect(profil)
            val chaine = Channel(id = "1", number = 1, name = "Une", cmd = "ffmpeg x")

            val lien = client.createLink(session, profil, chaine)

            assertThat(lien.url).isEqualTo("http://example.invalid/live/1.m3u8")
            assertThat(lien.isHls).isTrue()
            assertThat(lien.createdAtMs).isEqualTo(5_000L)
        }
    }

    @Nested
    @DisplayName("Maintien de session")
    inner class MaintienDeSession {

        @Test
        fun `s_interrompt quand le portail ne repond plus`() = runTest {
            var appelsRestants = 2
            val api = FakeApi { _, action, _ ->
                when (action) {
                    "handshake" ->
                        if (appelsRestants-- > 0) reponseHandshake() else throw IOException("coupure")
                    "get_profile" -> reponseProfil()
                    else -> null
                }
            }
            val client = StalkerClient(api)
            val session = client.connect(profil)

            val job = launch { client.keepAlive(session, profil, intervalMs = 1_000L) }
            advanceTimeBy(1_000L)
            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()

            // Le maintien s'arrête de lui-même au lieu de réessayer sans fin.
            assertThat(job.isCompleted).isTrue()
        }

        @Test
        fun `continue tant que le portail repond`() = runTest {
            val api = FakeApi { _, action, _ ->
                if (action == "handshake") reponseHandshake() else reponseProfil()
            }
            val client = StalkerClient(api)
            val session = client.connect(profil)
            val appelsAvant = api.appels

            val job = launch { client.keepAlive(session, profil, intervalMs = 1_000L) }
            advanceTimeBy(3_500L)
            runCurrent()

            assertThat(api.appels - appelsAvant).isAtLeast(3)
            job.cancel()
        }
    }
}
