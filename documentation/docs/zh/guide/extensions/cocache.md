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

```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-cocache")
```

::: warning
`wow-cocache` 是一个库，而非 Spring Boot 自动配置的特性能力。你需要手动装配缓存源、
缓存刷新器和 CoCache `Cache` Bean。不存在 `wow.cocache.enabled` 属性 —— 该模块在你注册其 Bean 时激活。
:::

## 快速开始

### 1. 配置 CoCache 缓存

创建一个 CoCache `Cache<String, YourCacheData>` Bean（后端配置请参阅
[CoCache](https://github.com/Ahoo-Wang/CoCache) 文档）：

```kotlin
@Configuration
class CacheConfiguration {
    @Bean
    fun orderCache(): Cache<String, OrderCacheData> = MapClientSideCache()
}
```

`MapClientSideCache` 适用于本地示例；生产环境的缓存实现与后端配置请以 CoCache 文档为准。

### 2. 注册缓存源（缓存未命中时加载）

缓存缓存在未命中时从本地 `SnapshotQueryService` 加载数据：

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

### 3. 注册缓存刷新器（事件驱动刷新）

刷新器既是 Wow 的 `MessageFunction`（自动注册为事件处理器），也是缓存更新器。
选择 `EvictStateCacheRefresher`（在领域事件时失效）或 `SetStateCacheRefresher`（在状态事件时主动更新）：

```kotlin
@Component
class OrderCacheRefresher(
    namedAggregate: NamedAggregate,        // 此缓存跟踪的聚合
    cache: Cache<String, OrderCacheData>,  // 步骤 1 中的 CoCache 缓存
) : EvictStateCacheRefresher<String, Any, OrderCacheData>(
    namedAggregate = namedAggregate,
    cache = cache,
)
```

当目标聚合的领域事件到达时，刷新器会逐出（或更新）缓存条目。在下一次缓存读取时，
缓存源会从 `SnapshotQueryService` 重新加载最新快照。

## 缓存刷新策略

两种刷新器都继承自 `StateCacheRefresher`，并绑定到一个 `Cache<K, D>`
（即 CoCache 的 `Cache` SPI，而非 `CoCache` 协调器本身）。构造函数的第一个参数是
该刷新器订阅的 `NamedAggregate`。

### 逐出策略（Evict）

监听**领域事件**（`FunctionKind.EVENT`），移除过期的缓存条目，下次读取时强制缓存未命中：

```kotlin
class OrderCacheRefresher(
    namedAggregate: NamedAggregate, // 例如注入或从元数据解析
    cache: Cache<String, OrderCacheData>
) : EvictStateCacheRefresher<String, Any, OrderCacheData>(
    namedAggregate = namedAggregate,
    cache = cache,
)
```

`EvictStateCacheRefresher<K, S : Any, D>` 默认以事件的 `aggregateId.id` 作为缓存键；
可重写 `keyConvert` lambda 将其映射为其他键类型。

### 更新策略（Set）

监听**状态事件**（`FunctionKind.STATE_EVENT`），主动将最新的聚合状态写入缓存。
当状态被删除时改为逐出：

```kotlin
class OrderCacheRefresher(
    namedAggregate: NamedAggregate, // 例如注入或从元数据解析
    converter: StateToCacheDataConverter<ReadOnlyStateAggregate<OrderState>, OrderCacheData>,
    cache: Cache<String, OrderCacheData>
) : SetStateCacheRefresher<String, OrderState, OrderCacheData>(
    namedAggregate = namedAggregate,
    stateToCacheDataConverter = converter,
    cache = cache,
)
```

`SetStateCacheRefresher` 还实现了 CoCache 的 `TtlConfiguration`
（`ttl` / `ttlAmplitude`，默认为 `CoCache.DEFAULT_TTL`），因此每次刷新的条目都会以
带 TTL 的 `DefaultCacheValue` 写入。

## 缓存加载源

`StateCacheSource<String, MaterializedSnapshot<S>, D>` 在缓存未命中时按键加载快照。

### QueryServiceCacheSource

使用本地 `SnapshotQueryService`（按 `aggregateId` 单结果查询）加载聚合快照到缓存：

```kotlin
val cacheSource = QueryServiceCacheSource<OrderState, OrderCacheData>(
    snapshotQueryService,
    StateToCacheDataConverter { snapshot -> /* 映射为 OrderCacheData */ },
)
```

### QueryApiCacheSource

`QueryApiCacheSource<S>` 是一个**接口**，它将 `wow-apiclient` 的 REST 客户端
`ReactiveSnapshotQueryApi<S>` 与 `StateCacheSource` 组合在一起。在你的 API 客户端上实现该接口，
它会通过 `getById(key)` 加载快照，远端返回 not-found 时转为空：

```kotlin
@Component
class OrderQueryApiCacheSource(
    private val delegate: ReactiveSnapshotQueryApi<OrderState>
) : QueryApiCacheSource<OrderState>, ReactiveSnapshotQueryApi<OrderState> by delegate
```
