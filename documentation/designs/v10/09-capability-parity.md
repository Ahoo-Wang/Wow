# V10 与 9.x 的能力对照

日期：2026-10-13。基线：`v10` `f71209eda`（含 D182、D183）。状态：持续维护。上位文档：[路线图](roadmap.md)（6.0i，D183）。

本文逐项列出 9.x 面向用户的能力在 V10 中的状态，是 M7 的入口门槛（D183）：缺失项默认在 M7 之前补齐，推迟或放弃须经用户批准。迁移 Skill 的已知缺口表以本文为准。

状态：**已有**（V10 有对应实现）、**替代**（以别的方式提供，附决定）、**计划**（有工作包或决定，尚未实现）、**缺失**（没有决定覆盖）、**放弃**（有意不做，附决定）。V10 的状态均按 V10 源码核对；9.x 一侧的位置在 webflux 路由、starter 配置、测试 DSL 与平台模块处只到模块级，归档前复核。

## 1. 命令侧

| 能力 | 9.x | V10 |
|---|---|---|
| 命令网关（发送与等待） | `wow-core` `CommandGateway` | 已有：`runtime/admission/CommandGateway`（D92），返回 `Flow<CommandResult>` |
| Reactor 形态的网关 | 9.x 网关即 Reactor | 计划：有需求时设 `wow-runtime-reactor`（06，D149） |
| 等待阶段与等待链 | `command/wait/*` | 替代：`Wow-Wait` 计划与多跳链（D61、D62、D99） |
| 跨上下文等待 | 经消息总线 | 已有（6.6，D34、D65） |
| SSE 推送结果 | `wow-webflux` | 已有 |
| 请求 ID 幂等 | `RequestIdChecker`、布隆过滤器 | 已有：日志唯一键与 `findByRequestId`（D46、D55、D162）；L1 布隆过滤器未做（D46 可选） |
| 命令重写 | `CommandBuilderRewriter` | 计划：`CommandTransformer`（D182，6.0h），删除 `CommandRewriter` |
| 命令校验 | `CommandValidator` | 已有：`CommandValidator` 端口与 Jakarta 校验（D106） |
| 挂载命令 | `@AggregateRoot(commands=)` | 计划：`@MountedCommand`（D182，6.0h） |
| `@VoidCommand` | 注解 | 放弃（D67）；`accepted` 等待覆盖（D100） |
| 自定义路由（`@CommandRoute` 等） | 注解与 webflux | 替代：Manifest 固定路由（D104、D113、D176）；自定义路由放弃（D104） |
| 命令请求头 | `Command-*` | 替代：`Wow-Wait`、`Wow-Request-Id`、`If-Match`、`Wow-Scope`（D95、D96、D98） |
| 请求事实传播 | `CommandRequestHeaderPropagator` | 已有：`wow.http.request-facts` 白名单 |
| 操作人 | `CommandOperator` | 计划：`Operator`（D163，6.0e） |
| ID 生成（全局、按聚合、CosId、领域内取 ID） | `GlobalIdGenerator`、`AggregateIdGenerator` | 计划：D171，6.0f |
| 批量写入（批量准入、车道） | `wow-core` `batch/*` | 替代：追加队列与组提交（D11，`wow.log.append-queue.*`） |
| 命令结果 | `CommandResultAccessor` | 替代：`DecideScope.putResult`（D49、D51） |
| 跨聚合唯一键 | `infra/prepare`、`@PreparableKey` | 替代：`DecideScope.reserve` 与预留存储（D38、D50） |
| 期望版本 | `Command-Aggregate-Version` | 已有：`If-Match`，412 |
| 命令总线（本地优先、Kafka、Redis） | `CommandBus` 系列 | 替代：集群通道与 Partitioned/Local 路由（D6、D71）；Kafka 命令入口已有（6.3d）；按聚合的 Kafka Router 计划（D143，6.5） |

## 2. 领域模型

