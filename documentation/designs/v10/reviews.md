# V10 审查台账

日期：2026-10-19。状态：持续维护（主会话在派工前登记，修复合并后更新）。上位文档：[进度](progress.md)。

本文是审查发现的唯一记录：每项带编号、位置与现象，派工说明只引用编号。第一轮（A.1～A.5，75 项）的结论与遗留见 [进度](progress.md) 的「全面审查结论」；本文从第二轮 R2（D230）开始逐项登记。

## 1. 登记规则

- 发现先登记、后派工：子会话的说明引用本文编号，不在会话上下文里单独保存发现。
- 每项写明：编号、级别（P0/P1/P2）、位置（模块与文件）、现象与触发条件、去向（修复批次、PR 或决定）、状态。
- 状态：`待修`、`修复中`、`已修`（带 PR）、`转 10.x`、`放弃`、`待重审`（原文丢失，须重新审计找回）。

## 2. 第二轮全面审查 R2（D230）

基线 `origin/v10` `e043be491`，只读，34 项（P0 0、P1 7、P2 27）。下表在 2026-10-19 补登：R2 的发现原文只留在当时协调会话的上下文里，现由进度文档、各批 PR 的描述与提交还原；还原不出现象的标为 `待重审`。

| 编号 | 级别 | 位置 | 现象 | 去向 | 状态 |
|---|---|---|---|---|---|
| R2.1-1 | P1 | `:runtime` | 节点在途上限以 BUSY 拒绝内部来源（intake、Saga、定时器），被当作技术故障停放 | G2 | 已修 #4555 |
| R2.1-2 | P1 | `:runtime` | 跨上下文不带 ID 的创建共用 `(none)` 车道、分区与停放键 | G2 | 已修 #4555 |
| R2.1-3 | P2 | `:runtime` | 重复请求的回执没有带上已提交流的聚合 | G2 | 已修 #4555 |
| R2.1-4 | P2 | `:runtime` | 主题消息换了分区后，聚合的停放丢失 | G2 | 已修 #4555 |
| R2.1-5 | P2 | `:runtime` | 续约间隔不小于租约 TTL 的一半时不报错 | G2 | 已修 #4555 |
| R2.1-6 | P2 | `:runtime` | 信号在本地投递时不检查是否属于本服务的节点 | G2 | 已修 #4555 |
| R2.1-7 | P2 | `:runtime` | 同一命令的提交回执与拒绝分不开 | G2 | 已修 #4555 |
| R2.1-8 | P2（单节点部署为 P1） | `:runtime` 主题订阅 | **重审还原（2026-10-19）**：`TopicSubscriptions.start`、`refresh` 里的 `joinOver()` 不在 `retryForever` 之内；`PartitionOwnership.start()` 遇到租约存储或心跳的暂时错误时，后台加入协程结束，节点已报 RUNNING 却永不读该主题；`running` 停在半启动或已停止的属主上，重试撞 `check(running == null)`（`TopicSubscriptions.kt:175-200`、`:227-243`，`PartitionOwnership.kt:235-263`） | 运行时批 | 待修 |
| R2.1-9 | P2 | `:runtime` | 等待在发出前已超时的转发没有撤下 | G2 | 已修 #4555 |
| R2.2-1 | P1 | `:postgres` | 依赖连接池默认 autocommit，关闭时写入被静默回滚 | G3 | 已修 #4554 |
| R2.2-2 | P2 | `:mongo` | 超过文档大小上限的事件流在分组提交里拖累同组 | G4 | 修复中 #4556 |
| R2.2-3 | P2-低 | `:kafka` | **重审还原**：`SharedTopicConsumer` 在 `OffsetOutOfRangeException` 处理里调用 `place()`，不在 `WakeupException` 的捕获内；期间的加入或离开唤醒会结束整个会话，所有读者失败重读（`SharedTopicConsumer.kt:161-163`）；能自愈，但噪声大 | G4 收尾 | 待修 |
| R2.2-4 | P2 | `:mongo` 查询 | `ENDS_WITH` 没有锚定到字符串末尾 | G4 | 修复中 #4556 |
| R2.2-5 | P2 | `:postgres` | `PostgresFailureLedger` 的 KDoc 过时 | G3 | 已修 #4554 |
| R2.3-1 | P1 | `:http`、`:spring-boot` | `Wow-Call` 的 `wait.origin` 不校验服务，被信任的调用方可套走目标服务的集群令牌（绕过 D226） | G1 | 已修 #4553 |
| R2.3-2 | P2 | view-store | 授权器放行不带属主的写入 | G1 | 已修 #4553 |
| R2.3-3 | P2 | view-store | 偏好写入可以作为别人的属主 | G1 | 已修 #4553 |
| R2.3-4 | P2 | `:kafka` | `wow.kafka.offset.gaps` 指标的来源被弱引用回收 | G4 | 修复中 #4556 |
| R2.3-5 | P2 | `:http` | 重建与作业的系统路由不在内部端口的调用路由上 | G1 | 已修 #4553 |
| R2.3-6 | P2 | `:http` | 快照写出前 `events` 模型的可见性 | D231 的 A.M | 已修 #4566 |
| R2.3-7 | P2 | view-store | 系统视图 ID 前缀可被占用；一个坏的存储视图让所有租户的列表失败 | G1 | 已修 #4553 |
| R2.3-8 | P2 | `:http` | 停放与系统视图路由的存储故障没有按 problem 回答 | G1 | 已修 #4553 |
| R2.3-9 | P2 | `:http` | `/wow/ops/parked` 的读取没有授权 | G1 | 已修 #4553 |
| R2.4-1 | P1 | `:migrate-data` | 补偿导入只增不减（9.x 之后解决的执行仍停放） | G3 | 已修 #4554 |
| R2.4-2 | P1 | `:migrate-data` | 预留导入不跟随 `rollback` 与 `reprepare` | G3 | 已修 #4554 |
| R2.4-3 | P2 | Manifest 兼容门、Gradle 插件 | **重审还原**：缺少基线时 `checkManifestCompatibility` 通过，只在报告里写一行，`check` 为绿却没有门（`ManifestTools.kt:64-67`、`WowPlugin.kt:173`）；当前主版本高于基线后所有破坏性变更都放行且不告警，直到有人更新基线（`ManifestCompatibility.kt:62-70`） | P.20 之后 | 待修 |
| R2.4-4 | P2-低 | `:migrate-data` | **重审还原**：检查点 `save(load().with(..))` 是读改写，固定的 `<file>.tmp`、无锁、原子移动前不 fsync；切换规程里增量追尾与 compare 或快照生成同时跑时互相覆盖（`ImportCheckpoints.kt:157-172`、`EventStreamImporter.kt:305`） | 迁移批 | 待修 |
| R2.5-1 | P2 | `:runtime` | 定时命令在摄入处不等到期时间 | G2 | 已修 #4555 |
| R2.5-2 | P2 | 横切 | 同步调用的执行上下文与超时 | G5 | 待修 |
| R2.5-3 | P1 | `:spring-boot` | `wow.http.services.*` 拼错键名不报错，`commands` 为空时放开全部领域命令（由 P2 升 P1） | G1 | 已修 #4553 |
| R2.5-4 | P2 | 横切 | 错误码表 | G5 | 待修 |
| R2.5-5 | P2 | CI | v10 CI 范围（改 CI 须用户批准） | G5 | 待修 |
| R2.5-6 | P2 | 设计文档 | 文档漂移 | — | 已修 #4552 |
| R2.5-7 | P2 | `:architecture` | 架构规则 | G5 | 待修 |

