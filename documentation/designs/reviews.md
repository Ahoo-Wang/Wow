# Wow 10 审查台账

日期：2026-11-09（初稿 2026-10-19；2026-10-22 按 `origin/main` 逐项复核状态）。状态：持续维护（主会话在派工前登记，修复合并后更新）。上位文档：[进度](progress.md)。

本文是审查发现的唯一记录：每项带编号、位置与现象，派工说明只引用编号。第一轮（A.1～A.5，75 项）的结论与遗留见 [进度](progress.md) 的「全面审查结论」；本文从第二轮 R2（D230）开始逐项登记。

## 1. 登记规则

- 发现先登记、后派工：子会话的说明引用本文编号，不在会话上下文里单独保存发现。
- 每项写明：编号、级别（P0/P1/P2）、位置（模块与文件）、现象与触发条件、去向（修复批次、PR 或决定）、状态。
- 状态：`待修`、`修复中`、`部分`（写明余下什么）、`已修`（带 PR）、`转 10.x`、`放弃`、`待重审`（原文丢失，须重新审计找回）。

## 2. 第二轮全面审查 R2（D230）

基线 `e043be491`，只读，34 项（P0 0、P1 7、P2 27）。下表在 2026-10-19 补登：R2 的发现原文只留在当时协调会话的上下文里，现由进度文档、各批 PR 的描述与提交还原；还原不出现象的标为 `待重审`。

| 编号 | 级别 | 位置 | 现象 | 去向 | 状态 |
|---|---|---|---|---|---|
| R2.1-1 | P1 | `:runtime` | 节点在途上限以 BUSY 拒绝内部来源（intake、Saga、定时器），被当作技术故障停放 | G2 | 已修 #4555 |
| R2.1-2 | P1 | `:runtime` | 跨上下文不带 ID 的创建共用 `(none)` 车道、分区与停放键 | G2 | 已修 #4555 |
| R2.1-3 | P2 | `:runtime` | 重复请求的回执没有带上已提交流的聚合 | G2 | 已修 #4555 |
| R2.1-4 | P2 | `:runtime` | 主题消息换了分区后，聚合的停放丢失 | G2 | 已修 #4555 |
| R2.1-5 | P2 | `:runtime` | 续约间隔不小于租约 TTL 的一半时不报错 | G2 | 已修 #4555 |
| R2.1-6 | P2 | `:runtime` | 信号在本地投递时不检查是否属于本服务的节点 | G2 | 已修 #4555 |
| R2.1-7 | P2 | `:runtime` | 同一命令的提交回执与拒绝分不开 | G2 | 已修 #4555 |
| R2.1-8 | P2（单节点部署为 P1） | `:runtime` 主题订阅 | **重审还原（2026-10-19）**：`TopicSubscriptions.start`、`refresh` 里的 `joinOver()` 不在 `retryForever` 之内；`PartitionOwnership.start()` 遇到租约存储或心跳的暂时错误时，后台加入协程结束，节点已报 RUNNING 却永不读该主题；`running` 停在半启动或已停止的属主上，重试撞 `check(running == null)`（`TopicSubscriptions.kt:175-200`、`:227-243`，`PartitionOwnership.kt:235-263`） | 运行时批 | 已修（#4579） |
| R2.1-9 | P2 | `:runtime` | 等待在发出前已超时的转发没有撤下 | G2 | 已修 #4555 |
| R2.2-1 | P1 | `:postgres` | 依赖连接池默认 autocommit，关闭时写入被静默回滚 | G3 | 已修 #4554 |
| R2.2-2 | P2 | `:mongo` | 超过文档大小上限的事件流在分组提交里拖累同组 | G4 | 已修（#4556） |
| R2.2-3 | P2-低 | `:kafka` | **重审还原**：`SharedTopicConsumer` 在 `OffsetOutOfRangeException` 处理里调用 `place()`，不在 `WakeupException` 的捕获内；期间的加入或离开唤醒会结束整个会话，所有读者失败重读（`SharedTopicConsumer.kt:161-163`）；能自愈，但噪声大 | G4 收尾 | 已修（#4556） |
| R2.2-4 | P2 | `:mongo` 查询 | `ENDS_WITH` 没有锚定到字符串末尾 | G4 | 已修（#4556） |
| R2.2-5 | P2 | `:postgres` | `PostgresFailureLedger` 的 KDoc 过时 | G3 | 已修 #4554 |
| R2.3-1 | P1 | `:http`、`:spring-boot` | `Wow-Call` 的 `wait.origin` 不校验服务，被信任的调用方可套走目标服务的集群令牌（绕过 D226） | G1 | 已修 #4553 |
| R2.3-2 | P2 | view-store | 授权器放行不带属主的写入 | G1 | 已修 #4553 |
| R2.3-3 | P2 | view-store | 偏好写入可以作为别人的属主 | G1 | 已修 #4553 |
| R2.3-4 | P2 | `:kafka` | `wow.kafka.offset.gaps` 指标的来源被弱引用回收 | G4 | 已修（#4556） |
| R2.3-5 | P2 | `:http` | 重建与作业的系统路由不在内部端口的调用路由上 | G1 | 已修 #4553 |
| R2.3-6 | P2 | `:http` | 快照写出前 `events` 模型的可见性 | D231 的 A.M | 已修 #4566 |
| R2.3-7 | P2 | view-store | 系统视图 ID 前缀可被占用；一个坏的存储视图让所有租户的列表失败 | G1 | 已修 #4553 |
| R2.3-8 | P2 | `:http` | 停放与系统视图路由的存储故障没有按 problem 回答 | G1 | 已修 #4553 |
| R2.3-9 | P2 | `:http` | `/wow/ops/parked` 的读取没有授权 | G1 | 已修 #4553 |
| R2.4-1 | P1 | `:migrate-data` | 补偿导入只增不减（9.x 之后解决的执行仍停放） | G3 | 已修 #4554 |
| R2.4-2 | P1 | `:migrate-data` | 预留导入不跟随 `rollback` 与 `reprepare` | G3 | 已修 #4554 |
| R2.4-3 | P2 | Manifest 兼容门、Gradle 插件 | **重审还原**：缺少基线时 `checkManifestCompatibility` 通过，只在报告里写一行，`check` 为绿却没有门（`ManifestTools.kt:64-67`、`WowPlugin.kt:173`）；当前主版本高于基线后所有破坏性变更都放行且不告警，直到有人更新基线（`ManifestCompatibility.kt:62-70`） | P.20 之后 | 已修（2026-11-02 复核：没有基线即失败，任何破坏性变更都使闸门失败，有意的破坏改记为新基线；`ManifestCompatibility`、`CheckManifestCompatibility`） |
| R2.4-4 | P2-低 | `:migrate-data` | **重审还原**：检查点 `save(load().with(..))` 是读改写，固定的 `<file>.tmp`、无锁、原子移动前不 fsync；切换规程里增量追尾与 compare 或快照生成同时跑时互相覆盖（`ImportCheckpoints.kt:157-172`、`EventStreamImporter.kt:305`） | 迁移批 | 已修（#4556） |
| R2.5-1 | P2 | `:runtime` | 定时命令在摄入处不等到期时间 | G2 | 已修 #4555 |
| R2.5-2 | P2 | 横切 | 同步调用的执行上下文与超时 | G5 | 已修（#4602） |
| R2.5-3 | P1 | `:spring-boot` | `wow.http.services.*` 拼错键名不报错，`commands` 为空时放开全部领域命令（由 P2 升 P1） | G1 | 已修 #4553 |
| R2.5-4 | P2 | 横切 | 错误码表 | G5 | 已修（#4602） |
| R2.5-5 | P2 | CI | v10 CI 范围（改 CI 须用户批准） | G5 | 已修（#4602；CI 之后由 #4689、#4739 重做） |
| R2.5-6 | P2 | 设计文档 | 文档漂移 | — | 已修 #4552 |
| R2.5-7 | P2 | `:architecture` | 架构规则 | G5 | 已修（#4602） |

## 3. 其他来源的发现

