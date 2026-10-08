package com.missa.tv.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Sélection du guide d'une chaîne : programme en cours, suivant, à venir, et
 * avancement dans le programme.
 *
 * Ces règles décident de ce que l'utilisateur voit à côté d'une chaîne et sur
 * l'écran de lecture : un guide périmé ne doit jamais présenter un programme
 * terminé comme s'il était en cours.
 */
@DisplayName("Guide d'une chaîne (EPG)")
class EpgTest {

    private val maintenant = 1_700_000_000_000L
    private val heure = 60 * 60 * 1000L

    private fun evenement(
        id: String,
        debutMs: Long = maintenant - heure,
        finMs: Long = maintenant + heure,
        titre: String = "Journal",
    ) = EpgEvent(
        id = id,
        channelId = "chaine-1",
        title = titre,
        startMs = debutMs,
        endMs = finMs,
    )

    @Nested
    @DisplayName("Sélection du guide")
    inner class Selection {

        @Test
        fun `le programme en cours est retenu`() {
            val guide = ChannelEpg.of("chaine-1", listOf(evenement("a")), maintenant)

            assertThat(guide.current?.id).isEqualTo("a")
            assertThat(guide.next).isNull()
            assertThat(guide.upcoming).isEmpty()
            assertThat(guide.hasGuide).isTrue()
        }

        @Test
        fun `un programme terminé n est plus en cours`() {
            val termine = evenement("a", maintenant - 2 * heure, maintenant - heure)

            val guide = ChannelEpg.of("chaine-1", listOf(termine), maintenant)

            assertThat(guide.current).isNull()
            assertThat(guide.next).isNull()
            assertThat(guide.hasGuide).isFalse()
        }

        @Test
        fun `un programme se terminant exactement maintenant n est plus en cours`() {
            val termineALInstant = evenement("a", maintenant - heure, maintenant)

            val guide = ChannelEpg.of("chaine-1", listOf(termineALInstant), maintenant)

            assertThat(guide.current).isNull()
        }

        @Test
        fun `un programme commençant exactement maintenant est en cours`() {
            val commence = evenement("a", maintenant, maintenant + heure)

            val guide = ChannelEpg.of("chaine-1", listOf(commence), maintenant)

            assertThat(guide.current?.id).isEqualTo("a")
            assertThat(guide.next).isNull()
        }

        @Test
        fun `le suivant est le premier programme après l instant présent`() {
            val courant = evenement("a", maintenant - heure, maintenant + heure)
            val suivant = evenement("b", maintenant + heure, maintenant + 2 * heure)
            val lointain = evenement("c", maintenant + 3 * heure, maintenant + 4 * heure)

            val guide = ChannelEpg.of("chaine-1", listOf(lointain, suivant, courant), maintenant)

            assertThat(guide.current?.id).isEqualTo("a")
            assertThat(guide.next?.id).isEqualTo("b")
            assertThat(guide.upcoming.map { it.id }).containsExactly("b", "c").inOrder()
        }

        @Test
        fun `sans programme en cours le suivant est le premier à venir`() {
            val aVenir = evenement("b", maintenant + heure, maintenant + 2 * heure)

            val guide = ChannelEpg.of("chaine-1", listOf(aVenir), maintenant)

            assertThat(guide.current).isNull()
            assertThat(guide.next?.id).isEqualTo("b")
            assertThat(guide.hasGuide).isTrue()
        }

        @Test
        fun `les événements sont triés et dédoublonnés`() {
            val b = evenement("b", maintenant + 2 * heure, maintenant + 3 * heure)
            val a = evenement("a", maintenant + heure, maintenant + 2 * heure)

            val guide = ChannelEpg.of("chaine-1", listOf(b, a, a), maintenant)

            assertThat(guide.upcoming.map { it.id }).containsExactly("a", "b").inOrder()
        }

        @Test
        fun `un guide sans événement ne retient rien`() {
            val guide = ChannelEpg.of("chaine-1", emptyList(), maintenant)

            assertThat(guide.current).isNull()
            assertThat(guide.next).isNull()
            assertThat(guide.upcoming).isEmpty()
            assertThat(guide.hasGuide).isFalse()
        }
    }

    @Nested
    @DisplayName("Avancement dans le programme")
    inner class Avancement {

        @Test
        fun `l avancement est nul au début du programme`() {
            val evenement = evenement("a", maintenant, maintenant + heure)

            assertThat(evenement.progressAt(maintenant)).isEqualTo(0f)
        }

        @Test
        fun `l avancement est à mi-chemin au milieu du programme`() {
            val evenement = evenement("a", maintenant - heure, maintenant + heure)

            assertThat(evenement.progressAt(maintenant)).isEqualTo(0.5f)
        }

        @Test
        fun `l avancement vaut un à la fin du programme`() {
            val evenement = evenement("a", maintenant - heure, maintenant)

            assertThat(evenement.progressAt(maintenant)).isEqualTo(1f)
        }

        @Test
        fun `l avancement reste borné hors du programme`() {
            val evenement = evenement("a", maintenant, maintenant + heure)

            assertThat(evenement.progressAt(maintenant - heure)).isEqualTo(0f)
            assertThat(evenement.progressAt(maintenant + 2 * heure)).isEqualTo(1f)
        }

        @Test
        fun `un programme sans durée exploitable ne produit pas de valeur aberrante`() {
            val inverse = evenement("a", maintenant + heure, maintenant - heure)

            assertThat(inverse.durationMs).isEqualTo(0L)
            assertThat(inverse.progressAt(maintenant)).isEqualTo(0f)
        }
    }

    @Nested
    @DisplayName("Chevauchement de fenêtre")
    inner class Chevauchement {

        @Test
        fun `un programme en cours chevauche la fenêtre commençant maintenant`() {
            val enCours = evenement("a", maintenant - heure, maintenant + heure)

            assertThat(enCours.overlaps(maintenant, maintenant + heure)).isTrue()
        }

        @Test
        fun `un programme terminé avant la fenêtre ne chevauche pas`() {
            val termine = evenement("a", maintenant - 2 * heure, maintenant - heure)

            assertThat(termine.overlaps(maintenant, maintenant + heure)).isFalse()
        }

        @Test
        fun `un programme commençant à la fin de la fenêtre ne chevauche pas`() {
            val aLaFin = evenement("a", maintenant + heure, maintenant + 2 * heure)

            assertThat(aLaFin.overlaps(maintenant, maintenant + heure)).isFalse()
        }
    }
}
