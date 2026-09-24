# Operator guide: running the kit on a real server

This is the production counterpart of [local-development.md](local-development.md). It
assumes you run a Paper server for other people (VPS, dedicated box or a game panel) and
want players to link their Moonsama Portal account, wear NFT skins and use gated items.

Read [Security rules](../README.md#security-rules) first. The short version: keep
`online-mode=true`, never share your Portal credentials, and every independently operated
server needs its own Portal app.

## 1. Requirements

| What | Version / note |
| --- | --- |
| Paper | **26.3** (`api-version: '26.3'`). Each kit release states the Paper build it was tested with (`paperBuild` in `gradle.properties`, currently 40). 26.3 is alpha; newer builds usually work, older ones may not. |
| Java | 25 (Temurin or any OpenJDK 25). |
| Network | Players reach the server on `25565`. One extra **public HTTPS URL** for the Portal OAuth callback (see §4). |
| Portal | A Moonsama Portal **production** API key pair and OAuth client for this server (§3). |
| Disk | The plugins store everything in `plugins/MoonsamaCore/data.db` (SQLite). A few MB. |
| Optional | A [MineSkin](https://account.mineskin.org) API key if you want `MoonsamaWardrobe` to sign composed skins. |

Velocity / proxies are **not** supported yet (linking and the callback server live in the
backend Paper instance; nothing forwards Portal identity across servers).

## 2. Install the plugins

Download the release assets from the
[GitHub releases page](../../../releases) and verify them:

```bash
sha256sum -c SHA256SUMS
```

Copy the JARs you want into `plugins/`. Names are stable across versions, so upgrading is
a file replacement.

| JAR | Purpose | Requires | Default state |
| --- | --- | --- | --- |
| `MoonsamaCore.jar` | Account linking, Portal API, economy, SQLite, resource pack serving. **Always required.** | - | on |
| `MoonsamaSkins.jar` | `/skins`: wear the signed skin of a held Moonsama / Exosama / Gromlin / Embassy / Multiverse Avatar. | Core | on |
| `MoonsamaItems.jar` | `/items`: weapon skins, off-hands with gameplay perks, 3D hats. Needs the resource pack. | Core (+Skins for hats) | on; each perk can be switched off |
| `MoonsamaWardrobe.jar` | `/wardrobe`: compose custom skins from held parts. | Core, Skins; `MINESKIN_API_KEY` for wearing | on (browse-only without MineSkin) |
| `MoonsamaWhaleBuffs.jar` | Moon Power buffs (health, damage, name colour, Whale Scepter). | Core (+Skins) | on |
| `MoonsamaGatekeeper.jar` | Holder-only access gate with waiting room. | Core | **off** until `enabled: true` |
| `MoonsamaOffhandDemo.jar` | Sample plugin for developers (`/offhanddemo`, `/buyrelic` spend demo). | Core | do **not** install on a public server |

Load order is handled by `depend`/`softdepend` in the plugins themselves; just drop the
files in. Start the server once so every plugin writes its default `config.yml` under
`plugins/<PluginName>/`, then stop it and configure.

`resourcepack.zip` goes to `plugins/MoonsamaCore/resourcepack.zip` (§5).

## 3. Portal app and credentials

Each server gets its own credentials so that a leak or abuse on one server cannot affect
another, and so that Portal can attribute spends and rewards.

1. In the Moonsama Portal admin, create (or ask Moonsama to create) an **app for your
   server** and generate an **API key + secret**. Sandbox keys (the ones used in local
   development) only see sandbox assets; production keys see the real collections
   (`moonsama`, `exosama`, `gromlin`, `moonsama-embassy`, `moonsama-multiverse-art-eth`,
   `moonsama-x`, `sama`, `gold`, ...).
2. Create an **OAuth login client** with **exactly** the redirect URI you will expose in §4,
   for example `https://mc.example.com/callback`. Portal rejects mismatched URIs, including
   `http` vs `https` or a trailing slash.
3. Give the credentials to `MoonsamaCore` **through environment variables**, not by editing
   the JAR or committing them anywhere:

   | Variable | Config key | Meaning |
   | --- | --- | --- |
   | `PORTAL_API_KEY` / `PORTAL_API_SECRET` | `portal.api-key` / `portal.api-secret` | Production key pair for this server. |
   | `PORTAL_OAUTH_CLIENT_ID` / `PORTAL_OAUTH_CLIENT_SECRET` | `oauth.client-id` / `oauth.client-secret` | The login client from step 2. |
   | `PORTAL_OAUTH_REDIRECT_URI` | `oauth.redirect-uri` | Must match step 2 byte for byte. |
   | `PORTAL_API_URL` / `PORTAL_WEB_URL` | `portal.api-url` / `portal.web-url` | Leave at the defaults (`https://portal-api.moonsama.com`, `https://portal.moonsama.com`). |
   | `PORTAL_CALLBACK_BIND_HOST` / `PORTAL_CALLBACK_PORT` | `oauth.callback-bind-host` / `oauth.callback-port` | Where the embedded HTTP server listens (default `0.0.0.0:8080`). Bind to `127.0.0.1` when the reverse proxy runs on the same host. |
   | `PORTAL_SPEND_ENABLED`, `PORTAL_REWARD_ENABLED`, `PORTAL_REFUND_ENABLED`, `PORTAL_HOLDS_ENABLED` | `economy.*` | Value-moving routes, all **off** by default. Enable only what your plugins call. |
   | `MINESKIN_API_KEY` | `skins.mineskin-api-key` | Optional; enables wearing composed wardrobe skins. |
   | `MOONSAMA_SQLITE_JOURNAL_MODE` | `storage.journal-mode` | `wal` (default) or `truncate`, see §7. |

   An environment variable wins over the config value whenever it is set. How you set
   them depends on the host: a `systemd` unit `Environment=` / `EnvironmentFile=`, a Docker
   `environment:` block or `env_file`, or the "startup variables" of your game panel. If
   your panel only lets you edit files, put the values in
   `plugins/MoonsamaCore/config.yml` and make sure that file is not world-readable and
   is excluded from any public backups or Git repositories.

On startup `MoonsamaCore` logs `Moonsama Portal integration is ready.` when all four
credentials are present, otherwise `Portal credentials are not configured` and
`/moonsama link` refuses to start.

## 4. Public HTTPS callback

When a player runs `/moonsama link`, they open Portal in a browser, approve access, and
Portal redirects the browser to your **redirect URI**. The plugin then shows a
confirmation page naming the Minecraft player ("link Portal account X to Minecraft player
**Steve**?"); only after the Portal user clicks **Yes, link** is the link saved. That
request has to reach the embedded HTTP server inside the plugin (`/callback` and
`/callback/confirm` on `oauth.callback-port`). Browsers and Portal both expect HTTPS on a
real hostname, so put a reverse proxy in front:

```
player browser --HTTPS--> mc.example.com (nginx/Caddy/Traefik) --HTTP--> 127.0.0.1:8080
```

Minimal Caddy configuration (automatic TLS):

```
mc.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Equivalent nginx `server` block (with certificates from certbot or your provider):

```nginx
server {
    listen 443 ssl;
    server_name mc.example.com;
    ssl_certificate     /etc/letsencrypt/live/mc.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/mc.example.com/privkey.pem;

    # prefix match: also covers /callback/confirm and /callback/cancel (POST)
    location /callback        { proxy_pass http://127.0.0.1:8080; }
    location /resourcepack.zip { proxy_pass http://127.0.0.1:8080; }
}
```

Then:

- `PORTAL_OAUTH_REDIRECT_URI=https://mc.example.com/callback` (and the identical value in
  the Portal login client).
- `PORTAL_CALLBACK_BIND_HOST=127.0.0.1` so port 8080 is not reachable from the internet
  directly; only the proxy talks to it. Do not open 8080 in the firewall.
- The HTTP surface is `GET /callback?code=...&state=...` (single-use `state` + PKCE, expires
  after `oauth.attempt-ttl-minutes`, default 10) and the `POST /callback/confirm` /
  `POST /callback/cancel` forms of the confirmation page (single-use token, 5 minutes).
  There is nothing else to secure behind the proxy.
- Tell your players what the in-game message already says: never open a Portal link
  another player sent them. The `state` identifies the Minecraft account that *started*
  the flow, so a forwarded link would connect the victim's Portal account to the sender's
  Minecraft account - the confirmation page (which shows the Minecraft name) is the step
  where the Portal owner can catch that.

If you run Paper in Docker, publish 8080 only to localhost (`"127.0.0.1:8080:8080"`) and
proxy to it, or attach the proxy to the same Docker network.

Multiple servers behind one domain: give each its own hostname or path prefix and its own
Portal login client. Each server's redirect URI must be unique and match its client.

## 5. Resource pack hosting

`MoonsamaItems` needs the kit's resource pack (item skins, off-hand models, hats and the
sounds the perks play). `MoonsamaCore` loads `plugins/MoonsamaCore/resourcepack.zip`,
computes its SHA-1 and tells every joining player to download it from
`resource-pack.public-url`. Two options:

1. **Serve it from the plugin** (simplest). The embedded HTTP server already exposes
   `/resourcepack.zip`; with the proxy from §4 set
   `resource-pack.public-url: 'https://mc.example.com/resourcepack.zip'`.
2. **Host it on a CDN / object storage.** Upload the *same* `resourcepack.zip` and point
   `public-url` at it. The SHA-1 is computed from the local file, so the bytes must be
   identical - re-upload after every upgrade.

`resource-pack.required: true` (default) kicks players who decline the pack; set it to
`false` if you prefer vanilla-looking items for players who refuse. Set
`resource-pack.enabled: false` if you do not install `MoonsamaItems` at all.

The pack only adds Moonsama models, textures and sounds on top of vanilla, so it
coexists with players' own packs. It contains Moonsama-owned art only - you may host it as
is.

## 6. Configure the feature plugins

Every plugin writes a documented `config.yml` on first start. The knobs operators usually
touch:

- **`MoonsamaGatekeeper`** - `enabled: true` to turn the gate on; `passes:` lists the
  collections (optionally specific `token-ids`) that count as a ticket; `unlinked` /
  `denied` / `unavailable` each choose `kick`, `restrict` (a waiting room where the player
  can still `/moonsama link`) or `allow`. Defaults: unlinked players get 10 minutes in the
  waiting room, linked players without a pass are kicked, Portal outages let everyone in.
  Operators bypass the gate with `moonsama.gatekeeper.bypass`.
- **`MoonsamaWhaleBuffs`** - `power.per-token` (Moon Power per held NFT per collection),
  `health-per-power`, `damage-per-power`, `names.tiers`, `whale-mode` (scepter). With
  `skins.require-entitled-skin: true` (default) buffs only apply while the player wears
  an entitled NFT skin.
- **`MoonsamaItems`** - `item-skins.enabled`, `offhands.enabled`, `hats.enabled`;
  `offhands.perks.enabled` switches all gameplay perks off (items stay cosmetic) and
  `offhands.perks.disabled` lists individual off-hand ids whose perk is off (flight, ore
  glow, explosives, ...). `unequip-when-sold` strips cosmetics whose token left the
  account.
- **`MoonsamaWardrobe`** - `signing-budget.cooldown-seconds` / `max-per-hour` cap how many
  *new* looks a player can have MineSkin-signed (default 20 s / 30 per hour; already-signed
  looks are free). Raise it if your MineSkin plan is generous, lower it if you see quota
  errors in the log. `moonsama.wardrobe.unlimited` exempts staff.
- **`MoonsamaSkins`** - `collections` (which collections show in `/skins`),
  `uniform-collections` (one entry regardless of count, e.g. Gromlin).
- **`MoonsamaCore`** - `sync.holdings-ttl-seconds` (how stale holdings may be before
  a refresh, default 60), `sync.changes-interval-seconds` (Portal change feed polling, 5).

Permissions (`default: true` = everyone): `moonsama.use`, `moonsama.skins.use`,
`moonsama.items.use`, `moonsama.wardrobe.use`. Operator-only: `moonsama.admin`
(`/moonsama admin status`), `moonsama.gatekeeper.admin`, `moonsama.gatekeeper.bypass`,
`moonsama.whalebuffs.admin`. Use your permissions plugin to restrict the player-facing
ones (for example to a "linked" group) if you want.

Restart the server after config changes; `/gatekeeper reload` and `/whalebuffs reload`
re-read their own files without a restart.

## 7. Storage, backups and file systems

All state lives in `plugins/MoonsamaCore/data.db`: account links (player UUID → Portal
`plr_…` id), holdings cache, and - if you enable economy routes - idempotency keys,
pending writes, receipts and holds. Losing it means players link again and any spend
that was in flight is reconciled against Portal receipts on the next start, which is by
design, but back it up with the rest of the server anyway.

- **Backup**: stop the server, or copy `data.db` *and* its `-wal`/`-shm` (WAL mode) or
  `-journal` (truncate mode) side files together. `sqlite3 data.db ".backup out.db"` is
  the safe online alternative.
- **Journal mode**: leave `storage.journal-mode: wal` on a normal local disk. On Docker
  Desktop bind mounts, NFS/SMB shares, or game panels that mount server files from a
  network volume, WAL memory-maps a shared file and can crash the JVM with `SIGBUS`. Set
  `MOONSAMA_SQLITE_JOURNAL_MODE=truncate` there; the plugin converts the database on the
  next start.
- The per-plugin folders (`plugins/MoonsamaSkins/`, ...) hold only config files;
  skins and item data ship inside the JARs.

## 8. Upgrades

1. Read the release notes / `CHANGELOG.md` for config changes. New config keys are **not**
   added to an existing `config.yml`; the plugins fall back to built-in defaults, so an
   old file keeps working. Compare with the fresh default in the JAR when a note mentions a
   new option you want.
2. Stop the server, replace the JARs (same file names) and `resourcepack.zip`, start.
   The database schema migrates itself.
3. If you host the pack externally, re-upload it (§5).
4. Paper: stay on 26.3. When a kit release moves to a newer Paper build it is stated in
   the notes; upgrading Paper independently is normally fine as long as the major stays
   26.3.

## 9. Checklist before opening the server

- [ ] `online-mode=true` in `server.properties`.
- [ ] Production `PORTAL_*` credentials set through the environment, not in a public file.
- [ ] `https://<host>/callback` reaches the plugin (open it in a browser: you should see
      the plugin's `Missing OAuth code or state.` page, not a proxy error).
- [ ] Redirect URI identical in Portal and `PORTAL_OAUTH_REDIRECT_URI`.
- [ ] Port 8080 not exposed publicly; only via the proxy.
- [ ] `resource-pack.public-url` downloads the zip from outside your network.
- [ ] Only the economy routes your plugins use are enabled.
- [ ] `MoonsamaOffhandDemo.jar` not installed.
- [ ] Backup of `plugins/MoonsamaCore/data.db` in your regular backup job.
- [ ] Test with a real account: `/moonsama link`, `/moonsama holdings`, `/skins`.

## Getting help

Open an issue on the repository with the plugin versions (`/version MoonsamaCore`),
Paper build, and the relevant log lines - strip API keys and secrets from anything you
paste. Problems with Portal accounts, keys or collections go to the Moonsama team.
