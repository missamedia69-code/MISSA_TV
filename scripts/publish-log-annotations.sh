#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# MISSA TV — publication d'un journal sous forme d'annotations GitHub
#
# Pourquoi ce script existe :
#   Les journaux complets d'un job GitHub Actions sont servis depuis des hôtes
#   de stockage (results-receiver.actions.githubusercontent.com) qui ne sont pas
#   joignables depuis tous les environnements. Les *annotations*, en revanche,
#   restent lisibles via l'API :
#
#     gh api repos/<compte>/<dépôt>/check-runs/<id>/annotations
#
#   Ce script découpe la fin d'un fichier journal en plusieurs annotations, ce
#   qui rend un échec de compilation diagnosticable à distance.
#
# Il est utilisé uniquement pendant la phase de construction (voir ci.yml) et
# sera retiré avec l'étape correspondante.
#
# Usage :
#   scripts/publish-log-annotations.sh [fichier] [titre] [taille_morceau] [nb_max]
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

fichier="${1:-build-output.txt}"
titre="${2:-gradle}"
taille_max="${3:-4000}"
nb_max="${4:-10}"

if [[ ! -f "$fichier" ]]; then
  echo "::warning title=${titre}::fichier ${fichier} introuvable"
  exit 0
fi

# Échappe les caractères réservés par la syntaxe des annotations.
escape_annotation() {
  sed 's/%/%25/g' | sed ':a;N;$!ba;s/\n/%0A/g'
}

contenu="$(tail -c $((taille_max * nb_max)) "$fichier")"
total="${#contenu}"

echo "::notice title=${titre}::${total} caractères de journal à publier depuis ${fichier}"

i=0
while [[ -n "$contenu" && "$i" -lt "$nb_max" ]]; do
  morceau="$(printf '%s' "$contenu" | head -c "$taille_max")"
  restant=$((taille_max + 1))
  contenu="$(printf '%s' "$contenu" | tail -c +"$restant")"
  echo "::error title=${titre}-${i}::$(printf '%s' "$morceau" | escape_annotation)"
  i=$((i + 1))
done

echo "::notice title=${titre}::rapport publié en ${i} annotation(s)"
