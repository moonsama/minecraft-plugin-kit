# Changelog

All notable changes to the Moonsama Minecraft plugin kit. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/) once 1.0.0 is reached. Before that, minor
versions may contain breaking config changes - they are called out under **Changed**.

Releases are cut by tagging `main` as `vX.Y.Z`; the section for that version becomes the
GitHub release notes (see `CONTRIBUTING.md`, "Versions and releases").

## [Unreleased]

Nothing yet - first release pending.

## [0.1.0] - unreleased

Initial public kit for Paper 26.3 / Java 25, replacing the retired internal game backend
with direct Moonsama Portal integration.

### Added

- **MoonsamaCore** (`moonsama-paper`): Portal account linking via OAuth (`/moonsama link`)
  with a browser confirmation page that names the Minecraft account before the link is
  saved (prevents forwarded-link account takeover), holdings
  cache, crash-safe economy with idempotent Portal transactions, SQLite storage
  (`storage.journal-mode`, default `wal`, `truncate` for bind-mounted volumes), resource
  pack serving with SHA-1 pinning, shared API (`MoonsamaApi`) for feature plugins.
- **MoonsamaSkins**: NFT skins for `moonsama`, `exosama`, `gromlin`, `moonsama-embassy`,
  `moonsama-multiverse-art-eth` from bundled legacy signed textures; `/skins` menu and
  `/skins wear`; uniform collections (one skin per collection, e.g. Gromlin).
- **MoonsamaItems**: legacy off-hand cosmetics and gameplay perks (Moonbag, scepters, …)
  gated on Portal holdings, with custom models in the resource pack.
- **MoonsamaWardrobe**: custom skin compositions (Java port of the legacy Composer
  renderer in `skin-compositor`) with optional MineSkin signing.
- **MoonsamaGatekeeper**: optional collection-based join gate (disabled by default).
- **MoonsamaWhaleBuffs**: "moon power" buffs scaled by held collections.
- **MoonsamaOffhandDemo** (`examples/offhand-demo`): minimal feature plugin showing the
  API surface.
- `cosmetics-data`: generated dataset extracted from the legacy databases (signed skins,
  item gates, compositor components/compositions, whale buffs, game passes) plus the
  extraction tooling in `tools/legacy-extract`.
- Docker dev stack (`compose.yaml`, `Makefile`), dev container, `CONTRIBUTING.md`,
  operator guide, CI build and tag-driven release workflow producing `build/dist`
  (stable plugin JAR names, resource pack, `SHA256SUMS`).
