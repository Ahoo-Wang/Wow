# QueryHandler 提升为 QueryGateway 设计

## 背景

当前 `QueryHandler<R>` 接收 `NamedAggregate` 与查询请求，负责为每次订阅创建独立 `QueryContext`、执行查询过滤链、处理错误，并由 Tail Filter 调用 `QueryServiceFactory` 创建的存储后端 `QueryService`。WebFlux 直接调用 Snapshot/EventStream Handler；Spring 注册的聚合级 `QueryService` 则通过 `QueryServiceProxy` 委托同一 Handler。

因此，`QueryHandler` 实际承担的是统一查询治理与执行入口职责，而不是 HTTP Handler 或普通 Filter Handler。现有命名与职责不一致，并且 `Handler<QueryContext<*, *>>` 继承把过滤链执行细节暴露到了公共查询契约。

本次变更把现有职责原位提升为 `QueryGateway`。它不是新的查询架构，也不替换 `QueryService`、Factory、FilterChain 或 Spring 聚合级查询门面。

## 已验证的现状

当前主线存在三条路径：

```text
WebFlux ─────────────────────────────→ QueryHandler
聚合级 QueryService → QueryServiceProxy → QueryHandler
                                               ↓
                              QueryContext + FilterChain
                                               ↓
                              Tail Filter → Factory → 后端 QueryService

受信基础设施 ─────────────────────────→ Factory → 后端 QueryService
```

- WebFlux 是直接调用 `QueryHandler` 的外部入口。
- `QueryServiceProxy` 保留聚合绑定、Snapshot 状态泛型、后端名称与 EventStream Schema Provider 能力，同时把查询操作委托给 Handler。
- Registrar 为本地聚合注册类型安全的 `SnapshotQueryService<S>` / `EventStreamQueryService`；存在 Handler 时注册策略代理，否则保留原始服务行为。
- Tail Filter 从 Factory 获取原始后端服务。它不会调用 Spring 注册的 Proxy，因此不存在运行时递归。
- Factory 是明确受信任的低层 SPI，会绕过查询治理链。
- `Mono.defer` / `Flux.defer` 保证重复、重试和并发订阅使用不同 `QueryContext`。

基线契约已通过：

```text
QueryHandlerSubscriptionTest
QueryServiceProxyTest
QueryAutoConfigurationTest
```

## 目标

- 将统一查询治理与执行入口准确命名为 `QueryGateway`。
- 让 WebFlux 与进程内聚合级 `QueryService` Proxy 继续汇入同一 Gateway。
- 从 Gateway 公共契约移除 `Handler<QueryContext<*, *>>`。
- 保持查询过滤、授权、Guard、改写、脱敏、错误处理、后端路由和响应式订阅语义不变。
- 保留 `QueryServiceProxy`、Registrar、Query DSL 和聚合级类型安全查询编程模型。
- 保持 `QueryService` 与 Factory 存储 SPI 不变。
- 允许 Gateway 接口进行破坏性清理，不增加旧 Handler 兼容层。

## 非目标

- 不删除或替换 `QueryServiceProxy`、`SnapshotQueryServiceRegistrar`、`EventStreamQueryServiceRegistrar`。
- 不移除 Spring 聚合级 `QueryService` Bean。
- 不新增 GatewayFactory、BackendProvider、Policy、Router、Plan 或第二套查询管线。
- 不重构 `QueryService`、Factory、Query DSL、MongoDB 或 Elasticsearch 查询实现。
- 不改变 Gradle 模块结构、依赖、配置项、OpenAPI、JSON Schema、HTTP 路由或线协议。
- 不改变显式同名自定义 `QueryService` Bean 和无 Gateway Registrar 场景的现有回退行为。

## 职责边界

目标调用链如下：

```text
WebFlux ─────────────────────────────→ QueryGateway
聚合级 QueryService → QueryServiceProxy → QueryGateway
                                               ↓
                              QueryContext + FilterChain
                                               ↓
                              Tail Filter → Factory → 后端 QueryService
```

三个角色保持清晰：

- `QueryGateway`：唯一的策略治理与查询执行边界。
- 聚合级 `QueryService` Proxy：进程内类型安全调用门面，不自行执行查询。
- 后端 `QueryService`：Factory 创建的存储执行 SPI。

Factory 直接访问仍属于受信基础设施 SPI，不视为应用查询入口。

## 公共 API

### QueryGateway

`QueryGateway<R>` 位于 `me.ahoo.wow.query`，保留现有查询方法与 `NamedAggregate` 参数：

