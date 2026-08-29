# 查询模块 Gateway / Backend 分层重构设计

## 背景

当前查询模块包含两组重叠职责：

- `QueryGateway<R>` 接收 `NamedAggregate`，创建 `QueryContext` 并执行查询 `FilterChain`；
- 聚合级 `QueryService<R>` 同时作为业务侧类型化 API 和存储后端 SPI；
- Spring 通过 `QueryServiceProxy` 把聚合级 `QueryService` 委托给全局 Gateway；
- Tail Filter 再通过 `QueryServiceFactory` 定位原始存储实现；
- 动态结果使用 Wow 自有的 `DynamicDocument`，而序列化、Schema 与其他核心路径已经使用 Jackson 3 `ObjectNode`。

现有执行链如下：

```text
WebFlux ─────────────────────────────→ QueryGateway
聚合级 QueryService → QueryServiceProxy → QueryGateway
                                               ↓
                                  QueryContext + FilterChain
                                               ↓
                         Tail Filter → QueryServiceFactory
                                               ↓
                                    后端 QueryService
```

这使 `QueryService` 同时承担聚合级调用门面与存储实现两个角色，Gateway 也没有绑定聚合，导致 Proxy、Tail Filter 和 typed/dynamic 分支成为额外中转层。

本设计把职责收敛为聚合级 `QueryGateway` 与原始 `QueryBackend` 两层，并统一以 `ObjectNode` 作为过滤链中的文档表示。

本设计取代当前实现架构；历史规格文档保留原始决策记录，不做追溯改写。

## 目标

- `QueryGateway` 成为聚合级、类型安全、经过查询治理链的唯一业务入口。
- `QueryBackend` 成为聚合级、只返回 `ObjectNode` 的原始存储 SPI。
- 原 `QueryService` 职责由 Gateway 与 Backend 明确拆分。
- 请求改写与结果处理复用同一条 around `FilterChain`。
- Backend 作为过滤链固定终点，不再由 Tail Filter 在每次查询时查找。
- typed 与 dynamic 查询共用同一次 `ObjectNode` 查询与结果过滤，再由 Gateway 决定是否转换。
- storage routing、Schema、HTTP/OpenAPI 与响应式订阅语义保持正确。
- 删除不再需要的 QueryService、DynamicDocument、Proxy、Tail Filter 和对象级 masking 体系。

## 非目标

- 不保留旧 QueryService API、typealias、弃用适配器或二进制桥接。
- 不新增全局 QueryGateway、AggregatedQueryGateway 或 QueryGatewayFactory。
- 不拆分 RequestFilterChain 与 ResultFilterChain。
- 不改变查询请求 DTO、FilterExpression、AggregationQuery 或 Query DSL 构造语义。
- 不改变 HTTP 路径、请求/响应 JSON、OpenAPI 查询结构或存储数据结构。
- 不改变 storage routing 配置键与 default/binding/aggregate route 选择规则。
- 不增加依赖、Gradle 模块、CI/CD 或发布逻辑。
- 不为未来后端、未来结果类型或未出现的策略预留新抽象。

## 总体架构

目标执行链如下：

```text
SnapshotQueryGateway<S> / EventStreamQueryGateway
                         │
                  around FilterChain
                         │
               SnapshotQueryBackend /
               EventStreamQueryBackend
```

三个边界分别负责：

- Gateway：聚合绑定、查询治理、错误边界和 typed 转换；
- FilterChain：请求正向处理与结果逆向处理；
- Backend：Schema 解析、存储查询与 `ObjectNode` 结果生成。

storage routing 发生在 Gateway 装配阶段，而不是单次查询阶段：

```text
NamedAggregate
  → Routing*QueryBackendFactory
  → default / aggregate storage binding
  → cached QueryBackend
  → aggregate-bound QueryGateway
```

## 公共 API

### QueryBackend

`QueryBackend` 位于 `me.ahoo.wow.query`，绑定一个 `NamedAggregate`，只公开后端原始结果：

```kotlin
interface QueryBackend : NamedAggregateDecorator {
    fun single(query: ISingleQuery): Mono<ObjectNode>
    fun list(query: IListQuery): Flux<ObjectNode>
    fun paged(query: IPagedQuery): Mono<PagedList<ObjectNode>>
    fun count(filter: FilterExpression): Mono<Long>
    fun aggregate(query: AggregationQuery): Flux<ObjectNode>
}
```

Backend 不提供 `dynamic*` 方法，因为它没有第二种结果表示；也不提供 typed 方法、FilterChain 或 mask。