| 编号 | 级别 | 位置 | 现象 | 去向 | 状态 |
|---|---|---|---|---|---|
| X-1 | P2 | `:http` | SSE 响应体在被订阅前请求就取消时，结果 channel 永不取消，生产者挂在 `send` 上泄漏（`CommandHandler`、`QueryHandler`） | 单独修复 | 已修 #4559 |
| X-2 | P2 | `:http` 模块边界 | 读路径的语义散在 `:http` 与宿主里 | D231 的 A.M | 已修 #4566 |
| X-3 | P1 | `:spring-boot`、9.x 能力复核 | 没有 springdoc 集成（Swagger UI、与应用接口合并、securitySchemes）；09 §9 误判为 A | D233，P.19 | 已修 #4574 |
| X-4 | P2 | `:eshop-payment-server` 测试 | `PaymentServerTest.close` 卡在 Spring 的 `GracefulShutdown.abort` 约 40 分钟，栈上无 Wow 帧；重跑通过（P.14 验证时见到） | 待查 | 已修（#4680） |
| X-5 | P1 | `:runtime` 命令摄入 | 未知的聚合或命令类型被当作技术故障：重试后停放目标聚合，其后的命令等运维（`CommandIntake.kt:71-74`、`:120-125`）；版本错位或发错命令即可触发，发送方的缺陷越过服务边界。应作为拒绝回答（如 `wow.command.unhandled`，带回执） | 独立修复（D234 对抗性验证 K1） | 已修 #4573 |
| X-6 | P2 | `:kernel`、`:compiler` | 命令与事件的命名有三套：编译器读 `@Name`，内核 DSL 默认名（`AggregateModelDsl.kt:246`）与 `CommandTypes.SIMPLE_NAMES`（`Kernel.kt:1112`）不读；手写或 Java 模型与 Manifest 的名字可能不一致。统一为一个命名函数，DSL 的 `name=` 与 `@Name` 不一致即失败 | 独立修复（K2） | 已修（随 R5.A-5，#4914；2026-11-02 复核） |
| X-7 | P2 | `:kernel` 定时 | `wow.schedule.Set` 存下 `commandType` 而不校验（`KernelLifecycle.kt:98`），名字写错要到触发时才失败（cron 可能数月之后）。目标在决定 `SetSchedule` 时按其模型校验，错误即拒绝 | 独立修复（C6） | 已修 #4573 |
| X-8 | P2 | `:query` 内存后端 | 对多值数组路径的根级指标只取单元素数组的值（`InMemoryValues.kt:135`），一次提交里两个 `ItemAdded` 的 `sum(events.body.qty)` 悄悄少算 | D235 ③（准入拒绝） | 已修 #4570 |
| X-9 | P2 | `:clickhouse` | 数据集的 `count()` 不加 `FINAL` 时，ReplacingMergeTree 在合并前会因重放多计（未核实） | A.M2 第二步核实 | 已修（#4376） |
| X-10 | P2 | `:runtime` 集群配置 | `ClusterOptions` 只校验了续约间隔（R2.1-5），心跳间隔只需小于成员 TTL，0.9 倍也能通过，一次慢心跳即让成员过期、分区抖动；`reconcileTimeout > drainTimeout` 也未校验（`ClusterOptions.kt:96`） | 运行时批 | 已修（#4579） |
| X-11 | P2-低 | `:kernel` 定时 | `ScheduleSlots` 的 cron 与时区缓存是静态 Map、永不淘汰，键来自用户给的字符串；能否由不可信输入触达未核实（`ScheduleSlots.kt:46-47`） | 运行时批 | 已修（#4579） |
| X-12 | P2 | `:runtime` 测试 | `WaitWithdrawalTest`「a command still queued in its lane when its wait times out…」在机器负载下偶发失败（10-19 两次，单跑通过），期望 `timed out at ACCEPTED` 得到 `COMMITTED` | 运行时批 | 已修（#4579） |
| X-13 | P2 | `:cosec`、`:spring-boot` | CoSec 启用后 `wowCoSecCallerResolver` 与 `wowCoSecInternalCallerResolver` 都是 `CallerResolver`，宿主的 `wowRouterFunction(callers: CallerResolver)` 启动失败；P.15 以平台侧 `PublicCallersFirst` 绕过 | R3-S | 已修（#4580） |
| X-14 | P1 安全 | `:cosec` | `CoSecInternalCallerResolver` 使用任意 `SecurityContextParser`，inject 模式下会接受伪造的服务主体；P.15 以平台侧的校验解析器绕过，框架未修 | R3-S | 已修（#4580） |
| X-15 | P2 | `:clickhouse` Sink | 滚动升级时新旧实例交替 `CREATE OR REPLACE VIEW _state_flat`；`VALIDATE` 只在首次准备时检查，之后发现不了漂移（`ClickHouseSink.kt:120-133`、`ClickHouseSinkSchema.kt:100-102`） | ClickHouse 批 | 已修（#4556） |
| X-16 | P2 | `:clickhouse` Sink | 表名前缀 `"${context}_${name}".lowercase()` 再把非 `[a-z0-9_]` 换成 `_`，是有损映射：`order-item` 与 `order_item`、大小写不同的名字会落到同一组表，且没有冲突检查（`ClickHouseSinkSchema.kt:218-220`） | ClickHouse 批 | 已修（#4556） |
| X-17 | P1 | `:test-dsl` | 9.x 能力回退：`AggregateSpec.fork` 重放 fork 点之前的步骤（重新执行 given 与每条命令），注入的 mock 被重复调用、调用次数校验失败、有状态 mock 结果改变；整个规范是一个 `@Test`，第一个失败的 fork 让其余 fork 不运行，没有场景树；09 误记为 A | T.1（D245） | 已修（#4588） |
| X-18 | P2 | 基准 ABAB | 饱和扫描的压测端只有一个核，e1-pg-uniform 与 mixed-pg 两边都在 0.97～0.98 核到顶，饱和比测的是压测端；三个 PG 套件在 `noise.json` 里没有 A/A 阈值，9 个比值都无法判定显著 | 压测端多核或多进程；压测端核用量过约 0.9 时把饱和与校准行标为无效；补 A/A 阈值 | 已修（#5065、#5121；#5138 给 mongod 独立的核，`bench.mjs` 的 `cpuLayout`，Mongo 的 A/A 阈值随之重算；2026-11-02 复核）。4 核 runner 上 mongod 一核到顶、延迟阈值因此偏宽，是 runner 的限制，记为已知 |
| X-19 | P2 | `:spring-boot` 集成测试 | `MongoSagaStateHostTest` 在本地偶发失败两次（多读一次尾部，期望 0 得 1），CI 上未见 | 定位根因后修，不靠重跑 | 已修（#5120）：`HandoffLog` 的空闲探测有竞态，读头在途时自己的追加已交给内存，于是多读一次；`withTimeoutOrNull(receive)` 还可能丢一个元素。改为先排空再复查、用 `select`/`onTimeout`，并加门控读头的测试 |
| X-20 | P2 | 基准 ABAB | 混合 Mongo 套件里列表查询答 429：来自查询隔舱的 `wow.query.busy`；四核上 node、mongo、kafka 挤在两核，服务端整体饱和 | Mongo 套件的核分配改为压测端 3、node 0、mongo 1、kafka 2；每秒记下查询上限的轨迹；429 单列；差距过 3 倍时重测饱和；补 Mongo 的 A/A 阈值 | 已修（#5138）；限流本身的缺陷另登记为 X-21 |
| X-21 | P1 | 查询准入 | 自适应查询上限在没有查询负载时也减半（预热时 25→3）、只写阶段停在下限「50 中的 1」不再恢复、混合负载下在 1～8 之间震荡；写压力基线的非对称 EWMA 追到最安静的几秒，平常的 15～30 ms p90 都算压力（#5138 的限流轨迹） | 只有查询争用时才降；无压力时逐段恢复（无争用到起始值、有争用到上限）；基线为最近 60 段 p90 的中位数；压力须同时高于基线 1.5 倍与 20 ms | 已修（#5147） |
| X-22 | P1 | 集群运行时 | GA E2E 中 payment 的 pod 启动后整个虚拟线程调度器卡死（疑为 JDK 21 下 `synchronized` 钉住载体：Kafka 客户端在 OTel agent 下的 JMX 注册），控制面的租约与心跳都跑在它上面且无界，看门狗的补救要靠同一执行器上的心跳，存活探针看不见，节点静默卡住约 1 分钟、未就绪 48 秒 | 存活探针在调度器停摆或对账超过时限时 DOWN（复用 `wow.health.liveness.stall-timeout`）；E2E 采集被钉住线程的跟踪与线程转储；推荐运行时 JDK 25（D368）；控制面与数据面继续共用执行器（卡死时及时交出租约，缺的只是重启） | 已修：存活探针（#5172）、推荐运行时 JDK 25（#5176，D368）；触发已证实（ga-e2e 运行 40856899674 的 JDK 21 钉住跟踪：Kafka `AppInfoParser.registerAppInfo` 与 `Metadata` 的 `synchronized`、`MongoShards.isMongos` 的 `lazy`），Mongo 存储改为构建时解析是否分片（#5178） |
| X-23 | P1 | `:mongo` 分片集群 | 夜间 `mongo-cluster.yml` 的 sharded 作业自加入（D277）起从未通过（10-25～10-28 四晚，rs 作业通过），四晚无人察觉。原因有四，均不在产品：分片关闭的用例不先 `createSchema()` 就写定时器，共享注册集合在无格式记录时被隐式创建，其后每个 `createSchema` 都被拒，连锁 66 个失败；`MongoEventLogTest` 未计 mongos 上分片键的第三个索引；`MongoTimerStoreTest` 直接 `updateOne` 不带分片键（错误 72）；集群容器启动时 `addShard` 一次静默失败即退出 | 测试按宿主顺序先建 schema，不加存储端守卫（`NodeLifecycle` 先建 schema，下一次 `createSchema` 已明确报错）；索引计数加分片键并断言；单文档更新带分区；`supervise.sh` 重试 `addShard`、逐步记日志，`cluster.sh` 启动失败时打印容器状态与成员日志，时限 300 s；协调者每轮查看夜间运行 | 已修（#5183）；main 上首次通过 sharded：运行 40873118816 |
| X-24 | P2 | `:runtime` 测试 | #5172 新增的 `NodeHealthTest` 两个对账存活用例偶发失败（慢存储用例量到 60 µs 的空对账，卡死用例 13 次中 1 次等不到 DOWN）：用例等到「任意」对账就撤掉丢租约，若该对账早于租约丢失开始，它无可获取、立即结束 | 租约桩在被请求获取时完成 `asked`，用例等它之后才撤掉；慢存储挡住 1 s，对正在等待的那次对账断言，不取采样最大值、不放宽 250 ms 的界 | 已修（#5181） |
| X-25 | P1 | `:manifest-tools` 契约 | 类型参数的可空性丢失：`List<Long?>` 的元素、`Map<String, String?>` 的值写成非空；Map 以擦除后的类型作为共享 `$defs`，`Map<String, Long?>` 与 `Map<String, Long>` 共用一个定义（`SchemaIntrospector.isKotlinNullable` 只读属性本身的类型）；查询描述符、D362 的 `<State>View`、TS 类型都把可空值当作非空。同时框架的定时规则未声明语义：快照画像手写的 `schedules.{key}.epochMillis`（时刻）与 `millis`（时长）没有语义类型，不给 TODAY 与日期界面 | 容器（列表、数组、Map）在每个用处内联生成，只有类与枚举是 `$defs`；可空性按 `KType` 的类型参数递归取；Java 与平台类型不变；不做兼容（D306）；端到端验证到描述符、`<State>View` 与 TS 类型。定时规则在 `QueryModelProfile` 声明语义，`ScheduleRule.At`/`After` 加同样的注解，由测试把两处钉在一起 | 已修（#5190，D371） |
| X-26 | P2 | `:query` 单记录准入 | 单记录读的准入（`RecordAdmission` 的 `RecordFilter`）只认等值、IN、空与存在，与内存后端的 `InMemoryFilters` 是两套求值：同一条范围策略在状态读与 trace 上隐藏、在快照查询上返回该行 | 一个过滤求值器两处共用，同一策略处处准入同一行；只有本地无法求值（全文搜索等）才失败即隐藏；相对时间先换算为区间；删去重复代码 | 已修（#5197）：单记录准入与内存后端共用解析与求值，`RecordFilter` 删除；只有 schema 校验拒绝即隐藏，求值前已拒绝一切无法求值的条件，两处走不到的捕获删除；在 `ELEMENT_MATCH` 之外引用元素字段，单记录读也拒绝 |
| X-27 | P2 | CI 覆盖率 | 所有 TS 包的上传都带共用的 `typescript` 标记，Codecov 只在一个会话的所有标记都没有本次上传时才沿用它，一次只测部分 TS 包的运行让其余包从报告中消失（只改 Kotlin 的 `ebbde37` 上 TS 只剩 98 个文件，应为约 780）；view-engine 上传最慢，main 的子提交还会在它到达前建报告 | 每个 TS 包只用自己的 `typescript-<包>` 标记，状态与组件按名单读；`typescript` 与遗留的 `unit` 标记停止沿用；ci-gate 的缺口检查覆盖 TS 标记 | 已修（#5184）；验证：只改注释的 PR 上五个 TS 包的标记都在，ci-gate 当场指出 main 未完成运行留下的 JVM 沿用缺口 |
| X-28 | P2 | CI 覆盖率 | #5182 上 34 个 JVM 组件状态误报下降：Codecov 处理时通常会去掉 Kotlin JaCoCo 报告中只有 `}` 的行（约 61,190 行），该提交多出一个本次作业并未发送的重复 `jvm-unit-tooling` 会话，未做此调整，多出约 4,600 行花括号行；上传已关闭搜索、只有一个文件 | 在我们的覆盖率 XML 改写中去掉只有右花括号的 `<line>` 并重算各级计数，上传的数据不再依赖 Codecov 的调整；测试钉住每个上传步骤 `disable_search` 与显式文件 | 已修（#5193）：规范化后 jvm-unit 61,387 行、86.45%，与基线 61,392 行、86.46% 一致（Kotlin 代码相同） |
| X-29 | P2 | eShop Web E2E | `shop.spec.ts:44` 的「拒付后链路在已支付之前结束并取消订单」一步偶发超时（约 1/25）：`shop.spec.ts:151` 等待「the chain ended at handled(eshop-payment/OrderPaymentSaga.onPaymentSucceeded) without paying the order」45 s 未出现，其后的断言未执行（运行 40879123801，作业 123465741294）；与 X-22 无关 | 从该作业的 trace、截图与错误上下文查明链路结束是迟到（Saga、CoCache 回源、停放的一步、等待链通知）还是丢失；丢失即产品缺陷，在浏览器以下补可复现的测试；不加长超时；不能定论时在 Actions 上重复派发该作业复现 | 进行中（2026-10-30 派给子会话 x29-chain-flake）：实际约 1/1000（10-26 起约 1,120 次运行失败 1 次），不是 1/25；trace 显示 bob 的结账 15 跳中 11 跳在约 200 ms 内到达，第 12 跳（`PaymentGatewaySaga.onPaymentRequested`）与服务端 30 s 的等待超时都没有到，按本地重放 13 ms 结束，指向第 11 跳之后测试 JVM（五个服务共用）或其共享线程停顿，而不是 Saga 慢或信号丢失。失败诊断已合并（#5208：失败时附完整日志、GC 与安全点日志和两份线程转储，含虚拟线程）；Actions 上循环 784 次（运行 41030728308）未复现，临时分支已删。挂起：等下一次自然发生时由 #5208 的诊断取证 |
| X-30 | P2 | CI 覆盖率 | `ci-gate` 依赖 Codecov 的 `codecov/patch`：结转会话、半途通知与过期基线反复让合并关卡误判或久等（X-27、X-28、D358、D365 都在补它） | 短期：`ci-gate` 不再依赖 Codecov，在单一的 `coverage` 作业里用本次运行自己的 JaCoCo 与 Istanbul 报告、对与 main 的合并基的改动行判定补丁覆盖率（≥ 89%，即 Codecov 的 90% 减其 1% 阈值）；改动的主源码文件无任何报告插桩时，只有该类报告整类没跑才失败，否则告警；Codecov 的 patch、project 与组件状态改为 `informational`，上传与唯一一次通知尽力而为；夜间全量运行比较一周前的总覆盖率（总体降超过 0.5 个点或任一标记降超过 1 个点即失败）。D358 的缺口检查不再充当关卡，D365 的单次通知保留 | 已修（#5201、#5205、#5206） |
| X-31 | P2 | eShop 浸泡 | `soak.yml` 在 main 上 10-27～10-29 连续三晚失败，每晚同样两条 `read-models.converge` 违例，其余八条不变式（命令恰好一次、日志连续、无重复效果、流程完成、检查点单调、分区有主等）全部通过：① 抽样到内置聚合 `inventory.schedule`（`receive-deliveries`），它没有 `/state/trace` 路由，404；② 热点库存 `inventory.stock/soak-a` 一晚超过 1 万次提交，按版本翻页的 trace 超出状态读的回放上限 `wow.query.state.max-replay`（默认 10000），400 `replay-too-long`（运行 40969321145 的 `report-20261029-225524.json`）。是检查器的问题，不是产品缺陷：回放上限是有意的保护。另：② 说明默认配置下一个聚合超过 1 万个版本后，其 trace 与按版本的历史读在上限之后即不可读（只有最新快照，历史读须从第一个版本回放），待与用户讨论（推荐 GA 维持上限并写进文档，按版本间隔保存历史快照列 10.x 候选） | 检查器跳过 Wow 的内置聚合（`schedule`），浸泡栈把 `wow.query.state.max-replay` 设到足以回放整段日志；修复后在 Actions 上派发一次 `soak.yml` 验证 | 已修（#5210）：回放只覆盖各上下文 Manifest 声明的聚合类型（内置聚合不在其中、也没有 trace 路由；取不到 Manifest 记为违例），「快照停在最后版本」仍覆盖全部聚合；浸泡栈的 `wow.query.state.max-replay` 由负载推出（每单至多两次库存提交加种子、再乘 2，一小时默认 57,618），每个库存仍从版本 1 完整回放。分支上的完整浸泡（运行 41023426608，一小时、24 次故障）九条不变式全部通过、0 违例，`soak-a` 22,095 个版本完整回放。备注中的产品问题已定为 D376（GA 维持上限并写进文档，历史快照列 10.x） |
| X-32 | P2 | eShop Web 的命令流显示 | 运行 40637007000 中服务端的 SSE 流把结账的 15 跳全部发为成功（`"status":"succeeded"`），页面却把第 1 跳显示为「Failed」。起因：该运行测的是 #5087（D352）的第一个提交，它只改了 JVM 的写出，页面仍以 `=== "SUCCEEDED"` 判断，读流在第 1 跳停下；#5087 合并前已改正，main 无此缺陷。根因：浏览器以下没有测试把 TS 客户端的枚举拼写与 JVM 的写出对齐，`status` 的类型为 `… \| (string & {})`，过时的字面量照样通过编译 | 跨语言金样：`:api` 的 `TypeScriptWireNamesTest` 守住 `typescript/wow-client/test/fixtures/wire-names.json`（客户端手写声明的 22 个枚举的线上名，不含读方才有的 `UNKNOWN`，只重生成不手改），wow-client 的 `wireNames.test.ts` 把声明的取值与之对齐；eShop Web 改用 `CommandResultStatus` 常量，不认识的状态原样显示、不冒称失败；录制流回归测试 | 已修（#5211） |
| X-33 | P2 | `:spring-boot` 的传输推导、`InMemoryTopics` | M5 Max 上的 Axon 对比（#5225）里，「Kafka 关闭」的会话用空的 `--wow.kafka.bootstrap-servers=`；基准节点的内部端口在回环地址上，算开发模式，`TransportSelection` 于是选了内存传输，而 `InMemoryTopics` 留着每一条记录，1 GB 堆在并发 256 时耗尽，W 与 W32 的扫描作废（本机与 M4 Pro 的两次对比没有这样）。生产环境不受影响：开发模式之外，需要主题的节点不能启动。两处缺陷：内存主题没有上限；开发模式下没有「不要主题」的写法（`wow.transport` 没有 `none`） | 内存主题有上限（各读者都读过的记录即丢弃，或按分区封顶并告警一次）；接受 `wow.transport=none`；基准的「Kafka 关闭」改用它 | 已修（#5231：内存主题按分区封顶，默认 1,000 条、超出丢最旧并告警一次，键 `wow.memory.topic-retention`；`wow.transport=none` 在任何配置下都不要主题；基准的「Kafka 关闭」改用它） |
| X-34 | P2 | 集群路由、内核的冲突重试 | M5 Max 饱和快照（#5224）里 3 节点同机、PostgreSQL、并发 1024 时，E1 有 7 条、E3 有 9 条命令答 409 `wow.command.contended`（并发写者一再抢占版本号）；按设计一个聚合只有属主在写，疑为单机过载下租约交接、转发失败后退回本地执行而两个节点同写；该运行没有保留节点日志 | 在 Actions 的多机集群运行（L4 的 `e5-*`）与 GA E2E 中核查是否出现；出现则保留节点日志定位 | 复现、按设计关闭（#5303，M5 Max 快照 2）：同机三节点在并发 1,024 下仍有少量 409；与「转发确定未送达属主时降级为不持租约的本地执行」相符（D223：已发出后超时不降级，答 503），两写者由存储的版本检查拒绝其一，结果安全；未能逐条对上日志，故记为推断。同机过载下内部 HTTP/2 拒绝新流另记 X-55。快照 3（#5334）：PostgreSQL 共 37 个、MongoDB 1 个，仍未逐条对上日志，仍是推断 |
| X-35 | P2 | Kotlin 的 ABI 转储 | 全部参数有默认值、主构造器标 `@InternalWowApi` 的类，Kotlin 生成的无参构造器在干净编译下可能丢掉该标记，转储多出一个公开的 `<init>()`；增量编译保留标记，故本地通过而 CI 失败（4c 的 `ParkedRetryOptions`，#5227） | 内部改用该类的 `@InternalWowApi copyWith`，不在别处调用其构造器；转储中出现意外的公开无参构造器时先疑此因 | 已绕开（#5227），记作已知现象 |
| X-36 | P2 | `:runtime` 测试 | `SubscriptionOrphansTest` 偶发 `ConcurrentModificationException`：测试挂在日志上的 `ListAppender` 的列表是普通 `ArrayList`，停止中的运行时的协程仍可能在写日志，测试读取时不加锁；产品代码无问题（`QueryObserversTest` 同样的读法） | 在追加器的锁内取快照后再筛选 | 已修（#5241） |
| X-37 | P2 | 测试工具的开关 | 重新生成快照与金样的开关借用 `wow.` 前缀（如 `-Dwow.snapshot.update=true`）；整套检查带上它时，Spring 宿主把它当作未知的 `wow.*` 键拒绝启动（D302），宿主测试全部失败 | 测试工具的开关移出 `wow.` 命名空间（如 `wow-test.snapshot.update`），或只由各测试读取而不进 Spring 环境 | 已修（#5263：会进测试 JVM 或被测试读取的开关一律改用 `wow-test.` 前缀，构建脚本、工作流与文档随之改名；`wow.internal.host` 是真实的配置键，只在 Gradle 里用的 `-Pwow.*` 构建属性不进测试 JVM，均保留；架构测试的 `wowSwitches` 规则挡住新的 `wow.` 开关） |
| X-40 | P2 | 内存传输（开发用）过载时的应答 | M5 Max 上 8 GB 堆的 Axon 对比（#5254）中，Kafka 地址留空的会话走内存传输，并发 4,096 时除 429、504 外还有 21 次与 11 次 HTTP 500 及 EOF、超时异常；这些运行早于 X-33（#5231）给内存主题加上限 | 在 X-33 之后的构建上复现：过载应答应为 `wow.command.busy` 或等待超时，不是 500；若仍有 500，找出未分类的异常 | 未复现（#5303）：当前构建上 8 GB 堆的内存传输与 `wow.transport=none` 过载时只答 429 与 504，0 个 500；与 X-33 的有界主题相符，不能据此证明。#5334 的 8 GB 会话都用 `wow.transport=none`，不经内存传输，其 0 个 500 与本项无关，不增加证据 |
| X-41 | P2 | CI 范围：文档里的查询示例 | #5253 加的 `DocumentedQueriesTest`（以路由自己的解析器读文档、技能与 README 中的每个 JSON 查询示例）在 `:http` 的测试里，只改文档的 PR 不跑 JVM 任务，写错的示例要到下一个 JVM PR 才被发现 | 用户 2026-10-30 批准改 CI：改动文档、技能或 README 的 PR 也跑这一个测试（小的限定作业，`ci-gate` 要求它） | 已修（#5259：只改文档、技能或 README 的 PR 在架构作业里多跑一步 `DocumentedQueriesTest`，不多占 runner；`ci-scope` 的测试从该测试的源码读它读的目录，保证每个页面都被覆盖） |
| X-42 | P1 | 拒绝并提交事件时的预留 | 文档最终重写（组 A）核对 guide/rejections 时发现：`reject(code, message, events)` 提交事件时，传输里仍带着这次尝试的预留，预留订阅可能确认内核已放掉的键，与「拒绝释放它的预留」矛盾；未经测试 | 先以测试证实或否定（内存后端：预留一个键后带事件拒绝，订阅处理后键应已释放、别的聚合能预留）；属实则拒绝的提交不带待确认的预留，各后端一致；顺带改正 `CommandIntake` 与代码不符的 KDoc | 已修（#5280：属实，两种情形都由测试复现——预留的键被订阅为已拒绝的聚合确认、别的聚合预留不到；决定里释放的键被订阅真的释放；拒绝的提交不再在传输里带键，单条与批量追加两条路径都覆盖；预发布数据不保留，已写入的旧记录不设防；顺带改正 `CommandIntake.unhandled` 的 KDoc） |
| X-43 | P1 | Saga 的回执处理函数 | 文档一致性核对（区域 1）发现：`@OnRejected`/`@OnCommitted` 处理函数放出 `context.call` 的拒绝时被当作可恢复失败重试，而 `@OnEvent` 路径上同样的拒绝交给 `@OnRejected` 或立即停放（`SagaReceipts.kt:95-123` 直接调用，对照 `SubscriptionBindings.kt:357-383`）；未经测试 | 先以测试证实；属实则回执处理函数按 `@OnEvent` 路径同样分类（回执处理函数里的拒绝不能再交给 `@OnRejected`，预计立即按不可恢复停放） | 已修（#5288：属实；回执处理函数放出的调用拒绝立即按 `CallRejectedHandlingException` 停放——补偿不补偿自己；`@OnCommitted` 那一跳以 REJECTED 结束） |
| X-44 | P1 | 内核的决定纯度检查与状态缓存 | 不带事件的拒绝在纯度检查之前返回（`Kernel.kt:623` 早于 `:632`）：改了状态再拒绝的决定函数通过测试，且 `RequestCheck` 把改过的状态放回缓存，下一个命令在没有事件产生的状态上决定；未经测试 | 先以测试证实；属实则每个决定都做纯度检查，拒绝从不把改过的状态留在缓存 | 已修（#5286） |
| X-45 | P1 | Saga 派生命令的 Bean Validation | `DerivedCommands` 与 `CommandIntake` 绕过准入的校验器，派生命令不校验；文档与 `SubmitOrder` 的 KDoc 承诺每个命令都校验；未经测试 | 推荐：派生命令与 HTTP 命令同样校验，违规是该命令的拒绝 `wow.command.invalid`（带违规项），由 Saga 的 `@OnRejected` 收到；若有理由不在此校验则改文档 | 已修（#5286；定时器发给自身的命令见 X-51） |
| X-46 | P2 | `Rejection.of`/`Decision.reject` | 用 `wow.*` 错误码拒绝时抛普通的 `IllegalArgumentException`（`Decision.kt:150`），调用方得到 400 `wow.command.invalid` | 领域借用框架错误码是 Wow API 的误用：改用 `Misuse`（`requireUse`），命令失败为 `wow.command.failed` | 已修（#5288：`Rejection.of` 对 `wow.*` 与空错误码以 `requireUse` 拒绝，命令以 `wow.command.failed` 失败） |
| X-47 | P2 | Manifest 兼容性门禁 | 每个聚合都列出 events 查询，`Exposure.events` 永不为空，「提高 `@Revision` 并配 `@Upgrade`」永远不能让事件的改动变为兼容（`ManifestCompatibility.kt:449-487`，而 `ManifestTools.kt:186` 这样建议）；有读者时约束收紧与放宽都算破坏 | 先由子会话写分析与提案（门禁对事件要保护什么：存储的历史经升级读取，还是对端读 events 查询；各条规则应如何；最小改动），协调者定后再实现 | 已修（D381，#5287） |
| X-48 | P2 | JVM 侧，一致性核对（区域 2、3）发现 | 订阅名拼错抛普通 `IllegalArgumentException`，不是配置异常与失败分析；分区暂停的健康详情带异常消息（文档说只给类名）；`secondaryStores` 健康组件不在 Actuator 的列表；`migrate-data` 的 `env:NAME` 只对部分字段生效；一批过期 KDoc（部分进生成的配置参考页）；`guide/reading-state` 描述的状态应答形状与路由不符 | 代码按文档改（配置异常、只给类名、补上组件、`env:` 覆盖 `jdbcUrl` 与 `username`），改正 KDoc 并重新生成参考页，改正 reading-state 页 | 已修（#5294：订阅名不匹配与不带上下文的键报 `WowConfigurationException`；分区暂停的健康详情只给异常类名；`secondaryStores` 进 Actuator；`env:` 覆盖所有连接字符串、库名、JDBC 地址、用户名与密码；过期 KDoc 改正并重新生成参考页；reading-state 按快照记录逐字段列出） |
| X-49 | P2 | TypeScript 侧，一致性核对（区域 3）发现 | `wow-client` 不再拒绝空 ID，请求发往 `//`；`wow-generator` 把只有 OpenAPI 客户端才用的 fetcher 包列为必需的 peer；`QueryConstraintTypes` 缺 `aggregate-required`；视图引擎不执行「至多一个主操作」；几处过期注释 | 恢复空 ID 的 `TypeError`；两个 peer 标为可选；补上约束类型；第一个主操作画成按钮、其余按普通操作并在开发时警告一次；改正注释 | 已修（#5293：空 ID 抛 `TypeError`；两个 fetcher peer 标为可选；`AGGREGATE_REQUIRED`；第一个主操作画成按钮，多余的经 `onIssue` 报 `binding.action.primary-extra`） |
| X-50 | P3 | 编译器与内核：返回 `Decision` 而不列 `produces` | 返回 `Decision` 的命令函数不写 `@OnCommand(produces)` 时编译无提示（`AggregateAnalyzer.kt:289-300`），内核随之跳过未声明事件的检查（`Kernel.kt:1243-1249`）；只拒绝或无操作的函数不列事件本属正常，注解的默认空值无法区分 | 待定：编译警告（可抑制）、改为必须显式声明，或维持现状并写明 | 已修（D382，#5295：凡返回类型说不出事件且未列 `produces` 的命令函数——`Decision`、`Any`、类型参数、星投影，即内核跳过检查的情形——给警告，以 `@Suppress("UnlistedEvents")` 抑制；从库 jar 继承的处理函数不警告；仓库内无一处触发） |
| X-51 | P3 | 定时器发给自身上下文的命令 | X-45 之后派生命令与主题命令都校验，唯独上下文自己的定时器发给自身的命令仍绕过校验器 | 与派生命令同样校验 | 已修（#5295：定时器发给自身上下文的命令经同一组校验器；违规时该次触发的结果为 `rejected`，与现有被拒定时命令一样：计为已触发、不重试、不停放） |
| X-52 | P1 | 编译器：`collectBoundedContexts` 偶发空指针 | CI 上与改动无关的 PR 偶发失败：`@BoundedContext(name = 另一模块的常量)` 经 `getAnnotationsByType` 的反射代理读出 `name` 为 null（KSP 并发运行时常量未求值，见 X-54） | 按名字直接读注解参数，读不到即以指明声明的编译错误结束、不生成代码；`ops-domain` 与 `view-store-domain` 的 `name` 改写字面量并以测试锁定等于各自的 `*Context.NAME`；注解 KDoc 写明用字面量 | 已修（#5298） |
| X-53 | P3 | 编译器：其余经反射代理读的注解 | `@Name`、`@Description`、`@Revision` 同样经反射代理读取，遇到未求值的跨模块常量时悄悄退回默认值；仓库内目前没有触发 | 与 X-52 同样按名字读参数，读不到即编译错误；逐个核对 `:compiler` 主代码里其余的 `getAnnotationsByType`（`@Name`、`@Revision`、`@MountedCommand`、`@ErrorCode`、`retiredEvents` 等），并加一道防回退 | 已修（#5339：共用的 `AnnotationArguments` 按名字读参数，给了参数却没有值、必填参数未给即编译错误；`:compiler` 主代码不再用 `getAnnotationsByType`，`:architecture` 守住；Jackson 注解因决定 Manifest 的名字与路径一并纳入；数组参数中未求值的元素被 KSP 丢弃，只能写明限制；12 个跑处理器的模块生成结果逐字节相同） |
| X-54 | P1 | KSP2 同一守护进程内并发运行 | 多个 `kspKotlin` 同时运行时偶发把库类型解析为错误类型：标准库扩展与 Jackson 类型报未解析、跨模块常量未求值、未通过 `validate()` 的符号被推迟后静默丢弃，下游缺生成类（KSP 2.3.13 在守护进程内共用 IntelliJ 环境，缓存按线程） | Wow Gradle 插件以 `maxParallelUsages = 1` 的 `KspRuns` 构建服务串行化一个构建内的 KSP 任务（编译与测试仍并行），对用户的多模块并行构建同样生效；从未通过校验的模型符号改为编译错误而非静默丢弃 | 已修（#5301）。上游：google/ksp#3286 是同一回归，根因已查明（2.3.13 的库根范围改为比较文件对象，而每次执行结束都清空共享的 jar 缓存，并发的另一次执行便把有效的库文件判在范围之外），我们原先「按线程的缓存」的推测不对；2026-11-02 经用户同意在其下补了我们的证据（构建内并发的三种症状、串行化的做法，以及数组参数里未求值的元素被丢弃），不另开 issue。KSP 修复后升级并去掉 `KspRuns`（路线图 §6「条件到达时做」） |
| X-55 | P2 | 内部 HTTP/2 客户端的连接与流上限 | M5 Max 快照 2（#5303）同机三节点、并发 1,024 时，内部端口上的 HTTP/2 报 `too many concurrent streams`，丢弃了 1,300 多个等待信号，转发也因此失败（进而降级或答 503）。快照 3（#5334，`fe6f38f224`）补证，至少有两种机制，都只是时间上的相关、未做对照实验：① `too many concurrent streams`（`HttpSignalSender` 丢弃等待信号，日志每 100 个记一行）只在 E3 出现，最早在 E3 扫描的并发 256，累计丢弃 PostgreSQL 三个节点 4,500、4,400、200 个，MongoDB 2,100、2,900、1,700 个；② E1 在并发 1,024 的 503（PostgreSQL 2,151 个）、504 与 MongoDB 的一个 409 早于任何这类告警，同时段节点报 Netty 的 `Http2Exception: Maximum number of RST frames reached`：服务端防快速重置的 RST 帧上限关闭了整条内部连接（两个集群的 node-1、node-3，都在 E1 并发 1,024 的时段），快照 2 的日志里也有（三份日志共 8 次） | 派工前定方向：① 内部客户端在对端的流上限处排队或另开连接，不丢信号；② 查清谁在大量重置流（转发或等待超时后的取消）与服务端 RST 帧上限的取值，内部端口是否另设（D298 视服务内部为受信，但该上限是防护，不轻易放开）；③ 以限定并发数的集成测试在本机复现两种机制（不是负载测试），修复后在本机（M5 Max）复跑三节点 E1、E3 | 已修（#5340：内部端口显式通告 100 个流，转发最多占 84 个、信号 16 个；超出的转发排队，不再失败，排到期限按 unknown，不降级；信号遇流上限算暂时失败，退避重试；转发的流由属主的答复结束，HTTP 超时为期限加 `ANSWER_GRACE` 2 秒，作为兜底；Actions 4 核 runner 上三节点 E1 并发 1,024：main 918/s、p99 8.3 s、503 ×1,129、`NOT_HELD` 降级 10,436 次，修复后 1,650/s、p99 1.6 s、0 错误、0 降级；本机 M5 Max 的确认复跑待 X-60 用完本机） |
| X-56 | P1 | GA 端到端夜跑：结论步骤列订单得 403 | 10-31 起 `ga-e2e` 两个存储分支都失败：33 项检查通过后，结论步骤以员工身份 `POST /eshop-ordering/order/snapshot/query` 列订单，被 CoSec 网关拒绝（403 `FORBIDDEN`，请求未到 ordering）。D373（#5260）起订单号由 ordering 发放，harness 不再事先知道订单号，于是改为列出订单，但网关文档的 `employee` 策略没有随之放行 | 错在策略一侧：`employee` 策略补上订单的 `snapshot/query`，与同一策略里 catalog 的 `product/snapshot/query` 同理；harness 经网关的其余路由逐条核对无缺失，买家策略不变 | 已修（#5325，2026-11-02 合入）；合入时验证运行未结束（41389108033 的 postgres 臂因 runner 磁盘写满中断，与修复无关；41390273199 带 #5325 与 #5327）；结论步骤随即暴露 X-59，#5330 之后运行 41402695083 两臂全绿 |
| X-57 | P1 | 夜跑失败没有开 issue（D375） | GA 端到端连续两晚失败，却没有 `nightly-failure` issue：`nightly.yml` 以 `GITHUB_TOKEN` 分派，GitHub 不为 `GITHUB_TOKEN` 发起的运行发 `workflow_run`，`nightly-issues` 对夜跑从未运行（大量 `skipped` 是 `ci.yml` 的 push 运行，按设计跳过）；手动分派的运行不受影响。脚本本身无误，对两次失败 dry-run 均会开 issue | 用户 2026-11-01 选定时巡检（不加 App 令牌或 PAT）：`nightly-issues` 加 01:30 与 06:30 UTC 的定时触发与手动触发，脚本 `--sweep` 取四个工作流在 `main` 上 36 小时内最近一次完成的分派运行，交给原有逻辑，按运行 id 去重；`workflow_run` 保留，照常报告手动分派 | 已修（#5326；合入后手动巡检一次即开出 #5328。已知且接受：两次巡检之间有人手动关闭 issue，06:30 的巡检会为同一运行再开一个，因为只读打开的 issue；手动分派的 `workflow_run` 报告与巡检不在同一并发组，同一时刻重叠时可能各开一个） |
| X-59 | P1 | GA 端到端：结论步骤列订单得 400 | X-56 放行后，两次证明运行（41389108033、41390273199）都在结论步骤得 400 `wow.query.invalid`：harness 每页请求 1,000 个订单，而 HTTP 查询的分页上限是 100（同样随 D373 #5260 引入）。其余检查全过：扩容窗口 0 ms 且按时稳定、降主通过、存储 Pod 无重启，X-58 得证 | harness 每页取 100（`ORDER_PAGE`），循环照旧翻页 | 已修（#5330，协调者直接改的单行常量）；GA 端到端运行 41402695083 两臂全绿，X-56、X-58、X-59 一并得证 |
| X-58 | P2 | GA 端到端：存储 Pod 被 OOMKilled 而运行未报 | 11-01 的 mongo 分支里 `mongodb-0` 被 OOMKilled 两次、主节点 `mongodb-2` 一次（上限 768Mi，WiredTiger 缓存 0.25GB），检查点采样器的 mongosh 因此 exit 137；同一运行扩容窗口 1,051 次结账（此前 83～97），mongod 峰值连接 239～271（此前 52～170）。「no container restarted」只看服务，「step down the primary」测到的其实是主节点的 OOMKill | 先查结账量跳升十倍的原因（负载生成、窗口长度或节奏、10-30 以来的改动），是回归就修回归；再按 harness 预期的负载定存储内存与各服务连接池（先收紧连接池，加大上限为后备）；容器重启检查覆盖存储 Pod（MongoDB 与 PostgreSQL），降主检查能分辨 OOMKill | 已修（#5327，2026-11-02 合入）：结账量跳升是 harness 的问题、不是回归——扩容窗口的「稳定」要求每个节点都持有分区，而 Wow 按 HRW 分配分区只分散不均衡（8 个分区 3 个节点约八分之一的 Pod 名组合有节点分不到），窗口跑满 10 分钟上限、负载一直在跑；OOM 是 harness 在成员容器里 exec `mongosh`（Node.js 进程与 mongod 共用 768Mi）。改为租约按 `Hrw.owner` 在就绪 Pod 中的分配持有即稳定（节点 id 即 Pod 名，HRW 不掺组名，已核对），从未稳定的窗口判失败，`mongosh` 移到独立的 `mongo-shell` Pod，存储 Pod 的重启与被 OOMKill 的主节点都使运行失败；存储规格不变。证明运行 41390273199 中扩容窗口 0 ms、按时稳定，降主通过，存储 Pod 无重启；41402695083 两臂全绿 |
| X-60 | P1 | MongoDB 上多节点的跨聚合流程吞吐 | M5 Max 快照 3（#5334）同机三节点、MongoDB 时，E3（转账：命令、Saga、另一个聚合的命令）峰值只有 506/s，在并发 16，其后随并发下降（64 时 398、256 时 341、1,024 时 289，定并发 1,024 为 209），并发 256 之前没有任何错误；单客户端即只有 70/s、p50 11.5 ms。同样条件下 PostgreSQL 三节点峰值 5,563/s（单客户端 299/s、p50 3.3 ms），MongoDB 单节点 6,226/s（单客户端 203/s、p50 4.5 ms），MongoDB 三节点的 E1 为 11,886/s。快照 2（#5303）形状相同（并发 16 时 663/s），两份报告都没有提。吞吐塌落的并发 16～64 节点日志没有告警（分区暂停最早出现在并发 1,024），同机共用一个 mongod 不足以解释（PostgreSQL 同样同机） | 先定位：单客户端即慢，先量每一跳的耗时，看跨节点时下一跳如何被唤起（Kafka 消费、日志尾读的轮询、等待信号）；再查为何并发越高越慢（租约、分组提交、重试）；以 GA E2E 的 mongo 臂或 Actions L4 核对是否只在同机出现；修复以三节点 MongoDB 的 E3 复跑证明 | 已修并验证（2026-11-03：F1 #5337，属主的尾读丢弃别的节点写陈旧的状态；F2 #5357，MongoDB 分组改为无序插入，冲突只伤冲突的那条流；本机 M5 Max 以「main 加修复、去掉 #5340」复跑「E1 并发 1,024 → E3」，E3 不再变慢，结果见 #5357 的评论）。其后 M5 Max 快照里 MongoDB 争用键下的变慢与这两个修复时间相关，另登记为 X-68 |
| X-61 | P2 | 路由器在转发期限上的结局 | X-55（#5340）查明：① 属主在等待期限撤回仍在队列里的命令并给出明确答复（`WAIT_TIMEOUT`，未执行），但 `CommandRouter` 在同一时刻停止等待，报为 503 `wow.command.unknown`；② 因对端流上限在通道里排队、从未发出的转发，到期限时也被报为 unknown（若通道按 `unsent` 失败，还会降级为本地无租约执行，正是 X-34 两个写者的路径，审查已要求 #5340 改为不降级）。两者都把明确的结局报成了「未知」 | 在发送前的阶段先占一个对端的流（与「期限内未连上 → unsent；等待已过 → `WAIT_TIMEOUT`」同处）；期限之后稍等属主的明确答复（与 #5340 的 `ANSWER_GRACE` 对齐），再报 unknown；以单元测试钉住两种结局 | 已修（#5355：发送前先占流，排到等待期限为 `WAIT_TIMEOUT`，只过了转发期限为 429 busy，不降级；期限后最多再等 `ANSWER_GRACE` 取属主的明确答复） |
| X-62 | P2 | `test-spring-boot` 的 `wowServer` | 同一个 JVM 里关闭一个 `wowServer`，其他测试服务器的端口随之停止：reactor-netty 的全局事件循环被关闭的那个释放了。多个服务同 JVM 的测试（D389 的假对端、多服务运行器）都会撞上 | 每个测试服务器用自己的 `ReactorResourceFactory`；以测试钉住「关一个不影响另一个」 | 已修（#5346：每个测试服务器用自己的 `ReactorResourceFactory`，关闭一个不影响其他） |
| X-63 | P2 安全 | 平台服务的命令是否该是内部命令 | PA-35（D388）落地时发现：9.x view-store 有四条以 `@CommandRoute(enabled = false)` 关掉 HTTP 路由的命令（`SetViewPreferences`、`SetDatasetWording`、`ClearDatasetWording` 与一条 claim 命令）；Wow 10 的平台服务（ops、view-store、analytics catalog）对应的命令今天都在公开端口上，只靠授权挡 | 逐个审视平台服务的命令：只由 Saga、定时器或其他服务发出的标 `@InternalCommand`，并补测试；用户真正要调用的保持公开 | 已修（#5370：六条命令改为内部命令，ops 的 `RecordParked`、`RecordResolved`、`RecordActionRefused`，分析目录的 `SyncSource`、`RecompileDataset`、`DropDatasetViews`；其余保持公开，因为控制台或浏览器在发；公开端口对内部命令答 404，发送方（台账主题、Saga、定时器、目录的监视器）照常工作；9.x 那四条关掉路由的 view-store 命令其实面向用户，D388 的迁移规则据此修订；关掉监视器的部署不再能手动同步数据源，已写入文档） |
| X-64 | P3 | 用户模式的对端客户端发送内部命令 | `edge/client` 以用户模式（`PeerMode.USER`）发送对端的内部命令时，要等对端答 404 才知道 | 客户端按对端契约的 `internal` 在本地先拒绝，给出指明命令的错误 | 已修（#5365：客户端按对端契约的 `internal` 在本地先拒绝，错误指明命令） |
| X-65 | P3 | `test-spring-boot` 的计时类测试 | `TypedClientsTest` 读 `Retry-After` 的 HTTP 日期时，两次读钟之间跨过整秒即得到不同的秒数，偶发失败；`WowServerTest` 以墙钟断言关闭耗时（`measureTime < 2.seconds`），在繁忙的机器上不稳 | 用过去的日期，两边都钳到 0，与读钟时机无关；关闭耗时改为确定性断言：以 `BeanPostProcessor` 在 Boot 自己的工厂初始化前关掉全局资源，经公开 API 断言静默期 | 已修（#5359） |
| X-66 | P1 | 基准脚本的测试动了宿主上的真实进程 | `bench.test.mjs` 的交接测试以 `pgrep -f` 查找并发信号，结束了宿主上别的会话正在跑的测量进程（本机 X-60 的一次测量因此作废、重跑） | 进程的查找与发信号改为可注入（`HOST_PROCESSES`），测试里用假的；`hostProcesses()` 在 `NODE_TEST_CONTEXT` 下拒绝，测试文件在测试运行器之外拒绝运行；同类调用全仓排查 | 修复中（#5363） |
| X-67 | P3 | 过载降级之后 E3 第一步的等待 | X-60 在本机的验证中，E1 过载（转发降级为非属主执行）之后，E3 第一步（并发 1）出现约 2.6 s 的等待；只在会降级转发的过载之后出现，#5340 之后 E1 并发 1,024 不再降级，触发概率很低 | 下一个本机窗口用分阶段计时，对比「F1、F2 都开」与「只开 F1」 | 待查（本机窗口） |
| X-68 | P1 | MongoDB 争用键下的吞吐 | M5 Max 快照（#5366，测于 `b435768e49`）的单节点 MongoDB：E1 Zipf 在并发 1,024 为 2,672.6/s，前两轮为 15,171.4/s 与 15,296.6/s；单一热点账户 1,634.7/s，前几轮约 9,650/s；本机只用了 2～4 核，像在等待。PostgreSQL 的三种键与 MongoDB 的均匀键都没有这个现象。时间上与 X-60 的 F1（#5337）、F2（#5357）相关，尚无对照把它们分开 | 先在 Actions 上以 ABAB 对比 `e57b42e6df`（两个修复之前）、`c973fb9d4c`（F1 之后）、`1357642891`（F2 之后）复现并定位，有证据再改；修复须保住 X-60 的两个目的（陈旧状态最迟约 1 秒愈合、分组里一条流冲突不连累其他流）；Actions 复现不了再安排本机窗口 | 已修（#5368：先整组有序插入一次，冲突时没走到的流分两轮；热点账户对 main 2.55 倍、Zipf 1.63 倍，对 F2 之前在噪声内，第二次 Zipf 采样确认） |
| X-69 | P2 | PostgreSQL 单节点 E1 过峰值后吞吐下降 | 同一快照：E1 均匀在 4,096 个客户端为 32,303.0/s，8,192 个时降到 20,823.6/s（−35.5%），只有 2.1% 被 429 拒绝（约 446 次/s，不足以解释）；全机 CPU 反从 12.6 核降到 10.4 核，像在等待；扫描不记各进程的资源。推测之一是 JDK 21 上 JDBC 的虚拟线程被钉住（D368），未证实 | 在 JDK 25 上（D393）以 `bench.sh run` 测 4,096 与 8,192 个客户端，记下节点、GC、PostgreSQL 各自的占用与线程转储 | 待查（D393 落地后的本机窗口） |
| X-70 | P3 | CI 没有在 JDK 25 上测试 | D29 定「CI 同时在 25 上测试」，D368 落地时 CI 的测试 JVM 保持 21，现在没有任何 job 在 25 上跑测试，而生产推荐 25 | 在 main 推送或夜间运行上加一个 JDK 25 的测试 job（改工作流，须用户批准），或修订 D29 | 已修（#5417：JDK 25 的测试腿只在 main 推送与夜间运行上跑，PR 上不跑；25 上唯一的失败是日期模式语料记着生成它的 JDK，比对时去掉该行；迁移演练的 V10 一侧也改用 25，#5415） |
| X-71 | P3 | 负载生成器的单元测试不在 CI | `:benchmark-load` 不在任何检查分片，其单元测试与 detekt 从未在 CI 跑过（`Report.kt` 的过长函数因此未被发现） | 加入 rest 分片，拆短该函数 | 已修（#5388） |
| X-72 | P2 | 同速率下的延迟不稳 | Actions 上的 Axon 报告（#5395）：E1 1,000/s 时 Wow 两个会话的 p50 为 17.5 与 3.7 ms（Axon 1.2 与 1.5 ms），E3 类似；另：每命令 WAL 约为 Axon 的 2 倍，每事件存储 626 对 480 字节，启动 7–11 s 对 4 s、常驻 400 对 290 MB | 先查延迟：分阶段计时与 JFR（追加的批处理等待、分区节流、GC 等假设，未证实）；其余三项登记为改进项 | 已修（#5420，D398：快照订阅的批量写在被覆盖时不等 WAL 落盘；写入限速 1,200/s 时 E1 p50 4.1–4.3 → 1.4 ms，与 Axon 持平。存储与启动的差距另行排队） |
| X-73 | P1 | 迁移追尾漏流 | 50 万订单的切换测试：9.x 持续写入时一次追尾读不完、游标又无回看，提交早而可见晚的流被跳过（9 条 view 流）；`compare` 也未报出 | 每次追尾止于开始时最后提交的流，下一次从回看窗口重读（#5409）；`compare` 核对追尾跟随过的每种聚合的流都在日志里（#5410）；PR 中写明受影响版本与用户应做之事 | 已修（#5409、#5410） |
| X-74 | P2 | GA E2E 的 MongoDB 序号「重复」 | 4,267 处「同一序号两次」全是封存占位：D390 的回填为无历史的内置快照聚合建了空日志集合，此后每次封存在每个集合各写一份占位；检查器把占位算进了重复。无事件丢失或重复 | 检查器只数流（#5407）；回填不为无历史的类型建集合、表或注册项（#5408） | 已修（#5407、#5408） |
| X-75 | P2 | 导入后的首次激活慢、追赶饿死线上命令 | 5 万订单：首次激活 26–70 s（之后重启约 1 s），因各分区一启动就追赶导入的历史、与仍在启动的分区争连接；改为先启动后追赶后，激活约 1 s，但追赶期间 6,000 单中 2,000 余单在接收后超时（JDK 25 上同样，是连接池而非钉住） | 先启动全部分区再放追赶（等待有上限）；每节点同时追赶的分区数设上限，消除接收后超时，或改为明确的忙拒绝 | 已修（#5399，D399） |
| X-76 | P2 | 在线切换后，策略下的事件查询暂时看不到导入的历史 | F 的演练（#5429，5 万订单）：访问遍历给导入的历史盖章用了 187–220 s，约 250 个聚合/秒（`wow.access.walk-rate` 为 500/s，节奏由存储决定），比在线切换的停写（78–103 s）还长；遍历只在启动或激活时开始，待命节点不遍历。其间受策略或按属主、空间限定的事件查询看不到导入的历史（状态读取与快照查询不受影响），规模越大越久 | 遍历按页批量盖章，让限速真正起作用，前后实测；评估待命节点在双跑期间遍历并跟随（`wow.access`），须在导入的并发追加下正确，且激活之前不对外服务；做不到则给出仅提速时的上界 | 已修（D400：#5438 日志的批量盖章，#5431 由 `migrate-data snapshot` 盖章并记下遍历完成；最终演练 41954891177 两臂通过，激活时 0 条未盖章流、0 次遍历认领，待命与激活后策略下的读取正确；在线切换停写 +2.2 s。遍历本身的按页批量未合并，见 X-77） |
| X-77 | P2 | 访问遍历期间线上命令的尾延迟 | #5432 的规模检查（10 万账户，每秒 200 笔存款）：遍历期间线上 E1 的 p99 在 PostgreSQL 上由 5.5–6.1 ms 升到 370–500 ms，MongoDB 上由 47–57 ms 升到 392–968 ms，500、1,000、2,000/s 都在 A/A 噪声带外（PostgreSQL 的中位数仍约 2 ms，MongoDB 升到 60–80 ms）；速率越高 p99 反而越低，像是按批而非按速率 | 先以 main 的遍历在同样负载、500/s 下对照，判定是否批量改法所致（#5432 合并的门槛）；逐秒采样存储的等待事件与连接池，另跑遍历与存款不相交的一臂，分清同一聚合上的争用与共享资源的争用；默认速率暂留 500/s，若无速率落在噪声带内且原因是共享资源，改为限制遍历占用的连接池份额或并发 | 已修（D402，#5552：遍历按时间份额定速，默认 25%，写压力下自动减速，历史读取走查询隔板；修正基准后判定通过。原诊断的数百毫秒主要来自基准本身与跟随者，PostgreSQL 上连不限速的遍历也不伤 p99，受影响的是 MongoDB。新增租约过期后接管的用例） |
| X-78 | P3 | 迁移演练 kind 一臂里 9.x 视图存储丢了快照保存 | 运行 41838225960：9.x 的视图存储丢了两次快照保存，#5413 的检查重读 30 s 仍落后，判为 STALE；runner 一臂通过 | 查明是 9.x 本身尽力而为的快照，还是 kind 环境；若是 9.x 的预期行为，检查改为按 9.x 由事件重建的状态比对，或计数并说明，不让演练失败 | 已修（#5437：9.x 在快照保存前确认命令、保存失败即丢弃，kind 一臂经 NodePort 的连接被重置时触发；检查改为计数并说明，不再判失败。kind 上哪一跳重置连接待查） |
| X-79 | P3 | CI 中偶发失败的测试 | 分析目录控制台 `readings.test.tsx` 等待首次渲染只有默认的 1 s（G2、G3 各失败一次）；`MongoTimeoutsTest` 的准备写入也在它要测的 200 ms 超时下进行 | 首次渲染单独给足等待；准备写入用默认超时，只把被测的读放在 200 ms 下 | 已修（#5433、#5434） |
| X-80 | P1 | 堆耗尽后 Saga 的通道停下、失败台账为空 | M5 Max 对比（#5442）：Wow 单节点 1 GB 堆，Zipf E3 的 8,192 客户端步之后、下一步开始时 OutOfMemoryError（两次会话中一次），发生在回放热点账户的事件时；TransferSaga 的订阅把它当致命错误停掉通道（11 次），2,443 笔转账锁定后不再推进，失败台账没有记录 | 两件事：一是 Zipf 过载下的堆占用（热点聚合的回放、状态缓存与在途批次的上限）；二是 OOM 这类资源错误不应静默停掉通道：应退避重试或进入失败台账并告警 | 已修（#5531：致命的 JVM 错误让节点限时退出全部租约组、交出分区，交不完则不再续约，由对端在租约到期后接手，存活与就绪探针保持 DOWN 直到进程结束；`StackOverflowError` 按处理器失败重试并停放；镜像与 chart 默认 `-XX:+ExitOnOutOfMemoryError`。#5532：堆占用的主因是 Saga 的状态从版本 1 重建时，后端把整段历史一次读进内存（热点账户约 24 万个流）；PostgreSQL 与 MongoDB 的读取改为每页 512 个流，同样的过载下老年代不再顶满，Full GC 59 → 14。#5533：同一聚合的重建单飞并走日志存储的查询隔板（Actions 上 A B B A：过载档峰值 −2.4%，在噪声带内；单飞在 Zipf 过载下只命中 0.3%，价值在于重放受隔板约束）；过载下的重建来自落后读者的视图被逐出、快照已越过，见路线图 §6 性能第 21、22 项） |
| X-81 | P3 | 基准：节点停不下来 | `bench.sh down` 发出停止后，一个 Wow 节点又跑了约 71 s，读已删掉的库，与下一个会话的种子、预热重叠（未影响计时） | harness 等节点真正退出再开下一个会话；同时查节点为何停机慢 | 待定 |
| X-82 | P3 | 基准：压测端在途上限 | 开环压测端每个目标最多 2,000 个在途命令，超出记为失败（`generator-overload`）；Wow 的 E3 自身份额（5,800/s）与 Axon 的一次停顿因此判为没跟上，E1 自身份额让压测端最忙的线程到 0.73–0.74 个核 | 本机要测 18 核的开环上限，提高该上限或用多个压测进程；报告区分压测端饱和与服务端失败 | 待定 |
| X-83 | P2 | soak：`partitions.owned` 误报 | 夜间 soak 自 2026-11-03 起每晚失败（#5419），违例全是 `#access-walk/<类型>` 租约：遍历完成后释放租约，行仍在，检查器要求每一行都有 owner | 遍历租约改查活性规则：遍历未完成时必须有活的 owner，完成后释放不算 | 已修（#5567，分支上的 soak 24 次故障、9 项不变式 0 违例；检查器未碰运行时代码） |
| X-84 | P3 | 夜间全量 CI 的覆盖率趋势 | 夜间 `ci.yml` 自 2026-11-04 起只有 Coverage trend 判失败（#5441）：集成覆盖率一周 82.11% → 80.53%（−1.58 点，阈值 1 点） | 查是哪些模块降的：补测试，或说明原因后等基线滚动 | 2026-11-08 的夜间全量 CI 通过，#5441 于 2026-11-09 由巡检关闭；降幅出在哪些模块没有查，再失败时查 |
| X-85 | P3 | 夜间 `mongo-cluster` | 最近五晚失败四次（#5569）：2026-11-04～11-06 是 `MongoDB sharded` 作业，11-08 是 `MongoDB rs` 作业，`cluster.sh` 报「the rs cluster did not start」，集成测试没有开始（没有测试报告），当时副本集刚发生一次选主；11-07 通过 | 查 `test/mongo-cluster/cluster.sh` 等待集群就绪的条件与时限，以及 sharded 那三晚的失败是否同因；是 harness 的问题就修 harness，是存储的问题另行登记 | 待查 |
| X-86 | P3 | `wow-client` 的体积预算 | Renovate 的 npm 非 major 升级（#5571）与锁文件维护（#4902）在 `wow-client` 的构建上失败：根入口 gzip 后 11,690 B，超过上限 11,650 B 40 B（+0.3%），其余 TypeScript 作业随之失败 | 原因已查明（2026-11-09）：打包器 rolldown 1.2.15→1.2.16（vite 8.3.6 带入）改了导入声明的顺序与局部短名，内容不变，根入口 gzip 多 74 B。保留新版打包器；客户端的几个 PR 合完后单独一个 PR 把上限调到实测值加约 1.5% 的工具链余量，再合并 #5571 与 #4902 | 待修（BA-5 的客户端 PR 之后） |
| X-89 | P2 | `HostParkedOpsTest` 偶发失败 | #5574 的 `Check (host)` 失败一次（重跑通过）：「an entry whose retries are spent waits for an operator」在 APPLY_RETRY_SPEC 之后等不到第 4 次尝试而超时 | 即 #5506 的交错落在应用重试规格上：扫描按旧内容算出「永不」，在修订之后落盘。随 D404 的 PR 关闭，并补一个确定性的测试 | 修复中（D404） |
| X-90 | P3 | 未送达的「已解决」记录在重启后丢失 | #5581 让发送失败的解决记录留在内存里由每次扫描重发，节点重启即忘：运维视图继续显示该聚合停放，直到运维在那里操作一次、所属服务答 `NOT_PARKED` 才收敛。与「重启后不记得发布过什么」同一限制 | 与 #5561（运维侧保留最新的已解决时间）一起看是否需要运维侧的对账；不在节点侧做持久化的发件箱 | 已登记（#5581 的 Known limits） |
| X-88 | P2 | 过大的类与文件 | 用户 2026-11-09：避免单个类过大。盘点（`main` `3fc1dc9aa1`）：1,342 个 Kotlin 主源文件里 32 个超过 600 行、10 个超过 1,000 行。承担逻辑的：`Kernel.kt` 1,467、`WowAutoConfiguration.kt` 1,098、`PostgresEventLog.kt` 1,068、`CommandGateway.kt` 1,041、`WaitCoordinator.kt` 964、`MongoEventLog.kt` 920、`PartitionOwnership.kt` 906、`SubscriptionWorker.kt` 858、`MigrateData.kt` 833、`EventStreamImporter.kt` 722、`QueryResolver.kt` 719、`SubscriptionRuntime.kt` 717；声明为主、按内聚判断的：`WowProperties.kt` 1,706、`AggregationQuery.kt` 1,510、`QueryViolation.kt` 1,102、`RelativeTimeFilters.kt` 1,048、`Manifest.kt` 878 与 TCK 规格。TypeScript：`wow-generator` 的 `analyze.ts` 1,054、`typeResolver.ts` 855、`manifestClients.ts` 823，`wow-client` 的 `builders.ts` 1,457，视图引擎的 `useWorkbench.ts` 846、`viewEngine.ts` 829、`WorkbenchShell.tsx` 869 等十余个 | 两步：现在起超线文件不得因修复再长（进度 §5 的审查标准），缺陷批次碰到的顺手抽出职责；承担逻辑的超线文件按职责拆分，每个一个不改行为的 PR，排在碰同一文件的缺陷批次之后（`Kernel`、`SubscriptionWorker` 在 BA-1、BA-3、BA-4 之后），拆法先报协调者 | 待排期 |
| X-87 | P3 | 仓库根目录的孤儿文件 | #5436 误提交了一个名为 `ow-cocache-on-cocache-8)#` 的文件（一段 PR 描述，373 字节），`check:hygiene` 不查这类文件 | 删除 | 已修（#5573） |
| X-38 | P0 | `migrate-data` 导入之后的发布流 | 导入后启用的服务，其传输 Sink（`SinkSubscription` 默认 `StartFrom.BEGINNING`）把整个日志、连同导入的 9.x 历史发布到 Kafka；对端订阅这条流的 Saga 与 Reactor 首次加入时（`JOIN`：尚未加入即从主题开头）读到全部历史，重新处理并再次发出命令，在服务群切换时造成重复的副作用。D379 的文档（#5243）暂以把对端订阅的 `start` 设为停写时刻绕开 | 协调者定（2026-10-30 修订）：传输 Sink 照常从头发布，但切换点以前的记录（`migrate-data` 每次追加后把各分区的 `log.head` 记为切换点，组 `wow.migrate/<context>`、订阅 `cutover`，只前进；导入的流若已带来源标记则以它为准）带「已导入」标记，为 Kafka 信封的加法字段；对端的 Saga 与 Reactor 跳过带标记的记录（9.x 已据此行动过），投影与数据 Sink 照常处理，使对端投影在切换后仍有历史；从未迁移的节点没有切换点与标记，行为不变；回滚导出后再次切换会重发期间重新导入的流，写进文档；测试：运行时单测加 `migrate-data` 集成测试的「导入 → 节点 → 对端 Saga 与投影」全链路；原定「传输 Sink 从切换点开始、不发布历史」放弃：对端投影会在切换后为空，9.x 读模型不迁移，没有别处可补 | 已修（#5251：切换点以前的记录带 `imported`，对端 Saga 与 Reactor 跳过、投影照常处理；PostgreSQL 与 MongoDB 上的导入至对端全链路测试） |
| X-39 | P1 | Kafka 主题名 | 9.x 的状态主题与 Wow 10 的发布流默认同名 `wow.<context>.<aggregate>.state`；在 9.x 用过的 Kafka 上不设 `wow.kafka.topics.prefix` 时，Wow 10 的订阅会读到 9.x 的记录，回滚后 9.x 也会读到 Wow 10 的记录（#5243 已在切换清单里提示） | 推荐：Wow 10 的解码器遇到不是自己格式（无格式版本）的记录即明确拒绝并指出 `wow.kafka.topics.prefix`，不按聚合停放；改主题默认名属线上契约，另议 | 已修（#5264：解码失败且不是 Wow 信封（带整数 `format` 与文本 `kind` 的 JSON 对象）的记录让该分区的读取失败，与偏移缺口的 `fail` 模式同一路径：订阅停在它之前、检查点不动、不停放，消息指出 `wow.kafka.topics.prefix`；Wow 信封但本版本读不了的照旧停放或越过；不扫描主题、不改默认主题名） |

