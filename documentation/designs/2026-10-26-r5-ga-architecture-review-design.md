# R5：GA 前的第一性原理架构审查

日期：2026-10-26。基线：`origin/main` `147ce51d5`（`10.0.0-alpha.5` 之后）。状态：**已采纳**（2026-10-26，用户按推荐定 Q1～Q10，即 D282～D291）；发现登记在[审查台账](reviews.md) §7（编号 R5.A-n、R5.B-n、R5.C-n），工作包是[路线图](roadmap.md) 9.3d，批 0 进行中。

上位文档：[00 目标架构](00-architecture.md)、[06 模块与目录](06-modules.md)、[审查台账](reviews.md)。

## 1. 目的与方法

GA 之后，公开 ABI、REST 形状、配置键、错误码、指标名与存储格式都按破坏性变更管理（AGENTS.md 的 Breaking 定义；D216）。所以凡是 GA 后改起来贵的东西，都要在 GA 前定对。GA 前的规则是：直接替换，不叠加兼容层（D1），SPI 可以破坏，存储格式版本在 RC 前保持为 1。本轮从第一性原理出发，回答一个问题：**哪些东西现在不改，GA 后就只能带着走，或者只能用破坏性变更去改？**

评判标准（用户 2026-10-26）：高内聚、低耦合、职责清晰、高扩展性、高伸缩性、高性能、易用性、可维护性，代码清洁、没有兼容负担。由这些标准推出 GA 必须成立的不变式：

| 编号 | 不变式 | 由哪条标准推出 |
|---|---|---|
| G1 | 运行时在任何取消与停机路径下都不会永久阻塞（活性） | 可伸缩、可维护 |
| G2 | 冻结面等于承诺面：只有用户真正需要的类型公开，其余为 `internal` 或 `@InternalWowApi` | 低耦合、可维护、前瞻 |
| G3 | 每条语义规则只有一个家，并且在核心模块里；边缘只做协议适配，宿主只做装配（A10、D44、D231） | 职责清晰、高内聚 |
| G4 | 一个概念只有一处配置、一种类型、一个名字 | 易用、可维护 |
| G5 | 契约命名空间受保护：错误码、指标名、配置键各有规则，并由测试守住 | 前瞻、易用 |
| G6 | 扩展点对称：能插存储，就能插 Sink 与传输；SPI 能区分「必须实现」和「可选优化」 | 扩展性 |
| G7 | 格式知识只有一份：后端只存它要查询的原生形式，其余是运行时的不透明字节 | 扩展性、可维护 |
| G8 | 依赖只带上用到的东西：领域模块不会因为依赖 Wow 而被 Spring 的版本约束抬高 | 低耦合、易用 |
| G9 | 用户看到的文字是解释，不是台账编号 | 易用、代码清洁 |

范围与方法：JVM 侧全部模块，分三组只读审查（A：编程模型与写核心，`:api`～`:test-dsl`，12 个模块；B：运行时与后端，6 个模块；C：读平面、边缘、宿主、迁移，以及配置、错误码、观测、ABI 这几类横切问题，10 个模块），共约 15.5 万行主代码。每条发现都带 `path:line`，已对照 R2、R3、R4-Q、M 去重。协调者对照代码复核了 B-1、B-2、C-4、C-8、A-1（见 §4「复核」列）。TypeScript 各包与平台服务放在第二阶段（§7）。

## 2. 总体判断

**扎实、不要动的部分**：写路径的正确性（去重、冲突重试、追加之后不可撤销、`Unknown` 不补偿）、追加队列、转发结局的判定（D223）、停放与系统性故障的区分、HRW 加租约仲裁、后端经 `StoreProvider` 接入（各后端的 ABI 只有 13～17 行）、错误码表与 problem+json、未知配置键拒绝启动、`:compiler`/`:reactor`/`Kernel` 的公开面。这些细节见 §8。

**问题集中在七处**，每一处都是 GA 后改起来很贵的那一类：

