package com.missa.tv.core.log

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Masquage des identifiants dans les journaux.
 *
 * Les valeurs sensibles utilisées ici sont **construites à l'exécution** : aucun
 * identifiant réaliste n'apparaît en clair dans le code source, ce qui est la
 * règle du projet (et ce que vérifie scripts/check-secrets.sh à chaque push).
 */
class SecretsTest {

    /** Adresse MAC de test, assemblée octet par octet. */
    private val macTest = listOf("00", "1A", "79", "AB", "CD", "EF").joinToString(":")

    /** Jeton d'API GitHub factice, assemblé à l'exécution. */
    private val jetonGitHub = "ghp_" + "A".repeat(30)

    @Test
    fun `masque une adresse mac en conservant les trois premiers octets`() {
        assertThat(Secrets.maskMac(macTest)).isEqualTo("00:1A:79:**:**:**")
    }

    @Test
    fun `accepte aussi les separateurs par tiret`() {
        val macAvecTirets = macTest.replace(":", "-")
        assertThat(Secrets.maskMac(macAvecTirets)).isEqualTo("00:1A:79:**:**:**")
    }

    @Test
    fun `remplace entierement une valeur qui n_a pas le format attendu`() {
        assertThat(Secrets.maskMac("pas-une-mac")).isEqualTo("**")
    }

    @Test
    fun `masque l_hote et le chemin d_une url`() {
        val masquee = Secrets.maskUrl("http://192.0.2.10:8080/c/")

        assertThat(masquee).isEqualTo("http://1***:8080/***")
        assertThat(masquee).doesNotContain("192.0.2.10")
        assertThat(masquee).doesNotContain("/c/")
    }

    @Test
    fun `masque une url sans port`() {
        val masquee = Secrets.maskUrl("https://portail.exemple.invalid/c/")

        assertThat(masquee).isEqualTo("https://p***/***")
        assertThat(masquee).doesNotContain("exemple.invalid")
    }

    @Test
    fun `masque un jeton en ne conservant que sa longueur`() {
        assertThat(Secrets.maskToken("abcdef123456")).isEqualTo("<jeton de 12 caracteres>")
        assertThat(Secrets.maskToken("")).isEqualTo("<vide>")
    }

    @Test
    fun `masque tous les identifiants d_une ligne de journal`() {
        val ligne = "GET http://192.0.2.10:8080/c/?mac=$macTest " +
            "Authorization: Bearer jetonEnClair12345678 jeton=$jetonGitHub"

        val masquee = Secrets.mask(ligne)

        assertThat(masquee).doesNotContain(macTest)
        assertThat(masquee).doesNotContain("jetonEnClair12345678")
        assertThat(masquee).doesNotContain(jetonGitHub)
        assertThat(masquee).doesNotContain("192.0.2.10")
        // Le masquage remplace par un repère lisible plutôt que de tout supprimer.
        assertThat(masquee).contains("mac=**")
    }

    @Test
    fun `un parametre mac dans une url est masque meme sans adresse complete`() {
        assertThat(Secrets.mask("requete ?mac=$macTest&type=itv"))
            .isEqualTo("requete ?mac=**&type=itv")
    }

    @Test
    @DisplayName("un texte sans identifiant reste inchangé")
    fun texteSansIdentifiantInchange() {
        val texte = "Connexion au portail reussie en 320 ms"
        assertThat(Secrets.mask(texte)).isEqualTo(texte)
    }
}
