package com.missa.tv.domain.repository

import com.missa.tv.core.result.AppResult
import com.missa.tv.domain.model.PortalCatalog

/**
 * Chargement du catalogue de chaînes.
 *
 * Le catalogue regroupe les catégories et les chaînes à afficher sur l'accueil.
 * Il est construit depuis les sources de playlists M3U déclarées par la
 * configuration distante ; en cas d'échec réseau, la dernière version mémorisée
 * localement est reprise afin de ne pas priver l'utilisateur de sa liste.
 */
interface CatalogRepository {

    /**
     * Catalogue chargé depuis les sources de playlists, ou repris du cache local
     * si toutes les sources sont inaccessibles.
     */
    suspend fun load(): AppResult<PortalCatalog>

    /**
     * Clé du catalogue local.
     *
     * Identifie la source privilégiée : c'est sous cette clé que le catalogue est
     * mémorisé et relu. L'accueil l'utilise pour afficher la liste en cache avant
     * même la fin du chargement réseau.
     */
    suspend fun cacheKey(): String
}
