# Protocole du portail Stalker / Ministra

Les portails Stalker (Ministra, et la plupart des serveurs utilisés par les
fournisseurs d'accès IPTV) **n'ont pas de documentation officielle**. La seule
façon d'obtenir les mêmes réponses qu'un décodeur est de se présenter comme
lui. Ce document décrit ce que l'application envoie réellement, et surtout **ce
qui est vérifié et ce qui ne l'est pas**.

---

## 1. Statut de vérification

| Élément | Statut |
| --- | --- |
| Constantes HTTP (User-Agent, `X-User-Agent`, cookie `mac`, `Authorization`) | **Reprises du comportement documenté publiquement des décodeurs MAG250** et figées dans `StalkerProtocol` |
| Séquence d'appels (`handshake` → `get_profile` → `get_genres` → `get_all_channels` → `create_link`) | **Implémentée et couverte par des tests** avec des réponses de portail simulées |
| Analyse des réponses (`js`, `token`, `data`, erreur `error`) | **Couverte par des tests** : `StalkerResponseParserTest` |
| Découverte de l'endpoint parmi quatre chemins | **Couverte** : `PortalEndpointResolverTest` |
| Bascule entre profils | **Couverte** : `PortalFailoverPolicyTest`, `PortalRepositoryImplTest` |
| **Valeurs exactes attendues par un portail réel** | ⚠️ **NON vérifiées** : aucun portail n'était accessible depuis l'environnement de développement. Aucune valeur n'a été inventée pour autant : ce qui n'était pas certain est soit absent, soit déclaré ici comme hypothèse à confirmer. |
| Pagination de `get_all_channels` (nombre de pages) | **Hypothèse** : la pagination s'arrête quand `total_items` est absent ou atteint, avec un garde-fou de 200 pages. |

> **À faire avant toute mise en production** : rejouer la séquence sur un portail
> réel (§ 6) et corriger ce document avec les valeurs observées. C'est la seule
> méthode acceptable — la mise en œuvre actuelle reproduit fidèlement le
> décodeur, mais elle n'a pas été confrontée à un serveur en production.

---

## 2. Découverte de l'endpoint

`PortalEndpointResolver` normalise l'URL saisie par l'utilisateur (ajout du
schéma si absent, suppression du `/c/` final, du `portal.php` et du
`stalker_portal/server/load.php` résiduels, `trim`), puis essaie dans l'ordre :

1. `server/load.php`
2. `portal.php`
3. `stalker_portal/server/load.php`
4. `c/server/load.php`

Le premier chemin qui répond avec un jeton valide est **mémorisé pour la
session** : les appels suivants ne refont pas la découverte. Les essais qui
échouent par erreur réseau ou par réponse inexploitable passent au chemin
suivant ; une **réponse d'authentification refusée arrête** la découverte, car
cela signifie que le portail est trouvé mais que le poste n'est pas autorisé.

---

## 3. En-têtes envoyés

| En-tête | Valeur |
| --- | --- |
| `User-Agent` | `Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 2 rev: 250 Safari/533.3` |
| `X-User-Agent` | `Model: MAG250; Link: WiFi` |
| `Cookie` | `mac=<adresse MAC>; stb_lang=fr; timezone=<fuseau>` |
| `Authorization` | `Bearer <jeton>` — présent seulement après le handshake |
| `Accept` | `application/json` |

Le paramètre `JsHttpRequest=1-xml` accompagne chaque requête, même lorsque la
réponse est en JSON : c'est ce que fait le décodeur.

**Aucune adresse MAC n'apparaît jamais dans un journal** : les traces passent par
`Secrets.maskMac`, qui réduit l'adresse à `00:1A:79:**:**:**`.

---

## 4. Déroulement d'une session

| Étape | Requête | Ce qui est lu |
| --- | --- | --- |
| 1. Handshake | `type=stb&action=handshake` | `js.token`, `js.not_valid` |
| 2. Profil | `type=stb&action=get_profile` + `PROFILE_PARAMETERS` | `js.id`, `js.name`, `js.status`, `js.subscribed`, `js.expire_billing_date` |
| 3. Genres | `type=itv&action=get_genres` | tableau `js[].id`, `js[].title` |
| 4. Chaînes | `type=itv&action=get_all_channels`, paginé | `js.data[]` : `id`, `number`, `name`, `cmd`, `logo`, `tv_genre_id`, `censored`, `status` |
| 5. Lien de lecture | `type=itv&action=create_link&cmd=<cmd>` | `js.cmd` — l'URL réelle, à préfixe `ffmpeg ` retiré |

### Paramètres de profil envoyés

```
hd=1, num_banks=2, stb_type=MAG250, client_type=STB, image_version=218,
video_out=hdmi, hw_version=1.7-BD-00, not_valid_token=0, api_signature=262
```

`device_id`, `device_id2`, `signature` et `sn` sont envoyés **vides** : les
renseigner supposerait de fabriquer des identifiants de décodeur, ce que ce
projet ne fait pas.

### Maintien de session

Le jeton est renouvelé par un handshake toutes les **3 minutes** tant que la
lecture est active. Un échec de ce renouvellement arrête proprement la boucle :
mieux vaut une session qui se termine qu'une boucle qui interroge un portail
devenu injoignable.

---

## 5. Les champs d'abonnement ne décident de rien

Les portails ne s'accordent pas sur la signification des champs rendus par
`get_profile` :

| Champ | Ce qu'on observe selon le portail |
| --- | --- |
| `status` | `1` pour un compte actif chez les uns, `0` chez d'autres — **le même sens n'est pas garanti** |
| `subscribed` | tantôt `1`, tantôt `"1"`, et très souvent un **tableau** (`[1,1]`, `[0,0]`) sans aucune documentation |
| `expire_billing_date` | date, chaîne vide, ou champ absent |

**Règle appliquée : l'application ne refuse jamais l'accès sur ces valeurs.** Un
champ absent ou mal formé vaut *inconnu*, jamais *inactif*. Ce sont les appels
suivants (`get_genres`, `get_all_channels`, `create_link`) qui décident : si le
portail refuse, l'erreur réelle est affichée, et si le compte s'était annoncé
inactif, le message le précise.

Conséquence observée en pratique : un portail parfaitement fonctionnel qui
répond `status: 0` et `subscribed: [1,1]` refusait la connexion dans la première
version de l'application (« Abonnement expiré ou inactif ») alors qu'il
fonctionnait avec d'autres lecteurs. Le test
`ne refuse jamais un profil exprime en champs variables` verrouille ce cas.

## 6. Erreurs et bascule de profil

| Réponse du portail | Interprétation | Comportement |
| --- | --- | --- |
| `{"js": "error"}` ou `false` | jeton refusé ou expiré | `UNAUTHORIZED` / `EXPIRED` : bascule sur le profil suivant |
| Compte explicitement signalé inactif (`status` ou `subscribed` à 0) | abonnement probablement inactif | **n'interrompt rien** : l'information sert seulement à expliquer un refus ultérieur du portail |
| HTTP 4xx/5xx, coupure | portail injoignable | bascule sur le profil suivant après échec |
| JSON illisible | réponse inattendue | `MALFORMED` : **pas** de bascule, l'adresse est probablement fausse |
| Session inconnue à la lecture des chaînes | session d'un autre lancement | `SESSION_EXPIRED` : message distinct, sans accuser l'abonnement |

`PortalFailoverPolicy` essaie au maximum **3 profils**, en commençant par le
profil actif, et seulement pour les erreurs où un autre profil a une chance
d'aboutir. Un profil supprimé ou désactivé n'est jamais essayé. En cas de
réussite sur un autre profil, celui-ci devient le profil actif.

---

## 7. Vérifier le protocole sur un portail réel

À exécuter **depuis une machine dont l'adresse IP est autorisée** par le
fournisseur, en remplaçant les valeurs entre chevrons. Les adresses MAC doivent
être masquées dans toute capture partagée.

```bash
# 1. Handshake
curl -s "https://<PORTAL>/server/load.php?type=stb&action=handshake&JsHttpRequest=1-xml" \
  -H "User-Agent: Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 2 rev: 250 Safari/533.3" \
  -H "X-User-Agent: Model: MAG250; Link: WiFi" \
  -H "Cookie: mac=<MAC>; stb_lang=fr; timezone=Europe/Paris"

# 2. Profil (remplacer <TOKEN> par le jeton obtenu)
curl -s "https://<PORTAL>/server/load.php?type=stb&action=get_profile&hd=1&num_banks=2&stb_type=MAG250&client_type=STB&image_version=218&video_out=hdmi&hw_version=1.7-BD-00&not_valid_token=0&api_signature=262&JsHttpRequest=1-xml" \
  -H "Authorization: Bearer <TOKEN>" -H "Cookie: mac=<MAC>"

# 3. Chaînes
curl -s "https://<PORTAL>/server/load.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml&p=1" \
  -H "Authorization: Bearer <TOKEN>" -H "Cookie: mac=<MAC>"
```

Ce qu'il faut relever et reporter dans ce document :

1. le chemin qui répond (parmi les quatre) ;
2. la présence éventuelle d'un en-tête `Set-Cookie` supplémentaire ;
3. le nom exact du champ de pagination (`total_items`, `max_page_items`, `p`) ;
4. la forme du jeton (longueur, présence de caractères spéciaux) ;
5. la forme de `cmd` renvoyée par `create_link` (préfixe, extension du fichier) ;
6. toute erreur d'authentification rencontrée et son message exact ;
7. la forme exacte de `status` et `subscribed` dans `get_profile` (§ 5).

---

## 8. Ce que l'application ne fait pas

- Elle **ne contourne aucune protection** : si le portail refuse l'accès, elle
  s'arrête et affiche l'erreur.
- Elle **ne contient aucune adresse de portail** : l'utilisateur saisit la
  sienne, ou elle est publiée dans la configuration distante de son propre dépôt.
- Elle **ne journalise aucun identifiant** : ni URL complète de flux, ni adresse
  MAC en clair.
