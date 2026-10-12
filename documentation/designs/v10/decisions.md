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
| D47 | 跨上下文命令被拒绝的回执 | 目标上下文把拒绝作为 `CommandRejected` 写入发起上下文的回执主题，**之后**才确认命令消息，崩溃重投保证至少一次，回执按命令 ID 去重；发起方 Saga 可订阅并补偿；技术故障才进目标的失败台账；拒绝不写日志 | 待定 | M6 | [04](04-subscriptions.md) |
| D45 | 租约交接与 fencing | 不做 fencing：命令靠版本唯一键，订阅副作用靠确定性 ID 幂等，检查点只前进，台账写入幂等（KaeX mvp 做过 fencing 后删除，理由相同） | 待定 | M4 | [04](04-subscriptions.md) |
| D40 | 历史状态（按版本、按时间、逐事件追溯） | 保留：最新读快照，历史从日志重放；带超时与版本数保护 | 待定 | M3 | [03](03-log-and-snapshot.md) |
| D10 | 新增 PostgreSQL 日志后端 | 新增，作为首选后端；分配方式由 H1 定 | 待证据 H1 | M3 | [03](03-log-and-snapshot.md) |
| D27 | PostgreSQL 驱动 | R2DBC（与 Spring 生态一致），以 H1 基准与 Vert.x 客户端对比 | 待证据 H1 | M3 | [06](06-modules.md) |
| D28 | 默认分区数 P | 64，按 H1 的单分区吞吐调整 | 待证据 H1 | M3 | [03](03-log-and-snapshot.md) |
| D6 | 命令路由 | 默认 Partitioned（经集群通道转发到分区属主）；单节点或降级用 Local；需要持久摄入的异步命令用 Kafka | 待证据 H2 | M4 | [02](02-write-path.md) |
| D15 | 等待阶段 | ACCEPTED / COMMITTED（默认）/ SNAPSHOT / HANDLED(订阅)；ACCEPTED 只在 Kafka 路由下表示持久 | 待定 | M4 | [04](04-subscriptions.md) |
| D24 | 集群通道 | 服务内节点间唯一内部通道（转发、信号、推送）；成员与属主来自存储租约；不做认证（D35） | 待定 | M4 | [02](02-write-path.md) |
| D25 | 等待的传输元数据 | `WaitContext` 只在等待超出 `COMMITTED` 时写入日志条目、跨上下文命令与 Kafka 信封的传输段；不属于事实，领域代码与查询不可见 | 待定 | M4 | [03](03-log-and-snapshot.md) |
| D26 | 集群通道的协议 | 复用宿主的 HTTP/2 栈与 JSON；有基准证据再换二进制协议 | 待定 | M4 | [02](02-write-path.md) |
| D34 | 跨上下文等待 | **保留**（重要特性，用户 2026-10-11）：等待目标是跨上下文的链；信号按发起服务寻址，经集群通道中继到发起节点；链上任一跳失败立即通知（[04](04-subscriptions.md) §6.2） | 方向已定，机制待定 | M4 | [04](04-subscriptions.md) |
| D17 | 契约来源 | Manifest 为唯一来源；生成器、OpenAPI、view-engine 都读它 | 待定 | M5 | [05](05-read-and-contract.md) |
| D30 | HTTP 宿主 | Spring WebFlux 的协程路由（`coRouter`）；运行时本身不依赖它 | 待定 | M5 | [06](06-modules.md) |
| D13 | Elasticsearch 的定位 | 不作为日志后端；作为快照、查询与全文检索后端 | 待定 | M6 | [03](03-log-and-snapshot.md) |
| D23 | 跨上下文集成 | 日志是上下文私有的；跨上下文走 Kafka Sink 发布的 `StateEvent`，下游订阅语义不变 | 待定 | M6 | [04](04-subscriptions.md) |
| D33 | 跨上下文的命令投递 | 一律写入目标上下文的命令主题；发送方等到持久 `ACCEPTED` 即完成；失败记入目标上下文的台账。集群通道不跨服务 | 待定 | M6 | [02](02-write-path.md) |
| D31 | Kafka 客户端 | 直接基于 `kafka-clients`，协程驱动拉取循环（Reactor Kafka 已停止维护） | 待定 | M6 | [06](06-modules.md) |
| D20 | TS 升级方式 | `wow-generator` 重新生成 + `wow-client` Skill 改写应用代码 | 待定 | M8 | [07](07-migration.md) |
| D21 | 迁移层的寿命 | 只支持 9.x 最后一个次版本 → 10.0；10.x 维护，11.0 删除 | 待定 | M8 | [07](07-migration.md) |

