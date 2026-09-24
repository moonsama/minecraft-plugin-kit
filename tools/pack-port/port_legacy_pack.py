#!/usr/bin/env python3
"""Port the cosmetics of the legacy Moonsama resource pack into ``resourcepack/src``.

Reads the item definitions of the retired pack (``references/minecraft-resourcepacks-old``,
maintainers only) and copies exactly the models and textures needed for the cosmetics the
kit exposes:

* ``cosmetics-data/item-skins.json`` — weapon/tool skins
* ``cosmetics-data/offhands.json``  — cosmetic off-hand items
* ``cosmetics-data/hats.json``      — 3D hats
* ``cosmetics-data/offhand-states.json`` — alternate looks the off-hand perks switch to
  (cooldown buzzers, charge stages, Detectore glow) and the Moonsama-owned sounds they play

Everything else in the legacy pack (blocks, gameplay items, third-party music, menus) is
left behind.
The generated ``assets/minecraft/items/<material>.json`` files are ``range_dispatch`` item
model definitions on ``custom_model_data`` (floats index 0), which is how the plugins
select a skin; the legacy custom model data numbers are preserved.

Usage: ``python3 tools/pack-port/port_legacy_pack.py``  (idempotent; regenerates the
files listed in ``resourcepack/src/.ported-files``).
"""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
LEGACY = REPO_ROOT / "references" / "minecraft-resourcepacks-old"
DATA = REPO_ROOT / "cosmetics-data"
OUT = REPO_ROOT / "resourcepack" / "src"
MANIFEST = OUT / ".ported-files"

# Hand-written pack content that must survive regeneration (material -> entries).
EXTRA_ENTRIES: dict[str, list[dict]] = {
    "feather": [
        {
            "threshold": 9001.0,
            "model": {"type": "minecraft:model", "model": "moonsama:item/portal_relic"},
        }
    ],
}

# Texture directories the vanilla "blocks" atlas already covers.
DEFAULT_ATLAS_DIRS = {"block", "item", "entity", "particle"}


def material_key(identifier: str) -> str:
    return identifier.split(":", 1)[1] if ":" in identifier else identifier


def wanted_thresholds() -> dict[str, set[float]]:
    wanted: dict[str, set[float]] = {}

    for skin in json.loads((DATA / "item-skins.json").read_text()):
        for material in skin["materials"]:
            wanted.setdefault(material_key(material), set()).add(float(skin["customModelData"]))

    offhands = json.loads((DATA / "offhands.json").read_text())["offhands"]
    for offhand in offhands:
        wanted.setdefault(material_key(offhand["material"]), set()).add(float(offhand["customModelData"]))

    hats = json.loads((DATA / "hats.json").read_text())
    for rule in hats["rules"]:
        wanted.setdefault(material_key(hats["material"]), set()).add(float(rule["customModelData"]))

    for state in offhand_states()["states"]:
        base = int(state["customModelData"])
        for offset in range(int(state.get("count", 1))):
            wanted.setdefault(material_key(state["material"]), set()).add(float(base + offset))
    return wanted


def offhand_states() -> dict:
    return json.loads((DATA / "offhand-states.json").read_text())


def model_refs(node, out: set[str]) -> None:
    """Collect every ``"model": "ns:path"`` string inside an item model definition tree."""
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "model" and isinstance(value, str):
                out.add(value)
            else:
                model_refs(value, out)
    elif isinstance(node, list):
        for value in node:
            model_refs(value, out)


def split_ref(ref: str) -> tuple[str, str]:
    if ":" in ref:
        namespace, path = ref.split(":", 1)
    else:
        namespace, path = "minecraft", ref
    return namespace, path


