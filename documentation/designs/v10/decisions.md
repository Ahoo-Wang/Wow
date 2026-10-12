# V10 决策台账

日期：2026-10-11。状态：持续维护。

规则：

- 每个 V10 决定只在这里登记一次，专题文档只引用编号。
- 状态只有四种：**已定**（写明日期与决定人）、**待定**（有推荐，等拍板）、**待证据**（等某个假设的 spike 结论）、**已撤销**（写明被哪个决定取代）。
- 「最晚」是必须拍板的里程碑：到了还没定，该里程碑不开始。
- 拍板后，改状态与日期，再更新受影响的专题文档；推荐被推翻时，把推翻的理由写进「推荐」一栏。

## 1. 待决（TODO）

按「最晚」排序。

| # | 问题 | 推荐 | 状态 | 最晚 | 文档 |
|---|---|---|---|---|---|
| D6 | 命令路由 | 默认 Partitioned（经集群通道转发到分区属主）；单节点或降级用 Local；需要持久摄入的异步命令用 Kafka | 待证据 H2 | M4 | [02](02-write-path.md) |

## 2. 待验证的假设

| # | 假设 | spike | 放弃条件与退路 | 里程碑 | 影响的决定 |
|---|---|---|---|---|---|
| H1 | 分区序号的分配能满足吞吐 | PostgreSQL 两种分配方式与 MongoDB 事务；单分区与 64 分区的吞吐、p99、尾随延迟；**分别在属主串行（Partitioned）与多节点并发追加（Local、交接期）下测**，含分组提交里单条冲突的隔离；故障注入 | 单分区不足：加大 P；MongoDB 事务过贵：分组提交（同分区多次追加合并为一个事务），MongoDB 不降级（D11） | M1 | D10、D11、D27、D28；**已验证**：PostgreSQL（`wow-benchmarks/results/reports/v10-h1-postgres-partition-sequence.md`）；MongoDB（2026-10-12，`wow-benchmarks/results/reports/v10-h1-mongo-partition-sequence.md`），F1 的塌缩被测到，属主串行加分组提交可用 |
| H2 | 亲和路由值得转发的一跳 | 3 节点随机负载均衡，Local 与 Partitioned 的缓存命中率、每命令读次数、冲突率、p99 | Partitioned 的 p99 不优于 Local：默认改为 Local，并按 H1 的并发追加数据重新评估 MongoDB 后端（F1） | M1 | D6、D11 |
| H3 | 协程内核不比 9.3 的 Reactor 内核慢 | 同一 JMH 生产链路基准的吞吐与 `gc.alloc.rate.norm`，同步与 `suspend` 处理函数分别测 | 下降超过噪声：优化热点后才进入 M2；方向已定，不回退 | M1 | D9；**已验证（2026-10-11）**：同机同 JVM 下 V10 内核约 3.5 µs、11.2 KB/次，9.3 约 26.6 µs、21.8 KB/次；V10 的成本主要是序列化与指纹（`wow-benchmarks/results/reports/v10-h3-kernel-comparison.md`） |
| H4 | KSP 覆盖注解模型的全部语义 | example、compensation、view-store 与 transfer（Java 源码）的生成模型与 9.x 运行时解析结果比对 | 有缺口：补编译器，不回到反射 | M1 | D7、D8、D37；**已验证（2026-10-12）**：5 个领域 9 个聚合 38 个命令处理函数，27 个源码不改即可绑定、生成代码在原模块编译通过；其余 11 个只因签名用了 9.x 框架类型，有 V10 对应写法（D49），由迁移 Skill 改写（`wow-benchmarks/results/reports/v10-h4-ksp-coverage.md`） |
| H5 | Skill 能把真实的 9.x 领域升级到 V10 | 5 个 9.x 领域（example、transfer、compensation、view-store、dataset-registry）作为 eval，各跑多次 | 门禁通不过：补参考规则与示例；反复失败的改写改为「必须问用户」 | M8 | D3、D20 |
| H6 | 存储租约能支撑成员与再平衡 | 在 PostgreSQL 与 MongoDB 上实现分区租约：节点上下线时属主收敛时间、再平衡迁移的分区数、交接期间重复处理的比例 | 收敛过慢：缩短租约并加主动让渡；仍不足再评估外部协调器（etcd） | M1 | D6、D24、D45 |


