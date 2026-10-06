# Configuration distante

L'application lit périodiquement un fichier JSON publié dans un dépôt GitHub.
Ce fichier permet de corriger un réglage — seuil de faible débit, mode par
défaut, plafonds, tampon, ou une adresse de portail pour un déploiement familial
— **sans publier une nouvelle version de l'application**.

---

## 1. Emplacement du fichier

| Élément | Valeur par défaut |
| --- | --- |
| Dépôt | `missamedia69-code/MISSA_TV` |
| Chemin | `remote-config/portal-config.json` |
| API | `GET https://api.github.com/repos/{owner}/{repo}/contents/{path}` |
| En-têtes | `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`, `If-None-Match: <etag>` |
| Jetons | `X-GitHub-Api-Version`, et `Authorization: Bearer <jeton>` **si** un jeton est fourni |

Le dépôt étant public et le fichier **volontairement vide d'identifiants**, le
jeton est **facultatif**. Il n'est nécessaire que pour un dépôt privé.

### Où ces valeurs sont-elles définies ?

Dans `app/build.gradle.kts`, elles deviennent des constantes de compilation
(`BuildConfig.GITHUB_CONFIG_OWNER`, `…_REPO`, `…_PATH`, `…_TOKEN`). Elles sont
lues dans cet ordre :

1. `local.properties` (fichier **non versionné**) ;
2. une propriété Gradle `-Pmissa.config.owner=…` ;
3. une variable d'environnement (`MISSA_CONFIG_OWNER`, `MISSA_CONFIG_REPO`,
   `MISSA_CONFIG_PATH`, `MISSA_CONFIG_TOKEN`) — c'est ce qu'utilise la CI ;
4. la valeur par défaut ci-dessus.

```properties
# local.properties — NE JAMAIS COMMITER
missa.config.owner=mon-compte
missa.config.repo=mon-depot
missa.config.path=remote-config/portal-config.json
missa.config.token=github_pat_xxx
```

Le jeton n'est **jamais** affiché dans le journal de compilation : seuls le nom
de la variable et le fait qu'elle est fournie apparaissent.

---

## 2. Format du fichier

Le schéma complet est dans [`remote-config/portal-config.schema.json`](../remote-config/portal-config.schema.json).

```json
{
  "schemaVersion": 1,
  "configVersion": 1,
  "updatedAt": "2026-10-06T00:00:00Z",
  "minAppVersion": 1,
  "profiles": [],
  "defaultProfileId": null,
  "bandwidth": {
    "lowBandwidthThresholdKbps": 1000,
    "defaultMode": "AUTO_ECONOMY",
    "maxVideoHeightByMode": { "AUTO_ECONOMY": 720, "MAX_QUALITY": 2160, "AUDIO_ONLY": 0 },
    "maxVideoBitrateByMode": { "AUTO_ECONOMY": 900000, "MAX_QUALITY": 0, "AUDIO_ONLY": 0 },
    "buffer": { "minMs": 15000, "maxMs": 40000, "playbackMs": 3000, "afterRebufferMs": 6000 }
  }
}
```

### Règles appliquées à la lecture

| Règle | Conséquence |
| --- | --- |
| `schemaVersion` doit valoir `1` | sinon le fichier entier est ignoré |
| `configVersion` strictement supérieure à celle mémorisée | sinon l'application **conserve** sa configuration |
| Profil invalide (URL ou MAC manquante) | le profil est écarté **seul**, les autres sont conservés |
| Entier hors bornes | la valeur est ignorée, la valeur par défaut s'applique |
| `buffer.maxMs < buffer.minMs` | le bloc `buffer` entier est remplacé par les valeurs par défaut |
| `0` dans `maxVideoBitrateByMode` | signifie « pas de limite », et non « zéro bit » |
| `minAppVersion` supérieure au `versionCode` installé | l'accueil affiche qu'une mise à jour est disponible (l'application reste utilisable) |

Aucun identifiant n'est présent dans le fichier livré : `profiles` est vide et
l'utilisateur saisit son portail dans l'application (ou publie ses propres
valeurs dans **son** dépôt).

---

## 3. Quand la configuration est-elle relue ?

| Moment | Déclencheur |
| --- | --- |
| Au démarrage de l'application | lecture du cache chiffré, puis rafraîchissement en tâche de fond |
| Toutes les 6 heures | `ConfigRefreshWorker` (WorkManager, contrainte « réseau disponible », travail unique persistant) |
| À la demande | bouton « Vérifier maintenant » de l'écran de réglages |

Un `304 Not Modified` est le cas normal : il n'entraîne aucune écriture, seule la
date de dernière vérification est mise à jour. Une panne réseau **conserve** la
configuration en mémoire : l'application ne se retrouve jamais sans réglages
parce qu'un rafraîchissement a échoué.

---

## 4. Stockage local

| Clé DataStore (`missa_config`) | Contenu |
| --- | --- |
| `config_payload` | document JSON **chiffré** (AES-GCM, clé `missa_tv_config_key` dans l'Android Keystore) |
| `config_etag` | `ETag` du dernier document lu, pour les requêtes conditionnelles |
| `config_synced_at` | date de la dernière vérification réussie |

Le document est conservé **tel quel**, chiffré : l'analyse se refait à la
lecture, ce qui évite de figer une interprétation erronée et permet de corriger
l'analyseur sans invalider les données déjà reçues.

---

## 5. Publier une mise à jour

1. Modifier `remote-config/portal-config.json` en **incrémentant `configVersion`**
   (sans quoi l'application ignorera le fichier) et en mettant `updatedAt` à jour.
2. Valider localement :

   ```bash
   python3 scripts/validate-config.py remote-config/portal-config.json
   ```
3. Committer et pousser le fichier.
4. Vérifier le contenu publié :

   ```bash
   curl -s "https://api.github.com/repos/missamedia69-code/MISSA_TV/contents/remote-config/portal-config.json" \
     -H "Accept: application/vnd.github+json" | head -20
   ```
5. Sur l'appareil : écran de réglages → **Vérifier maintenant**, ou attendre le
   prochain cycle de 6 heures.

> La CI valide aussi le fichier (schéma + cohérence) à chaque modification :
> une configuration invalide ne peut pas atteindre la branche `main` sans que le
> contrôle soit rouge.

---

## 6. Dépannage

| Symptôme | Cause probable | Que faire |
| --- | --- | --- |
| « Jamais vérifiée » reste affiché | aucune connexion réseau, ou jeton invalide | vérifier la connexion, puis le jeton si le dépôt est privé |
| La configuration ne change pas | `configVersion` non incrémentée | incrémenter la version, pousser, revérifier |
| `404` dans les journaux | mauvais propriétaire, dépôt ou chemin | vérifier les valeurs `missa.config.*` du build |
| `401` / `403` | jeton expiré ou sans portée `contents:read` | régénérer un jeton en lecture seule sur le dépôt |
| Les réglages distants sont ignorés | `schemaVersion` différente de 1 | corriger le fichier ; l'application refuse les formats qu'elle ne connaît pas |

L'URL du portail et les adresses MAC ne sont **jamais** journalisées : en cas de
doute, la trace se limite à des codes d'erreur et à des types d'exception.