```kotlin
interface QueryGateway<R : Any> {
    fun single(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<R>
    fun dynamicSingle(namedAggregate: NamedAggregate, singleQuery: ISingleQuery): Mono<DynamicDocument>
    fun list(namedAggregate: NamedAggregate, listQuery: IListQuery): Flux<R>
    fun dynamicList(namedAggregate: NamedAggregate, listQuery: IListQuery): Flux<DynamicDocument>
    fun paged(namedAggregate: NamedAggregate, pagedQuery: IPagedQuery): Mono<PagedList<R>>
    fun dynamicPaged(namedAggregate: NamedAggregate, pagedQuery: IPagedQuery): Mono<PagedList<DynamicDocument>>
    fun aggregate(namedAggregate: NamedAggregate, query: AggregationQuery): Flux<DynamicDocument>
    fun count(namedAggregate: NamedAggregate, filter: FilterExpression): Mono<Long>
}
```

破坏性清理包括：

- 不再继承 `Handler<QueryContext<*, *>>`。
- 删除废弃的 `count(NamedAggregate, Condition)`。
- `aggregate` 为必须实现的契约，不再提供 `UnsupportedOperationException` 默认实现。
- 不保留 `QueryHandler` 类型、typealias、废弃接口或二进制桥接。

`QueryService` 继续保留自身的兼容 API，包括废弃的 `count(Condition)` 和默认 `aggregate`；Gateway 的清理不扩散到仍被广泛实现和调用的存储 SPI。

### 专用 Gateway

类型与包映射如下：

| 当前类型 | 目标类型 |
| --- | --- |
| `me.ahoo.wow.query.filter.QueryHandler` | `me.ahoo.wow.query.QueryGateway` |
| `me.ahoo.wow.query.filter.AbstractQueryHandler` | `me.ahoo.wow.query.AbstractQueryGateway` |
| `me.ahoo.wow.query.snapshot.filter.SnapshotQueryHandler` | `me.ahoo.wow.query.snapshot.SnapshotQueryGateway` |
| `me.ahoo.wow.query.snapshot.filter.DefaultSnapshotQueryHandler` | `me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway` |
| `me.ahoo.wow.query.event.filter.EventStreamQueryHandler` | `me.ahoo.wow.query.event.EventStreamQueryGateway` |
| `me.ahoo.wow.query.event.filter.DefaultEventStreamQueryHandler` | `me.ahoo.wow.query.event.DefaultEventStreamQueryGateway` |

`SnapshotQueryGateway` 继续实现 `QueryGateway<MaterializedSnapshot<Any>>`，`EventStreamQueryGateway` 继续实现 `QueryGateway<DomainEventStream>`。Snapshot 泛型绑定继续由现有 Proxy 负责，不新增 Gateway typed 重载。

## Gateway 实现

`AbstractQueryGateway` 保留当前实现结构，但把 `handle(QueryContext)` 改为内部执行函数：

1. `Mono.defer` / `Flux.defer` 创建新的 `DefaultQueryContext`。
2. 执行 FilterChain。
3. FilterChain 完成后延迟读取 Context 中的结果 Publisher。
4. FilterChain 错误继续写入 `ErrorAccessor` 并交给配置的 `ErrorHandler`。

所有操作都继续复用同一组内部 mono/flux 执行函数。不得提前创建 Context、共享可变 Context、阻塞订阅或增加额外缓存。

## FilterChain

- `QueryFilter` 的通用 `@FilterType` 从 `QueryHandler::class` 改为 `QueryGateway::class`。
- Snapshot/EventStream Filter、ABAC、Masking 与 WebFlux Guard 改为对应专用 Gateway 标记。
- `FilterChainBuilder.filterCondition` 改用 `SnapshotQueryGateway::class` / `EventStreamQueryGateway::class`。
- `QueryContext`、`QueryType`、QueryFilter 与 Tail Filter 继续位于 `.filter` 包，仍是自定义查询过滤器所需 SPI。
- Filter 顺序与 Tail Filter 行为不变。

## Spring Proxy 与 Registrar

`QueryServiceProxy` 和两个专用 Proxy 保留，只把字段、构造参数与委托类型从 Handler 改为 Gateway。

Registrar 保持现有行为：

1. Factory 创建聚合绑定的原始 `QueryService`。
2. 对应 Gateway 可用时，用 `QueryServiceProxy` 包装原始服务。
3. Gateway 不可用时返回原始服务。
4. 存在显式同名 Bean 时保持该 Bean 原样，不增加代理。

Snapshot Proxy 继续保留后端 `name`，EventStream Proxy 继续转发 `QueryModelSchemaProvider`。现有集中泛型转换继续保留在 Proxy 内，不移动到 Gateway 公共 API。

Spring Boot Bean 与参数命名统一改为：

- `snapshotQueryGateway`
- `eventStreamQueryGateway`

原 `snapshotQueryHandler` / `eventStreamQueryHandler` Bean 名不保留兼容别名。

## WebFlux

WebFlux 查询执行依赖从 `QueryHandler` 改为 `QueryGateway`。请求体提取、path/header rewrite、响应转换、异常映射和 Route 结构不变。

