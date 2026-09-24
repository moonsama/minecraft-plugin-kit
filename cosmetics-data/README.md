# Moonsama cosmetics data

Static data extracted from the retired Moonsama Minecraft services (Minecraft API,
Multiverse Customizer, Composer) by `tools/legacy-extract/extract_cosmetics.py`.
Everything is keyed by Portal collection slug and token id. There are no player
identifiers of any kind (no Minecraft UUIDs, no legacy account ids, no Portal ids).

Do not edit the extracted files by hand; re-run the extractor instead.

## License

Everything in this directory is licensed under the [Moonsama Asset License](../LICENSE-ASSETS),
not the Apache License that covers the code: you may bundle and redistribute it in software
that delivers each asset's utility to the current holder of the corresponding NFT, and for
nothing else. The Multiverse Art (Ethereum) avatars (`moonsama-multiverse-art-eth`) are
the work of nine independent artists who retain their copyright. Credit them where your
interface credits creators; `attributions.json` has the per-token mapping and the
`/skins` menu shows it.

| Tokens | Artist | Artist collection | Pieces |
| --- | --- | --- | --- |
| 1–3 | Yumi | Dreamscapes | The Moon | Nemesis, the Queen of Retribution, Erebus, the Horror of the Depths, Ares, the King of Wrath |
| 4–6 | Tiff (tiffdairyqueen) | tiffdairyqueen Originals | Venus Fighters #1, Venus Fighters #2, Venus Fighters #3 |
| 7–9 | Marlua | Marlua's Realm | Wizardnos, Archmage, Pyromancer |
| 10–12 | Blood Moon Clan | Blood Moon Clan | Blood Moon Overlord #1, Blood Moon Overlord #2, Blood Moon Overlord #3 |
| 13–15 | Majan | Komainu Lions | Lion Kabuki #1, Lion Kabuki #2, Lion Kabuki #3 |
| 16–18 | Kusama Kingdom | Medieval Moonsama | Moonsama Knights #1, Moonsama Knights #2, Moonsama Knights #3 |
| 19–21 | Wangdoodle | The Cheese Boys | Cheddar Fred, Mister Swiss, Parmesan John |
| 22–24 | Tako | Shiba Tales | Glitch, Tronica, El Rmrko |
| 25–27 | Ruben Topia | Moonsama Topia | Strawberry Topia, Psychedelic Topia, Shadow Topia |

Note that a Mojang-signed texture (`skins/*.jsonl`, `value`) embeds the profile id and
name of the Minecraft account it was signed on. These are the legacy service's own account
and MineSkin's generator accounts, not players.

## Contents

- `collections.json` — Portal collection ↔ legacy Composer collection / on-chain contract map.
- `attributions.json` — artist credit per token for artist-made collections
  (27 tokens).
- `skins/<collection>.jsonl` — Mojang-signed skin textures per NFT (`id`, `value`,
  `signature`, `model`). Apply with a `textures` profile property; no signing needed.
  Counts: moonsama 1,000, exosama 10,000, gromlin 3,333, moonsama-embassy 15, moonsama-multiverse-art-eth 27.
- `item-skins.json` — tool skins (custom model data per vanilla material) and the Portal
  token that unlocks each one (68 entries). Unlock tokens live in
  the `moonsama-x` collection; token ids are identical on the Moonriver contract
  (1285 `0x1974eeaf317ecf792ff307f25a3521c35eecde86`) and its Exosama-network migration
  target (2109 `0x9984440fb82f1af013865141909276d26b86e303`).
- `offhands.json`, `hats.json` — cosmetic off-hand items and 3D hats of the legacy server
  (material, custom model data, unlocking Portal token). Curated by hand from the retired
  plugin sources rather than extracted, so they survive re-runs of the extractor.
- `offhand-states.json` — the alternate looks an off-hand takes while its gameplay perk
  runs (cooldown buzzers, Eggnade charge stages, Detectore ore glow, Pods props) and the
  Moonsama-owned sounds the perks play. Drives the resource-pack port and the perk tests.
- `game-passes.json`, `whale-buffs.json` — legacy access gates and buff allowlists mapped
  to Portal collections (entries with `collection: null` cannot be checked through Portal).
- `compositor/` — data for composing custom skins the way the Customizer did:
  `collections.json` (slots, assets, proxies, traits, unlock rules, render variants),
  `components/<collection>.json` (conditional texture fragments), `files/` (layer PNGs),
  `compositions/<collection>.jsonl` (each token's default slot values).
  760 components, 477 layer files.

## Known gaps

- `exosama-minecraft` components reference `cyber_wiring/v<n>.png`, but the Composer file
  table only ever held `cyber_wiring/cyber_wiring_v<n>.png`; the legacy renderer never
  had those layers either. Treat the `cyber_wiring` slot as a no-op or alias the files.
- Per-token `bird_head` hat textures were never stored anywhere recoverable.
- Gromlins have no slots, assets or default compositions in the Composer; their skins were
  produced elsewhere and only exist as the pre-signed textures in `skins/gromlin.jsonl`.
  `skin-compositor` therefore cannot re-compose them.
- `moonsama-multiverse-art-eth` is the Composer's "Multiverse Avatars" (27 tokens). The
  collection was migrated Moonriver → Exosama network → Ethereum with stable token ids, so
  the legacy signed skins apply to the Portal tokens directly (each signed texture equals
  the avatar's body layer). The Composer also stored an unreferenced `_hat.png` variant per
  avatar; no component uses it, so it is not exported.
