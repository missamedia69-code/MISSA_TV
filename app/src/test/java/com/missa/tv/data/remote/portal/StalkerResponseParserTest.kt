package com.missa.tv.data.remote.portal

import com.google.common.truth.Truth.assertThat
import com.missa.tv.domain.model.VideoQuality
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Analyse des réponses du portail.
 *
 * Les réponses utilisées ici reproduisent la forme réellement renvoyée par un
 * portail Stalker : enveloppe `js`, nombres parfois entre guillemets, pages
 * paginées. Aucune adresse ni URL réelle n'y figure : les hôtes sont en
 * `.invalid` et les MAC sont construites à l'exécution.
 */
@DisplayName("Analyse des réponses Stalker")
class StalkerResponseParserTest {

    private val parser = StalkerResponseParser()

    @Nested
    @DisplayName("Handshake")
    inner class Handshake {

        @Test
        fun `extrait le jeton de session`() {
            val corps = """{"js":{"token":"jeton-de-test","not_valid":0,"random":"abc"}}"""

            assertThat(parser.token(corps)).isEqualTo("jeton-de-test")
        }

        @ParameterizedTest
        @ValueSource(strings = ["""{"js":"error"}""", """{"js":false}""", """{"js":{}}"""])
        fun `refuse une reponse sans jeton`(corps: String) {
            val erreur = assertThrows(PortalProtocolException::class.java) { parser.token(corps) }

            assertThat(erreur.failure).isEqualTo(PortalFailure.UNAUTHORIZED)
        }

        @Test
        fun `signale une reponse illisible`() {
            val erreur = assertThrows(PortalProtocolException::class.java) {
                parser.token("<html>page introuvable</html>")
            }

            assertThat(erreur.failure).isEqualTo(PortalFailure.MALFORMED)
        }

        @Test
        fun `signale une reponse sans enveloppe js`() {
            val erreur = assertThrows(PortalProtocolException::class.java) {
                parser.token("""{"token":"jeton"}""")
            }

            assertThat(erreur.failure).isEqualTo(PortalFailure.MALFORMED)
        }
    }

    @Nested
    @DisplayName("Profil et abonnement")
    inner class Profil {

        @Test
        fun `accepte un abonnement actif`() {
            val corps = """
                {"js":{"id":"42","status":1,"subscribed":1,
                "expire_billing_date":"2027-01-31","trial":0}}
            """.trimIndent()

            val compte = parser.account(corps)

            assertThat(compte.explicitlyInactive).isFalse()
            assertThat(compte.expiresAtRaw).isEqualTo("2027-01-31")
            assertThat(compte.isTrial).isFalse()
        }

        @Test
        fun `ne refuse jamais un profil exprime en champs variables`() {
            // Cas réel : un portail parfaitement fonctionnel (il fonctionne avec
            // d'autres lecteurs) qui annonce son abonnement sous forme de tableau
            // et un `status` à zéro. Conclure « abonnement expiré » sur ces seules
            // valeurs rendait l'application inutilisable : c'est le portail qui
            // décide, pas une déduction de l'application.
            val corps = """{"js":{"id":"42","status":0,"subscribed":[1,1],"phone":"000"}}"""

            val compte = parser.account(corps)

            assertThat(compte.explicitlyInactive).isFalse()
        }

        @Test
        fun `marque comme inactif un abonnement explicitement refuse`() {
            val corps = """{"js":{"id":"42","status":0,"subscribed":0}}"""

            val compte = parser.account(corps)

            // L'information est conservée pour expliquer un refus du portail,
            // mais elle ne lève aucune erreur à elle seule.
            assertThat(compte.explicitlyInactive).isTrue()
        }

        @Test
        fun `laisse les champs absents inconnus plutot que faussement inactifs`() {
            val corps = """{"js":{"id":"42"}}"""

            val compte = parser.account(corps)

            assertThat(compte.isActive).isNull()
            assertThat(compte.isSubscribed).isNull()
            assertThat(compte.explicitlyInactive).isFalse()
        }

        @Test
        fun `lit subscribed en tableau avec une seule entree`() {
            val corps = """{"js":{"status":1,"subscribed":["1"]}}"""

            assertThat(parser.account(corps).isSubscribed).isTrue()
        }

        @Test
        fun `un tableau d_abonnements tous nuls est lu, sans conclure seul`() {
            val corps = """{"js":{"subscribed":[0,0,0]}}"""

            val compte = parser.account(corps)

            // Le tableau est bien compris…
            assertThat(compte.isSubscribed).isFalse()
            // …mais un seul champ ne suffit jamais à conclure à un abonnement
            // inactif : il faut que les deux champs concordent.
            assertThat(compte.explicitlyInactive).isFalse()
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                """{"js":{"id":"42","status":0}}""",
                """{"js":{"id":"42","subscribed":0}}""",
            ],
        )
        fun `un seul champ a zero ne conclut pas a un abonnement inactif`(corps: String) {
            assertThat(parser.account(corps).explicitlyInactive).isFalse()
        }

