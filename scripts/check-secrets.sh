#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# MISSA TV — Détection de secrets et de fichiers interdits
#
# Exécuté :
#   - par la CI (.github/workflows/ci.yml) sur chaque push et chaque PR ;
#   - manuellement avant un commit :  ./scripts/check-secrets.sh
#
# Sortie 0 si le dépôt est propre, 1 sinon.
#
# Pour autoriser volontairement une ligne (exemple de documentation), ajouter
# le marqueur :   secret-scan:allow
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

# Fichiers autorisés à contenir les motifs eux-mêmes (définition des règles).
EXCLUDE_PATHS=(
  "scripts/check-secrets.sh"
)

# Lignes manifestement fictives ou masquées : jamais signalées.
#   - valeurs masquées (** / XX / XXXX) et mots-clés de documentation ;
#   - plages réservées par la RFC 5737 (192.0.2.0/24, 198.51.100.0/24,
#     203.0.113.0/24) et RFC 2606 (.invalid, .test) : jamais routables, elles
#     sont faites pour les exemples ; elles peuvent donc apparaître dans docs/.
PLACEHOLDER_REGEX='(\*\*|XXXX|exemple|example\.com|placeholder|TODO|FIXME|127\.0\.0\.1|0\.0\.0\.0|10\.0\.2\.2|192\.0\.2\.|198\.51\.100\.|203\.0\.113\.|\.invalid|\.test|(^|[^A-Za-z0-9])X{2,}([^A-Za-z0-9]|$))'

failures=0

echo "▸ Collecte des fichiers suivis…"
ALL_FILES=()
while IFS= read -r f; do
  [[ -n "$f" ]] && ALL_FILES+=("$f")
done < <(git ls-files)

FILES_TO_SCAN=()
for f in "${ALL_FILES[@]}"; do
  skip=0
  for ex in "${EXCLUDE_PATHS[@]}"; do
    [[ "$f" == "$ex" ]] && skip=1
  done
  [[ $skip -eq 0 && -f "$f" ]] && FILES_TO_SCAN+=("$f")
done

echo "  ${#FILES_TO_SCAN[@]} fichier(s) suivi(s) sur ${#ALL_FILES[@]}"

if [[ ${#FILES_TO_SCAN[@]} -eq 0 ]]; then
  echo "  (rien à analyser)"
fi

# ── 1. Fichiers qui ne doivent jamais être versionnés ────────────────────────
echo "▸ Vérification des fichiers interdits…"
forbidden=0
for f in "${ALL_FILES[@]}"; do
  case "$f" in
    *.jks|*.keystore|*.p12|*.pepk|local.properties|keystore.properties|.env|*.env)
      echo "  ✗ fichier interdit versionné : $f"
      forbidden=$((forbidden + 1))
      ;;
  esac
done
if [[ $forbidden -eq 0 ]]; then
  echo "  ✓ aucun fichier interdit"
else
  failures=$((failures + forbidden))
fi

# ── 2. Motifs d'identifiants ─────────────────────────────────────────────────
# Format : "NOM|EXPRESSION RÉGULIÈRE|EXPLICATION"
PATTERNS=(
  'MAC|([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}|adresse MAC en clair'
  'TOKEN_GH|gh[pousr]_[A-Za-z0-9]{16,}|token GitHub'
  'TOKEN_GH_FINE|github_pat_[A-Za-z0-9_]{20,}|token GitHub fine-grained'
  'TOKEN_AWS|AKIA[0-9A-Z]{16}|clé AWS'
  'CLE_PRIVEE|-----BEGIN ([A-Z]+ )?PRIVATE KEY-----|clé privée'
  'PORTAIL_IP|https?://([0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{2,5}|URL de portail en IP:port'
  'PORTAIL_STALKER|https?://[^ "'"'"'/]+/c/|URL de portail Stalker (chemin /c/)'
)

echo "▸ Analyse des motifs sensibles…"
for entry in "${PATTERNS[@]}"; do
  IFS='|' read -r name regex label <<<"$entry"

  if [[ ${#FILES_TO_SCAN[@]} -eq 0 ]]; then
    hits=""
  else
    hits="$(grep -nIE -- "$regex" "${FILES_TO_SCAN[@]}" 2>/dev/null \
      | grep -v 'secret-scan:allow' \
      | grep -vE -- "$PLACEHOLDER_REGEX" || true)"
  fi

  if [[ -n "$hits" ]]; then
    echo "  ✗ [$name] $label"
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      echo "      ${line:0:160}"
    done <<<"$hits"
    failures=$((failures + 1))
  else
    echo "  ✓ $name"
  fi
done

echo
if [[ $failures -gt 0 ]]; then
  echo "✗ ÉCHEC : $failures problème(s) détecté(s)."
  echo "  Aucun identifiant réel ne doit être versionné dans ce dépôt."
  exit 1
fi

echo "✓ Aucun secret détecté."
exit 0
