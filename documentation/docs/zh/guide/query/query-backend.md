---
title: 查询后端
description: 查询后端的逻辑 Query、Schema、原生编译与节点所有权合同。
---

# 查询后端

## QueryBackend 契约

`QueryBackend` 是聚合绑定的原生查询执行边界。每个操作都接收一个 `AdmittedQuery`：最终逻辑 Query、本次订阅取得的 Schema、查询入口，以及查询中每个字段引用的解析结果。它只能由准入创建，未经校验的查询到不了 Backend：

```kotlin
val cursorPositions: CursorPositionCodec
fun stream(query: AdmittedQuery<IListQuery>): Flux<ObjectNode>
fun page(query: AdmittedQuery<Queryable<*>>, window: PageWindow): Mono<BackendPage>
fun count(query: AdmittedQuery<FilterExpression>): Mono<Long>
fun aggregate(query: AdmittedQuery<AggregationQuery>, window: GroupWindow): Flux<ObjectNode>
```

核心由这四个原语派生出每种查询形态（`BackendQueries` 中的 `single`、`list`、`paged`、`cursor` 与 `aggregate`）：single 为 `page(Offset(0, 1, withTotal = false))`，list 为 `stream`，paged 为 `page(Offset(offset, size, withTotal = true))`，cursor 为 `page(Keyset(after, size + 1))`，多取的一条决定是否还有下一页。`BackendPage` 带着记录、窗口要求时的总数，以及 keyset 窗口下每条记录的原生游标位置。窗口只由核心决定。

Backend 不读取 Provider，不执行请求策略、公共查询校验，不查找字段，也不做响应脱敏。它按准入登记的字段解析结果编译 Filter、Projection、Sort 与 Aggregation，检查原生参数，再访问存储；未知字段不能直接当成物理路径使用。typed 物化由 Gateway 完成。

准入为每个带字段的节点分配新实例，并以节点身份登记它的 `ResolvedField`：`admitted.field(reference)` 回答准入后查询中的一个 `QueryField`，`admitted.systemField(filter)` 回答 `TENANT_ID`、`DELETION` 等系统字段过滤。以身份而不是相等性为键，所以不同元素作用域里写法相同的条件、调用方在不同作用域复用的同一个 `QueryField`，都各自解析。`ResolvedField` 包含逻辑绝对路径、元素祖先、所在元素作用域的物理容器（`physicalParent`）、准入时使用的能力，以及按该能力绑定、已替换具体键的绝对物理字段 `physicalField`；`relativePhysicalField` 是它相对容器的路径。MongoDB 在 `$elemMatch` 内使用相对路径，在 `$unwind` 之后使用绝对路径；Elasticsearch 使用绝对路径和 nested 作用域。Projection 解析到独立的投影 binding，可以选择节点及其后代；后端本地生成的通配表达式不进入公共 Query。

原生编译器只接受准入后的查询，没有直接按物理字段编译手写过滤的入口。事件存储不经过它们：`EventStore` 的各项操作直接用自己的类型化参数（聚合 ID、版本或时间范围），在事件存储自己写入的字段上构造原生检索，不经过准入，也不需要 Schema。

## Factory 与路由

`SnapshotQueryBackendFactory.create(namedAggregate)` 与 `EventStreamQueryBackendFactory.create(namedAggregate)` 返回 `QueryBackendBinding`，显式配对 Backend 和 `QueryModelSchemaProvider`。抽象 Factory 缓存完整 binding；Routing Factory 原子转发它。Spring Registrar 在创建聚合 Gateway 时选择一次路由，此后查询与 Schema HTTP 端点使用同一对对象。

存储通过 `QueryBackendProvider` SPI 注册它的 Factory：一个 `name`，以及它提供的快照和/或事件流 Factory。Spring starter 收集所有 provider Bean，按名称路由；新增存储只需实现后端并注册 provider，不需要改动 starter：

```kotlin
@Bean
fun archiveQueryBackendProvider(factory: ArchiveSnapshotQueryBackendFactory): QueryBackendProvider =
    QueryBackendProvider.snapshot("archive", factory)
```

内置存储以存储名注册（`mongo`、`elasticsearch`），路由的 `storage` 与默认存储都解析到这个名称；路由的 `binding` 可以指向任何其他 provider。提供不同读模型的 provider 可以同名（MongoDB 在各自的存储条件下分别注册快照与事件流 provider）；同名 provider 提供同一读模型时启动失败。

