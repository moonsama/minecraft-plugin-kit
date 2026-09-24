# Moonsama Minecraft Plugin Kit

Starter kit for Paper server builders who want to use Moonsama Portal identity
and assets in Minecraft.

The kit links Mojang-authenticated players to Portal and gives builder plugins
a durable API for holdings, spend, rewards, refunds, and asset holds.

> Paper 26.3 is currently alpha software. The server and API are pinned to a
> verified build so upgrades are explicit and reproducible. Run
> `make refresh-paper` to pin the newest available 26.3 build.

## Requirements

- Docker Desktop
- Minecraft Java Edition 26.3
- A Moonsama Portal sandbox API key and OAuth client

Java does not need to be installed on the host. Builds use Java 25 in Docker.

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

See [local development](docs/local-development.md) for setup and troubleshooting.

## Modules

- `portal-client`: Paper-independent asynchronous Java client for Portal.
- `moonsama-paper`: OAuth linking, SQLite persistence, commands, and builder API.
- `cosmetics-data`: offline data recovered from the retired Moonsama services:
  Mojang-signed skins for every Moonsama, Exosama, Gromlin and Embassy NFT, item
  skin/gate mappings, and the avatar compositor rules. Keyed only by Portal
  collection slug and token id. See [cosmetics-data/README.md](cosmetics-data/README.md).
- `moonsama-skins`: `/skins` plugin that lets a linked player wear the skin of an
  NFT they hold in Portal. Needs no Customizer, Minecraft API or Mineskin; the
  signed textures ship inside the JAR.
- `skin-compositor`: Paper-independent Java port of the legacy Composer/Unity avatar
  renderer. Turns a Moonsama or Exosama composition (base, outfit, hat, costume, …)
  into a 64×64 skin PNG from the layers in `cosmetics-data`. See
  [skin-compositor/README.md](skin-compositor/README.md).
- `examples/offhand-demo`: minimal plugin consuming the builder API.
- `resourcepack`: Minecraft 26.3 sample pack using modern custom model data.
- `tools/legacy-extract`: read-only scripts that regenerate `cosmetics-data`
  from local copies of the legacy databases (maintainers only).

Legacy production sources under `references/`, `minecraft-plugins-old/` and
`minecraft-resourcepacks-old/` are ignored reference material. The new modules
do not depend on them.

## Commands

- `/moonsama link`
- `/moonsama status`
- `/moonsama holdings`
- `/skins` (menu of owned NFT skins), `/skins wear <collection> <id>`,
  `/skins reset`, `/skins status`
- `/offhanddemo`
- `/buyrelic`
- `/moonsama admin status` (server operators)

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

Velocity support, NFT character skins, and production callback hosting remain
follow-up work.

Before public redistribution, the project also needs an explicit source and
asset license. No legacy music or proprietary resource-pack art is included.
