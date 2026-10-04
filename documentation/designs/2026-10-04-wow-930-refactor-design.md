# Wow 9.3.0 整体重构

日期：2026-10-04。状态：已确认，实施中（§8 的 13 个问题 2026-10-04 全部按推荐拍板，见 §8「决定」列）。

本文从第一性原理审视 Wow 的全部 Kotlin 模块和 TypeScript 边界，给出 9.3.0 的目标架构与实施计划。依据是四份只读审查（写侧核心、运行时与存储、查询与数据、边缘与集成，均基于 `origin/main` `3b720959e`），文中的行号都指那个提交。已有的三份设计——[命令链路 v9](2026-09-28-command-chain-v9-architecture-design.md)、[查询目标架构](2026-09-24-query-target-architecture-design.md)、[存储批量](2026-09-09-storage-batching-redesign.md)——在本文里各有位置（§9），不重新论证已经定下的决定。

## 1. 约束

1. **v9 兼容边界不变。** REST 行为（路由、请求头、JSON、SSE、错误码、HTTP 状态）、存储契约（持久化格式、索引、唯一键、EventStore/SnapshotStore SPI）、线上格式（Kafka 消息与键、等待信号）全部冻结。判定规则沿用命令链路设计 §1：把 9.2.x 与 9.3.0 部署在同一个集群，共用存储、Kafka 和客户端，任一方向的调用、读取和行为都与 9.2.x 一致。通不过的改动不进 9.3.0，也不留待办。
2. **公开 Kotlin API 可以破坏，但要有理由。** 9.3.0 是次版本（x.Y.0）。每一处破坏都写进发布说明的「Breaking」，能先 `@Deprecated` 再替换的就不直接删。`<module>/api/*.api` 是公开面的唯一判据。
3. **安全修复例外。** 只改变恶意或畸形请求结果的修复（空路径段、保留请求头、门面类名）按安全修复处理，写进「Behaviour changes」；混部时旧节点对这些请求的行为不变，新节点拒绝。
4. **每一步都要能证明行为不变。** 没有证明手段的重构不开始（§5 第 0 阶段先补齐）。

## 2. 诊断

审查的结论不是“代码烂”，而是**同一类问题在每个子系统里重复出现**。按根因归成七类：

| # | 根因 | 代表证据 | 后果 |
|---|---|---|---|
| D1 | **一个事实多处决定** | 租户、所有者、空间、聚合 ID 在 webflux 边缘、`CommandFactory`、命令处理、事件流工厂四层决定，优先级各不相同（`AggregateRequest.kt:35-102`、`CommandFactory.kt:85,99`、`SimpleCommandAggregate.kt:71-76,112`、`DomainEventStreamFactory.kt:112-115`）；错误映射有 JSON、SSE、批量三条路径（`WebFluxResponseStrategy.kt:142`、`BatchResults.kt:31`）；重试策略三套默认值，Kafka、Redis、Mongo 各不相同 | 每条新规则要改 N 处，漏一处就是安全缺陷：**空路径段退回读请求头**（13 个调用点）、**`Command-Header-command_operator` 冒充操作人**、静态租户只在 REST 入口生效、SSE 把内部异常消息原样返回 |
| D2 | **没有 API 分层** | wow-core 656 个公开类型，`@InternalWowApi` 用了 0 次；wow-query 236 个公开类，后端 SPI 与应用 API 同级 | 任何内部重构在 ABI 上都是公开破坏；命令链路设计“内部可完全重构”的前提不成立 |
| D3 | **热路径每次重建可复用的东西** | 每条命令新建处理器、状态聚合（含自己的溯源表）、命令聚合、函数解析器（`AggregateProcessorFilter.kt:38`、`StateAggregateMetadata.kt:56`、`CommandAggregateMetadata.kt:116-128`）；每个事件×函数复制一次属性表；查询每行建路径字符串（`BackendQueries.kt:161-165`）；查询模型每 5 分钟无条件重编译并丢掉缓存（`QueryModelSchemaProvider.kt:92-100`） | 分配量随命令数、事件数、行数增长，而不是随聚合类型数 |
| D4 | **资源随领域模型而不是硬件扩展** | 每个（分发器×聚合）一个线程池和一个 Kafka 消费者（`AggregateSchedulerSupplier.kt:140`、`MainDispatcher.kt:155-173`、`AbstractKafkaBus.kt:175-198`）；16 核、10 个本地聚合的例子里，单实例约 1,000 个线程、110 个消费者；重试退避占住整个哈希组 | 线程、连接、重平衡开销随聚合数增长；一个冲突的聚合拖慢同组的其他聚合 |
| D5 | **失败被静默** | 事件处理器默认记日志后确认（`Handler.kt:113-124` + `ExchangeAck.kt:41-45`）；补偿过滤器记录失败时用发送错误替换原始错误（`CompensationFilter.kt:108`）；Redis 接收不重试、Redis/Kafka 的瞬时异常不算可恢复；`TracingEventStore` 不覆盖 `existsRequestId`，开启 OTel 后去重检查读整条事件流 | 投影和 Saga 在缺少补偿模块或补偿记录失败时是“至多一次”且无痕；同一故障在不同后端表现不同 |
| D6 | **可选功能压在核心路径上** | `wow-webflux` 以 `api` 依赖 `wow-bi`（连带 ClickHouse 客户端）；路由契约的构建同时生成 OpenAPI schema，`wow.openapi.enabled=false` 也照样生成；`wow-compiler`（KSP）依赖整个 wow-core 运行时 | 每个 HTTP 应用都带 8.2k 行 BI；启动成本与 swagger 版本耦合到运行时；KSP 类路径拖着 Reactor、CosId |
| D7 | **复制粘贴的管道和手写镜像** | Kafka、Redis 各一套 3 个总线 + 3 个交换 + 转换器 + 就绪逻辑；13 个 `Metric*`、约 10 个 `Tracing*` 装饰器手工转发 SPI；BI 有自己的一套类型推断（不认 `@Sensitive`）；TS 客户端用正则读 Kotlin 源文件来核对请求头 | 一个修复要做 N 遍；装饰器漏覆盖一个默认方法就是性能缺陷（D5 的 OTel 例子） |

