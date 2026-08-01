---
title: CoCache
description: CoCache-based projection caching for Wow framework, providing event-driven cache refresh strategies.
---

# CoCache

The CoCache extension integrates the [CoCache](https://github.com/Ahoo-Wang/CoCache) distributed caching framework with Wow's CQRS read model, providing event-driven cache refresh capabilities.

## Features

- **Event-driven cache refresh**: Automatically refresh or evict cache entries when domain events are received
- **Two refresh strategies**: Evict (remove stale entries) and Set (update with latest state)
- **Flexible cache sources**: Local QueryService or remote REST API for cache loading

## Installation

Add the `wow-cocache` dependency:

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-cocache")
```

::: warning
`wow-cocache` is a library, not a Spring Boot auto-configured feature variant. You must
manually wire the cache source, cache refresher, and CoCache `Cache` bean. There is no
`wow.cocache.enabled` property — the module activates when you register its beans.
:::

## Getting Started

### 1. Configure the CoCache Cache

Create a CoCache `Cache<String, YourCacheData>` bean (see the
[CoCache](https://github.com/Ahoo-Wang/CoCache) documentation for backend configuration):

```kotlin
@Configuration
class CacheConfiguration {
    @Bean
    fun orderCache(): Cache<String, OrderCacheData> = MapClientSideCache()
}
```

`MapClientSideCache` is suitable for a local example. Select and configure a production cache implementation using the CoCache documentation.

### 2. Register a Cache Source (cache miss loader)

The cache source loads data from the local `SnapshotQueryService` when the cache misses:

```kotlin
@Bean
fun orderCacheSource(
    queryService: SnapshotQueryService<OrderState>
): QueryServiceCacheSource<OrderState, OrderCacheData> {
    return QueryServiceCacheSource(
        queryService,
        StateToCacheDataConverter { snapshot ->
            OrderCacheData(
                orderId = snapshot.state.id,
                status = snapshot.state.status,
                totalAmount = snapshot.state.totalAmount,
            )
        },
    )
}
```

### 3. Register a Cache Refresher (event-driven refresh)

The refresher is both a Wow `MessageFunction` (auto-registered as an event handler) and a
cache updater. Choose `EvictStateCacheRefresher` (invalidate on domain events) or
`SetStateCacheRefresher` (proactively update on state events):

```kotlin
@Component
class OrderCacheRefresher(
    namedAggregate: NamedAggregate,        // the aggregate this cache tracks
    cache: Cache<String, OrderCacheData>,  // the CoCache cache from step 1
) : EvictStateCacheRefresher<String, Any, OrderCacheData>(
    namedAggregate = namedAggregate,
    cache = cache,
)
```

When a domain event for the target aggregate arrives, the refresher evicts (or updates) the
cache entry. On the next cache read, the source reloads the latest snapshot from the
`SnapshotQueryService`.

## Cache Refresh Strategies

Both refreshers extend `StateCacheRefresher` and are wired to a `Cache<K, D>`
(the CoCache `Cache` SPI, not the `CoCache` coordinator itself). The first
constructor argument is the `NamedAggregate` the refresher subscribes to.

### Evict Strategy

Listens to **domain events** (`FunctionKind.EVENT`) and removes the stale cache
entry, forcing a cache miss on the next read:

```kotlin
class OrderCacheRefresher(
    namedAggregate: NamedAggregate, // e.g. injected or resolved from metadata
    cache: Cache<String, OrderCacheData>
) : EvictStateCacheRefresher<String, Any, OrderCacheData>(
    namedAggregate = namedAggregate,
    cache = cache,
)
```

`EvictStateCacheRefresher<K, S : Any, D>` derives the cache key from the event's
`aggregateId.id` by default; override the `keyConvert` lambda to map it to a
different key type.

### Set Strategy

Listens to **state events** (`FunctionKind.STATE_EVENT`) and proactively updates
the cache with the latest aggregate state. When the state is deleted it evicts
instead of setting:

```kotlin
class OrderCacheRefresher(
    namedAggregate: NamedAggregate, // e.g. injected or resolved from metadata
    converter: StateToCacheDataConverter<ReadOnlyStateAggregate<OrderState>, OrderCacheData>,
    cache: Cache<String, OrderCacheData>
) : SetStateCacheRefresher<String, OrderState, OrderCacheData>(
    namedAggregate = namedAggregate,
    stateToCacheDataConverter = converter,
    cache = cache,
)
```

`SetStateCacheRefresher` also implements CoCache `TtlConfiguration`
(`ttl` / `ttlAmplitude`, defaulting to `CoCache.DEFAULT_TTL`), so each refreshed
entry is written as a TTL-backed `DefaultCacheValue`.

## Cache Sources

A `StateCacheSource<String, MaterializedSnapshot<S>, D>` loads a snapshot by key
when the cache misses.

### QueryServiceCacheSource

Uses the local `SnapshotQueryService` (single-result query by `aggregateId`) to
load aggregate snapshots into cache:

```kotlin
val cacheSource = QueryServiceCacheSource<OrderState, OrderCacheData>(
    snapshotQueryService,
    StateToCacheDataConverter { snapshot -> /* map to OrderCacheData */ },
)
```

### QueryApiCacheSource

`QueryApiCacheSource<S>` is an **interface** that combines
`ReactiveSnapshotQueryApi<S>` (the `wow-apiclient` REST client) with
`StateCacheSource`. Implement it on your API client and it loads snapshots via
`getById(key)`, returning empty when the remote returns not-found:

```kotlin
@Component
class OrderQueryApiCacheSource(
    private val delegate: ReactiveSnapshotQueryApi<OrderState>
) : QueryApiCacheSource<OrderState>, ReactiveSnapshotQueryApi<OrderState> by delegate
```
