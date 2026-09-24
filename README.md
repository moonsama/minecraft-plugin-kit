# Moonsama Minecraft Plugin Kit

Starter kit for Paper server builders who want to use Moonsama Portal identity
and assets in Minecraft.

The kit links Mojang-authenticated players to Portal and gives builder plugins
a durable API for holdings, spend, rewards, refunds, and asset holds.

> Paper 26.3 is currently alpha software. The server and API are pinned to a
> verified build so upgrades are explicit and reproducible. Run
> `make refresh-paper` to pin the newest available 26.3 build.

## Requirements

- Docker Desktop (or any Docker Engine with Compose v2)
- Minecraft Java Edition 26.3
- A Moonsama Portal sandbox API key and OAuth client

Java does not need to be installed on the host. Builds use Java 25 in Docker.

Prefer a ready-made environment? Open the repository in a **dev container**
(VS Code "Reopen in Container", Cursor, or a GitHub Codespace): it comes with
JDK 25, Docker and the Java extensions, and `make` uses the local JDK there.
See [CONTRIBUTING.md](CONTRIBUTING.md).

## Run locally

1. In the Portal admin Sandbox tab, get a sandbox key.
2. Create a login client with this exact redirect URI:
   `http://127.0.0.1:8080/callback`
3. Copy the environment template and fill in the credentials:

   ```bash
   cp .env.example .env
   ```

4. Build and start Paper:

   ```bash
   make up
   ```

   To refresh the Paper pin first, use `make up-latest`.

5. Join `localhost:25565` with Minecraft 26.3.
6. Run `/moonsama link`, click **Open Portal**, and approve access.
7. Run `/moonsama holdings`.
8. Run `/offhanddemo` to equip the item gated by `sandbox-items` token `1`.
9. Run `/buyrelic` to test a crash-safe spend of 10 `sandbox-gold`.

The server world and embedded SQLite database live under `dev-data/` and are
ignored by Git.

See [local development](docs/local-development.md) for setup and troubleshooting,
[CONTRIBUTING.md](CONTRIBUTING.md) for the developer guide and
[docs/plugin-authoring.md](docs/plugin-authoring.md) to build your own plugin on the API.

## Run on a real server

Download the plugin JARs and `resourcepack.zip` from the
[GitHub releases](../../releases) (every tag `vX.Y.Z` publishes them with a
`SHA256SUMS`), or build them yourself with `make build` - they land in `build/dist/`.
Then follow the [operator guide](docs/operator-guide.md): Portal production app,
public HTTPS callback, resource pack hosting, config, backups and upgrades.
Changes between versions are listed in [CHANGELOG.md](CHANGELOG.md).

## Modules

- `portal-client`: Paper-independent asynchronous Java client for Portal.
- `moonsama-paper`: OAuth linking, SQLite persistence, commands, and builder API.
- `cosmetics-data`: offline data recovered from the retired Moonsama services:
  Mojang-signed skins for every Moonsama, Exosama, Gromlin, Embassy and Multiverse Avatar NFT, item
  skin/gate mappings, and the avatar compositor rules. Keyed only by Portal
  collection slug and token id. See [cosmetics-data/README.md](cosmetics-data/README.md).
- `moonsama-skins`: `/skins` plugin that lets a linked player wear the skin of an
  NFT they hold in Portal. Needs no Customizer, Minecraft API or Mineskin; the
  signed textures ship inside the JAR. Collections whose tokens all share one look
  (Gromlins) are `uniform-collections`: any number of tokens unlocks a single entry.
- `skin-compositor`: Paper-independent Java port of the legacy Composer/Unity avatar
  renderer. Turns a Moonsama or Exosama composition (base, outfit, hat, costume, …)
  into a 64×64 skin PNG from the layers in `cosmetics-data`. See
  [skin-compositor/README.md](skin-compositor/README.md).
