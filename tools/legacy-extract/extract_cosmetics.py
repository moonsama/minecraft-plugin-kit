#!/usr/bin/env python3
"""Build the static ``cosmetics-data`` package from the legacy Moonsama dumps.

Inputs (all local, all read-only):

* MySQL ``mc_backend.minecraft_skin`` — Mojang-signed textures per Composer token
* Composer Postgres — token ids, compositions, components, slots, assets, traits
* Customizer Postgres — asset unlock rules (legacy contract + token id)
* ``references/archive`` — layer PNGs archived by ``archive_assets.py``
* ``references/mcapi_public_static_data`` — legacy item skin / game pass / whale buff JSON
* ``references/skin_metas`` — Moonsama metadata with signed skins (cross-check only)

Output: ``cosmetics-data/`` at the repository root. Everything in there is keyed by
Portal collection + token id; no player identifiers of any kind are emitted.

Usage:
    python3 tools/legacy-extract/extract_cosmetics.py
"""

from __future__ import annotations

import base64
import json
import re
import shutil
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from legacydb import REPO_ROOT, LegacyDatabases  # noqa: E402

ARCHIVE = REPO_ROOT / "references" / "archive"
STATIC_DATA = REPO_ROOT / "references" / "mcapi_public_static_data" / "data"
SKIN_METAS = REPO_ROOT / "references" / "skin_metas"
OUT = REPO_ROOT / "cosmetics-data"

# Composer collection referenceId -> Portal collection. Only collections that exist in the
# Portal catalog are exported; the rest of the Composer catalogue is intentionally dropped.
PORTAL_COLLECTIONS = {
    "moonsama": "moonsama",
    "exosama": "exosama",
    "gromlins": "gromlin",
    "moonsama-embassy": "moonsama-embassy",
}

# Legacy on-chain contracts -> Portal collection. The Moonriver Moonsama X contract and the
# Exosama-network Multiverse Items contract hold the same items under the same token ids
# (the migration preserved id parity; verified against Portal token metadata), so both map
# to the single Portal collection `moonsama-x`.
LEGACY_CONTRACTS = {
    (1285, "0x1974eeaf317ecf792ff307f25a3521c35eecde86"): {"portal": "moonsama-x", "note": "Moonsama X (Moonriver)"},
    (2109, "0x9984440fb82f1af013865141909276d26b86e303"): {
        "portal": "moonsama-x",
        "note": "Multiverse Items (Exosama network); token ids mirror the Moonriver twin",
    },
    (1285, "0xb654611f84a8dc429ba3cb4fda9fad236c505a1a"): {"portal": "moonsama", "note": "Moonsama (Moonriver)"},
    (2109, "0xf5211d1e02939856da995965ef7b24dc60cc472e"): {"portal": "moonsama", "note": "Moonsama (Exosama network)"},
    (1, "0xac5c7493036de60e63eb81c5e9a440b42f47ebf5"): {"portal": "exosama", "note": "Exosama (Ethereum)"},
    (1284, "0xf27a6c72398eb7e25543d19fda370b7083474735"): {"portal": "gromlin", "note": "Gromlins (Moonbeam)"},
    (1285, "0x0a54845ac3743c96e582e03f26c3636ea9c00c8a"): {"portal": "moonsama-embassy", "note": "Moonsama Embassy (Moonriver)"},
    (2109, "0xc630f52a35cfde19122ccd822f1ba00be6fd2e71"): {"portal": None, "note": "Multiverse Costumes; not in Portal"},
    (2109, "0x5cb76be66792a48bdc96676224cda8bf1df611d4"): {"portal": None, "note": "Multiverse Backgrounds; not in Portal"},
    (2109, "0xc2f48a85903d8a6c5276a4f63f75240c355f716c"): {"portal": None, "note": "Multiverse Avatars (Exosama network); not in Portal"},
    (1285, "0xdea45e7c6944cb86a268661349e9c013836c79a2"): {"portal": None, "note": "Multiverse Avatars (Moonriver); not in Portal"},
}

