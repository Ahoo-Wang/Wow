---
title: Prepare Key
description: PrepareKey conditional reservation, TTL, rollback, key change, and transaction boundaries.
outline: deep
---

# Prepare Key

`PrepareKey<V>` provides application-level reservation for keys such as usernames or SKUs that contend across aggregates. A MongoDB, Redis, or other adapter atomically owns the mapping from key to current value. PrepareKey is not part of EventStore and is not a database transaction spanning EventStore and an external store.

## Declaration and assembly

```kotlin
@PreparableKey(name = "username")
interface UsernamePrepareKey : PrepareKey<UsernameIndex>
```

The Starter scans application base packages and `wow.prepare.base-packages` for interfaces annotated with `@PreparableKey`. The interface must expose a concrete value type through `PrepareKey<V>`. A blank annotation name falls back to the interface simple name; the Spring bean name also uses the interface simple name.

The default proxy factory can create a backend delegate only when a `PrepareKeyFactory` exists. Backend selection and enablement live in the [Core Configuration Reference](../../reference/config/core.md) and the [MongoDB](../extensions/mongo.md) / [Redis](../extensions/redis.md) extension pages.

## Operation contract

| Operation | Success condition | Meaning of `false` |
| --- | --- | --- |
| `prepare(key, value)` | The key can be reserved for this value | The key is occupied |
| `get(key)` | A record exists and its `PreparedValue` is not expired | Empty means absent or expired |
| `getValue(key)` | A record exists | Returns value and `ttlAt`, even when expired |
| `rollback(key)` | Delete the current record unconditionally | No record to delete |
| `rollback(key, value)` | Delete only when current value matches | Key absent or value mismatch |
| `reprepare(key, old, new)` | Current value equals old and is replaced with new | Key absent or old value mismatch |

The concrete `PrepareKeyFactory` owns atomicity. The interface alone does not establish lock scope, isolation level, or cross-region consistency.

## TTL

`PreparedValue` stores a value and absolute expiration time `ttlAt` in Unix epoch milliseconds:

```kotlin
val forever = value.toForever()
val temporary = value.toTtlAt(System.currentTimeMillis() + 5 * 60_000)
```

`get` filters expired values at the client-interface layer; the backend is responsible for allowing an expired key to be prepared again. TTL depends on caller/backend clocks and is not a precise business timer. Permanent values use framework constant `TTL_FOREVER`; do not copy its numeric value.

## Exact `usingPrepare` boundary

```kotlin
return usernamePrepareKey.usingPrepare(command.username, index) { prepared ->
    require(prepared) { "username is already reserved" }
    Registered(command.username).toMono()
}
```

The flow is:

1. invoke `prepare`;
2. pass the Boolean result to `then`, whether true or false;
3. only when `prepared == true` and `then` terminates with an error, invoke conditional rollback;
4. after rollback completes, propagate the original error; if rollback itself fails, the reactive chain propagates that rollback error.

The current implementation uses `onErrorResume` and does not register automatic rollback for cancellation. A successful result also does not “commit” another record: the reservation remains until explicit rollback, reprepare, or TTL expiry. “Transaction-like” therefore refers only to conditional release on the error path and must not be expanded into a cross-storage transaction guarantee.

## Change a key

```kotlin
prepareKey.reprepare(
    oldKey = state.username,
    oldValue = currentIndex,
    newKey = command.newUsername,
    newValue = currentIndex,
)
```

The default composition first executes `prepare(newKey)`, then conditional `rollback(oldKey, oldValue)`:

- if the new key is occupied, return `false` and retain the old key;
- if old key/value does not match, throw `IllegalStateException` and use the error path to attempt release of the newly reserved key;
- if old and new keys are equal, reject immediately; use the same-key `reprepare` overload instead.

These are two backend operations composed with compensation, not an indivisible cross-key transaction. Recovery still needs backend/TTL evidence when the process crashes, times out, or is cancelled between the two operations.

## Use with aggregate commands

PrepareKey fits a command decision that must reserve an external unique key, but EventStore append may fail afterward. A permanent reservation needs an explicit release/reconciliation design for command failure. A TTL reservation needs evidence that expiry and reacquisition are acceptable to the business.

Do not collapse request-ID idempotency, EventStore version concurrency, and PrepareKey uniqueness into one mechanism:

- request ID identifies a repeated command request;
- aggregate version protects one aggregate event stream;
- PrepareKey coordinates aggregates contending for one application key.

## Verification

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.infra.prepare.PrepareKeyTest"
./gradlew :wow-core:test --tests "me.ahoo.wow.infra.prepare.proxy.PrepareKeyProxyAndMetadataTest"
./gradlew :wow-mongo:integrationTest --tests "*PrepareKey*"
./gradlew :wow-redis:integrationTest --tests "*PrepareKey*"
```

The last two require their infrastructure. Applications should additionally test crash windows, TTL/clock behavior, and reconciliation after failure.

## Source

- [`PrepareKey`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/infra/prepare/PrepareKey.kt)
- [`PreparedValue`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/infra/prepare/PreparedValue.kt)
- [`PrepareKeyAutoRegistrar`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/prepare/PrepareKeyAutoRegistrar.kt)