此外还有一类**缺少护栏**的问题：命令、事件、等待信号的线上格式没有黄金样本，没有新旧版本混部测试，JMH 报告停在 8.10.4，消费侧（事件分发、本地优先、Kafka/Redis 接收）没有基准。

已经做对、不需要动的：查询核心基本符合目标设计（单一准入、`AdmittedQuery`、能力表编译一次、兼容垫片全部登记）；路由目录 `RouteCatalog` 已经是 webflux 和 OpenAPI 的唯一路由来源（284 条路由的快照）；运行时生命周期的状态机；批量写入的架构；`appendResolvingOutcome` 的版本槽判定。

## 3. 原则

从上面的根因反推，9.3.0 的每个改动都要服从以下八条：

1. **一个事实只在一处决定。** 身份（租户、所有者、空间、聚合 ID、操作人）由一个解析器决定，边缘只收集线索并标注来源；错误到 `ErrorInfo` 的映射只有一个；重试与失败策略在核心定义一次，后端只做适配。
2. **路由契约是运行时真相，文档是它的投影。** 运行时按契约里每条路由声明的变量决定读路径还是读请求头；OpenAPI 渲染只读契约，不参与构建。
3. **编译一次，热路径不解释。** 聚合模型、处理器参数解析、查询掩码计划、计量器都在启动或首次使用时编译；热路径只查表。
4. **资源随硬件扩展。** 线程数随 CPU，消费者数随限界上下文，与聚合类型数无关。
5. **失败不静默。** 每一次失败要么被处理、要么被持久记录、要么不确认（重投），并且有指标。
6. **显式分层：应用 API / SPI / 内部。** 内部类型标 `@InternalWowApi` 或改成 `internal`，SPI 用显式标注，应用 API 才算兼容承诺。
7. **可选功能不进核心依赖。** BI、OpenAPI 渲染、补偿等通过注册扩展点接入，不让核心模块依赖它们。
8. **先护栏，后重构。** 每个工作包先写能证明行为不变的测试（黄金样本、混部、契约快照、特征测试、基准），再改实现。

## 4. 目标架构

模块边界基本不变（只新增一个 `wow-metadata` 模块，见 §8 Q12）；变化在模块内部的分层与依赖方向。

