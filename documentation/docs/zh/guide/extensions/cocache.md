---
title: CoCache
description: 用 Wow 快照查询和领域/状态事件驱动 CoCache 缓存加载与刷新。
---

# CoCache

`wow-cocache` 连接 Wow 查询/事件合同与 CoCache `Cache` SPI。需要按聚合 ID 缓存读模型，并希望在领域事件到达时逐出或按状态事件主动更新时使用；它不自动创建 cache backend，也不自动注册任何业务缓存。

## 特性

- `QueryGatewayCacheSource` 从本地 `SnapshotQueryGateway` 加载；
- `QueryApiCacheSource` 从远程 `ReactiveSnapshotQueryApi` 加载，并把 404 转为空；
- `EvictStateCacheRefresher` 消费领域事件并逐出；
- `SetStateCacheRefresher` 消费状态事件并 set，deleted state 改为逐出。

应用拥有 cache key、数据转换、TTL、backend、容量、一致性目标和 Bean 注册；CoCache backend 拥有 eviction/TTL/存储语义。

## 安装

```kotlin
implementation("me.ahoo.wow:wow-cocache")
```

Starter 没有 `cocache-support` capability，也没有 `wow.cocache.*` 自动配置。最小可用条件是：一个现有 `Cache<K,D>`、一个 source（需要 miss load 时）以及一个显式注册的 refresher（需要事件刷新时）。

## 快速开始

先确定聚合、key 类型、缓存 DTO 和刷新策略，再只注册所需组件。

### 1. 配置 CoCache 缓存

使用应用现有 CoCache 配置创建 `Cache<String, OrderView>`。`wow-cocache` 不选择 Redis、本地 map 或其他 backend，也不复制 backend 的连接/序列化校验。

### 2. 注册缓存源（缓存未命中时加载）

```kotlin
@Bean
fun orderCacheSource(queryGateway: SnapshotQueryGateway<OrderState>) =
    QueryGatewayCacheSource(
        snapshotQueryGateway = queryGateway,
        stateToCacheDataConverter = { snapshot -> OrderView(snapshot.state) },
    )
```

默认 `LoadCacheSourceConfiguration` 为 `timeout=10s` 和 CoCache 默认 TTL/amplitude。`loadCacheValue` 是 CoCache 同步 SPI：它等待 Reactor source，超时或查询异常会传播；不要从 Reactor event loop 直接调用阻塞 miss loader。

### 3. 注册缓存刷新器（事件驱动刷新）

```kotlin
@Bean
fun orderCacheRefresher(
    cache: Cache<String, OrderView>,
) = EvictStateCacheRefresher<String, OrderState, OrderView>(
    namedAggregate = ORDER,
    cache = cache,
)
```

Refresher 是 `MessageFunction`，必须进入应用的事件处理器发现/注册流程；构造对象本身不会订阅总线。

## 缓存刷新策略

逐出更简单，下一次读取重新加载；主动 set 减少 miss，但依赖状态事件总线、converter 和 TTL 正确。两者都只提供最终一致的缓存刷新，不是与 EventStore 同一事务。

### 逐出策略（Evict）

`EvictStateCacheRefresher` 的 `functionKind=EVENT`，默认把 `aggregateId.id` 转为 key 并调用 `cache.evict`。key 类型不是 String 时提供 `keyConvert`，不要依赖 unchecked cast。

### 更新策略（Set）

`SetStateCacheRefresher` 的 `functionKind=STATE_EVENT`。非 deleted 状态经 `StateToCacheDataConverter` 写成 `DefaultCacheValue.ttlAt`，deleted 状态逐出。TTL 默认来自 CoCache，也可在构造时覆盖。

## 缓存加载源

source 只负责 miss load 与 DTO 转换。权限、tenant/space 作用域和不存在语义必须由所调用 Gateway/API 的公共合同保证。

### QueryGatewayCacheSource

它构造按 `aggregateId` 的 single query。空结果返回 `null` cache value；查询错误和 timeout 不会伪装成 cache miss。

### QueryApiCacheSource

它同时实现 `ReactiveSnapshotQueryApi` 与 `StateCacheSource`，`getById` 的 not-found 转为空，其他 HTTP/解码失败继续传播。不要把远端不可用缓存为“不存在”。

已验证失败/边界：空 source 不写缓存；deleted state 逐出；miss loader timeout/异常传播；cache 操作异常使 refresher 失败并由事件处理恢复策略处理。

聚焦检查：

```bash
./gradlew :wow-cocache:check
```

下一步阅读[查询](../query.md)和[投影](../projection.md)，再用所选 cache backend 的真实 TTL/eviction 测试验证一致性。
