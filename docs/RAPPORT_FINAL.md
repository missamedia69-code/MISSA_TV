# Rapport de livraison — MISSA TV 1.0.0

**Date** : 6 octobre 2026
**Dépôt** : <https://github.com/missamedia69-code/MISSA_TV>
**Branche de travail** : `arena/a245dda8-missa-tv` (62 commits)
**Dernier commit validé** : `7003ac3` — intégration continue **verte**

---

## 1. Ce qui est livré

Application Android complète, en **Kotlin uniquement**, pour **Android TV**,
**smartphone** et **tablette** : lecteur de télévision en direct connecté au
portail Stalker / Ministra configuré par l'utilisateur. **L'application ne
contient aucune chaîne et aucun flux.**

| # | Étape | État | Où |
| --- | --- | --- | --- |
| 1 | Socle du projet (Gradle Kotlin DSL, catalogue de versions, minSdk 23, compileSdk/targetSdk 37, AGP 9 avec Kotlin intégré) | livré | `app/build.gradle.kts`, `gradle/libs.versions.toml` |
| 2 | Interface Compose adaptative (Material 3 mobile, `tv-material` sur TV, `WindowWidthClass`, thème sombre TV, densité) | livré | `ui/theme`, `ui/adaptive`, `ui/home`, `ui/player`, `ui/settings` |
| 3 | Client portail Stalker : découverte d'endpoint, session, profils, genres, chaînes paginées, `create_link`, maintien de session, bascule multi-profils | livré | `data/remote/portal`, `domain/portal` |
| 4 | Configuration distante GitHub (API Contents, `ETag`, cache chiffré, WorkManager 6 h) | livré | `data/remote/config`, `data/work`, `remote-config/` |
| 5 | Stockage : profils chiffrés AES-GCM (Keystore), préférences, cache du catalogue | livré | `data/local`, `core/security` |
| 6 | Mode faible débit : 5 modes, mesure et classification, plafonds `TrackSelectionParameters`, `DefaultLoadControl` ajusté, dégradation et remontée automatiques, sélecteur TV et mobile | livré | `domain/bandwidth`, `domain/playback`, `data/player` |
| 7 | Regroupement SD / HD des variantes persistant, requêtes SQL, repli hors ligne | livré | `data/local/db`, `domain/channel` |
| 8 | Interface d'accueil, de lecture et de réglages, saisie manuelle du portail | livré | `ui/` |
| 9 | Tests : 158 cas (JUnit 5, MockK, Truth, Turbine, Compose UI Test) | livré | `app/src/test`, `app/src/androidTest` |
| 10 | CI (`ci.yml`) : hygiène/secrets, validation de la configuration distante, ktlint, lint, tests, APK debug, tests instrumentés compilés | livré | `.github/workflows/ci.yml` |
| 11 | Publication (`release.yml`) : keystore par secrets, R8, APK + AAB signés, vérification de signature, empreinte SHA-256, version GitHub | livré | `.github/workflows/release.yml` |
| 12 | Documentation et rapport | livré | `README.md`, `docs/`, `CONTRIBUTING.md` |

**Volume** : 63 fichiers Kotlin (≈ 7 300 lignes) côté application, 158 cas de test,
800 lignes de documentation.

### Phase 2 — architecture préparée, non implémentée

