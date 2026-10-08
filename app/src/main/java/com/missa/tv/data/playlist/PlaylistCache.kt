package com.missa.tv.data.playlist

/**
 * Mémoire des dernières entrées de playlists chargées avec succès.
 *
 * Sert de repli quand toutes les sources échouent : l'utilisateur retrouve alors
 * les chaînes de la dernière synchronisation réussie plutôt qu'une page vide.
 * L'implémentation fournie ici est en mémoire ; une implémentation persistante
 * prendra le relais avec la migration du catalogue local.
 */
interface PlaylistCache {

    /** Entrées mémorisées, vides si aucune synchronisation n'a réussi. */
    suspend fun load(): List<M3uEntry>

    /** Remplace les entrées mémorisées par [entries]. */
    suspend fun store(entries: List<M3uEntry>)
}

/** Cache en mémoire, utilisé en attendant la persistance locale du catalogue. */
class InMemoryPlaylistCache : PlaylistCache {

    private var entrees: List<M3uEntry> = emptyList()

    override suspend fun load(): List<M3uEntry> = entrees

    override suspend fun store(entries: List<M3uEntry>) {
        entrees = entries
    }
}
