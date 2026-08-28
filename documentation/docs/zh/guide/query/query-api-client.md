---
title: 查询 API 客户端
description: 使用 wow-apiclient 的响应式、同步、类型化与独立快照聚合查询接口。
---

# 查询 API 客户端

## 适用范围

`wow-apiclient.query` 是远程调用 Snapshot HTTP 查询合同的 CoApi 传输接口。依赖安装、`@EnableCoApi`、服务发现、命令客户端与通用错误类型见 [API 客户端扩展](../extensions/apiclient.md)。

这些客户端是 snapshot-only（仅快照）的。它们不会读取运行时 Query Model Schema、在客户端校验字段、执行授权或替代服务端 QueryGateway 与 HTTP 护栏；路由和线协议仍以运行服务的 OpenAPI 为准。

## 接口矩阵

| 接口 | 能力或返回类型 | 相对 `@HttpExchange` 的路径 |
|---|---|---|
| `SnapshotSingleQueryApi` | single 的 typed、dynamic、state-only 合同 | `snapshot/single`、`snapshot/single/state` |
| `SnapshotListQueryApi` | list 的 typed、dynamic、state-only 合同 | `snapshot/list`、`snapshot/list/state` |
| `SnapshotPagedQueryApi` | paged 的 typed、dynamic、state-only 合同 | `snapshot/paged`、`snapshot/paged/state` |
| `SnapshotCountQueryApi` | `FilterExpression` 精确计数 | `snapshot/count` |
| `SnapshotAggregationQueryApi` | `AggregationQuery` 动态结果行 | `snapshot/aggregation` |
| `ReactiveSnapshotQueryApi` | 响应式组合 single、list、paged、count | 不包含 aggregation |
| `SynchronousSnapshotQueryApi` | 同步组合 single、list、paged、count | 不包含 aggregation |
| `ReactiveSnapshotAggregationQueryApi` | `Flux<Map<String, Any?>>` | 独立 aggregation 客户端 |
| `SynchronousSnapshotAggregationQueryApi` | `List<Map<String, Any?>>` | 独立 aggregation 客户端 |

前五个基础接口通过继承的 `@PostExchange` 注解声明上表路径。

Reactive 与 Synchronous 的细分接口已经由两个普通组合接口继承。通常直接选择组合接口即可；只有客户端只需一种能力时，才单独继承细分接口，不必把每个派生接口重复声明成公共客户端。

## 声明类型化客户端

沿用项目已有的 CoApi 声明方式，把接口绑定到聚合路由基址：

```kotlin
@CoApi(baseUrl = "http://order-service:8080")
@HttpExchange("cart")
interface CartQueryClient : ReactiveSnapshotQueryApi<CartState>

@CoApi(baseUrl = "http://order-service:8080")
@HttpExchange("cart")
interface CartAggregationClient : ReactiveSnapshotAggregationQueryApi
```

将需要物化的两个接口都注册到 `@EnableCoApi(clients = [...])`。如果 CoApi 或应用约定需要具体泛型元数据，可像仓库示例客户端一样用具体返回类型和 `@RequestBody` 重新声明方法，但不要重复方法上的路径。

## 单条、列表、分页与计数

| 操作 | 响应式结果 | 同步结果 |
|---|---|---|
| single typed / state-only / dynamic | `Mono<MaterializedSnapshot<S>>` / `Mono<S>` / `Mono<Map<String, Any>>` | 对应 nullable 值 |
| list typed / state-only / dynamic | 对应 `Flux` | 对应 `List` |
| paged typed / state-only / dynamic | `Mono<PagedList<...>>` | `PagedList<...>` |
| count | `Mono<Long>` | `Long` |

`ISingleQuery`、`IListQuery`、`IPagedQuery` 分别通过 `query`、`queryState`、`dynamicQuery` 扩展执行；`FilterExpression.count` 执行计数。`getById` 与 `getStateById` 是按 `aggregateId` 构造 single 查询的便捷方法。

## 完整快照、state-only 与动态结果

- typed 完整快照返回 `MaterializedSnapshot<S>`，同时保留 `state` 与快照系统元数据。
- state-only 返回 `S`；它只改变响应形状，请求过滤字段仍使用 `state.*` 路径。
- dynamic 返回 `Map<String, Any>`，适合 projection 改变结果形状的查询，但不保留 `S` 的编译期字段类型。
- aggregation 始终返回 `Map<String, Any?>` 动态结果行，不存在 typed 或 state-only 变体。

## 独立的聚合客户端

`ReactiveSnapshotQueryApi` 与 `SynchronousSnapshotQueryApi` 刻意不包含 aggregation。需要聚合时必须单独声明 `ReactiveSnapshotAggregationQueryApi` 或 `SynchronousSnapshotAggregationQueryApi`，并把 `AggregationQuery` 提交到 `snapshot/aggregation`：

```kotlin
val rows: Flux<Map<String, Any?>> = aggregation {
    terms("state.status", "status")
    count("count")
}.query(cartAggregationClient)
```

聚合字段、Element 路径、后端能力与成本保护仍由服务端负责，详见[快照聚合查询](./snapshot-aggregation.md)。

## Reactive 与 Synchronous

Reactive 接口使用 `Mono`/`Flux`，适合非阻塞调用链；Synchronous 接口直接返回值、`List` 或 `PagedList` 并阻塞调用线程。不要在 Reactor event loop 或 Wow 核心响应式处理路径中调用同步客户端。

两类接口提交相同的查询 DTO 和 HTTP 路径，区别只在调用与返回模型，不改变服务端查询语义。

## 404 与空结果语义

HTTP single 无匹配时返回 404。客户端提供的 `ISingleQuery.query`、`queryState`、`dynamicQuery` 以及 `getById`、`getStateById` helper 会把该 404 转换为响应式空 `Mono` 或同步 `null`。直接调用继承的 `single`、`singleState`、`dynamicSingle` 是原始 CoApi 传输调用，不经过这些 helper 的 404 转换。

正常的无匹配 list 返回空 `Flux`/`List`，paged 返回 `total = 0` 且 `list = []` 的 `PagedList`，count 返回 `0`；它们不是 single 404。校验、授权、限流、超时和后端错误仍继续传播。

## 当前不支持的事件流客户端

`wow-apiclient.query` 当前只提供 Snapshot 接口，没有 EventStream 数据查询或聚合查询客户端。服务端已发布的 EventStream HTTP 路由不能用于推导一个内置客户端；如需调用，应由应用按实际 OpenAPI 自行声明，并保持与[事件流查询](./event-stream-query.md)记录的能力边界一致。