| 能力 | 9.x | V10 |
|---|---|---|
| 核心注解（`@AggregateRoot`、`@OnCommand`、`@OnSourcing`、`@CreateAggregate`、`@AllowCreate`、`@AfterCommand`、`@Blocking`、`@Name` 等） | `wow-api` | 已有（D79） |
| 事件修订与升级 | `@Event(revision)`、`EventUpgrader` | 替代：`@Revision`、`@Upgrade`、`@Alias`（D22、D82） |
| 固定租户 | `@StaticTenantId` | 替代：`wow.aggregates."ctx.agg".tenant`（D116）；`@StaticAggregateId` 没有单独的决定，待复核 |
| 所有者与空间策略 | `@AggregateOwner`、`@Spaced` | 替代：作用域取自主体，内核作用域守卫（D56、D68、D94、D96） |
| 命令字段上的身份注解 | `@AggregateId` 等 | 放弃（D68），编译报错 |
| 标签 | 状态里提取标签 | `ApplyTags` 生命周期命令与查询 ABAC 已有（D129）；**从状态提取标签缺失**（见 §13） |
| 生命周期命令 | 删除、恢复、转移等 | 已有（D91、D113） |
| `@EntityObject`、`@ValueObject`、`@Order`、`Identifier` | 注解 | 放弃（D67） |
| `@OnError` | 注解 | 放弃（D7） |
| Java 领域模型 | 反射 | 已有：KSP 处理 Java 源码（D8） |
| 领域服务注入 | `ServiceProvider` | 已有：`Dependencies` 与生成的工厂（D107、D119） |
| 错误码拒绝 | 异常与 `ErrorInfo` | 已有（D121） |
| 定时命令 | 无 | 已有（新增，D166） |

## 3. 事件侧

| 能力 | 9.x | V10 |
|---|---|---|
| 事件存储：MongoDB；PostgreSQL（新增） | `wow-mongo` | 已有（D10、D11、D162） |
| 事件存储：Redis、Elasticsearch | `wow-redis`、`wow-elasticsearch` | 放弃（D12、D13） |
| 快照存储：MongoDB、PostgreSQL | `wow-mongo` | 已有（D161） |
| 快照存储：Elasticsearch | `wow-elasticsearch` | 计划（6.7，D167） |
| 快照策略（按版本间隔） | `SnapshotStrategy` | 替代：每个版本都写（D4） |
| 按聚合关闭快照 | 无 | 计划（D4） |
| 按聚合配置存储 | `storage-routing` | 计划（D177，6.0g） |
| 快照重建 | 重建路由 | 计划（D172，6.2e） |
| 事件补偿、重发状态事件 | `event/compensation` | 替代：停放与失败台账、恢复与跳过（D14）；运维入口计划（D141，6.3f） |
| 历史状态（按版本、按时间、追踪） | 按版本读 | 已有（D40） |
| 单聚合事件流查询 | `EventStreamQueryBackend` | 计划：`events` 模型（D131，6.2c） |
| 跨聚合事件检索 | Elasticsearch | 计划（6.7） |
| 领域事件总线、状态事件总线 | `DomainEventBus`、`StateEventBus` | 替代：日志即总线（D5），跨上下文经 Kafka Sink（D23） |

## 4. 处理器

| 能力 | 9.x | V10 |
|---|---|---|
| Saga、投影、事件处理器 | `@StatelessSaga`、`@ProjectionProcessor`、`@EventProcessor` | 已有：`@Saga`（含 `@OnRejected`）、`@Projection`、`@Reactor` |
| `@OnStateEvent`、`@OnMessage` | 注解 | 替代：统一的 `@OnEvent` 与 `StateEvent<S>`（D67、D81） |
| 跨上下文事件处理 | Kafka 总线 | 已有（D134、D138、D140）；带类型解码计划（D178） |
| `@Retry` | 注解 | 替代为订阅配置，目前只有全局 `wow.subscription.retry`；**按处理函数的重试缺失**（见 §13） |
| 订阅起点 | 无 | 已有配置形式（D154）；注解形式计划（D179） |
| 过滤器、拦截器、`@Order` | `filter/*`、`ExchangeFilter` | `@Order` 放弃（D67）；**处理函数的拦截器缺失**（见 §13） |

## 5. 查询

| 能力 | 9.x | V10 |
|---|---|---|
| 查询协议、引擎、TCK、内存后端 | `wow-api` `query`、`wow-query` | 已有（D125、D126） |
| 快照查询：MongoDB；PostgreSQL（新增） | `wow-mongo` | 已有（6.2a、6.2b） |
| 快照查询：Elasticsearch | `wow-elasticsearch` | 计划（6.7） |
| 查询 HTTP 路由 | `wow-webflux` | 计划（D127，6.2c） |
| schema、脱敏、作用域与 ABAC | `wow-query`、`wow-cosec` | 计划：6.1c（在审） |
| 查询字段注解（含 `@QueryReference`） | `wow-api` | 已有 |
| 数据集（目录、网关、动态注册） | `wow-query` `dataset` | 计划（6.1d） |
| ClickHouse 数据集后端 | `wow-clickhouse` | 计划（6.4a） |
| Kotlin 查询构建 DSL；生成的查询字段常量 | `wow-query` DSL；编译器 `<State>Properties` | **缺失**（见 §13） |

