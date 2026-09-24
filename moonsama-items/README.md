# MoonsamaItems

Paper plugin for the legacy Moonsama item cosmetics: weapon/tool skins, off-hand items and
3D hats, unlocked by what a linked player holds in Portal (mostly `moonsama-x`, the
Multiverse Items). Off-hand items also carry the gameplay perks the old server gave them.

Depends on `MoonsamaCore` (identity, holdings). Uses `MoonsamaSkins` when present (hats
follow the worn NFT skin). Bundles the item tables of `cosmetics-data`; the models live in
`resourcepack/`.

## Player flow

- `/items` opens a menu of the item skins and off-hands the player owns. Clicking a skin
  applies it to the tool in the main hand; clicking an off-hand puts it in the off-hand slot.
- `/items skin <id>|clear`, `/items offhand <id>|none`, `/items list`, `/items status`.
- Off-hands and hats are managed items: they cannot be dropped, moved, swapped to the main
  hand or lost on death, and the stored off-hand comes back on join/respawn. When Portal
  reports the token is gone, the cosmetic is removed (`unequip-when-sold`).

## Off-hand perks

While a cosmetic off-hand sits in the off-hand slot its perk is active. The behaviour and
numbers follow the legacy `moonsama-offhands` plugin; the item switches between looks
(catalog look, cooldown "buzzer", charge stages) without changing identity, and cooldowns
also show as the vanilla item cooldown overlay.

| Off-hand | Perk |
| --- | --- |
| Moonsama Egg | Haste II while equipped |
| Moonana | Jump Boost I while equipped |
| Moondrink | Haste III at night |
| Moonbrella (all colours) | Slow Falling while holding right-click (blocking) |
| Moonburger | Eat from the off-hand: +4 food, 2 min cooldown |
| Moonrum | Drink from the off-hand: Strength I, Nausea and Slowness for 30 s, +2 food, 2 min cooldown, pirate laugh |
| Moonpaw | Sneak for 2 s, then sprint/jump within 3 s to pounce: Speed I for 10 s, 40 s cooldown |
| Detectore (5 gems) | Samples blocks within 3.5 blocks; when an ore is near the gem glows in that ore's colour until the block changes or you walk 5 blocks away |
| Moonsquid / Moonsquid Neon | Aqua Affinity: a squid helmet on a bare head, or the enchantment added to the worn helmet; both undone on unequip |
| Moonbroom | Right-click to launch on a broom (speed 1.5); 60 s cooldown, and hitting a player grounds the broom for at least 10 s |
| Moontree | Right-click the ground to plant up to 4 saplings that grow into spruce trees after 10 s (needs sky light ≥ 13); one sapling regrows per minute |
| Moonflake | When another player hits you: blizzard — everyone within 20 blocks gets Slowness VI for 10 s and is frozen. 3 min cooldown, restarted on equip |
| Moonbasket | Right-click to throw up to 3 Eggnades: harmless explosions that knock entities away (radius 5); one egg recharges per minute |
| Moonbag | A random buff that changes every 5 minutes; three tiers, stronger the longer the bag stays equipped (6 cycles per tier) |
| Pods | Right-click to pick a song and start a jam: a 10-block circle where the DJ gets Haste, Strength and Resistance II for the track's length; 7 min cooldown kept across equips |

Differences from the legacy plugin, on purpose:

- Moonbag applies the tier level as written in the tables (level II/III where the table says
  so); the old code always applied level I despite promising more.
- The Detectore ore table had two typo'd keys (`nether_gold_core`, `nether_quartz_core`);
  they are read as the nether gold and quartz ores.
- Pods music: the legacy tracks are third-party (Public Pressure, Merk & Kremont) and are
  not part of this kit's resource pack. The default song list still names them so servers
  that distribute the old pack keep the audio; everyone else configures their own tracks
  under `offhands.perks.pod-songs` or gets a silent jam with the same buffs.
- Cooldowns live in memory only (like before) and reset on a restart.

### Configuration (`config.yml`)

```yaml
offhands:
  perks:
    enabled: true                 # false → every off-hand is purely cosmetic
    disabled: []                  # ids whose perk stays off, e.g. publicpressure:pods
    moonbasket-breaks-blocks: false
    # pod-songs:
    #   my_track: { sound: myserver:music.my_track, length-ms: 180000, name: My Track, artist: Somebody }
```

Perk-issued items (the Moonsquid helmet) are tagged like other managed cosmetics and get the
same protections.

## For plugin authors

`ItemsService` is registered with the `ServicesManager`: `catalog()`, `owned(uuid)`,
`applySkin`, `equipOffhand`, `removeOffhand`, `items()` (build/recognise cosmetic
`ItemStack`s). `MoonsamaItemsPlugin#perks()` exposes the `PerkManager` (active perk and
state per player). New perks extend `perks.Perk` and are mapped in `perks.PerkRegistry`;
alternate item looks go into `cosmetics-data/offhand-states.json` so the pack port and
`PerkDataTest` keep the resource pack in step.
