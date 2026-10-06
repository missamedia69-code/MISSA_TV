package com.missa.tv.domain.model

/**
 * Contenu d'un portail, chargé en une fois.
 *
 * Les catégories et les chaînes sont récupérées ensemble car l'écran d'accueil a
 * besoin des deux : la liste des catégories alimente le filtre, et chaque chaîne
 * référence sa catégorie par identifiant.
 */
data class PortalCatalog(
    val categories: List<Category>,
    val channels: List<Channel>,
    val loadedAtMs: Long,
) {
    /** Chaînes d'une catégorie donnée, dans l'ordre de numérotation du portail. */
    fun channelsOf(categoryId: String): List<Channel> =
        channels.filter { it.categoryId == categoryId }.sortedBy { it.number }

    /** Nombre de chaînes par catégorie, pour l'affichage du compteur. */
    fun countByCategory(): Map<String, Int> =
        channels.groupingBy { it.categoryId.orEmpty() }.eachCount()
}
