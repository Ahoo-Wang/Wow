# Wow 10 审查台账

日期：2026-10-22（初稿 2026-10-19；2026-10-22 按 `origin/main` 逐项复核状态）。状态：持续维护（主会话在派工前登记，修复合并后更新）。上位文档：[进度](progress.md)。

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
| R2.4-3 | P2 | Manifest 兼容门、Gradle 插件 | **重审还原**：缺少基线时 `checkManifestCompatibility` 通过，只在报告里写一行，`check` 为绿却没有门（`ManifestTools.kt:64-67`、`WowPlugin.kt:173`）；当前主版本高于基线后所有破坏性变更都放行且不告警，直到有人更新基线（`ManifestCompatibility.kt:62-70`） | P.20 之后 | 待修（无基线时仍通过、`newMajor` 放行破坏性变更；2026-10-22 复核） |
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
| X-6 | P2 | `:kernel`、`:compiler` | 命令与事件的命名有三套：编译器读 `@Name`，内核 DSL 默认名（`AggregateModelDsl.kt:246`）与 `CommandTypes.SIMPLE_NAMES`（`Kernel.kt:1112`）不读；手写或 Java 模型与 Manifest 的名字可能不一致。统一为一个命名函数，DSL 的 `name=` 与 `@Name` 不一致即失败 | 独立修复（K2） | 待修（D285 拉回 GA 前，见 R5.A-5） |
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
| X-18 | P2 | 基准 ABAB | 饱和扫描的压测端只有一个核，e1-pg-uniform 与 mixed-pg 两边都在 0.97～0.98 核到顶，饱和比测的是压测端；三个 PG 套件在 `noise.json` 里没有 A/A 阈值，9 个比值都无法判定显著 | 压测端多核或多进程；压测端核用量过约 0.9 时把饱和与校准行标为无效；补 A/A 阈值 | 进行中：#5065 做了前三项（PG 的核拆分、压测端瓶颈守卫、PG 的 A/A 阈值）；#5121 给 Mongo 拆两核、在途上限由配置派生、按进程树计 CPU、补 Mongo 的 A/A 阈值，并修正基准从 D352a 起发错的 match-all 条件。新发现：Mongo 自身约一核到顶，延迟阈值因此偏宽，下一步给 Mongo 固定独立的核 |
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
| X-34 | P2 | 集群路由、内核的冲突重试 | M5 Max 饱和快照（#5224）里 3 节点同机、PostgreSQL、并发 1024 时，E1 有 7 条、E3 有 9 条命令答 409 `wow.command.contended`（并发写者一再抢占版本号）；按设计一个聚合只有属主在写，疑为单机过载下租约交接、转发失败后退回本地执行而两个节点同写；该运行没有保留节点日志 | 在 Actions 的多机集群运行（L4 的 `e5-*`）与 GA E2E 中核查是否出现；出现则保留节点日志定位 | 待查 |
| X-35 | P2 | Kotlin 的 ABI 转储 | 全部参数有默认值、主构造器标 `@InternalWowApi` 的类，Kotlin 生成的无参构造器在干净编译下可能丢掉该标记，转储多出一个公开的 `<init>()`；增量编译保留标记，故本地通过而 CI 失败（4c 的 `ParkedRetryOptions`，#5227） | 内部改用该类的 `@InternalWowApi copyWith`，不在别处调用其构造器；转储中出现意外的公开无参构造器时先疑此因 | 已绕开（#5227），记作已知现象 |
| X-36 | P2 | `:runtime` 测试 | `SubscriptionOrphansTest` 偶发 `ConcurrentModificationException`（PA-2、PA-3 的全量检查中出现一次，重跑三次都通过）：疑为告知无主检查点时遍历的集合与并发注册的订阅共享 | 查明被并发修改的集合与线程；若产品代码在启动时并发读写同一集合，即改为快照或加锁，测试稳定复现后再修 | 待查 |
| X-37 | P2 | 测试工具的开关 | 重新生成快照与金样的开关借用 `wow.` 前缀（如 `-Dwow.snapshot.update=true`）；整套检查带上它时，Spring 宿主把它当作未知的 `wow.*` 键拒绝启动（D302），宿主测试全部失败 | 测试工具的开关移出 `wow.` 命名空间（如 `wow-test.snapshot.update`），或只由各测试读取而不进 Spring 环境 | 待派 |

