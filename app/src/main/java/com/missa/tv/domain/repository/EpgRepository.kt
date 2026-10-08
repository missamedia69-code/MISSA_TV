package com.missa.tv.domain.repository

import com.missa.tv.core.result.AppResult

/**
 * Guide de programmes associé au bouquet chargé.
 *
 * Le guide est téléchargé au format XMLTV depuis l'adresse déclarée dans la
 * configuration distante, puis associé aux diffusions du catalogue via leur
 * identifiant `tvgId`. Les programmes mémorisés sont ensuite servis hors
 * connexion par le cache EPG.
 */
interface EpgRepository {

    /**
     * Rafraîchit le guide des sources configurées et mémorise les programmes
     * des diffusions connues. Le cache local reste disponible hors connexion ;
     * l'échec d'une source n'empêche pas les autres d'être mises à jour.
     */
    suspend fun refresh(): AppResult<Unit>
}
