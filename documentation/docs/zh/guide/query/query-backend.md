---
title: 查询后端
description: 了解 QueryService、Spring 类型化 Bean、Factory 缓存与存储实现之间的关系。
---

# 查询后端

## QueryService 契约

`QueryService<R>` 是聚合查询后端契约，提供 typed 与 dynamic 的 single、list、paged、count 和 aggregation 操作。`SnapshotQueryService<S>` 返回 `MaterializedSnapshot<S>`，而 `EventStreamQueryService` 返回 `DomainEventStream`。聚合始终返回动态文档行；后端未支持时，默认 `aggregate` 会失败。

## 注入类型化的 SnapshotQueryService Bean

Spring 可按状态类型注入快照查询服务：

```kotlin
@Component
class OrderReader(
    private val queryService: SnapshotQueryService<OrderState>,
) {
    fun find(query: PagedQuery): Mono<PagedList<MaterializedSnapshot<OrderState>>> =
        queryService.paged(query)
}
```

这是一条应用内 JVM 入口；受管服务可经由代理进入[查询网关](query-gateway.md)。

## Bean 注册与命名

`SnapshotQueryServiceRegistrar` 使用 `ResolvableType` 注册 `SnapshotQueryService<STATE>`，Bean 名为 `{contextAlias.}{aggregateName}.SnapshotQueryService`。这让 Spring 能以状态泛型选择快照服务。

存在同名 Bean 或不存在对应 Gateway 时，会保留原始、未代理的查询服务；不要把这描述成常规业务扩展点。

## QueryServiceProxy 如何路由

`QueryServiceProxy` 保留后端的 `name` 与 `namedAggregate`，并把 single、list、paged、count 和 aggregation 转交相应 Gateway。代理本身不实现后端查询，也不把两个查询模型混为一个服务。

## Factory、缓存与存储路由

`SnapshotQueryServiceFactory` 与 `EventStreamQueryServiceFactory` 是原始服务的创建入口；其抽象基类按 materialized aggregate 缓存服务。Routing Factory 先查找聚合专属路由，未命中时使用默认 Factory，最终由 MongoDB、Elasticsearch 或其他已配置实现执行查询。

Factory 的创建结果不经过 Gateway。应用代码应优先使用 Spring 注册的类型化服务；后端选择与物理查询编译属于存储扩展的职责。

## EventStreamQueryService Bean

`EventStreamQueryServiceRegistrar` 也按聚合注册 Bean，命名规则使用 `.EventStreamQueryService`。事件流服务没有 `STATE` 泛型；存在多个候选时，应按 Bean 名限定，而不是依赖泛型消歧。

## 原始后端访问

直接使用 Factory 适合受信基础设施扩展或明确要求原始后端语义的场景。它会绕过 Gateway 的查询重写、ABAC 与结果脱敏；同名自定义 Bean 和 Gateway 缺失也有同样的未代理边界。

## Schema Provider 差异

快照代理不实现 Schema Provider，而事件流代理实现 Provider 并委托原始服务。WebFlux 目前只暴露快照 Schema 路由；不要由事件流代理具有 Provider 推导出事件流 Schema HTTP 路由。
