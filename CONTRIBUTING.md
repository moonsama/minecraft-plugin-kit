# Developer guide

This is the guide for people who want to work *on* the kit: fix a plugin, port another
legacy feature, tune the data, or build a new plugin next to the existing ones. If you
only want to *run* a server, start with the [README](README.md),
[docs/local-development.md](docs/local-development.md) (dev stack) and
[docs/operator-guide.md](docs/operator-guide.md) (production). If you want to build your own
plugin on top of `MoonsamaCore`, read [docs/plugin-authoring.md](docs/plugin-authoring.md)
after this page.

## Development environment

Pick one; they all produce identical builds.

| | What you need | How Gradle runs |
| --- | --- | --- |
| **Dev container** (recommended) | VS Code / Cursor with the Dev Containers extension, or a GitHub Codespace | `./gradlew` with the container's JDK 25 |
| **Docker only** | Docker Desktop / Docker Engine + Compose v2 | inside a throw-away `gradle:9.1.0-jdk25` container (`make …`) |
| **Local JDK** | JDK 25 (Temurin), Docker for the Paper server | `./gradlew` directly, or `make GRADLE=./gradlew …` |

### Dev container

`.devcontainer/devcontainer.json` builds an Ubuntu image with Temurin JDK 25, Docker in
Docker (the Paper stack from `compose.yaml` runs *inside* the dev container and ports
`25565`/`8080` are forwarded to your machine), Python 3 for the maintenance scripts, and the
Java/Gradle/Docker extensions. Gradle's cache and the inner Docker images live in named
volumes so rebuilding the container does not re-download the world.

Open the repository and choose **Reopen in Container** (or **Code → Codespaces → Create**
on GitHub). The post-create step warms the Gradle cache and tells you what to do next;
the `GRADLE=./gradlew` environment variable makes every `make` target use the local JDK.

### Portal credentials

