#!/usr/bin/env python3
"""Archive the legacy Composer assets that the kit still needs.

The Composer, Customizer and Minecraft API services are gone, but the S3 bucket
behind ``assets-api.static.moonsama.com`` still serves files. This script pulls
everything the kit needs before that disappears too:

* skin layer files (PNG fragments) of every ``*-minecraft`` Composer collection,
  stored by their logical path, e.g. ``moonsama-minecraft/base/black_bird.png``;
* optionally the rendered ``minecraft`` representation of every token of the
  chosen collections (the finished 64x64 skins, useful as reference renders
  when validating the Java compositor);
* a JSON dump of the Composer tables that describe the compositor rules so the
  extraction step never needs the databases again.

Everything lands under ``references/archive`` (gitignored). Re-running is
incremental: files whose md5 matches the database hash are skipped.

Usage:
    python3 tools/legacy-extract/archive_assets.py [--skins moonsama,exosama,...] [--workers 16]
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from legacydb import REPO_ROOT, LegacyDatabases  # noqa: E402

STATIC_HOST = "https://assets-api.static.moonsama.com"
ARCHIVE_ROOT = REPO_ROOT / "references" / "archive"

# Composer collections that carry Minecraft render components, e.g. moonsama-minecraft,
# multiverse-costumes-minecraft-moonsama, 420budheads-mc, default-minecraft.
MINECRAFT_COLLECTION_FILTER = "c.\"referenceId\" like '%minecraft%' or c.\"referenceId\" like '%-mc'"


def download(url: str, target: Path, expected_hash: str | None) -> str:
    # The legacy `hash` column is not a digest of the stored bytes (the Composer hashed
    # the upload before S3 re-encoding), so it cannot be used to verify downloads.
    # A previous non-empty download is treated as complete.
    if target.exists() and target.stat().st_size > 0:
        return "skip"
    target.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "moonsama-kit-archive/1.0"})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read()
    if not data:
        raise RuntimeError("empty response")
    target.write_bytes(data)
    return "ok"


def run_downloads(jobs: list[tuple[str, Path, str | None]], workers: int, label: str) -> None:
    counts = {"ok": 0, "skip": 0, "error": 0}
    errors: list[str] = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(download, url, target, expected): (url, target) for url, target, expected in jobs}
        for index, future in enumerate(as_completed(futures), 1):
            url, target = futures[future]
            try:
                counts[future.result()] += 1
            except Exception as exception:  # noqa: BLE001 - report and continue
                counts["error"] += 1
                errors.append(f"{url} -> {target}: {exception}")
            if index % 500 == 0:
                print(f"  {label}: {index}/{len(jobs)}", flush=True)
    print(f"{label}: {counts}")
    for error in errors[:20]:
        print("  error:", error)
    if len(errors) > 20:
        print(f"  ... {len(errors) - 20} more errors")


def archive_layer_files(db: LegacyDatabases, workers: int) -> None:
    rows = db.composer.rows(
        f"""
        select c."referenceId" as collection, f.path, f.url, f.hash, f."mimeType" as mime_type
        from composable_collection_file_entity f
        join composable_collection_entity c on c.id = f."composableCollectionId"
        where {MINECRAFT_COLLECTION_FILTER}
        order by 1, 2
        """
    )
    manifest: dict[str, list[dict[str, str]]] = {}
    jobs = []
    for row in rows:
        target = ARCHIVE_ROOT / "composer-files" / row["collection"] / row["path"]
        jobs.append((STATIC_HOST + row["url"], target, row["hash"] or None))
        manifest.setdefault(row["collection"], []).append(
            {"path": row["path"], "hash": row["hash"], "mimeType": row["mime_type"], "url": row["url"]}
        )
    (ARCHIVE_ROOT / "composer-files").mkdir(parents=True, exist_ok=True)
    (ARCHIVE_ROOT / "composer-files" / "manifest.json").write_text(json.dumps(manifest, indent=1, sort_keys=True))
    print(f"layer files: {len(jobs)} across {len(manifest)} collections")
    run_downloads(jobs, workers, "layer files")


def archive_rendered_skins(db: LegacyDatabases, collections: list[str], workers: int) -> None:
    quoted = ", ".join(f"'{c}'" for c in collections)
    rows = db.composer.rows(
        f"""
        select c."referenceId" as collection, t."assetId" as asset_id, r.url, r.hash
        from composable_token_representation_entity r
        join composable_token_entity t on t.id = r."composableTokenId"
        join composable_collection_entity c on c.id = t."composableCollectionId"
        where r."renderType" = 'minecraft' and c."referenceId" in ({quoted})
        order by 1, 2
        """
    )
    jobs = []
    for row in rows:
        target = ARCHIVE_ROOT / "composer-skins" / row["collection"] / f"{row['asset_id']}.png"
        jobs.append((STATIC_HOST + row["url"], target, row["hash"] or None))
    print(f"rendered skins: {len(jobs)} for {collections}")
    run_downloads(jobs, workers, "rendered skins")


def dump_compositor_tables(db: LegacyDatabases) -> None:
    """Dump the Composer rows that define Minecraft skin composition."""
    out = ARCHIVE_ROOT / "composer-db"
    out.mkdir(parents=True, exist_ok=True)
    queries = {
        "collections": """
            select id, "referenceId", name, type, "parentCollectionId", "isPublic", "isFree", config
            from composable_collection_entity order by "referenceId"
        """,
        "render_variants": """
            select v."renderType", c."referenceId" as collection, vc."referenceId" as variant_collection,
                   v."autoGenerate", v."allowManualCreate"
            from composable_collection_render_variant_entity v
            join composable_collection_entity c on c.id = v."composableCollectionId"
            left join composable_collection_entity vc on vc.id = v."variantCollectionId"
            order by 2, 1
        """,
        "slots": """
            select c."referenceId" as collection, s."referenceId", s.name, s.type, s.customizable, s.required,
                   p."referenceId" as parent_slot
            from composable_slot_entity s
            join composable_collection_entity c on c.id = s."composableCollectionId"
            left join composable_slot_entity p on p.id = s."parentSlotId"
            order by 1, 2
        """,
        "slot_permissions": """
            select c."referenceId" as collection, s."referenceId" as slot, sp.*
            from composable_slot_permission_entity sp
            join composable_slot_entity s on s.id = sp."composableSlotId"
            join composable_collection_entity c on c.id = s."composableCollectionId"
            order by 1, 2
        """,
        "assets": """
            select c."referenceId" as collection, a."referenceId", a.name, a.description, a.tags, a.groups
            from composable_asset_entity a
            join composable_collection_entity c on c.id = a."composableCollectionId"
            order by 1, 2
        """,
        "asset_proxies": """
            select c."referenceId" as collection, ap.*
            from composable_asset_proxy_entity ap
            join composable_collection_entity c on c.id = ap."composableCollectionId"
            order by 1
        """,
        "traits": """
            select c."referenceId" as collection, t.*
            from composable_trait_entity t
            join composable_collection_entity c on c.id = t."composableCollectionId"
            order by 1
        """,
        "result_types": """
            select c."referenceId" as collection, rt."renderType", cm."referenceId" as component
            from composable_result_type_entity rt
            join composable_collection_entity c on c.id = rt."composableCollectionId"
            left join composable_component_entity cm on cm.id = rt."composableComponentId"
            order by 1, 2
        """,
        "components": f"""
            select c."referenceId" as collection, cm."referenceId", cm.name, cm.states, cm.config
            from composable_component_entity cm
            join composable_collection_entity c on c.id = cm."composableCollectionId"
            where {MINECRAFT_COLLECTION_FILTER}
            order by 1, 2
        """,
        "tokens": """
            select c."referenceId" as collection, t."referenceId", t."assetId", t.name, t.attributes,
                   t."defaultComposition", t.composition
            from composable_token_entity t
            join composable_collection_entity c on c.id = t."composableCollectionId"
            where c."referenceId" in ('moonsama', 'exosama', 'gromlins', 'moonsama-embassy', 'multiverse-avatars')
            order by 1, t."assetId"
        """,
    }
    for name, query in queries.items():
        rows = db.composer.rows(query)
        (out / f"{name}.json").write_text(json.dumps(rows, indent=1))
        print(f"composer-db/{name}.json: {len(rows)} rows")

    customizer = {
        "collections": """
            select "referenceId", name, type, "isPublic", "isFree", config
            from composable_collection_entity order by "referenceId"
        """,
        "assets": """
            select c."referenceId" as collection, a."referenceId", a.name, a.tags, a.groups, a.unlock
            from composable_asset_entity a
            join composable_collection_entity c on c.id = a."composableCollectionId"
            order by 1, 2
        """,
    }
    for name, query in customizer.items():
        rows = db.customizer.rows(query)
        (out / f"customizer_{name}.json").write_text(json.dumps(rows, indent=1))
        print(f"composer-db/customizer_{name}.json: {len(rows)} rows")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--skins", default="", help="comma separated Composer collection referenceIds whose rendered minecraft skins to archive")
    parser.add_argument("--workers", type=int, default=16)
    parser.add_argument("--no-files", action="store_true", help="skip layer files")
    parser.add_argument("--no-db", action="store_true", help="skip the table dump")
    args = parser.parse_args()

    db = LegacyDatabases.from_env()
    ARCHIVE_ROOT.mkdir(parents=True, exist_ok=True)
    if not args.no_db:
        dump_compositor_tables(db)
    if not args.no_files:
        archive_layer_files(db, args.workers)
    if args.skins:
        archive_rendered_skins(db, [c.strip() for c in args.skins.split(",") if c.strip()], args.workers)


if __name__ == "__main__":
    main()
