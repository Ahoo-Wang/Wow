---
title: V9 查询迁移
description: 将 V8 查询 JVM API 迁移到聚合级 Gateway 与 ObjectNode Backend。
---

# V9 查询迁移

## 迁移边界

V9 删除旧 JVM 类型，不提供 bridge、typealias 或 deprecation 过渡。该变更会破坏依赖旧类型的 JVM 源码与二进制；请重新编译下游代码，并按下表直接迁移。

HTTP 路径与请求/响应 JSON、生成 OpenAPI、wire 格式、存储布局和既有数据不因这次 JVM 重构改变。无需迁移存储数据。

## JVM 类型映射

| V8 源码 | V9 源码 |
| --- | --- |
| `SnapshotQueryService<S>` | `SnapshotQueryGateway<S>` |
| `EventStreamQueryService` | `EventStreamQueryGateway` |
| `QueryServiceCacheSource` | `QueryGatewayCacheSource` |
| `SnapshotQueryServiceFactory` | `SnapshotQueryBackendFactory` |
| `EventStreamQueryServiceFactory` | `EventStreamQueryBackendFactory` |
| `AbstractSnapshotQueryServiceFactory` | `AbstractSnapshotQueryBackendFactory` |
| `AbstractEventStreamQueryServiceFactory` | `AbstractEventStreamQueryBackendFactory` |
| `RoutingSnapshotQueryServiceFactory` | `RoutingSnapshotQueryBackendFactory` |
| `RoutingEventStreamQueryServiceFactory` | `RoutingEventStreamQueryBackendFactory` |
| `AbstractMongoQueryService` | `AbstractMongoQueryBackend` |
| `MongoSnapshotQueryService` | `MongoSnapshotQueryBackend` |
| `MongoEventStreamQueryService` | `MongoEventStreamQueryBackend` |
| `MongoSnapshotQueryServiceFactory` | `MongoSnapshotQueryBackendFactory` |
| `MongoEventStreamQueryServiceFactory` | `MongoEventStreamQueryBackendFactory` |
| `AbstractElasticsearchQueryService` | `AbstractElasticsearchQueryBackend` |
| `ElasticsearchSnapshotQueryService` | `ElasticsearchSnapshotQueryBackend` |
| `ElasticsearchEventStreamQueryService` | `ElasticsearchEventStreamQueryBackend` |
| `ElasticsearchSnapshotQueryServiceFactory` | `ElasticsearchSnapshotQueryBackendFactory` |
| `ElasticsearchEventStreamQueryServiceFactory` | `ElasticsearchEventStreamQueryBackendFactory` |
| `SnapshotQueryServiceFactoryBinding` | `SnapshotQueryBackendFactoryBinding` |
| `EventStreamQueryServiceFactoryBinding` | `EventStreamQueryBackendFactoryBinding` |
| `UnavailableQueryService` | `UnavailableQueryBackend` |
| `QueryServiceRegistrar` | `QueryGatewayRegistrar` |
| `SnapshotQueryServiceRegistrar` | `SnapshotQueryGatewayRegistrar` |
| `EventStreamQueryServiceRegistrar` | `EventStreamQueryGatewayRegistrar` |
| `QueryServiceProxy` / `SnapshotQueryServiceProxy` / `EventStreamQueryServiceProxy` | 已删除；直接注入聚合级 Gateway |
| `DynamicDocument` / `SimpleDynamicDocument` | `tools.jackson.databind.node.ObjectNode` |
| `DynamicDocumentMasker` | `ObjectNodeMasker` |
| `QueryType.DYNAMIC_SINGLE` | `QueryType.SINGLE` |
| `QueryType.DYNAMIC_LIST` | `QueryType.LIST` |
| `QueryType.DYNAMIC_PAGED` | `QueryType.PAGED` |

typed 与节点返回共享 `SINGLE`、`LIST`、`PAGED` 操作类型。Backend 始终返回 `ObjectNode`，Gateway 在结果掩码之后按需使用 Jackson 物化 typed 结果。

## Spring Bean 映射

| V8 Bean | V9 Bean |
| --- | --- |
| `*.SnapshotQueryService` | `*.SnapshotQueryGateway` |
| `*.EventStreamQueryService` | `*.EventStreamQueryGateway` |

新 Bean 的精确全名是 `{contextAlias.}{aggregateName}.SnapshotQueryGateway` 与 `{contextAlias.}{aggregateName}.EventStreamQueryGateway`；没有 context alias 时省略前缀。删除旧 Bean 后不注册 alias。

## Binding 配置值保持不变

JVM Factory 类型已改名为 Backend Factory，但公开 binding 字符串有意保持兼容，仍使用 `*-query-service-factory` 后缀，例如 `mongo-snapshot-query-service-factory` 与 `elasticsearch-event-stream-query-service-factory`。不要因为 JVM 类型改名而修改已有路由配置值。

## 调用入口

业务代码注入聚合级 Gateway，让请求过滤、ABAC、Backend 节点结果掩码与错误观察经过一条 around chain。只有受信低层诊断、Backend 合同测试与存储扩展直接调用 Backend Factory；该路径绕过 Gateway 治理。

Schema handler 也使用 routed Backend Factory，因此 Schema 与实际查询按 `NamedAggregate` 选择同一 Backend。通用 `QueryFilter` 不标注 `@FilterType`；只有模型专属过滤器才限定对应 Gateway 类型。

## ObjectNode 所有权与掩码

自定义 Backend 返回的 Publisher 每次订阅都必须创建由该订阅独占的可变 `ObjectNode`；`retry`、`repeat` 和并发订阅也必须分别得到新节点。不得跨订阅缓存或共享节点、发布缓存节点，或在节点发出后异步继续修改。

Backend 边界只允许标准 JSON tree。存储驱动的 `Map`/`Document`、BSON 值、`POJONode` 和任意 POJO 必须在 Backend 内规范化或被拒绝。

`ObjectNodeMasker` 可以原位修改输入节点或返回替代节点，但不得缓存、跨订阅共享、异步发布，或在调用返回后继续修改。输出必须保留 Snapshot/EventStream 必需信封字段和 typed 目标可物化的字段类型；违规时 typed 查询 fail-closed，Gateway 不恢复字段，也不绕过掩码。

## 传输与错误语义

JSON 数组与 SSE 的流式行为保持不变。若流在输出部分元素后失败，已输出元素不会回滚；SSE 会尝试发送一个 `ErrorInfo` 错误事件。`RequestExceptionHandler` 失败，或该错误事件生成、渲染、序列化失败时，只要失败不同于原始错误且尚未记录，就附加为 suppressed error；原始终止错误始终继续传播，迁移不能把这种部分失败改写为空结果或成功完成。

## 最小迁移步骤

1. 按表替换 import、构造参数、Bean qualifier 与 Factory 实现。
2. 让自定义 Backend 的每次订阅返回独占、只含标准 JSON tree 的新 `ObjectNode`，把 typed 转换留给 Gateway。
3. 将结果掩码实现迁移到 `ObjectNodeMasker`，并核对通用/模型专属过滤器选择。
4. 重新编译、启动 Spring 上下文，并分别验证 JVM、HTTP/OpenAPI、Schema 和实际存储路由。