```text
wow-api            注解与值契约；新增聚合级策略声明（spaced / owner，§8 Q3）
   ▲
wow-core
 ├─ modeling.metadata    AggregateMetadata = 名称 + 静态租户 + spaced + owner；唯一读取策略注解的地方，冲突即启动失败
 ├─ identity             IdentityResolver：每个身份事实一条规则；线索带来源 ROUTE / AUTH / HEADER / BODY / UPSTREAM   [internal]
 ├─ command              CommandGateway（门面）→ 准入（校验 → 去重 → 等待登记）→ CommandBus
 ├─ command.kernel       AggregateModel（启动时编译：命令表、溯源表、after 列表、参数解析器）
 │                       决定 → 追加 → 应用；无状态调用器                                                     [internal]
 ├─ eventsourcing        EventStore / SnapshotStore SPI（冻结）+ 装饰器契约测试
 ├─ messaging.transport  Transport SPI：send / open(group, topics) / record.ack；TransportFailurePolicy；TopicNaming（冻结的命名）
 ├─ messaging            TransportMessageBus（通用编解码、校验、就绪）；MessageBus 只有一个接收入口；本地优先是路由策略
 ├─ execution            KeyedExecutor：每个运行时一组按 CPU 的工作线程，每个聚合 ID 一个邮箱；重试不占用组
 ├─ dispatch             每个（角色, 限界上下文）一个接收器 → 邮箱 → 函数；ProcessingOutcome
 ├─ processing.failure   FailureRecorder SPI（默认：日志 + 指标）；补偿模块提供持久记录器
 ├─ runtime              WowRuntime（语义不变）；生命周期线程池全部归 RuntimeExecutionResources；存储与总线纳入关停时限
 └─ metrics              按（描述符, 结果, 异常类）缓存计量器
   ▲
wow-query          api（应用）/ spi（@WowSpi，后端实现者）/ internal 三层
wow-mongo, wow-elasticsearch, wow-redis, wow-kafka     只做适配：实现 SPI，注册各自的可恢复异常
wow-openapi        contract 层（RouteCatalog、路由元数据、请求头名、RouteSuffixes）与 render 层（OpenAPI 渲染，惰性注册 schema）
wow-webflux        identity：RouteIdentityBinding（物化路由时按契约算出每个身份事实的来源）→ IdentityResolver
                   唯一的错误生成器（JSON、SSE、批量）；不再依赖 wow-bi
wow-bi             通过路由与错误码注册接入；类型事实与查询模型对齐（§5 Q4）
wow-cosec          只贡献请求头别名与 appender，不再覆盖两个 SPI
starter            显式的自动配置顺序；路由选择（BI、补偿）集中在一个 RouteSelection
wow-compiler       只依赖元数据模型，生成结果可复现
```

依赖方向：后端 → 核心 SPI；可选功能 → 注册扩展点；边缘 → 核心的解析器。没有反向依赖。

**扩展点（9.3.0 之后）：** `Transport`（新消息中间件）、`FailureRecorder`、`TransportFailurePolicy`、`RecoverableExceptionProvider`（已有）、`BatchWriter`（已有）、查询后端 SPI、`ScopeContributor`（查询作用域可组合）、`IdentityResolver`（替代覆盖两个 SPI）、`WebFluxErrorStrategy`（覆盖全部错误出口）。

## 5. 实施计划

七个阶段，每个工作包（WP）是一个或几个可独立合并的 PR。最多三路并行，同一时间不改同一批文件。“兼容类别”：**内部** / **API 弃用→替换** / **API 破坏（有理由）** / **行为变化**（写进发布说明）。

### 第 0 阶段：护栏（先于一切合并）

| WP | 内容 | 证明 | 规模 |
|---|---|---|---|
| G1 | 线上格式黄金样本：命令、事件流、状态事件 JSON；Kafka 头（含 `command_wait_*`、`local_first`）；等待信号。新旧版本混部集成测试：9.2.2 镜像与当前构建共用 Mongo、Kafka、Redis，双向收发命令与事件、等待结果 | 样本本身即测试 | M |
| G2 | 基准：在 9.2.2 上重跑 JMH 作为基线；新增事件分发（1/N 个处理器、指标开关、1/4 个生产者）、本地优先发送、Kafka/Redis 接收到确认、整路由分发、命令 appender 的基准 | 基线报告入库 | M |
| G3 | 架构测试：核心不引用 HTTP 与驱动类型、后端之间互不依赖、存储包不引用查询包；装饰器契约测试（每个 `Metric*`/`Tracing*` 必须覆盖 SPI 里所有带默认实现的方法） | 测试本身 | S |

