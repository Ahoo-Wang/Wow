---
title: 查询网关
description: 理解查询请求如何经过上下文、过滤器链、权限与结果处理后到达查询后端。
---

# 查询网关

## 为什么查询先经过 Gateway

`SnapshotQueryGateway<S>` 与 `EventStreamQueryGateway` 是业务查询入口，也是策略执行边界。Spring 为每个聚合注册绑定后的 Gateway，使请求重写、HTTP 护栏、权限过滤和通用结果处理在同一条 around chain 中执行。

业务代码通常不应绕过 Gateway。只有基础设施扩展或明确需要原始后端语义时，才直接使用 Factory；这种调用不会执行 Gateway 策略链。

## 执行链

完整链路如下：

```mermaid
sequenceDiagram
    participant Caller as 调用方
    participant Entry as WebFlux Handler / JVM
    participant Gateway as 聚合绑定 Gateway
    participant Provider as QueryModelSchemaProvider
    participant Filters as 一条 around chain
    participant Backend as 绑定的 QueryBackend
    participant Mask as SchemaMaskQueryFilter
    participant Jackson as 可选类型化转换
    Caller->>Entry: Query DTO / DSL
    Entry->>Gateway: 作用域重写后的查询
    Gateway->>Provider: 每次订阅获取一次 Schema
    Provider-->>Gateway: 非空 QueryModelSchema
    Gateway->>Gateway: 创建 QueryContext(query, schema)
    Gateway->>Filters: 执行请求过滤器
    Filters-->>Gateway: 最终逻辑查询
    Gateway->>Gateway: 用 context.schema 解析并验证
    Gateway->>Backend: ResolvedQuery(query, context.schema)
    Backend-->>Filters: ObjectNode / PagedList / CursorPage / count
    Filters-->>Mask: 完成全部结果过滤（single/list/paged/cursor）
    Mask-->>Jackson: 脱敏后的 ObjectNode
    Jackson-->>Caller: ObjectNode 或类型化结果
```

Registrar 在装配 Gateway 时按 `NamedAggregate` 调用一次路由 Factory，并把选中的 Backend 绑定到 Gateway；每次请求不会再次路由。每次订阅中，Gateway 先从 Provider 获取一次 Schema，再构造 Context、运行 Filter、按配置的验证模式解析最终查询，并只把 `ResolvedQuery` 交给 Backend。Backend 统一产生 `ObjectNode`，不获取 Schema，也不决定验证模式。框架强制装配在最外层的 `SchemaMaskQueryFilter` 在全部通用结果 Filter 完成后读取 `QueryContext.schema` 并脱敏；Filter、Resolver、Backend 与 Mask 始终共享同一个 Schema 实例。typed 结果最后才由 Jackson 物化。Schema 不可用时所有受管 Gateway 调用都在 Context 与 Backend 之前失败关闭；count 保持 `Long` 且不执行结果脱敏，aggregation 保持 `ObjectNode` 行，并由 Schema 拒绝对 Mask 字段的分组、metric 与 expression 引用。

## QueryContext 与 QueryType

Gateway 在每次订阅时获取 Provider 当前发布的一个 Schema，并用它创建独立的 `QueryContext`，因此同一个响应式 Publisher 的不同订阅不会共享查询、结果或属性。Context 从 Filter 链开始就暴露非空、不可变的 `schema` 引用，并保存聚合标识、查询对象、结果和 `QueryType`，供过滤器重写查询或结果。

`QueryType` 只有 `SINGLE`、`LIST`、`PAGED`、`CURSOR`、`COUNT` 与 `AGGREGATION`；typed 与 `ObjectNode` 返回共享同一种操作类型。具体查询模型、入口和协议暴露能力仍可能不同。

## 快照与事件流过滤链