## 3. 已定

| # | 决定 | 日期与决定人 | 文档 |
|---|---|---|---|
| D1 | V10 以理想目标架构为目标，不受 9.x 兼容约束；源码与数据由迁移层一次性升级，V10 内零兼容代码 | 2026-10-11，用户 | [00](00-architecture.md)、[07](07-migration.md) |
| D2 | 在长期分支 `v10` 上开发，全部完成后一次合入 main；main 继续发布 9.x | 2026-10-11，用户 | [roadmap](roadmap.md) |
| D3 | 源码迁移用 Skill（扩展 `skills/wow-migrate`），不引入改写引擎；数据迁移用工具 | 2026-10-11，用户 | [07](07-migration.md) |
| D4 | 快照是默认读模型：每版本写一次的内置订阅，带 schema 指纹、可重建，按聚合可关闭，保留 `SNAPSHOT` 阶段 | 2026-10-11，用户 | [03](03-log-and-snapshot.md) |
| D5 | 日志就是总线：上下文内订阅带检查点读日志，删除领域事件总线与状态事件总线；**必须支持 MongoDB** | 2026-10-11，用户 | [04](04-subscriptions.md) |
| D7 | 领域模型：五类函数；**状态可变为默认**，不可变也支持，原子性靠失败时丢弃重载、版本视图靠提交时序列化一次，由内核保证；删除 `@OnError` | 2026-10-11，用户 | [01](01-programming-model.md) |
| D8 | Java 支持：Java 照样写注解，由 KSP 分析 Java 源文件生成同一个模型（构建需加 Kotlin Gradle 插件与 KSP）；不想引入 Kotlin 插件的项目用 Builder 写 Decider。不提供运行时反射 | 2026-10-11，用户 | [01](01-programming-model.md) |
| D9 | 协程原生，覆盖 API、SPI 与内核；用户编程模型可以用 Reactor，只由 `wow-reactor` 做适配，内核与 SPI 不感知。H3 是实现的性能门禁，不改变方向 | 2026-10-11，用户 | [01](01-programming-model.md)、[06](06-modules.md) |
| D10 | PostgreSQL 是首选日志后端：分区序号由一条自动提交的语句分配（CTE 内版本检查加计数行加锁，失败整体回滚，不留空洞）；H1：64 分区约 3 万次追加/秒，为直接插入的约 70% | 2026-10-11，H1 证据 | [03](03-log-and-snapshot.md) |
| D11 | MongoDB 是一等日志后端（D5 的条件）：多文档事务分配分区序号，吞吐不足用分组提交与加大 P，不降级。H1：分区属主串行并分组提交时约 4.4～5.6 千次追加/秒，为直接插入的 45%～57%，约为 PostgreSQL 的六分之一；多写者无串行时单分区塌缩到约 100 次/秒、p99 1.4 秒，所以 MongoDB 后端要求 Partitioned 路由（D6） | 2026-10-11，用户；2026-10-12，H1 证据 | [03](03-log-and-snapshot.md) |
| D12 | Redis 事件存储：V10 不提供 Redis 日志后端；需要的社区实现者按日志合同与 TCK 自行实现 | 2026-10-11，用户 | [03](03-log-and-snapshot.md) |
| D13 | Elasticsearch 的定位：不作为日志后端；作为快照、查询与全文检索后端 | 2026-10-11，用户授权按推荐 | [03](03-log-and-snapshot.md) |
| D14 | 失败处理与补偿：停放聚合 + 失败台账；台账写在出错服务自己的存储里，wow-ops 经 Kafka 汇总并把重试、跳过作为命令发回；compensation 改为 wow-ops | 2026-10-11，用户 | [04](04-subscriptions.md) |
| D15 | 等待阶段：ACCEPTED / COMMITTED（默认）/ SNAPSHOT / HANDLED(订阅)；ACCEPTED 只在 Kafka 路由下表示持久 | 2026-10-11，用户授权按推荐；阶段集合由 D61 修订 | [04](04-subscriptions.md) |
| D16 | REST 形状：身份来自主体，删除租户与所有者的路由变体；problem+json；`Prefer` 头表达等待 | 2026-10-11，用户 | [05](05-read-and-contract.md) |
| D17 | 契约来源：Manifest 为唯一来源；生成器、OpenAPI、view-engine 都读它 | 2026-10-11，用户授权按推荐 | [05](05-read-and-contract.md) |
| D18 | wow-bi：由 ClickHouse Sink 取代，删除 | 2026-10-11，用户 | [04](04-subscriptions.md) |
| D19 | 9.x 维护期限：V10 GA 后 `release-9.x` 只收安全补丁，为期 12 个月 | 2026-10-11，用户 | [roadmap](roadmap.md) |
| D20 | TS 升级方式：`wow-generator` 重新生成 + `wow-client` Skill 改写应用代码 | 2026-10-11，用户授权按推荐 | [07](07-migration.md) |
| D21 | 迁移层的寿命：只支持 9.x 最后一个次版本 → 10.0；10.x 维护，11.0 删除 | 2026-10-11，用户授权按推荐 | [07](07-migration.md) |
| D22 | V10 之后的格式演进：框架格式（信封、日志结构、快照元数据）带版本，运行时只读当前版本，**不兼容的**变化只在下一个主版本的迁移层转换（新增带默认值的可选字段属于同一版本内的加法，R5-1）；领域事件用 `revision` 与用户写的升级函数在读取时升级，属于用户模型 | 2026-10-11，用户 | [01](01-programming-model.md)、[07](07-migration.md) |
| D23 | 跨上下文集成：日志是上下文私有的；跨上下文走 Kafka Sink 发布的 `StateEvent`，下游订阅语义不变 | 2026-10-11，用户授权按推荐 | [04](04-subscriptions.md) |
| D24 | 集群通道：服务内节点间唯一内部通道（转发、信号、推送）；成员与属主来自存储租约；不做认证（D35） | 2026-10-11，用户授权按推荐 | [02](02-write-path.md) |
| D25 | 等待的传输元数据：`WaitContext` 只在等待超出 `COMMITTED` 时写入日志条目、跨上下文命令与 Kafka 信封的传输段；不属于事实，领域代码与查询不可见 | 2026-10-11，用户授权按推荐 | [03](03-log-and-snapshot.md) |
| D26 | 集群通道的协议：复用宿主的 HTTP/2 栈与 JSON；有基准证据再换二进制协议 | 2026-10-11，用户授权按推荐 | [02](02-write-path.md) |
| D27 | PostgreSQL 驱动：JDBC 加虚拟线程（JDK 21），不用 R2DBC；H1 表明它已触及数据库自身的上限，响应式驱动没有收益（推翻原推荐） | 2026-10-11，H1 证据 | [06](06-modules.md) |
| D28 | 默认分区数 P = 64：单分区上限约为每秒一次提交延迟的倒数（本机约 5 千次/秒），64 分区已达数据库上限 | 2026-10-11，H1 证据 | [03](03-log-and-snapshot.md) |
| D29 | JVM 基线：最低 21，CI 同时在 25 上测试 | 2026-10-11，用户 | [06](06-modules.md) |
| D30 | HTTP 宿主：Spring WebFlux 的协程路由（`coRouter`）；运行时本身不依赖它 | 2026-10-11，用户授权按推荐 | [06](06-modules.md) |
| D31 | Kafka 客户端：直接基于 `kafka-clients`，协程驱动拉取循环（Reactor Kafka 已停止维护） | 2026-10-11，用户授权按推荐 | [06](06-modules.md) |
| D32 | 切换后的回滚：切换后 V10 未写入：直接切回 9.x；已写入：提供 V10 → 9.x 的反向导出，只覆盖迁移前已存在的事件类型 | 2026-10-11，用户 | [07](07-migration.md) |
| D33 | 跨上下文的命令投递：一律写入目标上下文的命令主题；发送方等到持久 `ACCEPTED` 即完成；失败记入目标上下文的台账。集群通道不跨服务 | 2026-10-11，用户授权按推荐 | [02](02-write-path.md) |
| D34 | 跨上下文等待：**保留**（重要特性，用户 2026-10-11）：等待目标是跨上下文的链；信号直接发往发起节点（D65 修订了原先的按服务寻址与中继）；链上任一跳失败立即通知（[04](04-subscriptions.md) §6.3） | 2026-10-11，用户授权按推荐 | [04](04-subscriptions.md) |
| D35 | 部署前提与跨服务信号：所有服务部署在同一个集群内、彼此可直接调用；跨服务等待信号只用 HTTP 调用发起服务的信号入口，不提供 Kafka 替代；集群内部流量受信任，信号入口与集群通道不做认证 | 2026-10-11，用户 | [00](00-architecture.md)、[04](04-subscriptions.md) |
| D36 | 处理函数的调用形态与快速路径：同步、`suspend`、Reactor 三种形态，KSP 编译期选定调用；同步函数内联在工作线程上执行，阻塞的须标 `@Blocking` 并派发到阻塞调度器（JDK 21 下为虚拟线程）；内核在缓存命中且处理函数同步时只在追加处挂起 | 2026-10-11，用户 | [01](01-programming-model.md) |
| D37 | 注解模型由 KSP 在编译期生成绑定代码与 Manifest，运行时不扫描、不反射，也不为任何语言保留反射退路。收益：错误提前到编译期（重复处理函数、无法解析的参数）、快速路径是直接调用、启动快且可 AOT、绑定与 Manifest 同源；代价：构建依赖 KSP、需要调试生成代码、编译器须覆盖全部语义（H4） | 2026-10-11，用户 | [01](01-programming-model.md) |
| D38 | 跨聚合唯一性（9.x `PrepareKey`）：保留：`@WowSpi` 端口，由日志后端实现；在 Decide 中经 `DecideScope` 预留，拒绝或追加失败时内核回滚；释放与转移由 Saga 完成 | 2026-10-11，用户 | [01](01-programming-model.md) |
| D39 | after 钩子（9.x `@AfterCommand`）保留：外部用户在用。用途是在 Decide 之后追加事件：命令函数之后按 include/exclude 匹配、按声明顺序执行，看到与命令函数相同的（未演化）状态；事件拼在命令事件之后组成同一条事件流，一起演化、一次追加；调用形态同 Decide（D36），可以做 I/O；任一失败则命令失败、不写入 | 2026-10-11，用户 | [01](01-programming-model.md) |
| D40 | 历史状态（按版本、按时间、逐事件追溯）：保留：最新读快照，历史从日志重放；带超时与版本数保护 | 2026-10-11，用户授权按推荐 | [03](03-log-and-snapshot.md) |
| D41 | 仓库目录结构：按层分目录（`api/`、`compiler/`、`core/`、`query/`、`backends/`、`edge/`、`host/`、`test/`、`migration/`、`bom/`、`platform/`），Gradle 项目名保持 `:wow-xxx`；新模块在 M0 按新结构建立，main 上仍活跃的平台目录在 M9 合入前一次搬迁 | 2026-10-11，用户 | [06](06-modules.md) |
| D42 | 后端模块的划分：一种技术一个模块（`wow-postgres`、`wow-mongo`、`wow-elasticsearch`、`wow-clickhouse`、`wow-kafka`），各自实现它能实现的全部端口；不按日志、查询等角色拆 | 2026-10-11，用户 | [06](06-modules.md) |
| D43 | 内核的拆分：`wow-spi`（端口，供后端实现者与 TCK）、`wow-kernel`（模型与提交循环，供测试 DSL 与迁移工具）、`wow-runtime`（集群、订阅、等待、生命周期）三个模块 | 2026-10-11，用户 | [06](06-modules.md) |
| D44 | Spring 集成的形态：一个按类路径生效的自动配置模块 `wow-spring-boot`；后端由用户直接加技术模块，不用 Gradle 特性变体（Maven 用户同样适用） | 2026-10-11，用户 | [06](06-modules.md) |
| D45 | 租约交接与 fencing：不做 fencing：命令靠版本唯一键，订阅副作用靠确定性 ID 幂等，检查点只前进，台账写入幂等（KaeX mvp 做过 fencing 后删除，理由相同） | 2026-10-11，用户授权按推荐 | [04](04-subscriptions.md) |
| D46 | 请求幂等：去重先于决定、在冲突重试之外只做一次：有 I/O 的 decide 每次走 L2（唯一索引存在性检查）；纯计算的 decide 可用 L1 轮转布隆过滤器跳过 L2，漏判由追加唯一键兜住（对抗验证 F3：布隆的「无假阴性」跨重启、跨属主、跨轮转不成立）；记录命令指纹，同一请求 ID 携带不同命令时拒绝 | 2026-10-11，用户 | [02](02-write-path.md) |
| D47 | 跨上下文命令被拒绝的回执：目标上下文把拒绝作为 `CommandRejected` 写入发起上下文的回执主题，**之后**才确认命令消息，崩溃重投保证至少一次，回执按命令 ID 去重；发起方 Saga 可订阅并补偿；技术故障才进目标的失败台账；拒绝不写日志 | 2026-10-11，用户授权按推荐 | [04](04-subscriptions.md) |
| D49 | 处理函数取得请求事实与依赖：`Command` 带准入得到的所有者、空间与请求头，处理函数以 `Command<X>` 为第一个参数或经 `DecideScope.command` 读取；领域服务参数成为生成工厂的参数，构造期绑定；附加结果经 `DecideScope.putResult` 返回调用方、不持久化。依据 H4：5 个 9.x 领域里 11 个命令处理函数读命令元数据或写附加结果 | 2026-10-12，H4 证据 | [01](01-programming-model.md) |
| D50 | 预留的确认不只靠 `afterAppend`：本次尝试做过的预留（命名空间，键）随事件流写入日志条目的传输元数据；内置订阅 `reservation` 按条目确认（幂等），`afterAppend` 只是低延迟的快路径。确认失败或确认前崩溃，订阅补上；提交过的事实不会因为待确认预留过期而丢键 | 2026-10-12，用户 | [01](01-programming-model.md) |
| D51 | 附加结果（`DecideScope.putResult`）只随本次提交返回，不持久化；同一请求的重复提交得到「已提交」而不含附加结果。需要可重取的结果应写进事件 | 2026-10-12，用户 | [01](01-programming-model.md) |
| D52 | MongoDB 的定位：一等后端（D11 不变），但吞吐约为 PostgreSQL 的六分之一，且必须由分区属主串行追加（Partitioned，D6）；文档明确高吞吐场景推荐 PostgreSQL | 2026-10-12，用户 | [03](03-log-and-snapshot.md) |
| D53 | 租约、成员与预留的过期按节点时钟计算，不取数据库时钟：测试可控；部署要求节点间时钟偏差远小于 TTL（NTP）。偏差的后果只是交接期的重复处理（D45 已容忍），不影响正确性 | 2026-10-12，用户 | [04](04-subscriptions.md) |
| D55 | 请求 ID 复用的识别：按性能取舍，只比命令类型名（由模型命名，不用 Java 类名），不给命令体算哈希；事件流记 `commandType` 取代 `fingerprint`。同类型不同内容的复用被当作重试，是调用方契约（一次业务请求一个请求 ID） | 2026-10-12，用户 | [08](08-data-protocols.md) |
| D56 | 作用域：`wow-api` 的值类型 `Scope(ownerId, spaceId)`，命令、事件流、快照、查询授权共用；事件流的 `scope` 是提交后聚合的归属，由内核随状态维护、只由生命周期命令改变；事件流另带 `causation`（根命令、直接上游）与准入白名单内的 `headers` | 2026-10-12，用户 | [08](08-data-protocols.md) |
| D57 | 事件标识由（聚合引用，版本，事件序号）推导：`ref@version#index`，不另存 ID | 2026-10-12，用户 | [08](08-data-protocols.md) |
| D58 | Kafka 信封：key = 聚合引用；value = `{format, kind, id, type, payload, transport}`；发布的是 `StateEvent`（默认总附状态，单个 Sink 可按聚合类型关闭）；载荷是嵌入的 JSON 对象，不是字符串；下游按 id 去重 | 2026-10-12，用户 | [08](08-data-protocols.md) |
| D61 | 等待阶段收敛为 `ACCEPTED`、`COMMITTED`、`HANDLED(订阅)` 三个；`SNAPSHOT` 是 `HANDLED(snapshot)` 的简写；修订 D15 | 2026-10-12，用户 | [04](04-subscriptions.md) |
| D62 | 等待链：多跳，只有 Saga 跳之后能接跳（作用于它派生的命令），最多 4 跳，准入按 Manifest 校验；Saga 信号带派生命令 ID，全部到达才完成，派生 0 条即完成；早到信号按 `waitId` 缓存；任一跳失败立即失败 | 2026-10-12，用户 | [04](04-subscriptions.md) |
| D63 | 重复请求的等待：本上下文订阅按检查点与台账补查；跨上下文的跳无从补查，结果停在 `COMMITTED` 并标 `duplicate` | 2026-10-12，用户 | [04](04-subscriptions.md) |
| D64 | 等待超时：默认 10 秒，服务端上限默认 60 秒；绝对截止时间随 `WaitContext` 下发，过期后下游不发信号；超时结果带已到达的最后一跳 | 2026-10-12，用户 | [04](04-subscriptions.md) |
| D65 | 等待信号直接发往发起节点（`WaitContext.origin` 为实例内部地址），不经负载均衡与集群通道中继；送达失败即丢弃；修订 D35 中「按服务寻址、节点内中继」的部分 | 2026-10-12，用户 | [04](04-subscriptions.md) |
| D59 | 格式版本：日志、快照按表记录格式版本（schema 元数据），线上结构每条带 `format`；新增带默认值的可选字段不升版本；主版本内新字段先在一个次版本学会读、下一个次版本才写（D48 的落地规则）；不兼容的变化只在下一个主版本的迁移层处理（D22） | 2026-10-12，用户 | [08](08-data-protocols.md) |
| D60 | 对外命令结果：一个 `CommandResult`（status、hop、function、version、duplicate、error 含 violations、results）用于 HTTP、SSE、等待信号与回执；保留 9.x 的 `function`（SSE 中标明处理者）与字段级校验错误（改名 `violations`）；拒绝与失败走 problem+json 并带同样字段 | 2026-10-12，用户 | [08](08-data-protocols.md) |
| D54 | 快照只按版本守卫写入；状态 schema 变化后的重建一律写入新的快照存储（蓝绿），不做同版本原地覆盖 | 2026-10-12，用户 | [03](03-log-and-snapshot.md) |
| D48 | 主版本内的滚动升级：同一主版本内相邻次版本必须能混部：集群通道、日志条目、租约、等待信号、Kafka 信封都向前兼容一个次版本；10.0 GA 后重新启用 `mixed-version` 门禁；跨主版本才用蓝绿切换 | 2026-10-11，用户 | [02](02-write-path.md) |
