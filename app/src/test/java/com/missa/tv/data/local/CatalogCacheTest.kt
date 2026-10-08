package com.missa.tv.data.local

import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.local.db.CatalogDao
import com.missa.tv.data.local.db.CategoryEntity
import com.missa.tv.data.local.db.ChannelEntity
import com.missa.tv.data.local.db.ChannelGroupSummary
import com.missa.tv.domain.model.Catalog
import com.missa.tv.domain.model.Category
import com.missa.tv.domain.model.Channel
import com.missa.tv.domain.model.VideoQuality
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Vérifie le cache local du catalogue.
 *
 * La doublure de [CatalogDao] reproduit la sémantique des requêtes SQL : les
 * diffusions sont regroupées par clé de groupe, les qualités distinctes sont
 * comptées, et les variantes ressortent triées de la plus légère à la plus
 * lourde. Ce qui est testé ici, c'est l'assemblage fait par [CatalogCache] à
 * partir de ces résultats — le SQL lui-même étant vérifié par Room dès la
 * compilation.
 */
class CatalogCacheTest {

    private val horloge = object : TimeSource {
        override fun nowMs(): Long = 1_700_000_000_000
    }

    private fun cache(): Pair<CatalogCache, FakeCatalogDao> {
        val dao = FakeCatalogDao()
        return CatalogCache(dao = dao, timeSource = horloge) to dao
    }

    private fun chaine(
        id: String,
        numero: Int,
        nom: String,
        categorie: String? = "10",
    ) = Channel(
        id = id,
        number = numero,
        name = nom,
        streamUrl = "ffmpeg http://example.invalid/live/$id",
        categoryId = categorie,
    )

    @Test
    fun `enregistre puis relit les categories dans l'ordre du portail`() = runTest {
        val (catalogue, _) = cache()

        catalogue.save(
            PORTAL,
            Catalog(
                categories = listOf(Category("10", "Généralistes"), Category("20", "Sport")),
                channels = listOf(chaine("1", 1, "Chaîne une")),
                loadedAtMs = 0,
            ),
        )

        assertThat(catalogue.categories(PORTAL))
            .containsExactly(Category("10", "Généralistes"), Category("20", "Sport"))
            .inOrder()
        assertThat(catalogue.categories("autre-portail")).isEmpty()
    }

    @Test
    fun `regroupe les variantes SD et HD d'une meme chaine`() = runTest {
        val (catalogue, _) = cache()

        catalogue.save(
            PORTAL,
            Catalog(
                categories = listOf(Category("10", "Généralistes")),
                channels = listOf(
                    chaine("1", 1, "TF1"),
                    chaine("2", 2, "TF1 HD"),
                    chaine("3", 3, "TF1 FHD"),
                ),
                loadedAtMs = 0,
            ),
        )

        val groupes = catalogue.groups(PORTAL)

        assertThat(groupes).hasSize(1)
        val groupe = groupes.first()
        assertThat(groupe.variants.map { it.quality })
            .containsExactly(VideoQuality.UNKNOWN, VideoQuality.HD, VideoQuality.FHD)
            .inOrder()
        assertThat(groupe.distinctQualityCount).isEqualTo(3)
        assertThat(groupe.hasSingleVariant).isFalse()
        // Le mode économie part de la diffusion la plus légère.
        assertThat(groupe.lowest.channel.name).isEqualTo("TF1")
        assertThat(groupe.highest.channel.name).isEqualTo("TF1 FHD")
    }

    @Test
    fun `une chaine monoqualite est signalee comme telle`() = runTest {
        val (catalogue, _) = cache()

        catalogue.save(
            PORTAL,
            Catalog(
                categories = listOf(Category("10", "Généralistes")),
                channels = listOf(chaine("1", 1, "Chaîne unique HD")),
                loadedAtMs = 0,
            ),
        )

        val groupe = catalogue.groups(PORTAL).single()

        assertThat(groupe.hasSingleVariant).isTrue()
        assertThat(groupe.distinctQualityCount).isEqualTo(1)
        assertThat(groupe.variants.single().quality).isEqualTo(VideoQuality.HD)
    }

    @Test
    fun `deux chaines de meme nom dans deux categories ne sont pas fusionnees`() = runTest {
        val (catalogue, _) = cache()

        catalogue.save(
            PORTAL,
            Catalog(
                categories = listOf(Category("10", "Généralistes"), Category("20", "Sport")),
                channels = listOf(
                    chaine("1", 1, "Direct", categorie = "10"),
                    chaine("2", 2, "Direct HD", categorie = "20"),
                ),
                loadedAtMs = 0,
            ),
        )

        assertThat(catalogue.groups(PORTAL)).hasSize(2)
    }

    @Test
    fun `les variantes d'un groupe ressortent de la plus legere a la plus lourde`() = runTest {
        val (catalogue, _) = cache()

        catalogue.save(
            PORTAL,
            Catalog(
                categories = emptyList(),
                channels = listOf(
                    chaine("1", 1, "Match FHD"),
                    chaine("2", 2, "Match SD"),
                    chaine("3", 3, "Match"),
                ),
                loadedAtMs = 0,
            ),
        )

        val cle = ChannelEntity.fromDomain(PORTAL, chaine("2", 2, "Match SD"), 0).groupKey
        val variantes = catalogue.variants(PORTAL, cle)

        assertThat(variantes.map { it.quality })
            .containsExactly(VideoQuality.SD, VideoQuality.UNKNOWN, VideoQuality.FHD)
            .inOrder()
    }