## 3. 其他来源的发现

| 编号 | 级别 | 位置 | 现象 | 去向 | 状态 |
|---|---|---|---|---|---|
| X-1 | P2 | `:http` | SSE 响应体在被订阅前请求就取消时，结果 channel 永不取消，生产者挂在 `send` 上泄漏（`CommandHandler`、`QueryHandler`） | 单独修复 | 已修 #4559 |
| X-2 | P2 | `:http` 模块边界 | 读路径的语义散在 `:http` 与宿主里 | D231 的 A.M | 已修 #4566 |
| X-3 | P1 | `:spring-boot`、9.x 能力复核 | 没有 springdoc 集成（Swagger UI、与应用接口合并、securitySchemes）；09 §9 误判为 A | D233，P.19 | 已修 #4574 |
| X-4 | P2 | `:eshop-payment-server` 测试 | `PaymentServerTest.close` 卡在 Spring 的 `GracefulShutdown.abort` 约 40 分钟，栈上无 Wow 帧；重跑通过（P.14 验证时见到） | 待查 | 待修 |
| X-5 | P1 | `:runtime` 命令摄入 | 未知的聚合或命令类型被当作技术故障：重试后停放目标聚合，其后的命令等运维（`CommandIntake.kt:71-74`、`:120-125`）；版本错位或发错命令即可触发，发送方的缺陷越过服务边界。应作为拒绝回答（如 `wow.command.unhandled`，带回执） | 独立修复（D234 对抗性验证 K1） | 已修 #4573 |
| X-6 | P2 | `:kernel`、`:compiler` | 命令与事件的命名有三套：编译器读 `@Name`，内核 DSL 默认名（`AggregateModelDsl.kt:246`）与 `CommandTypes.SIMPLE_NAMES`（`Kernel.kt:1112`）不读；手写或 Java 模型与 Manifest 的名字可能不一致。统一为一个命名函数，DSL 的 `name=` 与 `@Name` 不一致即失败 | 独立修复（K2） | 待修 |
| X-7 | P2 | `:kernel` 定时 | `wow.schedule.Set` 存下 `commandType` 而不校验（`KernelLifecycle.kt:98`），名字写错要到触发时才失败（cron 可能数月之后）。目标在决定 `SetSchedule` 时按其模型校验，错误即拒绝 | 独立修复（C6） | 已修 #4573 |
| X-8 | P2 | `:query` 内存后端 | 对多值数组路径的根级指标只取单元素数组的值（`InMemoryValues.kt:135`），一次提交里两个 `ItemAdded` 的 `sum(events.body.qty)` 悄悄少算 | D235 ③（准入拒绝） | 已修 #4570 |
| X-9 | P2 | `:clickhouse` | 数据集的 `count()` 不加 `FINAL` 时，ReplacingMergeTree 在合并前会因重放多计（未核实） | A.M2 第二步核实 | 待查 |
| X-10 | P2 | `:runtime` 集群配置 | `ClusterOptions` 只校验了续约间隔（R2.1-5），心跳间隔只需小于成员 TTL，0.9 倍也能通过，一次慢心跳即让成员过期、分区抖动；`reconcileTimeout > drainTimeout` 也未校验（`ClusterOptions.kt:96`） | 运行时批 | 待修 |
| X-11 | P2-低 | `:kernel` 定时 | `ScheduleSlots` 的 cron 与时区缓存是静态 Map、永不淘汰，键来自用户给的字符串；能否由不可信输入触达未核实（`ScheduleSlots.kt:46-47`） | 运行时批 | 待查 |
| X-12 | P2 | `:runtime` 测试 | `WaitWithdrawalTest`「a command still queued in its lane when its wait times out…」在机器负载下偶发失败（10-19 两次，单跑通过），期望 `timed out at ACCEPTED` 得到 `COMMITTED` | 运行时批 | 待修 |
| X-13 | P2 | `:cosec`、`:spring-boot` | CoSec 启用后 `wowCoSecCallerResolver` 与 `wowCoSecInternalCallerResolver` 都是 `CallerResolver`，宿主的 `wowRouterFunction(callers: CallerResolver)` 启动失败；P.15 以平台侧 `PublicCallersFirst` 绕过 | R3-S | 待修 |
| X-14 | P1 安全 | `:cosec` | `CoSecInternalCallerResolver` 使用任意 `SecurityContextParser`，inject 模式下会接受伪造的服务主体；P.15 以平台侧的校验解析器绕过，框架未修 | R3-S | 待修 |