1. **取消是运行时最薄的不变式**（G1）。执行器车道让调用方的协程兼任执行者，批里一条跟随命令被取消，车道就可能永久卡住（B-1，P0）；停机顺序又让这种卡住变成停机挂起（B-2）。
2. **冻结面比承诺面大**（G2）。稳定性注解在 `:api` 里，而 `:query-api`、`:manifest` 依赖不到 `:api`，所以这两个模块的整个公开面要么冻结、要么只能用 `internal`（A-1、A-2、A-12）。D216 ⑤ 规定线上值类型用普通类加工厂，这条只在 `:api`、`:spi` 落实了，`:query-api` 还有 94 个 `data class`，`:kernel` 也有一些。宿主属性 Bean 约占 `spring-boot.api` 的八成（C-6）。测试 DSL 把内核的 `CommandOutcome` 交给了用户（A-3）。
3. **语义下沉只做了一半**（G3）。写侧的准入与授权都在 runtime；读侧的作用域授权在 `:http` 里抄了三份（C-1）；内部端口的安全底线（没有集群令牌就拒绝启动）、读侧组合、对端路由仍在 Spring 宿主里（C-2），而守护规则是 8 个类名的黑名单（C-10）。
4. **同一概念多处**（G4、G7）。DDL 有 5 个键、4 个枚举，语义还不一样（C-3a）；对端分 `wow.calls` 与 `wow.clients` 两处配（C-3b）；传输段在 PostgreSQL、MongoDB、Kafka 信封、集群通道里各编码一份（B-3）；错误码字符串有两份（A-9）；默认常量各定义两处（B-10）。
5. **契约命名空间不受保护**（G5、G9）。领域拒绝可以用 `wow.*` 码冒充框架语义，客户端会因此自动重试（C-8）；指标与错误码同名，分隔符混用（C-9）；决定与审查编号已经漏进 problem+json 的 `title`、异常消息、配置元数据和用户的生成代码（C-7）。主代码里含编号的有 3,575 行，R3 登记时是 439 处。
6. **扩展点不对称**（G6）。存储可插拔，Sink 与传输只能由宿主的专用自动配置装配（B-5）；`EventLog`、`SnapshotStore` 把可选优化和控制面方法混进同一个接口，组合读靠向下转型，失败了也只是静默退回（B-9）。
7. **依赖解析泄漏**（G8）。`wow-dependencies` 以 `api(platform)` 进入每个已发布库，把 Spring Boot BOM 和测试、lint 工具的约束带给所有 Gradle 用户（C-4）；`:client` 依赖整个 `:runtime` 和 `:query`，`:http` 又反过来依赖 `:client`（C-5）。

**伸缩性**：控制面按「租约组 × 节点」线性放大，与业务流量无关（B-4，15 组 × 10 节点约 550 次存储操作/秒，由代码推算）。**性能**：吞吐与 CPU 已有扎实的 ABAB 数据，但每条命令约 80 KB 的分配从来没有剖析过（B-6）。

## 3. 发现总表

级别：P0 正确性、数据丢失或安全；P1 GA 后改动代价高；P2 质量与整洁。代价：S 一两天，M 一周以内，L 更长。「面」一列写这项会改变的东西：ABI、REST、配置、存储/线路、发布元数据。各组报告的详细推理在本轮的工作区；每条发现的位置、现象与去向登记在审查台账 §7，派工说明引用台账编号。

### 3.1 活性与正确性

