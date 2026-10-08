# Recette du mode faible débit

> **Critère d'acceptation de la version 1.0** : sur une connexion mesurée à
> **moins de 1 Mb/s**, la lecture doit rester utilisable — la stabilité prime sur
> la qualité d'image, et l'application ne doit jamais prétendre réduire la
> résolution d'une chaîne qui n'est diffusée qu'en une seule qualité.

Ce document décrit les tests automatisés qui vérifient ces règles, puis la
recette manuelle à dérouler sur un appareil réel avant publication.

---

## 1. Ce qui est vérifié automatiquement

```bash
./gradlew testDebugUnitTest
```

Les deux fichiers à surveiller dans le rapport :

| Fichier | Cas | Ce qui est vérifié |
| --- | --- | --- |
| `app/src/test/java/com/missa/tv/domain/playback/PlaybackCapsTest.kt` | 9 | Traduction des cinq modes en contraintes : hauteur et débit exacts, `0` = aucune limite pour `MAX_QUALITY`, vidéo désactivée en `AUDIO_ONLY`, et **absence de déduction de débit depuis la résolution** |
| `app/src/test/java/com/missa/tv/domain/playback/LowBandwidthAcceptanceTest.kt` | 7 | Parcours complet sur une connexion à 800 kb/s : dégradation progressive, remontée, annulation de la remontée après coupure, mode mono-qualité, mode verrouillé |
| `app/src/test/java/com/missa/tv/domain/bandwidth/QualityControllerTest.kt` | — | Seuils de décision (2 remises en tampon / 60 s, remontée à 2,0 × pendant 90 s) |
| `app/src/test/java/com/missa/tv/domain/channel/ChannelVariantGrouperTest.kt` | — | Regroupement des variantes SD/HD/FHD et détection des chaînes mono-qualité |
| `app/src/test/java/com/missa/tv/data/local/CatalogCacheTest.kt` | 8 | Catalogue local : regroupement relu depuis la base, ordre des variantes, remplacement, vidage |

Le temps est **simulé** dans ces tests : une dégradation qui devrait prendre
90 secondes se vérifie en quelques millisecondes, et le résultat ne dépend pas de
la charge de la machine de CI.

## 2. Ce qui est vérifié sur appareil

```bash
./gradlew connectedDebugAndroidTest        # sur un appareil ou un émulateur connecté
```

| Fichier | Cas | Ce qui est vérifié |
| --- | --- | --- |
| `app/src/androidTest/java/com/missa/tv/ui/QualitySelectorInstrumentedTest.kt` | 2 | Le sélecteur affiche les cinq modes et le débit mesuré ; une chaîne mono-qualité est **annoncée comme telle** |

La CI ne fait que **compiler** ces tests (`assembleDebugAndroidTest`) : aucun
émulateur n'y est lancé. Leur exécution fait partie de la recette manuelle.

---

## 3. Comportement attendu sous 1 Mb/s

| Contexte | Comportement |
| --- | --- |
| Démarrage | Mode `AUTO_ECONOMY` (720p / 900 kb/s), ou un cran plus bas si la connexion est déjà classée faible au démarrage — **jamais plus haut** |
| Chaîne proposée en SD et HD | La diffusion **SD est choisie** : le regroupement des variantes permet de réduire réellement le débit |
| Chaîne mono-qualité | Message « Cette chaîne n'est diffusée qu'en une seule qualité : le mode économie agit sur le tampon et la stabilité, pas sur la résolution » |
| 2 remises en mémoire tampon en 60 s | Un palier vers le bas : `AUTO_ECONOMY → ECONOMY_480 → ULTRA_ECONOMY → AUDIO_ONLY` |
| Débit mesuré ≥ 2,0 × le débit requis pendant 90 s | Un palier vers le haut |
| Coupure pendant la phase de remontée | La remontée est annulée (anti-oscillation) |
| Mode `MAX_QUALITY` | Aucune dégradation automatique : le choix de l'utilisateur est respecté, l'échelle de dégradation ne s'applique pas |
| Mode verrouillé | Aucune dégradation automatique, mais le choix explicite reste possible |
| Réseau perdu puis retrouvé | La lecture reprend au palier précédent, sans repartir du plus haut |

