# Moonsama cosmetics data

Static data extracted from the retired Moonsama Minecraft services (Minecraft API,
Multiverse Customizer, Composer) by `tools/legacy-extract/extract_cosmetics.py`.
Everything is keyed by Portal collection slug and token id. There are no player
identifiers of any kind (no Minecraft UUIDs, no legacy account ids, no Portal ids).

Do not edit the extracted files by hand; re-run the extractor instead.

## Contents

- `collections.json` — Portal collection ↔ legacy Composer collection / on-chain contract map.
- `skins/<collection>.jsonl` — Mojang-signed skin textures per NFT (`id`, `value`,
  `signature`, `model`). Apply with a `textures` profile property; no signing needed.
  Counts: moonsama 1,000, exosama 10,000, gromlin 3,333, moonsama-embassy 15.
- `item-skins.json` — tool skins (custom model data per vanilla material) and the Portal
  token that unlocks each one (68 entries). Unlock tokens live in
  the `moonsama-x` collection; token ids are identical on the Moonriver contract
  (1285 `0x1974eeaf317ecf792ff307f25a3521c35eecde86`) and its Exosama-network migration
  target (2109 `0x9984440fb82f1af013865141909276d26b86e303`).
- `offhands.json`, `hats.json` — cosmetic off-hand items and 3D hats of the legacy server
  (material, custom model data, unlocking Portal token). Curated by hand from the retired
  plugin sources rather than extracted, so they survive re-runs of the extractor.
- `game-passes.json`, `whale-buffs.json` — legacy access gates and buff allowlists mapped
  to Portal collections (entries with `collection: null` cannot be checked through Portal).
- `compositor/` — data for composing custom skins the way the Customizer did:
  `collections.json` (slots, assets, proxies, traits, unlock rules, render variants),
  `components/<collection>.json` (conditional texture fragments), `files/` (layer PNGs),
  `compositions/<collection>.jsonl` (each token's default slot values).
  727 components, 450 layer files.

## Known gaps

- `exosama-minecraft` components reference `cyber_wiring/v<n>.png`, but the Composer file
  table only ever held `cyber_wiring/cyber_wiring_v<n>.png`; the legacy renderer never
  had those layers either. Treat the `cyber_wiring` slot as a no-op or alias the files.
- Per-token `bird_head` hat textures were never stored anywhere recoverable.
- Gromlins have no slots, assets or default compositions in the Composer; their skins were
  produced elsewhere and only exist as the pre-signed textures in `skins/gromlin.jsonl`.
  `skin-compositor` therefore cannot re-compose them.
