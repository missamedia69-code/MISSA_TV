package com.missa.tv.data.remote.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Analyse du catalogue des chaînes testées (catalog.json).
 *
 * Les adresses sont fictives (hôtes en .invalid). Ce qui est vérifié : le
 * refus des schémas inconnus et du JSON illisible, l'écart des entrées sans
 * URL, la numérotation et la déduction des catégories.
 */
@DisplayName("Catalogue des chaînes testées")
class CatalogJsonParserTest {

    private val parser = CatalogJsonParser()

    @Test
    @DisplayName("lit les chaînes fonctionnelles avec groupe et pays")
    fun `catalogue valide`() {
        val document = """
            {
              "schemaVersion": 1,
              "updatedAt": "2026-10-09T00:00:00Z",
              "sources": [{"id": "principal", "tested": 3, "ok": 2}],
              "channels": [
                {"id": "a", "name": "CRTV", "url": "http://exemple.invalid/a.ts",
                 "group": "Afrique", "country": "CM", "logo": "http://exemple.invalid/a.png"},
                {"name": "Sans URL"},
                {"id": "b", "name": "TF1 HD", "url": "http://exemple.invalid/b.ts",
                 "group": "Generalistes", "country": "FR", "logo": ""}
              ]
            }
        """.trimIndent()

        val catalogue = parser.parse(document)!!

        // L'entrée sans URL est écartée.
        assertThat(catalogue.channels).hasSize(2)
        assertThat(catalogue.channels.map { it.name }).containsExactly("CRTV", "TF1 HD").inOrder()
        assertThat(catalogue.channels.map { it.number }).containsExactly(1, 2).inOrder()
        assertThat(catalogue.channels[0].country).isEqualTo("CM")
        assertThat(catalogue.channels[0].categoryId).isEqualTo("Afrique")
        assertThat(catalogue.channels[0].logoUrl).isEqualTo("http://exemple.invalid/a.png")
        // Logo vide ramené à null, pour ne pas lancer de téléchargement inutile.
        assertThat(catalogue.channels[1].logoUrl).isNull()
        assertThat(catalogue.categories.map { it.id }).containsExactly("Afrique", "Generalistes").inOrder()
    }

    @Test
    @DisplayName("refuse un schema inconnu")
    fun `schema inconnu`() {
        val document = """{"schemaVersion": 2, "channels": []}"""
        assertThat(parser.parse(document)).isNull()
    }

    @Test
    @DisplayName("refuse un json illisible")
    fun `json illisible`() {
        assertThat(parser.parse("pas du json")).isNull()
    }

    @Test
    @DisplayName("accepte un catalogue sans aucune chaîne")
    fun `catalogue vide`() {
        val catalogue = parser.parse("""{"schemaVersion": 1, "channels": []}""")!!
        assertThat(catalogue.channels).isEmpty()
    }
}
