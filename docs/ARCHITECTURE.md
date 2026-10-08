# Architecture de MISSA TV

Ce document décrit la structure réelle du code livré : couches, responsabilités,
et les décisions qui ont des conséquences visibles pour l'utilisateur.

---

## 1. Vue d'ensemble

```
com.missa.tv
├── core/        socle sans logique métier (erreurs, logs, sécurité, temps, UI)
├── domain/      règles métier et modèles — ne dépend d'aucune bibliothèque Android
├── data/        implémentations : portail Stalker, configuration distante, stockage, lecteur
└── ui/          écrans Compose, ViewModels, navigation, thème adaptatif
```

La règle de dépendance est stricte : `ui → domain ← data`. Le domaine ne connaît
ni Retrofit, ni Room, ni ExoPlayer ; il expose des interfaces
(`PortalRepository`, `PortalProfileSource`, `RemoteConfigRepository`, `SettingsStore`)
que `data` implémente. C'est ce qui permet de tester les règles d'adaptation au
débit sans appareil, sans réseau et sans portail.

| Couche | Contenu principal |
| --- | --- |
| `core` | `AppResult`, `AppError`, `MissaLog`, `Secrets` (masquage), `SecretCipher`, `TimeSource`, `DispatcherProvider`, `ViewModelFactories` |
| `domain` | `QualityMode`, `BandwidthSettings`, `PlaybackCaps`, `QualityController`, `Channel`/`ChannelGroup`, `ChannelVariantGrouper`, `PortalCatalog`, `PortalProfile`, `RemoteConfig`, `PortalFailoverPolicy` |
| `data` | `StalkerClient` + `StalkerApi` + `StalkerResponseParser`, `EncryptedPortalProfileSource`, `DataStoreSettingsStore`, `CatalogCache` (Room), `GitHubConfigDataSource`, `RemoteConfigRepositoryImpl`, `PlayerFactory`, `PlaybackQualityApplier`, `ConfigRefreshWorker` |
| `ui` | `AppRoot`, `HomeScreen`, `PlayerScreen` + `QualitySelector`, `SettingsScreen`, `DeviceDiagnosticScreen`, thème adaptatif, navigation écrite à la main |

---

## 2. Chaîne de lecture et moteur de qualité

C'est la partie qui porte le critère d'acceptation « moins de 1 Mb/s ».

```
SettingsStore ─┐
RemoteConfig ──┼──► BandwidthSettings ──► PlaybackCaps ──► PlayerFactory (Media3)
ConnectionClass┘            ▲                                    │
                            │                                    ▼
                     QualityController ◄── télémétrie de lecture (rebuffers, débit mesuré)
```

| Composant | Rôle |
| --- | --- |
| `PlaybackCaps` | Traduit un mode en contraintes concrètes : hauteur maximale, débit maximal, vidéo désactivée. **Aucun plafond de débit n'est déduit de la résolution** : une chaîne 480p peut consommer plus qu'une 720p mieux encodée, et brider à partir de la hauteur empêcherait le mode « qualité maximale » de tenir sa promesse. |
| `PlayerFactory` | Construit l'`ExoPlayer` : `TrackSelectionParameters` (plafonds), `DefaultLoadControl` (les quatre durées de `BufferSettings`), `WAKE_MODE_NETWORK`, source de données OkHttp partagée, `User-Agent` générique **sans adresse MAC**. |
| `PlaybackQualityApplier` | Change les plafonds **à chaud**, sans recréer le lecteur. Signale seulement quand le tampon doit être recréé. |
| `QualityController` | Décide dégradation et remontée à partir de la télémétrie : 2 remises en mémoire tampon en 60 s ⇒ un palier vers le bas ; remontée si le débit mesuré dépasse 2,0 × le débit requis pendant 90 s, toute coupure annulant la remontée. |

### Les cinq modes