## 4. 第三轮架构审查 R3（2026-10-19，基线 `751c87569d`，只读）

角度：职责、清晰、内聚与耦合、性能、可用性、扩展、易用，并复核 9.x 能力对照。整体判断：内核、日志与订阅的正确性扎实，问题集中在边界——宿主里的语义、默认值、读路径对写路径的影响、社区后端的接入。

| 编号 | 级别 | 位置 | 现象 | 去向 | 状态 |
|---|---|---|---|---|---|
| R3.1-1 | P1 | `:spring-boot`、`:query`、`:runtime` | 读侧与存储布局的组装（约 2.3k 行）连同安全语义（`hides = abac \|\| policies`、`events` 兜底后端、作用域策略拼装）在 Spring 宿主里，A10 对读侧不成立 | R3-A（D237） | 部分（#4595；安全底线、读侧组合与对端路由仍在宿主，见 R5.C-2） |
| R3.1-2 | P2 | TS `wow-client`、`wow-view-store` | 10.x 的 npm 包根入口仍是 9.x 客户端（V10 在 `src/v10` 子入口），GA 后用户导入的是 9.x 协议 | D239 ①（GA 前清理 9.x） | 已修（#4659、#4669） |
| R3.2-1 | P2 | `:postgres`、`:mongo`、`:kernel`、`:kafka` | `TailPacer`、`VirtualThreads` 等逐字复制于多个后端 | R3-A | 已修（#4591） |
| R3.2-2 | P2 | 横切 | 主代码注释引用审查编号 439 处（流程产物，原文可能丢失） | R3 文档与规则 | 待修（主代码注释仍大量引用审查与决定编号，无规则约束；2026-10-22 复核） |
| R3.2-3 | P2 | `:query` | 公开 ABI 过大（`query.api` 2285 行，`schema` 包 122 个公开类），冻结面过宽 | R3-F | 部分（#4683：`query.api` 2285→1976 行，`schema` 公开类 122→81） |
| R3.3-1 | P1 | `:spring-boot` | 社区后端接不进宿主：`BackendKind` 封闭枚举、`HostStore` 构造 internal，`BackendFactory` 全有或全无 | R3-A | 已修（#4591，`StoreProvider`） |
| R3.3-2 | P2 | `:query`、`:tck` | `QueryBackendSpec` 在不发布的 testFixtures，`QueryBackend` 无 `@WowSpi`，没有 Sink 合同 | R3-A | 已修（#4591） |
| R3.3-3 | P1 安全 | `:query`、`:spring-boot` | 没有快照查询网关时（`snapshot=false`、内存快照未开查询、应用自带后端），`events` 对 ABAC 与应用策略默认放行 | R3-S | 已修（#4580） |
| R3.4-1 | P1 | `:spring-boot`、`:query` | 日志不在 PG/Mongo 时 `events` 退回内存全量扫描，每次查询读全部分区，无开关 | R3-S | 已修（#4580） |
| R3.4-2 | P1 | `:postgres`、`:mongo`、`:query` | 写库上的 `events` 查询与追加共用连接池、无并发上限、单条最长 30 s，可拖垮命令 | R3-B | 已修（#4598、#4613、#4632） |
| R3.4-3 | P2 | `:runtime` | 同时登记的等待没有上限（默认 `SNAPSHOT` 等待持有连接直到超时） | R3-B | 已修（#4598） |
| R3.5-1 | P1 数据丢失 | `:spring-boot` | `wow.backend=AUTO` 找不到持久后端时只告警并退回内存存储，重启丢数据（9.x 启动失败） | R3-D | 已修（#4578） |
| R3.5-2 | P1 | `:runtime` | 系统性故障按单个聚合停放（约 3 s 重试后停放，3 分钟起重试），下游抖动即停放所有有流量的聚合 | P.16 并入 | 已修（#4592） |
| R3.5-3 | P1 | `:spring-boot`、`:runtime`、`deploy/` | 没有健康模型：无 `HealthIndicator`，`isReady` 只看 RUNNING，K8s 用 TCP 探针 | R3-H | 已修（#4594） |
| R3.6-1 | P2 | `:postgres`、`:mongo`、`:kafka`、`:migrate-data` | 分区数 P 固定，没有重分区路径 | D240（槽位间接层 PS-1；在线分裂 10.x） | 部分（#4585 槽位间接层；在线分裂 10.x） |
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
| Q-8 | P2 | `:openapi` | 查询的 OpenAPI 描述太弱：`QueryRequest` 是只要求 `kind` 的扁平对象、`filter` 只有 `{op}`、响应是 6 种形状的 `anyOf`、`QueryDescriptor` 不透明（R6） | 契约与客户端保真 | 待修（Q5b，冻结后增量，未排期） |
| Q-9 | P2 | TS `wow-client` | `response.json()` 静默丢失 decimal 与 int64 精度（R8） | 契约与客户端保真（待定） | 已修（#4740） |
| Q-10 | P2 | `:query` | 相对时间默认用 JVM 时区（`FilterNormalizer.kt:63、226`），TS DSL 不发时区（R8） | 契约与客户端保真 | 已修（#4740、#4772） |
| Q-11 | P2 | `:clickhouse` 数据集 | 列表与偏移分页排序无唯一键兜底（`ClickHouseDatasetQueryBackend.kt:192-195`），并列时翻页不稳定；TCK 缺并列偏移分页用例（R4） | 小修 | 已修（#4695） |
| Q-12 | P2 | `:query` 计数缓存 | 计数缓存默认开启、按节点的快照检查点校验：跨节点时总数落后至多 30 秒而行是新的；events 模型复用快照检查点而日志在其前面（R1；场景 S1、S4） | 派生读的一致性 | 已修（#4687） |
| Q-13 | P2 | 一致性 | `ACCEPTED`/`COMMITTED` 等待之后没有最小版本或一致性令牌，唯一的办法 `?version=N` 要重放（R1） | 派生读的一致性（待定） | 已修（#4687；列表的位置令牌 10.x） |
| Q-14 | P2 | TS | 结果类型不随投影变化；事件体与聚合行无类型；9.x 的 `*State` 变体没有了（R7） | 契约与客户端保真 | 部分（#4740 的 `Projected`；其余为 Q5b） |
| Q-15 | P2 | 批量读 | 列表与 SSE 上限 1000 行、游标每页 100 行（`QueryBudget.kt:71-72、118`），HTTP 导出 100 万行需上万次往返（R7；场景 S2） | 并入 R3-B4（D253） | 已修（#4618） |
| Q-16 | P2 | 服务间读 | `SubscriptionContext` 没有状态读，对端契约不含查询（`PeerContracts.kt:39`），没有 JVM 查询客户端：Saga 与对端服务手写无类型的读（R7；场景 S3） | 类型化读 API | 已修（#4641） |
| Q-17 | P2 | 可见性 | 租户总被钉住，跨范围操作员无法跨租户查询，只能借道受信任的进程内代码（R3，要求本身受质疑；场景 S5） | 小修（待定） | 已修（#4701） |
| Q-18 | P2 | 演进 | `@QueryAlias` 只覆盖请求路径，响应只带新字段名，老客户端读到 `undefined`（门禁会报出，但没有增量的改名路径）（R6；场景 S9） | 契约与客户端保真 | 待修（Q5b，未排期） |

