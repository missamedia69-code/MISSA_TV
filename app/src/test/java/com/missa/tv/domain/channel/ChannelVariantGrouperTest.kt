package com.missa.tv.domain.channel

import com.google.common.truth.Truth.assertThat
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.QualityMode
import com.missa.tv.domain.model.VideoQuality
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Regroupement des diffusions d'une même chaîne.
 *
 * C'est la brique qui permet le mode économie sur les portails Stalker, où un
 * flux n'a pas de variantes : on choisit alors la diffusion la plus légère.
 */
class ChannelVariantGrouperTest {

    private fun chaine(
        numero: Int,
        nom: String,
        categorie: String? = "1",
    ) = Channel(
        id = numero.toString(),
        number = numero,
        name = nom,
        cmd = "ffmpeg http://exemple.invalid/live/$numero",
        categoryId = categorie,
    )

    @Nested
    @DisplayName("Reconnaissance de la qualité")
    inner class Qualite {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(
            "TF1 HD, HD",
            "TF1, UNKNOWN",
            "TF1 SD, SD",
            "TF1 FHD, FHD",
            "TF1 4K, UHD",
            "TF1 UHD, UHD",
            "TF1 1080p, FHD",
            "TF1 720p, HD",
            "TF1 LQ, LQ",
            "CANAL+ SPORT HD, HD",
        )
        fun `deduit la qualite du libelle`(nom: String, attendu: VideoQuality) {
            assertThat(ChannelVariantGrouper.qualityOf(nom)).isEqualTo(attendu)
        }

        @Test
        fun `le marqueur le plus eleve gagne`() {
            // Certains libellés cumulent les mentions : « HD 1080p » reste du FHD.
            assertThat(ChannelVariantGrouper.qualityOf("TF1 HD 1080p"))
                .isEqualTo(VideoQuality.FHD)
        }
    }

    @Nested
    @DisplayName("Nom de base")
    inner class NomDeBase {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource(
            "TF1 HD, TF1",
            "TF1 FHD, TF1",
            "TF1 4K, TF1",
            "TF1, TF1",
            "France 2 HD, FRANCE 2",
            "CANAL+ SPORT 4K, CANAL+ SPORT",
            "RTS 1 SD, RTS 1",
            "beIN SPORTS 1 HD, BEIN SPORTS 1",
        )
        fun `retire les marqueurs de qualite`(nom: String, attendu: String) {
            assertThat(ChannelVariantGrouper.baseName(nom)).isEqualTo(attendu)
        }

        @Test
        fun `supprime les accents pour regrouper les ecritures voisines`() {
            assertThat(ChannelVariantGrouper.baseName("Télé Congo HD"))
                .isEqualTo(ChannelVariantGrouper.baseName("TELE CONGO"))
        }

        @Test
        fun `ne touche pas un numero present dans le nom`() {
            // Le « 1 » de beIN SPORTS 1 fait partie du nom, pas de la qualité.
            assertThat(ChannelVariantGrouper.baseName("beIN SPORTS 1"))
                .isEqualTo("BEIN SPORTS 1")
        }
    }

