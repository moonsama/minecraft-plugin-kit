# MoonsamaWhaleBuffs

Paper plugin porting the legacy whale buffs: linked players earn **Moon Power** from the NFTs
they hold in Portal and get extra health, extra damage, a coloured name and — from 100 power —
the **Whale Scepter**, which activates Whale Mode.

Depends on `MoonsamaCore`; uses `MoonsamaSkins` to check the worn skin (the legacy rule: buffs
only apply while wearing a Moonsama or Exosama NFT skin).

## Rules (defaults = legacy numbers, all in `config.yml`)

| | Default |
| --- | --- |
| Power per token | Moonsama 10, Multiverse Avatar (`moonsama-multiverse-art-eth`) 10, Exosama 1; Neon Moonsamas (#276, 511, 545, 605, 787, 920) 100 |
| Extra max health | 0.1 HP per power |
| Extra melee damage | +2.5 % per power (100 power → ×3.5) |
| Name colour | ≥1 gold, ≥50 blue, ≥100 light purple, ≥200 rainbow (chat and player list) |
| Whale Scepter | given to players with ≥100 power; bound: cannot be dropped, stored or lost on death |
| Whale Mode | right-click the scepter: max health ×1.5 for 120 s, full heal, lightning, then 300 s cooldown |
| Entitlement | only while wearing an NFT skin from `skins.collections` (`moonsama`, `exosama`, `moonsama-multiverse-art-eth`); set `require-entitled-skin: false` to buff every linked holder |

Power is recomputed from MoonsamaCore's holdings on join and on every refresh, and the skin
check re-runs whenever the worn skin changes, so selling the NFT or taking off the skin
removes the buffs immediately. Nothing is persisted: the health modifier is removed on quit,
so player files never carry buffs.

## Skin-gated fire resistance

Protections follow Paper's data-driven damage types for fire, lava, magma, campfires, fireballs
(including unattributed fireballs), dragon breath clouds and sulfur cubes; protected players also
cannot be ignited, including by Fire Aspect. Set `fire-resistance.enabled: false` to disable it.
This protection uses `MoonsamaSkins` directly and works independently of Moon Power, Whale Mode,
`skins.require-entitled-skin`, and potion effects; it never adds or removes a `FIRE_RESISTANCE`
effect. `fire-resistance.show-icon` (default `true`) shows the wearer a display-only
fire-resistance HUD icon (no particles, no real potion effect, survives `/clear`). While a genuine
fire-resistance effect is active its icon takes precedence; the skin icon returns within 5 seconds after it ends.

Deliberate differences from the legacy plugin:

- Players with 0 power keep their plain name (the old code coloured everyone gold).
- Activation lightning is visual only by default (`whale-mode.lightning: effect`); set `real`
  for vanilla strikes that can hurt bystanders, or `none`.
- Name colours are applied through Adventure display/list names; there is no PlayerNameTools
  integration.

## Commands

- `/whalebuffs status [player]` – power, entitlement, resulting health/damage and Whale Mode state.
- `/whalebuffs reload` – re-read the config and recompute everyone.

Permission `moonsama.whalebuffs.admin` (ops).

## For plugin authors

`WhaleBuffs` is registered with the `ServicesManager`: `effectivePower(player)`,
`tracker(uuid)`, `activateWhaleMode(player)`, `scepter().isScepter(item)`.