模型专用 Backend 保留必要的模型能力：

```kotlin
interface SnapshotQueryBackend : QueryBackend, Named
interface EventStreamQueryBackend : QueryBackend
```

Snapshot 的 `Named` 能力继续表达实际 snapshot storage/backend 名称；EventStream 不为对称性增加无用途的名称契约。

MongoDB、Elasticsearch 与 NoOp/Unavailable 实现迁移为对应 Backend。Backend 可按实际能力实现 `QueryModelSchemaProvider`。

### QueryGateway

`QueryGateway<R>` 同样绑定一个 `NamedAggregate`，是业务侧唯一查询入口：

```kotlin
interface QueryGateway<R : Any> : NamedAggregateDecorator {
    fun single(query: ISingleQuery): Mono<R>
    fun dynamicSingle(query: ISingleQuery): Mono<ObjectNode>
    fun list(query: IListQuery): Flux<R>
    fun dynamicList(query: IListQuery): Flux<ObjectNode>
    fun paged(query: IPagedQuery): Mono<PagedList<R>>
    fun dynamicPaged(query: IPagedQuery): Mono<PagedList<ObjectNode>>
    fun count(filter: FilterExpression): Mono<Long>
    fun aggregate(query: AggregationQuery): Flux<ObjectNode>
}
```

模型专用入口为：

```kotlin
interface SnapshotQueryGateway<S : Any> :
    QueryGateway<MaterializedSnapshot<S>>

interface EventStreamQueryGateway :
    QueryGateway<DomainEventStream>
```

`SnapshotQueryGateway<S>` 直接替代原 `SnapshotQueryService<S>` 注入位置；`EventStreamQueryGateway` 直接替代原 `EventStreamQueryService`。

Gateway 不实现或代理 `QueryModelSchemaProvider`。Schema 是实际 Backend 查询模型的能力，不是策略入口能力。

### typed 转换

typed `single/list/paged` 复用对应 dynamic 内部执行路径，在全部结果 Filter 完成后把 `ObjectNode` 转换为 `R`：

```text
Backend ObjectNode
  → result filters / mask
  → Jackson typed conversion
  → R
```

Snapshot Gateway 使用聚合 metadata 构造 `MaterializedSnapshot<S>` 的 Jackson `JavaType`；EventStream Gateway 使用 `DomainEventStream` 目标类型。实现复用现有 Jackson 3 转换能力，不新增 ResultConverter 接口。

`count` 与 `aggregate` 没有 typed/dynamic 双重入口：count 固定为 `Long`，aggregate 固定为动态 `ObjectNode` 行。

## QueryContext 与 QueryType

`QueryType` 收敛为操作形态：

```kotlin
enum class QueryType {
    SINGLE,
    LIST,
    PAGED,
    COUNT,
    AGGREGATION,
}
```

删除 `DYNAMIC_SINGLE`、`DYNAMIC_LIST` 和 `DYNAMIC_PAGED`。typed 与 dynamic 的区别只发生在 Gateway 返回前是否转换，不再影响 Filter 选择、Backend 调用或 mask。

`QueryContext` 继续保存：

- 当前 `QueryType`；
- 聚合身份；
- 可被重写的查询；
- 请求范围 attributes；
- Backend 返回的原始 Publisher。

不同 QueryType 的原始结果分别为：

| QueryType | Context 结果 |
| --- | --- |
| `SINGLE` | `Mono<ObjectNode>` |
| `LIST` | `Flux<ObjectNode>` |
| `PAGED` | `Mono<PagedList<ObjectNode>>` |
| `COUNT` | `Mono<Long>` |
| `AGGREGATION` | `Flux<ObjectNode>` |

每次订阅都通过 `Mono.defer` / `Flux.defer` 创建新 Context。`retry`、`repeat` 与并发订阅不得共享查询改写、attributes、结果 Publisher 或 mask 状态。

## FilterChain

### 单条 around 链

继续使用现有 `Filter<T>` / `FilterChain<T>` around 语义：

```text
Filter A request
  → Filter B request
    → Backend terminal
  ← Filter B result
← Filter A result
```

请求 Filter 在调用 `next.filter(context)` 前执行，可重写查询或增加作用域；结果 Filter 在 `next.filter(context)` 完成后重写 Context 中的结果 Publisher。

不新增第二条结果链，也不增加 request/result 两套排序规则。

### Backend terminal

Backend 是 Gateway 绑定的固定终点，由 Gateway 组装到普通 Filters 之后：