另：R3.3-2 已完成（`QueryBackend` 是 `@WowSpi`，规范在 `query/query-tck`，#4591）。数据集查询的舱壁随 7.2c（D247）。

## 6. 9.x 替代落空审查 M（2026-10-20，基线 `f98d366c4f`，只读）

起因：D187 放弃 `wow-apiclient` 时所说的「生成的客户端」只有 TypeScript（已由 D257 补上）。逐行核对 09 与 D183、D184、D187、D213、D229、D239、D244 声称的替代是否真实存在、是否公开、是否覆盖 9.x 的用法。协调者已对照代码核实 M-1、M-3。

| 编号 | 级别 | 范围 | 发现 | 归属 | 状态 |
| --- | --- | --- | --- | --- | --- |
| M-1 | P1 | `:runtime` `CommandGateway` | Saga 以外的代码不能向其他上下文发命令：网关对本节点不服务的聚合答 `UNHANDLED`（`CommandGateway.kt:307`），命令主题只有 Saga 写；9.x 任何 Bean 都能经 Kafka 命令总线发（`DefaultCommandGateway.kt:95`）；迁移 Skill 的映射（`v9-to-v10.md:591`）对此误导 | CMD-1（D259） | 已修（#4622） |
| M-2 | P2 | `:runtime`、Java | Java 调不了命令网关（`send` 返回 `Flow`、`execute` 是 suspend），文档与测试都没有桥接写法；09 引用的 D149 是错的 | D257 的 Java 门面；先补文档与冒烟测试 | 已修（#4682） |
| M-3 | P2 | `:spring-boot`、`:http` | 9.x 给应用自己路由的全局错误处理器（`DefaultGlobalExceptionHandler`，默认开启）在 V10 没有，应用端点的错误体静默变成 Spring 默认格式 | M-3（D259，默认开启） | 已修（#4677） |
| M-4 | P2 | `:clickhouse` Sink | 没有集群拓扑（9.x 默认 `ON CLUSTER`、复制表与分布式表）也没有命令表；集群用户须手写与 Sink 列一致的 DDL | 文档与公布列 DDL；集群 DDL 生成 10.x | 待修（文档：公布 Sink 列 DDL 与集群说明；集群 DDL 生成 10.x） |
| M-5 | P2 | 迁移 | ES 用户没有迁移指引：`migrate-data` 只支持 PostgreSQL、MongoDB，Skill 不提 ES，重建能否填满 ES 快照目标未核实 | 文档；`migrate-data` 的 ES 支持 10.x | 部分（ES 事件存储先迁到 MongoDB 已写明；`migrate-data` 直接读 ES 10.x） |
| M-6 | P3 | 迁移 | Kafka 命令主题与线路格式变了（`wow.<ctx>.commands`、`CommandWire`），外部命令生产者的切换没有文档 | 文档（与 M-1 相关） | 部分（命令主题已写明；外部生产者的线路格式待写） |
| M-7 | P3 | 可观测性 | 指标与 span 名称全部改名，Skill 与 07 没有对照，告警与看板会静默失效 | 文档（对照表） | 已修（#4976） |
| M-8 | P3 | 迁移 | `wow-cocache` 放弃但没有替代写法 | 文档 | 待修（文档：`wow-cocache` 的替代写法） |
| M-9 | P3 | 设计文档 | 09 多处过时或有误（D149 误引、ES 已实现、R3-H 与 R3-D 已合并、Skill 已覆盖的条目、`wow-apiclient` 应指向 D257） | 协调者刷新 09 | 部分（09 第 25、134 行的出处与状态待更正） |

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
| R5.B-7 | P2 | `:runtime` `ServiceNode` | 1,473 行，组装、生命周期、门面三合一；`topicSubscriptions` 漏出内部类型 | 批 1（getter）、批 5（拆分） | 部分（#4929：getter；拆分随批 5） |
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