## 6. 消息与外部存储

| 能力 | 9.x | V10 |
|---|---|---|
| Kafka 传输 | `wow-kafka` | 替代且已有：Sink、来源订阅、命令入口、回执（D31、D58、D134、D135） |
| Kafka 主题管理 | starter 配置 | 计划（D168，6.3g） |
| Redis | `wow-redis` | 放弃（D12） |
| Elasticsearch | `wow-elasticsearch` | 计划（D167，6.7） |
| BI 同步 | `wow-bi` | 替代：ClickHouse Sink 与展平视图（D18、D142、D164，6.4b 在审） |

## 7. 租户与安全

| 能力 | 9.x | V10 |
|---|---|---|
| CoSec 集成 | `wow-cosec` | 已有：`edge/cosec`（D93） |
| 租户、所有者路由前缀 | `wow-webflux` | 放弃（D16）；跨作用域操作替代为 `Wow-Scope`（D95） |
| 命令与查询统一的调用方 | 无 | 计划（D165，6.0e） |
| 查询 ABAC | `AbacQueryPolicy` | 计划（D129，6.1c） |
| 跨上下文租户 | 无 | 已定（D180） |

## 8. 契约、OpenAPI 与 TypeScript

| 能力 | 9.x | V10 |
|---|---|---|
| 元数据、JSON schema、REST 契约 | `wow-metadata`、`wow-schema`、`wow-rest-contract` | 替代：Manifest、manifest-tools、problem+json 与 `CommandResult`（D17、D60、D73、D101） |
| OpenAPI | `wow-openapi` | 计划（5a.2） |
| TypeScript 生成器与客户端 | `typescript/wow-generator`、`wow-client` | 计划（5a.3，D20） |
| Manifest 兼容门禁 | 无 | 计划（5a.4，D76） |

## 9. 可观测性

| 能力 | 9.x | V10 |
|---|---|---|
| OpenTelemetry 追踪、指标 | `wow-opentelemetry`、`metrics/*` | 计划（D174，6.9），M7 之前 |

## 10. 测试 DSL

| 能力 | 9.x | V10 |
|---|---|---|
| 聚合测试 `AggregateSpec` | `wow-test` | 已有：`kernel` 的 testFixtures（含定时器）；未作为 `wow-test` 发布（G5）；`expectUnchanged` 后不能 `expectState`（G12）；**分叉场景缺失**（见 §13） |
| `SagaSpec` 与投影测试 DSL | `wow-test` `saga/stateless` | **缺失**（见 §13） |
| 端口 TCK | `wow-tck` | 已有：`spi` 与 `query` 的 testFixtures；未发布 |
| `wow-mock` | `wow-mock` | 放弃（06 §2） |

## 11. 平台服务与其他模块

| 能力 | 9.x | V10 |
|---|---|---|
| 补偿服务与控制台 | `compensation/*` | 替代为 wow-ops（D14、D141），计划（6.3f、7.1）；**失败通知 webhook 缺失**（见 §13） |
| K8s 部署清单 | `deploy/*` | **缺失**（见 §13） |
| view-store、dataset-registry、analytics | 各自目录 | 计划（7.2，D146） |
| `wow-models`（`EnumText`、`StatusCapable` 等） | `wow-models` | **缺失**：06 §6 未定（见 §13） |
| `wow-apiclient`（Kotlin 声明式客户端） | `wow-apiclient` | **缺失**：06 §6 未定（见 §13） |
| `wow-cocache`（分布式状态缓存） | `wow-cocache` | **缺失**：06 §6 未定（见 §13） |
| Spring 集成 | `wow-spring` | 替代：`host/spring-boot`（D44） |

## 12. Spring Boot 配置

| 能力 | 9.x | V10 |
|---|---|---|
| `wow.*` 配置 | starter 的各 `*Properties` | 替代为新键（D110），9.x 的键不沿用 |
| 能力变体（`mongo-support` 等） | starter 的 Gradle feature | 替代：每种技术一个模块（D44） |
| 按聚合的 `routing`、`id-generator`、`log.store`、`snapshot.store` | `storage-routing` | 计划（6.5、6.0f、6.0g） |
| `wow.contexts.include` / `exclude` | 无 | 计划（D169，E.R） |

## 13. 缺失项（M7 之前须补齐或获批豁免）

