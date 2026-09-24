# Legacy extraction tools

Maintainer-only scripts that rebuild `cosmetics-data/` from local, read-only copies of the
retired Moonsama services. Community servers never need to run these; they consume the
generated data through the `cosmetics-data` Gradle module.

## Inputs

Set in `.env.local` (never committed):

```
LOCAL_MYSQL_URL=mysql://root:localdev@127.0.0.1:3307/mc_backend            # Minecraft API
LOCAL_ASSETS_POSTGRES_URL=postgresql://postgres:localdev@127.0.0.1:5433/assets_api_moonsama   # Composer
LOCAL_CUSTOMIZER_POSTGRES_URL=postgresql://postgres:localdev@127.0.0.1:5433/msama_mv_customizer
```

The scripts shell out to `psql` and `mysql`; override their locations with `PSQL_BIN` /
`MYSQL_BIN` if they are not on the default Homebrew paths.

## Scripts

- `legacydb.py` — thin CSV-based readers over the CLI clients. Read-only.
- `archive_assets.py` — dumps the Composer tables for the Minecraft collections and
  mirrors the referenced layer PNGs and the pre-rendered 64×64 skins from the still-online
  `assets-api.static.moonsama.com` bucket into `references/archive/` (gitignored, ~135 MB).
  Re-runs skip files that already exist.
- `extract_cosmetics.py` — turns the databases, the archive and the static JSON from the
  old Minecraft API into `cosmetics-data/`. Only Portal-mapped collections are exported and
  every record is keyed by Portal collection slug + token id; player identifiers from the
  legacy databases are deliberately left behind.

Run `python3 tools/legacy-extract/archive_assets.py` once, then
`python3 tools/legacy-extract/extract_cosmetics.py` whenever the mapping tables change.