### 第 1 阶段：安全与正确性（先发 9.2.3，§8 Q1）

| WP | 内容 | 兼容类别 | 证明 | 规模 |
|---|---|---|---|---|
| S1 | **空路径段**：路由声明了 `{tenantId}`/`{ownerId}`/`{id}` 时只读路径，空白值返回 400，不再退回请求头；一次覆盖 13 个调用点（`AggregateRequest.kt` 三个函数） | 行为变化（安全） | 每类路由的 `%20` 测试；OpenAPI 与契约快照不变 | S |
| S2 | **保留请求头**：`Command-Header-*` 不能写框架保留键（操作人、等待、本地优先、追踪、空间），否则 400；操作人在所有 appender 之后确定；前缀匹配不区分大小写（HTTP/2） | 行为变化（安全） | appender 测试；h2c 测试 | S |
| S3 | **门面**只接受已注册且启用的命令类型（B2）；期望版本 0 不再等于创建（V2，B12） | 行为变化（安全） | 特征测试先写失败 | S |
| S4 | **命令处理的正确性**：先校验后去重，发送失败释放去重预留，无 EventStore 时存在性检查回答“不存在”（B7）；`@OnError` 只在最终失败后执行一次（B4）；每次重试使用干净的交换状态（B5） | 内部（只影响失败路径） | 每个缺陷一个先失败的特征测试 | M |
| S5 | **静默失败的小修**：`TracingEventStore`（及 ES 事件存储）覆盖 `existsRequestId`；补偿过滤器保留原始错误、记录失败时不确认；`RedisEventStore.loadStream(time)` 返回 `Flux.error` | 内部 | 装饰器契约测试（G3）；补偿测试 | S |
| S6 | **装配缺陷**：`wow.compensation.enabled=false` 与 webflux 同开时启动失败；CoSec 与 webflux 默认 bean 的先后只靠字母序；BI 巡检自动配置缺 `@ConditionalOnClass` | 内部 | `ApplicationContextRunner` 先复现再修 | S |

### 第 2 阶段：声明与身份

| WP | 内容 | 兼容类别 | 证明 | 规模 |
|---|---|---|---|---|
| I1 | **`spaced`/`owner` 移到聚合级**（已定）：wow-api 新增聚合级声明（形状见 §8 Q3）；`AggregateMetadata` 增加 `owner`，解析顺序“新声明 → `@AggregateRoute` → 默认”，两处都声明且不一致时启动失败、KSP 编译报错；静态租户的三处声明（`@StaticTenantId`、`@BoundedContext.Aggregate.tenantId`、`wow-metadata.json`）加同样的冲突检查；KSP 把 `spaced`/`owner` 写进 `wow-metadata.json`；openapi、webflux、cosec 改读 `AggregateMetadata`；example、view-store 迁移到新声明 | API 弃用→替换（`@AggregateRoute.spaced/owner`，登记 compat-debt） | 矩阵测试（只旧 / 只新 / 一致 / 冲突）；OpenAPI 快照逐字节不变；**只改声明不改行为**，混部一致 | M |
| I2 | **身份解析器**：核心 `IdentityResolver` + 边缘 `RouteIdentityBinding`（物化路由时从契约算出每个事实的来源：STATIC / PATH / HEADER / OWNER / NONE）；13 个调用点、`CommandBuilderExtractor`、`QueryRequestScope`、网关和 Saga 都经由它；CoSec 只贡献请求头别名；V3：路由或认证已给出租户、所有者、空间时，请求体或请求头给出不同的值就拒绝（400）；`ServerRequest.getTenantId` 等扩展函数弃用并委托 | API 弃用→替换；V3 为行为变化（只影响自相矛盾的请求） | REST 黄金测试（G1）；按入口比对命令消息相等；CoSec 测试；example-server 的 TS 契约测试 | M |
| I3 | **查询作用域可组合**：`ScopeContributor` 组合成 `QueryRequestScope`；view-store 以贡献者加入 `appId`，删除从 Reactor 上下文取原始请求的写法；声明 DSL 能把不透明字段打开为对象，删除 `ViewConfigQuerySchema` 的手工改写；view-store 的空白路径过滤依赖 S1 简化 | API 新增 | view-store 安全测试；查询错误契约测试 | M |

### 第 3 阶段：API 分层

