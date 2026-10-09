#!/usr/bin/env python3
"""MISSA TV — test des chaînes et publication du catalogue.

À COPIER dans le dépôt privé missa-tv-config sous scripts/build_catalog.py.

Lit portal-config.json (toutes les playlists M3U), télécharge chaque liste,
teste chaque chaîne (requête HTTP bornée) et écrit catalog.json : seules les
chaînes qui répondent sont conservées, triées par nom, avec leur groupe
(type) et leur pays quand la liste les fournit.

Standard library uniquement : aucune dépendance à installer.
"""

import json
import re
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from urllib.parse import urlparse

CONFIG = "portal-config.json"
SORTIE = "catalog.json"
UA = "MISSA-TV-Catalog/1.0"
DELAI_LISTE_S = 30          # téléchargement d'une liste M3U
DELAI_CHAINE_S = 4          # test d'une chaîne (premières octets)
OCTETS_MIN = 1              # une chaîne OK reçoit au moins 1 octet
MAX_CHAINES_PAR_SOURCE = 1500
TRAVAILLEURS = 32

EXTINF = re.compile(r"^#EXTINF:\s*(?P<duree>-?\d+)?\s*(?P<attrs>[^,]*),(?P<nom>.*)$")
ATTRIBUT = re.compile(r'([\w-]+)="([^"]*)"')


def masque(url: str) -> str:
    """Journalisation sans identifiants : hôte et port seulement."""
    try:
        p = urlparse(url)
        return f"{p.scheme}://{p.hostname or '?'}:{p.port or ''}/***"
    except Exception:
        return "***"


def telecharge(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=DELAI_LISTE_S) as r:
        return r.read().decode("utf-8", errors="replace")


def teste_chaine(url: str) -> bool:
    """Une chaîne fonctionne si elle répond 200 et envoie des octets."""
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=DELAI_CHAINE_S) as r:
            return r.status == 200 and len(r.read(OCTETS_MIN)) >= OCTETS_MIN
    except Exception:
        return False


def parse_m3u(texte: str):
    """Rend la liste des chaînes (nom, url, groupe, pays, logo) d'un M3U."""
    chaines = []
    attrs = {}
    nom = ""
    for ligne in texte.splitlines():
        ligne = ligne.strip()
        if not ligne:
            continue
        if ligne.startswith("#EXTINF"):
            m = EXTINF.match(ligne)
            attrs = dict(ATTRIBUT.findall(m.group("attrs") or "")) if m else {}
            nom = (m.group("nom") or "").strip() if m else ""
        elif not ligne.startswith("#"):
            chaines.append({
                "name": nom or ligne,
                "url": ligne,
                "group": attrs.get("group-title", "").strip(),
                "country": attrs.get("tvg-country", "").strip(),
                "logo": attrs.get("tvg-logo", "").strip(),
            })
            attrs, nom = {}, ""
    return chaines


def traite_source(source_id: str, url: str):
    """Télécharge une liste, teste ses chaînes, rend les statistiques."""
    print(f"::group::source {source_id} {masque(url)}")
    try:
        chaines = parse_m3u(telecharge(url))
    except Exception as e:
        print(f"liste injoignable : {type(e).__name__}")
        print("::endgroup::")
        return {"id": source_id, "tested": 0, "ok": 0}, []
    if len(chaines) > MAX_CHAINES_PAR_SOURCE:
        print(f"plafond appliqué : {len(chaines)} -> {MAX_CHAINES_PAR_SOURCE}")
        chaines = chaines[:MAX_CHAINES_PAR_SOURCE]

    ok = []
    with ThreadPoolExecutor(TRAVAILLEURS) as pool:
        futurs = {pool.submit(teste_chaine, c["url"]): c for c in chaines}
        for fut in as_completed(futurs):
            if fut.result():
                ok.append(futurs[fut])
    print(f"testées={len(chaines)} ok={len(ok)}")
    print("::endgroup::")
    stats = {"id": source_id, "tested": len(chaines), "ok": len(ok)}
    return stats, ok


def main() -> int:
    with open(CONFIG, encoding="utf-8") as f:
        config = json.load(f)

    sources, chaines, vus = [], [], set()
    for playlist in config.get("playlists", []):
        stats, ok = traite_source(playlist.get("id", "?"), playlist.get("url", ""))
        sources.append(stats)
        for c in ok:
            if c["url"] in vus:
                continue
            vus.add(c["url"])
            c2 = dict(c)
            c2["id"] = f"{stats['id']}-{len(chaines):05d}"
            chaines.append(c2)

    chaines.sort(key=lambda c: c["name"].lower())
    catalogue = {
        "schemaVersion": 1,
        "updatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "sources": sources,
        "channels": chaines,
    }
    with open(SORTIE, "w", encoding="utf-8") as f:
        json.dump(catalogue, f, ensure_ascii=False, indent=2)
    total_ok = sum(s["ok"] for s in sources)
    print(f"catalog.json : {total_ok} chaînes fonctionnelles, {len(sources)} source(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
