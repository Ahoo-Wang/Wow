# V10 与 9.x 的能力对照

日期：2026-10-22（上一版 2026-10-17，基线 `facbe4e83`）。2026-10-22 按代码审计更正 17 行（客户端事实、平台服务、补偿迁移、处理函数内查询、analytics 控制台、TS 路径、JVM 客户端、Java 体验等），汇总改为 A 77、B 44、C 25、D 6、E 8（2026-10-26 D292 删除全局 ID 路由后 A 76、C 26）；文件行号不再写，只写文件。2026-10-21 按 W0 文档写作对照代码更新 10 行（Elasticsearch、跨上下文解码、`@Retry` 分类、测试 fork、数据集注册与分析、健康、快速失败），并重新计数。状态：持续维护。上位文档：[路线图](roadmap.md)（6.0i，D183；复核 D229）。

本文逐项列出 9.x 面向用户的能力在 V10 中的状态；迁移 Skill 的已知缺口表以本文为准。2026-10-17 按当时代码全面复核（上一版为 2026-10-13，基线 `f71209eda`），两侧都给出代码位置。

分类：**A** V10 已有；**B** 以别的方式提供（注明迁移 Skill 是否覆盖）；**C** 有意放弃（附决定）；**D** 有决定的推迟或计划（含「已决定未实现」）；**E** 没有决定的缺口（见 §17，去向已由 D229 确定）。

## 1. 汇总

| 类 | 数量 |
|---|---|
| A 已有 | 76 |
| B 替代 | 45 |
| C 放弃 | 25 |
| D 推迟或计划 | 6 |
| E 无决定（D229 已定去向） | 8 |
| 合计 | 160 |

## 2. 命令侧

左列是 9.x 的模块与类型，它们在 `v9` 分支；右列是 `main` 上 Wow 10 的位置。

