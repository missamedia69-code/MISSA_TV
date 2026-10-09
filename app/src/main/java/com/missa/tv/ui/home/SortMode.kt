package com.missa.tv.ui.home

import androidx.annotation.StringRes
import com.missa.tv.R

/**
 * Ordre d'affichage des chaînes sur l'accueil.
 *
 * La numérotation est l'ordre publié par la source (comportement historique) ;
 * les autres tris répondent au classement du catalogue testé : alphabétique,
 * par groupe (type) et par pays.
 */
enum class SortMode(@StringRes val labelRes: Int) {
    NUMERATION(R.string.home_sort_number),
    ALPHABETIQUE(R.string.home_sort_alpha),
    GROUPES(R.string.home_sort_group),
    PAYS(R.string.home_sort_country),
}