---

## 4. Recette manuelle

### 4.1 Limiter le débit

**Émulateur** — le plus simple et le plus reproductible :

```bash
# 0,5 Mb/s descendant, 0,2 Mb/s montant, latence 150 ms
adb emu network speed 0.5:0.2
adb emu network delay 150

# Retour à pleine vitesse
adb emu network speed full
adb emu network delay none
```

**Appareil réel** — au choix : limite de débit sur le routeur (QoS), partage de
connexion mobile limité, ou point d'accès bridé. Le débit doit être **mesuré**,
pas supposé : l'écran de lecture affiche le débit estimé et la classe de
connexion.

### 4.2 Scénarios à dérouler

| # | Scénario | Résultat attendu |
| --- | --- | --- |
| 1 | Démarrage à 0,5 Mb/s sur une chaîne proposée en SD et HD | La lecture démarre en moins de 10 s, sur la diffusion SD |
| 2 | Même chaîne, mode `ULTRA_ECONOMY` | Lecture stable, résolution plus basse, débit mesuré inférieur |
| 3 | Chaîne mono-qualité, mode `ULTRA_ECONOMY` | Le message d'information s'affiche ; la lecture reste fluide |
| 4 | Chaîne mono-qualité, mode `AUDIO_ONLY` | Le son continue, l'image est noire, la consommation chute |
| 5 | Passer de 0,5 à 4 Mb/s | Remontée d'un palier après ~90 s, annoncée à l'écran |
| 6 | Repasser à 0,5 Mb/s | Dégradation après ~60 s, annoncée à l'écran |
| 7 | Couper le Wi-Fi 20 s puis le rétablir | La lecture reprend, sans remonter de palier immédiatement |
| 8 | Verrouiller le mode puis saturer la connexion | Aucune dégradation automatique ; le verrou reste affiché |
| 9 | Quitter l'écran de lecture | Le lecteur est libéré : aucune donnée ne continue de descendre (vérifier dans les statistiques réseau de l'appareil) |
| 10 | Lancer l'application, playlists injoignables | La liste mémorisée s'affiche, avec le bandeau « catalogue mémorisé » |

### 4.3 Mesures à relever

| Indicateur | Où le lire | Objectif |
| --- | --- | --- |
| Temps de démarrage à froid | `adb shell am start -W -n com.missa.tv.debug/com.missa.tv.MainActivity` | < 2 s jusqu'à la liste affichée |
| Débit consommé | statistiques réseau de l'appareil, ou `adb shell dumpsys netstats` | proche du plafond du mode, jamais au-delà |
| Nombre de remises en mémoire tampon | annonces de l'écran de lecture | ≤ 2 par tranche de 60 s |
| Palier atteint | indicateur de qualité du lecteur | le plus bas qui reste lisible |

---

## 5. Résultats

À compléter lors de la recette, sur l'appareil et les playlists réelles :

| Date | Appareil | Version | Débit | Scénarios réussis | Observations |
| --- | --- | --- | --- | --- | --- |
| _(à remplir)_ | | | | | |

---

## 6. Limites connues

- **Aucun test sur une playlist réelle** n'a été possible depuis l'environnement
  de développement : le téléchargement et l'analyse M3U sont couverts par des
  flux simulés. La recette sur une playlist réelle reste à faire.
- Le débit mesuré par l'application est une **estimation** issue de la lecture en
  cours (taille des segments, cadence réelle). Elle sert à décider d'un palier,
  pas à facturer quoi que ce soit.
- Les plafonds de débit dépendent des informations publiées par le flux : un flux
  sans métadonnées de débit ne peut être plafonné que par la hauteur de piste.
- Sur les chaînes mono-qualité, **aucune** réduction de résolution n'est
  possible : c'est une limite de la source, pas de l'application, et l'interface
  le dit.
