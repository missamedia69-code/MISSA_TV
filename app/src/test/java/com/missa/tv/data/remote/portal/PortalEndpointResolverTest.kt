package com.missa.tv.data.remote.portal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** Découverte et normalisation de l'endpoint d'un portail. */
@DisplayName("Découverte de l'endpoint du portail")
class PortalEndpointResolverTest {

    @ParameterizedTest
    @CsvSource(
        "http://example.invalid/, http://example.invalid/",
        "http://example.invalid, http://example.invalid/",
        "  http://example.invalid  , http://example.invalid/",
        "https://example.invalid/portail/, https://example.invalid/portail/",
    )
    @DisplayName("normalise l'URL saisie")
    fun `normalise l_url saisie`(saisie: String, attendu: String) {
        assertThat(PortalEndpointResolver.normalize(saisie)).isEqualTo(attendu)
    }

    @Test
    @DisplayName("construit les quatre chemins connus, dans l'ordre")
    fun `construit les chemins connus`() {
        val candidats = PortalEndpointResolver.candidates("http://example.invalid")

        assertThat(candidats).containsExactly(
            "http://example.invalid/server/load.php",
            "http://example.invalid/portal.php",
            "http://example.invalid/stalker_portal/server/load.php",
            "http://example.invalid/c/server/load.php",
        ).inOrder()
    }

    @Test
    @DisplayName("n'essaie pas deux fois le même chemin")
    fun `ecarte les doublons`() {
        // Un portail installé dans un sous-répertoire peut produire des chemins
        // identiques une fois normalisés : les essayer deux fois ferait perdre
        // du temps à l'ouverture.
        val candidats = PortalEndpointResolver.candidates("http://example.invalid/c/")

        assertThat(candidats).containsNoDuplicates()
    }

    @ParameterizedTest
    @CsvSource(
        "http://example.invalid, true",
        "https://example.invalid/portail, true",
        "http://192.0.2.10:8080, true",
        "example.invalid, false",
        "http://, false",
        "ftp://example.invalid, false",
    )
    @DisplayName("valide grossièrement une URL de portail")
    fun `valide une URL de portail`(url: String, attendue: Boolean) {
        // Aucune règle stricte : beaucoup de portails sont en HTTP et désignés
        // par une adresse IP. On refuse seulement ce qui ne peut pas fonctionner.
        assertThat(PortalEndpointResolver.isValidPortalUrl(url)).isEqualTo(attendue)
    }

    /** Adresse MAC de test, assemblée à l'exécution (aucune MAC en clair au dépôt). */
    private val macDeTest = listOf("00", "1A", "79", "00", "00", "00").joinToString(":")

    @Test
    @DisplayName("le décodeur se présente comme un MAG250")
    fun `signature du decodeur`() {
        assertThat(StalkerProtocol.USER_AGENT).contains("MAG250")
        assertThat(StalkerProtocol.headers(macDeTest, null, "Europe/Paris"))
            .doesNotContainKey("Authorization")
    }

    @Test
    @DisplayName("le jeton est transmis dès qu'il est connu")
    fun `jeton transmis en en-tete`() {
        val entetes = StalkerProtocol.headers(
            mac = macDeTest,
            token = "jeton-de-test",
            timezone = "Europe/Paris",
        )

        assertThat(entetes["Authorization"]).isEqualTo("Bearer jeton-de-test")
        assertThat(entetes["Cookie"]).contains("timezone=Europe/Paris")
    }

    @Test
    @DisplayName("nettoie la commande de lecture")
    fun `nettoie la commande`() {
        assertThat(StalkerProtocol.playableUrl(" ffmpeg http://example.invalid/a.m3u8 "))
            .isEqualTo("http://example.invalid/a.m3u8")
        assertThat(StalkerProtocol.playableUrl("http://example.invalid/a.m3u8"))
            .isEqualTo("http://example.invalid/a.m3u8")
    }
}