| 能力 | 9.x | 类 | V10 位置 / 决定 |
|---|---|---|---|
| 命令网关（发送、等待、执行） | `wow-core` `command/CommandGateway` | A | `core/runtime/.../admission/CommandGateway.kt`（D92），`Flow<CommandResult>` |
| Reactor 形态的网关 | 9.x 网关即 `Mono` | D | 有需求再设；`core/reactor` 只有处理函数的适配 `awaitResult`；Skill 已写 `.asFlux()` |
| 等待阶段 SENT/PROCESSED/SNAPSHOT/PROJECTED/EVENT_HANDLED/SAGA_HANDLED 与函数过滤 | `command/wait/CommandStage` | B | `Wow-Wait` 的 `accepted`/`committed`/`snapshot`/`handled(sub)`（D61、D62、D99），`runtime/wait/*`；Skill「REST clients」覆盖 |
| 等待链尾 `Command-Wait-Tail-*` | `command/wait/chain` | B | 多跳 `Wow-Wait`（D62、D190、D212）；Skill 覆盖 |
| 跨实例等待通知 `/wow/command/wait` | `webflux/wait/*` | B | 内部端口信号 `edge/http/.../internal/HttpSignalSender.kt`（D89、D226）；不面向用户 |
| 跨上下文等待 | 经总线 | A | 6.6（D34、D65） |
| SSE 推送结果 | `wow-webflux` | A | `edge/http/.../Streams.kt` |
| 请求 ID 幂等 | `RequestIdChecker` | A | `EventLog.findByRequestId`（D46、D55、D162、D191） |
| L1 布隆过滤器 | `BloomFilterIdempotencyChecker` | D | D46 可选，未做 |
| 命令重写 | `CommandBuilderRewriter` | B | `CommandTransformer`（`runtime/admission/CommandTransformer.kt`，D182，#4388）；Skill 覆盖 |
| 挂载命令 | `@AggregateRoot(commands=)` | B | `@MountedCommand`（`api/.../annotation/MountedCommand.kt`，D182）、`@Unaddressed`（D210）；Skill 覆盖 |
| 命令校验 | `CommandValidator`、Jakarta | B | 准入端口 `CommandValidator` + `JakartaCommandValidator`（`edge/http/.../command/`，D106）；Skill 覆盖 |
| `@VoidCommand` | 注解 | C | D186 |
| 自定义路由 `@CommandRoute`/`@AggregateRoute` | 注解 + webflux | C | D104；Skill 覆盖 |
| `Command-*` 请求头 | `CommandHeaders` | B | `Wow-Wait`、`Wow-Request-Id`、`If-Match`、`Wow-Scope`、`Wow-Space`（D95、D96、D98）；Skill 覆盖 |
| 请求事实白名单 | `CommandRequestHeaderPropagator` | A | `wow.http.request-facts`（`edge/http/.../WowHttp.kt`） |
| 默认的 `remote_ip`、`user_agent` | appender | A | `wow.http.client-facts`（默认 true，`edge/http/.../WowHttp.kt`；D267 ⑯，G-10） |
| CoSec 的 app/device ID | `CoSecCommandRequestHeaderAppender` | A | `edge/cosec/.../CoSecRequestFacts.kt` |
| 请求头别名 | `IdentityHeaderAliases` | E | G-13 |
| 操作人 | `CommandOperator` | A | `api/.../caller/Operator.kt`（D163） |
| 全局/按聚合 ID 生成、CosId、领域内取 ID | `id/*` | A | `core/spi/.../id/IdGenerators.kt`、`extensions/cosid`、`DecideScope.ids`（D171、D191） |
| `GET /wow/id/global` | `GlobalIdHandlerFunction` | C | 删除（D292）：没有 Wow 10 的使用者，任何已认证调用方都能取全局 ID |
| 批量准入与车道 | `batch/*` | B | 追加队列与组提交（D11，`wow.log.append-queue.*`） |
| 命令结果 | `CommandResultAccessor` | B | `DecideScope.putResult`（D49、D51） |
| 跨聚合唯一键 | `PrepareKey`/`@PreparableKey` | B | `DecideScope.reserve`/`release`（D38、D225）；数据 8b.3（D227 ③）；Skill 覆盖 |
| `PrepareKey` 查找、自定义值、TTL | `PrepareKey.get/getValue` | E | G-7 |
| 期望版本 | `Command-Aggregate-Version` | A | `If-Match`，412 |
| 命令总线（内存、本地优先、Kafka、Redis） | `CommandBus` 系列 | B | 集群通道 + Partitioned/Local（D6、D71）；按聚合 Kafka Router（D143，`WowProperties.AggregateRouting`，6.5 已合并）；Redis 见 C |
| 统一命令入口 `/wow/command/send` | `CommandFacadeHandlerFunction` | B | 每命令一条路由；Skill「REST clients」覆盖 |
| 命令过滤器（9.3 已退役）与 `CommandInstrumentation` | `filter/*` | B | `core/spi/.../observation/Observer.kt` 与 `:opentelemetry`（D188） |
| 错误码到 HTTP 状态 | `ErrorHttpStatusMapping` | A | `wow.http.errors.[code].status`、`@ErrorCode(status)` |
| 领域异常与 `ErrorInfo` 转换 | `ErrorInfoConverterRegistrar` | B | `Rejection` + `@ErrorCode`（D121、D211）；Skill 覆盖 |

## 3. 领域模型与注解

