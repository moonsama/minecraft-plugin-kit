# MoonsamaGatekeeper

Optional Paper plugin that turns Portal holdings into a server access gate: a player may
play only while their linked Portal account holds one of the configured passes. This is the
successor of the legacy "game passes" check, made configurable.

Depends on `MoonsamaCore` only. Off by default (`enabled: false`).

## How it works

1. On join the plugin asks MoonsamaCore whether the player is linked and what the cached
   holdings say.
2. A pass is any holding matching one entry of `passes` (collection, optional token ids and
   minimum balance). Any one pass is enough.
3. Depending on the outcome one of three actions applies, each configurable:
   - **allow** – play normally.
   - **restrict** – the player stays on the server in a *waiting room*: they can look
     around, chat and use a few whitelisted commands (`/moonsama link` above all), but
     cannot move, build, fight, open containers or take damage. A title and periodic
     reminders explain what to do.
   - **kick** – disconnect with a configurable MiniMessage screen.
4. Whenever MoonsamaCore loads fresh holdings (after linking, on its periodic refresh) the
   verdict is recomputed: waiting players get in the moment a pass shows up, and with
   `recheck-on-refresh` a player whose pass was sold is handled by `denied.action`.

| Situation | Default action | Notes |
| --- | --- | --- |
| Not linked | `restrict`, kick after `grace-seconds` (600) | Newcomers can link from the waiting room. `kick` only makes sense if linking happens elsewhere. `allow` makes the gate apply to linked players only. |
| Linked, no pass | `kick` | or `restrict` |
| Holdings not loaded yet / Portal down | `allow`, decided after `wait-seconds` (15) | or `restrict` / `kick`. Stale cached holdings are trusted. |
| Has `bypass-permission` (`moonsama.gatekeeper.bypass`, ops) | allow | |

The default pass list mirrors the legacy game passes that Portal can verify: any Moonsama,
Exosama, Gromlin, Embassy or Multiverse Avatar (`moonsama-multiverse-art-eth`) NFT, or
Multiverse Items token 1 (VIP Ticket). See `cosmetics-data/game-passes.json` for the legacy
contract mapping.

## Configuration

```yaml
enabled: true
passes:
  - collection: moonsama
  - collection: moonsama-x
    token-ids: ["1"]
  - collection: some-fungible
    min-balance: 100
unlinked: { action: restrict, grace-seconds: 600 }
denied: { action: kick }
unavailable: { action: allow, wait-seconds: 15 }
recheck-on-refresh: true
allowed-commands: [moonsama, help, msg, tell, r]
reminder-seconds: 30
messages: { ... MiniMessage ... }
```

Invalid config (unknown action, pass without collection) keeps the gate **off** and logs
why; an empty pass list does the same rather than locking everyone out.

## Commands

- `/gatekeeper status [player]` – gate state, passes, and a player's verdict.
- `/gatekeeper reload` – re-read the config and re-evaluate everyone online.

Permission `moonsama.gatekeeper.admin` (ops).