| Mode | Plafond de hauteur | Plafond de débit |
| --- | --- | --- |
| `AUTO_ECONOMY` (défaut) | 720p | 900 kb/s |
| `ECONOMY_480` | 480p | 700 kb/s |
| `ULTRA_ECONOMY` | 360p | 400 kb/s |
| `MAX_QUALITY` | 2160p | aucune limite |
| `AUDIO_ONLY` | — (vidéo désactivée) | 96 kb/s |

### Ordre de décision du mode

1. le **choix explicite** de l'utilisateur, respecté même si le verrou est actif ;
2. le mode **recommandé** par la configuration distante ;
3. le mode **corrigé par la connexion mesurée** — une connexion lente au démarrage
   fait descendre d'un cran, jamais monter.

### Honnêteté de l'interface

Quand le portail ne publie qu'une seule diffusion d'une chaîne, l'application
**ne prétend pas réduire la résolution** : elle affiche « Cette chaîne n'est
diffusée qu'en une seule qualité » et applique les optimisations de tampon. Une
amélioration possible (`ChannelGroup.bestFor`) est de choisir la diffusion la plus
légère parmi celles disponibles.

---

## 3. Regroupement SD / HD des variantes

Les portails Stalker publient souvent la même chaîne plusieurs fois — « TF1 »,
« TF1 HD », « TF1 FHD ». Ces flux sont **sans variantes** : réduire la qualité
impose donc de choisir une autre diffusion.