**决定（2026-10-13，用户确认按推荐）**：补齐项编为路线图 M6p 的 P.1～P.9（D184）；放弃 `ChangeFunction` 与强制准备（D185）、#10 的 6 项（D186）、`wow-apiclient` 与 `wow-cocache`（D187，`wow-models` 迁到 V10）、处理函数拦截器（D188）；#8 与 #12 的 G12 部分已由 #4380 完成；#15 并入 6.0f；#16 推迟到 10.x。

第三轮核对（覆盖 webflux 路由、starter 配置、测试 DSL、补偿服务）后的合并清单。批量写入经复核为「替代」（追加队列与组提交），不再列入。

**阻塞 M7（平台迁移）**

| # | 缺失项 | 影响 | 规模 |
|---|---|---|---|
| 1 | 停放的长周期自动重试（9.x `CompensationScheduler`、`RetrySpec`） | wow-ops 失去无人值守的恢复：V10 只在进程内重试几次后停放，等人工恢复 | M |
| 2 | 每条失败的运维操作（`MarkRecoverable`、`ApplyRetrySpec`、`ChangeFunction`、强制准备），D141 只有 Resume/Skip | 运维无法按条调整重试 | S～M |
| 3 | 失败通知（企业微信 webhook） | 值班收不到告警 | S |
| 4 | Kotlin 查询 DSL 与生成的字段常量（`<State>Properties`） | 平台移植（补偿的 `SnapshotFindNextRetry`、view-store、dataset-registry）要手写查询 | M |
| 5 | 按订阅的重试与超时（9.x `@Retry`） | 调用慢外部系统的处理函数要有自己的预算；现在只有全局 `wow.subscription.retry` | S |
| 6 | V10 的 K8s 部署清单（内部端口、PostgreSQL） | 跑不起 M7 的平台服务 | S |
| 7 | `SagaSpec` 与投影/反应器测试 DSL | 平台移植与迁移 Skill 改写 9.x 测试都要用 | M |
| 8 | 跨上下文命令缺少 Kafka 时的启动检查（G9） | Saga 只在运行时失败并停放 | S |

**阻塞 M8（迁移）**

| # | 缺失项 | 影响 | 规模 |
|---|---|---|---|
| 9 | 升级时丢弃过时事件（9.x `DroppedEvent`） | 数据工具导入不了含已退役事件类型的历史 | S |
| 10 | 隐式放弃的项需要明确决定：`@VoidCommand`、`@Order`、`@EntityObject`、`@ValueObject`、`wow-mock`、`@StaticAggregateId` | 迁移 Skill 的缺口表要有决定编号 | S（文档） |
| 11 | 从状态提取标签（`StateAggregateTagsExtractor`） | 依赖 ABAC 的领域失去自动打标签 | S～M |
| 12 | 测试 DSL：`fork`/`ref` 分叉、`givenState`、按类型 `expectError`、`expectUnchanged` 后 `expectState`（G12） | 多场景测试要展平重写 | S |
| 13 | `wow-models`（`EnumText`、`StatusCapable`、`Tree` 聚合）、`wow-apiclient`、`wow-cocache` 的去留（06 §6） | 9.x 用户依赖它们 | 决定 S；实现各 M |

**不阻塞（10.x 或决定放弃）**

| # | 缺失项 | 规模 |
|---|---|---|
| 14 | 处理函数拦截器 SPI 与排序 | M |
| 15 | 全局 ID 的 HTTP 路由（`GET /wow/id/global`） | S |
| 16 | Kafka 解码失败的跳过策略 | S |
| 17 | `TestValidator`、Testcontainers 夹具、发布 `wow-tck` | S |

**已决定但没有工作包**：按聚合关闭快照（D4）、跨上下文事件带类型（D178）、订阅起点写在注解上（D179，G10 标为阻塞 M7）、ClickHouse 嵌套展开（D142）、L1 布隆过滤器（D46，可选）、用 Manifest 的 JSON schema 取代 `schema/wow-metadata.schema.json`。

计划中但同样卡在 M7 门槛上的：6.2c、6.1c、6.1d、6.4a/b、6.3f、6.9、5a.2、5a.3、6.0e、D179、6.0h。卡在 M8 的：6.0f、6.0g、6.7、6.2e、6.5。

迁移 Skill 参考（`skills/wow-migrate/references/v9-to-v10.md`）中已过时的条目：挂载命令（现为 D182）、`@QueryReference`（已在 `:query-api`）、ID 生成（指向 D171）、`CommandValidator`（运行时有对应端口，D106）。