## 2. 待验证的假设

| # | 假设 | spike | 放弃条件与退路 | 里程碑 | 影响的决定 |
|---|---|---|---|---|---|
| H1 | 分区序号的分配能满足吞吐 | PostgreSQL 两种分配方式与 MongoDB 事务；单分区与 64 分区的吞吐、p99、尾随延迟；**分别在属主串行（Partitioned）与多节点并发追加（Local、交接期）下测**，含分组提交里单条冲突的隔离；故障注入 | 单分区不足：加大 P；MongoDB 事务过贵：分组提交（同分区多次追加合并为一个事务），MongoDB 不降级（D11） | M1 | D10、D11、D27、D28 |
| H2 | 亲和路由值得转发的一跳 | 3 节点随机负载均衡，Local 与 Partitioned 的缓存命中率、每命令读次数、冲突率、p99 | Partitioned 的 p99 不优于 Local：默认改为 Local，并按 H1 的并发追加数据重新评估 MongoDB 后端（F1） | M1 | D6、D11 |
| H3 | 协程内核不比 9.3 的 Reactor 内核慢 | 同一 JMH 生产链路基准的吞吐与 `gc.alloc.rate.norm`，同步与 `suspend` 处理函数分别测 | 下降超过噪声：优化热点后才进入 M2；方向已定，不回退 | M1 | D9 |
| H4 | KSP 覆盖注解模型的全部语义 | example、compensation、view-store 与 transfer（Java 源码）的生成模型与 9.x 运行时解析结果比对 | 有缺口：补编译器，不回到反射 | M1 | D7、D8、D37 |
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
| D11 | MongoDB 是一等日志后端（D5 的条件）：多文档事务分配分区序号，吞吐不足用分组提交与加大 P，不降级 | 2026-10-11，用户 | [03](03-log-and-snapshot.md) |
| D12 | Redis 事件存储：V10 不提供 Redis 日志后端；需要的社区实现者按日志合同与 TCK 自行实现 | 2026-10-11，用户 | [03](03-log-and-snapshot.md) |
| D14 | 失败处理与补偿：停放聚合 + 失败台账；台账写在出错服务自己的存储里，wow-ops 经 Kafka 汇总并把重试、跳过作为命令发回；compensation 改为 wow-ops | 2026-10-11，用户 | [04](04-subscriptions.md) |
| D16 | REST 形状：身份来自主体，删除租户与所有者的路由变体；problem+json；`Prefer` 头表达等待 | 2026-10-11，用户 | [05](05-read-and-contract.md) |
| D18 | wow-bi：由 ClickHouse Sink 取代，删除 | 2026-10-11，用户 | [04](04-subscriptions.md) |
| D19 | 9.x 维护期限：V10 GA 后 `release-9.x` 只收安全补丁，为期 12 个月 | 2026-10-11，用户 | [roadmap](roadmap.md) |
| D22 | V10 之后的格式演进：框架格式（信封、日志结构、快照元数据）带版本，运行时只读当前版本，**不兼容的**变化只在下一个主版本的迁移层转换（新增带默认值的可选字段属于同一版本内的加法，R5-1）；领域事件用 `revision` 与用户写的升级函数在读取时升级，属于用户模型 | 2026-10-11，用户 | [01](01-programming-model.md)、[07](07-migration.md) |
| D29 | JVM 基线：最低 21，CI 同时在 25 上测试 | 2026-10-11，用户 | [06](06-modules.md) |
| D32 | 切换后的回滚：切换后 V10 未写入：直接切回 9.x；已写入：提供 V10 → 9.x 的反向导出，只覆盖迁移前已存在的事件类型 | 2026-10-11，用户 | [07](07-migration.md) |
| D35 | 部署前提与跨服务信号：所有服务部署在同一个集群内、彼此可直接调用；跨服务等待信号只用 HTTP 调用发起服务的信号入口，不提供 Kafka 替代；集群内部流量受信任，信号入口与集群通道不做认证 | 2026-10-11，用户 | [00](00-architecture.md)、[04](04-subscriptions.md) |
| D36 | 处理函数的调用形态与快速路径：同步、`suspend`、Reactor 三种形态，KSP 编译期选定调用；同步函数内联在工作线程上执行，阻塞的须标 `@Blocking` 并派发到阻塞调度器（JDK 21 下为虚拟线程）；内核在缓存命中且处理函数同步时只在追加处挂起 | 2026-10-11，用户 | [01](01-programming-model.md) |
| D37 | 注解模型由 KSP 在编译期生成绑定代码与 Manifest，运行时不扫描、不反射，也不为任何语言保留反射退路。收益：错误提前到编译期（重复处理函数、无法解析的参数）、快速路径是直接调用、启动快且可 AOT、绑定与 Manifest 同源；代价：构建依赖 KSP、需要调试生成代码、编译器须覆盖全部语义（H4） | 2026-10-11，用户 | [01](01-programming-model.md) |
| D38 | 跨聚合唯一性（9.x `PrepareKey`）：保留：`@WowSpi` 端口，由日志后端实现；在 Decide 中经 `DecideContext` 预留，拒绝或追加失败时内核回滚；释放与转移由 Saga 完成 | 2026-10-11，用户 | [01](01-programming-model.md) |
| D39 | after 钩子（9.x `@AfterCommand`）保留：外部用户在用。用途是在 Decide 之后追加事件：命令函数之后按 include/exclude 匹配、按声明顺序执行，看到与命令函数相同的（未演化）状态；事件拼在命令事件之后组成同一条事件流，一起演化、一次追加；调用形态同 Decide（D36），可以做 I/O；任一失败则命令失败、不写入 | 2026-10-11，用户 | [01](01-programming-model.md) |
| D41 | 仓库目录结构：按层分目录（`api/`、`compiler/`、`core/`、`query/`、`backends/`、`edge/`、`host/`、`test/`、`migration/`、`bom/`、`platform/`），Gradle 项目名保持 `:wow-xxx`；新模块在 M0 按新结构建立，main 上仍活跃的平台目录在 M9 合入前一次搬迁 | 2026-10-11，用户 | [06](06-modules.md) |
| D42 | 后端模块的划分：一种技术一个模块（`wow-postgres`、`wow-mongo`、`wow-elasticsearch`、`wow-clickhouse`、`wow-kafka`），各自实现它能实现的全部端口；不按日志、查询等角色拆 | 2026-10-11，用户 | [06](06-modules.md) |
| D43 | 内核的拆分：`wow-spi`（端口，供后端实现者与 TCK）、`wow-kernel`（模型与提交循环，供测试 DSL 与迁移工具）、`wow-runtime`（集群、订阅、等待、生命周期）三个模块 | 2026-10-11，用户 | [06](06-modules.md) |
| D44 | Spring 集成的形态：一个按类路径生效的自动配置模块 `wow-spring-boot`；后端由用户直接加技术模块，不用 Gradle 特性变体（Maven 用户同样适用） | 2026-10-11，用户 | [06](06-modules.md) |
| D46 | 请求幂等：去重先于决定、在冲突重试之外只做一次：有 I/O 的 decide 每次走 L2（唯一索引存在性检查）；纯计算的 decide 可用 L1 轮转布隆过滤器跳过 L2，漏判由追加唯一键兜住（对抗验证 F3：布隆的「无假阴性」跨重启、跨属主、跨轮转不成立）；记录命令指纹，同一请求 ID 携带不同命令时拒绝 | 2026-10-11，用户 | [02](02-write-path.md) |
| D48 | 主版本内的滚动升级：同一主版本内相邻次版本必须能混部：集群通道、日志条目、租约、等待信号、Kafka 信封都向前兼容一个次版本；10.0 GA 后重新启用 `mixed-version` 门禁；跨主版本才用蓝绿切换 | 2026-10-11，用户 | [02](02-write-path.md) |