# Composer collections whose Minecraft render components are exported. These are the
# Minecraft variants of the Portal collections plus the shared costume/item layers.
COMPOSITOR_COLLECTIONS = {
    "moonsama-minecraft",
    "exosama-minecraft",
    "gromlins-minecraft",
    "moonsama-embassy-minecraft",
    "default-minecraft",
    "multiverse-costumes-minecraft-moonsama",
    "multiverse-costumes-minecraft-exosama",
    "multiverse-costumes-generic-minecraft",
    "multiverse-items-minecraft",
}

# Item skins in the legacy JSON omit the chain; every real gate was the Multiverse Items
# contract, plus one birthday token contract that never existed on chain.
ITEM_SKIN_ADDRESS_CHAIN = {"0x9984440fb82f1af013865141909276d26b86e303": 2109}

# Mojang texture hashes are SHA-256 hex, occasionally printed without leading zeros.
TEXTURE_HOST = re.compile(r"^https?://textures\.minecraft\.net/texture/[0-9a-f]{56,64}$")


def write_json(path: Path, payload) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=1, ensure_ascii=False, sort_keys=False) + "\n")


def write_jsonl(path: Path, rows) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with path.open("w") as handle:
        for row in rows:
            handle.write(json.dumps(row, separators=(",", ":"), ensure_ascii=False) + "\n")
            count += 1
    return count


def decode_texture(value: str) -> dict:
    payload = json.loads(base64.b64decode(value))
    skin = payload["textures"]["SKIN"]
    if not TEXTURE_HOST.match(skin["url"]):
        raise ValueError(f"unexpected texture url {skin['url']}")
    return {"url": skin["url"], "model": (skin.get("metadata") or {}).get("model", "classic")}


def parse_pg_array(value: str) -> list[str]:
    value = (value or "").strip()
    if not value or value == "{}":
        return []
    return [part.strip('"') for part in value.strip("{}").split(",") if part]


def load_json_text(value: str, default):
    if not value:
        return default
    try:
        return json.loads(value)
    except json.JSONDecodeError:
        return default


# --------------------------------------------------------------------------- signed skins


def export_signed_skins(db: LegacyDatabases) -> dict[str, int]:
    tokens = db.composer.rows(
        """
        select t.id, c."referenceId" as collection, t."assetId" as asset_id
        from composable_token_entity t
        join composable_collection_entity c on c.id = t."composableCollectionId"
        where c."referenceId" in (%s)
        """
        % ", ".join(f"'{c}'" for c in PORTAL_COLLECTIONS)
    )
    token_index = {row["id"]: (row["collection"], int(row["asset_id"])) for row in tokens}

    skins = db.minecraft_api.rows(
        "SELECT id, variant, texture_data, texture_signature FROM minecraft_skin "
        "WHERE source = 'token' AND texture_data IS NOT NULL AND texture_signature IS NOT NULL"
    )
    per_collection: dict[str, dict[int, dict]] = defaultdict(dict)
    problems = Counter()
    for row in skins:
        located = token_index.get(row["id"])
        if not located:
            continue
        collection, asset_id = located
        try:
            decoded = decode_texture(row["texture_data"])
        except Exception:  # noqa: BLE001
            problems[f"{collection}:undecodable"] += 1
            continue
        if decoded["model"] != (row["variant"] or decoded["model"]):
            problems[f"{collection}:variant-mismatch"] += 1
        entry = {
            "id": asset_id,
            "value": row["texture_data"],
            "signature": row["texture_signature"],
            "model": decoded["model"],
        }
        existing = per_collection[collection].get(asset_id)
        if existing and existing["value"] != entry["value"]:
            problems[f"{collection}:duplicate-asset-id"] += 1
        per_collection[collection].setdefault(asset_id, entry)

    # Cross-check Moonsama against the metadata drops (independent source of the same signatures).
    if SKIN_METAS.exists():
        mismatches = 0
        for meta_file in SKIN_METAS.glob("*.json"):
            meta = json.loads(meta_file.read_text())
            signed = (meta.get("minecraft") or {}).get("skin") or {}
            ours = per_collection["moonsama"].get(int(meta["id"]))
            if not ours:
                per_collection["moonsama"][int(meta["id"])] = {
                    "id": int(meta["id"]),
                    "value": signed["value"],
                    "signature": signed["signature"],
                    "model": decode_texture(signed["value"])["model"],
                }
                problems["moonsama:filled-from-skin-metas"] += 1
            elif ours["value"] != signed["value"]:
                mismatches += 1
        if mismatches:
            problems["moonsama:skin-metas-differ"] = mismatches

    counts = {}
    for collection, portal in PORTAL_COLLECTIONS.items():
        rows = [per_collection[collection][k] for k in sorted(per_collection[collection])]
        counts[portal] = write_jsonl(OUT / "skins" / f"{portal}.jsonl", rows)
    if problems:
        print("signed skins notes:", dict(problems))
    return counts