用户 2026-10-30 提出：交互式 UI 从未按人的方式走过一遍。第一组走查运维控制台与分析目录控制台（子会话 ui-walk-consoles）：两个服务按各自 e2e 的方式起在开发与 `cosec` 两种配置下（后者在 e2e 的网关替身之后），以账本与数仓模拟器灌入真实形态的数据，用浏览器在 1440、1100、1024、375 宽度、深浅色与中英文下逐页、逐操作走查。共 44 项（P0 1、P1 7、P2 36）。总览的数字与各队列一致，两个控制台的词汇已是 `retry` 与 `claim TTL`，控制台没有未捕获的错误。未能覆盖的：所属服务拒绝账本命令（模拟器无此路由）、目录的 Wow 表与「降低保护的审批」、目录的 Rows 与看板（需要 ClickHouse）、真实的 CoSec 网关与读屏软件；第二组（eShop Web 与各服务的 Swagger UI）待派。

| 编号 | 级别 | 位置 | 现象 | 去向 | 状态 |
|---|---|---|---|---|---|
| UI-1 | P0 | ops 服务（`cosec` 配置）的全部读取 | 没有 ops 角色（`roles: []` 或无关角色）的令牌读出全部停放条目：计数 50、列表 50、状态读 200；配置注释、文档、README 与控制台都说没有 `ops.viewer`/`ops.operator` 什么都读不到，没有测试覆盖。目录服务在同一替身后拒绝这样的读者。疑因（未证实）：无角色的调用者是所有者级，而条目没有所有者 | 先查明是 ops 服务的策略配置还是框架的读取策略（所有权、ABAC）；是框架语义则先报协调者再改；补「无角色读者什么都读不到」的回归测试（含目录） | 待派（P0） |
| UI-2 | P1 | ops，单条目的确认对话框（重试、应用重试设定） | 典型的长 ops id 不换行，内容宽于对话框（`scrollWidth` 556 对 384），「取消」「重试」在可见区域之外；375 宽度下更糟 | 长 id 在对话框内可换行或截断加完整提示 | 待派 |
| UI-3 | P1 | ops，保存视图 | 提供「所有人」并标为「共享」，但只存在本浏览器的 localStorage，「复制链接」给出别的浏览器打不开的视图 id | 没有视图存储时不提供共享范围与复制链接，标明「保存在本浏览器」 | 待派 |
| UI-4 | P1 | ops，分析「按上下文统计」与总览的「按上下文」 | 只计重试耗尽与不可恢复（29），不是全部停放（42），与标题、README 及其他分析不一致 | 计全部停放（未解决）的条目 | 待派 |
| UI-5 | P1 | 两个控制台，375 宽度的页头 | 页头不换行也不能横向滚动：ops 的调用者链接与语言切换、目录的 Rows、Boards、Draft、调用者与语言都在屏外 | 窄屏下页头换行或收进菜单 | 待派 |
| UI-6 | P1 | 文档 `operate/ops-console`（中英文） | 仍写 resume／恢复；CoSec 动作写成 `ops:Resume`，现为 `ops:Retry`，照文档写的策略永不匹配 | 立即改正动作名与词汇（不等最终重写） | 待派 |
| UI-7 | P1 | 目录，需要审批的发布 | 发布报「已完成」，状态仍是 v1；没有列出待审批发布的视图，审批人只能逐个打开 | 结果说「等待审批」；增加「待审批」系统视图 | 待派 |
| UI-8 | P1 | 目录，数据集列表「修订期间仍在服务」 | 正在服务 v2 的已发布数据集显示「否」；字段本义是「需要修订时仍在服务」 | 列名与取值按本义，或只在需要修订时显示 | 待派 |
| UI-9～UI-34、UI-36～UI-44 | P2 | ops 控制台、目录控制台、视图引擎、文档 | ops：确认后对话框偶尔不关（UI-9，未确证）、打开详情后焦点不进详情（UI-11）、结果说「已完成」而所属服务尚未执行（UI-12）、重试设定无前端校验且 422 丢掉输入（UI-13）、原因框回车无效（UI-14）、批量操作的数目不一致（UI-15）、时长显示为 ISO 原文（UI-16）、两个无标签徽标（UI-17）、后退不关详情（UI-18）、筛选与排序不进 URL（UI-19）、「放进仪表盘」是死路（UI-20）、总览数字卡不可点（UI-21）、总览截断与配色不一（UI-22）、窗口改宽后栅格不重排（UI-23）、宕机时十个相同错误框与挂起无超时（UI-24）、中文「重试」与重新加载同词（UI-25）、切换系统主题后部分不跟随（UI-26）、语言按「含 zh」而非偏好次序选且 `lang` 不变（UI-27）、登出无原因与开发调用者对 `cosec` 服务（UI-28）、结果条被详情遮住（UI-29）、无障碍名称过长与「重试中」列的是「计划重试」（UI-30）、过期拒绝的说明含毫秒时刻（UI-31）、「过去 7 天」按日历日且「1 days」（UI-32）、中文术语与文档不一（UI-33）；目录：「待审批」实为「未审批」（UI-10）、按角色本不可用的操作也可点（UI-34）、内存后端下 Rows 显示 Spring 的原始 404（UI-36）、修订保存后仍显示旧问题（UI-37）、回滚是自由填写的版本号且列表顺序与 README 不符（UI-38）、起草页的连接预填与链接（UI-39）、起草页的无障碍名称（UI-40）、列表不轮询（UI-41）、看板未写进 README 且中文「看板」与「仪表盘」混用（UI-42）、README 的配置键名错（UI-43）、文档站没有目录控制台的页面（UI-44） | 按应用成批修：ops 控制台与视图引擎一批，目录控制台一批；UI-33、UI-44 进文档最终重写（中文术语统一为停放、需要处理、重试耗尽、重试） | 待派 |
| UI-35 | P2 | 目录服务（`cosec` 配置），无角色读取 | 答 400 `wow.query.malformed` 而不是 403 `wow.auth.forbidden` | 随 UI-1 一起修 | 待派 |

