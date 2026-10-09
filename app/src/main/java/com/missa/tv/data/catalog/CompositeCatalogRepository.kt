package com.missa.tv.data.catalog

import com.missa.tv.core.result.AppResult
import com.missa.tv.domain.model.Catalog
import com.missa.tv.domain.repository.CatalogRepository

/**
 * Catalogue à deux sources, par priorité :
 *
 *  1. le catalogue **testé** (`catalog.json`), qui ne contient que les chaînes
 *     fonctionnelles, classées par groupe et par pays ;
 *  2. à défaut (fichier absent, réseau coupé, document refusé), le catalogue
 *     construit depuis les playlists M3U, qui garde son rôle de repli.
 *
 * La clé de cache suit la dernière source ayant fourni un catalogue : c'est
 * elle qui indexe la liste mémorisée, les favoris et l'affichage immédiat.
 */
class CompositeCatalogRepository(
    private val tested: TestedCatalogRepository,
    private val m3u: M3uCatalogRepository,
) : CatalogRepository {

    /** Dernière source ayant fourni un catalogue, pour la clé de cache. */
    private var derniereSource: String? = null

    override suspend fun load(): AppResult<Catalog> {
        val teste = tested.load()
        if (teste is AppResult.Success) {
            derniereSource = tested.cacheKey()
            return teste
        }
        val repli = m3u.load()
        if (repli is AppResult.Success) {
            derniereSource = m3u.cacheKey()
        }
        return repli
    }

    override suspend fun cacheKey(): String = derniereSource ?: m3u.cacheKey()
}
