---
title: CoCache
description: 基于 CoCache 的投影缓存扩展，提供事件驱动的缓存刷新策略。
---

# CoCache

CoCache 扩展将 [CoCache](https://github.com/Ahoo-Wang/CoCache) 分布式缓存框架与 Wow 的 CQRS 读模型整合，提供事件驱动的缓存刷新能力。

## 特性

- **事件驱动缓存刷新**：收到领域事件时自动刷新或逐出缓存条目
- **两种刷新策略**：逐出（Evict，移除过期条目）和更新（Set，写入最新状态）
- **灵活的缓存加载源**：支持本地 QueryService 或远程 REST API 加载缓存

## 安装

添加 `wow-cocache` 依赖：

=== "Gradle (Kotlin)"

```kotlin
implementation("me.ahoo.wow:wow-cocache")
```

## 缓存刷新策略

### 逐出策略（Evict）

逐出策略在收到领域事件时移除过期的缓存条目，下次读取时强制缓存未命中：

```kotlin
class OrderCacheRefresher(
    cache: CoCache<*, OrderState, *>
) : EvictStateCacheRefresher<String, OrderState, Any>(cache)
```

### 更新策略（Set）

更新策略在收到状态事件时主动将最新的聚合状态写入缓存：

```kotlin
class OrderCacheRefresher(
    cache: CoCache<*, OrderState, *>,
    converter: StateToCacheDataConverter<OrderState, OrderCacheData>
) : SetStateCacheRefresher<String, OrderState, Any>(cache, converter)
```

## 缓存加载源

### QueryServiceCacheSource

使用本地 `SnapshotQueryService` 加载聚合快照到缓存：

```kotlin
val cacheSource = QueryServiceCacheSource(snapshotQueryService, converter)
```

### QueryApiCacheSource

使用远程 REST API 加载聚合快照，适用于分布式场景：

```kotlin
val cacheSource = QueryApiCacheSource(reactiveSnapshotQueryApi)
```
