package com.missa.tv.data.local

import com.missa.tv.core.log.MissaLog
import com.missa.tv.core.time.TimeSource
import com.missa.tv.data.local.db.CatalogDao
import com.missa.tv.data.local.db.CategoryEntity
import com.missa.tv.data.local.db.ChannelEntity
import com.missa.tv.domain.model.Category
import com.missa.tv.domain.model.ChannelGroup
import com.missa.tv.domain.model.ChannelVariant
import com.missa.tv.domain.model.PortalCatalog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Catalogue local, en base Room.
 *
 * Deux usages, tous les deux au service du mode faible débit :
 *
 *  1. **Ouverture immédiate** : au lancement, la liste des chaînes est déjà là,
 *     sans attendre le portail. Le démarrage à froid reste sous les deux secondes
 *     même sur un boîtier Android TV modeste et une connexion lente.
 *  2. **Repli hors ligne** : si le portail ne répond pas, l'utilisateur garde sa
 *     liste et ses groupes SD/HD au lieu de voir un écran d'erreur.
 *
 * Le regroupement des variantes n'est pas recalculé à la lecture : il est
 * enregistré par colonnes (`groupKey`, `qualityRank`) et rendu par les requêtes
 * de [CatalogDao].
 */
@Singleton
class CatalogCache @Inject constructor(
    private val dao: CatalogDao,
    private val timeSource: TimeSource,
) {

    /** Enregistre le catalogue d'un portail, en remplacement du précédent. */
    suspend fun save(portalId: String, catalog: PortalCatalog) {
        val maintenant = timeSource.nowMs()
        val categories = catalog.categories.mapIndexed { index, categorie ->
            CategoryEntity.fromDomain(portalId, categorie, index)
        }
        val channels = catalog.channels.map { chaine ->
            ChannelEntity.fromDomain(portalId, chaine, maintenant)
        }

        dao.replaceCatalog(portalId, categories, channels)
        MissaLog.d(
            "Catalogue local enregistré : ${categories.size} catégories, " +
                "${channels.size} diffusions",
        )
    }

    /** Catégories mémorisées, dans l'ordre du portail. */
    suspend fun categories(portalId: String): List<Category> =
        dao.categories(portalId).map { it.toDomain() }

    /**
     * Groupes de chaînes reconstruits depuis la base.
     *
     * Les résumés (nom affiché, nombre de variantes, nombre de qualités
     * distinctes) viennent de la requête d'agrégation ; les variantes viennent de
     * la requête triée par qualité croissante. Aucun libellé n'est réanalysé ici.
     */
    suspend fun groups(portalId: String): List<ChannelGroup> {
        val resumes = dao.groups(portalId)
        if (resumes.isEmpty()) return emptyList()

        val variantesParGroupe = dao.channelsOrderedByGroup(portalId).groupBy { it.groupKey }

        return resumes
            .map { resume ->
                val variantes = variantesParGroupe[resume.groupKey].orEmpty().map { entite ->
                    ChannelVariant(channel = entite.toDomain(), quality = entite.quality)
                }
                resume.toDomain(variantes)
            }
            .filter { it.variants.isNotEmpty() }
    }

    /**
     * Diffusions d'un groupe, triées de la plus légère à la plus lourde.
     *
     * C'est la même sélection que celle faite en mémoire après un chargement
     * réussi : le choix de la diffusion en mode économie ne dépend donc pas de
     * l'origine des données.
     */
    suspend fun variants(portalId: String, groupKey: String): List<ChannelVariant> =
        dao.variants(portalId, groupKey).map { entite ->
            ChannelVariant(channel = entite.toDomain(), quality = entite.quality)
        }

    /** Nombre de diffusions mémorisées, pour dater ou vider le cache. */
    suspend fun channelCount(portalId: String): Int = dao.channelCount(portalId)

    /** Date du dernier enregistrement, ou `null` si le cache est vide. */
    suspend fun lastUpdatedMs(portalId: String): Long? = dao.lastUpdatedMs(portalId)

    /** Vide le cache d'un portail (profil supprimé, ou portail changé). */
    suspend fun clear(portalId: String) {
        dao.deleteChannels(portalId)
        dao.deleteCategories(portalId)
    }

    /** Vide tout le catalogue local, tous portails confondus. */
    suspend fun clearAll() {
        dao.clearAllChannels()
        dao.clearAllCategories()
    }
}