## 11. v9 真实项目的迁移审查：项目 A（2026-10-30，只读审查加临时副本试迁移）

用户 2026-10-30 交来真实的 9.x 业务服务，GA 前审查并试迁移（用户的仓库不改、副本不推送；项目的名称与内部细节不进本仓库，只登记 Wow 自身的缺陷与缺口，编号 PA-n）。项目 A：Wow 9.3.0，22 个聚合，MongoDB、Kafka，Redis 上的 CosId 与 CoCache，一个外部调度器；JDK 17、Spring Boot 3.5。试迁移以 main `9d7fff0c10` 发布到 mavenLocal 为目标，因额度上限停在半途：构建已换成 Gradle 9、JDK 21、三个 Wow 插件、Boot 4.1、CosId 5、CoCache 6.9、CoSec 8；api 模块编译通过；全部状态类改写；4 个枢纽聚合经 KSP 编译、其中 2 个的测试在测试 DSL 上通过；其余聚合、服务端、客户端与迁移映射未做。工作量：共用基础约 50 分钟之后，一个带决定期查询、Saga 与测试的聚合约 20 分钟。另有迁移技能与文档的 9 项、项目自身须改的 7 项（后者交项目方，不在此登记）。

| 编号 | 级别 | 位置 | 现象 | 去向 | 状态 |
|---|---|---|---|---|---|
| PA-1 | P0 | 内核的溯源函数（只收事件）、`@Upgrade`（只见载荷）、`DecideScope`（无时钟） | 9.x 的溯源可读事件的 `createTime` 与操作人（项目 A 有 15 个这样的函数）；`migrate-data` 把它们导入为提交时间与操作人，但 Wow 10 的溯源读不到、升级也注入不了，凡这类聚合重建后都丢这些字段，`compare` 必然有差异 | 编程模型的决定，待与用户讨论（推荐：溯源函数可选第二个参数取事件已记录的事实——提交时间、操作人、版本；`DecideScope.now` 由节点时钟给出、测试 DSL 可控） | 待决 |
| PA-2 | P1 | `manifest-tools` 的 `SchemaIntrospector`、查询准入 | 状态 schema 漏掉 Jackson 会写进快照的只读计算属性；查询它们被拒为未知字段，生成的 `<State>Properties` 与 `<State>View` 也缺它们；9.x 可按它们查询与排序 | schema 收录 Jackson 写出的只读属性（`readOnly`），可查询（协调者定） | 待派 |
| PA-3 | P1 | `StateViewSources.kt:154-158`、`SchemaIntrospector` | 多态的 api 类型（`@JsonTypeInfo`/`@JsonSubTypes`）写成没有基类 `x-wow-class` 的裸 `anyOf`，`generateWowContractSources` 失败并挡住全部测试；绕开只能关掉 `publishState`，对端与 CoCache 就没有视图 | 多态节点写出基类的 `x-wow-class`，类路径上有时引用它 | 待派 |
| PA-4 | P1 | `migrate-data` 的迁移映射 | 没有起草映射的工具：项目 A 要约 250 条事件与命令映射，几乎都是由 9.x 存储与 Manifest 可推出的 `snake_case → ClassName` | `migrate-data draft-map`：按名字起草，只留例外给人（协调者定） | 待派 |
| PA-5 | P1 | 命令转换（D182） | 9.x 可由命令体推出聚合 ID（项目 A 有 9 个聚合、几十种命令这样做）；Wow 10 没有 `@AggregateId`，每种命令要手写一个 `CommandTransformer`，Saga 也要自己给出 ID | 编程模型的决定，待与用户讨论（推荐：声明式地由命令体给出目标 ID） | 待决 |
| PA-6 | P1 | 决定函数的依赖、测试 DSL | 决定函数里查询很常见（项目 A 有 53 处）；`QueryApi` 在 `:api`，按命令的调用者 `of(caller)` 查询是正路，但没写进文档；测试 DSL 的 `memoryQueries()` 对模型带依赖的聚合不能按类型读 | 写进技能与指南；核实宿主可把 `QueryApi` 注入领域依赖；`memoryQueries` 能按类型读 Manifest 里的每个状态 | 待派 |
| PA-7～PA-11 | P2 | 编译器、KSP、决定内的查询错误、`GlobalIdGenerator` 的 KDoc、查询类型 | 返回非密封接口时被当作一个事件类（PA-7）；KSP2 不认源集的 `exclude`（PA-8，写进文档）；决定函数内的查询准入错误成了命令的拒绝 `wow.command.invalid`，客户端看到 400（PA-9：应为故障）；KDoc 仍说 wow-cosid 给全局 ID，api 里的静态 ID 无替代说明（PA-10）；`PagedList` 等不能子类化（PA-11，写进技能） | 编译器提示、决定内查询错误归为故障、KDoc 与技能 | 待派 |
| PA-12 | P1 | 9.x 与 Wow 10 的服务群 | 项目 A 与几个仍是 Wow 9 的服务互相消费事件与调用（api 模块之间还有环）；Wow 10 读不了 9.x 的主题，9.x 消费 Wow 10 的路径已放弃（G-4），api jar 不能混用：整个服务群必须一起切换 | 待与用户讨论（推荐：先写服务群级的切换顺序与环检查文档；9.x 主题桥只在真实服务群需要时再定） | 待决 |
| PA-13～PA-21 | P1～P2 | `skills/wow-migrate/references/v9-to-v10.md`、`migrate/*`、09 | 缺：外部调度到 `@OnSchedule` 的映射与不能映射的情形（逐个等待、固定速率）；持久化在事件与状态里的 9.x 类型（`Condition`、`Sort`）的升级；Kotlin 链式 `aggregateVerifier` 的映射；Gradle 9 与生成工厂的参数；`@MountedCommand` 须在领域模块；09 里 `wow-cocache` 仍写放弃（D364 已恢复）与按索引、联接的缓存；查询 schema 覆盖、读请求头的查询策略、直接聚合快照集合；带日期前缀的号段生成器与 cosid-migration；用 `DomainEventCompensator` 回填改为新订阅从头读 | 由做审查的子会话补技能与文档 | 待派 |