# --------------------------------------------------------------------------- ownership map


def export_collections() -> None:
    payload = {
        "portalCollections": {
            portal: {"composerCollection": composer} for composer, portal in PORTAL_COLLECTIONS.items()
        },
        "legacyContracts": [
            {"chainId": chain, "address": address, **info} for (chain, address), info in LEGACY_CONTRACTS.items()
        ],
    }
    write_json(OUT / "collections.json", payload)


def portal_requirement(chain_id: int | None, address: str, asset_id) -> dict:
    address = address.lower()
    if chain_id is None:
        chain_id = ITEM_SKIN_ADDRESS_CHAIN.get(address)
    info = LEGACY_CONTRACTS.get((chain_id, address)) if chain_id is not None else None
    legacy = {"chainId": chain_id, "address": address, "tokenId": asset_id}
    if info and info["portal"]:
        return {"collection": info["portal"], "tokenId": asset_id, "legacy": legacy}
    return {"collection": None, "tokenId": asset_id, "legacy": legacy}


# --------------------------------------------------------------------------- item skins / gates


def export_item_skins() -> int:
    legacy = json.loads((STATIC_DATA / "data-itemskins.json").read_text())
    result = []
    for entry in legacy:
        asset = entry.get("asset")
        requirement = None
        if asset:
            requirement = portal_requirement(asset.get("chainId"), asset["assetAddress"], int(asset["assetId"]))
        result.append(
            {
                "identifier": entry["identifier"],
                "name": entry["name"],
                "description": entry.get("description", ""),
                "rarity": entry.get("rarity", 0),
                "group": entry["group"],
                "customModelData": entry["customModelData"],
                "materials": entry.get("materials", []),
                "requirement": requirement,
            }
        )
    write_json(OUT / "item-skins.json", result)
    return len(result)


def export_game_passes() -> int:
    legacy = json.loads((STATIC_DATA / "global" / "data-gamePasses.json").read_text())
    result = []
    for entry in legacy["collections"]:
        pattern = entry.get("assetId", "*")
        info = LEGACY_CONTRACTS.get((int(entry["chainId"]), entry["assetAddress"].lower()))
        result.append(
            {
                "note": entry.get("note", ""),
                "collection": info["portal"] if info else None,
                "tokenIdPattern": pattern,
                "legacy": {"chainId": int(entry["chainId"]), "address": entry["assetAddress"].lower()},
            }
        )
    write_json(OUT / "game-passes.json", {"anyOf": result})
    return len(result)


def export_whale_buffs() -> int:
    legacy = json.loads((STATIC_DATA / "global" / "data-whaleBuffs.json").read_text())
    result = [
        {"composerCollection": ref, "collection": PORTAL_COLLECTIONS.get(ref)} for ref in legacy["collections"]
    ]
    write_json(
        OUT / "whale-buffs.json",
        {
            "entitledSkinCollections": result,
            "power": {"moonsama": 10, "moonsamaNeonBonus": 100, "exosama": 1},
        },
    )
    return len(result)


# --------------------------------------------------------------------------- compositor