- terminal 根据 QueryType 调用已绑定 Backend；
- terminal 把返回 Publisher 写入 Context；
- terminal 不是 Spring Filter Bean；
- terminal 不参与 `@Order` 或 `@FilterType` 选择；
- terminal 不再通过 Context 或 Factory 查找 Backend。

因此删除 `TailSnapshotQueryFilter` 与 `TailEventStreamQueryFilter`。不以新的 BackendFilter 名称复制 Tail Filter。

### Filter 类型选择

Snapshot 与 EventStream 继续构建各自的 FilterChain，并使用模型专用 Gateway 作为 `@FilterType` 条件：

- `SnapshotQueryGateway::class`；
- `EventStreamQueryGateway::class`。

通用 Query Filter 继续使用 `QueryGateway::class`。ABAC、HTTP Guard、request rewrite 及其他现有顺序保持不变。

## Masking

删除以下对象与接口：

- `DynamicDocumentMasker` 及其专用子接口；
- `DataMasking<SOURCE>` 与 `tryMask` 查询结果扩展；
- `DynamicDocument` / `SimpleDynamicDocument`；
- typed 与 dynamic 两套 mask 分支。

masker 体系统一使用 Jackson 3 `ObjectNode`，按现有模型边界保留 Snapshot 与 EventStream 注册表。命名同步改为 ObjectNode 语义，不继续使用 DynamicDocument 名称。

mask 范围为：

| QueryType | 是否 mask | 原因 |
| --- | --- | --- |
| `SINGLE` | 是 | 原始文档 |
| `LIST` | 是 | 原始文档流 |
| `PAGED` | 是 | 原始文档分页 |
| `COUNT` | 否 | 无文档结果 |
| `AGGREGATION` | 否 | alias 结果行不具有原始文档字段结构 |

typed 查询先执行 ObjectNode mask，再转换为类型化结果，确保 typed 与 dynamic 应用相同策略且只 mask 一次。

## Backend Factory 与 Storage Routing

原 QueryService Factory 体系按职责重命名：

| 当前类型 | 目标类型 |
| --- | --- |
| `SnapshotQueryServiceFactory` | `SnapshotQueryBackendFactory` |
| `EventStreamQueryServiceFactory` | `EventStreamQueryBackendFactory` |
| `AbstractSnapshotQueryServiceFactory` | `AbstractSnapshotQueryBackendFactory` |
| `AbstractEventStreamQueryServiceFactory` | `AbstractEventStreamQueryBackendFactory` |
| `RoutingSnapshotQueryServiceFactory` | `RoutingSnapshotQueryBackendFactory` |
| `RoutingEventStreamQueryServiceFactory` | `RoutingEventStreamQueryBackendFactory` |
| `SnapshotQueryServiceFactoryBinding` | `SnapshotQueryBackendFactoryBinding` |
| `EventStreamQueryServiceFactoryBinding` | `EventStreamQueryBackendFactoryBinding` |

模型专用 Factory 继续负责：

- 按 materialized `NamedAggregate` 缓存 Backend；
- 解析默认 Backend；
- 解析聚合专属 storage route；
- 解析显式 binding；
- 在配置缺失或 binding 无效时保持现有失败语义。

配置属性名称与 default/binding/aggregate route 选择逻辑不变。路由只改变 Java/Kotlin 类型名，不改变用户配置合同。

Factory 仍是绕过 Gateway 治理链的受信低层 SPI。直接调用 Backend Factory 不执行 request filter、ABAC、HTTP guard 或结果 mask。

## Spring 装配

### Registrar

原 QueryService Registrar 迁移为 QueryGateway Registrar。Registrar 遍历 `MetadataSearcher.namedAggregateType`：

1. 获取聚合 `NamedAggregate` 与状态类型；
2. 从对应主 Backend Factory 创建经过 storage routing 的缓存 Backend；
3. 获取模型专用 FilterChain 与 ErrorHandler；
4. 构造绑定聚合、Backend 与目标类型的 Gateway；
5. 注册聚合级 Gateway Bean。

Bean 名为：

```text
{contextAlias.}{aggregateName}.SnapshotQueryGateway
{contextAlias.}{aggregateName}.EventStreamQueryGateway
```

Snapshot Bean 通过 `ResolvableType` 注册 `SnapshotQueryGateway<STATE>`，保持按状态泛型注入能力。EventStream 不具有状态泛型，多候选时继续按 Bean 名限定。

