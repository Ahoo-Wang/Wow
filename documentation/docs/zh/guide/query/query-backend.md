---
title: 查询后端
description: 查询后端的逻辑 Query、Schema、原生编译与节点所有权合同。
---

# 查询后端

## QueryBackend 契约

`QueryBackend` 是聚合绑定的原生查询执行边界。Gateway 传入最终逻辑 Query 与本次订阅取得的同一个 Schema：

```kotlin
fun single(query: ISingleQuery, schema: QueryModelSchema): Mono<ObjectNode>
fun list(query: IListQuery, schema: QueryModelSchema): Flux<ObjectNode>
fun paged(query: IPagedQuery, schema: QueryModelSchema): Mono<PagedList<ObjectNode>>
fun cursor(query: ICursorQuery, schema: QueryModelSchema): Mono<CursorPage<ObjectNode>>
fun count(query: FilterExpression, schema: QueryModelSchema): Mono<Long>
fun aggregate(query: AggregationQuery, schema: QueryModelSchema): Flux<ObjectNode>
```

Backend 不读取 Provider，不执行请求策略、公共查询校验或响应脱敏。它按 Schema 的原生 binding 编译 Filter、Projection、Sort 与 Aggregation，检查原生参数及物理作用域，再访问存储；未知字段不能直接当成物理路径使用。typed 物化由 Gateway 完成。

`QueryFieldSchema.value` 是该逻辑值的定义；`binding(capability).physicalField` 是绝对物理路径。MongoDB 在元素谓词内显式计算相对路径，Elasticsearch 使用绝对路径和 nested 作用域。Projection 使用独立的投影 binding，可以选择节点及其后代；后端本地生成的通配表达式不进入公共 Query。

## Factory 与路由

`SnapshotQueryBackendFactory.create(namedAggregate)` 与 `EventStreamQueryBackendFactory.create(namedAggregate)` 返回 `QueryBackendBinding`，显式配对 Backend 和 `QueryModelSchemaProvider`。抽象 Factory 缓存完整 binding；Routing Factory 原子转发它。Spring Registrar 在创建聚合 Gateway 时选择一次路由，此后查询与 Schema HTTP 端点使用同一对对象。

应用通常注入 `SnapshotQueryGateway<OrderState>` 或按 Bean 名限定 `EventStreamQueryGateway`。直接 Factory 调用适合受信诊断、合同测试和存储扩展，会绕过 Gateway 的请求准备、scope、ABAC、Mask 与 Observer。

低层调用者必须明确承担这些责任。例如，只做公共字段校验并执行原始列表查询：

```kotlin
val binding = factory.create(namedAggregate)
val query = ListQuery(MatchAllFilter, limit = 10)
val rows = binding.schemaProvider.schema().flatMapMany { schema ->
    binding.backend.list(validateQuery(query, schema), schema)
}
```

这段代码的 `MatchAllFilter` 不限定删除状态；Backend、`FilterNormalizer` 与 Compiler 不会替它追加 `ACTIVE`。Snapshot 低层调用者如只需未删除数据，应显式传入 `DeletionFilter(DeletionState.ACTIVE)`。这段代码没有授权或脱敏，不能替代业务 Gateway。

## 数值原生语义

数值比较使用 binding 对应的存储精度，不能以 source 任意精度相等解释 `EXACT_MATCH`。标量字段指标保留原生聚合；数组/联合字段和算术字段叶子遵循[每记录一个数值贡献](./aggregation-query.md#numeric-contributions)的合同。Backend 不通过扫描 source 重建数组配对，runtime 输出也须符合逻辑数值模型。

## 节点所有权

每次订阅必须获得独占的可变 `ObjectNode`，包括 retry、repeat 与并发订阅。不得跨订阅共享缓存节点，也不得在发布后异步修改。MongoDB Document、Elasticsearch source Map、BSON 与 POJO 在后端规范化为标准 JSON tree；不可用的 JSON 值必须被拒绝。

## 游标执行

Gateway 在校验前补充唯一排序：Snapshot 为 `aggregateId`，EventStream 为 `id`。Backend 不再追加。原始调用者自行提供完整有效排序。

MongoDB 使用 keyset，Elasticsearch 使用无 PIT 的 search_after；均读取 size+1，不执行 count 或 offset，不返回 total。`CURSOR_SORT` 独立于 `SORT`，只接受已绑定的单值字段，不能穿过数组祖先或引用受 Mask 保护的源。后端检查原生排序字段重复与 token 结构。

token 是无签名、无加密的 Base64URL continuation，不承载授权。调用者原样传回即可。游标没有跨请求快照；并发写入可能改变后续页面。

Schema 端点与错误语义见[查询模型 Schema](./query-model-schema.md)、[WebFlux](../extensions/webflux.md)和[OpenAPI](../open-api.md)。