`SingleQueryHandlerFunction`、`ListQueryHandlerFunction` 等名称保持不变，因为它们是真实的 WebFlux `HandlerFunction`，不属于本次命名错误。

## 错误与响应式语义

- FilterChain、结果 Publisher 与 ErrorHandler 的错误传播保持不变。
- 每次订阅、retry 和 repeat 必须生成独立 Context。
- 并发订阅不得共享 query rewrite、masking 或 attributes 状态。
- 不捕获后端错误并返回空结果。
- 不新增静默降级、NoOp 或查询截断。
- 不改变 Factory 直接访问会绕过策略链的现有受信边界。

## 兼容性

### 明确破坏

- QueryHandler、SnapshotQueryHandler、EventStreamQueryHandler 及默认实现的源码和二进制名称。
- Gateway Spring Bean 名称。
- 自定义 Query Filter 的 `@FilterType` 目标类型。
- 自定义 Handler 实现需要迁移到 Gateway，并实现 `aggregate`，不再实现 `handle(QueryContext)`。

### 明确保留

- `QueryService`、Snapshot/EventStream QueryService、Factory、Routing Factory 的源码与 JVM 契约。
- Query DSL 与聚合级 Spring 注入方式。
- Proxy/Registrar 行为与 Bean 命名规则。
- MongoDB、Elasticsearch 与第三方存储 SPI。
- HTTP、OpenAPI、JSON Schema 和查询 wire shape。

## 文档

同步更新：

- 中英文查询指南与数据访问指南。
- Factory KDoc 中对策略入口和受信原始入口的说明。
- 项目本地 Wow skill 中引用 QueryHandler 的查询管线描述。

文档必须明确：聚合级 `QueryService` 是委托 Gateway 的类型安全门面；Factory 返回的是绕过策略链的原始后端服务。

## 测试策略

### wow-query

- 将 QueryHandler 测试迁移为 QueryGateway 测试。
- 保留 single/list/paged/dynamic/count/aggregation 转发覆盖。
- 保留 repeat、retry、并发订阅 Context 隔离测试。
- 删除 Gateway 默认 unsupported aggregation 与 `count(Condition)` 测试。
- 验证 QueryFilter 类型选择使用 Gateway 标记。

### wow-spring

- Proxy 的全部查询操作继续委托对应 Gateway。
- Snapshot Proxy 继续保留聚合身份与后端名称。
- EventStream Proxy 继续保留 Schema Provider 能力。
- Registrar 无 Gateway 时继续返回原始服务。

### wow-spring-boot-starter

- 上下文存在单例 Snapshot/EventStream Gateway。
- 聚合级 QueryService Bean 继续存在并执行策略、改写和脱敏。
- Factory 创建的原始服务继续绕过策略代理。
- 显式注册的同名 Bean 继续原样保留。

### wow-webflux

- 现有查询路由测试迁移依赖类型，不改变请求与响应断言。
- HTTP Guard 继续通过 Gateway FilterChain 生效。

## 验证

先运行聚焦模块检查：

```bash
./gradlew \
  :wow-query:clean :wow-query:check \
  :wow-spring:check \
  :wow-spring-boot-starter:check \
  :wow-webflux:check \
  --stacktrace
```

再运行完整构建与文档：

```bash
./gradlew build
cd documentation && pnpm docs:build
```

静态与 JVM 检查：

- 扫描生产代码、测试、当前用户文档与项目 skill，确认不存在旧 `QueryHandler` 类型残留；历史 specs/plans 保留原始上下文，不做追溯改写。
- 使用 `javap` 确认 `QueryGateway` 不继承 `Handler`、不存在 `count(Condition)`，且 `aggregate` 为抽象方法。
- 使用 `javap` 对比确认 `QueryService` JVM 契约未变化。
- `git diff --check` 必须通过。

完成实现后进行一轮聚焦对抗 Review，检查行为漂移、Spring 装配遗漏、FilterType 漏改、错误边界和非必要复杂度。只有 Review 导致公共契约或执行设计变化时才追加下一轮。

## 完成条件

- 所有受治理的 WebFlux 与聚合级 QueryService Proxy 查询汇入 `QueryGateway`。
- Gateway 名称、包、Bean 与 FilterType 标记一致；除本设计的迁移映射和历史 specs/plans 外，不再残留旧 Handler 类型。
- QueryService Proxy、Registrar、聚合级 Bean、类型绑定与 Schema 能力保持。
- 查询过滤顺序、错误传播、后端执行和订阅隔离行为通过现有契约测试。
- QueryService JVM 契约、HTTP/OpenAPI/Schema 合同保持不变。
- 聚焦模块检查、完整构建、文档构建、JVM 检查与 diff 检查全部通过。
