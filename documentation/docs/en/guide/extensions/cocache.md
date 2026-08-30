---
title: CoCache
description: Drive CoCache loading and refresh from Wow snapshot queries and domain/state events.
---

# CoCache

`wow-cocache` connects Wow query/event contracts to CoCache's `Cache` SPI. Use it to cache an aggregate read model by ID and either evict on domain events or update on state events. It does not create a cache backend or register a business cache automatically.

## Features

- `QueryGatewayCacheSource` loads from a local `SnapshotQueryGateway`.
- `QueryApiCacheSource` loads from a remote `ReactiveSnapshotQueryApi` and converts 404 to empty.
- `EvictStateCacheRefresher` consumes domain events and evicts.
- `SetStateCacheRefresher` consumes state events and sets, or evicts deleted state.

The application owns cache keys, DTO conversion, TTL, backend, capacity, consistency target, and bean registration. The CoCache backend owns eviction, TTL, and storage semantics.

## Installation

```kotlin
implementation("me.ahoo.wow:wow-cocache")
```

Starter has no `cocache-support` capability or `wow.cocache.*` auto-configuration. Minimum runtime wiring is an existing `Cache<K,D>`, a source when miss loading is required, and an explicitly registered refresher when event-driven refresh is required.

## Getting Started

Choose the aggregate, key type, cache DTO, and refresh strategy first, then register only the components needed.

### 1. Configure the CoCache Cache

Create `Cache<String, OrderView>` through the application's existing CoCache configuration. `wow-cocache` does not choose Redis, a local map, or another backend, and does not duplicate backend connection or serialization validation.

### 2. Register a Cache Source (cache miss loader)

```kotlin
@Bean
fun orderCacheSource(queryGateway: SnapshotQueryGateway<OrderState>) =
    QueryGatewayCacheSource(
        snapshotQueryGateway = queryGateway,
        stateToCacheDataConverter = { snapshot -> OrderView(snapshot.state) },
    )
```

Default `LoadCacheSourceConfiguration` uses `timeout=10s` plus CoCache default TTL/amplitude. `loadCacheValue` is a synchronous CoCache SPI and waits for the Reactor source. Timeout or query errors propagate. Do not call the blocking miss loader directly from a Reactor event loop.

### 3. Register a Cache Refresher (event-driven refresh)

```kotlin
@Bean
fun orderCacheRefresher(
    cache: Cache<String, OrderView>,
) = EvictStateCacheRefresher<String, OrderState, OrderView>(
    namedAggregate = ORDER,
    cache = cache,
)
```

A refresher is a `MessageFunction` and must enter the application's event-processor discovery/registration flow. Constructing it does not subscribe to a bus.

## Cache Refresh Strategies

Eviction is simpler and reloads on the next read. Active set reduces misses but depends on the state-event bus, converter, and TTL. Both are eventually consistent cache refresh, not the same transaction as EventStore.

### Evict Strategy

`EvictStateCacheRefresher` has `functionKind=EVENT`, converts `aggregateId.id` to the key by default, and calls `cache.evict`. Provide `keyConvert` for non-String keys instead of relying on the unchecked default cast.

### Set Strategy

`SetStateCacheRefresher` has `functionKind=STATE_EVENT`. Non-deleted state passes through `StateToCacheDataConverter` and is stored as `DefaultCacheValue.ttlAt`; deleted state is evicted. TTL defaults come from CoCache and can be overridden in the constructor.

## Cache Sources

A source only loads a miss and converts a DTO. Authorization, tenant/space scope, and not-found semantics must come from the called Gateway/API contract.

### QueryGatewayCacheSource

It creates a single query by `aggregateId`. Empty results return a null cache value. Query errors and timeouts do not masquerade as cache misses.

### QueryApiCacheSource

It combines `ReactiveSnapshotQueryApi` with `StateCacheSource`. `getById` not-found becomes empty; other HTTP and decode failures propagate. Do not cache remote unavailability as “not found.”

Verified failures and boundaries: an empty source writes no value; deleted state evicts; miss-loader timeout/errors propagate; cache operation failures fail the refresher and enter event-processing recovery.

Focused check:

```bash
./gradlew :wow-cocache:check
```

Next, read [Query](../query.md) and [Projection](../projection.md), then verify consistency with real TTL/eviction behavior from the selected cache backend.