| 编号 | 级别 | 位置 | 发现 | 建议 | 面 | 代价 | 复核 |
|---|---|---|---|---|---|---|---|
| B-1 | **P0** | `runtime/Executor.kt:145-205`、`kernel/Kernel.kt:240` | 批取走 `[A,B,C]`，B 的调用方被取消；`abandon` 以为结果没人要。批返回 `[Done, Again, Again]` 后，B 被放回队首、接手车道，但没有协程来领，`leading` 停在 `true`，C 及之后的命令永远等。热点聚合整体停写；不带截止时间的内部来源（Saga、摄入、定时器）永远挂起，并占着摄入许可。处理函数的单次超时、分区交出、停机都会触发 | `Queued` 加「已放弃」标记；已放弃又是 `Again` 的命令不放回队列（`Again` 表示未提交、已补偿，丢弃是安全的）；`handOff` 跳过已放弃的命令；补上以取消为轴的属性测试 | — | S | 已复核 |
| B-2 | P1 | `node/ServiceNode.kt:961-973` | `EXECUTIONS_ENDED` 超时后，`executor.close()` 无界等待在途命令，而能取消这些命令的 `coordinator.close()` 排在它之后 | 先取消，再关闭；`close` 加上 `drainTimeout` 的上限 | — | S | 已复核 |
| C-8 | P2 | `api/decide/Decision.kt:126`、`kernel/RejectionCodes.kt:31` | `Rejection.of("wow.command.busy")` 会被答成 429 加 `Retry-After`，客户端自动重试；领域拒绝也能写别的上下文的完整码 | `wow.` 前缀与外上下文的码在提交时转成 `wow.command.failed` 并记日志；`of` 里直接拒绝 | REST（只影响误用） | S | 已复核 |
| A-5 | P1 | `kernel/AggregateModelDsl.kt:246`、`Kernel.kt:1372` | X-6 的新角度：DSL 默认名不读 `@Name`，GA 后写进日志的就是 simpleName，到时只能靠 `@Alias` 迁移，或升级即失败 | 从「转 10.x」拉回 GA 前：DSL 的名字与 `@Name` 不一致即失败 | 存储（GA 前无存量） | S | — |

### 3.2 冻结面（G2）

| 编号 | 级别 | 位置 | 发现 | 建议 | 面 | 代价 |
|---|---|---|---|---|---|---|
| A-1 | P1 | `api/query-api`，94 个 `data class` | 查询协议是 10.x 演进最频繁的面，`copy`、`componentN` 都进了冻结面。根因是 `:query-api` 用不了 `@InternalWowApi` | 稳定性注解移到无依赖的模块（见决定 Q1）；请求与结果改成普通类加工厂；数据集聚合标 `@ExperimentalWowApi` | ABI | M |
| A-2 | P1 | `api/manifest/Manifest.kt:37`，38 个公开 `<init>` | 只有 Wow 自己构造 Manifest，真正的契约是 JSON 加 `format`；近一个月转储改了 14 次 | 构造函数标 `@InternalWowApi`，读者 API 保持公开（决定 Q2） | ABI | S |
| A-3 | P1 | `test-dsl/Expectations.kt:58` | 要测 `putResult` 只能强转内核的 `CommandOutcome`，于是 `LogPosition` 等成了用户依赖 | 加 `expectResult`/`results`；`CommandOutcome` 标 `@InternalWowApi`，改成普通类 | ABI | S |
| A-4 | P1 | `api/ledger`、`api/snapshot`（约 500 行 ABI） | 运维线路记录放在最稳定的领域 API 里，领域代码并不用；与 `:spi` 的同一概念有两套模型 | 移到运行时一侧并标内部，ops 服务按 08 §3 的 JSON 自己定义读者类型，或另设 `wow-ops-contract` 模块（决定 Q3） | ABI | M |
| C-6 | P1 | `spring-boot.api`（1,282 行）、`migrate-data`（101 个公开声明，无转储） | 配置键既冻结了键名，又冻结了 JVM setter；迁移工具作为库发布，却没有人跟踪它的公开面 | 属性类标 `@InternalWowApi`，由元数据测试守住配置键；`migrate-data` 全部改为 `internal`，或只发布可执行包 | ABI | S |
| A-7 | P2 | `kernel.api:73-85` 等 5 处 | 在 sealed 父类型上标的 `@InternalWowApi`，挡不住嵌套子类进转储 | 嵌套子类也标；构建加一条检查：外层被隐藏而嵌套可见即失败 | ABI（只删行） | S |
| A-8 | P2 | `api/subscribe/SubscriptionContext.kt:67,123,134,174` | 冻结接口用「默认抛异常」表达能力 | 改成抽象成员，或改成可空的能力接口 | ABI | S |
| A-9 | P2 | `kernel/CommandOutcome.kt:58`、`manifest/FrameworkErrors.kt:39` | 错误码字符串有两份，只靠 `:http` 的一个测试对齐 | 码常量放进 `:api`，删掉 `KernelRejections` | ABI | S |
| A-11、A-13 | P2 | `ProcessorContexts.kt:44`、`AggregateModel.kt`（`EventType`） | 内部的执行上下文是公开 `data class`；`EventType` 的 `equals` 落在 lambda 的身份比较上，Jackson 的 `JsonNode` 进了冻结面 | 标内部，改成普通类；升级函数用 Wow 自己的 `fun interface` | ABI | S |
| B-7 | P2 | `ServiceNode.kt`（1,473 行）、`:622` | 组装根、生命周期、门面三合一；`topicSubscriptions` 把 `@InternalWowApi` 类型漏到公开 getter 上 | 拆成 `NodeAssembly`、`NodeLifecycle` 与门面；收窄这个 getter | ABI | M |
| A-10 | P2 | `kernel/ModelProvider.kt` | 生成代码与内核之间没有绑定版本握手，版本不匹配要到第一条命令才报 `NoSuchMethodError` | `ModelProvider.binding`，启动时检查 | ABI（加法） | S |
| 其余 | P2 | A-12、A-14、A-15、A-16、B-11、B-12、C-19 | 同值枚举有两套、header 重复解析、数据集借用 `AggregateType` 命名、Java 重载不统一、`ContextBackends` 两种写法、测试回环通道在主代码里、`:query` 根包 35 个文件 | 见各组报告 | 多为 ABI | S |

