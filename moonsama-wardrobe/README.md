# MoonsamaWardrobe

Paper plugin that replaces the retired Multiverse Customizer: linked players mix the
parts of the Moonsama and Exosama NFTs they hold into a custom skin and wear it in game.

Depends on `MoonsamaCore` (identity, holdings, optional skin signing) and `MoonsamaSkins`
(wearing textures, ownership revalidation). Bundles `skin-compositor` and the compositor
part of `cosmetics-data`.

## Player flow

1. `/wardrobe` lists the customizable NFTs the player holds (player heads).
2. Clicking one shows its slots (hair, hat, eyes, outfit, costume, …) with the current
   part in each.
3. Clicking a slot lists the parts the player has unlocked: the NFT's own part, "Nothing"
   (unless the slot is required) and every other unlocked part.
4. "Wear this look" renders the composition, has it signed and applies it. Looks are saved
   per Minecraft account and NFT, so the player can come back and tweak them.
5. "Reset to the NFT's own look" forgets the saved look.

`/wardrobe <collection> <id>` jumps straight to an NFT, `/wardrobe reset <collection>
<id>` clears its look, `/wardrobe status` shows what is worn and whether signing works.

## What can be unlocked

The Customizer decided this from on-chain traits and contracts. Portal only exposes
collection + token id, so the wardrobe mirrors the rules like this:

| Customizer rule | Wardrobe equivalent |
| --- | --- |
| Trait rules (`nft` / `trait`, patterns) | A part is unlocked if one of the player's NFTs in the same collection has it in its default composition. Holding Moonsama #2 (aviator) unlocks the aviator for Moonsama #1. |
| `nft` `any` / `specific` / `range` on a legacy contract | The contract is mapped to a Portal collection through `cosmetics-data/collections.json` → `legacyContracts`, then checked against holdings. Multiverse Items on Moonriver and Exosama both map to `moonsama-x` with identical ids. |
| Contracts Portal does not know (Multiverse Costumes, Backgrounds, Avatars) | Governed by `parts-outside-portal`: `locked` (default) keeps the 39 costume-only parts unavailable; `free` opens them to every linked player. Portal will not index those contracts, so this is a server policy, not a data gap. |
| `AND` / `OR` / `NAND` / `NOR`, `invert` | Evaluated as written. |
| `none` / unknown types | Never unlock. |

Backgrounds and companions are hidden (`hidden-slots`) because they do not show on a
Minecraft skin.

## Signing

Minecraft only accepts Mojang-signed textures. `MoonsamaCore` registers a `SkinSigner`
service backed by MineSkin when `MINESKIN_API_KEY` (or `skins.mineskin-api-key`) is set,
and caches signatures per rendered PNG in `plugins/MoonsamaCore/skin-signatures.json`.
Without a key the wardrobe still opens and saves looks but tells the player the server
cannot sign yet. Signing takes a few seconds per new look; the player is told to wait and
only one apply per player runs at a time.

Every *new* look is one request against the operator's MineSkin quota, so applies are
budgeted per player (`signing-budget`: 20 s cooldown and 30 new looks per rolling hour by
default). Looks already signed on this server are cache hits and never count, and players
with `moonsama.wardrobe.unlimited` (ops) are exempt. Set both values to `0` to disable.

## Data

`plugins/MoonsamaWardrobe/looks.json` holds saved looks keyed by Minecraft UUID and
`collection:tokenId`. No Portal identifiers are stored. Looks are dropped on
`PortalPlayerErasedEvent`; parts that are no longer unlocked are removed from the saved
look on the next `PortalHoldingsLoadedEvent`, and losing the base NFT itself is handled by
`MoonsamaSkins`.

## Configuration

```yaml
collections: [moonsama, exosama]      # composable Portal collections
hidden-slots: [background, companion] # customizable slots to leave out of the menu
parts-outside-portal: locked          # or "free": costume-only parts for everyone
signing-budget:                       # per player; only NEW looks count (0 = off)
  cooldown-seconds: 20
  max-per-hour: 30
collection-names: { moonsama: Moonsama, exosama: Exosama }
```
