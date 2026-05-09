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

## Cache Refresh Strategies

### Evict Strategy

The evict strategy removes stale cache entries when domain events are received, forcing a cache miss on the next read:

```kotlin
class OrderCacheRefresher(
    cache: CoCache<*, OrderState, *>
) : EvictStateCacheRefresher<String, OrderState, Any>(cache)
```

### Set Strategy

The set strategy proactively updates the cache with the latest aggregate state when state events are received:

```kotlin
class OrderCacheRefresher(
    cache: CoCache<*, OrderState, *>,
    converter: StateToCacheDataConverter<OrderState, OrderCacheData>
) : SetStateCacheRefresher<String, OrderState, Any>(cache, converter)
```

## Cache Sources

### QueryServiceCacheSource

Uses the local `SnapshotQueryService` to load aggregate snapshots into cache:

```kotlin
val cacheSource = QueryServiceCacheSource(snapshotQueryService, converter)
```

### QueryApiCacheSource

Uses a remote REST API to load aggregate snapshots, suitable for distributed scenarios:

```kotlin
val cacheSource = QueryApiCacheSource(reactiveSnapshotQueryApi)
```
