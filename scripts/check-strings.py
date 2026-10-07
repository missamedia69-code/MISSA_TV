#!/usr/bin/env python3
"""Contrôles statiques des chaînes de caractères.

Ce script attrape trois classes d'erreur qui ne se voient qu'au moment de la
compilation Android, et qui coûtent un cycle de CI complet :

1. une apostrophe non échappée dans une valeur de chaîne (aapt2 refuse le
   fichier entier, pas seulement la chaîne fautive) ;
2. une chaîne dont les emplacements de formatage (%) sont mal formés ;
3. une traduction manquante ou orpheline entre `values` et `values-en`.

Il n'utilise que la bibliothèque standard : il tourne partout, y compris en CI,
sans outil Android ni SDK installé.

Usage :
    python3 scripts/check-strings.py
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

RACINE = Path(__file__).resolve().parent.parent
RES = RACINE / "app" / "src" / "main" / "res"

# Emplacement de formatage Android : %1$s, %2$d, ou %s / %d sans rang.
EMPLACEMENT = re.compile(r"%(?:\d+\$)?[a-zA-Z]")

# Séquence d'échappement acceptée dans une valeur de chaîne.
ECHAPPEMENT = re.compile(r"\\(?:[nt'\"@?\\]|u[0-9a-fA-F]{4})")


def lire_chaines(dossier: Path) -> dict[str, str]:
    """Renvoie les chaînes d'un dossier de ressources, par nom."""
    fichier = dossier / "strings.xml"
    if not fichier.is_file():
        return {}
    racine = ElementTree.parse(fichier).getroot()
    return {
        element.attrib["name"]: "".join(element.itertext())
        for element in racine.findall("string")
    }


def verifier_apostrophes(chemin: Path, chaines: dict[str, str]) -> list[str]:
    """Signale toute apostrophe qui n'est pas précédée d'une barre oblique."""
    erreurs = []
    for nom, valeur in chaines.items():
        nettoyee = ECHAPPEMENT.sub("", valeur)
        if "'" in nettoyee:
            erreurs.append(
                f"{chemin}: la chaîne '{nom}' contient une apostrophe non échappée : "
                f"{valeur!r} (écrire \\' dans le XML)"
            )
    return erreurs


def verifier_emplacements(chemin: Path, chaines: dict[str, str]) -> list[str]:
    """Vérifie que chaque % est suivi d'un spécificateur complet."""
    erreurs = []
    for nom, valeur in chaines.items():
        nettoyee = ECHAPPEMENT.sub("", valeur)
        for position, caractere in enumerate(nettoyee):
            if caractere != "%":
                continue
            suite = nettoyee[position + 1 :]
            if suite.startswith("%"):  # pourcentage littéral échappé par %%
                continue
            if not EMPLACEMENT.match(nettoyee[position :]):
                erreurs.append(
                    f"{chemin}: la chaîne '{nom}' contient un '%' qui n'ouvre pas un "
                    f"emplacement de formatage : {valeur!r}"
                )
                break
    return erreurs


def verifier_parite(
    french: dict[str, str], english: dict[str, str],
) -> list[str]:
    """Compare les jeux de clés : une traduction manquante est une régression."""
    erreurs = []
    manquantes = sorted(set(french) - set(english))
    orphelines = sorted(set(english) - set(french))
    if manquantes:
        erreurs.append(
            "chaînes absentes de values-en/strings.xml : " + ", ".join(manquantes)
        )
    if orphelines:
        erreurs.append(
            "chaînes présentes seulement en anglais : " + ", ".join(orphelines)
        )
    return erreurs


def main() -> int:
    dossier_fr = RES / "values"
    dossier_en = RES / "values-en"
    try:
        francais = lire_chaines(dossier_fr)
        anglais = lire_chaines(dossier_en)
    except ElementTree.ParseError as erreur:
        print(f"ERREUR : XML illisible ({erreur})", file=sys.stderr)
        return 1

    erreurs = []
    erreurs += verifier_apostrophes(dossier_fr / "strings.xml", francais)
    erreurs += verifier_apostrophes(dossier_en / "strings.xml", anglais)
    erreurs += verifier_emplacements(dossier_fr / "strings.xml", francais)
    erreurs += verifier_emplacements(dossier_en / "strings.xml", anglais)
    erreurs += verifier_parite(francais, anglais)

    if erreurs:
        print("Chaînes non conformes :", file=sys.stderr)
        for erreur in erreurs:
            print(f"  - {erreur}", file=sys.stderr)
        return 1

    print(
        f"Chaînes conformes : {len(francais)} clés en français, "
        f"{len(anglais)} en anglais."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