        @Test
        fun `se rabat sur status quand subscribed est absent`() {
            // Certains portails n'exposent que « status ».
            val corps = """{"js":{"status":1}}"""

            assertThat(parser.account(corps).isActive).isTrue()
        }

        @Test
        fun `un profil sans objet exploitable reste une erreur de protocole`() {
            // Là, ce n'est pas le compte qui est en cause mais la forme de la
            // réponse : la session n'est pas utilisable.
            val erreur = assertThrows(PortalProtocolException::class.java) {
                parser.account("""{"js":[]}""")
            }

            assertThat(erreur.failure).isEqualTo(PortalFailure.MALFORMED)
        }

        @Test
        fun `un refus exprime en texte reste une erreur de session`() {
            // Un `js` textuel est un refus du portail : sans terme qui désigne le
            // compte, il ne devient jamais un « abonnement inactif ».
            val erreur = assertThrows(PortalProtocolException::class.java) {
                parser.account("""{"js":"erreur"}""")
            }

            assertThat(erreur.failure).isEqualTo(PortalFailure.UNAUTHORIZED)
        }
    }

    @Nested
    @DisplayName("Catégories et chaînes")
    inner class Catalogue {

        @Test
        fun `lit les categories renvoyees sous forme de tableau`() {
            val corps = """{"js":[{"id":"2","title":"Toutes"},{"id":"5","title":"Sport"}]}"""

            val categories = parser.categories(corps)

            assertThat(categories.map { it.id }).containsExactly("2", "5").inOrder()
            assertThat(categories.map { it.title }).containsExactly("Toutes", "Sport").inOrder()
        }

        @Test
        fun `lit les chaines d_une page`() {
            val corps = """
                {"js":{"total_items":2,"max_page_items":2,"data":[
                  {"id":"101","number":"1","name":"Chaine Une","cmd":"ffmpeg http://example.invalid/a",
                   "logo":"http://example.invalid/logo.png","tv_genre_id":"5","status":1},
                  {"id":"102","number":2,"name":"Chaine Deux","cmd":"ffmpeg http://example.invalid/b",
                   "status":0,"censored":1}
                ]}}
            """.trimIndent()

            val chaines = parser.channels(corps)

            assertThat(chaines).hasSize(2)
            val premiere = chaines.first()
            assertThat(premiere.id).isEqualTo("101")
            assertThat(premiere.number).isEqualTo(1)
            assertThat(premiere.categoryId).isEqualTo("5")
            assertThat(premiere.logoUrl).isEqualTo("http://example.invalid/logo.png")
            assertThat(premiere.isAvailable).isTrue()

            val seconde = chaines.last()
            assertThat(seconde.isAvailable).isFalse()
            assertThat(seconde.isCensored).isTrue()
            assertThat(seconde.logoUrl).isNull()
        }

        @Test
        fun `ignore les chaines sans identifiant ou sans nom`() {
            val corps = """
                {"js":{"data":[{"number":"1","name":"Sans identifiant"},
                {"id":"7","number":"2"},{"id":"8","number":"3","name":"Valide"}]}}
            """.trimIndent()

            assertThat(parser.channels(corps).map { it.id }).containsExactly("8")
        }

        @Test
        fun `ignore les logos non absolus`() {
            val corps = """
                {"js":{"data":[{"id":"1","number":1,"name":"Test","logo":"/relatif.png"}]}}
            """.trimIndent()

            assertThat(parser.channels(corps).single().logoUrl).isNull()
        }
    }

    @Nested
    @DisplayName("Lien de lecture")
    inner class Lien {

        @Test
        fun `retire le prefixe ffmpeg de la commande`() {
            val corps = """{"js":{"cmd":"ffmpeg http://example.invalid/live/1.m3u8","id":"101"}}"""

            val lien = parser.streamLink(corps, channelId = "101", nowMs = 1_000L)

            assertThat(lien.url).isEqualTo("http://example.invalid/live/1.m3u8")
            assertThat(lien.isHls).isTrue()
            assertThat(lien.channelId).isEqualTo("101")
        }

        @Test
        fun `reconnait un flux non HLS`() {
            val corps = """{"js":{"cmd":"http://example.invalid/live/1.ts"}}"""

            val lien = parser.streamLink(corps, channelId = "1", nowMs = 0L)

            assertThat(lien.isHls).isFalse()
        }

        @Test
        fun `signale l_absence de flux`() {
            val erreur = assertThrows(PortalProtocolException::class.java) {
                parser.streamLink("""{"js":{}}""", channelId = "1", nowMs = 0L)
            }

            assertThat(erreur.failure).isEqualTo(PortalFailure.STREAM_UNAVAILABLE)
        }

        @Test
        fun `refuse une commande qui n_est pas une URL`() {
            val erreur = assertThrows(PortalProtocolException::class.java) {
                parser.streamLink("""{"js":{"cmd":"ffmpeg /dev/null"}}""", channelId = "1", nowMs = 0L)
            }

            assertThat(erreur.failure).isEqualTo(PortalFailure.STREAM_UNAVAILABLE)
        }
    }

    @Nested
    @DisplayName("Refus explicite du portail")
    inner class Refus {

        @Test
        fun `un refus qui parle d_expiration est reconnu comme tel`() {
            val erreur = assertThrows(PortalProtocolException::class.java) {
                parser.token("""{"js":"Subscription expired"}""")
            }

            assertThat(erreur.failure).isEqualTo(PortalFailure.EXPIRED)
        }

        @Test
        fun `un refus qui parle d_inactivite est reconnu comme tel`() {
            val erreur = assertThrows(PortalProtocolException::class.java) {
                parser.channels("""{"js":{"error":"Account is inactive"}}""")
            }

            assertThat(erreur.failure).isEqualTo(PortalFailure.EXPIRED)
        }

        @Test
        fun `un refus technique reste une erreur de session`() {
            // « Session expirée » se répare en rouvrant une session : l'annoncer
            // comme un abonnement inactif enverrait l'utilisateur au mauvais
            // endroit. Ici, la réponse ne contient aucune chaîne, sans pour
            // autant accuser le compte.
            val chaines = parser.channels("""{"js":{"error":"Session expired, please login again"}}""")

            assertThat(chaines).isEmpty()
        }

        @Test
        fun `une liste valide n_est jamais transformee en refus`() {
            // Garde-fou : le message d'erreur n'est examiné que lorsque la liste
            // est vide. Un portail qui renvoie des chaînes ET un champ d'erreur
            // résiduel doit continuer de fonctionner.
            val corps = """
                {"js":{"error":"expired","data":[
                  {"id":"101","number":"1","name":"Chaine Une","cmd":"ffmpeg http://example.invalid/a"}
                ]}}
            """.trimIndent()

            assertThat(parser.channels(corps).map { it.id }).containsExactly("101")
        }

        @Test
        fun `un lien absent pour cause d_abonnement est signale comme tel`() {
            val erreur = assertThrows(PortalProtocolException::class.java) {
                parser.streamLink(
                    """{"js":{"error":"no subscription for this device"}}""",
                    channelId = "101",
                    nowMs = 0L,
                )
            }

            assertThat(erreur.failure).isEqualTo(PortalFailure.EXPIRED)
        }

        @Test
        fun `un lien absent sans message reste une indisponibilite de flux`() {
            val erreur = assertThrows(PortalProtocolException::class.java) {
                parser.streamLink("""{"js":{"error":"unknown channel"}}""", channelId = "101", nowMs = 0L)
            }

            assertThat(erreur.failure).isEqualTo(PortalFailure.STREAM_UNAVAILABLE)
        }
    }

    @Nested
    @DisplayName("Enveloppe")
    inner class Enveloppe {

        @Test
        fun `extrait un objet utile`() {
            val charge = parser.payload("""{"js":{"a":1}}""")

            assertThat(charge.toString()).isEqualTo("""{"a":1}""")
        }

        @Test
        fun `extrait un tableau utile`() {
            val charge = parser.payload("""{"js":[{"a":1}]}""")

            assertThat(charge.toString()).isEqualTo("""[{"a":1}]""")
        }
    }

    @Nested
    @DisplayName("Robustesse")
    inner class Robustesse {

        @Test
        fun `accepte un champ supplementaire inconnu`() {
            // Les portails ajoutent des champs sans prévenir : une version
            // inconnue ne doit pas faire échouer tout le chargement.
            val corps = """
                {"js":{"token":"jeton-de-test"},"champ_inconnu":{"x":1}}
            """.trimIndent()

            assertThat(parser.token(corps)).isEqualTo("jeton-de-test")
        }

        @Test
        fun `le modele de chaine expose la qualite via le regroupement`() {
            // Vérifie que les chaînes analysées alimentent bien le regroupement
            // des variantes, socle du mode économie de données.
            val corps = """
                {"js":{"data":[
                  {"id":"1","number":1,"name":"Chaine HD","cmd":"a"},
                  {"id":"2","number":2,"name":"Chaine FHD","cmd":"b"}
                ]}}
            """.trimIndent()

            val qualites = parser.channels(corps).map { it.name }
            assertThat(qualites).containsExactly("Chaine HD", "Chaine FHD")
            assertThat(VideoQuality.HD.rank).isLessThan(VideoQuality.FHD.rank)
        }
    }
}
