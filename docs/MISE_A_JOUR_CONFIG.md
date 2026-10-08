# Configuration distante

L'application lit périodiquement un fichier JSON publié dans un dépôt GitHub
**privé**. Ce fichier permet de corriger un réglage — seuil de faible débit,
mode par défaut, plafonds, tampon — et surtout de déclarer les **sources de
playlists M3U** — **sans publier une nouvelle version de l'application**.

La configuration est gérée **exclusivement en ligne** : l'application n'offre
aucune saisie. Les sources déclarées ici sont les seules utilisées.

---

## 1. Emplacement du fichier

| Élément | Valeur par défaut |
| --- | --- |
| Dépôt | `missamedia69-code/missa-tv-config` (privé) |
| Chemin | `portal-config.json` (à la racine du dépôt) |
| Branche lue | **branche par défaut du dépôt** (`main`), sauf si `missa.config.ref` est renseigné |
| API | `GET https://api.github.com/repos/{owner}/{repo}/contents/{path}[?ref=<branche>]` |
| En-têtes | `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`, `If-None-Match: <etag>`, `Authorization: Bearer <jeton>` |

Le dépôt étant **privé**, un **jeton GitHub en lecture seule est requis** pour
lire le fichier. Sans jeton, GitHub répond `401`/`403` et l'application
conserve la configuration déjà mémorisée.

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
missa.config.token=github_pat_xxx
```

Seul le jeton est à fournir : le dépôt et le chemin ont leurs valeurs par
défaut. Le jeton n'est **jamais** affiché dans le journal de compilation : seuls
le nom de la variable et le fait qu'elle est fournie apparaissent.

---

### ⚠️ Le fichier doit exister sur la branche par défaut

L'API Contents lit la **branche par défaut** (`main`) quand aucun `ref` n'est
demandé. Un fichier publié uniquement sur une branche de travail est donc
**invisible pour les appareils** : la requête répond `404`, et l'application
affiche « Fichier de configuration introuvable : vérifiez le chemin et la
branche lus dans le dépôt. »

Deux façons de régler cela :

1. **fusionner la modification dans `main`** (méthode normale : `main` est la
   branche qui fait autorité pour les appareils) ;
2. compiler avec `missa.config.ref=<branche>` pour lire une autre branche — c'est
   un réglage de mise au point, à ne pas laisser dans une version publiée.

## 2. Format du fichier

Le schéma complet est dans
[`remote-config/portal-config.schema.json`](../remote-config/portal-config.schema.json)
(un gabarit vide est fourni dans `remote-config/portal-config.json`).

```json
{
  "schemaVersion": 2,
  "updatedAt": "2026-10-08T00:00:00Z",
  "minAppVersion": 1,
  "defaultPlaylistId": null,
  "playlists": [
    {
      "id": "principale",
      "name": "Ma sélection",
      "url": "https://exemple.invalid/liste.m3u8",
      "epgUrl": "https://exemple.invalid/guide.xml",
      "enabled": true
    }
  ],
  "bandwidth": {
    "lowBandwidthThresholdKbps": 1000,
    "defaultMode": "AUTO_ECONOMY",
    "maxVideoHeightByMode": { "AUTO_ECONOMY": 720, "MAX_QUALITY": 2160, "AUDIO_ONLY": 0 },
    "maxVideoBitrateByMode": { "AUTO_ECONOMY": 900000, "MAX_QUALITY": 0, "AUDIO_ONLY": 0 },
    "buffer": { "minMs": 15000, "maxMs": 40000, "playbackMs": 3000, "afterRebufferMs": 6000 }
  }
}
```

Une adresse de playlist est un **identifiant sensible** : elle ne doit figurer
que dans le dépôt privé de configuration, jamais dans le code, les journaux ou
un commit du dépôt applicatif.

### Règles appliquées à la lecture

| Règle | Conséquence |
| --- | --- |
| `schemaVersion` doit valoir `2` | sinon le fichier entier est ignoré |
| Le rafraîchissement est conditionnel (empreinte **ETag**) | un fichier inchangé répond `304` et n'est ni retéléchargé ni réanalysé ; il n'y a **pas de numéro de version à incrémenter** |
| Playlist invalide (`id` mal formé, `name` vide, `url` absente ou non `http`) | la playlist est écartée **seule**, les autres sont conservées |
| `epgUrl` présent mais non `http` | il est ignoré, la playlist reste utilisable |
| Entier hors bornes | la valeur est ignorée, la valeur par défaut s'applique |
| `buffer.maxMs < buffer.minMs` | le bloc `buffer` entier est remplacé par les valeurs par défaut |
| `0` dans `maxVideoBitrateByMode` | signifie « pas de limite », et non « zéro bit » |
| `minAppVersion` supérieure au `versionCode` installé | l'accueil affiche qu'une mise à jour est disponible (l'application reste utilisable) |

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

Les sources de playlists déclarées sont recopiées dans le magasin chiffré des
sources (`missa_playlist_sources`), distinct de la configuration. Le document est
conservé **tel quel**, chiffré : l'analyse se refait à la lecture, ce qui évite
de figer une interprétation erronée et permet de corriger l'analyseur sans
invalider les données déjà reçues.

---

## 5. Publier une mise à jour

1. Modifier `portal-config.json` **dans le dépôt privé `missa-tv-config`** et
   mettre `updatedAt` à jour. Aucun numéro de version n'est à incrémenter :
   c'est le changement de contenu (et donc d'empreinte ETag) qui déclenche
   l'application.
2. Valider localement le gabarit du schéma :

   ```bash
   python3 scripts/validate-config.py remote-config/portal-config.json
   ```
3. Committer et pousser le fichier dans `missa-tv-config`.
4. Vérifier le contenu publié (avec un jeton en lecture seule) :

   ```bash
   curl -s "https://api.github.com/repos/missamedia69-code/missa-tv-config/contents/portal-config.json" \
     -H "Accept: application/vnd.github+json" -H "Authorization: Bearer <jeton>" | head -20
   ```
5. Sur l'appareil : écran de réglages → **Vérifier maintenant**, ou attendre le
   prochain cycle de 6 heures.

---

## 6. Dépannage

| Symptôme | Cause probable | Que faire |
| --- | --- | --- |
| « Jamais vérifiée » reste affiché | aucune connexion réseau, ou jeton manquant/invalide | vérifier la connexion, puis le jeton (requis pour le dépôt privé) |
| La configuration ne change pas | le fichier n'a pas changé sur la branche lue | modifier le fichier dans `missa-tv-config` et pousser |
| « Fichier de configuration introuvable » | fichier absent de la branche lue (souvent : publié sur une branche de travail, pas encore dans `main`) | fusionner dans `main`, ou renseigner `missa.config.ref` |
| `401` / `403` | jeton expiré ou sans portée `contents:read` | régénérer un jeton en lecture seule sur le dépôt de configuration |
| Les réglages distants sont ignorés | `schemaVersion` différente de 2 | corriger le fichier ; l'application refuse les formats qu'elle ne connaît pas |

Les adresses de playlist ne sont **jamais** journalisées : en cas de doute, la
trace se limite à des codes d'erreur et à des types d'exception.