存在同名自定义 Gateway Bean 时保留自定义 Bean，不再注册默认 Gateway。自定义 Gateway 是完整策略边界，必须自行承担 FilterChain、安全策略与 Backend 选择；框架不再为其追加代理。

删除 `QueryServiceProxy`、`SnapshotQueryServiceProxy` 和 `EventStreamQueryServiceProxy`。

### WebFlux 路由

HTTP route contract 已包含 `AggregateMetadata`。WebFlux 在 Handler 构建时按确定性 Bean 名定位对应聚合 Gateway，并把该实例绑定到 Handler：

```text
HttpRouteContract + AggregateMetadata
  → resolve aggregate-bound Gateway Bean once
  → HandlerFunction
  → request-time Gateway call without lookup
```

不在每次 HTTP 请求时查找 Gateway，也不新增 QueryGatewayFactory。Spring/WebFlux 适配层可使用最小的内部 Bean lookup 函数，不引入新的公共 Provider/Registry 抽象。

WebFlux Handler 改为调用不再接收 `NamedAggregate` 参数的聚合级 Gateway。请求体提取、tenant/owner/space rewrite、raw request attribute、JSON/SSE 响应和异常映射保持不变。

### Schema 路由

Schema 与 refresh Handler 继续通过主 Backend Factory 取得聚合 Backend，然后使用 `requiredQueryModelSchemaProvider()` 校验能力：

```text
Schema HTTP route
  → Routing*QueryBackendFactory
  → cached Backend
  → QueryModelSchemaProvider
```

由于 Backend Factory 与 Gateway Registrar 使用同一条 storage routing，Schema 与实际查询后端保持一致。

## 错误处理

Gateway 的错误边界覆盖完整 Publisher 生命周期：

- 请求 Filter；
- Backend Publisher；
- 结果 Filter；
- ObjectNode mask；
- typed 转换。

发生错误时：

1. 如果 Context 支持 `ErrorAccessor`，记录原始错误；
2. 调用配置的 `ErrorHandler`；
3. 无论 ErrorHandler 是否正常完成，都向调用方传播原始错误；
4. ErrorHandler 自身的不同错误作为 suppressed error 附加到原始错误。

不得把安全过滤、mask、Schema、Backend 或 typed 转换失败恢复为空结果。WebFlux 继续由 `RequestExceptionHandler` 把传播错误映射为 HTTP 响应。

## 兼容性

### 明确破坏的 JVM 合同

- `QueryService`、`SnapshotQueryService`、`EventStreamQueryService`；
- 全部 QueryService Factory、Routing Factory 与 Binding 类型名；
- QueryService Proxy 与 Registrar 类型名；
- Snapshot/EventStream QueryService Spring Bean 名；
- `DynamicDocument`、`SimpleDynamicDocument` 与转换扩展；
- `DataMasking` 与 DynamicDocument masker API；
- 自定义 Query Filter 对 dynamic QueryType 的判断；
- 查询 DSL 执行扩展的 QueryService 参数类型；
- 自定义存储实现的 QueryService SPI。

不提供弃用周期或源码/二进制兼容桥接。该变更应在允许破坏性 JVM API 的版本中发布，并在发布说明中给出直接迁移映射。

### 保持的 wire 与存储合同

- HTTP 方法与路径；
- 查询请求 JSON；
- 查询响应 JSON 与 JSON/SSE 协商；
- OpenAPI 查询 DTO 与 route contract；
- MongoDB/Elasticsearch 存储结构；
- Query Model Schema 内容与 refresh 语义；
- Snapshot/EventStream 查询、分页、count 与 aggregation 语义；
- 无 group 空聚合与 grouped 空输入行为；
- API Client 的 HTTP 合同；
- storage routing 配置键与选择规则。

## 模块改动范围

| 模块 | 主要改动 |
| --- | --- |
| `wow-api` | 删除 DynamicDocument 体系 |
| `wow-query` | 新 Backend/Gateway 契约、Context、Filter、mask、Factory 与 DSL |
| `wow-mongo` | QueryService 实现迁移为 ObjectNode Backend |
| `wow-elasticsearch` | QueryService 实现迁移为 ObjectNode Backend |
| `wow-spring` | 删除 Proxy，Registrar 改为注册聚合级 Gateway |
| `wow-spring-boot-starter` | Gateway/FilterChain 装配与 Backend storage routing 重命名 |
| `wow-webflux` | Handler 在构建时绑定聚合级 Gateway；Schema 使用 Backend Factory |
| `wow-cocache` | QueryService 调用迁移为 SnapshotQueryGateway |
| `wow-apiclient` | 仅验证 wire 合同，不引入 Backend/Gateway 类型 |
| `test/wow-tck` | 拆分 Backend 与 Gateway 合同测试 |
| `example` / `compensation` | JVM 查询调用迁移 |
| `documentation` / `skills` | 中英文文档、图、迁移说明与本地 skill 同步 |