| 能力 | 9.x | 类 | V10 位置 / 决定 |
|---|---|---|---|
| 核心注解 | `wow-api` | A | `api/api/.../annotation/*`（D79） |
| 事件修订与升级 | `@Event(revision)`、`EventUpgrader` | B | `@Revision`、`@Upgrade`、`@Alias`（D22、D82）；Skill 覆盖 |
| 丢弃过时事件 | `DroppedEvent` | B | `@AggregateRoot(retiredEvents)`（D219，P.6）；Skill 覆盖 |
| 固定租户 | `@StaticTenantId` | B | `wow.aggregates.[ctx.agg].tenant`（D116）；Skill 覆盖 |
| 固定聚合 ID | `@StaticAggregateId` | C | D186 |
| 所有者与空间策略 | `@AggregateOwner`、`@Spaced` | B | `Ownership.OWNER/TENANT`（D189）、作用域取自主体；Skill 覆盖 |
| 命令字段上的身份注解 | `@AggregateId` 等 | C | D68 |
| 从状态推导标签 | `StateAggregateTagsExtractor` | B | `@Tags`（D220，`compiler/.../TagsResolver.kt`）；Skill 覆盖 |
| 应用标签与 ABAC 标签 | `ApplyAbacTags` | A | 生命周期 `ApplyTags`（D129） |
| 生命周期命令（删除、恢复、转移所有者/空间） | `DeleteAggregate` 等 | A | `api/.../lifecycle/*`（D91、D113） |
| 领域事件实现 `OwnerTransferred` | 事件接口 | B | Saga 派生 `TransferOwner`；Skill「Ownership」覆盖 |
| `@EntityObject`、`@ValueObject`、`@Order`、`Identifier` | 注解 | C | D67、D186 |
| `@OnError` | 注解 | C | D7 |
| Java 领域模型（注解） | 反射 | A | KSP 处理 Java 源（D8）；体验缺口见 G-8 |
| 领域服务注入 | `ServiceProvider` | A | `Dependencies`、生成的工厂（D107、D119） |
| 错误事件（`IgnoreSourcing` + `ErrorInfo`） | `api/event/IgnoreSourcing` | B | `Rejection`（D121）或空溯源函数；Skill「State classes」覆盖 |
| 聚合模式（命令类 + 状态类分离） | `@AggregateRoot` | A | `compiler/.../RootStateResolver.kt` |
| `@AfterCommand`、`@Blocking`、`@CreateAggregate`、`@AllowCreate` | 注解 | A | D39、D227 ① |
| 快照系统字段 `eventId`、`snapshotTime`、`eventTime` | `MaterializedSnapshot` | B | `SnapshotProperties`（`lastEventTime`、`operator`、`firstOperator`…）；Skill 未写字段对照 |

## 4. 事件与存储

| 能力 | 9.x | 类 | V10 位置 / 决定 |
|---|---|---|---|
| 事件存储 MongoDB | `wow-mongo` | A | `backends/mongo/.../log/MongoEventLog.kt` |
| 事件存储 Redis | `wow-redis` | C | D12（数据迁移 D213） |
| 事件存储 Elasticsearch | `wow-elasticsearch` | C | D13（数据迁移见 G-5） |
| 快照存储 MongoDB | `wow-mongo` | A | `backends/mongo/.../snapshot/MongoSnapshotStore.kt` |
| 快照存储 Redis | `wow-redis` | C | D12 |
| 快照存储 Elasticsearch | `wow-elasticsearch` | A | `backends/elasticsearch`（`ElasticsearchStoreProvider`，`snapshot/`）：6.7 已完成（D167） |
| 快照策略（版本间隔） | `SnapshotStrategy` | B | 每版本写（D4） |
| 按聚合关闭快照 | 无 | A | `@AggregateRoot(snapshot = false)`（D195） |
| 按聚合配置存储 | `storage-routing` | A | `wow.stores`、`runtime/log/LogGroupRouter.kt`、`SnapshotStoreRouter.kt`（D177、6.0g） |
| 快照重建（单个与批量） | `RegenerateSnapshot*`、`snapshot/{afterId}/{limit}` | A | `POST /{ctx}/snapshot/{agg}/wow.snapshot.rebuild`（D172，`WowRoutes.kt`） |
| 历史状态（按版本、按时间、追踪） | `state/{version}`、`state/time/{t}`、`state/tracing` | A | `GET …/state?version=&at=`、`…/state/trace`（D40，`WowRoutes.kt`） |
| 单聚合事件流读取 | `event/{head}/{tail}` | A | `events` 模型（D235，`query/query/.../query/events/`） |
| 停放失败的恢复与跳过 | `event/compensation` | B | 停放与台账（D14、D141、P.1）；Skill 覆盖 |
| 定向重放、批量重发状态事件 | `…/compensate`、`state/{afterId}/{limit}` | E | G-9 |
| 领域/状态事件总线 | `DomainEventBus`、`StateEventBus` | B | 日志即总线（D5），跨上下文经 Kafka Sink（D23） |
| Mongo 建表、索引校准 | `*SchemaInitializer`、`MongoIndexReconciler` | A | `wow.ddl`、`core/spi/.../StoreDdl.kt` |

