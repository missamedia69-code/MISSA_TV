# MISSA TV

**MISSA TV** est un lecteur de chaînes de télévision en direct pour **Android TV**,
**smartphone** et **tablette**, conçu pour fonctionner sur un portail IPTV de type
**Stalker / Ministra**.

L'application s'adapte automatiquement à l'appareil (télécommande ou tactile) et
est optimisée pour les **connexions à faible débit (moins de 1 Mb/s)** : stabilité
de lecture avant qualité d'image.

---

## Avertissement légal

> **L'application ne contient aucune chaîne, aucun flux vidéo et aucun contenu.**
> MISSA TV est un **lecteur** : il se connecte uniquement au portail que
> l'utilisateur configure lui-même, avec ses propres identifiants.
>
> L'utilisateur est **seul responsable** de n'utiliser que des contenus et des
> services pour lesquels il détient les droits et les autorisations nécessaires.
> Les auteurs du projet déclinent toute responsabilité quant à l'usage qui en est
> fait.

---

## État du projet

| Élément | Statut |
| --- | --- |
| Squelette Gradle / CI | en cours |
| Client portail Stalker | en cours |
| Configuration distante GitHub | en cours |
| UI mobile / tablette | en cours |
| UI Android TV | en cours |
| Lecteur Media3 + mode faible débit | en cours |

Ce fichier sera complété au fil des étapes (architecture, compilation,
configuration des secrets, dépannage).

---

## Identifiants et sécurité

**Aucun identifiant sensible n'est présent dans ce dépôt.** L'URL du portail et
les adresses MAC ne sont jamais écrites dans le code source : elles sont lues
depuis un fichier de configuration distant (`remote-config/portal-config.json`)
détenu par le propriétaire de l'application, ou saisies manuellement dans
l'écran de configuration de l'application.

Les valeurs du fichier versionné ici sont **volontairement vides**.

---

## Licence

Propriétaire — **Tous droits réservés**. Voir [LICENSE](LICENSE).

## Contribution

Voir [CONTRIBUTING.md](CONTRIBUTING.md).