### 3.3 职责与依赖（G3、G8）

| 编号 | 级别 | 位置 | 发现 | 建议 | 面 | 代价 | 复核 |
|---|---|---|---|---|---|---|---|
| C-1 | P1 | `http/query/QueryHandler.kt:444-511`、`http/read/StateHandler.kt:176`、`http/ops/ParkedRoutes.kt:313` | 读侧的作用域授权（`Wow-Scope` 只给 `crossScope`、跨租户开关、固定租户收窄）在 HTTP 层抄了三份，引擎无法复核 | `QueryCaller` 带上目标作用域，在 `:query` 的准入里统一判定；加一条规则：`:http` 不得读 `crossScope` | ABI | M | — |
| C-2 | P1 | `spring/boot/WowAutoConfiguration.kt:870-939`、`InternalServer.kt`、`HostQueries.kt`、`PeerClients.kt` | 宿主里还有安全底线、内部端口服务器、舱壁与重放闸的组合、对端路由。非 Spring 的宿主拿不到「没有集群令牌就拒绝启动」这条检查。**R3.1-1 应从「已修」改为「部分」** | `:http` 提供 `InternalPort` 工厂，安全不变量写在它的构造里；`:query` 提供 `QueryStores.of(settings)`；路由客户端移入 `:client`；宿主规则改为正向白名单 | ABI（多为内部类） | L | — |
| C-4 | P1 | `build.gradle.kts:366`、`bom/dependencies/build.gradle.kts:15-31` | 每个已发布库都以 `api(platform)` 带上 Spring Boot、Testcontainers、springdoc、fluent-assert 的平台，以及 mockk、detekt 的约束。只依赖 `wow-api` 的领域模块，Jackson、Netty 的版本可能被静默抬高 | 已发布库只带一个精简平台；Spring Boot BOM 只给 `wow-spring-boot`；测试与 lint 的平台只在仓库内部使用（决定 Q6） | 发布元数据 | M | 配置已复核；对使用方的影响待用小项目跑 `dependencyInsight` 确认 |
| C-5 | P1 | `edge/client/build.gradle.kts`、`edge/http/build.gradle.kts` | `:client` 为 6 个类型带上了整个节点与查询引擎；服务端为了线路语法依赖客户端 | 线路语法（请求头的编解码、`WaitWire`）下沉到中立位置；`:client` 只依赖 `:api`、`:manifest`、`:query-api`（决定 Q5） | ABI（包迁移） | M | — |
| C-12 | P2 | `edge/cosec/CoSecAutoConfiguration.kt`、`WowProperties.kt:230` | 可选集成有两套装配方式；`wow.cosec.*` 有两个读者、两份默认值 | 与 Kafka 一致：装配进宿主，`:cosec` 只保留与 Spring 无关的部分 | ABI | S～M | — |
| C-10 | P2 | `test/architecture/...` | 规则是正则黑名单。06 §3、07 §4 声称的「不出现 9.x 的类型名与配置键」并没有对应的规则 | 关键边界改成正向约束（或换成基于字节码的检查），补上缺的规则，更正文档 | — | S～M | — |