（R2DBC 与核心分片在 8.8 已删除，`c3c7a9dfa`，不属于 9.x 能力。）

## 5. 处理器与订阅

| 能力 | 9.x | 类 | V10 位置 / 决定 |
|---|---|---|---|
| Saga、投影、事件处理器 | `@StatelessSaga`、`@ProjectionProcessor`、`@EventProcessor` | B | `@Saga`、`@Projection`、`@Reactor`（D149）；Skill 覆盖 |
| `@OnStateEvent`、`@OnMessage` | 注解 | B | `@OnEvent` + `StateEvent<S>`（D67、D81）；Skill 覆盖 |
| 跨上下文事件处理 | Kafka 总线 | A | D134、D138、D140 |
| 跨上下文事件带类型解码 | 共享类 | A | DX-1c：修订号一致时解码为对端 api 的类，否则交付 `PublishedEvent`（D178、D267 ⑤） |
| `@Retry` 次数、退避、超时 | `@Retry` | B | V10 `@Retry` + `wow.subscriptions.[…].retry.*`（次数、退避、超时，P.3）与 `.parked.*`（长周期恢复，P.1、P.10）；Skill 覆盖 |
| `@Retry` 的可恢复分类与 `enabled` | `@Retry`、`RecoverableExceptionProvider` | A | `Retry.kt` 的 `recoverable`/`unrecoverable` 与 `FailureClassifier` SPI（P.16，D243） |
| 订阅起点 | 无 | A | `@OnEvent(start)`、`wow.subscriptions.[…].start`（D154、D194） |
| 处理器过滤器、拦截器、`@Order` | `filter/*` | C | D188、D186 |
| Kafka 解码失败跳过 | `decodeFailureStrategy` | D | 上一版 §13 #16 推迟到 10.x（D184 记录）；V10 停放不可解码消息 |
| 处理函数内读他聚合状态/查询 | `SnapshotQueryService` | A | `SubscriptionContext.queries`（类型化 `QueryApi`，Saga 与投影共用，D254 ②、D259 RQ-1） |

## 6. 查询

| 能力 | 9.x | 类 | V10 位置 / 决定 |
|---|---|---|---|
| 查询协议：single/list/paged/cursor/count/aggregation | `wow-api` `query`、`wow-query` | A | `api/query-api`、`query/query`（D125、D126；`QueryType.kt`） |
| `FilterExpression`、相对时间、元数据过滤、聚合窗口 | `wow-api` `query/*` | A | `api/query-api/.../query/*`（与 9.x 只有 KDoc 差异） |
| `Condition` 旧协议 | `Condition`、`LegacyConditionAdapter` | C | D125；Skill 覆盖 |
| Kotlin 查询 DSL 与 `<State>Properties` | `wow-query` `dsl`、编译器 | A | `api/query-api/.../dsl/*`、`compiler/.../QueryPropertiesSource.kt`（P.2） |
| 查询字段注解（`@Sensitive`、`@QueryAlias`、`@QueryReference`…） | `api/query/annotation` | A | `api/query-api/.../annotation/*` |
| 查询脱敏 | `SchemaMasker` | A | `query/query/.../mask/SchemaMasker.kt` |
| 状态点读脱敏 | `PointReadAdmission` | A | P.14 #4551（G-2） |
| ABAC 查询策略（含 CoSec `abac.` 属性） | `AbacQueryPolicy`、`CoSecAbacQueryPolicy` | A | `query/.../AbacQueryPolicy.kt`、`edge/cosec/.../CoSecCallerResolver.kt`（D129） |
| 查询策略 SPI、作用域 | `QueryPolicy`、`QueryScope` | A | `QueryPolicy`、`ScopeQueryPolicy`、`QueryCaller` |
| 快照查询 MongoDB | `wow-mongo` `query` | A | `backends/mongo/.../query/*`（6.2b） |
| 快照查询 Elasticsearch | `wow-elasticsearch` `query` | A | `backends/elasticsearch` `query/`（D167，6.7） |
| 跨聚合事件流查询与聚合（Mongo/ES） | `event/list`、`event/aggregation` | A | D235，A.M2 第一步 #4570：统一引擎，PostgreSQL、MongoDB、内存后端，按事件分析用 `expand("events")`；ES 由 6.7 提供；审查 R3.3-3、R3.4-1、R3.4-2 的限制已修（#4580、#4598） |
| HTTP 查询路由 | `snapshot/list` 等 | B | `POST /{ctx}/{agg}/{model}/query`（D127）；Skill 只写了「查询改 FilterExpression」，未写路由对照 |
| 列表流式返回 | `StreamingJsonArrayResponse` | A | `Accept: text/event-stream`（`edge/http/.../query/QueryHandler.kt`） |
| 查询 schema 路由与刷新、actuator `wowQuerySchema` | `QuerySchemaHandlerFunction` | B | `GET` 能力描述（D127），schema 来自 Manifest（D37），无需刷新 |
| 数据集目录、网关、路由 | `wow-query` `dataset` | A | `query/.../dataset/*`、`/wow/dataset`（6.1d，`WowRoutes.kt`） |
| 数据集动态注册 | dataset-registry | B | 由 analytics 平台的 catalog 服务提供：Wow 来源异步登记与分析师宽表（D238、D251；7.2b-1、7.2b-2） |
| ClickHouse 数据集后端 | `wow-clickhouse` | A | `backends/clickhouse/.../query/*`（6.4a） |
| ClickHouse 数据集草稿 | `ClickHouseDatasetDrafts` | D | D238 ③(b)：分析师宽表从 `system.columns` 起草（7.2b-1） |
| 预算、计数、游标 | `QueryBudget` 等 | A | `query/.../QueryBudget.kt`、计数缓存（D152） |