`SnapshotQueryGateway` 与 `EventStreamQueryGateway` 使用同一种 `QueryFilter<QueryContext<*, *>>` 合同。通用 `QueryFilter` 不需要 `@FilterType`，会进入两种 Gateway；只属于某个模型的过滤器才用 `@FilterType(SnapshotQueryGateway::class)` 或 `@FilterType(EventStreamQueryGateway::class)` 限定。

## WebFlux 请求边界

`RewriteRequestFilter` 在 Gateway 之前补入 tenant、owner 和 space 条件，快照与事件流 WebFlux 请求都会经过这一步。进程内调用不会自动获得这些 HTTP 请求作用域。

`HttpQueryGuardFilter` 同时属于两个 Gateway，但只有 Reactor Context 中存在 `ServerRequest` 时才生效；它不会改变普通进程内查询的约束。

游标不是策略快照。每个后续 HTTP 请求都会重新补入 tenant、owner 与 space 条件，并重新执行授权、原始 filter、HTTP guard、结果 Filter 和 `SchemaMasker`；token 不承载或恢复任何授权状态。进程内的每次订阅同样重新执行 Gateway chain。

## ABAC 与字段脱敏

内建 `AbacQueryFilter` 位于快照查询网关。`QueryGateway` 通过独立于 Backend 的构造参数接收 `QueryModelSchemaProvider`。当前 Spring 装配中，Registrar 在 routed Backend 上调用 `requiredQueryModelSchemaProvider()`，再把取得的 Provider 传给 Gateway；这只是当前装配方式。框架内建 `SchemaMaskQueryFilter` 会在全部通用结果 Filter 完成后、typed 物化前执行 Schema 驱动的字段脱敏。配对的 Provider 不可用时，查询在 Filter 与 Backend 执行前失败关闭，绝不会仅跳过 Mask。Snapshot 与 EventStream 的 typed、dynamic、cursor 和 aggregate-state load 入口共享这条受管路径。注解、缓存、行为矩阵与失败关闭规则见[字段脱敏](./masking.md)。

认证、Principal 绑定和完整的失败关闭策略请参阅[数据权限](../data-access.md)。

## 原始 Factory 边界

直接调用 `SnapshotQueryBackendFactory` 或 `EventStreamQueryBackendFactory` 会绕过整条 Gateway 治理链，包括 Schema 获取与准入、ABAC、结果 Filter 与字段脱敏；调用方必须自行构造已接受的 `ResolvedQuery`。这条返回原始值的受信低层边界只适合存储扩展、聚焦诊断和后端合同测试；常规应用代码应注入聚合绑定的 Gateway。

## Bean 名

聚合级 Bean 名精确为 `{contextAlias.}{aggregateName}.SnapshotQueryGateway` 与 `{contextAlias.}{aggregateName}.EventStreamQueryGateway`；没有 context alias 时省略前缀。快照 Gateway 还以状态泛型注册，事件流 Gateway 没有状态泛型，多候选时应按 Bean 名限定。

## 验证策略边界

Gateway 负责策略链与 Schema 准入，不替代后端字段能力或应用业务校验。Spring 的 `wow.query.schema.validation-mode` 只控制 Gateway 的解析准入；Backend 从不选择验证模式。`QueryModelSchema` 会在 Cursor 字段准入前追加模型专属唯一排序：Snapshot 使用 `aggregateId`，EventStream 使用流记录 `id`。最终有效 sort 必须精确解析、是单值字段、不能携带任何 Mask rule，也不能通过 projection 或物理 binding alias 指向 masked 字段。Mask rule 包括 `@Mask`、`@KeepMask` 与自定义 `@Masking` meta-annotation 编译出的规则；Schema 不可用时失败关闭，不会按兼容模式降级。JSON 数组/SSE 流若已输出部分行后失败，已输出行不会回滚；SSE 会尝试发送一个 `ErrorInfo` 错误事件。`RequestExceptionHandler` 失败，或该错误事件生成、渲染、序列化失败时，只要失败不同于原始错误且尚未记录，就附加为 suppressed error；原始终止错误始终继续传播，部分失败绝不会成功完成。