## 4. 第三轮架构审查 R3（2026-10-19，基线 `751c87569d`，只读）

角度：职责、清晰、内聚与耦合、性能、可用性、扩展、易用，并复核 9.x 能力对照。整体判断：内核、日志与订阅的正确性扎实，问题集中在边界——宿主里的语义、默认值、读路径对写路径的影响、社区后端的接入。

| 编号 | 级别 | 位置 | 现象 | 去向 | 状态 |
|---|---|---|---|---|---|
| R3.1-1 | P1 | `:spring-boot`、`:query`、`:runtime` | 读侧与存储布局的组装（约 2.3k 行）连同安全语义（`hides = abac \|\| policies`、`events` 兜底后端、作用域策略拼装）在 Spring 宿主里，A10 对读侧不成立 | R3-A（D237） | 已修（#4595；余下的安全底线、读侧组合与对端路由由 R5.C-2 #4932 移出宿主，`:architecture` 的 `HOST_ASSEMBLES_THE_READ_SIDE` 守住；2026-11-02 复核） |
| R3.1-2 | P2 | TS `wow-client`、`wow-view-store` | 10.x 的 npm 包根入口仍是 9.x 客户端（V10 在 `src/v10` 子入口），GA 后用户导入的是 9.x 协议 | D239 ①（GA 前清理 9.x） | 已修（#4659、#4669） |
| R3.2-1 | P2 | `:postgres`、`:mongo`、`:kernel`、`:kafka` | `TailPacer`、`VirtualThreads` 等逐字复制于多个后端 | R3-A | 已修（#4591） |
| R3.2-2 | P2 | 横切 | 主代码注释引用审查编号 439 处（流程产物，原文可能丢失） | R3 文档与规则 | 已修（R5.C-7 去掉主代码注释里的编号，#5036～#5045；`:architecture` 的 `DesignIdRule` 守住生产源码；2026-11-02 复核） |
| R3.2-3 | P2 | `:query` | 公开 ABI 过大（`query.api` 2285 行，`schema` 包 122 个公开类），冻结面过宽 | R3-F | 已定（2026-11-02 复核：#4683 收窄之后，D350 ① #5092 有意公开存储词汇；现 `query.api` 2,278 行、`schema` 顶层公开类型 34 个，即 GA 的公开面，不再收窄） |
| R3.3-1 | P1 | `:spring-boot` | 社区后端接不进宿主：`BackendKind` 封闭枚举、`HostStore` 构造 internal，`BackendFactory` 全有或全无 | R3-A | 已修（#4591，`StoreProvider`） |
| R3.3-2 | P2 | `:query`、`:tck` | `QueryBackendSpec` 在不发布的 testFixtures，`QueryBackend` 无 `@WowSpi`，没有 Sink 合同 | R3-A | 已修（#4591） |
| R3.3-3 | P1 安全 | `:query`、`:spring-boot` | 没有快照查询网关时（`snapshot=false`、内存快照未开查询、应用自带后端），`events` 对 ABAC 与应用策略默认放行 | R3-S | 已修（#4580） |
| R3.4-1 | P1 | `:spring-boot`、`:query` | 日志不在 PG/Mongo 时 `events` 退回内存全量扫描，每次查询读全部分区，无开关 | R3-S | 已修（#4580） |
| R3.4-2 | P1 | `:postgres`、`:mongo`、`:query` | 写库上的 `events` 查询与追加共用连接池、无并发上限、单条最长 30 s，可拖垮命令 | R3-B | 已修（#4598、#4613、#4632） |
| R3.4-3 | P2 | `:runtime` | 同时登记的等待没有上限（默认 `SNAPSHOT` 等待持有连接直到超时） | R3-B | 已修（#4598） |
| R3.5-1 | P1 数据丢失 | `:spring-boot` | `wow.backend=AUTO` 找不到持久后端时只告警并退回内存存储，重启丢数据（9.x 启动失败） | R3-D | 已修（#4578） |
| R3.5-2 | P1 | `:runtime` | 系统性故障按单个聚合停放（约 3 s 重试后停放，3 分钟起重试），下游抖动即停放所有有流量的聚合 | P.16 并入 | 已修（#4592） |
| R3.5-3 | P1 | `:spring-boot`、`:runtime`、`deploy/` | 没有健康模型：无 `HealthIndicator`，`isReady` 只看 RUNNING，K8s 用 TCP 探针 | R3-H | 已修（#4594） |
| R3.6-1 | P2 | `:postgres`、`:mongo`、`:kafka`、`:migrate-data` | 分区数 P 固定，没有重分区路径 | D240（槽位间接层 PS-1；在线分裂 10.x） | 部分；在线分裂转 10.x（D240 ③）。10.0 的槽位间接层已做（#4585，4,096 个槽） |
| R3.7-1 | P2 | `:compiler`、`:gradle-plugin` | 只能用 Gradle 构建（KSP、Manifest 补全在 Gradle Worker），9.x 的 Maven 用户无法迁移；09 漏列 | D239 ②（只支持 Gradle） | 已定 |