## 7. 消息、外部存储与 BI

| 能力 | 9.x | 类 | V10 位置 / 决定 |
|---|---|---|---|
| Kafka 传输 | `wow-kafka` | B | Sink、来源订阅、命令入口、回执（D31、D58、D134、D135） |
| Kafka 主题前缀与主题管理 | starter | A | `wow.kafka.topic-prefix`、`topics.ddl`（D168，`backends/kafka/.../KafkaTopicAdmin.kt`） |
| 生产者/消费者分开的配置与接收调优 | `KafkaProperties` | E | G-12 |
| Redis 总线 | `wow-redis` `bus` | C | D12 |
| BI 同步脚本 `/wow/bi/script` | `wow-bi` | B | ClickHouse Sink + 展平视图（D18、D142、D164，`backends/clickhouse/.../sink/*`） |
| BI 嵌套展开 | `bi/expansion` | D | D142 推迟（`FlatColumns.kt`），无工作包 |
| 9.x 主题的外部消费者过渡 | — | E | G-4 |

## 8. 租户与安全

| 能力 | 9.x | 类 | V10 位置 / 决定 |
|---|---|---|---|
| CoSec 集成 | `wow-cosec` | A | `edge/cosec`（D93） |
| 租户、所有者路由前缀 | `wow-webflux` | C | D16（跨作用域用 `Wow-Scope`，D95） |
| CoSec 主体传播到消息 | `CoSecMessagePropagator` | B | `Caller`、`Operator`（D163、D165） |
| 跨上下文租户 | 无 | A | D180 |

## 9. 契约、OpenAPI 与 HTTP