- `moonsama-items`: `/items` plugin for the legacy item cosmetics — weapon/tool skins
  and off-hand items unlocked by `moonsama-x` (Multiverse Items) tokens, plus the 3D hats
  that belong to a worn Moonsama/Exosama skin (with `MoonsamaSkins`). Off-hands carry
  their legacy gameplay perks (Moonbroom flight, Eggnades, Detectore ore glow, Pods jams,
  …), each of which can be switched off in the config. See
  [moonsama-items/README.md](moonsama-items/README.md).
- `moonsama-wardrobe`: `/wardrobe` plugin replacing the Customizer. A player picks a
  Moonsama or Exosama they hold, swaps parts (hair, hat, outfit, costume, …) for parts
  unlocked by their other holdings, and wears the composed skin. Composed skins need a
  Mojang signature, which `MoonsamaCore` obtains from MineSkin when `MINESKIN_API_KEY`
  is set; without it the wardrobe is browse/save only. See
  [moonsama-wardrobe/README.md](moonsama-wardrobe/README.md).
- `moonsama-gatekeeper`: optional access gate — only players whose Portal account holds one
  of the configured passes (default: Moonsama, Exosama, Gromlin, Embassy, Multiverse Avatar,
  VIP Ticket) may
  play; others are kicked or parked in a waiting room where they can still link. Off by
  default. See [moonsama-gatekeeper/README.md](moonsama-gatekeeper/README.md).
- `moonsama-whale-buffs`: Moon Power from held NFTs → extra health, damage, name colours and
  the Whale Scepter / Whale Mode, active while wearing an entitled NFT skin. All numbers in
  config. See [moonsama-whale-buffs/README.md](moonsama-whale-buffs/README.md).
- `examples/offhand-demo`: minimal plugin consuming the builder API.
- `resourcepack`: Minecraft 26.3 pack (format 97) with the ported legacy models for item
  skins, off-hands (including their perk states) and hats, plus the Moonsama-owned sounds
  the perks play. Third-party music of the old pack is not included. Regenerate the ported part with
  `python3 tools/pack-port/port_legacy_pack.py` (maintainers only).
- `tools/legacy-extract`: read-only scripts that regenerate `cosmetics-data`
  from local copies of the legacy databases (maintainers only).

Legacy production sources under `references/`, `minecraft-plugins-old/` and
`minecraft-resourcepacks-old/` are ignored reference material. The new modules
do not depend on them.

## Commands

- `/moonsama link`
- `/moonsama status`
- `/moonsama holdings`
- `/skins` (menu of owned NFT skins), `/skins wear <collection> <id>` (`<id>` optional for
  uniform collections, e.g. `/skins wear gromlin`), `/skins reset`, `/skins status`
- `/items` (menu of owned item skins and off-hands), `/items skin <id>|clear`,
  `/items offhand <id>|none`, `/items list`, `/items status`
- `/wardrobe` (pick NFT → slot → part, then "Wear this look"),
  `/wardrobe <collection> <id>`, `/wardrobe reset <collection> <id>`, `/wardrobe status`
- `/offhanddemo`
- `/buyrelic`
- `/moonsama admin status`, `/gatekeeper status [player]|reload`,
  `/whalebuffs status [player]|reload` (server operators)

## Security rules

- Keep Paper in `online-mode=true`.
- Never put API or OAuth secrets in a plugin JAR, resource pack, or commit.
- Every independently operated community server needs its own Portal app and
  credentials.
- Network and database work must stay off Paper's main thread.

## Production core

`MoonsamaCore` owns Portal credentials and executes value-moving calls for
feature plugins. SQLite stores idempotency keys, write state, receipts, holds,
holdings cache, feed cursors, and an event outbox. A write with an uncertain
response checks Portal for its receipt before it is ever sent again.

All value-moving routes are operator gated. The environment template enables
only spend for the sandbox `/buyrelic` demo; reward, refund, and holds remain
disabled until explicitly enabled.

Production hosting (HTTPS callback behind a reverse proxy, pack hosting, backups) is
covered in the [operator guide](docs/operator-guide.md). Velocity/proxy support remains
follow-up work.

Before public redistribution, the project also needs an explicit source and
asset license. The resource pack contains Moonsama's own legacy item, off-hand and hat
art only; no music or third-party assets are included.
