# Building on MoonsamaCore

`MoonsamaCore` registers `MoonsamaService` with Paper's `ServicesManager`.
Builder plugins declare a hard dependency on `MoonsamaCore` and load the
service during `onEnable`.

```java
MoonsamaService moonsama = getServer()
        .getServicesManager()
        .load(MoonsamaService.class);
```

The API exposes asynchronous reads:

```java
CompletableFuture<Optional<PortalPlayer>> linkedPlayer(UUID mojangUuid);
CompletableFuture<List<AssetHolding>> holdings(UUID mojangUuid);
CompletableFuture<HoldingsSnapshot> cachedHoldings(UUID mojangUuid);
```

Both methods complete asynchronously. World, inventory, entity, and player
changes must be scheduled back onto Paper's main thread.

The core plugin also emits:

- `PortalPlayerLinkedEvent`
- `PortalHoldingsLoadedEvent`
- `PortalPlayerErasedEvent`
- `EconomyOperationEvent`
- `AssetHoldChangedEvent`

See `examples/offhand-demo` for an entitlement check, custom item, and fixed
price purchase. `moonsama-skins` is a complete feature plugin built the same
way: it reads `cachedHoldings` for the menu, confirms with `holdings` before
applying a skin when the cache is cold, revalidates on
`PortalHoldingsLoadedEvent`, and drops its records on `PortalPlayerErasedEvent`.
It also registers `SkinService` with the services manager and fires
`MoonsamaSkinChangedEvent` so other plugins can react to skin changes.

## Spend, reward, and refund

Feature plugins choose the collection, token, amount, and a stable business
key. `MoonsamaCore` resolves the linked Portal player, namespaces the
idempotency key per plugin/server, journals the request, sends it, stores the
receipt, and recovers uncertain responses.

```java
moonsama.spend(this, new EconomyRequest(
    "craft:" + craftingJobId,
    List.of(new EconomyLine(player.getUniqueId(), "sandbox-gold", "0", "10")),
    "Portal Relic",
    "craft:" + craftingJobId
)).thenAccept(operation -> {
    // SUCCEEDED means Portal committed and the receipt is durable.
    // Schedule Minecraft inventory/world changes on Paper's main thread.
});
```

The same plugin name plus business key always addresses the same operation.
Reusing it with different economic lines is rejected locally. Never create a
new key merely because a request timed out; `RECOVERING` means the core is
checking whether the original write landed.

Amounts are decimal strings in token units. Use `"10"` for 10 tokens and
`"1.5"` for one and a half tokens. The catalog's `decimals` field limits the
allowed fractional precision; never multiply amounts by `10^decimals`.

Rewards use `reward(...)`. Refunds use `EconomyRefundRequest` and name the
original operation's business key. Full refunds pass `items = null`.

Minecraft fulfillment is a separate transaction. Persist enough state in the
feature plugin to retry item delivery after a successful receipt. The
`/buyrelic` example records pending fulfillment and tags the granted item with
the operation ID.

## Holds

`placeHold(...)` journals a reference before placement because Portal hold
creation has no idempotency key. If the response is lost, the core lists the
player's active holds and matches its namespaced reference before placing
again. Session plugins should call `renewHold(...)` at half the TTL and
`releaseHold(...)` on completion. The core recovers uncertain renewal and
release results; if the server crashes permanently, the Portal lease expires.

## Asset mappings

Portal exposes generic `(collection, tokenId, balance)` holdings. A game plugin
owns the meaning:

```text
sandbox-items / 1 / balance > 0 -> may use demo offhand
```

Keep these mappings in the feature plugin or its configuration. Do not add a
global hierarchy of hard-coded NFT Java classes to the Portal client.

## Security boundary

Feature plugins receive operations, not Portal credentials. The API is
deliberately low-level for trusted server plugins, so each feature plugin must
own its price table and authorization rules. Never accept collection, token,
amount, or business key directly from an untrusted Minecraft client.

Server operators independently enable `spend`, `reward`, `refund`, and `holds`
in `plugins/MoonsamaCore/config.yml` or with `PORTAL_*_ENABLED` environment
variables. `/moonsama admin status` shows gates, queue states, and the latest
observed rate limit.