    @Nested
    @DisplayName("Regroupement")
    inner class Regroupement {

        @Test
        fun `regroupe les diffusions d'une meme chaine`() {
            val chaines = listOf(
                chaine(1, "TF1"),
                chaine(2, "TF1 HD"),
                chaine(3, "TF1 FHD"),
            )

            val groupes = ChannelVariantGrouper.group(chaines)

            assertThat(groupes).hasSize(1)
            assertThat(groupes.first().variants).hasSize(3)
        }

        @Test
        fun `trie les variantes par qualite croissante`() {
            val chaines = listOf(
                chaine(3, "TF1 FHD"),
                chaine(1, "TF1"),
                chaine(2, "TF1 SD"),
            )

            val groupe = ChannelVariantGrouper.group(chaines).first()

            assertThat(groupe.lowest.quality).isEqualTo(VideoQuality.SD)
            assertThat(groupe.highest.quality).isEqualTo(VideoQuality.FHD)
        }

        @Test
        fun `ne melange pas deux categories differentes`() {
            val chaines = listOf(
                chaine(1, "TF1 HD", categorie = "1"),
                chaine(2, "TF1 HD", categorie = "2"),
            )

            val groupes = ChannelVariantGrouper.group(chaines)

            assertThat(groupes).hasSize(2)
            assertThat(groupes.all { it.hasSingleVariant }).isTrue()
        }

        @Test
        fun `une chaine sans variante forme un groupe d'une seule diffusion`() {
            val groupes = ChannelVariantGrouper.group(listOf(chaine(1, "France 3")))

            assertThat(groupes).hasSize(1)
            assertThat(groupes.first().hasSingleVariant).isTrue()
            assertThat(groupes.first().lowest.quality).isEqualTo(VideoQuality.UNKNOWN)
        }

        @Test
        fun `le nom affiche est celui de la meilleure qualite`() {
            val chaines = listOf(
                chaine(1, "TF1"),
                chaine(2, "TF1 HD"),
            )

            val groupe = ChannelVariantGrouper.group(chaines).first()

            assertThat(groupe.displayName).isEqualTo("TF1 HD")
        }

        @Test
        fun `les groupes sont ordonnes par numero de chaine`() {
            val chaines = listOf(
                chaine(50, "France 5 HD"),
                chaine(1, "TF1 HD"),
                chaine(20, "France 2 HD"),
            )

            val numeros = ChannelVariantGrouper.group(chaines)
                .map { it.variants.first().channel.number }

            assertThat(numeros).containsExactly(1, 20, 50).inOrder()
        }

        @Test
        fun `une liste vide ne produit aucun groupe`() {
            assertThat(ChannelVariantGrouper.group(emptyList())).isEmpty()
        }
    }

    @Nested
    @DisplayName("Choix de la diffusion selon le mode")
    inner class ChoixSelonMode {

        private val groupe = ChannelVariantGrouper.group(
            listOf(
                chaine(1, "TF1 SD"),
                chaine(2, "TF1 HD"),
                chaine(3, "TF1 FHD"),
                chaine(4, "TF1 4K"),
            ),
        ).first()

        @Test
        fun `en economie 480p choisit la diffusion 480 lignes`() {
            val choisie = groupe.bestFor(QualityMode.ECONOMY_480)
            assertThat(choisie.quality).isEqualTo(VideoQuality.SD)
        }

        @Test
        fun `en auto economie reste sous 720 lignes`() {
            val choisie = groupe.bestFor(QualityMode.AUTO_ECONOMY)
            assertThat(choisie.quality).isEqualTo(VideoQuality.HD)
        }

        @Test
        fun `en ultra economie choisit la diffusion la plus legere`() {
            val choisie = groupe.bestFor(QualityMode.ULTRA_ECONOMY)
            assertThat(choisie.quality).isEqualTo(VideoQuality.SD)
        }

        @Test
        fun `en audio seul choisit la diffusion la plus legere`() {
            val choisie = groupe.bestFor(QualityMode.AUDIO_ONLY)
            assertThat(choisie).isEqualTo(groupe.lowest)
        }

        @Test
        fun `en qualite maximale choisit la meilleure diffusion`() {
            val choisie = groupe.bestFor(QualityMode.MAX_QUALITY)
            assertThat(choisie.quality).isEqualTo(VideoQuality.UHD)
        }

        @Test
        fun `ne depasse jamais le plafond demande`() {
            val groupeSdUniquement = ChannelVariantGrouper.group(listOf(chaine(1, "TF1"))).first()

            // Aucune variante sous 480 lignes : on retombe sur la plus légère.
            assertThat(groupeSdUniquement.bestFor(QualityMode.ECONOMY_480))
                .isEqualTo(groupeSdUniquement.lowest)
        }
    }
}