## 4. 第三轮架构审查 R3（2026-10-19，基线 `751c87569d`，只读）

角度：职责、清晰、内聚与耦合、性能、可用性、扩展、易用，并复核 9.x 能力对照。整体判断：内核、日志与订阅的正确性扎实，问题集中在边界——宿主里的语义、默认值、读路径对写路径的影响、社区后端的接入。

| 编号 | 级别 | 位置 | 现象 | 去向 | 状态 |
|---|---|---|---|---|---|
| R3.1-1 | P1 | `:spring-boot`、`:query`、`:runtime` | 读侧与存储布局的组装（约 2.3k 行）连同安全语义（`hides = abac \|\| policies`、`events` 兜底后端、作用域策略拼装）在 Spring 宿主里，A10 对读侧不成立 | R3-A（D237） | 待修 |
| R3.1-2 | P2 | TS `wow-client`、`wow-view-store` | 10.x 的 npm 包根入口仍是 9.x 客户端（V10 在 `src/v10` 子入口），GA 后用户导入的是 9.x 协议 | 待决（GA 前） | 待定 |
| R3.2-1 | P2 | `:postgres`、`:mongo`、`:kernel`、`:kafka` | `TailPacer`、`VirtualThreads` 等逐字复制于多个后端 | R3-A | 待修 |
| R3.2-2 | P2 | 横切 | 主代码注释引用审查编号 439 处（流程产物，原文可能丢失） | R3 文档与规则 | 待修 |
| R3.2-3 | P2 | `:query` | 公开 ABI 过大（`query.api` 2285 行，`schema` 包 122 个公开类），冻结面过宽 | R3-F | 待修 |
| R3.3-1 | P1 | `:spring-boot` | 社区后端接不进宿主：`BackendKind` 封闭枚举、`HostStore` 构造 internal，`BackendFactory` 全有或全无 | R3-A | 待修 |
| R3.3-2 | P2 | `:query`、`:tck` | `QueryBackendSpec` 在不发布的 testFixtures，`QueryBackend` 无 `@WowSpi`，没有 Sink 合同 | R3-A | 待修 |
| R3.3-3 | P1 安全 | `:query`、`:spring-boot` | 没有快照查询网关时（`snapshot=false`、内存快照未开查询、应用自带后端），`events` 对 ABAC 与应用策略默认放行 | R3-S | 待修 |
| R3.4-1 | P1 | `:spring-boot`、`:query` | 日志不在 PG/Mongo 时 `events` 退回内存全量扫描，每次查询读全部分区，无开关 | R3-S | 待修 |
| R3.4-2 | P1 | `:postgres`、`:mongo`、`:query` | 写库上的 `events` 查询与追加共用连接池、无并发上限、单条最长 30 s，可拖垮命令 | R3-B | 待修 |
| R3.4-3 | P2 | `:runtime` | 同时登记的等待没有上限（默认 `SNAPSHOT` 等待持有连接直到超时） | R3-B | 待修 |
| R3.5-1 | P1 数据丢失 | `:spring-boot` | `wow.backend=AUTO` 找不到持久后端时只告警并退回内存存储，重启丢数据（9.x 启动失败） | R3-D | 待修 |
| R3.5-2 | P1 | `:runtime` | 系统性故障按单个聚合停放（约 3 s 重试后停放，3 分钟起重试），下游抖动即停放所有有流量的聚合 | P.16 并入 | 待修 |
| R3.5-3 | P1 | `:spring-boot`、`:runtime`、`deploy/v10` | 没有健康模型：无 `HealthIndicator`，`isReady` 只看 RUNNING，K8s 用 TCP 探针 | R3-H | 待修 |
| R3.6-1 | P2 | `:postgres`、`:mongo`、`:kafka`、`:migrate-data` | 分区数 P 固定，没有重分区路径 | 待决（07 写明离线方案或 10.x 工具） | 待定 |
| R3.7-1 | P2 | `:compiler`、`:gradle-plugin` | 只能用 Gradle 构建（KSP、Manifest 补全在 Gradle Worker），9.x 的 Maven 用户无法迁移；09 漏列 | 待决（与 P.17 一起） | 待定 |