| 能力 | 9.x | 类 | V10 位置 / 决定 |
|---|---|---|---|
| 元数据 `wow-metadata.json` 与 `/wow/metadata` | `wow-metadata`、`wow-compiler` | B | Manifest、`GET /wow/manifest`（D17、D74） |
| JSON schema 生成 | `wow-schema` | B | `compiler/manifest-tools/.../SchemaIntrospector.kt`（D73）；Joda Money 改为 `@QueryMoney` |
| 自定义 Jackson 模块（如 joda-money） | `JsonSerializer` 可注册模块 | C | D48、D122：`WowJson` 只注册 Kotlin 模块（`core/spi/.../json/WowJson.kt`）；**Skill 未覆盖** |
| OpenAPI 文档 | `wow-openapi` | A | `edge/openapi`、`GET /wow/openapi`、Gradle `GenerateOpenApi`（5a.2） |
| springdoc 集成（Swagger UI、与应用接口合并） | `wow-spring-boot-starter` 的 `WowOpenApiCustomizer` | A | P.19 #4574（D233）：`host/spring-boot/.../SpringdocAutoConfiguration.kt`，`wow.http.openapi.springdoc.*` |
| 错误体 | `ErrorInfo` | B | problem+json（D101）；Skill 覆盖 |
| `wow-rest-contract` 词汇 | 模块 | B | `edge/http`、Manifest |

## 10. 可观测性

| 能力 | 9.x | 类 | V10 位置 / 决定 |
|---|---|---|---|
| OpenTelemetry 追踪 | `wow-opentelemetry` | A | `edge/opentelemetry`（D174，6.9） |
| Micrometer 指标装饰器 | `metrics/*` | A | `edge/opentelemetry/.../NodeMetrics.kt`、`ParkedMetrics.kt` |

## 11. 测试

| 能力 | 9.x | 类 | V10 位置 / 决定 |
|---|---|---|---|
| 聚合测试 | `AggregateSpec`、`aggregateVerifier` | A | `test/test-dsl` `aggregateSpec`（artifact `wow-test`，已发布） |
| `fork`/`ref`、`givenState`、按类型 `expectError` | `wow-test` | A | T.1：`fork` 复制状态，`aggregateSpecTests`/`sagaSpecTests` 测试树（D245） |
| Saga/投影/反应器测试 | `SagaSpec` | A | `sagaSpec`、`projectionSpec`、`reactorSpec`（`ProcessorSpec.kt`） |
| Java 测试 API | `AggregateVerifier`（Java 可用） | E | G-8 |
| `TestValidator` | `test/validation` | C | D200 |
| TCK 与 Testcontainers 夹具 | `wow-tck` | A | `:tck`、`:tck-containers`（D196，已发布） |
| `wow-mock` | 模块 | C | D186 |

## 12. 平台服务

| 能力 | 9.x | 类 | V10 位置 / 决定 |
|---|---|---|---|
| 补偿服务 | `compensation/*` | B | `platform/ops`（D14、D141，7.1） |
| 长周期自动重试 | `CompensationScheduler` | A | `core/runtime/.../subscription/ParkedRetries.kt`（P.1） |
| Resume/Skip/`MarkRecoverable`/`ApplyRetrySpec` | 补偿命令 | A | `platform/ops/ops-api/.../ParkedCommands.kt`、`edge/http/.../ops/ParkedRoutes.kt` |
| `ChangeFunction`、强制准备 | 补偿命令 | C | D185 |
| 企业微信 webhook | `WeiXinWebHook` | A | `WebhookNotifier` `WECOM`（`wow.ops.webhook.*`） |
| 通知里的控制台链接与模板 | `QuickNavigation` | E | G-11 |
| 控制台堆栈 | dashboard `StackTrace` | C | D215 |
| 控制台每日趋势分析 | `activityAnalyses` | D | D215 → 10.x |
| 控制台可编辑看板 | `BoardsPage` | C | D215 |
| 平台服务的 CoSec/OTel/K8s 清单/控制台登录 | 9.x 的 compensation-server、`deploy/compensation` | A | P.15 #4575：CoSec、OTel、`deploy/ops`、`deploy/view-store` 清单与控制台登录（K8s 清单未在真实集群验证，G-3） |
| 补偿数据迁移 | — | A | `migration/migrate-data` 的 `CompensationImporter`（8b.4，D229 G-1） |
| view-store | `view-store/*` | A | `platform/view-store`（7.2a） |
| dataset-registry | `dataset-registry/*` | B | `platform/analytics/catalog`（D238、D251；7.2b） |
| analytics 服务与控制台 | `analytics/*` | B | catalog 服务提供数据集查询，数据负责人控制台 `platform/analytics/catalog/console`（7.2c-1、7.2c-2）；镜像 `wow-analytics-catalog-server` |