应用通常注入 `SnapshotQueryGateway<OrderState>` 或按 Bean 名限定 `EventStreamQueryGateway`。直接 Factory 调用适合受信诊断、合同测试和存储扩展，会绕过 Gateway 的请求准备、scope、ABAC、Mask 与 Observer。

低层调用者必须明确承担这些责任。`QueryAdmission` 执行准入的最后几步（游标的身份字段唯一排序、公共字段校验、规范化与字段解析），但不做 Gateway 的请求准备。例如执行原始列表查询：

```kotlin
val binding = factory.create(namedAggregate)
val query = ListQuery(MatchAllFilter, limit = 10)
val rows = binding.schemaProvider.schema().flatMapMany { schema ->
    binding.backend.list(QueryAdmission.list(query, schema))
}
```

这段代码的 `MatchAllFilter` 不限定删除状态；Backend、`FilterNormalizer` 与 Compiler 不会替它追加 `ACTIVE`。Snapshot 低层调用者如只需未删除数据，应显式传入 `DeletionFilter(DeletionState.ACTIVE)`。这段代码没有授权或脱敏，不能替代业务 Gateway。

## 数值原生语义

数值比较使用 binding 对应的存储精度，不能以 source 任意精度相等解释 `EXACT_MATCH`。标量字段指标保留原生聚合；数组/联合字段和算术字段叶子遵循[每记录一个数值贡献](./aggregation-query.md#numeric-contributions)的合同。Backend 不通过扫描 source 重建数组配对，runtime 输出也须符合逻辑数值模型。

## 节点所有权

每次订阅必须获得独占的可变 `ObjectNode`，包括 retry、repeat 与并发订阅。不得跨订阅共享缓存节点，也不得在发布后异步修改。MongoDB Document、Elasticsearch source Map、BSON 与 POJO 在后端规范化为标准 JSON tree；不可用的 JSON 值必须被拒绝。

## 游标执行

准入在校验前补充唯一排序：Snapshot 为 `aggregateId`，EventStream 为 `id`。Backend 不再追加。

MongoDB 使用 keyset，Elasticsearch 使用无 PIT 的 search_after；均不执行 count 或 offset，不返回 total。`CURSOR_SORT` 独立于 `SORT`，只接受已绑定的单值字段，不能穿过数组祖先或引用受 Mask 保护的源。准入拒绝映射到同一物理字段的游标排序。

游标位置是存储自己的值：MongoDB 取物理排序字段上的 BSON 值（在剥离只为游标补投影的字段之前读取），Elasticsearch 取 `hit.sort()`。Backend 用自己的 `CursorPositionCodec` 编码位置；位置外面的令牌由核心负责：版本、指纹（模型，即聚合与读模型，加上有效排序的字段名与方向）与载荷，以无填充的 Base64URL 编码。下一页令牌编码本页最后一条记录的位置，从不使用记录里的值，所以脱敏后的值不会进入令牌。无法解码、或为其他模型或排序签发的令牌，在任何 I/O 之前按 `Invalid cursor.` 拒绝，客户端回到第一页；本格式之前签发的令牌同样拒绝。

令牌不签名、不加密，不承载授权：每一页都重新准入，keyset 条件与完整的准入过滤取 AND，伪造的位置只相当于调用方自己写的范围条件。指纹不含过滤条件与 Schema 版本，所以每页重算时间边界的调用方可以继续翻页。游标没有跨请求快照；并发写入可能改变后续页面。

## 存储支持声明

除了每个字段的原生能力，存储适配器还在 `QueryModelSchema.storage` 中声明分页方式（keyset 分页、不限量流式）与聚合方式（HAVING、按指标取前 N、dense 补空、百分位、去重计数），各自为 `NATIVE`、`RESIDUAL` 或 `NONE`。`RESIDUAL` 的算子由核心在 Backend 之后用共享的纯函数计算，并相应调整下发的查询：从查询中去掉 HAVING、指标排序或 dense 标记，算子需要全部分组时请求 `GroupWindow.All`，再依次执行 dense 补空、HAVING、前 N 或 limit。声明为 `NONE` 的能力在任何 I/O 之前拒绝。MongoDB 全部原生计算；Elasticsearch 的 composite 聚合没有 bucket selector、不能按指标排序、也没有空桶，所以把 HAVING、按指标取前 N 与 dense 补空声明为 `RESIDUAL`。

Schema 端点与错误语义见[查询模型 Schema](./query-model-schema.md)、[WebFlux](../extensions/webflux.md)和[OpenAPI](../open-api.md)。
