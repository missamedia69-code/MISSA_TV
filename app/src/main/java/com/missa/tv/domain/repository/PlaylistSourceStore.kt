package com.missa.tv.domain.repository

import com.missa.tv.domain.model.PlaylistSource

/**
 * Magasin des sources de playlists.
 *
 * Les sources proviennent de la configuration distante (dépôt privé de
 * configuration) et sont conservées sur l'appareil : leur URL est un
 * identifiant sensible, aussi l'implémentation ne les écrit-elle que chiffrées.
 */
interface PlaylistSourceStore {

    /** Toutes les sources connues, y compris désactivées. */
    suspend fun sources(): List<PlaylistSource>

    /**
     * Remplace l'ensemble des sources par [sources].
     *
     * La configuration distante fait autorité : un rafraîchissement remplace la
     * liste précédente, il ne s'y ajoute pas.
     */
    suspend fun saveAll(sources: List<PlaylistSource>)
}