## 13. 扩展模块

| 能力 | 9.x | 类 | V10 位置 / 决定 |
|---|---|---|---|
| `wow-models` | 模块 | A | `extensions/models`（D187，P.8） |
| `wow-cocache` | 模块 | B | `extensions/cocache`（D364 修订 D187）：提供方按 `wow.cocache.publish` 写入，读者注入 `StateViewCache<V>`；读者自建的按索引、按联接的缓存无对应（D364 ⑦，迁移 Skill 覆盖） |
| `wow-apiclient` | 模块 | C | D187、D257（D187 的「生成的客户端」只有 TypeScript；D257 已交付：`:client`（`edge/client`，artifact `wow-client`，JDK HttpClient，无 Spring）提供位置透明的类型化 `CommandClient` 与 `QueryApi`，含 Java 门面；9.x `wow-apiclient` 的迁移映射在迁移 Skill） |
| CosId | `wow-core` `id` | A | `extensions/cosid` |
| Spring 集成与 starter 变体 | `wow-spring`、starter features | B | `host/spring-boot` + 每技术一个模块（D44）；Skill 覆盖 |

## 14. 配置与运维

| 能力 | 9.x | 类 | V10 位置 / 决定 |
|---|---|---|---|
| `wow.*` 配置 | starter `*Properties` | B | 新键（D110，`WowProperties.kt`、配置元数据）；Skill 覆盖 |
| 优雅停机 | `WowRuntimeSpringLifecycle` | A | `host/spring-boot/.../WowLifecycles.kt` |
| 健康指示器（就绪、存活） | 无 | A | `wow.health.*` 与 Actuator 健康组（R3-H），deploy 的探针使用它们 |
| 缺少持久存储时快速失败 | 默认 Mongo，缺失即启动失败 | A | R3-D：`wow.backend=auto` 只在开发 profile（`wow.internal.dev-profiles`）下退回内存，否则启动失败 |
| Maven 构建 | 运行期反射，Maven 可运行 | C | D239 ②：V10 目前只支持 Gradle（KSP、Manifest 补全）；快速开始与迁移 Skill 写明 |
| CLI：TS 生成器 | `wow-generator` | A | `typescript/wow-generator`（Manifest 输入，5a.3） |

## 15. TypeScript

| 能力 | 9.x | 类 | V10 位置 / 决定 |
|---|---|---|---|
| 命令客户端与等待 SSE | `wow-client` `client/command` | A | `typescript/wow-client/src/{commands,wait,streams}.ts` |
| 快照/事件/数据集/能力描述客户端 | `client/query/*` | A | `typescript/wow-client/src/queries.ts` |
| `Condition` 与操作符本地化 | `legacy/*` | C | D125 |
| 按所有者路径读状态 `loadOwnerStateAggregate` | `client/query/state` | C | D16 |
| React 查询钩子 | `wow-react` | A | `typescript/wow-react`（以 `execute` 泛化，可接 `wow-client`） |
| view-engine 与 view-store 客户端 | 包 | A | 7.3a（#4480）、`typescript/wow-view-store/src` |
| analytics 控制台 | `analytics/console` | B | `platform/analytics/catalog/console`（7.2c-1、7.2c-2） |

## 16. 数据迁移

| 能力 | 9.x 来源 | 类 | V10 位置 / 决定 |
|---|---|---|---|
| MongoDB 事件存储导入、追尾、校验、快照 | `wow-mongo` | A | `migration/migrate-data`（8b.1、8b.2） |
| Redis 事件存储来源 | `wow-redis` | C | D213 |
| ES 事件存储来源 | `wow-elasticsearch` | E | G-5 |
| `PrepareKey` 导入 | Mongo/Redis prepare | A | 8b.3（D227 ③，`NineXReservations.kt`） |

## 17. 缺口与去向（2026-10-17 复核，D229）

复核在 `facbe4e83` 上逐项核对 9.x 的 156 项面向用户的能力：A 已有 64、B 替代 41、C 放弃 24、D 推迟或计划 14、E 无决定 13（2026-10-19 补登 G-14 后为 157 项，D 15）。E 类的去向由用户按推荐确定（D229）：