### 3.4 格式、SPI 与伸缩（G6、G7；格式要在 RC 前定）

| 编号 | 级别 | 位置 | 发现 | 建议 | 面 | 代价 |
|---|---|---|---|---|---|---|
| B-3 | P1 | `PostgresEventLog.kt:943`、`MongoEventLog.kt:1114`、`spi/transport/EnvelopeCodec.kt:176`、`cluster/ClusterMessages.kt:91` | 传输段按设计不属于事实，存储形式却由每个后端各写一份编解码。GA 后给 `WaitContext` 加一个字段，每个后端（包括社区后端）都要改，漏改的会静默丢字段 | `:spi` 提供唯一的 `TransportCodec`；`EventLog` 合同改为「把传输段当不透明字节原样存、原样还」；Kafka 信封与集群通道复用同一个编码器 | SPI、存储（RC 前，格式号仍为 1） | M |
| B-4 | P1 | `cluster/PartitionOwnership.kt:270-466`、`spi/subscription/LeaseStore.kt:137` | 每个租约组每个节点每秒约 3.7 次存储操作，成员关系在每个组里重复心跳、重复读 | 成员关系按服务记，属主按组记；对账复用刚读到的成员 | SPI、租约表（RC 前） | M |
| B-5 | P1 | `host/spring-boot/build.gradle.kts:24`、`KafkaAutoConfiguration.kt`、`ClickHouseAutoConfiguration.kt` | `Sink` 与传输端口是 `@WowSpi`，却没有发现机制，社区要接 Pulsar 只能自己写 Spring 配置 | 按 `StoreProvider` 的模式加 `SinkProvider`、`TransportProvider`，宿主只认端口（与 C-3 的配置定稿一起做） | SPI、配置 | M |
| B-9 | P2 | `spi/log/EventLog.kt:47-183`、`PostgresEventLog.kt:645,661`、`spi/snapshot/SnapshotStore.kt` | 「必须实现」和「可选优化」混在一个接口里；组合读靠向下转型，失败时静默退回；快照重建的控制面混在快照存储里 | 组合读改成显式能力（`SnapshotTailReader`），重建控制面拆成独立端口，退回时 `warn` 并计数 | SPI | M |
| B-6 | P2 | `PostgresEventLog.kt:931-1062` | 每条命令约 80 KB 分配从未剖析过；已知事件体与 `trace` 有多余的「解析一次、序列化一次」 | 先用 JFR 在 E1 上剖析，再按 ABAB 修已知的两处 | — | S～M |

### 3.5 配置与观测定稿（G4、G5）

