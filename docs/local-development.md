# Local development

## Portal sandbox setup

Open `https://portal-admin.moonsama.com` and use the **Sandbox** tab.

1. Click **Get a sandbox key**.
2. Save both the API key and one-time API secret.
3. Register `http://127.0.0.1:8080/callback` as the redirect URI.
4. Click **Create login client**.
5. Save the OAuth client ID and one-time client secret.

Sandbox keys expire after seven days. Requesting a replacement revokes the
previous key.

Create `.env` from `.env.example` and set:

```dotenv
PORTAL_API_KEY=...
PORTAL_API_SECRET=...
PORTAL_OAUTH_CLIENT_ID=...
PORTAL_OAUTH_CLIENT_SECRET=...
```

Do not add `.env` to Git.

## Build and start

```bash
make up
```

Works the same on a host with Docker only (Gradle runs in a JDK 25 container) and
inside the dev container (`.devcontainer/`, where `GRADLE=./gradlew` makes `make` use
the container's JDK; see [CONTRIBUTING.md](../CONTRIBUTING.md#development-environment)).

`make up` uses the checksum-pinned Paper build. Use `make up-latest` when you
want to refresh that pin from Paper's official downloads API before building.
This keeps normal builds reproducible while 26.3 is receiving frequent alpha
updates.

This command:

1. Builds all Gradle modules using Java 25 in Docker.
2. Runs unit tests.
3. Assembles `build/dist/` (every plugin JAR under its plugin name, `resourcepack.zip`,
   `SHA256SUMS`); the compose file mounts those files into the server.
4. Builds a checksum-verified Paper 26.3 alpha image.
5. Starts one online-mode Paper server.

Exposed ports:

- `25565`: Minecraft
- `8080`: OAuth callback and sample resource pack

Stop the stack with:

```bash
make down
```

Persistent local data is under `dev-data/`. The Portal identity database is:

```text
dev-data/plugins/MoonsamaCore/data.db
```

SQLite is embedded in the plugin. There is no database container or database
account to configure.

## Account-linking test

1. Join `localhost:25565` using a paid Java account.
2. Accept the required server resource pack.
3. Run `/moonsama link`.
4. Open the clickable URL in chat.
5. Sign into Portal and approve the sandbox consent screen.
6. Return to Minecraft and run `/moonsama status`.
7. Run `/moonsama holdings`.
8. Run `/offhanddemo`.
9. Run `/buyrelic`.

The sample sandbox owner starts with `sandbox-items` token `1`, so the final
command should place a renamed custom-model feather in the offhand.

With an account that holds Moonsama NFTs on the production app, `/skins` and
`/items` list the skins, weapon skins and off-hands it unlocks; `/items` needs the
resource pack to show the models. `/wardrobe` lets that account mix parts of its
Moonsamas/Exosamas; to actually wear a composed look, put a MineSkin API key in
`MINESKIN_API_KEY` (see `.env.example`) — MoonsamaCore logs
`Skin signing is disabled` when it is missing and the wardrobe stays browse-only.

`/buyrelic` spends 10 token units of `sandbox-gold` and delivers another relic.
The checked-in `.env.example` enables spend for this sandbox test only. Use
`/moonsama admin status` from the server console or as an operator to inspect
the gate and durable operation state.

## Durable operation testing

Every write is inserted into `data.db` before the Portal request is sent.
Receipts, hold leases, holdings cache entries, change/erasure cursors, and
pending outcome events use versioned tables in that same database.

To exercise restart recovery:

1. Run `/buyrelic`.
2. Stop Paper while the purchase is pending.
3. Start it again with `make up`.
4. Check `/moonsama admin status`.

For an uncertain write, the restarted core queries
`/v1/economy/receipts/{idempotencyKey}` first. It stores an existing receipt,
or resends only after Portal confirms no receipt exists. The resent request
uses the original key and payload.

## How localhost OAuth works

The browser reaches `127.0.0.1:8080` on the host. Docker forwards that request
to the callback listener embedded in `MoonsamaCore`. The listener validates the
single-use OAuth state and PKCE verifier, exchanges the code with Portal, and
shows a confirmation page naming the Minecraft player. Clicking **Yes, link**
stores Mojang UUID to Portal player ID in SQLite; **Not me** discards the login.
The extra click is what stops a player from tricking someone else into linking
their Portal account to the player's Minecraft account.

For a remote community server, `127.0.0.1` would point at the player's
computer, not the Minecraft server. Production operators need a public HTTPS
callback URL routed to port 8080, and must register that exact URL on their
Portal OAuth client - see the [operator guide](operator-guide.md).

## Troubleshooting

### Portal credentials are not configured

Check that `.env` exists beside `compose.yaml` and contains all four
credentials. Restart with `make down && make up`.

### OAuth says the redirect URI is invalid

The value in `.env` and the URI registered on the Portal client must match
exactly, including scheme, host, port, and `/callback`.

### The custom item looks like a feather

Confirm that the client accepted the server resource pack. The pack is served
from `http://127.0.0.1:8080/resourcepack.zip`.

### A player loses their link after changing name

That should not happen in online mode because links use the Mojang UUID. Do not
change the server to offline mode.

### Paper fails during startup

26.3 is an alpha target. Check the Paper logs first; the pinned build may need
an explicit upgrade after an upstream breaking change.

### The JVM crashes with `SIGBUS` while MoonsamaCore enables

The stack trace ends in `libsqlitejdbc.so` / `SqlitePortalStore.initialize`. SQLite's
WAL mode memory-maps a shared file, which virtual and network filesystems (Docker
Desktop bind mounts, NFS, some game-panel volumes) do not always back. Set
`storage.journal-mode: truncate` in `plugins/MoonsamaCore/config.yml` or the
`MOONSAMA_SQLITE_JOURNAL_MODE=truncate` environment variable. `compose.yaml` already
does this for the `dev-data/` bind mount; nothing in the database is lost either way.