The kit talks to a Portal *sandbox* app. Create `.env` from `.env.example` and paste the
sandbox key pair and OAuth client from the Portal admin (steps in
[docs/local-development.md](docs/local-development.md#portal-sandbox-setup)). `.env` is
git-ignored; never commit credentials, never put them in a plugin `config.yml` that gets
committed, and never bake them into a JAR or the resource pack. Feature plugins do not
handle credentials at all — only `MoonsamaCore` does.

## Repository layout

```
portal-client/        Paper-independent async HTTP client for the Portal API
moonsama-paper/       MoonsamaCore: OAuth linking, SQLite store, economy, MoonsamaService API
cosmetics-data/       generated data: signed NFT skins, item/off-hand/hat mappings, compositor rules
skin-compositor/      Paper-independent port of the legacy avatar renderer (PNG in, PNG out)
moonsama-skins/       /skins       wear the skin of an NFT you hold
moonsama-items/       /items       item skins, off-hands + gameplay perks, hats
moonsama-wardrobe/    /wardrobe    compose a custom skin from unlocked parts (MineSkin-signed)
moonsama-gatekeeper/  optional access gate on Portal holdings
moonsama-whale-buffs/ Moon Power: health/damage/name buffs + Whale Mode
examples/offhand-demo minimal plugin against the builder API
resourcepack/         Minecraft pack (format 97): src/ is checked in, build/ is generated
tools/legacy-extract  maintainers: regenerate cosmetics-data from the retired services' DBs
tools/pack-port       maintainers: port models/textures/sounds from the legacy pack
scripts/              refresh-paper.py — bump the pinned Paper build
docs/                 operator + plugin-author documentation
```

Everything under `references/`, `minecraft-plugins-old/`, `minecraft-resourcepacks-old/`
and the other paths in `.gitignore` is legacy reference material on a maintainer's disk.
Nothing in the build depends on it, and none of it may be committed.

Dependency direction, top to bottom: feature plugins → `moonsama-paper` (compile-only; the
server provides it at runtime) → `portal-client`. `cosmetics-data` and `skin-compositor`
are plain libraries that get shaded into the plugins that use them. Feature plugins never
depend on each other's classes at compile time except through the documented *bridge*
pattern (below).

## Build, test, run

```bash
make build                       # all modules, unit tests, resource pack
make test                        # tests only, forced re-run
make up                          # build + start Paper 26.3 with every plugin (needs .env)
make logs / make down
make refresh-paper               # move the pinned Paper build forward (updates gradle.properties + compose.yaml)
```

Gradle directly (dev container / local JDK):

```bash
./gradlew build
./gradlew :moonsama-items:test --tests '*PerkDataTest*'
./gradlew :skin-compositor:test -Dmoonsama.compositor.archive=/path/to/references/archive/composer-skins
```

Outputs: `build/dist/` is the distribution - `plugins/Moonsama<Name>.jar` for every plugin
(shaded where the plugin bundles libraries), `resourcepack.zip` and `SHA256SUMS`. It is
assembled by the root `dist` task (part of `build`). Per-module intermediates stay in
`<module>/build/libs/` (`-unshaded`, `-sources`). `compose.yaml` bind-mounts `build/dist`
into the Paper container, so after `make build` a `docker compose restart paper` picks up
new code. Server files persist in `dev-data/`.

Talking to the server console (no rcon): `docker attach minecraft-plugin-kit-paper-1`
(detach with `Ctrl-p Ctrl-q`), or run commands as an operator in-game.

CI (`.github/workflows/build.yml`) runs `./gradlew build` on every PR with the same
JDK/Gradle versions and uploads `build/dist` as an artifact.

### Versions and releases

The in-tree version is always `<next>-SNAPSHOT` (root `build.gradle.kts`); do not bump it
in feature PRs. A release is a tag:

1. Move the `Unreleased` notes in `CHANGELOG.md` under a `## [X.Y.Z] - YYYY-MM-DD`
   heading and merge that to `main`.
2. `git tag -a vX.Y.Z -m "X.Y.Z" && git push origin vX.Y.Z`.
3. `.github/workflows/release.yml` builds with `-PkitVersion=X.Y.Z` (so `plugin.yml`
   reports the real version), extracts that CHANGELOG section as release notes, and
   publishes a GitHub release with every plugin JAR, the pack and `SHA256SUMS`.
   Tags containing a `-` (for example `v0.2.0-rc.1`) are marked pre-release.
4. Afterwards bump the SNAPSHOT version in `build.gradle.kts` if the next release changes
   the major/minor.

Locally, `./gradlew build -PkitVersion=1.2.3` reproduces a release build.

### Testing without a server

Unit tests run without Paper. Bukkit API classes that are safe in tests: `Material`,
`NamespacedKey.minecraft(...)`, `YamlConfiguration`, `ItemStack`-free logic, Adventure
components. Not safe (they need a running server/registry): `PotionEffectType` constants,
`Registry.*`, `Bukkit.getServer()`, anything creating `ItemMeta`. Keep such lookups behind a
runtime call and store plain keys in the code that tests touch (see `MoonbagPerk.Buff`).

Each plugin has a *data test* that loads its bundled `config.yml` and the relevant
`cosmetics-data` files and cross-checks them (every off-hand has a perk, every model
threshold exists in the pack, every default collection has skins, …). When you add data,
extend that test rather than trusting a manual check.

## Conventions

**Configuration.** Every plugin ships a commented `src/main/resources/config.yml` and
calls `saveDefaultConfig()`. Paper never overwrites an existing file, so a new key must
have a sensible default in code (`getX(path, default)`) and be mentioned in the plugin's
README. Anything that varies per server (numbers, collections, on/off switches) belongs in
config; behaviour that would make a server incompatible with the data does not.
`MoonsamaCore` additionally accepts environment overrides (`PORTAL_*`, `MINESKIN_API_KEY`,
`MOONSAMA_SQLITE_JOURNAL_MODE`) because it is what operators deploy through Docker.

**Threads.** Portal and SQLite work is asynchronous (`CompletableFuture`). Anything that
touches a `Player`, world, inventory or entity runs on the main thread — hop back with
`getServer().getScheduler().runTask(plugin, …)` and check `player.isOnline()` after the
hop. Never block the main thread on a future.

**Ownership is re-checked, never cached forever.** Read `cachedHoldings` for menus, confirm
with `holdings` before granting something when the cache is cold, and revalidate on
`PortalHoldingsLoadedEvent`. Forget everything about a player on `PortalPlayerErasedEvent`.

**Items handed out by a plugin are tagged** in the `PersistentDataContainer` with the
plugin's own `NamespacedKey` and protected (no drop, no storing in containers, removed on
death) by cancelling the relevant events. Look at `CosmeticItems` in `moonsama-items` and
`Scepter` in `moonsama-whale-buffs`.

**Bridges for optional plugins.** If plugin A can *use* plugin B but must work without it,
declare `softdepend: [B]` in `plugin.yml`, keep every reference to B's classes in one
package-private class (`SkinHatBridge`, `SkinBridge`) and instantiate it only after
`getServer().getPluginManager().getPlugin("B") != null`. Everything else talks through a
service registered with Paper's `ServicesManager` (`MoonsamaService`, `SkinService`,
`WhaleBuffs`) and Bukkit events.

**Identifiers.** Everything is keyed by *Portal collection slug + token id*
(`moonsama`, `exosama`, `gromlin`, `moonsama-embassy`, `moonsama-multiverse-art-eth`,
`moonsama-x`, …) or by Portal player id (`plr_…`). Legacy contract addresses and chain ids
appear only in `cosmetics-data/collections.json` for reference; legacy account ids and
Minecraft UUIDs from the old databases are never exported.