| WP | 内容 | 兼容类别 | 证明 | 规模 |
|---|---|---|---|---|
| T1 | **wow-core 划界**：命令内核、`CommandState`、`CommandFunction*`、交换属性键、`RetryableAggregateProcessor*` 等只在 wow-core 内部使用的类型标 `@InternalWowApi` 或改成 `internal`；跨模块使用的（`AggregateProcessorFilter`、`CommandAggregateFactory`、`EventSourcingStateAggregateRepository`、`ServerCommandExchange`）保留或标 SPI | API 破坏（有理由：从来不是受支持的扩展点；发布说明逐个列出） | `checkKotlinAbi` 差异逐项审；TCK、wow-test 编译 | M |
| T2 | **wow-query 分层**：后端 SPI 标 `@WowSpi`（opt-in，警告级）；只在模块内使用的约 8 个类型改 `internal`（`FilterNormalizer`、`QueryFieldCapabilities` 等） | API 破坏（有理由：查询设计 §1 已声明 SPI 与内部类型不兼容） | ABI 差异；mongo、es、tck 编译 | S |
| T3 | **消息 SPI**：`MessageBus` 只保留一个接收入口 `receiver`，`receive`/`runtimeReceiver` 弃用并委托 | API 弃用→替换 | 装饰器契约测试 | S |

### 第 4 阶段：写侧内核（命令链路设计的步骤 2、3、6、8，用 Reactor 实现）

| WP | 内容 | 兼容类别 | 证明 | 规模 |
|---|---|---|---|---|
| K1 | **编译一次**：启动时构建 `AggregateModel`（命令表含父类型与接口匹配、共享溯源表、after 列表、预解析的注入参数）；调用器无状态，接收者作为参数；重复处理器打 WARN（V7） | 内部（T1 之后） | 聚合与 TCK 测试；空结果语义的特征测试；JMH 每命令分配量必须下降 | L |
| K2 | **内核顺序**：决定 → 追加 → 应用（B9）；用户溯源函数执行完再推进版本；失败的状态实例丢弃；删除 `CommandFilter`（V5，发布说明给出到新扩展点的映射）；测试 DSL 驱动同一个内核（V6，发布说明逐条列出断言差异） | 内部 + API 破坏（V5、V6） | B9 特征测试；TCK `CommandDispatcherSpec`；wow-test 自身测试 | L |
| K3 | **准入与网关**：网关只做门面；准入一条链（校验 → 去重 → 等待登记）；消息头在构建时写完、之后不再修改；一套截止时间实现；SENT 信号只在一处发出；等待信号在锁外发出（B23）；等待头只传播到发起链路（B6，存储内容按 §8 Q6）；请求头传播与可恢复异常注册改为注入的 bean，ServiceLoader 作为默认贡献者，系统属性改为配置 | 内部（请求头键集合由 G1 黄金样本锁定） | 等待测试、SSE 黄金测试、请求头键集合测试 | L |

### 第 5 阶段：传输与执行

