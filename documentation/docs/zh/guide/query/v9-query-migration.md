---
title: V9 查询迁移
description: 将 V8 查询 JVM API 迁移到聚合级 Gateway 与 ObjectNode Backend。
---

# V9 查询迁移

## 迁移边界

V9 删除旧 JVM 类型，不提供 bridge、typealias 或 deprecation 过渡。该变更会破坏依赖旧类型的 JVM 源码与二进制；请重新编译下游代码，并按下表直接迁移。

HTTP 路径、请求/响应 JSON 结构、生成 OpenAPI、wire 结构、存储布局和既有数据不因这次 JVM 重构改变。无需迁移存储数据。当前 V9 同时临时删除全部 Mask 能力，因此原本被遮蔽的响应字段会返回原值；HTTP 值语义与保密语义不保持。静态注解替代方案由后续独立任务交付。

## JVM 类型映射

| V8 源码 | V9 源码 |
| --- | --- |
| `QueryService<R>` | 已删除；职责拆分为 `QueryBackend` 与聚合绑定的 `QueryGateway<R>` |
| `QueryGateway<R>` / `AbstractQueryGateway<R>` | 名称保留，但改为聚合绑定合同 |
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
| `NoOpSnapshotQueryService<S>` | `NoOpSnapshotQueryBackend` |
| `NoOpEventStreamQueryService` | `NoOpEventStreamQueryBackend` |
| `NoOpSnapshotQueryServiceFactory` | `NoOpSnapshotQueryBackendFactory` |
| `NoOpEventStreamQueryServiceFactory` | `NoOpEventStreamQueryBackendFactory` |
| `QueryServiceRegistrar` | `QueryGatewayRegistrar` |
| `SnapshotQueryServiceRegistrar` | `SnapshotQueryGatewayRegistrar` |
| `EventStreamQueryServiceRegistrar` | `EventStreamQueryGatewayRegistrar` |
| `QueryServiceProxy` / `SnapshotQueryServiceProxy` / `EventStreamQueryServiceProxy` | 已删除；直接注入聚合级 Gateway |
| `DynamicDocument` / `SimpleDynamicDocument` | `tools.jackson.databind.node.ObjectNode` |
| `DynamicDocumentMasker` | 已删除；当前版本无替代 |
| `AggregateDynamicDocumentMasker` | 已删除；当前版本无替代 |
| `StateDynamicDocumentMasker` | 已删除；当前版本无替代 |
| `EventStreamDynamicDocumentMasker` | 已删除；当前版本无替代 |
| `AggregateDataMasker` / `DefaultAggregateDataMasker` | 已删除；当前版本无替代 |
| `DataMaskerRegistry` / `AbstractDataMaskerRegistry` | 已删除；当前版本无替代 |
| `StateDataMaskerRegistry` / `EventStreamMaskerRegistry` | 已删除；当前版本无替代 |
| `DataMasker` / `DataMasking` / `tryMask` | 已删除；当前版本无替代 |
| `MaskingDynamicDocumentQueryFilter` | 已删除；当前版本无替代 |
| `QueryType.DYNAMIC_SINGLE` | `QueryType.SINGLE` |
| `QueryType.DYNAMIC_LIST` | `QueryType.LIST` |
| `QueryType.DYNAMIC_PAGED` | `QueryType.PAGED` |
| `QueryType.isDynamic` | 已删除；typed 与节点路径共享操作类型 |

typed 与节点返回共享 `SINGLE`、`LIST`、`PAGED` 操作类型。Backend 始终返回 `ObjectNode`，Gateway 在通用结果 Filter 完成后按需使用 Jackson 物化 typed 结果。

原 `QueryService<R>` 没有一对一替代类型：存储查询与 Schema 能力迁移到返回 `ObjectNode` 的 `QueryBackend`，受管入口、过滤链与 typed 物化留在聚合级 `QueryGateway<R>`。原 `QueryGateway` 每次调用接收 `NamedAggregate`；V9 在构造 Gateway 时绑定 `NamedAggregate` 与 routed Backend，因此 `single`、`list`、`paged`、`count` 和 `aggregate` 调用不再传聚合参数。自定义 `AbstractQueryGateway` 子类必须按新构造合同提供 `namedAggregate`、`backend`、`targetType`、`filters`、`filterType` 与 `errorHandler`；没有自定义入口策略时直接使用 Snapshot/EventStream 默认 Gateway。

