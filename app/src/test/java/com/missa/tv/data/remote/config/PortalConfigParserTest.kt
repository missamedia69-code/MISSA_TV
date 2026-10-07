package com.missa.tv.data.remote.config

import com.google.common.truth.Truth.assertThat
import com.missa.tv.domain.model.BufferSettings
import com.missa.tv.domain.model.QualityMode
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Lecture du fichier de configuration distante.
 *
 * Les documents utilisés reproduisent ceux publiés dans le dépôt. Aucune adresse
 * réelle n'y figure : les hôtes sont en `.invalid` et les MAC sont assemblées à
 * l'exécution.
 */
@DisplayName("Lecture de la configuration distante")
class PortalConfigParserTest {

    private val parser = PortalConfigParser()

    private val macValide = listOf("00", "1A", "79", "00", "00", "01").joinToString(":")

    private fun document(
        schemaVersion: Int = 1,
        configVersion: Int = 2,
        profils: String = "[]",
        bandwidth: String = "",
    ): String = """
        {"schemaVersion":$schemaVersion,"configVersion":$configVersion,
        "updatedAt":"2026-10-06T00:00:00Z","minAppVersion":1,
        "defaultProfileId":null,"profiles":$profils$bandwidth}
    """.trimIndent()

    @Nested
    @DisplayName("Document conforme")
    inner class Conforme {

        @Test
        fun `lit les champs principaux`() {
            val config = parser.parse(document())!!

            assertThat(config.schemaVersion).isEqualTo(1)
            assertThat(config.configVersion).isEqualTo(2)
            assertThat(config.minAppVersion).isEqualTo(1)
            assertThat(config.profiles).isEmpty()
        }

        @Test
        fun `lit un profil complet`() {
            val profils = """[{"id":"principal","name":"Salon","portalUrl":"http://example.invalid/c/",
                "mac":"$macValide","enabled":true}]"""

            val config = parser.parse(document(profils = profils))!!

            val profil = config.profiles.single()
            assertThat(profil.id).isEqualTo("principal")
            assertThat(profil.name).isEqualTo("Salon")
            assertThat(profil.enabled).isTrue()
        }

        @Test
        fun `applique les réglages de débit`() {
            val bandwidth = ""","bandwidth":{"lowBandwidthThresholdKbps":1500,
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
        @DisplayName("version de schéma inconnue")
        fun `schema inconnu`() {
            // Une version inconnue annonce un changement de format : l'appliquer
            // à l'aveugle produirait des réglages absurdes.
            assertThat(parser.parse(document(schemaVersion = 2))).isNull()
        }

        @Test
        fun `JSON illisible`() {
            assertThat(parser.parse("{ ceci n'est pas du JSON")).isNull()
        }

        @Test
        fun `document sans version de schéma`() {
            assertThat(parser.parse("""{"configVersion":3,"profiles":[]}""")).isNull()
        }
    }

    @Nested
    @DisplayName("Entrées invalides")
    inner class EntreesInvalides {

        @Test
        @DisplayName("les profils incomplets sont écartés un par un")
        fun `profils invalides ecartes`() {
            val macInvalide = "00:1A:79:00:00"
            val profils = """[
                {"id":"bon","name":"Valide","portalUrl":"http://example.invalid/c/","mac":"$macValide"},
                {"id":"mauvaise-mac","name":"MAC trop courte","portalUrl":"http://example.invalid/","mac":"$macInvalide"},
                {"id":"id invalide !","name":"Identifiant","portalUrl":"http://example.invalid/","mac":"$macValide"},
                {"id":"sans-url","name":"Sans URL","mac":"$macValide"}
            ]"""

            val config = parser.parse(document(profils = profils))!!

            // Un profil inexploitable ne fait pas perdre les autres.
            assertThat(config.profiles.map { it.id }).containsExactly("bon")
        }

        @Test
        @DisplayName("au plus seize profils sont retenus")
        fun `profils limites`() {
            val profils = (1..20).joinToString(",", prefix = "[", postfix = "]") { index ->
                """{"id":"p$index","name":"Profil $index","portalUrl":"http://example.invalid/","mac":"$macValide"}"""
            }

            val config = parser.parse(document(profils = profils))!!

            assertThat(config.profiles).hasSize(16)
        }

        @Test
        @DisplayName("les modes inconnus et les valeurs aberrantes sont ignorés")
        fun `modes et valeurs aberrants`() {
            val bandwidth = ""","bandwidth":{
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
            val bandwidth = ""","bandwidth":{"buffer":{"minMs":40000,"maxMs":5000,
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
            val document = """{"schemaVersion":1,"configVersion":5,"profiles":[],
                "nouveauReglage":{"x":1}}"""

            assertThat(parser.parse(document)?.configVersion).isEqualTo(5)
        }
    }
}