## 5. 查询 API 综合审查 R4-Q（2026-10-20，基线 `754a7f9832`，只读，第一性原理与用户场景）

**推导出的要求**：R1 读能反映调用者已确认的写，否则说明陈旧程度；R2 读不伤害写，代价在开始工作前就有上限；R3 每个答案限于调用者的范围，「看不到」与「不存在」表现一致，读可审计；R4 结果确定，续读用不透明的服务端令牌；R5 一套协议服务三种存储，后端差异只经声明的能力体现；R6 Manifest 是契约，只做增量演进；R7 常见用法简单且默认安全；R8 数字与时间在线路上精确。**审查认为设计优于要求之处**：写后读在命令端一次付清（默认 `SNAPSHOT` 等待），但基于快照的缓存须遵守同一规则（Q-12）；后端差异是声明而非隐藏，但每条限制须进描述符（Q-2）；快照没有位置，按排序值加唯一键分页并逐页重新准入是对的，事件日志有真实位置，跨聚合事件搜索应按位置分页。协调者已对照代码核实 Q-1、Q-5。

| 编号 | 级别 | 范围 | 发现（违反的要求） | 归属 | 状态 |
| --- | --- | --- | --- | --- | --- |
| Q-1 | P1 | `:runtime` `StateReads`、`:http` `StateHandler` | 历史点读（`?version=`、`?at=`、`/state/trace`）重放日志不经 `ReplayGate`、无长度上限，且 trace 在范围与策略检查之前就读（`StateReads.kt` `at`/`trace`，`Kernel.kt:542/622/654`，`StateHandler.kt:113`）：对 50 万事件的聚合循环请求即把整段日志装入堆，被拒的调用者也一样（R2、R3） | 点读加固 | 已修（#4615） |
| Q-2 | P1 | `:query` events 可见性 | 任一 ABAC 标志或 `QueryPolicy` 让 events 查询只能钉住至多 100 个聚合加租户，否则 400 `STORAGE_UNSUPPORTED`（`EventVisibilityPolicy.kt:63-72`）；钉住时逐个点读、无舱壁；描述符不列此约束——启用策略的用户失去 D235 的跨聚合时间线（R3、R5、R7） | R4-Q2（D254、D259） | 已修（#4663、#4665） |
| Q-3 | P1 | `:query`、`:http` | 没有受支持的类型安全进程内查询 API：网关取自 `:http` 的 `HttpQueries`，行是 `ObjectNode`，默认 `QueryCaller` 受信任且无租户、进程内预算为空（列表无界、计数精确），Java 只有 suspend/Flow（R3、R7） | R4-Q3（D254、D259） | 已修（#4624、#4682） |
| Q-4 | P2 | 错误模型 | 状态读参数错误答 `wow.command.malformed`；`single` 未命中对 events 与数据集也答 `wow.aggregate.not-found`；未知数据集 404 无错误码；`wow.query.timeout` 带 `Retry-After: 1` 且 TS 视为可重试，诱导立即重跑已耗尽预算的查询（R5、R2） | 小修（超时状态待定） | 已修（#4615、#4695） |
| Q-5 | P2 | `:http` `StateHandler` | 所有者或空间不符答 403 `wow.aggregate.forbidden`（ABAC 与查询则为 404 或过滤）：可探测聚合 ID 是否存在（R3） | 点读加固（待定） | 已修（#4615） |
| Q-6 | P2 | `:http` `StateHandler` | 状态 ETag 只是版本号：D172 修订号重建、新掩码、升级器变化后 304 仍返回旧的或未掩码的体（R6） | 点读加固 | 已修（#4615） |
| Q-7 | P2 | `:query` `ReadFacade` | `/state`、`?at=`、trace 不经 `QueryObserver`，不可观测、不审计（R3） | 点读加固 | 已修（#4615） |
| Q-8 | P2 | `:openapi` | 查询的 OpenAPI 描述太弱：`QueryRequest` 是只要求 `kind` 的扁平对象、`filter` 只有 `{op}`、响应是 6 种形状的 `anyOf`、`QueryDescriptor` 不透明（R6） | 契约与客户端保真 | 转 10.x（D267 ㉑：Q5b 只在冻结后增量做；已列入路线图 §6） |
| Q-9 | P2 | TS `wow-client` | `response.json()` 静默丢失 decimal 与 int64 精度（R8） | 契约与客户端保真（待定） | 已修（#4740） |
| Q-10 | P2 | `:query` | 相对时间默认用 JVM 时区（`FilterNormalizer.kt:63、226`），TS DSL 不发时区（R8） | 契约与客户端保真 | 已修（#4740、#4772） |
| Q-11 | P2 | `:clickhouse` 数据集 | 列表与偏移分页排序无唯一键兜底（`ClickHouseDatasetQueryBackend.kt:192-195`），并列时翻页不稳定；TCK 缺并列偏移分页用例（R4） | 小修 | 已修（#4695） |
| Q-12 | P2 | `:query` 计数缓存 | 计数缓存默认开启、按节点的快照检查点校验：跨节点时总数落后至多 30 秒而行是新的；events 模型复用快照检查点而日志在其前面（R1；场景 S1、S4） | 派生读的一致性 | 已修（#4687） |
| Q-13 | P2 | 一致性 | `ACCEPTED`/`COMMITTED` 等待之后没有最小版本或一致性令牌，唯一的办法 `?version=N` 要重放（R1） | 派生读的一致性（待定） | 已修（#4687；列表的位置令牌 10.x） |
| Q-14 | P2 | TS | 结果类型不随投影变化；事件体与聚合行无类型；9.x 的 `*State` 变体没有了（R7） | 契约与客户端保真 | 部分，大体已做：#4740 的 `Projected`；生成器给出类型化的事件联合与 `SnapshotQueryClient<State, Field>`；JVM 侧见 D362、D366。其余转 10.x（Q5b，D267 ㉑） |
| Q-15 | P2 | 批量读 | 列表与 SSE 上限 1000 行、游标每页 100 行（`QueryBudget.kt:71-72、118`），HTTP 导出 100 万行需上万次往返（R7；场景 S2） | 并入 R3-B4（D253） | 已修（#4618） |
| Q-16 | P2 | 服务间读 | `SubscriptionContext` 没有状态读，对端契约不含查询（`PeerContracts.kt:39`），没有 JVM 查询客户端：Saga 与对端服务手写无类型的读（R7；场景 S3） | 类型化读 API | 已修（#4641） |
| Q-17 | P2 | 可见性 | 租户总被钉住，跨范围操作员无法跨租户查询，只能借道受信任的进程内代码（R3，要求本身受质疑；场景 S5） | 小修（待定） | 已修（#4701） |
| Q-18 | P2 | 演进 | `@QueryAlias` 只覆盖请求路径，响应只带新字段名，老客户端读到 `undefined`（门禁会报出，但没有增量的改名路径）（R6；场景 S9） | 契约与客户端保真 | 转 10.x（D267 ㉑：Q5b 只在冻结后增量做；已列入路线图 §6） |

