---
title: V9 查询迁移
description: 将 V8 查询 JVM API 迁移到聚合级 Gateway 与 ObjectNode Backend。
---

# V9 查询迁移

## 迁移边界

V9 删除旧 JVM 类型，不提供 bridge、typealias 或 deprecation 过渡。该变更会破坏依赖旧类型的 JVM 源码与二进制；请重新编译下游代码，并按下表直接迁移。

HTTP 路径、请求/响应 JSON 结构、生成 OpenAPI、Backend wire tree、存储布局和既有数据不因这次 JVM 重构或静态注解 Mask 改变。无需迁移存储数据，Backend 与存储中的原值也不会被改写。把原 Mask 配置迁移到字段注解后，受管 Gateway 会恢复响应的保密语义。

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
| `DynamicDocumentMasker` | 已删除；在领域字段使用 `@Mask`、`@KeepMask` 或自定义 `@Masking` meta-annotation |
| `AggregateDynamicDocumentMasker` | 已删除；Snapshot 与 EventStream 由受管 Gateway 按 Query Schema 统一 Mask |
| `StateDynamicDocumentMasker` | 已删除；在状态字段声明静态 Mask 注解 |
| `EventStreamDynamicDocumentMasker` | 已删除；在事件 payload 字段声明静态 Mask 注解 |
| `AggregateDataMasker` / `DefaultAggregateDataMasker` | 已删除；不保留运行时对象 Mask SPI |
| `DataMaskerRegistry` / `AbstractDataMaskerRegistry` | 已删除；规则由 Query Schema 从字段注解发现 |
| `StateDataMaskerRegistry` / `EventStreamMaskerRegistry` | 已删除；不再注册模型级 Masker |
| `DataMasker` / `DataMasking` / `tryMask` | 已删除；迁移为字段静态注解 |
| `MaskingDynamicDocumentQueryFilter` | 已删除；Mask 固定在 Gateway 结果 Filter 之后执行 |
| `QueryType.DYNAMIC_SINGLE` | `QueryType.SINGLE` |
| `QueryType.DYNAMIC_LIST` | `QueryType.LIST` |
| `QueryType.DYNAMIC_PAGED` | `QueryType.PAGED` |
| `QueryType.isDynamic` | 已删除；typed 与节点路径共享操作类型 |

typed 与节点返回共享 `SINGLE`、`LIST`、`PAGED` 操作类型。Backend 始终返回 `ObjectNode`，Gateway 在通用结果 Filter 完成后按需使用 Jackson 物化 typed 结果。

原 `QueryService<R>` 没有一对一替代类型：存储查询与 Schema 能力迁移到返回 `ObjectNode` 的 `QueryBackend`，受管入口、过滤链与 typed 物化留在聚合级 `QueryGateway<R>`。原 `QueryGateway` 每次调用接收 `NamedAggregate`；V9 在构造 Gateway 时绑定 `NamedAggregate` 与 routed Backend，因此 `single`、`list`、`paged`、`count` 和 `aggregate` 调用不再传聚合参数。自定义 `AbstractQueryGateway` 子类必须按新构造合同提供 `namedAggregate`、`backend`、`targetType`、`filters`、`filterType` 与 `errorHandler`；没有自定义入口策略时直接使用 Snapshot/EventStream 默认 Gateway。

Filter 不再通过 `QueryType.isDynamic` 判断最终返回 typed 对象还是节点；两条路径在同一 ObjectNode FilterChain 中处理，区别仅发生在链完成后的可选 Jackson 物化。删除只为 typed/dynamic 分流的分支，不要发明新的结果类型判别器。

删除旧 Mask 类型、实现、Bean、Registry 与自定义 Filter，不建立 ObjectNode Mask 兼容层。把原规则声明到领域字段后，Snapshot、EventStream 的 typed、dynamic 与 aggregate-state load 会在同一条受管 Gateway 路径自动脱敏。直接 Backend Factory 或不提供 `QueryModelSchemaProvider` 的自定义 Backend 仍是返回原始值的受信低层边界。

## 静态 Mask 迁移

默认全量遮蔽使用 `@Mask`；手机号等需要保留前后字符的字段使用 `@KeepMask(prefix, suffix)`：

```kotlin
import me.ahoo.wow.api.query.mask.KeepMask
import me.ahoo.wow.api.query.mask.Mask

data class AccountState(
    @field:Mask
    val password: String,
    @field:KeepMask(prefix = 3, suffix = 4)
    val phone: String?,
)
```

`@Mask` 按 Unicode code point 数生成等长 `*`。`@KeepMask` 按 code point 保留两端；值太短时全量遮蔽。`null` 与空字符串保持不变。注解可位于字段或 getter，并随父类 Kotlin property、接口 getter 的成员关系继承；嵌套对象和集合由 Query Schema 路径递归处理。

特殊规则使用 `@Masking(strategy)` 定义领域注解，不需要 Registry：

```kotlin
import me.ahoo.wow.api.query.mask.CompiledMask
import me.ahoo.wow.api.query.mask.MaskStrategy
import me.ahoo.wow.api.query.mask.Masking

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@Masking(FixedMaskStrategy::class)
annotation class FixedMask(val replacement: String = "***")

object FixedMaskStrategy : MaskStrategy<FixedMask> {
    override fun compile(annotation: FixedMask): CompiledMask =
        CompiledMask { annotation.replacement }
}
```

Query Schema 在运行时发现、校验并编译 Strategy，不使用 KSP。自定义 Strategy 使用 Kotlin `object` 或公开无参类。字段必须是 `String` wire 值；多个有效规则、分支规则冲突、非 String 分支或 Strategy 构建失败都会让 Schema 失败关闭。EventStream 启用 Mask 后，缺失或未知 `bodyType` 会终止整个结果 Publisher，不会降级返回原值。

公开 Schema 元数据只增加字段级 `masked: Boolean`；Strategy、注解参数与可执行 `MaskRule` 只保留在内存中。普通 filter、全文 search 和 sort 可引用 Mask 字段；group、字段 metric 与算术 expression 不可引用，`COUNT` 不变。

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
3. 删除全部旧 Mask 实现、Bean、Registry 与 Filter；把每条旧规则迁移为 `@Mask`、`@KeepMask` 或自定义 `@Masking(strategy)` 字段注解。
4. 检查 Schema 的 `masked` 元数据；分别验证 Snapshot/EventStream 的 typed、dynamic、state-only/aggregate-state load，以及 direct Backend 原始值边界。
5. 验证普通 filter/search/sort 和 count 保持可用，并确认 group、字段 metric、expression 引用 Mask 字段时失败关闭；再核对实际 MongoDB/Elasticsearch 路由、HTTP/OpenAPI 与存储原值。