Filter 不再通过 `QueryType.isDynamic` 判断最终返回 typed 对象还是节点；两条路径在同一 ObjectNode FilterChain 中处理，区别仅发生在链完成后的可选 Jackson 物化。删除只为 typed/dynamic 分流的分支，不要发明新的结果类型判别器。

删除旧 Mask 类型、实现、Bean、Registry 与自定义 Filter；当前版本不建立 ObjectNode Mask 兼容层，也不提供内建替代。Snapshot、EventStream 与直接 aggregate-state load 都不会自动脱敏。在后续静态注解方案交付前，调用方必须把原始字段值视为临时降级边界，并使用访问控制或外部隔离保护敏感端点。

## Spring Bean 映射

| V8 Bean | V9 Bean |
| --- | --- |
| `*.SnapshotQueryService` | `*.SnapshotQueryGateway` |
| `*.EventStreamQueryService` | `*.EventStreamQueryGateway` |

新 Bean 的精确全名是 `{contextAlias.}{aggregateName}.SnapshotQueryGateway` 与 `{contextAlias.}{aggregateName}.EventStreamQueryGateway`；没有 context alias 时省略前缀。删除旧 Bean 后不注册 alias。

## Binding 配置值保持不变

JVM Factory 类型已改名为 Backend Factory，但公开 binding 字符串有意保持兼容，仍使用 `*-query-service-factory` 后缀，例如 `mongo-snapshot-query-service-factory` 与 `elasticsearch-event-stream-query-service-factory`。不要因为 JVM 类型改名而修改已有路由配置值。

## 调用入口

业务代码注入聚合级 Gateway，让请求过滤、ABAC、通用结果处理与错误观察经过一条 around chain。只有受信低层诊断、Backend 合同测试与存储扩展直接调用 Backend Factory；该路径绕过 Gateway 治理。

Schema handler 也使用 routed Backend Factory，因此 Schema 与实际查询按 `NamedAggregate` 选择同一 Backend。通用 `QueryFilter` 不标注 `@FilterType`；只有模型专属过滤器才限定对应 Gateway 类型。

## ObjectNode 所有权

自定义 Backend 返回的 Publisher 每次订阅都必须创建由该订阅独占的可变 `ObjectNode`；`retry`、`repeat` 和并发订阅也必须分别得到新节点。不得跨订阅缓存或共享节点、发布缓存节点，或在节点发出后异步继续修改。

Backend 边界只允许标准 JSON tree。存储驱动的 `Map`/`Document`、BSON 值、`POJONode` 和任意 POJO 必须在 Backend 内规范化或被拒绝。

## 传输与错误语义

JSON 数组与 SSE 的流式行为保持不变。若流在输出部分元素后失败，已输出元素不会回滚；SSE 会尝试发送一个 `ErrorInfo` 错误事件。`RequestExceptionHandler` 失败，或该错误事件生成、渲染、序列化失败时，只要失败不同于原始错误且尚未记录，就附加为 suppressed error；原始终止错误始终继续传播，迁移不能把这种部分失败改写为空结果或成功完成。

## 最小迁移步骤

1. 按表替换 import、构造参数、Bean qualifier 与 Factory 实现。
2. 让自定义 Backend 的每次订阅返回独占、只含标准 JSON tree 的新 `ObjectNode`，把 typed 转换留给 Gateway。
3. 删除全部旧 Mask 实现、Bean 与 Registry；确认当前临时版本的查询和 aggregate-state load 会返回原始字段值，并收紧敏感端点访问。
4. 重新编译、启动 Spring 上下文，并分别验证 JVM、HTTP/OpenAPI、Schema、实际存储路由与临时 Mask 降级边界。