另：R3.3-2 已完成（`QueryBackend` 是 `@WowSpi`，规范在 `query/query-tck`，#4591）。数据集查询的舱壁随 7.2c（D247）。

## 6. 9.x 替代落空审查 M（2026-10-20，基线 `f98d366c4f`，只读）

起因：D187 放弃 `wow-apiclient` 时所说的「生成的客户端」只有 TypeScript（已由 D257 补上）。逐行核对 09 与 D183、D184、D187、D213、D229、D239、D244 声称的替代是否真实存在、是否公开、是否覆盖 9.x 的用法。协调者已对照代码核实 M-1、M-3。

| 编号 | 级别 | 范围 | 发现 | 归属 | 状态 |
| --- | --- | --- | --- | --- | --- |
| M-1 | P1 | `:runtime` `CommandGateway` | Saga 以外的代码不能向其他上下文发命令：网关对本节点不服务的聚合答 `UNHANDLED`（`CommandGateway.kt:307`），命令主题只有 Saga 写；9.x 任何 Bean 都能经 Kafka 命令总线发（`DefaultCommandGateway.kt:95`）；迁移 Skill 的映射（`v9-to-v10.md:591`）对此误导 | CMD-1（D259） | 已修（#4622） |
| M-2 | P2 | `:runtime`、Java | Java 调不了命令网关（`send` 返回 `Flow`、`execute` 是 suspend），文档与测试都没有桥接写法；09 引用的 D149 是错的 | D257 的 Java 门面；先补文档与冒烟测试 | 已修（#4682） |
| M-3 | P2 | `:spring-boot`、`:http` | 9.x 给应用自己路由的全局错误处理器（`DefaultGlobalExceptionHandler`，默认开启）在 V10 没有，应用端点的错误体静默变成 Spring 默认格式 | M-3（D259，默认开启） | 已修（#4677） |
| M-4 | P2 | `:clickhouse` Sink | 没有集群拓扑（9.x 默认 `ON CLUSTER`、复制表与分布式表）也没有命令表；集群用户须手写与 Sink 列一致的 DDL | 文档与公布列 DDL；集群 DDL 生成 10.x | 已修（#5352：`operate/clickhouse` 公布 Sink 的列 DDL 与 `ON CLUSTER` 复制表加分布式表的示例，两份 DDL 由 `PublishedDdlTest` 钉在代码生成的结果上；写明命令表已去掉；集群 DDL 的生成 10.x） |
| M-5 | P2 | 迁移 | ES 用户没有迁移指引：`migrate-data` 只支持 PostgreSQL、MongoDB，Skill 不提 ES，重建能否填满 ES 快照目标未核实 | 文档；`migrate-data` 的 ES 支持 10.x | 已修（10.0 部分：先在 9.x 侧迁到 MongoDB 已写明，ES 快照经 `SnapshotRebuildStore` 重建；2026-11-02 复核）；`migrate-data` 直接读 ES 转 10.x（D259 M-5，09 的 G-5 随之改为 10.x） |
| M-6 | P3 | 迁移 | Kafka 命令主题与线路格式变了（`wow.<ctx>.commands`、`CommandWire`），外部命令生产者的切换没有文档 | 文档（与 M-1 相关） | 已修（#5352：迁移的切换文档与技能加了「外部命令生产者」一节：主题、信封与 `CommandWire`、键与分区、无准入的安全提醒、改走 HTTP 或 `WowClient` 的建议，以及在切换步骤中的位置） |
| M-7 | P3 | 可观测性 | 指标与 span 名称全部改名，Skill 与 07 没有对照，告警与看板会静默失效 | 文档（对照表） | 已修（#4976） |
| M-8 | P3 | 迁移 | `wow-cocache` 放弃但没有替代写法 | 文档 | 已修（`wow-cocache` 的替代写法写进迁移技能与 `migrate/capabilities`；扩展 D364；#5229；2026-11-02 复核） |
| M-9 | P3 | 设计文档 | 09 多处过时或有误（D149 误引、ES 已实现、R3-H 与 R3-D 已合并、Skill 已覆盖的条目、`wow-apiclient` 应指向 D257） | 协调者刷新 09 | 已修（#4796 及其后；2026-11-02 复核，原记的行号已过时） |

## 7. GA 前架构审查 R5（2026-10-26，基线 `147ce51d5`，只读）

角度与推出的不变式见 [R5 设计](2026-10-26-r5-ga-architecture-review-design.md)；用户按推荐定 D282～D291。三组：A 编程模型与写核心、B 运行时与后端、C 读平面、边缘、宿主、迁移与横切。共 54 项（P0 1、P1 15）；协调者已对照代码复核 B-1、B-2、C-4、C-8、A-1。去向是路线图 9.3d 的批次。

| 编号 | 级别 | 位置 | 现象 | 去向 | 状态 |
| --- | --- | --- | --- | --- | --- |
| R5.B-1 | P0 活性 | `:runtime` `Executor` | 批取走的跟随命令的调用方被取消后，批提前结束时它被放回队首并接手车道，但无人领取，`leading` 停在 true，车道永久卡住（热点聚合停写，内部来源永久挂起） | 批 0 | 已修（#4905） |
| R5.B-2 | P1 | `:runtime` `ServiceNode` 停机 | `EXECUTIONS_ENDED` 超时后 `executor.close()` 无界等待，排在会取消执行的 `coordinator.close()` 之前 | 批 0 | 已修（#4905） |
| R5.B-10 | P2 | `:runtime`、02 | 默认常量两处定义（`laneCapacity`、`maxBatch`、`recentRequests`）；02 仍写 256（D278 为 32） | 批 0 | 已修（#4905） |
| R5.C-8 | P2 | `:api`、`:kernel`、`:http` | 领域拒绝可用 `wow.*` 码（答 429 加 `Retry-After`，客户端自动重试）或别的上下文的完整码 | 批 0 | 已修（#4906；外上下文的完整码有意保留：共享模型如 `tree.*`，边缘只按命令自己的上下文查状态） |
| R5.C-7 | P2 | 横切 | 决定与审查编号进入用户可见的文字：108 个异常与拒绝消息、错误码描述（problem 的 `title`、Manifest、TS 快照）、54 行配置元数据、生成代码；主代码含编号 3,575 行（R3.2-2 登记时 439 处；B-14、A 组的分模块计数并入本项） | 批 0（用户可见部分）、批 5（注释）（D290） | 已修（#5014、#5016、#5019～#5025、#5036～#5042、#5045；`DesignIdsTest` 守住生产代码与 README） |
| R5.C-18 | P2 | `:http` problem | `title` 用错误码的长描述，不是 RFC 9457 的简短摘要 | 批 0 | 已修（#4906） |
| R5.C-11 | P2 | `:spring-boot` | 31 处配置检查以普通异常埋在 `BeanCreationException` 里，没有 `FailureAnalyzer`；240 个键中 171 个没有描述 | 批 0 | 已修（#4906：`WowConfigurationException` 与 `FailureAnalyzer`，240 个键都有描述） |
| R5.C-13 | P2 | `:spring-boot` | `WowAutoConfiguration` 连续两段 KDoc，前一段是残留 | 批 0 | 已修（#4906） |
| R5.C-14 | P2 | `:http` 路由 | 每次构造路由新建一个从不取消的 `CoroutineScope`，停机后 SSE 生产者仍在（待核实） | 批 0 | 已修（#4906：路由作用域随节点停止取消） |
| R5.A-1 | P1 | `:query-api` | 查询协议 94 个公开 `data class`（`copy`、`componentN` 冻结）；根因：`:query-api` 用不了 `@InternalWowApi` | 批 1（D282） | 已修（#4916） |
| R5.A-2 | P1 | `:manifest` | 38 个公开构造函数冻结进 ABI，只有 Wow 自己构造，近一个月转储改了 14 次 | 批 1（D283） | 已修（#4924） |
| R5.A-3 | P1 | `:test-dsl`、`:kernel` | 测 `putResult` 只能强转内核的 `CommandOutcome`，`LogPosition` 等成了用户依赖 | 批 1 | 已修（#4924） |
| R5.A-7 | P2 | ABI 转储 | sealed 父类型上的 `@InternalWowApi` 挡不住嵌套子类（`BatchOutcome$*`、`QueryPathSegment$*` 等 5 处） | 批 1 | 已修（#4924） |
| R5.A-8 | P2 | `:api` `SubscriptionContext` | 冻结接口用「默认抛异常」表达能力 | 批 1 | 已修（#4929） |
| R5.A-9 | P2 | `:kernel`、`:manifest` | 错误码字符串两份，只靠 `:http` 的一个测试对齐 | 批 1（D294） | 已修（#4924，D294） |
| R5.A-10 | P2 | `:kernel` `ModelProvider` | 生成代码与内核没有绑定版本握手，不匹配要到首条命令才 `NoSuchMethodError` | 批 1 | 已修（#4924） |
| R5.A-11 | P2 | `:kernel` | `ExecutionProjectContext` 等内部执行上下文是公开 `data class` | 批 1 | 已修（#4924） |
| R5.A-12 | P2 | `:manifest`、`:kernel` | `Creation`、`Ownership` 两套同值枚举 | 批 1（D294） | 已修（#4924，D294） |
| R5.A-13 | P2 | `:kernel` `EventType` | `data class` 的 `equals` 落在 lambda 身份上；`JsonNode` 进冻结面 | 批 1 | 已修（#4924） |
| R5.A-14 | P2 | `:api` `Command` | `schedule` 每次访问都解析 headers | 批 1 | 已修（#4929） |
| R5.A-15 | P2 | `:api` `QueryApi` | 数据集用 `AggregateType` 命名 | 批 1 | 已修（#4916） |
| R5.A-16 | P2 | `:api` `DecideScope` | `schedule` 用默认参数，Java 必须传 `target` | 批 1 | 已修（#4929） |
| R5.C-6 | P1 | `:spring-boot`、`:migrate-data`、`:query` schema | 属性 Bean 约占 `spring-boot.api` 八成；`migrate-data` 101 个公开声明无转储；`schema` 包有若干无外部使用者的公开类（补 R3.2-3） | 批 1 | 已修（#4929） |
| R5.B-7 | P2 | `:runtime` `ServiceNode` | 1,473 行，组装、生命周期、门面三合一；`topicSubscriptions` 漏出内部类型 | 批 1（getter）、批 5（拆分） | 已修（#4929 getter；#5039 拆分 `ServiceNode`，1,473 行到 395 行；2026-11-02 复核） |
| R5.B-12 | P2 | `:runtime` | 测试用 `LoopbackClusterChannel` 在主代码里 | 批 1 | 已修（#4929） |
| R5.C-1 | P1 | `:http` 读路由 | 读侧作用域授权（`crossScope`、跨租户开关、固定租户）在 HTTP 层抄了三份，引擎无法复核 | 批 2 | 已修（#4927） |
| R5.C-2 | P1 | `:spring-boot` | 宿主里有安全底线（无集群令牌拒绝启动）、内部端口服务器、读侧组合、对端路由；守护规则是 8 个类名的黑名单 | 批 2 | 已修（#4932） |
| R5.C-4 | P1 | 发布元数据 | `wow-dependencies` 以 `api(platform)` 进入每个已发布库，把 Spring Boot、测试与 lint 的约束带给所有 Gradle 用户 | 批 2（D287） | 已修（#4921） |
| R5.C-5 | P1 | `:client`、`:http` | `:client` 依赖整个 `:runtime` 与 `:query`；`:http` 为线路语法依赖 `:client` | 批 2（D286、D293） | 已修（#4919、#4941） |
| R5.A-4 | P1 | `:api` | 运维线路记录（`api.ledger`、`api.snapshot`，约 500 行 ABI）在领域 API 里，与 `:spi` 同一概念两套模型 | 批 2（D284、D304） | 已修（#4942） |
| R5.C-10 | P2 | `:architecture` | 规则是正则黑名单；06 §3、07 §4 声称的 9.x 名称规则不存在；缺 `:client` 方向等规则 | 批 2 | 已修（#4937） |
| R5.C-12 | P2 | `:cosec`、`:spring-boot` | 可选集成两套装配方式；`wow.cosec.*` 两个读者 | 批 2 | 已修（#4935） |
| R5.B-3 | P1 | `:postgres`、`:mongo`、`:spi`、`:runtime` | 传输段在四处各编码一份，GA 后加字段要改每个后端，漏改即静默丢字段 | 批 3（D291） | 已修（#4917） |
| R5.B-4 | P1 | `:runtime` 租约、`:spi` `LeaseStore` | 控制面约 3.7 次存储操作/租约组/节点/秒，成员关系在每组重复心跳与读取 | 批 3（D291） | 已修（#4931） |
| R5.B-5 | P1 | `:spi`、`:spring-boot` | Sink 与传输没有发现机制，只能由宿主专用自动配置装配 | 批 3（SPI）、批 4（配置）（D288） | 已修（#4934、#4983） |
| R5.B-9 | P2 | `:spi` | `EventLog`、`SnapshotStore` 混着必须实现与可选优化；组合读靠向下转型且静默退回 | 批 3（D291） | 已修（#4923） |
| R5.B-16 | P2 | `:postgres` | 组合读失败静默退回，不记日志 | 批 3 | 已修（#4923） |
| R5.B-17 | P1 | `:postgres`、`:mongo`、`:spi` `TimerStore` | 定时器每个上下文一张表（`wow_timer__<context>`），按 `owner_aggregate` 列区分类型；一个聚合类型的日志可以单独放到别的存储，它的定时器却留在上下文共用的表里 | 批 3（D307、D308） | 已修（#4967） |
| R5.B-18 | P1 | `:postgres`、`:mongo`、`:spi` `ReservationStore` | 预留全局一张表（`wow_reservation`），主键（上下文，租户，命名空间，key），连上下文都未拆分；命名空间在上下文内跨类型共享 | 批 3（D307：按聚合类型拆分，唯一性在类型内；D308：MongoDB 分片） | 已修（#4946） |
| R5.A-5 | P1 | `:kernel` DSL | X-6 新角度：DSL 默认名不读 `@Name`，GA 后写进日志的名字只能靠 `@Alias` 迁移 | 批 3（D285） | 已修（#4914） |
| R5.C-3 | P1 | 配置 | DDL 5 键 4 枚举语义不一；对端分 `calls`/`clients`；`in-process-timeout` 与 `in-process.timeout`；Mongo 读偏好进中立 SPI；两份开发 profile | 批 4（D288、D303） | 已修（#4969、#4971） |
| R5.B-8 | P2 | 配置 | 已实测默认开启的优化仍留总开关；`ServiceNodeOptions` 21 个平铺字段 | 批 4（D288、D303） | 已修（#4974） |
| R5.C-16 | P2 | 配置 | `wow.query.http.*` 与 `in-process.*` 两组相同的 12 个键 | 批 4（D288、D303） | 已修（#4969） |
| R5.C-9 | P2 | 观测 | 指标与错误码同名、分隔符混用、标签不一致；`:opentelemetry` 名实不符 | 批 4（D289） | 已修（#4976，D326） |
| R5.B-11 | P2 | `:runtime` `ContextBackends` | `snapshots` 与 `snapshotTargets` 两种写法 | 批 4 | 已修（#4974） |
| R5.A-6 | P2 | `:kernel` | `Kernel.kt` 1,403 行装着写、批、历史读与副作用作用域；`maxReplay` 默认 `Int.MAX_VALUE` | 批 5 | 已修（#4988） |
| R5.A-17 | P2 | 06 | §2.1 仍写 `wow-api` 无依赖、6 个包 | 批 1（随文档） | 已修（本轮设计文档） |
| R5.A-18 | P2 | `:compiler` | 29 个文件一个包，`ModelSymbolProcessor` 901 行 | 批 5 | 已修（#4986） |
| R5.A-19 | P2 | `:manifest-tools` | 为一个指纹依赖整个 `:kernel` | 批 5 | 已修（#4985） |
| R5.A-20 | P1 | `:api` `DecideScope`、`:kernel` | 决定函数里抛出的任何异常都是技术故障（`wow.command.failed`，500）：`require`/`check` 的业务检查对 Saga 发出的命令会被重试、停放，而决定函数是确定性的，重试注定失败；深层校验只能一层层传回 `Decision` | 批 1（D311） | 已修（#4965） |
| R5.A-21 | P1 | `:test-dsl` | 9.x 的 `inject` 可以在用例中途替换依赖的服务（9.x example `OrderSpec` 用了 5 次）；V10 的服务在构建模型时绑定，分叉复制运行时，同一用例的分叉无法换服务 | 批 1（D315 ①） | 已修（#4968） |
| R5.A-22 | P2 | `:test-dsl` | 没有 `expectEventCount`；`expectEvent<E>` 只见事件体，看不到聚合 ID、版本、请求 ID、请求头 | 批 1（D315 ③） | 已修（#4968） |
| R5.A-23 | P2 | `:test-dsl` | 断言失败是纯字符串（`Expected events [..] but got [..]`），没有差异，不带命令、步骤与分叉路径 | 批 1（D315 ④） | 已修（#4968） |
| R5.A-24 | P1 | 横切（`SurfaceRules` 的允许清单） | #4929 留下的 80 个公开 `data class`（`:query` 59、`:runtime` 15、`:kernel` 3、其他 3），其 `copy`、`componentN` 仍在冻结面；清单只许缩小 | 批 1（rc 前：逐个改为普通类加工厂，或在清单里写明保留的理由） | 已修（#4970、#5006、#5009，清单已删除） |
| R5.C-20 | P2 | `:http`/`:runtime` 集群通道 | 启动时对端 Pod 的 DNS 名尚未发布，问候失败按 WARN 打出完整堆栈（`UnresolvedAddressException`），是例行竞态 | 批 5（INFO、不带堆栈，持续失败才 WARN） | 已修（#4977） |
| R5.B-6 | P2 | 性能 | 每条命令约 80 KB 分配未剖析；事件体与 `trace` 多一次解析加序列化 | 批 5（JFR 后 ABAB） | 已修（#5044：事务的界限随首条语句发出，checkpoint 多行写入；ABAB 第 2 轮每条命令的 CPU 与分配都不高于 main，饱和项因压测端单核到顶不采信，见 X-18） |
| R5.B-13 | P2 | `:runtime`、`:spi` | 52 处手写 `equals`/`hashCode`/`valueFields`，漏字段编译器发现不了 | 批 5 | 已修（#4991） |
| R5.B-15 | P2 | `:mongo` | `MongoEventLog.kt` 1,402 行 | 批 5（B-3 之后） | 已修（#4987） |
| R5.C-19 | P2 | `:query` | 根包 35 个文件混着引擎、策略、缓存、舱壁 | 批 5 | 已修（#5013） |
| R5.C-15 | 提醒 | `:http` | 9.x 遗留的公开路由 `GET /wow/id/global` | 批 2（D292，删除） | 已修（#4939） |
| R5.C-17 | 提醒 | `:http` | `wow.http.client-facts` 默认开启，把 IP 与 UA 写进不可删除的日志 | 维持默认（D297），`remote_ip` 取自网关转发头 | 已修（#4943：转发头默认打开） |

## 8. 安全性统一讨论（2026-10-26，基线 `c403da4e4`，只读）

边界、原则与问题见[安全讨论](2026-10-26-security-review-design.md)；用户定 D295～D302。去向是路线图 9.3d 的 S.1～S.3。

