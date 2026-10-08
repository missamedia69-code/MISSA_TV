package com.missa.tv.data.remote.config

import com.google.common.truth.Truth.assertThat
import com.missa.tv.domain.model.BufferSettings
import com.missa.tv.domain.model.QualityMode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Lecture du fichier de configuration distante (schéma v2).
 *
 * Les documents utilisés reproduisent ceux publiés dans le dépôt privé de
 * configuration. Aucune adresse réelle n'y figure : les hôtes sont en
 * `.invalid`.
 */
@DisplayName("Lecture de la configuration distante")
class PortalConfigParserTest {

    private val parser = PortalConfigParser()

    private fun document(
        schemaVersion: Int = 2,
        playlists: String = "[]",
        bandwidth: String = "",
    ): String =
        """
        {"schemaVersion":$schemaVersion,
        "updatedAt":"2026-10-06T00:00:00Z","minAppVersion":1,
        "defaultPlaylistId":null,"playlists":$playlists$bandwidth}
        """.trimIndent()

    @Nested
    @DisplayName("Document conforme")
    inner class Conforme {

        @Test
        fun `lit les champs principaux`() {
            val config = parser.parse(document())!!

            assertThat(config.schemaVersion).isEqualTo(2)
            assertThat(config.minAppVersion).isEqualTo(1)
            assertThat(config.playlists).isEmpty()
        }

        @Test
        fun `lit une playlist complete`() {
            val playlists =
                """[{"id":"principale","name":"Salon","url":"http://exemple.invalid/liste.m3u8",
                "epgUrl":"http://exemple.invalid/guide.xml","enabled":true}]"""

            val config = parser.parse(document(playlists = playlists))!!

            val playlist = config.playlists.single()
            assertThat(playlist.id).isEqualTo("principale")
            assertThat(playlist.name).isEqualTo("Salon")
            assertThat(playlist.url).isEqualTo("http://exemple.invalid/liste.m3u8")
            assertThat(playlist.epgUrl).isEqualTo("http://exemple.invalid/guide.xml")
            assertThat(playlist.enabled).isTrue()
        }

        @Test
        fun `une playlist sans guide a un epgUrl nul`() {
            val playlists = """[{"id":"p","name":"P","url":"http://exemple.invalid/l.m3u8"}]"""

            val playlist = parser.parse(document(playlists = playlists))!!.playlists.single()

            assertThat(playlist.epgUrl).isNull()
        }

        @Test
        fun `applique les réglages de débit`() {
            val bandwidth =
                ""","bandwidth":{"lowBandwidthThresholdKbps":1500,
                "defaultMode":"ECONOMY_480",
                "maxVideoHeightByMode":{"AUTO_ECONOMY":576},
                "maxVideoBitrateByMode":{"AUTO_ECONOMY":600000},
                "buffer":{"minMs":20000,"maxMs":45000,"playbackMs":2500,"afterRebufferMs":5000}}"""

            val config = parser.parse(document(bandwidth = bandwidth))!!

            assertThat(config.bandwidth.lowBandwidthThresholdKbps).isEqualTo(1500)
            assertThat(config.bandwidth.defaultMode).isEqualTo(QualityMode.ECONOMY_480)
            assertThat(config.bandwidth.maxHeightFor(QualityMode.AUTO_ECONOMY)).isEqualTo(576)
            assertThat(config.bandwidth.maxBitrateFor(QualityMode.AUTO_ECONOMY)).isEqualTo(600_000)
            assertThat(config.bandwidth.buffer.minMs).isEqualTo(20_000)
            assertThat(config.bandwidth.buffer.maxMs).isEqualTo(45_000)
        }

        @Test
        @DisplayName("les modes non surchargés gardent leur valeur par défaut")
        fun `modes non surcharges`() {
            val bandwidth = ""","bandwidth":{"maxVideoHeightByMode":{"AUTO_ECONOMY":576}}"""

            val config = parser.parse(document(bandwidth = bandwidth))!!

            // Le mode économie extrême n'est pas mentionné : sa valeur du code
            // reste appliquée.
            assertThat(config.bandwidth.maxHeightFor(QualityMode.ULTRA_ECONOMY))
                .isEqualTo(QualityMode.ULTRA_ECONOMY.defaultMaxHeight)
        }

        @Test
        @DisplayName("zéro signifie « pas de limite » et reste accepté")
        fun `zero accepte`() {
            val bandwidth = ""","bandwidth":{"maxVideoHeightByMode":{"MAX_QUALITY":0}}"""

            val config = parser.parse(document(bandwidth = bandwidth))!!

            assertThat(config.bandwidth.maxHeightFor(QualityMode.MAX_QUALITY)).isEqualTo(0)
        }
    }

