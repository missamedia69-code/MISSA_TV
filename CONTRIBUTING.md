# Contribuer à MISSA TV

Merci de votre intérêt pour MISSA TV. Ce document décrit les règles de
contribution appliquées au projet.

## 1. Règle d'or : aucun identifiant dans le dépôt

Ce dépôt est destiné à être publié. Il ne doit **jamais** contenir :

- l'URL d'un portail IPTV,
- une adresse MAC,
- un token GitHub ou un mot de passe,
- un fichier de keystore (`*.jks`, `*.keystore`),
- le fichier `local.properties` ou `keystore.properties`.

Les valeurs d'exemple du fichier `remote-config/portal-config.json` sont
**volontairement vides**. La configuration réelle est fournie au moment du build
via des secrets, ou saisie par l'utilisateur dans l'application.

La CI exécute une tâche de détection de secrets qui échoue si un motif suspect
est introduit.

## 2. Langue

- **Interface** : le français est la langue par défaut ; toutes les chaînes de
  caractères vont dans `res/values/strings.xml`, avec une traduction anglaise
  dans `res/values-en/strings.xml`.
- **Code** : identifiants en anglais, commentaires en français.
- **Documentation et commits** : en français.

## 3. Style de code

- Kotlin uniquement, style officiel (voir `.editorconfig`).
- `ktlint` et `detekt` doivent passer.
- Pas d'appel réseau sur le thread principal, pas de `!!` non justifié.
- Les logs ne doivent jamais contenir une URL complète de flux, une MAC ou un
  token : utiliser le logger du module `core` qui masque les valeurs sensibles.

## 4. Commits

Format [Conventional Commits](https://www.conventionalcommits.org/fr/), en
français, à l'impératif présent :

```
feat(lecteur): ajout du sélecteur de qualité 480p
fix(portail): recréation du lien de lecture après expiration
docs(readme): ajout de la procédure de sideload Android TV
ci: cache Gradle sur le job de tests
```

Préfixes acceptés : `feat`, `fix`, `chore`, `docs`, `ci`, `refactor`, `test`,
`perf`, `build`, `style`.

Un commit = une intention. Éviter les commits fourre-tout.

## 5. Branches et intégration

- `main` : branche stable, protégée.
- `develop` : intégration continue.
- `feature/...` : développement d'une fonctionnalité, fusionnée dans `develop`.

Toute Pull Request doit :

1. passer la CI (build, lint, tests) ;
2. ne pas introduire de secret ;
3. mettre à jour la documentation si le comportement change.

## 6. Tests

- Toute logique du domaine et toute logique faible débit doit être testée.
- Les appels réseau sont testés avec `MockWebServer`, jamais contre un vrai
  portail.
- Les captures d'écran de test ne doivent pas afficher de données réelles.

## 7. Signaler un problème

Ouvrir une issue en décrivant l'appareil, la version d'Android, le mode
(TV ou mobile) et les étapes de reproduction. **Ne joignez jamais** d'URL de
portail, de MAC ou de capture contenant ces informations : masquez-les
(`00:1A:79:**:**:**`).