`ChannelVariantGrouper` analyse le libellé (aucune information de définition
fiable n'est fournie par `get_all_channels`) : marqueurs `UHD/4K`, `FHD/1080`,
`HD/720`, `SD/480`, `LQ/360`, puis le nom de base est débarrassé de ces mentions.
La clé de groupe combine la catégorie et le nom de base, pour ne pas fusionner
deux chaînes homonymes de genres différents.

Le résultat est **mémorisé en base Room** (`groupKey`, `baseName`, `qualityRank`,
`qualityLabel`) : la liste est reconstruite par SQL, sans réanalyser des milliers
de libellés à chaque ouverture. La requête d'agrégation renvoie, par groupe, le
nom affiché, le nombre de variantes et le nombre de **qualités distinctes** —
`distinctQualityCount = 1` étant exactement le cas mono-qualité annoncé à
l'utilisateur. Le tri des variantes est fait en SQL, de la plus légère à la plus
lourde, de sorte que le mode économie parte toujours de la bonne diffusion.

> Note de compatibilité : le nom affiché est obtenu par sous-requête et non par
> une fonction de fenêtrage, indisponible sur les appareils les plus anciens
> (SQLite livré avec Android 6).

---

## 4. Stockage

| Donnée | Support | Chiffrement |
| --- | --- | --- |
| Profils de portail (URL + MAC) | DataStore `missa_profiles` | AES-GCM, clé dans l'Android Keystore (`SecretCipher`) ; l'identifiant du profil actif reste en clair |
| Configuration distante | DataStore `missa_config` | AES-GCM, même mécanisme (`config_payload`) ; `config_etag` et `config_synced_at` en clair |
| Préférences de lecture (mode, verrou) | DataStore `missa_settings` | en clair : aucune donnée sensible |
| Catalogue (catégories, diffusions) | Room `missa_tv.db` | en clair **assumé** : la base ne contient aucun identifiant, seulement des noms de chaînes publics du portail, et elle est effaçable sans perte de configuration |

**Aucune écriture n'a lieu si le chiffrement échoue** : mieux vaut ne rien
enregistrer que d'écrire une adresse MAC en clair.

---

## 5. Démarrage à froid : tenir les deux secondes

1. `MainActivity` ne fait que composer l'arbre d'interface (`AppRoot`).
2. `HomeViewModel` publie **immédiatement** les groupes du catalogue local
   (Room), puis interroge le portail en tâche de fond.
3. Si le portail répond, la liste est remplacée par la version fraîche et le
   catalogue est réenregistré (écriture après affichage : l'utilisateur n'attend
   jamais la base).
4. Si le portail ne répond pas, la liste mémorisée **reste affichée** avec un
   bandeau « catalogue mémorisé » — une liste légèrement en retard vaut mieux
   qu'un écran d'erreur.

---

## 6. Configuration distante

`GitHubConfigDataSource` lit `remote-config/portal-config.json` par l'API
Contents (`GET /repos/{owner}/{repo}/contents/{path}`) avec `If-None-Match` et
`X-GitHub-Api-Version: 2022-11-28`. Un `304` ne coûte rien ; un `200` est
déchiffré, analysé (`schemaVersion = 1` exigé), validé champ par champ, puis
enregistré **uniquement si `configVersion` est strictement supérieure**. Les
profils invalides sont écartés **un par un** : une entrée fautive ne doit pas
annuler toute une mise à jour.

`ConfigRefreshWorker` (WorkManager, toutes les **6 heures**, réseau exigé) applique
la même logique en arrière-plan. Une panne réseau **conserve** la configuration
en place : l'application ne devient jamais inutilisable parce qu'un rafraîchissement
a échoué. Détails : [`MISE_A_JOUR_CONFIG.md`](MISE_A_JOUR_CONFIG.md).

---

## 7. Interface adaptative

`DeviceProfile` (type, classe de largeur, orientation, tactile, PiP, échelle de
police) est calculé une fois par `rememberDeviceProfile()`.

| Appareil détecté | Interface |
| --- | --- |
| Télévision | Thème sombre forcé, densité adaptée au recul, navigation au D-pad, cartes `androidx.tv.material3` (focus mis à l'échelle, visible à distance), sélecteur de qualité en boîte de dialogue |
| Téléphone / tablette | Material 3, navigation tactile, sélecteur de qualité en feuille glissante |

Le **`WindowSizeClass` n'est pas utilisé** : il appartient à
`androidx.compose.material3.adaptive`, qui exige minSdk 24 alors que le projet
cible minSdk 23 pour rester installable sur des boîtiers Android TV anciens. La
classe de largeur est donc calculée à partir de la taille de fenêtre réelle
(`WindowWidthClass`), ce qui couvre le besoin d'adaptation sans relever le
minimum d'API.

`androidx.tv:tv-foundation` n'est **pas** utilisé : ses listes paresseuses
(`TvLazyRow`, `TvLazyColumn`) ont été fusionnées dans Compose Foundation, et le
reste du module n'apporte rien à ce projet. Les listes de l'écran d'accueil sont
donc des `LazyRow` / `LazyColumn` ordinaires, et seule la carte de chaîne est
spécifique à la télévision (`androidx.tv.material3.Card`).

De même, `androidx.navigation` (et `hilt-navigation-compose`) est écarté pour la
même raison : la navigation est écrite à la main (`ui/navigation`), avec une pile
conservée par `remember` — les changements de configuration sont déclarés dans le
manifeste, l'activité n'est donc pas recréée et l'écran de lecture n'est pas
interrompu par une rotation.

---

## 8. Libération des ressources

- Le lecteur Media3 est libéré dans `onStop`/`onDestroy` de l'écran de lecture
  (`DisposableEffect` + `PlayerViewModel.releasePlayer()`), et non seulement à la
  destruction de l'activité : sans cela, un lecteur oublié continue de
  télécharger et consomme le forfait de l'utilisateur.
- Les flux sont collectés avec `collectAsStateWithLifecycle`.
- Le client HTTP est partagé (OkHttp) ; les liens de lecture sont demandés au
  portail au dernier moment, jamais conservés.

---

## 9. Tests

| Niveau | Outils | Ce qui est couvert |
| --- | --- | --- |
| Unitaire (JVM) | JUnit 5, Truth, MockK, Turbine | règles de qualité, tampon, regroupement, analyse des réponses du portail, configuration distante, chiffrement, cache |
| Acceptation bas débit (JVM) | JUnit 5, horloge simulée | dégradation progressive, remontée, mode mono-qualité |
| Instrumenté (appareil) | Compose UI Test | sélecteur de qualité (les cinq modes, verrou, annonce mono-qualité) |

```bash
./gradlew testDebugUnitTest          # tests unitaires et d'acceptation
./gradlew assembleDebugAndroidTest   # compilation des tests instrumentés
```

La CI compile les tests instrumentés mais ne les exécute pas (pas d'émulateur) :
leur exécution relève de la recette manuelle décrite dans
[`TESTS_FAIBLE_DEBIT.md`](TESTS_FAIBLE_DEBIT.md).

---

## 10. Guide électronique des programmes (EPG)

Le portail publie un guide par chaîne, en deux granularités : le **guide
court** (`get_short_epg`, le programme en cours et les suivants) et le **guide
complet** (`get_events`, paginé sur une fenêtre de dates). L'application affiche
les deux, toujours depuis le cache d'abord.

```
StalkerClient.shortEpg / events ──► StalkerResponseParser (formes tolérées)
        │                                    │
        ▼                                    ▼
PortalRepository.shortEpg / epg      EpgEvent (domaine : startMs, endMs, titre)
        │                                    │
        ▼                                    ▼
EpgLoader (politique de rafraîchissement) ──► EpgCache (Room, table epg_events)
        │                                    │
        ▼                                    ▼
HomeViewModel / EpgViewModel /         ChannelEpg.of (sélection : en cours,
ChannelGuideViewModel /                suivant, à venir — le périmé est
PlayerViewModel                        filtré par le temps)
```

| Composant | Rôle |
| --- | --- |
| `EpgEvent` / `ChannelEpg` | Modèle du domaine. `ChannelEpg.of` ne retient que ce qui chevauche l'instant présent : un guide périmé ne peut pas afficher un programme terminé comme en cours. |
| `StalkerResponseParser.shortEpg` / `events` | Analyse tolérante : tableau d'événements, tableau de chaînes imbriquées, réponse paginée (`js.data.data`), horodatages en secondes ou en millisecondes. Les heures « HH:MM » seules sont ignorées (la date du jour manque). |
| `StalkerClient.events` | Pagination identique à celle des chaînes, avec `date_from` / `date_to` au format `yyyy-MM-dd` dans le fuseau de la session. |
| `EpgCache` | Table `epg_events`, fusion par identifiant (guide court et guide complet coexistent), suppression des programmes terminés à chaque écriture : la table reste bornée sans migration. |
| `EpgLoader` | Politique partagée par tous les écrans : guides mémorisés d'abord, puis rafraîchissement des chaînes périmées (30 min), borné à 40 requêtes, interrompu au premier échec. |
| `EpgRefreshWorker` | Rafraîchissement périodique (3 h) des guides **déjà mémorisés** (30 chaînes max) : jamais le catalogue entier. |
| Écrans | Accueil (programme en cours sous chaque chaîne), grille « Programme TV » (en cours + suivant par chaîne, rafraîchie au défilement), programme d'une chaîne (24 h, bouton « Regarder »), lecteur (programme en cours + suivant). |

La base passe en version 2 (ajout de `epg_events`) ; la migration destructive
reste acceptable, le guide se reconstruit au prochain chargement.

---

## 11. Phase 2 — architecture préparée, non implémentée

Ces fonctions ne sont **pas** livrées ; leur place est réservée pour ne pas avoir
à réécrire les couches existantes.

| Fonction | Point d'accroche prévu | Ce qu'il faudra ajouter |
| --- | --- | --- |
| Code parental | `SettingsStore` et l'écran de réglages existent | hachage du code (jamais en clair), drapeau `isCensored` des chaînes déjà présent dans le modèle, écran de verrouillage avant l'accès aux catégories réservées |
| Enregistrement | `PlayerFactory` construit un lecteur unique ; `Media3` fournit `MediaRecorder`/`DownloadManager` | service d'enregistrement au premier plan, stockage, notification de progression, gestion de l'espace libre |
| Chromecast | `media3-session` est déjà une dépendance | `MediaSessionService`, `CastPlayer` et bouton de diffusion ; le mode faible débit devient un plafond transmis au récepteur |

Ces trois chantiers n'ajoutent aucune dépendance lourde et n'invalident aucune
décision d'architecture prise ici.