    @Nested
    @DisplayName("Documents refusés en bloc")
    inner class Refus {

        @Test
        @DisplayName("l'ancien schéma v1 est refusé")
        fun `schema v1 refuse`() {
            // Le schéma a changé : appliquer l'ancien format à l'aveugle
            // produirait des réglages absurdes.
            assertThat(parser.parse(document(schemaVersion = 1))).isNull()
        }

        @Test
        @DisplayName("version de schéma inconnue")
        fun `schema inconnu`() {
            assertThat(parser.parse(document(schemaVersion = 3))).isNull()
        }

        @Test
        fun `JSON illisible`() {
            assertThat(parser.parse("{ ceci n'est pas du JSON")).isNull()
        }

        @Test
        fun `document sans version de schéma`() {
            assertThat(parser.parse("""{"playlists":[]}""")).isNull()
        }
    }

    @Nested
    @DisplayName("Entrées invalides")
    inner class EntreesInvalides {

        @Test
        @DisplayName("les playlists incomplètes sont écartées une par une")
        fun `playlists invalides ecartees`() {
            val playlists =
                """[
                {"id":"bonne","name":"Valide","url":"http://exemple.invalid/liste.m3u8"},
                {"id":"id invalide !","name":"Identifiant","url":"http://exemple.invalid/x.m3u8"},
                {"id":"sans-url","name":"Sans URL"},
                {"id":"url-ftp","name":"URL non http","url":"ftp://exemple.invalid/x.m3u8"}
            ]"""

            val config = parser.parse(document(playlists = playlists))!!

            // Une source inexploitable ne fait pas perdre les autres.
            assertThat(config.playlists.map { it.id }).containsExactly("bonne")
        }

        @Test
        @DisplayName("au plus seize playlists sont retenues")
        fun `playlists limitees`() {
            val playlists = (1..20).joinToString(",", prefix = "[", postfix = "]") { index ->
                """{"id":"p$index","name":"Playlist $index","url":"http://exemple.invalid/$index.m3u8"}"""
            }

            val config = parser.parse(document(playlists = playlists))!!

            assertThat(config.playlists).hasSize(16)
        }

        @Test
        @DisplayName("les modes inconnus et les valeurs aberrantes sont ignorés")
        fun `modes et valeurs aberrants`() {
            val bandwidth =
                ""","bandwidth":{
                "defaultMode":"MODE_INVENTE",
                "lowBandwidthThresholdKbps":1,
                "maxVideoHeightByMode":{"AUTO_ECONOMY":99,"MODE_INVENTE":480},
                "maxVideoBitrateByMode":{"AUTO_ECONOMY":10}}"""

            val config = parser.parse(document(bandwidth = bandwidth))!!

            assertThat(config.bandwidth.defaultMode).isEqualTo(QualityMode.DEFAULT)
            assertThat(config.bandwidth.lowBandwidthThresholdKbps)
                .isEqualTo(com.missa.tv.domain.model.BandwidthSettings.DEFAULT_LOW_THRESHOLD_KBPS)
            assertThat(config.bandwidth.maxHeightFor(QualityMode.AUTO_ECONOMY))
                .isEqualTo(QualityMode.AUTO_ECONOMY.defaultMaxHeight)
            assertThat(config.bandwidth.maxBitrateFor(QualityMode.AUTO_ECONOMY))
                .isEqualTo(QualityMode.AUTO_ECONOMY.defaultMaxBitrate)
        }

        @Test
        @DisplayName("un tampon incohérent est remplacé par les valeurs par défaut")
        fun `tampon incoherent`() {
            val bandwidth =
                ""","bandwidth":{"buffer":{"minMs":40000,"maxMs":5000,
                "playbackMs":3000,"afterRebufferMs":6000}}"""

            val config = parser.parse(document(bandwidth = bandwidth))!!

            // Un maximum inférieur au minimum ferait saccader la lecture : on
            // préfère ignorer le bloc entier.
            assertThat(config.bandwidth.buffer).isEqualTo(BufferSettings())
        }

        @Test
        @DisplayName("un tampon incomplet est remplacé par les valeurs par défaut")
        fun `tampon incomplet`() {
            val bandwidth = ""","bandwidth":{"buffer":{"minMs":20000}}"""

            val config = parser.parse(document(bandwidth = bandwidth))!!

            assertThat(config.bandwidth.buffer).isEqualTo(BufferSettings())
        }

        @Test
        @DisplayName("un bloc bandwidth absent n'empêche rien")
        fun `bloc absent`() {
            val config = parser.parse(document())!!

            assertThat(config.bandwidth.lowBandwidthThresholdKbps)
                .isEqualTo(com.missa.tv.domain.model.BandwidthSettings.DEFAULT_LOW_THRESHOLD_KBPS)
            assertThat(config.bandwidth.defaultMode).isEqualTo(QualityMode.DEFAULT)
        }

        @Test
        @DisplayName("les champs inconnus sont tolérés")
        fun `champs inconnus toleres`() {
            // Les versions futures ajouteront des champs : refuser tout le
            // document à cause d'un champ inconnu serait une régression.
            val document = """{"schemaVersion":2,"playlists":[],"nouveauReglage":{"x":1}}"""

            assertThat(parser.parse(document)).isNotNull()
        }
    }
}
