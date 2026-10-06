# MISSA TV

**MISSA TV** est un lecteur de télévision en direct pour **Android TV**,
**smartphone** et **tablette**. Il se connecte au portail IPTV de type
**Stalker / Ministra** que **vous** configurez, avec vos propres identifiants.

L'application est conçue pour les **connexions à faible débit (moins de 1 Mb/s)** :
la stabilité de lecture passe avant la qualité d'image, et l'application vous dit
toujours ce qu'elle fait — y compris quand elle ne peut rien améliorer.

---

## Avertissement légal

> **L'application ne contient aucune chaîne, aucun flux et aucun contenu.**
> MISSA TV est un **lecteur** : il se connecte uniquement au portail que
> l'utilisateur configure lui-même.
>
> L'utilisateur est **seul responsable** de n'utiliser que des contenus et des
> services pour lesquels il détient les droits et les autorisations nécessaires.
> Les auteurs du projet déclinent toute responsabilité quant à l'usage qui en est
> fait.

---

## État du projet

| Élément | Statut |
| --- | --- |
| Squelette Gradle, catalogue de versions, CI | terminé |
| Client portail Stalker / Ministra (découverte, session, catalogue, liens) | terminé |
| Configuration distante GitHub (ETag, cache chiffré, WorkManager 6 h) | terminé |
| Interface mobile / tablette / Android TV | terminé |
| Lecteur Media3 et mode faible débit (5 modes, adaptation automatique) | terminé |
| Catalogue local Room (ouverture immédiate, repli hors ligne) | terminé |
| Publication signée (R8, keystore par secrets) | terminé |
| EPG complet, code parental, enregistrement, Chromecast | **phase 2** (architecture préparée, non implémentée) |

---

## Identifiants et sécurité

**Aucun identifiant sensible n'est versionné.** L'URL du portail et les adresses
MAC ne figurent ni dans le code, ni dans les journaux : elles sont saisies dans
l'application (ou publiées dans **votre** dépôt, via la configuration distante) et
stockées **chiffrées** (AES-GCM, clé conservée par l'Android Keystore).

- Les adresses MAC sont masquées (`00:1A:79:**:**:**`) dans toute trace.
- Les URL complètes de flux ne sont jamais journalisées.
- Le fichier `remote-config/portal-config.json` livré est **volontairement vide**.
- La CI exécute un contrôle de secrets qui échoue si un motif suspect apparaît.

---

## Compiler

Pré-requis : **JDK 17**, SDK Android avec la plateforme **37**, et un
`local.properties` contenant `sdk.dir=…`.

```bash
./gradlew assembleDebug             # APK de développement
./gradlew testDebugUnitTest         # tests unitaires et d'acceptation
./gradlew lintDebug ktlintCheck     # analyse statique
./gradlew assembleRelease           # APK release, R8 activé (signé si keystore fourni)
./gradlew connectedDebugAndroidTest # tests d'interface (appareil requis)
```

| Variante | Application | Remarque |
| --- | --- | --- |
| `debug` | `com.missa.tv.debug` | installable à côté de la version publiée |
| `release` | `com.missa.tv` | minification et réduction des ressources activées |

### Configuration du build

Ces valeurs se règlent dans `local.properties` (non versionné), par propriété
Gradle ou par variable d'environnement :

| Propriété | Variable | Rôle |
| --- | --- | --- |
| `missa.config.owner` | `MISSA_CONFIG_OWNER` | compte propriétaire du dépôt de configuration |
| `missa.config.repo` | `MISSA_CONFIG_REPO` | dépôt contenant `portal-config.json` |
| `missa.config.path` | `MISSA_CONFIG_PATH` | chemin du fichier dans le dépôt |
| `missa.config.token` | `MISSA_CONFIG_TOKEN` | jeton GitHub (facultatif : le dépôt public n'en exige pas) |
| `missa.keystore.file` | `MISSA_KEYSTORE_FILE` | keystore de signature (release) |
| `missa.keystore.password` | `MISSA_KEYSTORE_PASSWORD` | mot de passe du keystore |
| `missa.key.alias` | `MISSA_KEY_ALIAS` | alias de la clé |
| `missa.key.password` | `MISSA_KEY_PASSWORD` | mot de passe de la clé |
| `missa.versionName` | — | nom de version publié (`-Pmissa.versionName=1.0.0`) |

Sans keystore, la variante release **se compile quand même** : R8 s'exécute, les
règles sont vérifiées, et seul le fichier final n'est pas signé. C'est ce qui
permet à la CI de valider la minification à chaque push sans détenir de secret.

---

## Utilisation

1. **Ouvrir l'application.** Aucun portail n'étant configuré, elle propose la
   saisie manuelle.
2. **Saisir l'adresse du portail et l'adresse MAC** fournies par votre
   fournisseur, puis enregistrer. Le profil peut être nommé ; plusieurs profils
   sont possibles, avec bascule automatique si l'un d'eux ne répond plus.
3. **Choisir une chaîne.** Les diffusions d'une même chaîne (SD, HD, FHD) sont
   regroupées : une seule ligne par chaîne.
4. **Régler la qualité** depuis l'écran de lecture (D-pad sur TV, feuille
   glissante sur mobile) : cinq modes, du plus économe au maximum, plus un mode
   audio seul.

Le mode **Économie automatique** est actif par défaut : il plafonne la qualité à
720p / 900 kb/s, choisit la diffusion la plus légère disponible et adapte le
palier à la connexion mesurée. Le verrou du sélecteur empêche toute adaptation
automatique, sans jamais bloquer un choix explicite.

---

## Documentation

| Document | Contenu |
| --- | --- |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Couches, moteur de qualité, cache Room, adaptabilité TV/mobile, tests, préparation de la phase 2 |
| [`docs/PROTOCOLE_PORTAIL.md`](docs/PROTOCOLE_PORTAIL.md) | Protocole Stalker tel qu'implémenté, statut de vérification et procédure de contrôle sur un portail réel |
| [`docs/MISE_A_JOUR_CONFIG.md`](docs/MISE_A_JOUR_CONFIG.md) | Format de la configuration distante, publication, dépannage |
| [`docs/TESTS_FAIBLE_DEBIT.md`](docs/TESTS_FAIBLE_DEBIT.md) | Recette du critère d'acceptation « moins de 1 Mb/s » |
| [`docs/RAPPORT_FINAL.md`](docs/RAPPORT_FINAL.md) | Rapport de livraison : résultats de build, secrets à configurer, étapes manuelles, risques |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Règles de contribution, langue, format des commits |
| [`remote-config/portal-config.schema.json`](remote-config/portal-config.schema.json) | Schéma JSON de la configuration distante |

---

## Licence

**Tous droits réservés** — voir [`LICENSE`](LICENSE). Ce dépôt est publié pour
consultation ; toute réutilisation, redistribution ou publication dérivée est
soumise à l'autorisation écrite de l'auteur.