不移动 Gradle 模块职责，不增加模块依赖。

## 测试策略

### wow-query

- Gateway typed/dynamic 调用共用同一 Backend、QueryType 与 FilterChain；
- `SINGLE/LIST/PAGED/COUNT/AGGREGATION` 全操作转发；
- 请求 Filter 正序、结果 Filter 逆序；
- mask 在 typed 转换前执行且每次订阅只执行一次；
- `COUNT/AGGREGATION` 不执行 mask；
- repeat、retry 与并发订阅 Context 隔离；
- Backend、结果 Filter、mask、typed 转换错误经过 ErrorHandler 后传播原始错误；
- Backend terminal 固定且不参与 Filter 排序；
- Backend Factory 缓存与 Snapshot/EventStream routing 选择。

### Backend TCK

把现有 QueryService TCK 迁移为 Backend 合同，MongoDB 与 Elasticsearch 共同覆盖：

- single/list/paged/count；
- projection、sort、pagination 与 limit；
- aggregation 与空输入语义；
- Snapshot/EventStream `ObjectNode` wire shape；
- QueryModelSchema 与 refresh；
- compatible/strict Schema validation。

typed 转换与 mask 属于 Gateway TCK，不在每个存储后端重复验证。

### Spring 与 Starter

- Snapshot Gateway 泛型注入；
- EventStream Gateway 确定性 Bean 名；
- Registrar 绑定正确的 routed Backend、FilterChain 与目标类型；
- 同名自定义 Gateway 保持原样；
- default storage、aggregate storage route 与显式 binding；
- 缺失 Backend/Binding 的失败语义；
- 不再注册 QueryService、Proxy、Tail Filter 或全局 Gateway Bean。

### WebFlux 与 OpenAPI

- 每个 route contract 在 Handler 构建时解析正确聚合 Gateway；
- 不同聚合不会共享错误 Gateway；
- request rewrite、HTTP guard、ABAC 与 raw request attribute 继续生效；
- JSON/SSE 响应内容与现有快照保持一致；
- Schema/refresh 使用 routed Backend；
- HTTP 路径、请求/响应 schema 与 OpenAPI 快照不变。

### 静态验收

除历史 specs/plans 和迁移说明外，生产代码、测试、当前文档与项目 skill 不再引用：

- `QueryService`；
- `DynamicDocument` / `SimpleDynamicDocument`；
- `TailSnapshotQueryFilter` / `TailEventStreamQueryFilter`；
- `DataMasking`；
- `DYNAMIC_SINGLE` / `DYNAMIC_LIST` / `DYNAMIC_PAGED`。

## 验证命令

先运行聚焦模块：

```bash
./gradlew \
  :wow-api:check \
  :wow-query:check \
  :wow-spring:check \
  :wow-spring-boot-starter:check \
  :wow-webflux:check \
  :wow-cocache:check \
  :wow-apiclient:check \
  --stacktrace
```

再运行存储与集成验证：

```bash
./gradlew \
  :wow-mongo:check :wow-mongo:integrationTest \
  :wow-elasticsearch:check :wow-elasticsearch:integrationTest \
  :wow-it:integrationTest \
  --stacktrace
```

最后运行完整构建与文档：

```bash
./gradlew build
cd documentation && pnpm docs:build
git diff --check
```

## 完成条件

- 业务侧只通过聚合级 Snapshot/EventStream QueryGateway 查询；
- Gateway、FilterChain 与 Backend 的职责符合本设计；
- typed 与 dynamic 查询共用 ObjectNode 策略链，typed 转换发生在 mask 后；
- Backend Factory 保持缓存和全部 storage routing 语义；
- WebFlux 路由绑定正确的聚合级 Gateway，Schema 路由绑定正确的 Backend；
- QueryService、DynamicDocument、Proxy、Tail Filter 与对象级 masking 体系从当前实现移除；
- 明确保留的 HTTP/OpenAPI/wire/storage 合同无变化；
- 聚焦检查、存储 integration tests、`:wow-it:integrationTest`、完整构建、文档构建与静态验收全部通过。
