package com.missa.tv.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.missa.tv.domain.model.Channel

/**
 * Écrans de l'application.
 *
 * La navigation est écrite à la main : la bibliothèque de navigation de Jetpack
 * exige minSdk 24, alors que le projet cible minSdk 23 pour rester installable
 * sur les boîtiers Android TV anciens. Le besoin est ici très simple — quatre
 * écrans et une pile d'un niveau.
 */
sealed interface Screen {

    /** Liste des chaînes, filtrée par catégorie. */
    data object Home : Screen

    /** Lecture d'une chaîne. */
    data class Player(val channel: Channel) : Screen

    /** Réglages : profils de connexion, qualité, diagnostics. */
    data object Settings : Screen

    /** Configuration manuelle d'un portail (aucune valeur n'est fournie d'avance). */
    data object ManualSetup : Screen
}

/**
 * État de navigation de l'application.
 *
 * Une pile est conservée pour que le retour ramène à l'écran précédent, ce qui
 * est le comportement attendu aussi bien sur téléphone que sur télévision.
 */
class MissaNavigator internal constructor(initial: Screen) {

    private val stack = mutableListOf(initial)

    var current by mutableStateOf(initial)
        private set

    /** Empile un écran. */
    fun open(screen: Screen) {
        if (screen == current) return
        stack += screen
        current = screen
    }

    /**
     * Dépile l'écran courant.
     *
     * @return faux si la pile ne contient plus qu'un écran : l'appelant peut
     *   alors fermer l'application plutôt que de rester bloqué.
     */
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        current = stack.last()
        return true
    }

    /** Revient directement à l'accueil (utilisé après une configuration réussie). */
    fun home() {
        stack.clear()
        stack += Screen.Home
        current = Screen.Home
    }

    /** Remplace l'écran courant, sans empiler (retour d'un écran de lecture). */
    fun replace(screen: Screen) {
        stack.removeAt(stack.lastIndex)
        stack += screen
        current = screen
    }
}

/**
 * Mémorise un navigateur.
 *
 * La rotation et les autres changements de configuration ne recréent pas
 * l'activité (ils sont déclarés dans le manifeste) : la pile de navigation est
 * donc conservée telle quelle, sans mécanisme supplémentaire.
 */
@Composable
fun rememberNavigator(initial: Screen = Screen.Home): MissaNavigator =
    remember(initial) { MissaNavigator(initial) }