| WP | 内容 | 兼容类别 | 证明 | 规模 |
|---|---|---|---|---|
| X1 | **Transport SPI**：Kafka、Redis、内存实现 `Transport`；通用 `TransportMessageBus` 负责编解码、校验、就绪、交换；现有 `Kafka*Bus`/`Redis*Bus` 保留为薄门面（构造器不变），各自的交换类合并为核心的 `TransportExchange`；主题名按聚合缓存；Kafka 发送去掉关联 sink | 内部 + API 弃用（交换类） | `MessageBusSpec` 等在三种实现上运行；混部测试（G1）：同主题、同键、同 JSON、同消费组 | L |
| X2 | **失败策略统一**：`TransportFailurePolicy` 在核心定义一次接收重试与解码失败策略，Redis 获得与 Kafka 相同的默认重试；Redis、Kafka 注册各自的可恢复异常；删除未使用的 `retryStrategy`（弃用）；`FailureRecorder` + `ProcessingOutcome`：失败既未处理也未记录时可选择不确认（默认行为见 §8 Q7），补偿模块改为实现记录器，不再按类名排序过滤器 | 行为变化（Redis、Kafka 的瞬时故障会重试）+ API 新增 | 暂停 Redis 容器 5 秒，分发器存活；补偿命令的线上载荷与 9.2 逐字节一致 | M |
| X3 | **生命周期**：批量写入器、Kafka 发送器以 `RuntimeComponent` 纳入运行时，在同一个 `shutdownTimeout` 内于分发器之后停止；5 个生命周期线程池并为 2 个，全部归 `RuntimeExecutionResources` | 内部 | `WowRuntimeTest`（时限）、批量关闭测试、Spring 关停测试 | M |
| X4 | **热路径**：计量器缓存（名称与标签不变）；以 G2 的数据为准，再决定是否去掉双重原子守卫、属性表逐函数复制等 | 内部 | 计量器快照测试；G2 前后对比 | M |
| X5 | **本地优先**：分布式副本异步发送，不再等本地准入（D3，已定）；去掉整条总线的监视器锁 | 行为变化（发送更早完成；线上标记语义不变） | 本地优先契约测试；混部：9.2 消费者仍过滤 `local_first` 副本 | M |
| X6 | **Redis 流**：可选的 `MAXLEN ~`/`MINID` 裁剪（默认关闭，§8 Q10）；回收没有待处理消息的空闲消费者 | 行为变化（选择开启时） | Redis 集成测试、`XINFO` 断言 | S |
| X7 | **共享执行器与按上下文接收**：一个运行时一组 `KeyedExecutor`；每个（角色, 限界上下文）一个接收器，命令侧与事件侧都改；消费组 ID 不变；`AggregateSchedulerSupplier` 与并行度配置弃用 | 内部 + API 弃用 | 每个聚合 ID 的顺序测试；**滚动升级测试**：同一消费组里 9.2 成员（按聚合消费）与 9.3 成员（按上下文消费）共存，不丢不多（至少一次以内）；线程数断言；G2 消费侧基准 | L |

X7 风险最高，放在最后。它的合并门槛是滚动升级测试与基准都通过（§8 Q9）。

### 第 6 阶段：查询、数据与边缘

| WP | 内容 | 兼容类别 | 证明 | 规模 |
|---|---|---|---|---|
| Q1 | **BI 离开 HTTP 适配器**：BI 的路由与三个错误码通过注册接入；wow-webflux 去掉 `api(wow-bi)`，starter 用 BI 特性引入 | 依赖破坏（发布说明：需要 BI 脚本路由的应用自己加 `wow-bi`，§8 Q11）+ API 弃用（类搬包） | 路由清单快照；OpenAPI 快照不变；BI 路由集成测试 | M |
| Q2 | **查询热路径**：标准 JSON 校验只在失败时构建路径；掩码计划在编译时合并列表；版本不变时保留已发布的模型实例，9.1 声明文件扫描只在首次加载时做 | 内部 | 现有测试；新增事件流行的基准用例；“事实不变时刷新保留实例”测试 | S |
| Q3 | **BI 类型事实**：先写对齐测试，比较 BI 解析出的属性（名称、类型、可空性）与 `JsonQueryModelSource` 的类型事实；BI 遵守 `@Sensitive`（开关，§8 Q11） | 内部；`@Sensitive` 为可选行为 | `expected_bi_*_script.sql` 逐字节不变（开关关闭时） | S |
| E1 | **唯一的错误生成器**：SSE 错误帧和批量结果走同一个 `WebFluxErrorStrategy`，意外异常统一为 `InternalServerError` 并隐藏消息；弃用无人调用的 `toResponseEntity`/`toServerResponse` | 行为变化（只影响意外异常的 SSE/批量错误码，§8 Q8） | 错误码黄金表；意外异常的 SSE 测试 | S |
| E2 | **远程 IP**：用地址字面量代替会阻塞事件循环的反向 DNS；X-Forwarded-For 选择写直白（结果不变） | 行为变化（`remote_ip` 从主机名变为 IP，§8 Q8） | appender 测试；G2 的 appender 基准 | S |
| E3 | **契约与渲染分离**：贡献者只产出符号化的 schema 引用，OpenAPI 渲染时才注册组件，路由构建不再触碰 swagger。**先测启动耗时**，有收益才做 | 内部 | 两份 OpenAPI/契约快照逐字节不变 | M |
| E4 | **跨语言契约**：`RouteSuffixes` 一处定义，贡献者与 apiclient 共用；TS 测试改为读契约快照核对 `endpointPaths.ts` 与请求头名，不再正则解析 Kotlin 源文件；在 `RouteIdSpec` 上注明它是 wow-generator 的契约；apiclient 用共享的请求头值对象收拢 14 个重复参数（新方法） | 内部 + API 新增 | 契约快照不变；新 TS 测试 | S |
| E5 | **KSP**：`wow-compiler` 只依赖元数据模型（§8 Q12），去掉生成代码里的 `LocalDateTime.now()`，构建可复现 | 内部（构建） | KSP 测试；两次构建产物一致 | S |