def export_compositor(db: LegacyDatabases) -> dict[str, int]:
    dump = ARCHIVE / "composer-db"
    if not dump.exists():
        raise SystemExit("run archive_assets.py first (references/archive/composer-db missing)")
    load = lambda name: json.loads((dump / f"{name}.json").read_text())  # noqa: E731

    collections = {row["referenceId"]: row for row in load("collections")}
    by_id = {row["id"]: row["referenceId"] for row in load("collections")}
    render_variants = [
        r
        for r in load("render_variants")
        if r["renderType"] == "minecraft" and r["variant_collection"] in COMPOSITOR_COLLECTIONS
    ]
    variant_of = {r["collection"]: r["variant_collection"] for r in render_variants}

    components = defaultdict(list)
    for row in load("components"):
        if row["collection"] not in COMPOSITOR_COLLECTIONS:
            continue
        components[row["collection"]].append(
            {
                "referenceId": row["referenceId"],
                "states": load_json_text(row["states"], {}),
                "config": load_json_text(row["config"], {}),
            }
        )
    result_types = defaultdict(dict)
    for row in load("result_types"):
        result_types[row["collection"]][row["renderType"]] = row["component"]

    slots = defaultdict(list)
    for row in load("slots"):
        slots[row["collection"]].append(
            {
                "referenceId": row["referenceId"],
                "type": row["type"],
                "customizable": row["customizable"] == "t",
                "required": row["required"] == "t",
                "parentSlot": row["parent_slot"] or None,
            }
        )
    slot_permissions = defaultdict(list)
    for row in load("slot_permissions"):
        slot_permissions[row["collection"]].append(
            {
                "slot": row["slot"],
                "sourceCollection": by_id.get(row["composableCollectionId"]),
                "renderTypeSuffix": row["renderTypeSuffix"] or "",
                "config": load_json_text(row["config"], {}),
            }
        )
    assets = defaultdict(list)
    for row in load("assets"):
        assets[row["collection"]].append(
            {
                "referenceId": row["referenceId"],
                "name": row["name"],
                "tags": parse_pg_array(row["tags"]),
                "groups": parse_pg_array(row["groups"]),
            }
        )
    proxies = defaultdict(list)
    for row in load("asset_proxies"):
        proxies[row["collection"]].append({"referenceId": row["referenceId"], "tags": parse_pg_array(row["tags"])})
    traits = defaultdict(list)
    for row in load("traits"):
        traits[row["collection"]].append(
            {
                "traitType": row["traitType"],
                "isMainTrait": row["isMainTrait"] == "t",
                "config": load_json_text(row["config"], {}),
            }
        )

    unlocks = defaultdict(list)
    for row in json.loads((dump / "customizer_assets.json").read_text()):
        unlock = load_json_text(row["unlock"], None)
        if unlock is None:
            continue
        unlocks[row["collection"]].append({"referenceId": row["referenceId"], "unlock": unlock})

    minecraft_collections = sorted(components)
    involved = set(minecraft_collections) | set(variant_of) | {"multiverse-costumes", "multiverse-items", "multiverse-backgrounds"}
    involved = {c for c in involved if c in collections}

    write_json(
        OUT / "compositor" / "collections.json",
        {
            "renderVariants": variant_of,
            "collections": {
                ref: {
                    "type": collections[ref]["type"],
                    "parentCollection": by_id.get(collections[ref]["parentCollectionId"]),
                    "resultTypes": result_types.get(ref, {}),
                    "slots": slots.get(ref, []),
                    "slotPermissions": slot_permissions.get(ref, []),
                    "assets": assets.get(ref, []),
                    "assetProxies": proxies.get(ref, []),
                    "traits": traits.get(ref, []),
                    "unlockRules": unlocks.get(ref, []),
                }
                for ref in sorted(involved)
            },
        },
    )
    for ref in minecraft_collections:
        write_json(OUT / "compositor" / "components" / f"{ref}.json", components[ref])

    # Default compositions per Portal token (the look a token has before customization).
    composition_counts = {}
    tokens = defaultdict(list)
    for row in load("tokens"):
        portal = PORTAL_COLLECTIONS.get(row["collection"])
        if not portal:
            continue
        tokens[portal].append({"id": int(row["assetId"]), "slots": load_json_text(row["defaultComposition"], [])})
    for portal, rows in tokens.items():
        deduped = {row["id"]: row for row in rows}
        composition_counts[portal] = write_jsonl(
            OUT / "compositor" / "compositions" / f"{portal}.jsonl", [deduped[k] for k in sorted(deduped)]
        )

    # Layer files: copy only the images referenced by texture elements of the minecraft components.
    referenced = defaultdict(set)
    for ref, rows in components.items():
        for component in rows:
            for element in component["config"].get("elements", []) or []:
                for url in (element.get("url"), element.get("mask_url")):
                    if url and not str(url).startswith("#") and not str(url).endswith(".glb"):
                        referenced[ref].add(url)
                for layer in element.get("layers", []) or []:
                    url = layer.get("url")
                    if url and not str(url).startswith("#"):
                        referenced[ref].add(url)
    copied = 0
    missing = []
    files_root = OUT / "compositor" / "files"
    if files_root.exists():
        shutil.rmtree(files_root)
    for ref, paths in referenced.items():
        for rel in sorted(paths):
            source = ARCHIVE / "composer-files" / ref / rel
            if not source.exists():
                missing.append(f"{ref}/{rel}")
                continue
            target = files_root / ref / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            copied += 1
    if missing:
        print(f"compositor: {len(missing)} referenced files missing from archive, e.g. {missing[:5]}")
    return {"components": sum(len(v) for v in components.values()), "files": copied, **composition_counts}