    @Test
    fun `un nouvel enregistrement remplace le catalogue precedent`() = runTest {
        val (catalogue, _) = cache()

        catalogue.save(
            PORTAL,
            Catalog(
                categories = listOf(Category("10", "Généralistes")),
                channels = listOf(chaine("1", 1, "Ancienne")),
                loadedAtMs = 0,
            ),
        )
        catalogue.save(
            PORTAL,
            Catalog(
                categories = listOf(Category("20", "Sport")),
                channels = listOf(chaine("9", 9, "Nouvelle", categorie = "20")),
                loadedAtMs = 0,
            ),
        )

        assertThat(catalogue.groups(PORTAL).single().displayName).isEqualTo("Nouvelle")
        assertThat(catalogue.channelCount(PORTAL)).isEqualTo(1)
        assertThat(catalogue.categories(PORTAL)).containsExactly(Category("20", "Sport"))
    }

    @Test
    fun `la date du dernier enregistrement est exposee et le vidage efface tout`() = runTest {
        val (catalogue, _) = cache()

        assertThat(catalogue.lastUpdatedMs(PORTAL)).isNull()
        assertThat(catalogue.groups(PORTAL)).isEmpty()

        catalogue.save(
            PORTAL,
            Catalog(
                categories = listOf(Category("10", "Généralistes")),
                channels = listOf(chaine("1", 1, "Chaîne une")),
                loadedAtMs = 0,
            ),
        )

        assertThat(catalogue.lastUpdatedMs(PORTAL)).isEqualTo(1_700_000_000_000)

        catalogue.clear(PORTAL)
        assertThat(catalogue.channelCount(PORTAL)).isEqualTo(0)
        assertThat(catalogue.groups(PORTAL)).isEmpty()
    }

    /** Nettoyage complet : utilisé par l'écran de réglages (réinitialisation). */
    @Test
    fun `le vidage global efface les catalogues de tous les portails`() = runTest {
        val (catalogue, _) = cache()

        catalogue.save(
            PORTAL,
            Catalog(emptyList(), listOf(chaine("1", 1, "Une")), loadedAtMs = 0),
        )
        catalogue.save(
            "autre-portail",
            Catalog(emptyList(), listOf(chaine("2", 2, "Deux")), loadedAtMs = 0),
        )

        catalogue.clearAll()

        assertThat(catalogue.channelCount(PORTAL)).isEqualTo(0)
        assertThat(catalogue.channelCount("autre-portail")).isEqualTo(0)
    }

    private companion object {
        const val PORTAL = "profil-test"
    }
}

/**
 * Doublure en mémoire de [CatalogDao].
 *
 * Elle reproduit les requêtes utilisées par [CatalogCache] : regroupement par clé,
 * tri par qualité croissante, comptage des qualités distinctes.
 */
private class FakeCatalogDao : CatalogDao {

    private val categories = mutableListOf<CategoryEntity>()
    private val channels = mutableListOf<ChannelEntity>()

    override suspend fun upsertCategories(categories: List<CategoryEntity>) {
        categories.forEach { entite ->
            this.categories.removeAll { it.portalId == entite.portalId && it.id == entite.id }
            this.categories += entite
        }
    }

    override suspend fun upsertChannels(channels: List<ChannelEntity>) {
        channels.forEach { entite ->
            this.channels.removeAll { it.portalId == entite.portalId && it.id == entite.id }
            this.channels += entite
        }
    }

    override suspend fun deleteCategories(portalId: String) {
        categories.removeAll { it.portalId == portalId }
    }

    override suspend fun deleteChannels(portalId: String) {
        channels.removeAll { it.portalId == portalId }
    }

    override suspend fun categories(portalId: String): List<CategoryEntity> =
        categories.filter { it.portalId == portalId }.sortedWith(
            compareBy({ it.sortIndex }, { it.title }),
        )

    override suspend fun groups(portalId: String): List<ChannelGroupSummary> =
        channels.filter { it.portalId == portalId }
            .groupBy { it.groupKey }
            .map { (cle, membres) ->
                val meilleur = membres.maxWithOrNull(
                    compareBy<ChannelEntity> { it.qualityRank }.thenByDescending { it.name.length },
                )
                val triees = membres.sortedWith(
                    compareBy<ChannelEntity> { it.qualityRank }.thenBy { it.number },
                )
                ChannelGroupSummary(
                    groupKey = cle,
                    displayName = meilleur?.name.orEmpty(),
                    firstNumber = membres.minOf { it.number },
                    variantCount = membres.size,
                    distinctQualityCount = membres.map { it.qualityRank }.distinct().size,
                    lowestRank = triees.first().qualityRank,
                    highestRank = triees.last().qualityRank,
                    logoUrl = membres.firstNotNullOfOrNull { it.logoUrl },
                )
            }
            .sortedBy { it.firstNumber }

    override suspend fun channelsOrderedByGroup(portalId: String): List<ChannelEntity> =
        channels.filter { it.portalId == portalId }.sortedWith(
            compareBy<ChannelEntity> { it.groupKey }
                .thenBy { it.qualityRank }
                .thenBy { it.number },
        )

    override suspend fun variants(portalId: String, groupKey: String): List<ChannelEntity> =
        channels.filter { it.portalId == portalId && it.groupKey == groupKey }.sortedWith(
            compareBy<ChannelEntity> { it.qualityRank }.thenBy { it.number },
        )

    override suspend fun channelCount(portalId: String): Int =
        channels.count { it.portalId == portalId }

    override suspend fun lastUpdatedMs(portalId: String): Long? =
        channels.filter { it.portalId == portalId }.maxOfOrNull { it.updatedAtMs }

    override suspend fun clearAllChannels() {
        channels.clear()
    }

    override suspend fun clearAllCategories() {
        categories.clear()
    }
}