| 编号 | 级别 | 位置 | 现象 | 去向 | 状态 |
| --- | --- | --- | --- | --- | --- |
| S-1 | P0 | `:spring-boot` `WowAutoConfiguration` | 没有 `CallerResolver` bean 时以匿名调用方服务全部公开路由，只告警一次 | S.1（D301） | 已修（#4943） |
| S-2 | P1 | `:runtime` `HttpSignalSender` | 发往其他服务的信号把服务凭据带给等待来源里写的地址 | D300 删除服务凭据后失效 | 放弃 |
| S-3 | P1 | `:runtime` `CommandIntake` | Kafka 命令主题不认证：能写主题即可绕过准入与授权 | S.3 文档（D298：内部受信） | 已修（#4949 文档） |
| S-4 | P1 | `:http` `RequestHeaders` | `client-facts` 默认开启；网关之后记下的是网关地址 | S.1（D297：维持默认，取网关转发头；D303 ⑥：转发头策略由 Wow 默认打开） | 已修（#4943） |
| S-5 | P1 | `:http` `CallPermits` | 不列 `commands`/`reads` 即全部领域命令与全部模型 | 维持现状（D298、D299） | 放弃 |
| S-6 | P1 | `:spring-boot` `ConfigurationKeys` | 只有 4 个前缀拼错会拒绝启动 | S.2（D302，随批 4） | 已修（#4972、#4975，D324 ②） |
| S-7 | P1 | `deploy/*/secret.yaml` | 示例集群令牌满足长度检查 | D300 删除集群令牌后失效 | 放弃 |
| S-8 | P1 | `:http` `InternalHttp` | 内部端口只能明文 | S.3 文档：服务网格 mTLS（D298） | 已修（#4949 文档） |
| S-9 | P2 | 平台服务 `OpsCallers` | 头部身份开关没有开发 profile 或回环守卫 | S.1 | 已修（#4943） |
| S-10 | P2 | `:http` | 请求事实没有单条大小上限 | S.1（固定常量，D299） | 已修（#4943） |
| S-11 | P2 | 平台服务 | 三个平台服务共用一个 HMAC 密钥 | S.1：改用注入模式，删掉共享密钥（D296、D299） | 已修（S.1，平台服务不再持有密钥） |
| S-12 | P2 | `:query` `CrossTenantAudit` | 跨租户查询的审计只是 INFO 日志 | 转 10.x（路线图 §6） | 转 10.x |
| S-13 | P1 | `:http`、`:client`、`:spring-boot` | 内部端口的集群令牌与服务令牌在受信内网中维护成本高、收益只针对已排除的威胁 | S.1（D300：删除，身份由调用方声明，网络策略作边界） | 已修（#4949） |

## 9. GA 前的集群端到端验证（D317，#4961，Actions 上的 kind）

按文档部署 eShop、ops、view-store 与分析目录，CoSec Gateway 在前。发现的问题按此登记；修复由各自的工作包做，E2E 不打补丁绕过。

| 编号 | 级别 | 位置 | 现象 | 去向 | 状态 |
| --- | --- | --- | --- | --- | --- |
| F1 | P1 | eShop | 不能跑在 MongoDB | eShop 的 example PR；宿主侧 D318 | 已修（#4963、#4962） |
| F2～F4 | P1 | eShop | 四个服务没有 CoSec、Actuator 健康检查与 Dockerfile | eShop 的 example PR | 已修（#4963） |
| F5 | P3 | CI | CoSec Gateway 依赖 Redis | 用户批准仅在 CI 使用 `redis:7.4-alpine` | 已处理 |
| F6 | P2 | eShop | 没有 Wow 的指标（未依赖 `wow-observability`，未暴露指标端点） | G.1：示例按最佳实践带上可观测性 | 已修（G.1 #4997：OTel agent 导出 Wow 指标，E2E 断言） |
| F7 | P2 | CoSec Gateway 6.1.2 | 配置 Redis 时限流 bean 冲突而不能启动 | 绕过：`cosec.limiter.enabled=false`；上游 issue 草稿待用户决定是否提交 | 已提上游（Ahoo-Wang/CoSec#1063），绕过保留 |
| F8 | P1 | `:http` `HttpSignalSender` | 一次丢失的等待信号让已提交的命令变成 504 | D320 | 已修（#4977，D320） |
| F9 | P0 | 部署清单 | Deployment 的 Pod 没有逐 Pod 的 DNS，公布的节点地址解析不到，转发与跨节点信号全部失败 | D319 | 已修（#4966） |
| F10 | P0 | `:postgres` | 强删属主后，死事务的表锁、启动时无条件的 DDL 与只在客户端的超时连成一串，`max_connections` 用尽，替补节点反复重启 | D321 | 已修（#4980，D321；E2E 5 次强删验证） |
| F11 | P1 | `:spring-boot` | 4a-2 后没有 Kafka 的 view-store 与分析目录在开发环境之外拒绝启动 | D322 | 已修（#4978，D322） |
| F12 | P1 | `:spring-boot` `ConfigurationKeys` | #4975 的环境变量检查只认 Spring 的规范写法，拒绝了合法的旧写法（短横换下划线） | 两种写法都接受；随附清单用规范写法（D324 ②） | 已修（#4982） |
| F13 | P2 | 网关 | 网关复用通向已删 Pod 的连接，客户端收到 500 | D328 | 已修（#4989、#4992） |
| F14 | P1 | `:mongo` `MongoEventLog.head` | 交接期间新类型的首个提交：新属主的 head 用缓存的注册表，订阅 10 s 不读，命令 504 | head 发现别的节点登记的集合 | 已修（#4998） |
| F15 | P1 | `:postgres` 定时器 | 没有定时器类型的上下文每次轮询都报 SQL 错误（空的 LATERAL 分支缺列）；忙的目标无上限重试；停放的定时器在 ops 不可见 | 只读计数；忙重试设上限；ops 加定时器范围 | 已修（#4994、#4996） |
| F16 | P0 | `:postgres` 结构步骤 | 两节点同时启动时快照表的 ALTER 与 CREATE INDEX CONCURRENTLY 死锁 | 按表的 advisory lock、40P01 重试（D321） | 已修（#4980） |
| F17 | P3 | `:runtime` 测试 | `SnapshotSubscriptionTest` 的竞态：只在测试的时序里出现两份视图，不会写进存储 | 测试改为等待 | 已修（#5002） |
| F18 | P1 | `:mongo` | 只有日志与计数器写 majority，预留、定时器等在主节点切换时可能丢失 | D332 | 已修（#5001） |
| F19 | P1 | `:runtime` 账本 | 发布账本但不接收命令的服务收不到 ops 的恢复与跳过 | D331 ① | 已修（#5000） |
| F20 | P1 | 契约、`:spring-boot` | api jar 的契约没有服务名，DNS 发现找不到只有 api jar 的对端 | D330 | 已修（#5010）；E2E 已验证（#5008） |
| F21 | P2 | `:runtime` 账本 | 恢复、跳过不带所针对的停放，旧命令可能作用到后来的停放 | D331 ② | 已修（#5011） |
| F22 | P2 | `:kafka` | 启动时 Kafka 客户端对不再分配的分区的偏移量回复刷 WARN | 元数据消费者按需分配；共享消费者不再每次重定位 | 已修（#5007） |
| F23 | P2 | `:query` | 存储不可达时查询答 500 而不是 503 | 不可达即 `unavailable`（503） | 已修（#5033） |
| F24 | P2 | `:clickhouse` | 连接失败没有分类，与 F23 同一条路径 | ClickHouse 分类自己的连接失败 | 已修（#5033） |
| F25 | P1 | `gradle-plugin`、`examples/wow-contract.gradle.kts` | 编译类路径按 jar 解析时（IntelliJ 委托 Gradle）api 与 domain 之间成环 | 配对的 domain 编译时用 api 不含契约、不发布的 core jar 变体；发布的 api 不变 | 已修（#5049） |

## 10. GA 前的交互式 UI 走查（2026-10-30，基线 `cebdb88d01`，只读）

用户 2026-10-30 提出：交互式 UI 从未按人的方式走过一遍。第一组走查运维控制台与分析目录控制台（子会话 ui-walk-consoles）：两个服务按各自 e2e 的方式起在开发与 `cosec` 两种配置下（后者在 e2e 的网关替身之后），以账本与数仓模拟器灌入真实形态的数据，用浏览器在 1440、1100、1024、375 宽度、深浅色与中英文下逐页、逐操作走查。共 44 项（P0 1、P1 7、P2 36）。总览的数字与各队列一致，两个控制台的词汇已是 `retry` 与 `claim TTL`，控制台没有未捕获的错误。未能覆盖的：所属服务拒绝账本命令（模拟器无此路由）、目录的 Wow 表与「降低保护的审批」、目录的 Rows 与看板（需要 ClickHouse）、真实的 CoSec 网关与读屏软件；第二组（eShop Web 与各服务的 Swagger UI，2026-10-30）21 项（P1 8、P2 13），UI-45～UI-65：已付、拒付与缺货三种结局的显示与 SSE 一致，购物篮按所有者（D370），Swagger 只有公开路由、内部端口 404、枚举为小写短横线、问题码有文档、`cosec` 配置声明 bearer（D233）；未能覆盖：中文（应用只有英文）、链路中途停一个服务（五个服务同一 JVM）、30 s 等待超时、ClickHouse 销售、真实 CoSec 网关。

| 编号 | 级别 | 位置 | 现象 | 去向 | 状态 |
|---|---|---|---|---|---|
| UI-1 | P0 | ops 服务（`cosec` 配置）的全部读取 | 没有 ops 角色（`roles: []` 或无关角色）的令牌读出全部停放条目：计数 50、列表 50、状态读 200；配置注释、文档、README 与控制台都说没有 `ops.viewer`/`ops.operator` 什么都读不到，没有测试覆盖。目录服务在同一替身后拒绝这样的读者。疑因（未证实）：无角色的调用者是所有者级，而条目没有所有者 | 先查明是 ops 服务的策略配置还是框架的读取策略（所有权、ABAC）；是框架语义则先报协调者再改；补「无角色读者什么都读不到」的回归测试（含目录） | 已修（#5223：两种运维角色都没有的调用者读不到停放条目，控制台说明它没有读取角色） |
| UI-2 | P1 | ops，单条目的确认对话框（重试、应用重试设定） | 典型的长 ops id 不换行，内容宽于对话框（`scrollWidth` 556 对 384），「取消」「重试」在可见区域之外；375 宽度下更糟 | 长 id 在对话框内可换行或截断加完整提示 | 已修（#5232：对话框 `overflow-wrap: anywhere`，确认按钮不再被挤出） |
| UI-3 | P1 | ops，保存视图 | 提供「所有人」并标为「共享」，但只存在本浏览器的 localStorage，「复制链接」给出别的浏览器打不开的视图 id | 没有视图存储时不提供共享范围与复制链接，标明「保存在本浏览器」 | 已修（#5232：视图引擎新增 `ViewStore.local`，运维控制台的存储标为本浏览器，不提供共享与链接） |
| UI-4 | P1 | ops，分析「按上下文统计」与总览的「按上下文」 | 只计重试耗尽与不可恢复（29），不是全部停放（42），与标题、README 及其他分析不一致 | 计全部停放（未解决）的条目 | 已修（#5232：「按上下文统计」统计全部停放条目） |
| UI-5 | P1 | 两个控制台，375 宽度的页头 | 页头不换行也不能横向滚动：ops 的调用者链接与语言切换、目录的 Rows、Boards、Draft、调用者与语言都在屏外 | 窄屏下页头换行或收进菜单 | 已修（#5232：两个控制台的顶栏在窄屏折行） |
| UI-6 | P1 | 文档 `operate/ops-console`（中英文） | 仍写 resume／恢复；CoSec 动作写成 `ops:Resume`，现为 `ops:Retry`，照文档写的策略永不匹配 | 立即改正动作名与词汇（不等最终重写） | 已修（#5232：`operate/ops-console.md` 改用 retry/重试、claim TTL 与 CoSec 动作名；中文术语与控制台一致） |
| UI-7 | P1 | 目录，需要审批的发布 | 发布报「已完成」，状态仍是 v1；没有列出待审批发布的视图，审批人只能逐个打开 | 结果说「等待审批」；增加「待审批」系统视图 | 已修（#5234：结局写「等待审批」；数据集新增「待审批」系统视图） |
| UI-8 | P1 | 目录，数据集列表「修订期间仍在服务」 | 正在服务 v2 的已发布数据集显示「否」；字段本义是「需要修订时仍在服务」 | 列名与取值按本义，或只在需要修订时显示 | 已修（#5234：标签改为「需要修订期间仍在提供」，只在需要修订时显示） |
| UI-9～UI-34、UI-36～UI-44 | P2 | ops 控制台、目录控制台、视图引擎、文档 | ops：确认后对话框偶尔不关（UI-9，未确证）、打开详情后焦点不进详情（UI-11）、结果说「已完成」而所属服务尚未执行（UI-12）、重试设定无前端校验且 422 丢掉输入（UI-13）、原因框回车无效（UI-14）、批量操作的数目不一致（UI-15）、时长显示为 ISO 原文（UI-16）、两个无标签徽标（UI-17）、后退不关详情（UI-18）、筛选与排序不进 URL（UI-19）、「放进仪表盘」是死路（UI-20）、总览数字卡不可点（UI-21）、总览截断与配色不一（UI-22）、窗口改宽后栅格不重排（UI-23）、宕机时十个相同错误框与挂起无超时（UI-24）、中文「重试」与重新加载同词（UI-25）、切换系统主题后部分不跟随（UI-26）、语言按「含 zh」而非偏好次序选且 `lang` 不变（UI-27）、登出无原因与开发调用者对 `cosec` 服务（UI-28）、结果条被详情遮住（UI-29）、无障碍名称过长与「重试中」列的是「计划重试」（UI-30）、过期拒绝的说明含毫秒时刻（UI-31）、「过去 7 天」按日历日且「1 days」（UI-32）、中文术语与文档不一（UI-33）；目录：「待审批」实为「未审批」（UI-10）、按角色本不可用的操作也可点（UI-34）、内存后端下 Rows 显示 Spring 的原始 404（UI-36）、修订保存后仍显示旧问题（UI-37）、回滚是自由填写的版本号且列表顺序与 README 不符（UI-38）、起草页的连接预填与链接（UI-39）、起草页的无障碍名称（UI-40）、列表不轮询（UI-41）、看板未写进 README 且中文「看板」与「仪表盘」混用（UI-42）、README 的配置键名错（UI-43）、文档站没有目录控制台的页面（UI-44） | 按应用成批修：ops 控制台与视图引擎一批，目录控制台一批；UI-33、UI-44 进文档最终重写（中文术语统一为停放、需要处理、重试耗尽、重试） | 已修（#5232：UI-12～UI-18、UI-20～UI-22、UI-24、UI-25、UI-27～UI-31、UI-33；#5234：UI-10、UI-34、UI-36～UI-43 与 UI-27、UI-28 的目录部分）。未能复现：UI-9、UI-11、UI-23、UI-26（走查时浏览器面板隐藏，后台页没有 rAF、ResizeObserver 不触发；正常渲染下复查正确，UI-11、UI-23 加了 e2e）；UI-32 的单位词本按数量取单复数，「过去 N 天」按日历日计是既定语义。UI-44 已随用户文档的最终重写完成（#5277、#5289、#5290）。余下的五项 2026-11-02 定：UI-13（服务端拒绝后对话框仍关闭，输入随之丢失）与 UI-30（行内控件的可访问名称）GA 前修；UI-19（视图的临时状态写进地址）、UI-24 的逐面板错误条、UI-38 列表中的版本选择（详情里已可选）是引擎的新功能，转 10.x（路线图 §6） |
| UI-35 | P2 | 目录服务（`cosec` 配置），无角色读取 | 答 400 `wow.query.malformed` 而不是 403 `wow.auth.forbidden` | 随 UI-1 一起修 | 已修（#5223：目录服务对没有角色的读取答 403 `wow.auth.forbidden`） |
| UI-45 | P1 | eShop Web，多件商品的结账 | 一次结账有两件以上商品时，服务端为每件商品各发一个 `ReserveStock`、`onReserveCommitted`、`ConfirmOrderStock`、`onOrderStockConfirmed` 的结果（两件商品共 19 个结果对 15 跳），页面按位置把第 i 个结果放在计划的第 i 行，显示「19 of 15 hops」、丢掉最后 4 个结果、徽标错位；端到端测试只结账一件商品 | 按跳（函数与上下文）归并结果，不按位置；计数说跳不说结果；查明归并是否在 `wow-client`/`wow-react` 也有同样的假设；端到端补多件商品 | 已修（#5253：结账页按跳与函数归并结果，多商品的扇出归入同一步，计数说跳；`wow-client`、`wow-react` 没有按位置对应的假设；加了双商品的 SSE 录制与 e2e） |
| UI-46～UI-49、UI-51、UI-52 | P1 | eShop Web 与服务 | 切换用户后仍显示上一用户的结果（UI-46）；管理页的报错显示在屏外（UI-47）；没有修改商品、改名、下架，缺品牌或分类的草稿永远发布不了（UI-48）；只有 catalog 有 `CommandAuthorizer`，买家或匿名调用者能入库、alice 能发自己的货（UI-49）；某个服务宕机时订单页整页只显示 502（UI-51）；README 的查询示例仍用大写的 `AND`/`EQ`/`ASC`，被拒为 400（UI-52） | eShop 一批修 | 已修（#5253：切换用户重挂页面；报错显示在操作处；商品可编辑、下架，品牌与分类可改名；inventory、ordering 加 `CommandAuthorizer`，库存与发货只许员工；订单与支付分开读取；文档站与技能中的查询示例改为线上的小写，并由 `DocumentedQueriesTest` 守住） |
| UI-50 | P1 | 框架：内置聚合的系统命令在公开端口上的授权 | `wow.schedule.*`（任务的 Enable、Reschedule、Disable）在公开端口上，买家与匿名调用者都能执行：两者都停掉了库存服务的 `receive-deliveries` 任务；租户级资源的作用域检查对同一租户的任何调用者都放行 | 协调者定：Wow 内置聚合的系统命令在公开端口上，除非有 `CommandAuthorizer` 放行，一律拒绝（403 `wow.auth.forbidden`，与运维路由「默认从不放行」同一规则）；有授权器时由它判定（CoSec 动作 `wow.schedule:<命令>`）；排查其他在公开端口上路由的内置系统命令；文档写明 | 已修（#5247：没有授权器时公开端口拒绝 `wow.schedule.*` 与 `wow.snapshot.Rebuild`；CoSec 动作沿用 `<context>:wow.schedule.Enable`；eShop 的授权器只许员工） |
| UI-53～UI-62 | P2 | eShop Web | 空购物篮结账显示开发者文字（UI-53）；拒付与缺货的措辞（UI-54）；商品以 ID 显示（UI-55）；搜索、筛选与分页不进 URL、默认的 404 页（UI-56）；管理页按钮名不分行（UI-57）；双击「发货」得到 409（UI-58）；购物篮按钮失去键盘焦点（UI-59）；375 宽度下跳列表拥挤（UI-60）；深色模式没有 `color-scheme`（UI-61）；裸 502 与缺失读取的控制台噪声（UI-62） | eShop 一批修 | 已修（#5253） |
| UI-63、UI-65 | P2 | 框架：OpenAPI | 五个服务的 Swagger UI 每次都显示红色的 `jsonSchemaDialect` 告警（UI-63）；开发配置下的全局 `security` 是五个任选其一的方案，生成的客户端可能只发第一个（UI-65） | 随 UI-50 的会话修 | 已修（#5248：不声明 `jsonSchemaDialect`，取 OpenAPI 3.1 的默认方言；开发配置的 `security` 为一个同时列出各方案的要求） |
| UI-64 | P2 | 框架：Manifest 与 OpenAPI 的 schema | 命令的 Jakarta 约束（`@Positive`、`@NotBlank` 等）没有写进 schema，示例请求体因此不合法 | D380：引入 victools 的 Jakarta 校验模块，约束写进 Manifest 与 OpenAPI 的 schema | 已修（D380，#5256） |

## 11. v9 真实项目的迁移审查：项目 A（2026-10-30，只读审查加临时副本试迁移）

用户 2026-10-30 交来真实的 9.x 业务服务，GA 前审查并试迁移（用户的仓库不改、副本不推送；项目的名称与内部细节不进本仓库，只登记 Wow 自身的缺陷与缺口，编号 PA-n）。项目 A：Wow 9.3.0，22 个聚合，MongoDB、Kafka，Redis 上的 CosId 与 CoCache，一个外部调度器；JDK 17、Spring Boot 3.5。试迁移以 main `9d7fff0c10` 发布到 mavenLocal 为目标，因额度上限停在半途：构建已换成 Gradle 9、JDK 21、三个 Wow 插件、Boot 4.1、CosId 5、CoCache 6.9、CoSec 8；api 模块编译通过；全部状态类改写；4 个枢纽聚合经 KSP 编译、其中 2 个的测试在测试 DSL 上通过；其余聚合、服务端、客户端与迁移映射未做。工作量：共用基础约 50 分钟之后，一个带决定期查询、Saga 与测试的聚合约 20 分钟。另有迁移技能与文档的 9 项、项目自身须改的 7 项（后者交项目方，不在此登记）。