EPG complet, code parental, enregistrement, Chromecast : aucun de ces chantiers
n'est codé. Leur point d'accroche est décrit dans
[`ARCHITECTURE.md`](ARCHITECTURE.md#10-phase-2--architecture-préparée-non-implémentée)
(actions `get_events` / `get_short_epg` déjà déclarées, `media3-session` déjà
présent, drapeau `isCensored` déjà dans le modèle de chaîne).

---

## 2. Résultats vérifiés

| Contrôle | Résultat | Preuve |
| --- | --- | --- |
| Compilation debug | ✅ | exécution `37516414430` (commit `7003ac3`) |
| Tests unitaires et d'acceptation | ✅ **158 cas**, 0 échec | même exécution |
| Tests instrumentés (compilation) | ✅ | `assembleDebugAndroidTest` |
| ktlint | ✅ | `ktlintCheck` |
| Lint Android | ✅ (`abortOnError = true`) | `lintDebug` |
| Détection de secrets | ✅ aucun secret détecté | job « Hygiène / secrets » |
| Validation de `portal-config.json` | ✅ schéma respecté | job « Configuration distante » |
| Contrôle des chaînes français / anglais | ✅ 95 clés, parité exacte | `scripts/check-strings.py` |
| Compilation des ressources (`aapt2`) | ✅ 12 fichiers | contrôle local |

Exécutions vertes successives : `37516414430`, `37514566087`, `37510504896`.

> Les journaux complets d'exécution restent consultables dans l'onglet *Actions*
> du dépôt ; les échecs éventuels y sont aussi republiés en annotations.

---

## 3. Secrets à configurer

Aucun secret n'est présent dans le dépôt. Pour publier une version signée, créer
ces secrets dans **Settings → Secrets and variables → Actions** :

| Secret | Obligatoire | Rôle |
| --- | --- | --- |
| `MISSA_KEYSTORE_BASE64` | ✅ pour publier | keystore de signature encodé en base64 |
| `MISSA_KEYSTORE_PASSWORD` | ✅ pour publier | mot de passe du keystore |
| `MISSA_KEY_ALIAS` | ✅ pour publier | alias de la clé (ex. `missa`) |
| `MISSA_KEY_PASSWORD` | ✅ pour publier | mot de passe de la clé |
| `MISSA_CONFIG_TOKEN` | ❌ facultatif | jeton GitHub en lecture seule, **uniquement** si le dépôt de configuration est privé |

Création du keystore (à faire **une fois**, en local, hors du dépôt) :

```bash
keytool -genkeypair -v \
  -keystore missa-release.keystore \
  -alias missa -keyalg RSA -keysize 4096 -validity 10000

base64 -w0 missa-release.keystore > missa-release.keystore.b64

gh secret set MISSA_KEYSTORE_BASE64 < missa-release.keystore.b64
gh secret set MISSA_KEYSTORE_PASSWORD
gh secret set MISSA_KEY_ALIAS --body "missa"
gh secret set MISSA_KEY_PASSWORD
```

Sans ces secrets, la variante release **se compile quand même** (R8 vérifié à
chaque push) : seule la publication d'un fichier signé échoue, avec un message
explicite.

---

## 4. Étapes manuelles restantes

1. **Récupérer l'APK de développement** produit par la CI : chaque exécution verte
   du job « Build / lint / tests » publie l'artefact **`missa-tv-debug-apk`**
   (onglet *Actions* → l'exécution → section *Artifacts*, en bas de page).
   Il s'agit d'un APK signé par la clé de débogage, installable directement sur
   un téléphone, une tablette, un téléviseur ou un émulateur, et portant
   l'identifiant `com.missa.tv.debug` — il ne remplace donc pas une version
   publiée. Le résumé de l'exécution en donne la taille et l'empreinte SHA-256.
   Depuis un poste connecté à GitHub : `gh run download <id-exécution> -n missa-tv-debug-apk`.
   L'artefact est conservé 30 jours.
2. **Publier une version** : `git tag v1.0.0 && git push origin v1.0.0`.
   Le workflow `release.yml` produit l'APK et l'AAB signés, vérifie la signature
   et crée la version GitHub avec l'empreinte SHA-256.
3. **Configurer le portail** sur l'appareil : écran de réglages → profil (nom,
   URL, adresse MAC fournie par le fournisseur). Plusieurs profils sont
   possibles, la bascule est automatique.
4. **Recette sur un portail réel** : rejouer la séquence du protocole et reporter
   les valeurs observées dans [`PROTOCOLE_PORTAIL.md`](PROTOCOLE_PORTAIL.md#6-vérifier-le-protocole-sur-un-portail-réel).
   **Aucun portail n'était accessible depuis l'environnement de développement** :
   c'est la vérification la plus importante qui reste à faire.
5. **Recette faible débit** : dérouler les dix scénarios de
   [`TESTS_FAIBLE_DEBIT.md`](TESTS_FAIBLE_DEBIT.md#42-scénarios-à-dérouler) et
   remplir le tableau de résultats.
6. **Tests instrumentés** : `./gradlew connectedDebugAndroidTest` sur un
   téléviseur ou un émulateur Android TV (la CI ne fait que les compiler).
7. **Réglages du dépôt** (voir § 5) : visibilité, sujets, protection de `main`.
8. **Fusion vers `main`** : la livraison est sur
   `arena/a245dda8-missa-tv` — une demande de fusion est ouverte vers `main`.

---

## 5. Points d'attention et conflits

### 5.1 Visibilité du dépôt : demande contredite par l'existant

La demande initiale était un dépôt **privé**. Le dépôt `missamedia69-code/MISSA_TV`
est **public**, et c'est celui qui a été utilisé : il contient déjà l'historique
du projet, et il sert de source à la configuration distante (le jeton y est donc
facultatif). **Rien n'a été rendu public ou privé sans votre accord.**

Deux options, au choix :

- **rester public** : aucune action, tout fonctionne tel quel ;
- **passer en privé** : *Settings → General → Danger zone → Change visibility*.
  Il faudra alors définir `MISSA_CONFIG_TOKEN` (jeton en lecture seule) pour que
  la lecture de `remote-config/portal-config.json` continue de fonctionner, et
  l'application devra être recompilée avec ce jeton. Un dépôt privé nommé
  `missa-tv` peut aussi être créé à côté : **dites-le et la configuration sera
  adaptée** (aucun fichier ne contient le nom du dépôt en dur, seulement les
  valeurs par défaut du build).

### 5.2 `main` n'est pas protégée et `develop` n'existe pas

La méthode souhaitée (`develop` + branches `feature/*`, puis `main` à la
livraison) n'a pas pu être appliquée : cette session est liée à une seule branche,
`arena/a245dda8-missa-tv`. Le travail y est complet et **une demande de fusion
vers `main` est ouverte** ; l'historique est découpé en 62 commits atomiques en
français, ce qui permet de créer `develop` depuis `main` après fusion si vous
souhaitez conserver ce fonctionnement.

### 5.3 Sujets et protection de branche

Non appliqués : ce sont des réglages de dépôt qui demandent des droits
d'administration. Commandes équivalentes :

```bash
gh repo edit missamedia69-code/MISSA_TV \
  --add-topic android --add-topic kotlin --add-topic android-tv \
  --add-topic iptv --add-topic jetpack-compose --add-topic media3 \
  --description "MISSA TV - Application Android TV & smartphone de lecture de chaînes en direct (portail Stalker)"

gh api -X PUT repos/missamedia69-code/MISSA_TV/branches/main/protection \
  --input - <<'JSON'
{ "required_status_checks": { "strict": true, "contexts": ["Build / lint / tests"] },
  "enforce_admins": false,
  "required_pull_request_reviews": { "required_approving_review_count": 1 },
  "restrictions": null }
JSON
```

### 5.4 Historique

Trois commits `chore(ci): rapport de compilation (temporaire) [skip ci]` subsistent
dans l'historique (outil de diagnostic utilisé pendant la mise au point). Les
fichiers correspondants et l'outil ont été retirés à la livraison ; ces commits ne
contiennent aucun identifiant.

---

## 6. Risques connus

| Risque | Gravité | Ce qui a été fait | Ce qui reste à faire |
| --- | --- | --- | --- |
| **Protocole Stalker non confronté à un portail réel** | élevée | Séquence, en-têtes et analyse des réponses implémentés d'après le comportement des décodeurs MAG250, couverts par des tests à réponses simulées ; toutes les hypothèses sont listées dans `PROTOCOLE_PORTAIL.md` § 1 | Rejouer la séquence sur un portail réel (§ 6 du même document) et corriger les constantes concernées |
| Portail fermé ou hors service | moyenne | Bascule automatique sur le profil suivant (3 essais), catalogue local conservé et affiché | — |
| Chaîne diffusée en une seule qualité | moyenne | L'application **n'annonce jamais** une réduction de résolution impossible ; elle applique les optimisations de tampon et le mode audio seul | — |
| Plafonds de débit inefficaces sur un flux sans métadonnées | moyenne | Plafond de hauteur appliqué, tampon ajusté, sélection de la variante la plus légère | Mesurer sur plusieurs portails |
| `minSdk 23` et bibliothèques AndroidX récentes | moyenne | Versions figées après vérification (`work-runtime` 2.11.2, pas de `navigation-compose`) et documentées dans le catalogue | Contrôler à chaque mise à jour de dépendance |
| Estimation de débit imprécise | faible | Elle sert à choisir un palier, jamais à facturer ; la remontée exige 2,0 × la marge pendant 90 s | — |
| Keystore perdu | élevée si cela arrive | Aucun keystore dans le dépôt | **Sauvegarder** le keystore et ses mots de passe hors ligne : sans lui, plus aucune mise à jour ne sera acceptée par les appareils déjà installés |
| Configuration distante mal publiée | faible | `configVersion` stricte, schéma validé en CI, profils fautifs écartés un à un, panne réseau sans effet | Publier un JSON invalide pour vérifier le comportement (facultatif) |

---

## 7. Qualité et choix techniques notables

- **R8 activé** en release (`isMinifyEnabled`, `isShrinkResources`), règles
  ProGuard écrites pour kotlinx.serialization, Retrofit, Hilt et Media3.
- **Aucun identifiant journalisé** : `Secrets.maskMac` pour les adresses MAC,
  aucune URL de flux complète dans les traces, aucun keystore ni jeton versionné.
- **Aucun plafond de débit déduit de la résolution** : cette déduction, tentée
  puis abandonnée, bridait le mode « qualité maximale ».
- **Honnêteté de l'interface** : mono-qualité annoncée, dégradations et remontées
  annoncées, catalogue mémorisé signalé.
- **Démarrage à froid** : catalogue local affiché avant la réponse du portail.
- **Thème et navigation par `remember`** : les changements de configuration sont
  déclarés dans le manifeste, l'écran de lecture n'est pas interrompu par une
  rotation.
- **`tv-foundation` retiré** : ses listes paresseuses ont été fusionnées dans
  Compose Foundation ; s'en servir pour la forme aurait ajouté une dépendance
  inutile (décision documentée dans `ARCHITECTURE.md`).