| 编号 | 级别 | 发现 | 建议 | 面 | 代价 |
|---|---|---|---|---|---|
| C-3 | P1 | 共 240 个 `wow.*` 键。具体问题：DDL 有 5 个键、4 个枚举，`validate` 的语义各不相同；对端配置分在 `calls`、`clients` 两处，KDoc 还自相矛盾；`in-process-timeout` 与 `in-process.timeout` 只差一个点、含义不同；Mongo 的读偏好进了后端中立的 SPI；开发 profile 有两份 | 一个 `DdlMode`，各处以 `wow.ddl` 为默认、逐级覆盖；`wow.peers.<ctx>.*` 取代 `calls` 与 `clients`；删掉易混的那个键；读偏好放到 `wow.stores.<n>.mongo.*`；开发 profile 只留 `wow.dev-profiles`（决定 Q7） | 配置、ABI | M |
| B-8 | P2 | 已实测并默认开启的优化（追加队列、交接日志、成批）仍各留一个总开关；`ServiceNodeOptions` 有 21 个平铺字段 | 删掉总开关，只保留确有部署差异的容量参数；字段归入 `WaitOptions`、`ExecutorOptions`（决定 Q7） | 配置、ABI | S～M |
| C-16 | P2 | `wow.query.http.*` 与 `wow.query.in-process.*` 是两组相同的 12 个键 | `wow.query.budgets.<entry>.*` | 配置 | S |
| C-9 | P2 | 指标与错误码同名（`wow.wait.timeout`），分隔符混用，标签有的带前缀、有的不带；`:opentelemetry` 实际还承担 Micrometer 指标 | 定一条指标命名规则并用测试守住，与 M-7 的 9.x 对照表一起交付；模块改名为 `:observability`（决定 Q8） | 观测契约 | S |
| C-11 | P2 | 宿主有 31 处 `require`/`check`，以普通异常埋在 `BeanCreationException` 链里；240 个键里有 171 个没有描述 | `WowConfigurationException` 加 `FailureAnalyzer`；说明写到每个属性上，测试要求每个键都有描述 | — | S |
| C-18 | P2 | problem 的 `title` 用的是错误码的长描述 | `title` 改用短标题，长描述放 `detail` | REST 文本 | S |

### 3.6 清洁度（G9）

| 编号 | 级别 | 发现 | 建议 | 代价 |
|---|---|---|---|---|
| C-7（R3.2-2） | P2 | 主代码含编号 3,575 行，分布在 690 个文件里。其中 108 个异常和拒绝消息、错误码表描述（会成为 problem 的 `title`、写进 Manifest 和 TS 快照）、54 行配置元数据、用户的生成代码都带编号 | ① 用户可见的字符串在 GA 前清零，加一条架构规则：主代码的字符串字面量里不得出现编号；② 注释改写成理由，分模块逐步做（决定 Q9） | ① S；② L |
| A-6 | P2 | `Kernel.kt` 1,403 行，写、批、历史读、副作用作用域都在里面；`maxReplay` 默认为 `Int.MAX_VALUE` | 拆出 `StateHistory` 与 `AttemptScope`；引入 `KernelOptions`；`maxReplay` 默认给一个有限值 | M |
| B-13 | P2 | 手写 `equals`/`hashCode`/`valueFields` 共 52 处，漏一个字段编译器发现不了 | 用 Poko 一类插件生成，或用反射测试校验覆盖了全部属性 | M |
| 其余 | P2 | B-10（默认值与文档漂移，常量定义两处）、B-15（`MongoEventLog` 1,402 行）、A-17（06 §2.1 过时）、A-18（编译器 29 个文件都在一个包里）、A-19（`manifest-tools` 为一个指纹依赖整个内核）、C-13、C-14（路由的协程作用域从不取消） | 见各组报告 | S～M |

### 3.7 提醒（用户已定，只请再确认）

- **C-15**：`GET /wow/id/global` 是 9.x 遗留的公开 REST，任何已认证调用方都能取全局 ID（雪花 ID 会暴露机器号与时间）。**已定删除**（D292）。
- **C-17**：`wow.http.client-facts` 默认 `true`（D267 ⑯），会把客户端 IP 与 UA 写进不可删除的事件日志。GA 后再改默认值属于行为变更，GDPR 场景下代价不小。用户 2026-10-26：并入 Wow 10 安全性的统一讨论（T4）。