2026-10-31 在 D377、D378 落地后续做试迁移（main `2d71f677` 发布到 mavenLocal），至检查组为止停止：枢纽聚合（溯源读 `EventStream` 的提交时间与操作人，14 处；由命令体推出 ID 的 9 个接口在计算属性 `id` 上标 `@AggregateId`，Manifest 列出 `idProperty`，Saga 改用 `CommandRequest.of(body)`）与检查组（计划、任务、条目）编译通过，测试 DSL 上 9 个用例全绿。检查组带来枢纽没有的 9 种 9.x 写法（Reactor 的领域查询服务、注入 `Clock`、返回 `Mono`/`Flux<CommandBuilder>` 的 Saga、`@OnStateEvent`、Saga 构造器注入快照查询、在计算属性上查询、`@OnError` 译重复创建、无 `produces` 的 `List<Any>`、9.x 测试 DSL），都有 V10 的写法，Wow 侧只得 PA-22～PA-24 三项文档与测试体验的缺口。两组都没有新的框架缺陷，其余各组是项目自身的迁移工作，不再作为 Wow 的验证继续。数据步骤（`draft-map` 对 9.x 存储）待用户提供存储转储或由用户执行：子会话按约定不连远端库、不用项目凭据。

2026-11-01 项目 A 的真实数据验证（beta.1 发布的前提，用户 2026-10-31）：在本地克隆上重迁两组，对项目 A 的 9.x 开发库只读运行 `draft-map`、`check`、`import --dry-run`，目标为本地临时库。已迁移部分的映射无 AMBIGUOUS/RENAMED；`check` 在修好 PA-29、PA-30（试运行补丁）后无 PROBLEM；`import --dry-run` 转换约 21 万条流，0 失败、0 跳过、0 未解析。Wow 侧发现 PA-25～PA-30。观察（未立缺陷）：每个实例约 1 条流的聚合，对远程开发库的转换速率较低，大库的导入时长待在本地测量。按快照抽查（真正导入本地临时库后 `compare --sample`）进行中。

2026-11-02 项目 A 的端到端验证（用户 2026-11-01 要求「迁移后的服务真正跑起来」）：全部领域代码与服务端迁移到 Wow 10，在本地对导入的真实数据运行。`check --decode` 全部可读；全新完整导入 23 个聚合、约 14 万实例、约 42 万条流、约 43 万个事件，快照全部写成；按部署时区全量 `compare` 约 14 万实例，真实映射错误 0（差异为形状、环境与需项目方确认的计算逻辑变化）。冷启动 16/16 分区持有、无掉线（PA-47 修复前掉 14 个）。主数据、导入库存、库存移动、盘点、提货、出库、拣货、复核到交接的业务流程全部通过。演练中 Wow 侧发现 PA-25～PA-58，P0/P1 均已修复；经验整理进迁移文档与 wow-migrate 技能（进行中）。

| 编号 | 级别 | 位置 | 现象 | 去向 | 状态 |
|---|---|---|---|---|---|
| PA-1 | P0 | 内核的溯源函数（只收事件）、`@Upgrade`（只见载荷）、`DecideScope`（无时钟） | 9.x 的溯源可读事件的 `createTime` 与操作人（项目 A 有 15 个这样的函数）；`migrate-data` 把它们导入为提交时间与操作人，但 Wow 10 的溯源读不到、升级也注入不了，凡这类聚合重建后都丢这些字段，`compare` 必然有差异 | 编程模型的决定，待与用户讨论（推荐：溯源函数可选第二个参数取事件已记录的事实——提交时间、操作人、版本；`DecideScope.now` 由节点时钟给出、测试 DSL 可控） | 已修（D377，#5252：溯源函数可选第二个参数 `EventStream`，读提交时间与操作人；`DecideScope.now`） |
| PA-2 | P1 | `manifest-tools` 的 `SchemaIntrospector`、查询准入 | 状态 schema 漏掉 Jackson 会写进快照的只读计算属性；查询它们被拒为未知字段，生成的 `<State>Properties` 与 `<State>View` 也缺它们；9.x 可按它们查询与排序 | schema 收录 Jackson 写出的只读属性（`readOnly`），可查询（协调者定） | 已修（#5237：只读计算属性写进 schema 并标 `readOnly`，可查询） |
| PA-3 | P1 | `StateViewSources.kt:154-158`、`SchemaIntrospector` | 多态的 api 类型（`@JsonTypeInfo`/`@JsonSubTypes`）写成没有基类 `x-wow-class` 的裸 `anyOf`，`generateWowContractSources` 失败并挡住全部测试；绕开只能关掉 `publishState`，对端与 CoCache 就没有视图 | 多态节点写出基类的 `x-wow-class`，类路径上有时引用它 | 已修（#5237：多态节点写出基类的 `x-wow-class`） |
| PA-4 | P1 | `migrate-data` 的迁移映射 | 没有起草映射的工具：项目 A 要约 250 条事件与命令映射，几乎都是由 9.x 存储与 Manifest 可推出的 `snake_case → ClassName` | `migrate-data draft-map`：按名字起草，只留例外给人（协调者定） | 已修（#5261：`migrate-data draft-map` 按 9.x 生命周期表、精确名字、去分隔符的词三步匹配；匹配不上的写进映射的 `exceptions`，每行带 UNMATCHED、AMBIGUOUS、UNSOURCED、RENAMED、REVIEW 标记，`check` 只对它们警告；必须挡住的名字不进映射，由 `check` 照常拒绝） |
| PA-5 | P1 | 命令转换（D182） | 9.x 可由命令体推出聚合 ID（项目 A 有 9 个聚合、几十种命令这样做）；Wow 10 没有 `@AggregateId`，每种命令要手写一个 `CommandTransformer`，Saga 也要自己给出 ID | 编程模型的决定，待与用户讨论（推荐：声明式地由命令体给出目标 ID） | 已修（D378：A 内核、编译器与 Manifest #5249；B TypeScript 客户端与生成器 #5255；C 文档与技能 #5265。计算出的 ID 由服务从字段算出，手写请求给出的 `id` 被忽略，文档已写明） |
| PA-6 | P1 | 决定函数的依赖、测试 DSL | 决定函数里查询很常见（项目 A 有 53 处）；`QueryApi` 在 `:api`，按命令的调用者 `of(caller)` 查询是正路，但没写进文档；测试 DSL 的 `memoryQueries()` 对模型带依赖的聚合不能按类型读 | 写进技能与指南；核实宿主可把 `QueryApi` 注入领域依赖；`memoryQueries` 能按类型读 Manifest 里的每个状态 | 已修（#5235：命令函数经节点的 `QueryApi` 查询，测试 DSL 的 `memoryQueries` 按类型读） |
| PA-7～PA-11 | P2 | 编译器、KSP、决定内的查询错误、`GlobalIdGenerator` 的 KDoc、查询类型 | 返回非密封接口时被当作一个事件类（PA-7）；KSP2 不认源集的 `exclude`（PA-8，写进文档）；决定函数内的查询准入错误成了命令的拒绝 `wow.command.invalid`，客户端看到 400（PA-9：应为故障）；KDoc 仍说 wow-cosid 给全局 ID，api 里的静态 ID 无替代说明（PA-10）；`PagedList` 等不能子类化（PA-11，写进技能） | 编译器提示、决定内查询错误归为故障、KDoc 与技能 | 已修：PA-7（#5266：命令函数或 after 钩子在产生事件的位置返回非密封接口或抽象类即编译错误，类型别名按其所指判断，类型参数不产生事件；顺带修好别名被当作事件类的旧缺陷）；PA-8、PA-10、PA-11（#5229，文档与技能）；PA-9（#5235、#5238） |
| PA-12 | P1 | 9.x 与 Wow 10 的服务群 | 项目 A 与几个仍是 Wow 9 的服务互相消费事件与调用（api 模块之间还有环）；Wow 10 读不了 9.x 的主题，9.x 消费 Wow 10 的路径已放弃（G-4），api jar 不能混用：整个服务群必须一起切换 | 待与用户讨论（推荐：先写服务群级的切换顺序与环检查文档；9.x 主题桥只在真实服务群需要时再定） | 已修（D379，#5243：`migrate/cutover` 的「服务群切换」一节） |
| PA-13～PA-21 | P1～P2 | `skills/wow-migrate/references/v9-to-v10.md`、`migrate/*`、09 | 缺：外部调度到 `@OnSchedule` 的映射与不能映射的情形（逐个等待、固定速率）；持久化在事件与状态里的 9.x 类型（`Condition`、`Sort`）的升级；Kotlin 链式 `aggregateVerifier` 的映射；Gradle 9 与生成工厂的参数；`@MountedCommand` 须在领域模块；09 里 `wow-cocache` 仍写放弃（D364 已恢复）与按索引、联接的缓存；查询 schema 覆盖、读请求头的查询策略、直接聚合快照集合；带日期前缀的号段生成器与 cosid-migration；用 `DomainEventCompensator` 回填改为新订阅从头读 | 由做审查的子会话补技能与文档 | 已修（#5229：`wow-migrate` 技能与 `migrate/*` 各页补上这些缺口） |
| PA-22 | P2 | `skills/wow-migrate/references/v9-to-v10.md`、`migrate/source` | 技能说「私有函数被忽略」，而编译器拒绝带注解（`@OnCommand`、`@OnSourcing`、`@AfterCommand`、`@OnEvent`）的私有函数与 Kotlin `protected` 的处理函数；试迁移的检查组有 26 个这样的 9.x 私有函数 | 写清三种情形：带注解的私有与 Kotlin `protected` 是编译错误，改为 `internal`（Java 为包可见或公开）；只靠名字绑定的私有函数被忽略 | 已修（#5268） |
| PA-23 | P2 | `skills/wow-migrate/references/v9-to-v10.md`、`migrate/source` | 没有 9.x 命令函数注入的 `Clock`（含可空）及其测试 `inject { register(clock) }` 的映射；9.x 用 `@OnError` 把 `DuplicateAggregateIdException` 译成领域消息，在 V10 没有对应：重复创建在决定之前即答 `wow.aggregate.exists` | 映射为 `DecideScope.now`（毫秒时刻，默认取 `LocalDate.now()` 的辅助函数须传入决定的日期）与 `aggregateSpec(start = …)`、`advanceTime`；写明重复创建答错误码，客户端按码匹配 | 已修（#5270） |
| PA-24 | P3 | `test/test-query` 的 `MemoryQueries.snapshot`、`givenSnapshots` | 只收一个由 Jackson 序列化的状态对象；推荐的状态写法是私有 setter，测试造不出，只好写 JSON 映射，绕过 schema，拼错的字段静默取默认值 | 增加按模型经溯源演化状态的重载 `snapshot(model, id, vararg events, …)` 与对应的 `givenSnapshots`，保留按对象的重载 | 已修（#5271：`MemoryQueries.snapshot(model, id, *events, …)` 与 `givenSnapshots(model, …)` 按模型的溯源重放事件；模型不溯源的事件与空历史被拒绝；按对象的重载保留） |
| PA-25～PA-27 | P1～P3 | 编译器 | 试迁移重建时发现：溯源函数参数类型解析不出时 KSP 空指针（PA-25）；Wow 10 自己的 `QueryApi`/`NodeQueryApi` 被当作 9.x 类型拒绝，指南里在决定函数中查询的写法编译不过（PA-26，P1）；返回 Reactor 而缺 wow-reactor 时报错出现在生成代码里（PA-27） | 未解析类型报错并提示 V10 形状；可注入服务用白名单（`QueryApi`、`NodeQueryApi`、`CommandClient`），9.x 判断只决定措辞；缺 wow-reactor 时明确报错 | 已修（#5299） |
| PA-28 | P1 | `migrate-data` 的 `env:` 解析 | X-48 后 `ConfiguredConnections` 里构造参数遮住了解析后的配置，只有源 URI 被解析；`source.database` 写成 `env:X` 时静默读名为 `env:X` 的空库，`draft-map` 报 0 个聚合而退出码为 0 | 每个 `env:` 字段解析一次；源库中一个事件流集合都没有时 `draft-map` 与 `check` 报错并写出所读的库 | 已修（#5305） |
| PA-29 | P1 | `migrate-data check` | 要求源库里每个 9.x 聚合与 PrepareKey 都有映射，无法声明「本次不迁移」；分阶段迁移与参考文档里「多个 V10 存储各用一份映射」的做法都过不了 `check`，与文档矛盾 | D383：映射的 `excluded`（带理由），`check` 报为警告，`import` 跳过 | 已修（#5305） |
| PA-30 | P1 | `migrate-data` 读 9.x 框架请求头 | 较早的 9.x 把框架请求头写成点分隔（`upstream.name`、`upstream.id`、`trace.id`、`command.operator`、`command.wait.*`），工具只认下划线；项目 A 的库中约 2% 的流因此被当作没记录命令，用 `defaultCommand` 绕开会记错命令并丢掉操作人、trace 与上游 ID；极少数流只有 `command.name` | 两种写法都认，最早的 `command.name` 形状也认；以真实形状做测试夹具 | 已修（#5305） |
| PA-31 | P1 | `migrate-data compare` 构建模型 | 只能为接口类型的依赖造替身，领域服务依赖 `java.time.Clock` 这类具体 JDK 类型时模型建不起来 | `compare` 只重放溯源：为 `Clock`、`ZoneId` 提供 UTC 实例，其他具体类型明确报错并建议依赖接口 | 已修（#5305） |
| PA-32 | P1 | 查询 schema 的读取与合并 | 状态里的密封多态值类型、各子类型把判别字段保留为自己的字符串属性时，Manifest 写成 `allOf[string, const]`；读取器不读 `const`，合并判为冲突，宿主启动失败；子类型在某一部分把判别字段列为必填也冲突 | `const` 与无类型的 `enum` 按收窄读取，合并后再按值定类型；`allOf` 的必填取并集；真冲突仍拒绝 | 已修（#5306） |
| PA-33 | P2 | Saga 收到的状态事件 | 状态事件不带聚合的 tags，Saga 要用只能读快照，快照滞后时可能为空；9.x 的只读状态聚合带 tags | D385：`StateEvent` 带上提交后的有效标签 | 已修（#5342）；D390 之后 Saga 改为直接读状态上的访问属性，`StateEvent.tags` 随之去掉 |
| PA-34 | — | 分阶段迁移时的跨服务事件 | V10 的 Saga 订阅不到仍在 9.x 上的服务的事件流 | 已由 D379 定：互相消费事件的 9.x 服务群一起切换，9.x 主题桥是 10.x 的候选 | 已定（D379） |
| PA-35 | P2 | 命令的 HTTP 路由 | 无法让某个命令不开放 HTTP 路由（9.x 的 `@CommandRoute(enabled = false)`），内部命令都成了公开路由，只能靠授权挡 | D388：命令类上标 `@InternalCommand`，公开端口不挂路由、OpenAPI 与 TS 客户端不含它，内部端口照常受理服务调用 | 已修（#5353，D388：`@InternalCommand`，公开端口不受理） |
| PA-36 | P3 | 重复创建的错误提示 | 领域代码无法自定义重复创建的提示（9.x 用 `@OnError` 改写）；V10 在决定之前即答 `wow.aggregate.exists` | D386：维持框架错误码，客户端按码匹配（PA-23 已在迁移文档写明） | 已定，不改代码 |
| PA-37 | P2 | 测试 DSL 的决定纯度检查 | 版本 0 时序列化初始状态，状态里有尚未赋值的 `lateinit`（9.x 常见）时创建命令失败；生产不开此检查 | 只在版本 0 且原因是 `lateinit` 未赋值时跳过；规则放宽为「创建事件应用后每个属性都有值」 | 已修（#5308） |
| PA-38 | P1 | 状态 schema 漏掉接口默认 getter | 以 Kotlin 默认模式（`-jvm-default=enable`/`all-compatibility`）编译的类会为接口默认 getter 生成桥接方法，schema 内省按 Method 同一性匹配 Jackson 的 getter 而对不上，计算属性被丢掉，按它查询被拒；`<State>Properties` 却有 | 按 getter 名在声明类型的层级中匹配；两种 jvm-default 模式的夹具 | 已修（#5309；项目 A 的构建上确认属性数 13→23） |
| PA-39 | P1 | KSP 增量编译丢上下文 | 增量的一轮里 `@BoundedContext` 文件未被处理时，重新生成的模型工厂丢了上下文的默认值 | 每轮从全部文件读上下文，并把上下文文件列为每个以之命名的输出的源 | 已修（#5308） |
| PA-40 | P2 | 宿主的 `CommandClient` | 转换器注入 `CommandClient` 时启动报循环依赖（client → gateway → transformers） | 客户端首次使用时才构建（`DeferredCommandClient`） | 已修（#5309） |
| PA-41 | 文档 | 迁移指南 | 9.x 的 `limit(Int.MAX_VALUE)` 在 V10 被拒（进程内列表上限 1000） | 写明改用 `unbounded()` 及其适用场合 | 已修（#5309） |
| PA-42 | P1 | `migrate-data` 的单条坏流 | 9.x 一条流的聚合 ID 为空串，`check` 不报，`import` 中途失败；映射只能排除整个聚合 | D384：`check` 逐条报无法导入的流，`excluded` 可排除单条流 | 已修（#5311） |
| PA-43 | P2 | 旧事件中的 null 元素 | Wow 10 的 Jackson 3 Kotlin 模块拒绝非空 `List<String>` 中的 null，9.x 的 Jackson 2 接受，部分旧事件解码失败 | 保持严格；迁移指南写明，用 `@Upgrade` 清理旧形状，由 PA-44 检出 | 已修（#5311；后由 #5312 改正措辞：9.x 也是 Jackson 3） |
| PA-44 | P2 | `migrate-data` 读不出的事件 | 快照步骤遇到第一个读不出的事件就整体中止，无法一次看到全部 | `check --decode` 逐条列出全部读不出的事件；快照步骤也报全 | 已修（#5311） |
| PA-45 | P2 | 命令转换器的拒绝 | 转换器抛出的任何异常都成了 500，无法以领域码拒绝 | 转换器像决定函数一样 `reject(code, message)`，按所属聚合限定错误码，HTTP 用该码的状态（默认 422），不写入任何东西 | 已修（#5319） |
| PA-46 | P0 | `WowJson` 读回没有 setter 的 `val` | Wow 10 的 `WowJson` 未开 `ALLOW_FINAL_FIELDS_AS_MUTATORS`（9.x 开着；两者都是 Jackson 3），由溯源填充的 `val` 集合与带初值的 `val` 从快照读回为空或初值；查询侧类型化读取、命令体与 CoCache 视图受影响，读另一聚合或收到 9.x 形状命令的决定可能据此做错 | 开启该特性，与 9.x 一致；各解码路径一一加测试 | 已修（#5312；项目 A 上确认） |
| PA-47 | P1 | 分区启动时的租约 | 租约在拿到时开始计时，分区却等运行时、入口与定时器启动完才进入续约，启动一长即被判过期；项目 A 大历史的冷启动中 16 个分区掉 14 个、约 2.5 分钟不恢复 | 拿到租约即登记为「启动中」，续约覆盖启动，启动完才接收命令；启动中租约丢了就不开 | 已修（#5314；项目 A 冷启动 97 秒 16/16 无掉线） |
| PA-48 | P3 | `migrate-data snapshot` 的实例枚举 | 从 9.x 源枚举实例，9.x 不可达就跑不了 | D387：维持从 9.x 源枚举，生成快照是切换闸门的一步，此时源必然可读；之后的快照由服务自己写 | 已定，不改代码 |
| PA-49 | P2 | 被排除实例的附属数据与空白租户 | 被排除实例的预留键与补偿执行仍被导入；租户为空白的流在解析时失败；`--decode` 遇缺必填字段的流中止 | 被排除实例什么都不导入；`check` 报空白租户；`--decode` 跳过并继续 | 已修（#5317） |
| PA-50 | P3 | 未标注的命令体 ID | 9.x 惯例由 `Identifier.id` 指明聚合，迁移后易漏 `@AggregateId`，Saga 不带 ID 发出的创建命令会得到生成的 ID | 编译警告 `UnmarkedAggregateId`：命令有 `id` 字符串属性而无 `@AggregateId` 时提示；路径绑定的 `id` 提示删除 | 已修（#5318） |
| PA-51 | P2 | 内核 StateJson 的 getter 填充集合 | `USE_GETTERS_AS_SETTERS` 使 getter 返回不可变集合的事件让聚合无法加载 | 去掉该开关，与 9.x 一致；集合藏在自身 getter 后的状态改为重放 | 已修（#5315） |
| PA-52 | P1 | 事件与状态中的 Wow 查询类型 | 写得进、读不回（`QueryField` 无反序列化器）；迁移指南本已约定不存这些线上类型 | 构建时拒绝：事件或状态的类型结构中出现 `me.ahoo.wow.api.query` 的协议类即失败并写出路径（不注册查询模块，先前的决定已撤回） | 已修（#5316） |
| PA-53～PA-58 | P1～P3 | `migrate-data` 的工具与文档 | 快照失败的提示应指 `tail`（PA-53）；`tail` 需 9.x 侧的索引（PA-54）；`compare` 的时区（PA-55）；删除了实例却无删除映射时 `check` 不报（PA-56）；`--restart` 不重写已导入的流（PA-57）；退役改变过状态的事件会丢状态（PA-58） | PA-54 已修（#5320：`check` 报缺失的索引，文档写明是切换前提）；PA-53、PA-55～PA-57 已修（#5323：`check` 报未映射的 9.x 删除，`snapshot`、`compare` 与 `import` 的提示更明确）；PA-58 的规则（`retire` 只用于没有改变状态的事件）写进迁移文档与技能（#5322） | 已修（2026-11-02 复核） |