class Porter:
    def __init__(self) -> None:
        self.copied: list[Path] = []
        self.missing: list[str] = []
        self.texture_dirs: set[str] = set()
        self.seen_models: set[str] = set()

    def clean_previous(self) -> None:
        if not MANIFEST.exists():
            return
        for line in MANIFEST.read_text().splitlines():
            target = OUT / line.strip()
            if target.is_file():
                target.unlink()
        for directory in sorted({p.parent for p in (OUT / l for l in MANIFEST.read_text().splitlines())},
                                key=lambda p: len(p.parts), reverse=True):
            while directory != OUT and directory.exists() and not any(directory.iterdir()):
                directory.rmdir()
                directory = directory.parent

    def write(self, relative: str, content: bytes) -> None:
        target = OUT / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        self.copied.append(Path(relative))

    def copy_file(self, relative: str) -> bool:
        source = LEGACY / relative
        if not source.is_file():
            return False
        target = OUT / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        self.copied.append(Path(relative))
        return True

    def copy_model(self, ref: str) -> None:
        if ref in self.seen_models:
            return
        self.seen_models.add(ref)
        namespace, path = split_ref(ref)
        relative = f"assets/{namespace}/models/{path}.json"
        source = LEGACY / relative
        if not source.is_file():
            if namespace != "minecraft":
                self.missing.append(f"model {ref}")
            return  # vanilla model
        model = json.loads(source.read_text())
        self.copy_file(relative)
        parent = model.get("parent")
        if isinstance(parent, str) and not parent.startswith("builtin/"):
            self.copy_model(parent)
        for texture in (model.get("textures") or {}).values():
            if isinstance(texture, str) and not texture.startswith("#"):
                self.copy_texture(texture)

    def copy_texture(self, ref: str) -> None:
        namespace, path = split_ref(ref)
        relative = f"assets/{namespace}/textures/{path}.png"
        if not (LEGACY / relative).is_file():
            if namespace != "minecraft":
                self.missing.append(f"texture {ref}")
            return
        self.copy_file(relative)
        self.copy_file(relative + ".mcmeta")
        top = path.split("/", 1)[0] if "/" in path else ""
        if top and top not in DEFAULT_ATLAS_DIRS:
            self.texture_dirs.add(top)

    def port_material(self, material: str, thresholds: set[float]) -> None:
        legacy_file = LEGACY / "assets" / "minecraft" / "items" / f"{material}.json"
        entries: list[dict] = []
        fallback = {"type": "minecraft:model", "model": f"minecraft:item/{material}"}
        if legacy_file.is_file():
            legacy = json.loads(legacy_file.read_text())["model"]
            if legacy.get("type", "").endswith("range_dispatch"):
                by_threshold: dict[float, dict] = {}
                for entry in legacy.get("entries", []):
                    # The legacy pack has a few duplicated thresholds (e.g. iron_sword 60);
                    # the first entry is the one the item state actually used.
                    by_threshold.setdefault(float(entry["threshold"]), entry)
                for threshold in sorted(thresholds):
                    entry = by_threshold.get(threshold)
                    if entry is None:
                        self.missing.append(f"{material} custom_model_data {threshold:g}")
                    else:
                        entries.append(entry)
                if "fallback" in legacy:
                    fallback = legacy["fallback"]
        else:
            for threshold in sorted(thresholds):
                self.missing.append(f"{material} custom_model_data {threshold:g} (no legacy definition)")
        entries.extend(EXTRA_ENTRIES.get(material, []))
        entries.sort(key=lambda e: float(e["threshold"]))
        if not entries:
            return
        refs: set[str] = set()
        model_refs(entries, refs)
        model_refs(fallback, refs)
        for ref in sorted(refs):
            if ref.startswith("moonsama:item/portal_relic"):
                continue  # hand-written, lives in the pack already
            self.copy_model(ref)
        definition = {
            "model": {
                "type": "minecraft:range_dispatch",
                "property": "minecraft:custom_model_data",
                "entries": entries,
                "fallback": fallback,
            }
        }
        self.write(f"assets/minecraft/items/{material}.json",
                   (json.dumps(definition, indent=2) + "\n").encode())

    def port_sounds(self, events: list[str]) -> None:
        """Copy the listed sound events (and their .ogg files) from the legacy pack, per namespace."""
        by_namespace: dict[str, list[str]] = {}
        for event in events:
            namespace, name = event.split(":", 1) if ":" in event else (event.split(".", 1)[0], event)
            by_namespace.setdefault(namespace, []).append(name)
        for namespace, names in sorted(by_namespace.items()):
            source = LEGACY / "assets" / namespace / "sounds.json"
            if not source.is_file():
                self.missing.extend(f"sound {namespace}:{n}" for n in names)
                continue
            legacy = json.loads(source.read_text())
            ported: dict[str, dict] = {}
            for name in names:
                definition = legacy.get(name)
                if definition is None:
                    self.missing.append(f"sound {namespace}:{name}")
                    continue
                ported[name] = definition
                for sound in definition.get("sounds", []):
                    ref = sound["name"] if isinstance(sound, dict) else sound
                    sound_ns, path = split_ref(ref)
                    if not self.copy_file(f"assets/{sound_ns}/sounds/{path}.ogg"):
                        self.missing.append(f"sound file {ref}")
            if ported:
                self.write(f"assets/{namespace}/sounds.json", (json.dumps(ported, indent=2) + "\n").encode())

    def write_atlas(self) -> None:
        if not self.texture_dirs:
            return
        atlas = {"sources": [{"type": "directory", "source": d, "prefix": f"{d}/"} for d in sorted(self.texture_dirs)]}
        self.write("assets/minecraft/atlases/blocks.json", (json.dumps(atlas, indent=2) + "\n").encode())

    def write_manifest(self) -> None:
        MANIFEST.write_text("".join(f"{p.as_posix()}\n" for p in sorted(set(self.copied))))


def main() -> int:
    if not LEGACY.is_dir():
        print(f"legacy pack not found at {LEGACY}", file=sys.stderr)
        return 1
    porter = Porter()
    porter.clean_previous()
    wanted = wanted_thresholds()
    for material in sorted(wanted):
        porter.port_material(material, wanted[material])
    for material in EXTRA_ENTRIES:
        if material not in wanted:
            porter.port_material(material, set())
    porter.port_sounds([s["event"] for s in offhand_states().get("sounds", [])])
    porter.write_atlas()
    porter.write_manifest()
    print(f"ported {len(set(porter.copied))} files for {len(wanted)} materials")
    if porter.missing:
        print("missing in legacy pack:")
        for item in sorted(set(porter.missing)):
            print(f"  - {item}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
