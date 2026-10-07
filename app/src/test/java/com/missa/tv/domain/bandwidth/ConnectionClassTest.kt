package com.missa.tv.domain.bandwidth

import com.google.common.truth.Truth.assertThat
import com.missa.tv.domain.model.BandwidthSettings
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** Classification du débit : faible, moyenne, bonne. */
class ConnectionClassTest {

    @ParameterizedTest(name = "{0} kb/s -> {1}")
    @CsvSource(
        "200, LOW",
        "600, LOW",
        "999, LOW",
        "1000, MEDIUM",
        "2000, MEDIUM",
        "2999, MEDIUM",
        "3000, GOOD",
        "12000, GOOD",
    )
    fun `classe le debit selon les seuils par defaut`(
        debitKbps: Int,
        attendu: ConnectionClass,
    ) {
        assertThat(ConnectionClass.from(debitKbps)).isEqualTo(attendu)
    }

    @ParameterizedTest(name = "débit invalide : {0}")
    @ValueSource(ints = [-1, 0])
    fun `un debit nul ou negatif est inconnu`(debitKbps: Int) {
        assertThat(ConnectionClass.from(debitKbps)).isEqualTo(ConnectionClass.UNKNOWN)
    }

    @Test
    fun `une estimation absente est inconnue`() {
        assertThat(ConnectionClass.from(null)).isEqualTo(ConnectionClass.UNKNOWN)
    }

    @Test
    fun `le seuil de connexion faible est parametrable`() {
        // Le seuil provient de la configuration distante : 2 Mb/s ici.
        val reglages = BandwidthSettings(lowBandwidthThresholdKbps = 2_000)

        assertThat(ConnectionClass.from(1_500, reglages)).isEqualTo(ConnectionClass.LOW)
        assertThat(ConnectionClass.from(2_500, reglages)).isEqualTo(ConnectionClass.MEDIUM)
    }

    @Test
    fun `seule la classe faible est contraignante`() {
        assertThat(ConnectionClass.LOW.isConstrained).isTrue()
        assertThat(ConnectionClass.MEDIUM.isConstrained).isFalse()
        assertThat(ConnectionClass.GOOD.isConstrained).isFalse()
        assertThat(ConnectionClass.UNKNOWN.isConstrained).isFalse()
    }

    @Test
    fun `l_estimation initiale est volontairement prudente`() {
        // On démarre en supposant une connexion faible : mieux vaut monter en
        // qualité que saccader dès la première seconde.
        assertThat(ConnectionClass.from(ConnectionClass.INITIAL_ESTIMATE_KBPS))
            .isEqualTo(ConnectionClass.LOW)
    }
}