### 分支与验证（2026-10-04 定）

不开长期开发分支，工作包逐个合进 main：

1. 第 0、1 阶段（护栏、安全修复）不破坏兼容，在 main 上完成后从 main 发 9.2.3。
2. 以 `v9.2.3` 切出 `release-9.2`，之后的 9.2.x 补丁从它发布（dist-tag `release-9.2`，不动 `latest`）。
3. 此后 main 是 9.3.0 的开发线，破坏性改动从这时开始合入。每个 PR 的门槛：完整 CI 全绿、审查通过；改到线上格式或执行模型的（K、X1、X5、X7）必须通过混部测试；性能相关的附基准前后对比。
4. 充分验证放在 `9.3.0-rc.n`：补偿控制台端到端验证、使用方试用，通过后发 `9.3.0`。
5. 文档站随 main 部署，9.3.0 新增或改变的行为在文档里注明「自 9.3.0 起」。

### 文档与发布

每个 WP 在同一个 PR 里更新受影响的文档（中英文）。第 6 阶段之后：9.3.0 发布说明（Breaking：T1、T2、K2 的 V5/V6、Q1 依赖变化；Behaviour changes：S1–S3、I2 的 V3、X2、X5、E1、E2）、迁移指南（`@AggregateRoute.spaced/owner` → 新声明、`CommandFilter` → 新扩展点、BI 依赖）、compat-debt 新条目，然后按发布手册走 `9.3.0-rc.0` → 补偿控制台端到端验证 → `9.3.0`。

## 6. 不做什么

- **改冻结的契约**：唯一键加入租户（B27）、存储驱动的重发/发件箱（P10）、补偿改为定向发送、Kafka 主题/消费组/Redis 键/Mongo 文档结构、查询游标与描述符版本哈希、删除已弃用的 `/schema/refresh` 路由与 `condition` 线上格式、精简 284 条路由的租户/所有者变体、奇怪但冻结的请求头名。这些都只能在 v10 做，本文不留待办。
- **内部改用协程（V9）**：没有 spike，AGENTS 要求核心路径用 Reactor。9.3.0 全部用 Reactor 实现。
- **运行时生命周期状态机**：每个守卫对应一个测过的竞态，只合并线程池（X3）。
- **批量写入架构**：刚重做完，只剩已测出的准入锁热点（随 X4 按数据处理）。
- **查询侧**：快照/事件流两套平行类型、每个后端穷举的过滤编译器、手写的 `schema/query/v2/*.json` 加守卫测试、9.1 兼容垫片——都是有意为之。
- **能力判定的跨后端统一**（审查 C 的 C8）：收益主要在接入第三种存储时才兑现，风险落在描述符与准入上，9.3.0 不做。
- **单一 starter 拆成多个**：坐标变化代价大，收益小，只修具体的装配缺陷（S6）。
- **测试套件的模块拆分**、**路由分发改为前缀树**：先看 G2 的数据，没有证据不动。

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| 内核重写（K1–K3）改变可观察语义 | 先写特征测试锁住 `@OnError`、after 函数、空结果语义；TCK 全量；G1 黄金样本 |
| 混部或滚动升级出问题（X1、X5、X7） | G1 混部测试是这些 WP 的合并门槛；X7 另加滚动升级测试 |
| API 破坏影响下游 | T1/T2 的 ABI 差异逐项审，只移除仓库外没有用户的内部类型；能弃用的先弃用；发布说明逐项列出 |
| 性能改动没有收益或退化 | G2 先建基线，每个性能 WP 附前后对比，没收益就撤 |
| 工作量大、周期长 | 安全修复先发 9.2.3；各阶段内的 WP 相互独立，三路并行；X7 不通过门槛就不进 9.3.0 |

## 8. 决定

2026-10-04 全部按推荐拍板。

