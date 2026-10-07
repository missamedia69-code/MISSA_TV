#!/usr/bin/env python3
"""Validation de la configuration distante de MISSA TV.

Ce script vérifie que ``remote-config/portal-config.json`` respecte son schéma
et applique des contrôles de cohérence que JSON Schema ne peut pas exprimer :

  1. conformité au schéma ``portal-config.schema.json`` ;
  2. unicité des identifiants de profil ;
  3. existence du profil désigné par ``defaultProfileId`` ;
  4. cohérence du bloc ``bandwidth`` (seuils croissants, bitrates cohérents
     avec les hauteurs annoncées).

Il est exécuté par la CI à chaque push. Il peut aussi être lancé à la main :

    python3 scripts/validate-config.py

Sortie 0 si tout est valide, 1 sinon.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

RACINE = Path(__file__).resolve().parent.parent
DOSSIER_CONFIG = RACINE / "remote-config"

# Dimensions minimales attendues par hauteur de vidéo (en bits par seconde).
# Ces valeurs servent uniquement à détecter des incohérences grossières, pas à
# imposer un encodage.
BITRATE_MINI_PAR_HAUTEUR = {
    0: 0,
    360: 100_000,
    480: 250_000,
    720: 500_000,
    1080: 1_000_000,
    1440: 2_000_000,
    2160: 4_000_000,
}


def erreur(message: str) -> None:
    print(f"✗ {message}")


def succes(message: str) -> None:
    print(f"✓ {message}")


def valider_schema(config: dict, schema: dict) -> list[str]:
    """Retourne la liste des erreurs de conformité au schéma."""
    try:
        from jsonschema import Draft202012Validator
    except ImportError:  # pragma: no cover - dépendance de CI
        print("! bibliothèque jsonschema absente : contrôle du schéma ignoré")
        return []

    validateur = Draft202012Validator(schema)
    erreurs = sorted(validateur.iter_errors(config), key=lambda e: list(e.absolute_path))
    resultat = []
    for exc in erreurs:
        chemin = "/".join(str(p) for p in exc.absolute_path) or "(racine)"
        resultat.append(f"schéma : {chemin} → {exc.message}")
    return resultat


def valider_coherence(config: dict) -> list[str]:
    """Contrôles que le schéma ne peut pas exprimer."""
    erreurs: list[str] = []

    profils = config.get("profiles", [])
    identifiants = [p.get("id") for p in profils]

    if len(identifiants) != len(set(identifiants)):
        erreurs.append("identifiants de profil dupliqués")

    defaut = config.get("defaultProfileId")
    if defaut is not None and defaut not in identifiants:
        erreurs.append(f"defaultProfileId « {defaut} » ne correspond à aucun profil")

    if not profils:
        print("  (aucun profil déclaré : c'est l'état attendu du dépôt, "
              "les valeurs réelles sont fournies au build ou saisies dans l'application)")

    debit = config.get("bandwidth")
    if isinstance(debit, dict):
        hauteurs = debit.get("maxVideoHeightByMode", {})
        bitrates = debit.get("maxVideoBitrateByMode", {})
        tampon = debit.get("buffer", {})

        for mode, hauteur in hauteurs.items():
            if mode not in bitrates:
                erreurs.append(f"bandwidth : le mode « {mode} » a une hauteur mais pas de bitrate")
                continue
            bitrate = bitrates[mode]
            # 0 signifie « pas de plafond » : rien à vérifier.
            if bitrate == 0:
                continue
            minimum = 0
            for hauteur_max, bitrate_min in BITRATE_MINI_PAR_HAUTEUR.items():
                if hauteur >= hauteur_max:
                    minimum = bitrate_min
            if bitrate < minimum:
                erreurs.append(
                    f"bandwidth : le mode « {mode} » plafonne à {bitrate} b/s "
                    f"alors que {hauteur}p demande au moins {minimum} b/s"
                )

        for mode, bitrate in bitrates.items():
            if mode not in hauteurs:
                erreurs.append(f"bandwidth : le mode « {mode} » a un bitrate mais pas de hauteur")

        if tampon:
            mini = tampon.get("minMs", 0)
            maxi = tampon.get("maxMs", 0)
            lecture = tampon.get("playbackMs", 0)
            apres = tampon.get("afterRebufferMs", 0)
            if maxi <= mini:
                erreurs.append(f"bandwidth.buffer : maxMs ({maxi}) doit dépasser minMs ({mini})")
            if lecture > mini:
                erreurs.append(
                    f"bandwidth.buffer : playbackMs ({lecture}) doit rester "
                    f"inférieur à minMs ({mini})"
                )
            if apres < lecture:
                erreurs.append(
                    f"bandwidth.buffer : afterRebufferMs ({apres}) doit être "
                    f"supérieur ou égal à playbackMs ({lecture})"
                )

    return erreurs


def main() -> int:
    chemin_schema = DOSSIER_CONFIG / "portal-config.schema.json"
    chemin_config = DOSSIER_CONFIG / "portal-config.json"

    for chemin in (chemin_schema, chemin_config):
        if not chemin.exists():
            erreur(f"fichier manquant : {chemin.relative_to(RACINE)}")
            return 1

    try:
        schema = json.loads(chemin_schema.read_text(encoding="utf-8"))
        config = json.loads(chemin_config.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        erreur(f"JSON invalide dans {chemin_config.name} : {exc}")
        return 1

    toutes_erreurs = valider_schema(config, schema)
    toutes_erreurs += valider_coherence(config)

    if toutes_erreurs:
        for message in toutes_erreurs:
            erreur(message)
        print(f"\n✗ {len(toutes_erreurs)} problème(s) dans portal-config.json")
        return 1

    succes("portal-config.json conforme au schéma et cohérent")
    nb = len(config.get("profiles", []))
    print(f"  {nb} profil(s) déclaré(s), configVersion={config.get('configVersion')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