# --------------------------------------------------------------------------- main


def write_readme(summary: dict) -> None:
    skins = ", ".join(f"{name} {count:,}" for name, count in summary.get("skins", {}).items())
    compositor = summary.get("compositor", {})
    text = f"""# Moonsama cosmetics data

Static data extracted from the retired Moonsama Minecraft services (Minecraft API,
Multiverse Customizer, Composer) by `tools/legacy-extract/extract_cosmetics.py`.
Everything is keyed by Portal collection slug and token id. There are no player
identifiers of any kind (no Minecraft UUIDs, no legacy account ids, no Portal ids).

Do not edit by hand; re-run the extractor instead.

## Contents

- `collections.json` — Portal collection ↔ legacy Composer collection / on-chain contract map.
- `skins/<collection>.jsonl` — Mojang-signed skin textures per NFT (`id`, `value`,
  `signature`, `model`). Apply with a `textures` profile property; no signing needed.
  Counts: {skins}.
- `item-skins.json` — tool skins (custom model data per vanilla material) and the Portal
  token that unlocks each one ({summary.get('itemSkins')} entries). Unlock tokens live in
  the `moonsama-x` collection; token ids are identical on the Moonriver contract
  (1285 `0x1974eeaf317ecf792ff307f25a3521c35eecde86`) and its Exosama-network migration
  target (2109 `0x9984440fb82f1af013865141909276d26b86e303`).
- `game-passes.json`, `whale-buffs.json` — legacy access gates and buff allowlists mapped
  to Portal collections (entries with `collection: null` cannot be checked through Portal).
- `compositor/` — data for composing custom skins the way the Customizer did:
  `collections.json` (slots, assets, proxies, traits, unlock rules, render variants),
  `components/<collection>.json` (conditional texture fragments), `files/` (layer PNGs),
  `compositions/<collection>.jsonl` (each token's default slot values).
  {compositor.get('components')} components, {compositor.get('files')} layer files.

## Known gaps

- `exosama-minecraft` components reference `cyber_wiring/v<n>.png`, but the Composer file
  table only ever held `cyber_wiring/cyber_wiring_v<n>.png`; the legacy renderer never
  had those layers either. Treat the `cyber_wiring` slot as a no-op or alias the files.
- Per-token `bird_head` hat textures were never stored anywhere recoverable.
"""
    (OUT / "README.md").write_text(text)


def main() -> None:
    db = LegacyDatabases.from_env()
    OUT.mkdir(parents=True, exist_ok=True)
    summary = {}
    summary["skins"] = export_signed_skins(db)
    export_collections()
    summary["itemSkins"] = export_item_skins()
    summary["gamePasses"] = export_game_passes()
    summary["whaleBuffs"] = export_whale_buffs()
    summary["compositor"] = export_compositor(db)
    write_readme(summary)
    print(json.dumps(summary, indent=1))


if __name__ == "__main__":
    main()