## 4. 用户的决定

AGENTS.md 规定：改模块结构、发布行为、跨模块移动职责、改配置契约，都要先问用户。2026-10-26 用户按下表的推荐全部确认，登记为 D282～D291（Q1→D282 … Q10→D291）；两项提醒中 C-15 已定删除（D292），C-17 并入安全性的统一讨论（决策台账 T4）。

| 编号 | 问题 | 选项 | 推荐与理由 |
|---|---|---|---|
| Q1 | 稳定性注解（`InternalWowApi`、`ExperimentalWowApi`、`WowSpi`）放在哪 | ① 新建零依赖模块 `:annotations`（artifact `wow-annotations`），由 `:api`、`:query-api`、`:manifest` 依赖；② 移进 `:query-api`，由 `:api` 转出 | **①**：注解是全仓库的公共底座，不该挂在查询协议名下；多一个很小的构件，代价可以忽略。A-1、A-2、A-12 都等它 |
| Q2 | `:manifest` 的 Kotlin 类型算不算契约 | ① 构造函数标内部，读者 API 公开；② 整个模块移出 `abiProjects` | **①**：OpenAPI 和用户的工具需要稳定的读者 API |
| Q3 | 运维线路记录（`api.ledger`、`api.snapshot`）放在哪；平台服务能否 opt in `InternalWowApi`（D216 ③） | ① 移到运行时并标内部，ops 按 JSON 自己定义读者；② 新建 `wow-ops-contract`；③ 允许平台服务 opt in | **①**：ops 是「只用公开面证明框架」的应用，它应该和第三方运维工具一样按 JSON 契约读；不必为它开 opt in 的口子 |
| Q4 | X-6 的命名分叉 | 从「转 10.x」拉回 GA 前 | **拉回**：GA 前做只要 S，而且没有存量数据 |
| Q5 | 线路语法（请求头、`WaitWire`）放在哪 | ① `:api` 新增 `wire` 包；② 新建 `:wire` 模块 | **①**：都是纯数据加解析，属于对外线路契约，正好在 D66 定义的 `:api` 范围内；不再加模块 |
| Q6 | 发布元数据：已发布库带哪个平台 | 精简平台（Jackson、协程、kotlin-logging 等）；Spring Boot BOM 只给 `wow-spring-boot` | **按此做**；先用一个只依赖 `wow-api` 的小项目跑 `dependencyInsight`，确认影响 |
| Q7 | 配置面定稿的范围（C-3、B-8、C-16、C-12、B-5 的配置部分） | 一次定稿；先写一张「概念 → 唯一键 → 唯一类型」表，与你过一遍再改 | **一次定稿**：分几次改，配置契约就会被反复破坏 |
| Q8 | 观测名规则与 `:opentelemetry` 改名 | 指标统一 `wow.<域>.<名词>`，标签不带前缀、用下划线（或直接采用 OTel 语义约定）；模块改名 `:observability` | **采用 OTel 语义约定的形式**：业界工具默认就认它；改名一起做 |
| Q9 | R3.2-2 编号清理的方式 | ① 用户可见的文字现在清零并加规则，注释分模块逐步改写；② 一次性全部改写 | **①**：用户可见部分是 GA 门槛，注释改写是 L，按模块分批不阻塞别的工作 |
| Q10 | 存储与 SPI 改动（B-3、B-4、B-9） | 在 RC 前做，格式号保持为 1 | **RC 前做**：RC 后再改就要升格式号并带迁移 |

## 5. 分批方案（讨论定稿后派给子会话）

依赖顺序：批 0 不需要任何决定，可以马上开始。批 1 要等 Q1。批 3 的 B-3 要在 B-15 之前做。批 4 要等 Q7、Q8。批 2 与批 3 改的文件互不重叠，可以并行。并行的会话保持 2～3 个。

