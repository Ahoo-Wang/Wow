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
| 批量命令 | `wow-core` `batch/*` | **缺失**（见 §13） |
| 命令结果 | `CommandResultAccessor` | 替代：`DecideScope.putResult`（D49、D51） |
| 跨聚合唯一键 | `infra/prepare`、`@PreparableKey` | 替代：`DecideScope.reserve` 与预留存储（D38、D50） |
| 期望版本 | `Command-Aggregate-Version` | 已有：`If-Match`，412 |
| 命令总线（本地优先、Kafka、Redis） | `CommandBus` 系列 | 替代：集群通道与 Partitioned/Local 路由（D6、D71）；Kafka 命令入口已有（6.3d）；按聚合的 Kafka Router 计划（D143，6.5） |

## 2. 领域模型

| 能力 | 9.x | V10 |
|---|---|---|
| 核心注解（`@AggregateRoot`、`@OnCommand`、`@OnSourcing`、`@CreateAggregate`、`@AllowCreate`、`@AfterCommand`、`@Blocking`、`@Name` 等） | `wow-api` | 已有（D79） |
| 事件修订与升级 | `@Event(revision)`、`EventUpgrader` | 替代：`@Revision`、`@Upgrade`、`@Alias`（D22、D82） |
| 固定租户 | `@StaticTenantId` | 替代：`wow.aggregates."ctx.agg".tenant`（D116） |
| 所有者与空间策略 | `@AggregateOwner`、`@Spaced` | 替代：作用域取自主体，内核作用域守卫（D56、D68、D94、D96） |
| 命令字段上的身份注解 | `@AggregateId` 等 | 放弃（D68），编译报错 |
| 标签 | 状态里提取标签 | 替代：`ApplyTags` 生命周期命令与查询 ABAC（D129）；从状态提取标签不做 |
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
| Kotlin 查询构建 DSL | `wow-query` DSL | **缺失**（见 §13） |

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
| 补偿服务与控制台 | `compensation/*` | 替代为 wow-ops（D14、D141），计划（6.3f、7.1） |
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

| # | 缺失项 | 影响 | 阻塞 | 规模 |
|---|---|---|---|---|
| 1 | `SagaSpec` 与投影测试 DSL | Saga、投影的作者没有单元测试 DSL；平台移植与迁移 Skill 改写 9.x 的 `SagaSpec` 都要用 | M7、M8 | M |
| 2 | `wow-models`、`wow-apiclient`、`wow-cocache` 的去留 | 迁移 Skill 需要目标或明确的放弃；analytics 与控制台可能用到 `wow-models` 的类型 | M8（可能 M7） | 决定 S；实现各 M |
| 3 | 按处理函数的重试（9.x `@Retry`） | 迁移无处映射按函数调过的重试 | M8 | S |
| 4 | 批量命令 | 一次请求提交多条命令的导入方只能逐条发送 | 可能 M8 | M |
| 5 | 处理函数的拦截器（9.x 过滤器链） | 用过滤器做日志、指标、租户上下文的用户失去扩展点；OTel（D174）覆盖一部分 | 否 | M，或决定放弃 |
| 6 | Kotlin 查询构建 DSL | 平台代码要手写查询数据类 | 否 | S |
| 7 | `AggregateSpec` 分叉场景，`expectUnchanged` 后 `expectState`（G12） | 多分支测试场景要展平重写 | 否 | S |

计划中但同样卡在 M7 门槛上的：6.2c、6.1c、6.1d、6.4a/b、6.3f、6.9、5a.2、5a.3、6.0e、D179、6.0h。卡在 M8 的：6.0f、6.0g、6.7、6.2e、6.5。

迁移 Skill 参考（`skills/wow-migrate/references/v9-to-v10.md`）中已过时的条目：挂载命令（现为 D182）、`@QueryReference`（已在 `:query-api`）、ID 生成（指向 D171）、`CommandValidator`（运行时有对应端口，D106）。