**Resource pack.** Custom looks use `CustomModelData` floats on vanilla materials; the
mapping material → threshold → model is data (`item-skins.json`, `offhands.json`,
`offhand-states.json`, `hats.json`, `whale-buffs.json`) that both the plugins and
`tools/pack-port` read. Add the data first, regenerate the pack, then write the code. Only
Moonsama-owned art and sounds go into the pack; the third-party music of the legacy pack is
deliberately excluded.

**Style.** Java 25, records for value types, `final` classes unless designed for extension,
no Lombok, no reflection into Paper internals. Adventure `Component`s / MiniMessage for
text, never legacy `§` codes. Small commits with a descriptive message body; one feature
or fix per pull request.

## Working with the data

`cosmetics-data/` is **generated**. Do not hand-edit `collections.json`, `skins/*.jsonl`,
`game-passes.json`, `whale-buffs.json` or anything under `compositor/`; change the mapping
tables at the top of `tools/legacy-extract/extract_cosmetics.py` and re-run it. This needs
the retired services' databases restored locally (see `tools/legacy-extract/README.md`),
which only maintainers have — if you need a data change and cannot run the extractor,
open an issue or PR describing the mapping change and a maintainer will regenerate.

`offhands.json`, `hats.json` and `offhand-states.json` are curated by hand and survive
re-runs. When you add a state or sound there, `python3 tools/pack-port/port_legacy_pack.py`
copies the matching model/texture/sound from the legacy pack (maintainers) and the
`PerkDataTest` verifies the pack contains it.

Signed skins are Mojang signatures over a texture URL; they never expire and cannot be
forged, so new skins for a collection either come from the legacy database (as for the
Multiverse Avatars) or from a one-time MineSkin upload of the texture. Both routes end in a
`skins/<collection>.jsonl` row `{"id", "value", "signature", "model"}`.

## Adding a feature plugin to the kit

1. Copy the shape of the closest existing module (`moonsama-gatekeeper` is the smallest
   complete one): `build.gradle.kts` with `compileOnly(project(":moonsama-paper"))` and
   `compileOnly("io.papermc.paper:paper-api:$paperApiVersion")`, `plugin.yml` with
   `depend: [MoonsamaCore]`, a commented `config.yml`, a `README.md` that states what it
   does, what it deliberately does *not* do, and every config key.
2. Register it in `settings.gradle.kts`, mount its JAR in `compose.yaml`, list it in the
   root README's module and command tables.
3. Write the config/data test first, then the logic. Verify on the local stack
   (`make up`, join with a linked account, check the plugin's log lines).
4. Follow the conventions above; if you have to break one, say why in the README.

If your plugin is useful only to your own server, you do not need it in this repository:
depend on the published `moonsama-paper` API as described in
[docs/plugin-authoring.md](docs/plugin-authoring.md) and keep it in your own repo.

## Porting more legacy features

The legacy plugin sources are available to maintainers under `references/`. Each module
README has a "differences from the legacy plugin" list for what was ported with changes.
The legacy server ran dozens of further plugins that are **not** ported (custom vehicles
such as the Moonbike, airdrops, colosseum, custom paintings, minigames, chat and utility
plugins, …); the kit deliberately covers only the Portal-identity and NFT-cosmetics layer,
so anything gameplay-only should live in its own repository built on the API rather than
here. Before porting something, open an issue describing the behaviour
you intend to reproduce, and bring the data (`cosmetics-data` / pack) in a separate commit
from the code — it makes review of the gameplay logic far easier.

## Pull requests

- Branch from `main`, keep the PR focused, make sure `make build` is green locally — CI
  runs the same command.
- Say in the description how you tested it on a live server, if you did.
- Update the module README and the root README's tables in the same PR.
- No credentials, no `dev-data/`, no files from the reference folders, no generated
  `build/` output in the diff. `git status` before you push.
- Dependabot opens PRs for Gradle, GitHub Actions and dev-container updates
  (`.github/dependabot.yml`); the Paper API is pinned on purpose - move it with
  `make refresh-paper`, not by merging the bot's bump.

### License of contributions

By submitting a pull request you agree that your code contribution is licensed under the
[Apache License 2.0](LICENSE), the same license as the project (inbound = outbound). Do
not contribute artwork or textures you do not own; assets in `cosmetics-data/` and
`resourcepack/src/assets/` are distributed under the [Moonsama Asset License](LICENSE-ASSETS)
and are only added by maintainers from Moonsama-owned sources.

## Reporting problems

Include the Paper build (`gradle.properties` → `paperBuild`), the plugin versions from the
server log, the relevant log lines (plugins log at `INFO` what they loaded and at
`WARNING`/`SEVERE` what went wrong) and, for crashes, `hs_err_pid*.log` from the server
directory. Redact API keys and player ids from anything you paste.