| ID | 9.x 能力 | V10 现状 | 去向（D229） | 工作包 | 阻塞 M8 |
|---|---|---|---|---|---|
| G-1 | 补偿服务里未解决的 `ExecutionFailed` | 已补：`migrate-data` 把未解决的执行导入停放台账，`compare` 校验 | **GA 前补**（已完成）：`migrate-data` 把未解决的执行导入各服务的停放台账（含重试规格与可恢复标记），`compare` 校验 | 8b.4 | 是（8c.1） |
| G-2 | 状态点读的响应脱敏（9.x `PointReadAdmission`） | 已补（P.14 #4551） | **GA 前补**（安全回归） | P.14 | 否 |
| G-3 | 平台服务的生产形态（CoSec、OTel、K8s 清单、控制台登录） | 已补（P.15 #4575；K8s 清单未在真实集群验证） | **GA 前补** | P.15 | 部分（8c.1 的生产形态演练） |
| G-4 | 9.x 主题的外部消费者（07 承诺的「9.x 格式 Sink」） | 未实现 | **放弃**：下游在切换时同步改接 V10 Sink，07 写明切换规程 | — | 规程（8c.1） |
| G-5 | 9.x Elasticsearch 事件存储作为迁移来源 | `migrate-data` 只读 MongoDB | **放弃**（同 D213）：先在 9.x 侧迁到 MongoDB | — | 规程 |
| G-6 | `@Retry` 的可恢复性分类（`recoverable`、`unrecoverable`、`RecoverableExceptionProvider`） | 所有失败一视同仁重试 | **GA 前补**：`@Retry` 加带默认值的属性与可恢复分类端口，不可恢复的直接停放 | P.16 | 否 |
| G-7 | `PrepareKey` 的查找、值类型与 TTL | 应用代码无按键查询；无 TTL | **10.x**：只读的预留查询；TTL 放弃（用定时命令加 `release`） | — | 否 |
| G-8 | Java 用户体验（Java 测试 API、Java decide 调用挂起接口） | JVM 客户端已有 Java 门面（`CompletableFuture`，`edge/client/.../java/`）与 `:java-smoke` 冒烟测试；Java decide 内的 `reserve`、`SagaContext.call` 仍为 `suspend` | **GA 前**（已完成）：写明限制并加 Java 冒烟测试；**10.x**：Java 测试门面与阻塞/Future 桥 | P.17 | 否 |
| G-9 | 定向重放（按聚合版本区间重投、批量重发状态事件） | 只有停放恢复与整订阅重建 | **10.x** | — | 否 |
| G-10 | 默认请求事实 `remote_ip`、`user_agent` | 已补（P.14 #4551） | **GA 前补**（与 G-2 同批） | P.14 | 否 |
| G-11 | 补偿通知的控制台链接与模板 | webhook 固定格式 | **10.x** | — | 否 |
| G-12 | Kafka 生产者、消费者分开的配置与接收调优 | 只有一个 `wow.kafka.properties` | **10.x** | — | 否 |
| G-13 | 请求 ID、空间的请求头别名 | 只认 `Wow-Request-Id`、`Wow-Space` | **放弃**：网关改写请求头；迁移 Skill 写一行 | P.17（Skill） | 否 |
| G-14 | springdoc 集成：Swagger UI、与应用接口合并、鉴权方案（2026-10-19 补登，原误判为 A） | 已补（P.19 #4574） | **GA 前补**（D233） | P.19 | 否 |

Elasticsearch 后端（6.7，D167：快照存储、快照查询、事件检索）与 D178（跨上下文事件带类型解码，DX-1c）已完成。已决定但未实现、也没有工作包的还有 D142（嵌套展开），登记为 10.x。

## 18. 上一版 §13 的结案

2026-10-13 版 §13 的 17 个缺失项全部有了去向：P.1～P.13 已合并；#10 由 D186、#13 由 D187、#14 由 D188 决定，#17 中 `TestValidator` 由 D200 决定；#15 并入 6.0f，#16 推迟到 10.x。