| # | 问题 | 决定 |
|---|---|---|
| Q1 | 安全修复（S1–S3、S5）是否先发 9.2.3 补丁 | **是**。它们只改变恶意或畸形请求的结果，不碰 ABI；早发早保护。其余进 9.3.0 |
| Q2 | 路由声明的路径变量为空白时的行为；不可见字符（如 U+200B）是否一并拒绝 | 空白 → **400**，不当作默认租户/所有者。不可见字符 9.3.0 一并拒绝（不进 9.2.3） |
| Q3 | 聚合级 `spaced`/`owner` 的声明形状 | **独立注解，以“是否存在”表示“已声明”**，与 `@StaticTenantId` 同一风格，例如 `@Spaced` 与 `@AggregateOwner(OwnerPolicy.ALWAYS)`（名称可再议）。不放进 `@BoundedContext.Aggregate`，因为注解参数分不清“没写”和“写了默认值”。静态租户三处声明一并加冲突检查 |
| Q4 | V3：路由或认证已给出租户、所有者、空间时，请求体或请求头给出不同值是否拒绝 | **拒绝（400）**。现状允许请求体覆盖授权依据，属于越权 |
| Q5 | wow-core 内部类型（T1）一次性划出公开面，作为有理由的破坏，而不是逐个弃用 | **是**。这些类型从来不是受支持的扩展点，逐个弃用会让内核重写拖到 v10 |
| Q6 | 命令链路设计的其余决定 | V1（等待键是否继续写进事件存储）：**继续写入**——事件流查询 API 会返回这些键，算 REST 可观察内容，保守处理；只收窄传播范围。V5（删除 `CommandFilter`）、V6（测试 DSL 驱动同一内核）：**按设计执行**，进发布说明。V7：**WARN**。V8：**可配置，默认关闭**。V9：**9.3.0 不用协程** |
| Q7 | 事件处理失败、且没有安装持久记录器时的默认行为 | **默认不变（记日志后确认）**，新增开关可改为不确认重投；默认值只在 v10 改 |
| Q8 | E1：SSE/批量结果里意外异常的错误码从 `BadRequest`+原始消息改为 `InternalServerError`+隐藏消息；E2：`remote_ip` 从反向 DNS 主机名改为 IP | 两项都**同意**。前者堵住信息泄露，后者去掉事件循环上的阻塞调用 |
| Q9 | X7（共享执行器、按上下文接收，命令侧与事件侧一起）是否进 9.3.0 | **进**，作为最后一个 WP；滚动升级测试和基准不过就不合并，9.3.0 照常发布 |
| Q10 | Redis 流的保留策略 | **只提供可选裁剪**，默认不裁剪（默认裁剪会影响回放旧消息的用户） |
| Q11 | Q1：需要 BI 脚本路由的 HTTP 应用改为自己加 `wow-bi`；Q3：BI 遵守 `@Sensitive` | 前者**同意**（REST 行为在加上依赖后不变，发布说明写清）。后者**加开关、默认关闭**，生成的建表语句默认不变 |
| Q12 | 两处模块边界：KSP 改依赖一个只有元数据模型的小模块（同包名，wow-core `api` 依赖它，不破坏 ABI）；路由契约拆出 wow-openapi | 前者**新增 `wow-metadata` 模块**。后者**不拆**，先在 wow-openapi 内分 contract/render 两层（E3），等有数据再说 |
| Q13 | SPI 的标注方式 | 全仓统一两个标注：`@InternalWowApi`（已有，错误级 opt-in）标内部；新增 `@WowSpi`（警告级 opt-in）标供后端、传输实现者使用的 SPI |

## 9. 与已有设计的关系

- **命令链路 v9（2026-09-28）**：本文采纳它的问题分析（附录 B）和大部分决定。实施按本文第 1、4、5 阶段重新编排：它的步骤 0 拆成 S1–S5 并可先发补丁；步骤 1 即 G1/G2；步骤 2、3、6、8 即 K1–K3；步骤 4 即 X7 并扩展到事件侧；步骤 10、11 并入 X3、S5；步骤 12 即 K2 的 V6。协程（§6、V9）不进 9.3.0。该文的状态在本文确认后改为“由 9.3.0 重构设计接管实施”。
- **查询目标架构（2026-09-24）**：已基本实施，本文只补外围（I3、T2、Q1–Q3）；文中“刷新路由已删除”的表述与实际不符（为兼容保留并弃用），随 Q2 一并更正。
- **存储批量（2026-09-09）**：架构已实施，性能验收（4 个生产者）未通过，准入锁热点并入 X4，按 G2 的数据处理。