| 批 | 内容 | 前提 | 估计 |
|---|---|---|---|
| **0 活性与契约保护** | B-1（车道卡死，带属性测试）、B-2、C-8、B-10、C-13、C-14、C-7 ①（用户可见的编号清零并加规则）、C-18、C-11 | 无 | 2～3 天 |
| **1 冻结面收口** | Q1 的注解模块；A-1、A-2、A-3、A-7（含构建检查）、A-8、A-9、A-11、A-13、C-6、B-7 的 getter、B-12、A-10；`:architecture` 加一条「公开 `data class` 只能在白名单里」的规则 | Q1、Q2 | 1～1.5 周 |
| **2 语义下沉与依赖** | C-1、C-2、C-5、C-4、C-12、C-10、A-4 | Q3、Q5、Q6 | 1.5～2 周 |
| **3 格式、SPI 与伸缩** | B-3、B-4、B-9、B-5 的 SPI 部分、A-5；之后做 B-15 | Q4、Q10 | 1～1.5 周 |
| **4 配置与观测定稿** | C-3、B-8、C-16、B-5 的配置部分、C-9（含与 M-7 合并的对照表）、B-11 | Q7、Q8 | 1 周 |
| **5 整洁与性能** | A-6、A-18、A-19、C-19、B-13、R3.2-2 的注释改写（按模块）、B-6（JFR 剖析后再 ABAB） | 批 1～3 合并之后（避免冲突） | 持续进行，不阻塞 rc |

**对 rc 的影响**：批 0～4 改变冻结面、配置、存储格式或 SPI，应在 rc 之前完成。批 5 不改变任何契约，可以在 rc 之后继续。按每次 2～3 个并行会话估计，批 0～4 约需 4～6 周。rc 的时间仍由你决定，本文只说明哪些事必须排在 rc 之前。

## 6. 每批的完成标准

- 每条发现都有用例或规则守住：活性问题配属性测试，边界问题配 `:architecture` 的正向规则，命名问题配命名测试。
- 转储的 `-` 行在 PR 描述里逐条说明（GA 前转储只是审查工具；PR 打上 `breaking-change` 标签）。
- 用户可见的变化（配置键、REST 文本、指标名）同步更新文档站中英文页面与 9.x 对照表。
- 性能相关的改动按「基线是上一轮」做 ABAB。

## 7. 第二阶段（建议，待定）

- **TypeScript 包**：`wow-client`、`wow-react`、`wow-view-engine`、`wow-view-store`、`wow-generator`。用同样的标准审查：公开面（API 报告、surface 列表）、包间依赖、生成器与运行时的绑定、可访问性（WCAG 2.5.3 正在做）。
- **平台服务**：ops、view-store、analytics catalog。重点是它们是否只用公开面（与 Q3 相关）。
- **文档站与 Skills**：是否与本轮的配置、指标定稿一致。

建议在批 0、批 1 合并后开始，因为那时 JVM 的冻结面已经定了。

## 8. 不要改的地方

- 写路径：去重在重试之外只做一次；追加之后 `NonCancellable`；`AppendOutcome.Unknown` 不补偿；`StateCache` 的取出与放回让可变状态在失败时作废。
- `Decision` 是 sealed 值，拒绝是值而不是异常；`DecideScope` 把补偿、预留、定时放在同一个按尝试的作用域里。
- 追加队列（每分区一个在途组、同一聚合不可拆分、跨分区合并、可撤回）；转发结局判定（D223）；停放与系统性故障分开；HRW 加租约仲裁（D270）。
- 后端经 `StoreProvider` 接入，公开面只有 13～17 行；`:runtime` 不依赖宿主；`:reactor` 靠依赖隔离做适配；生成代码加 `ServiceLoader`，没有反射扫描。
- 错误码表有唯一来源，并由双向测试守住；problem+json 一致；未知配置键拒绝启动；启动拒绝时的消息能告诉用户怎么修。
- 读路径统一经过 `ReadFacade`；公开端口与内部端口复用同一组处理器。
