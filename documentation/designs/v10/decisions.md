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

## 2. 待验证的假设

| # | 假设 | spike | 放弃条件与退路 | 里程碑 | 影响的决定 |
|---|---|---|---|---|---|
| H1 | 分区序号的分配能满足吞吐 | PostgreSQL 两种分配方式与 MongoDB 事务；单分区与 64 分区的吞吐、p99、尾随延迟；**分别在属主串行（Partitioned）与多节点并发追加（Local、交接期）下测**，含分组提交里单条冲突的隔离；故障注入 | 单分区不足：加大 P；MongoDB 事务过贵：分组提交（同分区多次追加合并为一个事务），MongoDB 不降级（D11） | M1 | D10、D11、D27、D28；**已验证**：PostgreSQL（`wow-benchmarks/results/reports/v10-h1-postgres-partition-sequence.md`）；MongoDB（2026-10-12，`wow-benchmarks/results/reports/v10-h1-mongo-partition-sequence.md`），F1 的塌缩被测到，属主串行加分组提交可用 |
| H2 | 亲和路由值得转发的一跳（PostgreSQL；MongoDB 已由 H1 定为必须 Partitioned，D52） | **阶段 1**：进程内 3 节点（各自的 Executor 与状态缓存），共享真实 PostgreSQL，转发注入 0.3～1 ms 延迟并真实序列化命令；每次提交后同步写快照代替 4.6。对照组：Local、Partitioned、入口按聚合哈希（无转发）。自变量：访问分布（均匀 10 万 / Zipf s=1.1 / 10 个极热）、同聚合串行或并发、开环到达率 50% 与 80% 饱和、3 与 5 节点、小状态短尾与大状态长尾。指标：吞吐、p50/p99/p999、命中率、每命令读次数、冲突与 `Contended` 比例、每命令 CPU。**阶段 2**（4.2 之后）：3 个 JVM 真实转发复测 p99，并测属主被杀时的不可用窗口 | 预先登记：Zipf 80% 负载下 Partitioned 的 p99 不差于 Local 且读次数明显更低、均匀冷负载下 p99 代价不超过一次转发 RTT（约 +1 ms），则默认 Partitioned；热点下 Partitioned 的 p99 更差则默认 Local（MongoDB 仍强制 Partitioned）。阶段 1 定 D6，阶段 2 只做确认；没有 9.x 生产数据，分布参数用上述假设 | M1 | D6、D11；**阶段 1 已验证（2026-10-12）**，D6 定为 Partitioned；完整报告随 `claude/v10-spike-h2` 的 PR 入库 |
| H3 | 协程内核不比 9.3 的 Reactor 内核慢 | 同一 JMH 生产链路基准的吞吐与 `gc.alloc.rate.norm`，同步与 `suspend` 处理函数分别测 | 下降超过噪声：优化热点后才进入 M2；方向已定，不回退 | M1 | D9；**已验证（2026-10-11）**：同机同 JVM 下 V10 内核约 3.5 µs、11.2 KB/次，9.3 约 26.6 µs、21.8 KB/次；V10 的成本主要是序列化与指纹（`wow-benchmarks/results/reports/v10-h3-kernel-comparison.md`） |
| H4 | KSP 覆盖注解模型的全部语义 | example、compensation、view-store 与 transfer（Java 源码）的生成模型与 9.x 运行时解析结果比对 | 有缺口：补编译器，不回到反射 | M1 | D7、D8、D37；**已验证（2026-10-12）**：5 个领域 9 个聚合 38 个命令处理函数，27 个源码不改即可绑定、生成代码在原模块编译通过；其余 11 个只因签名用了 9.x 框架类型，有 V10 对应写法（D49），由迁移 Skill 改写（`wow-benchmarks/results/reports/v10-h4-ksp-coverage.md`） |
| H5 | Skill 能把真实的 9.x 领域升级到 V10 | 5 个 9.x 领域（example、transfer、compensation、view-store、dataset-registry）作为 eval，各跑多次 | 门禁通不过：补参考规则与示例；反复失败的改写改为「必须问用户」 | M8 | D3、D20 |
| H6 | 存储租约能支撑成员与再平衡（**已验证**，[报告](../../../wow-benchmarks/results/reports/v10-h6-leases.md)：默认参数下加入与优雅退出约 2 秒收敛、无重复，被杀约 TTL + 1 秒、窗口内重复 0.7～1.8%；迁移分区数恰为 HRW 份额；放弃条件未触发，不需缩短 TTL 或外部协调器。实测发现串行续约与缺少 MongoDB 属主追加队列两处缺陷，另行修复） | 在 PostgreSQL 与 MongoDB 上实现分区租约：节点上下线时属主收敛时间、再平衡迁移的分区数、交接期间重复处理的比例 | 收敛过慢：缩短租约并加主动让渡；仍不足再评估外部协调器（etcd） | M1 | D6、D24、D45 |


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
| D15 | 等待阶段：ACCEPTED / COMMITTED / SNAPSHOT（默认，D69） / HANDLED(订阅)；ACCEPTED 只在 Kafka 路由下表示持久 | 2026-10-11，用户授权按推荐；阶段集合由 D61 修订 | [04](04-subscriptions.md) |
| D16 | REST 形状：身份来自主体，删除租户与所有者的路由变体；problem+json；`Prefer` 头表达等待 | 2026-10-11，用户；等待请求头由 D98 修订为 `Wow-Wait` | [05](05-read-and-contract.md) |
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
| D6 | 命令路由：默认 Partitioned（经集群通道转发到分区属主）；单节点、交接与属主不可达时降级 Local（D71）；需要持久摄入的异步命令用 Kafka；入口能按聚合 ID 做一致性哈希时推荐配置，省掉转发一跳。依据 H2 阶段 1（预先登记的标准全部满足）：Zipf 热点 80% 负载 p99 Local 8.95 ms、Partitioned 3.20 ms，每命令读 2.44 对 1.38 次，Local 每轮 94～141 次 `Contended`、Partitioned 为 0；均匀冷负载 50% 时 p99 代价 +0.6 ms（≤ 一次转发 RTT），80% 时 Partitioned 更低；补充的大状态、5 节点与 8 个热点格子都没有相反证据（[报告](../../../wow-benchmarks/results/reports/v10-h2-routing.md)）。前提：PostgreSQL 跑在 Docker Desktop 上，默认配置的 fsync 停顿淹没了 1 ms 级的差异，最终一轮用了 `synchronous_commit=off` 与更大的 WAL、缓冲配置；两种路由共用同一个库、比较的是相对差异，结论不受影响，但追加比持久化的生产库便宜。阶段 2（真实 3 JVM、持久化配置，4.2b 之后）只做确认 | 2026-10-12，H2 证据 | [02](02-write-path.md) |
| D49 | 处理函数取得请求事实与依赖：`Command` 带准入得到的所有者、空间与请求头，处理函数以 `Command<X>` 为第一个参数或经 `DecideScope.command` 读取；领域服务参数成为生成工厂的参数，构造期绑定；附加结果经 `DecideScope.putResult` 返回调用方、不持久化。依据 H4：5 个 9.x 领域里 11 个命令处理函数读命令元数据或写附加结果 | 2026-10-12，H4 证据 | [01](01-programming-model.md) |
| D50 | 预留的确认不只靠 `afterAppend`：本次尝试做过的预留（命名空间，键）随事件流写入日志条目的传输元数据；内置订阅 `reservation` 按条目确认（幂等），`afterAppend` 只是低延迟的快路径。确认失败或确认前崩溃，订阅补上；提交过的事实不会因为待确认预留过期而丢键 | 2026-10-12，用户 | [01](01-programming-model.md) |
| D51 | 附加结果（`DecideScope.putResult`）只随本次提交返回，不持久化；同一请求的重复提交得到「已提交」而不含附加结果。需要可重取的结果应写进事件 | 2026-10-12，用户 | [01](01-programming-model.md) |
| D52 | MongoDB 的定位：一等后端（D11 不变），但吞吐约为 PostgreSQL 的六分之一，且必须由分区属主串行追加（Partitioned，D6）；文档明确高吞吐场景推荐 PostgreSQL | 2026-10-12，用户 | [03](03-log-and-snapshot.md) |
| D53 | 租约、成员与预留的过期按节点时钟计算，不取数据库时钟：测试可控；部署要求节点间时钟偏差远小于 TTL（NTP）。偏差的后果只是交接期的重复处理（D45 已容忍），不影响正确性 | 2026-10-12，用户 | [04](04-subscriptions.md) |
| D55 | 请求 ID 复用的识别：按性能取舍，只比命令类型名（由模型命名，不用 Java 类名），不给命令体算哈希；事件流记 `commandType` 取代 `fingerprint`。同类型不同内容的复用被当作重试，是调用方契约（一次业务请求一个请求 ID） | 2026-10-12，用户 | [08](08-data-protocols.md) |
| D56 | 作用域：`wow-api` 的值类型 `Scope(ownerId, spaceId)`，命令、事件流、快照、查询授权共用；事件流的 `scope` 是提交后聚合的归属，由内核随状态维护、只由生命周期命令改变；事件流另带 `causation`（根命令、直接上游）与准入白名单内的 `headers` | 2026-10-12，用户 | [08](08-data-protocols.md) |
| D57 | 事件标识由（聚合引用，版本，事件序号）推导：`ref@version#index`，不另存 ID | 2026-10-12，用户 | [08](08-data-protocols.md) |
| D58 | Kafka 信封：key = 聚合引用；value = `{format, kind, id, type, payload, transport}`；发布的是 `StateEvent`（默认总附状态，单个 Sink 可按聚合类型关闭）；载荷是嵌入的 JSON 对象，不是字符串；下游按 id 去重 | 2026-10-12，用户 | [08](08-data-protocols.md) |
| D73 | Manifest 的 schema 按 Jackson 的实际行为生成：KSP 产出结构与类型清单，构建期 Gradle 任务用 Jackson 序列化器内省出 JSON Schema 2020-12；状态 schema 指纹也由它计算；运行时不反射 | 2026-10-12，用户 | [05](05-read-and-contract.md) |
| D74 | Manifest 组装与发布：模块片段在 `META-INF/wow/manifest/`，运行时合并、冲突拒绝启动；Gradle 合并出服务级 `manifest.json`；公开端口 `GET /wow/manifest`，按普通 API 鉴权；跨上下文下游据此生成类型化消费代码 | 2026-10-12，用户 | [05](05-read-and-contract.md) |
| D75 | 数据集不并入 Manifest：运行时注册的数据集作为查询侧目录单独提供，view-engine 两者都读 | 2026-10-12，用户 | [05](05-read-and-contract.md) |
| D76 | Manifest 兼容性门禁：CI 比较本次与上一个已发布版本，主版本内的破坏性差异（删除、新增必填、schema 变化未升 revision）使构建失败；线上契约兼容由它守 | 2026-10-12，用户 | [05](05-read-and-contract.md) |
| D70 | 分区分配：HRW（最高随机权重哈希），各节点从成员列表独立算出同一目标分配，无主对账（每秒、成员变化时立即）；租约仲裁短暂分歧 | 2026-10-12，用户 | [02](02-write-path.md) |
| D71 | 交接与属主故障期间，命令降级为 Local 执行，不等新属主；以少量冲突换取命令可用性不依赖租约 TTL；主动交接按停收 → 执行完在途 → 存检查点 → 释放 | 2026-10-12，用户 | [02](02-write-path.md) |
| D72 | 租约组为（服务，上下文）；`Member` 增加节点内部地址；租约 TTL 10 秒、续约 3 秒、成员心跳 TTL 10 秒 | 2026-10-12，用户 | [02](02-write-path.md)、[04](04-subscriptions.md) |
| D77 | 集群通道分两步：4.2a 只做与传输无关的部分（`ClusterChannel` 端口与消息、HRW 对账、主动交接、降级、`Member` 地址、进程内回环传输与单 JVM 多节点测试）；4.2b 在宿主提供 HTTP/2 栈后接真实传输（D26 不变）。H6 只依赖 4.2a，H2 阶段 2 依赖 4.2b | 2026-10-12，用户 | [02](02-write-path.md)、[路线图](roadmap.md) |
| D78 | 集群通道目前只有一种消息：同步的命令转发，请求是命令的线上形式（含 `WaitContext`，`origin` 仍是发起节点）加绝对截止时间，响应是属主执行得到的 `CommandResult`；之后各跳的等待信号按 D65 直达发起节点；新条目推送留给订阅侧。消息是带版本号的 JSON，忽略未知字段（D48）。转发失败一律降级 Local：连接失败、超时、对端在途已满（默认每个对端 1024）、属主未知；不重试转发，属主其实已提交的由命令 ID 去重得到 `duplicate` | 2026-10-12，用户 | [02](02-write-path.md)、[08](08-data-protocols.md) |
| D79 | V10 注解与 9.x 同包（`me.ahoo.wow.api.annotation`）：13 个同名注解原地沿用、二进制形状不变，V10 处理器忽略 9.x 独有的属性；新增 `@Revision`、`@Alias`、`@Projection`、`@Reactor`、`@Saga`；V10 模型上出现已删除的注解或命令字段上的身份注解（D68）是编译错误；9.x 独有属性在删除 `wow-core` 的里程碑里裁掉 | 2026-10-12，用户；部分由 D85 修订（新模块里的 V10 注解不再带 9.x 属性） | [01](01-programming-model.md)、[06](06-modules.md) |
| D80 | 转发超时默认 1 秒，且不超过命令剩余的截止时间（取两者较小值），可配置；超时按 D78 降级 Local。1 秒盖住属主正常的执行尾延迟，又远小于 10 秒的租约 TTL | 2026-10-12，用户 | [02](02-write-path.md) |
| D81 | 命名：处理函数拿到的类型化形式叫 `StateEvent<S>`（`wow-api` 的 `subscribe` 包）；线上与存储形式（位置、事件流记录、状态视图，发布到 Kafka）改名 `StateEventRecord`，与 `EventStreamRecord`、`SnapshotRecord` 一致；D23、D58 中的 `StateEvent` 指后者 | 2026-10-12，用户 | [08](08-data-protocols.md) |
| D82 | 事件升级函数用注解 `@Upgrade(event, from)` 声明（参数与返回值为 JSON 节点），KSP 串成链并在编译期校验从 1 到当前 revision 不断档、不重复；V10 注解集增加这一个；生成的工厂函数的 `configure` 参数保留为通用扩展口 | 2026-10-12，用户 | [01](01-programming-model.md) |
| D83 | 新模块 `wow-gradle-plugin`（`compiler/`，插件 id `me.ahoo.wow`）：为用户构建接好 KSP 与 `wow-compiler`，注册「生成片段 schema」与「合并服务级 `manifest.json`」两个任务；内省在隔离类加载器的 Gradle Worker 里运行；与其他 V10 模块一样先孵化、不发布 | 2026-10-12，用户 | [06](06-modules.md)、[05](05-read-and-contract.md) |
| D84 | D73 的 schema 由 victools `jsonschema-generator` 加 `jsonschema-module-jackson` 生成（JSON Schema 2020-12），只在构建期使用；自定义序列化器无法内省，由显式覆盖配置补上 | 2026-10-12，用户 | [05](05-read-and-contract.md) |
| D85 | 同名模块不再原地演进：V10 重写的同名模块（先是 `wow-api`、`wow-compiler`、`wow-mongo`，以后的 `wow-query`、`wow-kafka` 等同理）在新目录另起模块，只放 V10 代码；Gradle 项目名临时加后缀 `-v10`（如 `:wow-api-v10`），发布的 artifactId 用最终名；9.x 模块的项目名与目录不动，前向合并照常；`v10` 不发布 9.x 模块，也不校验它们的 ABI（9.x 的 ABI 由 main 守）；架构测试禁止任何模块同时依赖新旧两边；M9 删除 9.x 时一次性去掉后缀。修订 06 §4「只能原地演进」与 D79：V10 注解在新模块里按 V10 原样定义，全名不变，不再带 9.x 的属性 | 2026-10-12，用户；命名部分由 D87 修订（不再用 `-v10` 后缀），ABI 与豁免由 D90 修订 | [06](06-modules.md) |
| D87 | V10 模块的 Gradle 项目名与目录不带 `wow-` 前缀（如 `:kernel`、`core/kernel`，`:mongo`、`backends/mongo`）；发布的 artifactId 与 jar 名仍是 `wow-<名字>`，由根构建统一设定，用户看到的坐标不变；项目名不得与 Gradle 任务名同名（如测试 DSL 用 `:test-dsl`，不用 `:test`）；与 9.x 的 `:wow-*` 天然不冲突，所以 D85 不再需要 `-v10` 后缀，M9 也不再改名；已有的 V10 模块随 D85 搬迁一起改名 | 2026-10-12，用户 | [06](06-modules.md) |
| D88 | V10 模块到 `10.0.0-beta`（API 冻结）才开始 ABI 转储与校验，之前一律不做；线上契约由 Manifest 兼容性门禁（D76）守；与 D85（`v10` 不校验 9.x 模块）合起来，`v10` 在 beta 之前没有 ABI 检查。代价：beta 前公开签名的变化只靠评审 | 2026-10-12，用户 | [06](06-modules.md) |
| D89 | 集群通道的真实传输（4.2b）：宿主 `wow-spring-boot` 在独立的内部端口上另起 Reactor Netty 的 HTTP/2（h2c）服务，`wow-http` 提供路由，跨服务的等待信号入口也在这个端口；公开 REST 仍走主端口，内部端口不经网关（D35，对抗验证 F6）；客户端用 JDK 的 `HttpClient`，不引入新依赖 | 2026-10-12，用户 | [02](02-write-path.md)、[04](04-subscriptions.md) |
| D90 | D85 的执行细则：`v10` 上保留 9.x 模块的 ABI 校验（拆分后转储与 main 一致，防止误改 9.x 签名、保持前向合并干净），只关闭 9.x 模块的发布（否则从 `v10` 发 10.0 预发布会把它们当 10.x 发出）；H3 的 `:kernel-benchmarks` 需要在同一 JVM 跑新旧两个内核，在「不得同时依赖新旧两边」的架构规则里具名豁免；搬迁分 3 个 PR（改名；拆 compiler、mongo、api；CI 路径、关发布与 AGENTS.md），各自 squash 合并 | 2026-10-12，用户 | [06](06-modules.md) |
| D91 | 注解写法用 `@AggregateRoot(lifecycle = [...])` 收窄启用的生命周期命令，KSP 传给 DSL 的 `lifecycle(...)` 并写进 Manifest | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [01](01-programming-model.md) |
| D92 | 准入在 `runtime`（`me.ahoo.wow.runtime.admission`）：`CommandGateway` 收 `CommandRequest`，返回 `Flow<CommandResult>`。顺序：解析命令类型 → 身份（每个事实一条规则，冲突即拒）→ 动作级授权（解码之前）→ 解码 → 改写（不得改身份、ID、等待）→ 校验 → 构造 `Command` → 等待链检查 → 路由；去重留在内核。修订 02 §1 的顺序 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [02](02-write-path.md) |
| D93 | 运行时的 `Caller`（租户、作用域、主体、`crossScope`、属性）；`CallerResolver` 在 `http`；CoSec 只在新的 V10 模块 `:cosec`（`edge/cosec`，artifact `wow-cosec`），其 WebFilter 按路径与方法授权 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [05](05-read-and-contract.md) |
| D94 | 内核作用域守卫：已有聚合的所有者或空间与命令不符即拒绝，码 `wow.aggregate.forbidden`（HTTP 403）；命令作用域为空的字段不检查 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [02](02-write-path.md) |
| D95 | 跨作用域请求头 `Wow-Scope`（RFC 8941 字典：tenant、owner、space）；无权限者携带即 403 `wow.auth.forbidden`，不静默忽略 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [05](05-read-and-contract.md) |
| D96 | 空间取自主体；`Wow-Space` 在主体所属的空间中选择，由解析器校验成员关系 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [05](05-read-and-contract.md) |
| D97 | 无租户的主体用 `wow.tenant.default = "(0)"`；默认解析器为匿名（默认租户、`Scope.NONE`），启动时告警 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [05](05-read-and-contract.md) |
| D98 | 等待用 `Wow-Wait` 与 `Wow-Wait-Timeout`，不用 `Prefer`（修订 D16）；请求 ID 用 `Wow-Request-Id`；期望版本用 `If-Match`，读响应带 `ETag` | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [05](05-read-and-contract.md) |
| D99 | `Wow-Wait` 语法：`plan := hop (">" hop)*`，`hop := accepted | committed["(" ctx ")"] | snapshot["(" ctx ")"] | handled(["ctx/"]订阅)`；`Hop.toString` 同形；超时写 `500ms`、`10s` 或 ISO-8601 ；`accepted` 也可带上下文 `accepted(ctx)`（Saga 之后的 accepted 可属另一上下文，Ahoo-Wang/Wow#4304） | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [04](04-subscriptions.md) |
| D100 | 非 Kafka 路由上的 `accepted`：准入后回 202，命令在节点的受监管作用域里脱离请求执行，不持久（D15） | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [04](04-subscriptions.md) |
| D101 | problem+json：`type=urn:wow:error:<码>`、`code`、`violations`，完整的 `CommandResult` 嵌在 `result` 下（修订 08 §7 的展平，避免 `status` 与 RFC 9457 冲突）；有聚合引用之前的错误不带 `result`；SSE 先看第一个元素，准入拒绝走 problem+json | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [08](08-data-protocols.md) |
| D102 | `wow.*` 错误码的 HTTP 状态表（见 05 §3）；领域拒绝默认 422，可按码配置；框架码在 Manifest 顶层 `errors`，各上下文的 `errors` 可选 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [05](05-read-and-contract.md) |
| D103 | 新增框架码：`wow.command.malformed`、`wow.command.invalid`、`wow.auth.unauthenticated`、`wow.auth.forbidden`、`wow.aggregate.forbidden`、`wow.command.busy`、`wow.node.closing` | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [08](08-data-protocols.md) |
| D104 | 命令路由：`POST /{ctx}/{agg}/{id}/{命令 kebab}`；创建命令另有不带 ID 的路由，由服务端生成 ID；编译器写入 `CommandManifest.routes`（列表）；合并时拒绝路由冲突与保留名（上下文 `wow`，命令段 `query`、`state`） | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [05](05-read-and-contract.md) |
| D105 | HTTP 解码、`EventCodec` 与集群编解码共用一个 Wow 自有的、只带 Kotlin 模块的 `JsonMapper`，不用 Spring 的 `ObjectMapper` | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [08](08-data-protocols.md) |
| D106 | `CommandValidator` 端口在 `runtime`；Jakarta 适配器在 `http`（`compileOnly`），由宿主启用；违例为 `wow.command.invalid`（400） | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [02](02-write-path.md) |
| D107 | 绑定索引：KSP 为每个模块生成 `ModelProvider` 并经 `ServiceLoader` 登记，带片段路径；内核的 `Dependencies` 端口在启动时解析工厂参数 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [05](05-read-and-contract.md) |
| D108 | 与宿主无关的 `ServiceNode` 组装器在 `runtime`：每个上下文一套（追加队列、内核、执行器、订阅、属主、等待协调器、转发端点），每个节点一份（等待登记、信号、路由、网关、模型登记）；启动与停机顺序见 06（细化 D71 到整个节点），即路线图 4.8 的组装部分 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [06](06-modules.md) |
| D109 | 内部端口路由 `POST /wow/internal/forward` 与 `/wow/internal/signals`；客户端在 `http`；服务端同时接受 HTTP/1.1 与 h2c（JDK 客户端从 HTTP/1.1 升级）；信号发送对每个发起节点有界排队，溢出即丢 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [02](02-write-path.md) |
| D110 | 宿主配置命名空间 `wow.*`（`service`、`node.*`、`internal.port` 9090、`backend` 按类路径自动选择、`log.partitions`、`log.append-queue.*` 按后端预设、`cluster.*`、`wait.*`、`tenant.default`、`http.*`），不沿用 9.x 的键 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [06](06-modules.md) |
| D111 | V10 示例为 `example/shop/{shop-api, shop-domain, shop-server}`（`:shop-*`），9.x 示例到 M9 前不动；一个上下文 `example`，`cart` 与 `order` 两个聚合，Saga 待订阅绑定 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [roadmap](roadmap.md) |
| D112 | M5 的读路由只有 `GET …/{id}/state` 与 `?version=`；`?at=`、`/state/trace` 与查询路由随 4.6 第二部分与 M6 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [05](05-read-and-contract.md) |
| D113 | 生命周期的 REST：`DELETE …/{id}` 与 `POST …/{id}/wow.lifecycle.<kebab>`；只为 Manifest `lifecycle` 列出的命令生成 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [05](05-read-and-contract.md) |
| D114 | `IdGenerator` 端口，默认 UUIDv7；宿主在有 CosId 时用 CosId | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [02](02-write-path.md) |
| D115 | 执行器满与停机在运行时内转为 FAILED 的 `CommandResult`（`wow.command.busy`、`wow.node.closing`），不再以异常逸出 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [02](02-write-path.md) |
| D116 | 按聚合类型固定租户（取代 9.x 的 `@StaticTenantId`）是宿主配置 `wow.aggregates.<ctx.agg>.tenant`，不是注解（规划者建议由用户确认；按授权先采纳，用户可改） | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [05](05-read-and-contract.md) |
| D117 | Manifest 格式 1 不带身份规则字段（删去 05 §2 的「身份规则」）：身份规则统一 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [05](05-read-and-contract.md) |
| D118 | 架构规则：`http` 只依赖 `runtime`（V10 `:query` 出现前）；`:cosec` 依赖 `http` 并列入替换对；`:shop-*` 只能依赖 V10 模块 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [06](06-modules.md) |
| D119 | 注入的领域服务用参数上的 `@Name("…")` 作限定名，生成的 `ModelProvider` 按（类型，限定名）解析；同类型不同限定名可以并存 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [01](01-programming-model.md) |
| D120 | `@OnCommand(produces = [...])`：返回 `Decision` 的命令可声明可能产出的事件，KSP 校验它们是本聚合的事件并写入 Manifest 的 `produces`；不声明则省略 | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [01](01-programming-model.md)、[05](05-read-and-contract.md) |
| D121 | 领域拒绝码用 `@ErrorCode(description, status = 422)` 标在 `const val` 上，KSP 收集进所在上下文的 Manifest `errors`；不得以 `wow.` 开头、模块内唯一；V10 注解集增加 `@ErrorCode` | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [01](01-programming-model.md)、[05](05-read-and-contract.md) |
| D122 | JSON 数字在存储与线路上精确往返：唯一的 Wow mapper（`:spi` 的 `WowJson`，只带 Kotlin 模块、`USE_BIG_DECIMAL_FOR_FLOATS`、保留尾随零）供内核、集群编解码与各后端使用（完成 D105）；PostgreSQL 用 `jsonb` 精确读写；MongoDB 以 `Decimal128` 保存小数（保留小数位），无法精确保存的数（超过 34 位有效数字）写入即拒绝，绝不舍入；无类型值里的小数绑定为 `BigDecimal` | 2026-10-12，协调者按推荐（用户授权默认按推荐） | [08](08-data-protocols.md) |
| D123 | 预发布：`v10` 的版本号改为 `10.0.0-alpha.2`（每次预发布前递增）；可用的 V10 模块移出孵化并发布到 Maven Central（artifactId `wow-<名字>`，不含示例、spike、架构测试；V10 模块在 beta 前仍不做 ABI 校验，D88；9.x 模块在 `v10` 上仍不发布，D90）；Gradle 插件只发 Maven Central（含插件标记，暂不发 Gradle Plugin Portal）；npm 用 `next` dist-tag。打标签与创建 Release 前须用户确认 | 2026-10-12，用户；**已撤销**，由 D124 取代 | [roadmap](roadmap.md) |
| D124 | 全部目标完成前不发任何预发布（取消各里程碑的 `alpha` / `beta` / `rc`）：里程碑出口只作为测试与 CI 门槛；`v10` 保持当前版本号、V10 模块保持孵化，版本号与发布范围在 M9 一次性准备，打标签前仍须用户确认。取代 D123 | 2026-10-12，用户 | [roadmap](roadmap.md) |
| D86 | 保留 `v10` 长期分支（维持 D2），不回主干：第一次前向合并只有 4 个冲突（都在工作流路径过滤），按目录分范围的 CI 与 D85 拆分已把分支成本降到每周一次合并；回主干则 main 须同时承载 9.x 与 10.0 的版本线，且工具链为 JDK 17 对 21（D29）。前向合并的提交由用户推送（自动权限分类器拦截直接推送）。M9 合入 main 前再评估 | 2026-10-12，用户 | [roadmap](roadmap.md) §1 |
| D69 | 默认等待 `SNAPSHOT`（即 `HANDLED(snapshot)`）而不是 `COMMITTED`：快照是默认读模型，写后即可查；代价是多一段快照订阅延迟、每条条目写 `WaitContext`；聚合关闭快照时默认退回 `COMMITTED`；修订 D15 的默认值 | 2026-10-12，用户 | [04](04-subscriptions.md) |
| D66 | `wow-api` 重新设计：只放领域代码会 import 的东西与对外线上契约的值类型，无依赖；包为 aggregate、decide、subscribe、result、lifecycle、annotation；9.x 的 modeling、naming、messaging、command、event 删除，query 搬入 `wow-query-api`；V10 包现在补齐，9.x 包随 `wow-core` 的替换删除 | 2026-10-12，用户 | [06](06-modules.md) |
| D67 | V10 注解集：约 15 个（见 01 §3），订阅处理函数统一为一个 `@OnEvent`、种类由所在类的 `@Projection` / `@Reactor` / `@Saga` 决定；KSP 只认 V10 注解，9.x 注解由迁移 Skill 映射 | 2026-10-12，用户 | [01](01-programming-model.md) |
| D68 | 命令体不放身份：删除命令字段上的 `@AggregateId`、`@TenantId`、`@OwnerId`、`@AggregateVersion`；身份在 `Command.ref`（路径或命令消息），作用域来自主体 | 2026-10-12，用户 | [01](01-programming-model.md) |
| D61 | 等待阶段收敛为 `ACCEPTED`、`COMMITTED`、`HANDLED(订阅)` 三个；`SNAPSHOT` 是 `HANDLED(snapshot)` 的简写；修订 D15 | 2026-10-12，用户 | [04](04-subscriptions.md) |
| D62 | 等待链：多跳，只有 Saga 跳之后能接跳（作用于它派生的命令），最多 4 跳，准入按 Manifest 校验；Saga 信号带派生命令 ID，全部到达才完成，派生 0 条即完成；早到信号按 `waitId` 缓存；任一跳失败立即失败 | 2026-10-12，用户 | [04](04-subscriptions.md) |
| D63 | 重复请求的等待：本上下文订阅按检查点与台账补查；跨上下文的跳无从补查，结果停在 `COMMITTED` 并标 `duplicate` | 2026-10-12，用户 | [04](04-subscriptions.md) |
| D64 | 等待超时：默认 10 秒，服务端上限默认 60 秒；绝对截止时间随 `WaitContext` 下发，过期后下游不发信号；超时结果带已到达的最后一跳 | 2026-10-12，用户 | [04](04-subscriptions.md) |
| D65 | 等待信号直接发往发起节点（`WaitContext.origin` 为实例内部地址），不经负载均衡与集群通道中继；送达失败即丢弃；修订 D35 中「按服务寻址、节点内中继」的部分 | 2026-10-12，用户 | [04](04-subscriptions.md) |
| D59 | 格式版本：日志、快照按表记录格式版本（schema 元数据），线上结构每条带 `format`；新增带默认值的可选字段不升版本；主版本内新字段先在一个次版本学会读、下一个次版本才写（D48 的落地规则）；不兼容的变化只在下一个主版本的迁移层处理（D22） | 2026-10-12，用户 | [08](08-data-protocols.md) |
| D60 | 对外命令结果：一个 `CommandResult`（status、hop、function、version、duplicate、error 含 violations、results）用于 HTTP、SSE、等待信号与回执；保留 9.x 的 `function`（SSE 中标明处理者）与字段级校验错误（改名 `violations`）；拒绝与失败走 problem+json 并带同样字段 | 2026-10-12，用户 | [08](08-data-protocols.md) |
| D54 | 快照只按版本守卫写入；状态 schema 变化后的重建一律写入新的快照存储（蓝绿），不做同版本原地覆盖 | 2026-10-12，用户 | [03](03-log-and-snapshot.md) |
| D48 | 主版本内的滚动升级：同一主版本内相邻次版本必须能混部：集群通道、日志条目、租约、等待信号、Kafka 信封都向前兼容一个次版本；10.0 GA 后重新启用 `mixed-version` 门禁；跨主版本才用蓝绿切换 | 2026-10-11，用户 | [02](02-write-path.md) |
