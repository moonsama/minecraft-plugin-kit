# skin-compositor

A Paper-independent Java library that re-implements the avatar compositor of the retired
Moonsama Composer service (`metaverse-assets-api` + Unity renderer). Given a composition —
a Portal collection, token id and a list of `slot → asset` values — it resolves the
components the legacy rules would have selected and draws the 64×64 Minecraft skin from
the layer files shipped in `cosmetics-data`.

```java
SkinCompositor compositor = SkinCompositor.load(getClassLoader());

// The NFT's default look.
SkinCompositor.Rendered rendered = compositor.renderToken("moonsama", 276).orElseThrow();
byte[] png = rendered.png();

// A custom look: swap the costume.
List<CompositorData.SlotValue> slots = new ArrayList<>(compositor.defaultSlots("moonsama", 276).orElseThrow());
slots.removeIf(s -> s.slot().equals("costume"));
slots.add(new CompositorData.SlotValue("costume", "multiverse-costumes", "multiverse_costumes:kringle_kap"));
rendered = compositor.render(Composition.of("moonsama", 276, slots));
rendered.warnings(); // missing layer files etc.; never throws for data problems

// What a wardrobe can offer: slots, the parts permitted in a slot, display names, and
// the Customizer's unlock rules (CollectionDef.unlockRules) with legacy contracts
// mapped to Portal collections (CompositorData.portalCollectionOf).
compositor.customizableSlots("moonsama");
compositor.permittedAssets("moonsama", "hat");
compositor.assetName(new CompositorData.SlotValue("hat", "moonsama", "moonsama:aviator")); // "Aviator"
```

The output is an unsigned PNG. To put it on a player it still has to be signed by Mojang
(e.g. through Mineskin) — `moonsama-wardrobe` does that through `MoonsamaCore`'s
`SkinSigner` service, not this library.

## What is ported

- Collection/representation resolution: the main collection is mapped to its Minecraft
  render variant (`moonsama` → `moonsama-minecraft`), slot values to their assets, and each
  asset to the representation collection that draws it (costumes have per-collection
  variants such as `minecraft-moonsama`).
- Slot permissions (`tagged`, `slot` options) and asset proxies.
- Component selection: the base components of the representation's `resultTypes` plus every
  component whose `states` logic (asset/token/renderType leaves, AND/OR/NAND/NOR/XOR gates,
  `invert`) passes for the composition.
- Elements `texture`, `texture_fragment` (z-index ordered, `pass: skin` fragments drawn
  last), `texture_atlas` and `sprite`. `model` elements are 3D preview only and ignored.
- Unity semantics: bottom-left texture origin and source-over blending in linear light.

## Fidelity against the legacy renders

`LegacyRenderParityTest` renders every token's default composition and compares it with the
skin the legacy pipeline produced (archived under `references/archive/composer-skins`,
maintainers only; the test is skipped without it). Current results:

| Collection | Identical | Within 4 px |
| --- | --- | --- |
| moonsama | 591 / 1000 | 82 |
| exosama | 6686 / 10000 | 1562 |
| moonsama-embassy | 14 / 15 | 0 |

The remainder are differences in the *legacy* data rather than in this port:

- The archived Exosama skins were rendered on 2023-08-31 13:07–13:18; the Exosama layer
  files were re-imported at 16:54 the same day. Tokens whose layers changed in that import
  (e.g. #1009 "Onyx Graphic Tee": blue in the archive, black in the current file) cannot match.
- The legacy renderer drew the soft arm shadows of some Moonsama shirts over the body and
  others under it although the component definitions are identical. The port follows the
  majority behaviour (`pass: skin` fragments last); set
  `-Dmoonsama.compositor.passOrdering=false` to draw strictly in component order.
- A few 2-pixel strays outside the declared 8×8 hair rectangles were copied by the legacy
  renderer; the port respects the rectangles.
- `exosama-minecraft/cyber_wiring/v*.png` are referenced by components but the files were
  stored as `cyber_wiring_v<n>.png`; those fragments are skipped with a warning.

Gromlins have no slots or assets in the Composer data (their 3333 skins were produced
elsewhere), so `supports("gromlin")` is `false`; use the pre-signed skins from
`cosmetics-data/skins/gromlin.jsonl` instead.