## 12. 缺陷审计的 issue（2026-11-05～11-08，基线 `10.0.0-beta.1` 之后的 `main`）

2026-11-05 至 11-08，一轮缺陷审计在 GitHub 上开了 103 个 bug issue（#5445～#5566 之间，P1 13 个、P2 90 个）。每个 issue 自带预期行为、复现步骤、根因（带文件链接）与一份可运行的复现测试，是该发现的完整记录；本节不逐项抄录，只登记批次：按根因所在的文件分组，同一组文件同一时间只交给一个会话。2026-11-09 登记时都未修。

规则：

- 子会话先在当前 `origin/main` 上复现，再修；复现测试改成断言正确行为的回归测试。不再复现或属于设计如此的，停下来报告，由协调者关闭 issue 并写明理由。
- 修复要改 SPI、存储或线上格式、公开 API，或改变运维动作的承诺时，先把方案报给协调者，定下后再做；需要登记的记为决定。
- 一个 PR 修一个 issue 或同一处代码的几个 issue，描述里写 `Fixes #n`；状态以 issue 的开闭为准，本表只记批次的去向。
- 顺序：先 P1（加粗），再按批次修 P2；同一模块的批次串行。

| 批次 | 范围 | 文件 | issue（P1 加粗） | 状态 |
| --- | --- | --- | --- | --- |
| BA-1 | 运行时：订阅、停放与失败台账（6） | `core/runtime` 的 `subscription/`、`source/`、`ledger/`、`ContractDirectory`、`SubscriptionBindings`、`ParkedOps`；#5506 另含 `:spi` 的 `FailureLedger` 与两个后端的台账 | **#5466**、**#5481**、**#5542**、#5459、#5506、#5526 | 修复中（会话 rt-subs-p1，2026-11-09）；#5506 定为 D404；#5481 按安全失败修，完整恢复转 10.x |
| BA-2 | 运行时之外的 P1（4） | `edge/client`、`compiler/manifest-tools` 的 `SchemaCompatibility`、`migration/migrate-data` 的 `StateComparison`、`platform/analytics/catalog` 的 `DatasetCompiler`；四个模块各一个 PR | **#5456**、**#5457**、**#5458**、**#5486** | **已修**（2026-11-09：#5574、#5575、#5576、#5578；#5456 的视图名为 D403；#5575 顺带修了 `$ref` 旁的兄弟关键字盖住定义自身约束的同根缺陷） |
| BA-3 | 运行时与内核：写入、等待、预留、生命周期（7） | `SnapshotWrites`、`Kernel`、预留（内核、运行时与两个后端）、`WaitCoordinator`、`NodeLifecycle`、`CommandIndex`、`DerivedRequests` | **#5445**、**#5474**、**#5480**、#5475、#5509、#5518、#5560 | 三个 P1 修复中（会话 kernel-writes-p1，2026-11-09）：#5445 在 #5580；#5474 定为 D405，改 `SubscriptionStart.kt` 与 `TopicEntries.checkpointOf`（与 BA-1 同文件不同函数）；#5480 由 `AttemptScope` 在每个声明键的事实上确认预留，不改存储与格式，余下的窗口（过期的释放恰在重新预留与其确认之间重放、键又被别的聚合取走）以 `ReservationLost` 可见，与待定预留在确认前过期同类，考虑过给已确认的预留加持有（改 SPI 与格式）而未取。四个 P2 待修 |
| BA-4 | 运行时：定时器与调度（7） | `TimerRunner`、`TimerOptions`、`ScheduleSlots`、`ContextTimers`、`ScheduleJobRuntime`，内核的 `CronExpression` | **#5446**、#5453、#5467、#5488、#5489、#5515、#5525 | 待修（#5446 与 BA-1 的 #5506 同改 `ParkedOps.kt`，BA-1 之后） |
| BA-5 | TypeScript：客户端、生成器与 React（11） | `typescript/wow-client`、`wow-generator`、`wow-react`；顺带处理 X-86 | **#5448**、#5452、#5471、#5484、#5485、#5497、#5498、#5529、#5530、#5537、#5538 | 修复中（会话 ts-client-gen，2026-11-09） |
| BA-6 | TypeScript：视图引擎（17） | `typescript/wow-view-engine`；#5546 另含 `wow-view-store` 与 `platform/view-store` | **#5447**、#5472、#5473、#5478、#5490、#5502、#5508、#5513、#5516、#5517、#5545、#5546、#5556、#5557、#5562、#5563、#5566 | 修复中（会话 view-engine-bugs，2026-11-09） |
| BA-7 | 契约工具与编译器（11） | `compiler/manifest-tools`（BA-2 的 #5458 之后）、`compiler/compiler`、`compiler/gradle-plugin`、`api/manifest` | #5450、#5462、#5463、#5487、#5495、#5496、#5514、#5527、#5528、#5539、#5564 | 待修 |
| BA-8 | 查询：内存后端与各后端的编译器（20） | `query/query` 的内存后端与窗口、`api/query-api`、PostgreSQL、ClickHouse 与 Elasticsearch 的过滤和聚合编译器 | #5451、#5468、#5469、#5470、#5482、#5483、#5491、#5492、#5499、#5501、#5507、#5511、#5512、#5519、#5524、#5535、#5536、#5544、#5555、#5559 | 待修（按后端拆成几个 PR） |
| BA-9 | 边缘：JVM 客户端与 HTTP（6） | `edge/client`（BA-2 的 #5457 之后）、`edge/http` | #5454、#5455、#5479、#5540、#5553、#5565 | 待修 |
| BA-10 | 迁移工具（6） | `migration/migrate-data`（BA-2 的 #5486 之后）；#5510 另含 `:spi` 的 `FailureLedger`（BA-1 之后） | #5449、#5476、#5477、#5510、#5543、#5554 | 待修 |
| BA-11 | 宿主与存储的生命周期（5） | `host/spring-boot` 的装配、`HostStores`、`WowNode`，PostgreSQL 的租约连接 | #5461、#5493、#5494、#5500、#5523 | 待修 |
| BA-12 | 平台服务（2） | `platform/analytics` 的 `CatalogWatcher`、`platform/ops` 的停放条目 | #5460、#5561 | 待修 |
| BA-13 | 发布自动化（1） | `release-plan.mjs`、`image-deploy.yml` | #5464 | 待修（改发布自动化，用户 2026-11-09 已批准） |

## 13. 存储结构与索引的性能审查（2026-11-09，基线 `6405a72076`，只读）

用户 2026-11-09 要求从性能角度审查存储层的存储结构与索引。四个后端（PostgreSQL、MongoDB、ClickHouse、Elasticsearch，约 2.7 万行）分三路只读盘点：每个表或集合的结构与索引、代码里每条语句按冷热分级后各自用到的索引、带建表语句与查询语句位置的发现；关键发现由协调者对照代码核实。**没有运行任何数据库，以下代价都由代码推出，量级须在 Actions 上测过才作数**（进度 §5：负载只在 CI 上测）。路线图 §6 性能清单里已做或已否决的不重复（提交时间索引、快照批次异步提交、一次聚合读快照与尾部、聚簇集合等）。

结论：

- **写路径的结构是对的。** 追加一条事件流在 PostgreSQL 写 1 行、3 个索引项并更新分区计数行，在 MongoDB 写 1 个文档、3 个索引项（分片时 4 个）；取上一版本是只读索引的反向扫描，请求去重是索引点查，订阅的尾部读取走主键范围；租约、检查点、台账、预留都是主键点查或前缀。MongoDB 分片后热路径都带分片键、落在单个分片。
- **问题集中在读平面与追赶路径**：键集分页与排序没有索引可用、事件查询没有租户或聚合前缀、追赶时尾部多读、快照重建的扫描每轮从头开始，以及 ClickHouse、Elasticsearch 上写入粒度过细与按行解析 JSON。
- **有一组改动要动存储格式或索引定义，GA 冻结格式之后再改就要迁移**，须在 GA 前定（下表「格式」一栏为「是」的各项）。

| 编号 | 级别 | 后端 | 发现（已核实的标「核」） | 处置建议 | 格式 | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| MG-1 | P1 | MongoDB | 快照的键集分页按 `(tenant_id, aggregate_id)` 字段排序，而 D277 之后快照的键是 `_id` `{t, a}`，没有任何索引给出这个顺序：每一页都把该租户的全部文档取出、在内存里排序，翻完一遍是平方级（核） | 排序以 `(tenant_id, aggregate_id)` 结尾时改按 `_id` 排序，游标条件写成 `_id > {t, a}`，顺序相同、游标格式不变 | 否 | 待排期 |
| MG-2、PG-11 | P1、P3 | MongoDB、PostgreSQL | 声明的查询索引是 `(tenant_id, 键…)`，而查询的排序总要追加决胜列（`_id` 或 `aggregate_id`）：MongoDB 上凡按声明字段排序的查询都做阻塞排序，无上限的流式读取会溢出排序内存；PostgreSQL 上是逐段的增量排序，低基数字段（状态类）代价高（MongoDB 一侧核） | 声明索引的键末尾加上决胜列；已有的声明索引在下次启动时重建 | 是 | 待定 |
| MG-3、PG-7 | P1、P2 | MongoDB、PostgreSQL | 事件查询没有可用的前缀：MongoDB 的日志索引都以 `partition` 开头，查询却只给租户与聚合 ID，于是全集合扫描加排序，分片时问遍每个分片（核）；PostgreSQL 的版本键是 `(aggregate_id, tenant_id, version)`，只按租户查时没有前缀 | MongoDB：查询钉住租户与聚合 ID 时由分区函数补上 `partition`（不改格式），并在投影里去掉 `transport` 等不返回的字段。PostgreSQL：版本键改成 `(tenant_id, aggregate_id, version)`，追加与加载都是两列等值、代价不变，但要重建约束、访问遍历的游标顺序随之改变 | MongoDB 否；PostgreSQL 是 | 待定 |
| PG-1 | P1 | PostgreSQL | 日志表的键列 `tenant_id`、`aggregate_id`、`request_id` 没有声明排序规则，用数据库默认的；快照表与台账的键都声明了 `COLLATE "C"`（核）。默认规则不是 C 时，每次追加在版本键与请求键上的几次 B 树下降都走 `strcoll` 而不是 `memcmp`，事件查询的排序也按区域规则，与快照查询不一致 | 日志、预留、定时器的键列声明 `COLLATE "C"`：框架的行为不该取决于库的默认排序规则。已有的表要重建两个唯一索引，进行中的访问遍历要重来 | 是 | 待定 |
| MG-5 | P2 | MongoDB | 追赶时尾部读取对上下文里每个聚合类型的集合各取 `tailBatch`（500）条完整文档，合并后至多交出 500 条，其余下一批再读：积压时读放大约为类型数 K（核）。稳态小积压不受影响 | 每个分支加上界 `_id <= 位置(last + tailBatch)` | 否 | 待排期 |
| PG-2、MG-6 | P2 | 两者 | 快照重建的扫描每一轮（5 秒）都从第一个键重新开始（`SnapshotMaintenance.rebuild` 里 `after = null`），已重写的行不再匹配却仍被扫过：总读取量是表大小的平方级，100 万行约 5×10^8 行（核）。两个后端在谓词上都没有索引 | 在运行时修：持有重建租约期间把 `after` 留到下一轮，租约重新取得或修订变化时才从头。不必加索引 | 否 | 待排期 |
| PG-3 | P2 | PostgreSQL | 多键排序的键集分页把游标条件写成 `(k0 > ?) OR (k0 = ? AND k1 > ?)…`，B 树不能把这种 OR 当索引条件：扫描从租户的第一行开始、把游标之前的都过滤掉，深页的代价等于它的位置；预算只限制偏移分页，不限制键集深度 | 在已有的 OR 之外再 AND 一个冗余的前导界（升序 `k0 >= ?`；降序且列非空时 `k0 <= ?`） | 否 | 待排期 |
| PG-12、MG-8 | P3、P2 | 两者 | 预留表的属主索引没有任何语句使用（预留的端口没有按属主的读取），却让每次预留的插入与每次属主变更多写一个索引项（核） | 删除该索引；运维要按属主查时再建 | 是（去掉一个索引，列不变） | 待定 |
| CH-1、ES-2 | P1、P2 | ClickHouse、Elasticsearch | Sink 的订阅每条记录调用一次写入（`SinkSubscription.write` 传 `listOf(record)`，核）。ClickHouse 的 Sink 只合并上一次 INSERT 进行期间排队的写入，且同步插入；开历史时一次 INSERT 经两个物化视图在三张表各生成一个 part。Elasticsearch 的事件 Sink 没有队列，每次提交一个 `_bulk`、一次 translog 落盘。批次实际多大取决于订阅端的并发，读代码定不了 | 在根上修：Sink 的订阅按批交给 Sink（像快照订阅那样成批），或 ClickHouse 的 Sink 等一小段时间或攒够一批再发；先在 Actions 上量每个 part 的行数与每次 `_bulk` 的文档数 | 否 | 待测 |
| CH-2 | P1 | ClickHouse | 扁平视图 `_state_flat` 是在 `_state FINAL` 之上逐列 `JSONExtract`（核），过滤与排序都落在这些表达式上：读取范围只被租户前缀缩小，查 20 行的代价等于读出并解析该租户的全部当前状态。性能第 23 项（受治数据集的半连接）是同一根因的另一面：`access` 是 JSON 文本、没有带类型的列 | 插入时算一次：把扁平列做成 `_state` 上带类型的 `MATERIALIZED` 列（访问属性同理，内置的 `owner`、`space` 直接用已有的类型列），视图只选列。与第 23 项一并设计 | 是 | 待定 |
| CH-3 | P1 | ClickHouse | 分析目录编译出的数据集视图把每个会合并的来源写成 `(SELECT * FROM t FINAL)`（核），租户条件只加在主体上。右表能否被下推过滤取决于服务器版本，未验证；不能时，查询代价随被关联表的总量增长 | 先在 Actions 上用 `EXPLAIN indexes=1` 看右表的主键条件；不下推就把视图做成带租户参数的，或在来源子查询里显式加租户条件 | 否 | 待测 |
| CH-5 | P2 | ClickHouse | `_state` 与历史表共用同一组列，带着事件载荷数组与请求头（核），读当前状态的都用不到 | `_state` 用自己的列清单，物化视图只选这些列 | 是 | 待定 |
| ES-1 | P1（属性常变时） | Elasticsearch | 一次提交改了访问属性，就对整个索引强制刷新一次，再用 `_update_by_query` 重写该聚合此前的全部版本（核）。这是 D390「历史跟随当前属性」的代价，属性常变时一个聚合一生是平方级 | 随 CH-1、ES-2 的成批：一批一次刷新、一次盖章；文档写明这类属性的代价 | 否 | 待排期 |
| ES-3 | P2 | Elasticsearch | 状态与事件体里每个字符串字段都同时建 `keyword` 与 `.text`（核），包括从不做全文检索的 id、枚举与编码：字符串的倒排索引约翻倍，每次快照重写与每个事件文档都多一次分词 | 只给标明可检索的字段加 `.text` | 是 | 待定 |
| ES-4 | P2 | Elasticsearch | 快照写入默认 `wait_for`（核）：每批要等到下一次刷新才返回，最长约 1 秒；命令的 SNAPSHOT 等待是否包含这次写入未能读定 | 非主存储的快照默认不等刷新（D327 已由主存储答命令与状态读），需要读己之写的查询再开 | 否（默认值） | 待定 |
| MG-14 | P2 | MongoDB | 没有哪个存储强制从主节点读：连接串带 `readPreference=secondary*` 时，状态加载会读到从节点的旧状态而引出版本冲突，而运维文档说状态读总走主节点 | 状态加载与日志读取显式用主节点 | 否 | 待排期 |
| PG-4、PG-5、PG-13 | P2 | PostgreSQL | 原地更新的表都没有留空间（没有任何表设 `fillfactor`）：快照保存只要有一个声明在 `state` 上的索引就失去 HOT，每次保存写全部索引项并留下死元组；全部分区计数行挤在一个堆页上，每次追加都更新它；检查点与租约同理 | 先量 HOT 比例（`n_tup_hot_upd / n_tup_upd`）与死元组，再定计数表、快照表、检查点表的 `fillfactor`；文档写明每个声明的状态索引都让快照保存多付一份 | 否（存储参数） | 待测 |
| PG-8、MG-7 | P2 | 两者 | 台账的到期扫描按主键顺序排序，而到期索引按 `(partition, next_attempt_at)`：要么走主键范围把别的节点的分区与「永不」的条目都读一遍，要么走到期索引再排序。大量停放之后一轮是平方级。取决于计划器 | 先看计划；坏的话 MongoDB 把到期索引换成带排序键的，PostgreSQL 把「未安排」存成哨兵值使谓词可寻址。与 D404 的台账改动同批看 | 视做法 | 待测 |
| PG-9 | P2 | PostgreSQL | 带类型的状态表达式对 `state` 引用两次（`CASE WHEN jsonb_typeof(state #> p) … THEN state #>> p`），未建索引的过滤与排序每行把 TOAST 的状态解压两次以上 | 改成只引用一次的不可变形式；表达式文本也是索引文本，声明的表达式索引要重建 | 是 | 待测 |
| PG-10 | P3 | PostgreSQL | 缓存未命中的加载为取最近 32 个请求键回表 32 次（版本键里没有 `request_id`、`command_type`） | 版本键 `INCLUDE` 这两列可做成只读索引，但每次追加的索引项变大：先量再定 | 是 | 待测 |
| MG-4 | P1（分片时） | MongoDB | 不带 ID 的创建命令按请求 ID 查重要问遍所有分区（D277 ② 已接受副本集上的 64 个点查）；分片集群上是每条这类命令问遍每个分片 | 存储层没有解法（分区取决于聚合 ID）；在分片部署的文档里写明，或另议由请求 ID 确定性地给出 ID | 否 | 已登记 |
| MG-10 | P2 | MongoDB | 快照加载的过滤带着 `tenant_id`、`aggregate_id`（为分片定位），在副本集上因此走不了 `_id` 的快速路径 | 未分片时只按 `_id` 过滤；按 §6 的 ABAB 量冷缓存下每命令的 mongod CPU | 否 | 待测 |
| MG-11 | P3 | MongoDB | 检查点、租约、成员、台账、定时器的文档各带一个没人读的 ObjectId `_id`，另有唯一的自然键索引：每个文档多一次唯一索引插入 | 像 D277 对快照那样以自然键作 `_id`、去掉二级唯一索引；收益除定时器外都小 | 是 | 待定 |
| CH-6、CH-7 | P2、P3 | ClickHouse | 没有任何列编解码器（状态与载荷是存储的主体，时间与版本在排序键内单调）；低基数字符串不是 `LowCardinality`；`id`（`ref@version`）可由其他列导出 | `state`、`payload` 用 ZSTD，时间与版本用 Delta；量 `system.parts_columns` 的压缩比再定 | 是 | 待测 |
| 增长 | P3 | 全部 | 日志无界增长且不分区，按时间清理没有索引可用；从不删除的行：已触发与已取消的定时器、过期的待定预留、未正常离开的节点的成员记录、退役订阅的检查点与租约。03 §5 的「保留」仍是待细化 | 与保留策略一并设计（定时器的删除涉及重放语义），不在本轮 | 待定 | 已登记 |
| 其余 | P3 | 各后端 | PG-6（访问遍历重写它盖章的每一行日志，遍历后建议 VACUUM）、PG-14（只存取不查询的列用了 jsonb）、MG-12（冲突路径与遍历多取了字段）、MG-13、CH-8（偏移不设上限、带总数时扫两遍）、CH-9（高精度时间游标被截到毫秒，即 #5507）、CH-10、ES-5（新增映射字段无限速地重写全索引）、ES-6（事件索引单主分片、无滚动）、ES-7（包含与后缀匹配是前导通配符）、ES-9 | 逐项排期；ES-5 加限速与分片并行，ES-6、ES-7 写进运维与查询文档 | 多数否 | 已登记 |

三路盘点没能读定、须实测的：数据库的默认排序规则（决定 PG-1 的量级）、有多个候选计划时计划器的选择（PG-8、MG-7）、Sink 订阅的并发（决定 CH-1、ES-1、ES-2 的批次）、ClickHouse 的版本对 JOIN 下推与 `FINAL` 下跳数索引的处理（CH-2、CH-3）。
