# 订阅侧定形：D372 的实施设计

日期：2026-10-29。状态：已实施（D372，协调者按「默认按推荐」定；全部落地于 2026-10-30）。上位：[决策台账](decisions.md) D372、[订阅](04-subscriptions.md)、[编程模型](01-programming-model.md)。

来源：订阅侧公开 API 的只读审查 S1～S28（批 2，基线 main `2935ead694`，七个质量维度）。本文把 D372 的每一项写到可以直接实施、不会走样的程度：问题、决定、改动范围、验收、明确不做的，以及 PR 拆分与状态。证据里的路径以仓库为根，`…` 代表 `src/main/kotlin/me/ahoo/wow`，行号是审查时的位置，仅作起点。

## 0. 约束

- GA 前不为兼容让步（D306）：改名、格式升级都不留别名、不做回退；预发布的存储格式只升版本号、不迁移。
- 一个概念只有一个名字，线上、API、配置、存储、控制台、文档同时改到位，改完 `grep` 到零（设计文档中的历史记录除外）。
- 测试证明行为，不为覆盖率改代码；走不到的防御代码删掉，不留。
- 每个 PR 从 main 开，不叠；做到一半停下的，以 WIP 提交并在 PR 说明里写明状态。

## 1. PR 拆分与状态

| PR | 范围 | 状态 |
| --- | --- | --- |
| PR 1 台账与运维 | S1～S4 | 已合并 #5194 |
| 测试 DSL | S19～S24 | 已合并 #5195 |
| PR 3 身份与运维面 | S5～S9，以及 #5194 带来的两处技能说明 | 已合并 #5198（2026-10-30） |
| PR 4a Saga 处理器 | S10、S11、S12、S16 | 已合并 #5215 |
| PR 4b 失败 | S13、S14（已定：保留内联、写明规则）、S15，以及失败分类规则移到后端 | 已合并 #5218 |
| PR 4c 形状与编译期检查 | S17、S18、S25 的 `JOIN`、S27 | 已合并 #5227 |

顺序：PR 3 → CosId 5（#5165，在 CoSec 8 #5167 之后）→ PR 4a → 4b → 4c，同一会话依次做（批 2）。PR 4a 改动回执类型时，在同一个 PR 里同步测试 DSL。

## 2. 台账与运维（PR 1，已落地）

### S1 节点只读自己分区里到期的条目

- 问题：每个节点每 10 秒读每个订阅的全部停放条目，读完才按分区过滤（`core/runtime/…/subscription/ParkedRetries.kt:34,66-76`），SPI 只有 `list`。
- 落地：条目带必填的 `partition` 与 `nextAttemptAt`；`FailureLedger.due(subscription, partitions, now, after, limit)` 返回未排期（null）或已到期的条目，索引 (service, subscription, partition, next attempt)，`after` 让一次扫描翻页读完所有到期条目。只有扫描写 `nextAttemptAt`，经条件修改 `ParkedAmendment.Schedule(retries, from, at)`；不会自动重试的条目（次数用尽、不可恢复、关闭重试）记 `ParkedAggregate.NEVER`；认领、标记、应用重试设定与请求操作会清空它。配置里改了重试设定，从已排期的那次之后生效。分区启动时只读自己分区的条目。
- 存储：Mongo 与 Postgres 的账本为格式 2（字段与列、到期与租户索引），去掉 Mongo 旧重试状态的回退（D306）。

### S2 停放计数

- 落地：`FailureLedger.count(subscription, max)`（各后端为带上限的 COUNT），`ParkedOps.count` 与节点采样使用它。

### S3 过期操作的守卫

- 落地：节点的停放路由与 ops 上下文一个约定，判定在 `ParkedOps`（`Outcome.STALE`），`LedgerCommands` 共用。**跳过必须给出 `failedAt`**（节点路由缺它为 400，ops 的 `SkipParked.failedAt` 非空；ops 的 Saga 在操作员未指明时发出条目记录的那次停放），因为跳过会丢弃数据；**重试（原「继续」）的 `failedAt` 可选**，只是重新处理，作用于当前停放是安全的。所指停放已被解决并重新停放时为 409 `wow.ledger.stale`。

### S4 按租户列出

- 落地：`list(…, tenantId)` 在各后端按租户过滤并有索引；路由用 `TenantPolicy.confinedTo(caller)`（架构规则不许 wow-http 读 crossScope）；一页对每个订阅的账本只读一次。

## 3. 身份与运维面（PR 3，进行中 #5198）

### S5 一个订阅身份

- 问题：运维按裸名找订阅并按「覆盖」猜（`ParkedOps.kt:45,190-196`）；`Ledger.parkedId` 是 `service:subscription:ref`（`api/api/…/ledger/Ledger.kt:84-87`），而名字只在上下文内唯一（`ManifestMerger.kt:29`），同一服务两个上下文的同名处理器读同一对端聚合时冲突；按订阅的配置以名字为键（`SubscriptionOptions.kt:166`），`wow.subscriptions.[snapshot].start` 命中每个上下文的 `snapshot`。计量已是 `(wow.group, wow.subscription)`（D360 ①）。
- 决定：`SubscriptionId(context, name)` 贯穿 `ParkedOps`、路由 `/wow/ops/parked/{context}/{subscription}/…`、HTTP 视图（带上下文与组）、`parkedId`、计量与按订阅的配置键。配置键带上下文、写法唯一、无歧义（上下文名与处理器名都可能含点，键的确切形状在 PR 里给出，与 D361 的写法一致），写进配置参考。
- 验收：两个上下文同名处理器的停放条目、计量与配置互不干扰；旧写法的键在启动时报未知键（D361 的严格检查）。
- 实现（#5198）：`SubscriptionId` 的键为 `<context>.<name>`（上下文名不含点，按第一个点切分），配置键 `wow.subscriptions.[<context>.<name>]`；停放路由为 `/wow/ops/parked/{subscription}/{context}/{aggregate}/{tenant}/{id}`，`{subscription}` 取该键；`Ledger.parkedId` 为 `<service>:<context>:<subscription>:<aggregate>:<tenant>:<id>`；计量仍按（组，订阅），组比上下文更细（D360）。

### S6 改名留下的检查点

- 问题：订阅名是 `processor.function`，改类名、函数名或 `spring.application.name` 即成新订阅，Saga 与 Reactor 从 END 开始、静默跳过中间（`operate/upgrades.md:43`）；`SubscriptionRuntime.kt:359` 启动时已列出组的检查点，但不报告无主的名字。
- 决定：启动时对没有订阅认领的检查点与停放条目打 WARN，列出名字、位置与停放数；保留检查点的办法是把 `@Name` 钉为旧名，写进文档。
- 不做：`@Name` 的别名（`aliases`）。

### S7 运维面

- 决定：只读的 `GET /wow/ops/subscriptions`：每个订阅的身份、各分区位置与滞后、自动暂停的分区、停放数、S6 的无主名字；重试设定的请求体只有一种形状（时长按 D361 的写法），节点与 ops 一致。
- 不做：批量动作（控制台对选中项逐个操作）、节点停放路由的 TS 客户端、回卷（`CheckpointStore.kt:26` 的设计如此；文档写明「换一个名字是唯一的回卷」，见 DG-23）。
- 实现（#5198，内容与授权由协调者定）：与停放路由同开关（`wow.http.ops.enabled`）。应答为 `node`、`subscriptions`（`subscription` 键、`group`、`source` 为 `log` 或 `topic`、`parked` 取节点采样的计数、`partitions`）与 `orphans`。日志组的订阅列出该组已存检查点的每个分区；本节点运行的分区另带 `runs`、实时水位、`head`、`lag`、`paused`、`throttled`；主题订阅只列本节点运行且暂停或限流的分区。无主检查点与 S6 的启动告警共用 `SubscriptionRuntime.orphans`。授权：须认证；限于一个租户的调用者 403（这是全服务的视图，不属于任何聚合与租户）；再由节点的 `ServiceRouteAuthorizer` 以保留上下文 `wow` 的路由 `ops.subscriptions` 判定（CoSec 为 `wow:ops.subscriptions`），没有授权器时与服务路由一样放行。开发用的调用者解析器不产生跨租户调用者，开发环境下该路由为 403，除非应用自带 `CallerResolver`。

### S8 词语（范围已定：整套改名）

- 停放条目的「继续」改为 **`retry`**，整套词汇一起改：线上与 API（`wow.ledger.Retry`、`RetryParked`、`LedgerRequest.RETRY`、`LedgerResolution.RETRIED`）、ops-api（`Retry`、`RetryRequested`）、`ParkedOps` 与路由 `…/retry`、控制台的动作与多语言文案、ops 基线与重新生成的客户端。
- 节点离开待命为 **`activate`**（`POST /wow/node/activate`）。改完后「resume」不再表示任何动作；「暂停」只指自动的系统性暂停（`SubscriptionPauses.kt:19`）。
- `wow.subscription.parked.timeout`（认领租期，并不取消什么，`WowProperties.kt:813-814`）改为 **`parked.claim-ttl`**，整个字段一起改：`ParkedRetrySpec.claimTtl`、节点与 ops 的重试设定请求体、控制台表单、存储名（Mongo `retry_spec.timeout_millis`、Postgres `retry_timeout_millis`）；账本升为**格式 3**，不回退（格式 2 已在 main，CI 与开发库可能已有）。
- 改名单独成一个提交，便于与 S5～S7、S9 分开审查。
- 实现（#5198）：存储名为 Mongo `retry_spec.claim_ttl_millis`、Postgres `retry_claim_ttl_millis`，失败台账格式 3；节点待命的状态写 `activatedAt`、`activatedBy`。「resume」不再指任何运维动作；以下不是运维动作，有意保留（协调者 2026-10-30）：可续读的查询流（`resumable`、`StreamResumeOptions`、`STREAM_NOT_RESUMABLE`）、系统性暂停的自动结束（`ObservedEvent.PartitionResumed`、计量的 `resumed`，D360）、`Observer.resuming(trace)`、`migrate-data` 导入续跑的 `resumeAt`，以及第三方 API（Reactor 的 `onErrorResume` 等）。

### S9 内置订阅名（已做）

- 内置订阅都在 `wow.` 下：`wow.snapshot`（及 `wow.snapshot:<store>`）、`wow.reservation`、`wow.timers`、`wow.transport-sink.<aggregate>`、`wow.elasticsearch-sink.<aggregate>`、`wow.clickhouse-sink`、`wow.cocache-sink.<aggregate>`；已在前缀下的不变（`wow.commands`、`wow.ledger.commands`、`wow.snapshot.rebuild`）。停放的定时发送改记在 `wow.timers.sends`，不再与定时器订阅同名。编译器拒绝名为 `wow` 或 `wow.…` 的处理器。等待信号里快照订阅名为 `wow.snapshot`，`Wow-Wait` 的关键字 `snapshot` 不变。

### 随 PR 3：技能说明

- `skills/wow-develop/references/processes.md:152`、`skills/wow-migrate/references/v9-to-v10.md:793`：跳过必须给出 `{"failedAt"}`，重试可选（S3）；按 S8 改用 `retry` 一词。

## 4. Saga 处理器（PR 4a）

### S10 调用被拒绝走同一条路

- 问题：返回的命令被拒绝交给 `@OnRejected`；`SagaContext.call` 的拒绝若未被捕获，变成 `CallRejectedHandlingException` 立即停放（`core/runtime/…/binding/SubscriptionBindings.kt:322,339`）。
- 决定：未捕获的 `call` 拒绝交给按命令类型匹配的 `@OnRejected`；没有则照旧停放。被中止的那次处理不发出任何派生命令。
- 验收：同一命令类型，返回与 `call` 两种发法被拒绝时进同一个 `@OnRejected`；没有处理器时停放；中止的处理没有派生命令。

### S11 一个 SagaContext

- 问题：`schedule`、`cancel`、`call` 只在 `SagaContext` 上（`api/api/…/subscribe/SubscriptionContext.kt:114,123,154`），`ReceiptContext`（`:161`）与 `JobContext`（`:175`）没有；补偿取消不了兄弟处理器设的截止时间，任务不能设定时。
- 决定：事件、拒绝回执、提交回执、定时四种 Saga 处理器共用一个 `SagaContext`；`ReceiptContext`、`JobContext` 删除，不留别名（D306）。测试 DSL 的 `ScheduleSpec.answerCalls`（#5195 已接好）随之生效。

### S12 `@Sends`

- 问题：`@OnEvent` 的五个属性里 `derives`、`schedules`、`calls` 只对 Saga 有意义，在投影与 Reactor 上是编译错误（`SubscriptionResolver.kt:465,481,508`）；运行时只校验 `calls`（`ProcessorBinding` `:90`），`schedules` 与 `derives` 不校验（`ProcessorContexts.kt:145-165`），未声明的定时类让 Manifest 缺项而不报错。
- 决定：三者从 `@OnEvent` 移到 `@Sends(derives = […], schedules = […], calls = […])`，四种 Saga 处理器都可标注；Saga 之外编译期拒绝；三者都在运行时强制（发出未声明的命令即失败，分类为不可恢复）。`derives` 仍可由返回类型推导，`@Sends(derives)` 用于返回 `CommandRequest<*>` 等擦除类型的情形。
- 细化（协调者 2026-10-30，PR 4a 实施中提出）：返回的命令类型无法确定（`CommandRequest<*>`、`Any`、擦除的发布者）又没有 `@Sends(derives)` 的 Saga 处理器是编译错误（取代今天的警告），错误信息给出 `@Sends(derives = […])`；于是返回命令的处理器都按类型或 `@Sends` 声明，运行时检查不会让结构正确的 Saga 在第一次发送时失败。`schedule` 与 `call` 编译期看不到，仍由运行时检查，未声明即不可恢复。

### S16 回执是密封类型

- 问题：`CommandRejected` 与 `CommandCommitted` 重复八个字段，没有共同类型，`version` 一个是 `Int?` 一个是 `Int`（运行时填 `result.version ?: 0`）。
- 决定：密封的 `CommandReceipt<C>`，子类型 `Committed`、`Rejected`；一个处理器可以接收 `CommandReceipt<C>` 同时处理两者。`version` 的可空性按语义定（被拒绝的命令可能没有版本）。
- 随之：测试 DSL 的 `whenRejected`、`whenCommitted` 与 eShop 的回执处理器同步修改。
- 细化（协调者 2026-10-30，PR 4a 实施中提出）：同时处理两种回执的处理器同时标 `@OnRejected` 与 `@OnCommitted` 并接收 `CommandReceipt<C>`：每个注解声明一条路径及其规则，参数类型本身不选路径；编译器绑定两次（拒绝与提交各一），Manifest 只列一次并同时写 `rejected` 与 `committed`。只标一个注解却接收 `CommandReceipt<C>`，或注解与参数的种类不符（`@OnRejected` 接收 `Committed<C>`，反之亦然），都是编译错误，错误信息指出应补的注解或应接收的类型。在 PR 4b（S14）之前两条路径各用各的重试规则，`CommandReceipt` 的 KDoc 写明。

## 5. 失败（PR 4b）

### S13 发布者返回值的检查

- 问题：处理器返回 Reactor 发布者时跳过返回类型检查（`SubscriptionResolver.kt:386-388`、`:577`、`ScheduleJobResolver`）；运行时 `DerivedRequests.kt:102` 抛出的 `IllegalStateException` 被归为可恢复，重试后才停放；带 `wait`/`timeout` 的派生请求（`SubscriptionContext.kt:93-94`）也只在运行时拒绝。
- 决定：检查类型参数：投影与 Reactor 为 `Mono<Void>`/`Mono<Unit>`，Saga 为 `CommandRequest` 的 `Mono`/`Flux`；这类编程错误与带 `wait`/`timeout` 的派生请求归为不可恢复，立即停放。

### S14 回执的重试范围（已定：保留内联）

- 问题：回执处理器上的 `@Retry` 是编译错误（`SubscriptionResolver.kt:277-281`）；同上下文的回执在派生它的 `@OnEvent` 处理里内联执行（`SubscriptionBindings.kt:366,373`），受派生处理器的预算约束，而 `OnRejected.kt:23-24`、`OnCommitted.kt:28` 说「每个处理器是自己的订阅」；类上的 `@Retry` 也作用于 `@OnSchedule`（`SubscriptionResolver.kt:174`），`Retry.kt:21-22` 只说事件处理器。
- 决定：每个回执经自己的订阅、用自己的 `@Retry`，同上下文也一样，`@Retry` 可标在回执处理器上。**条件**：实施前确认同上下文内联是否是已定的等待链性质（`SagaReceipts.kt:41-42`）；若是，先报协调者，再在「保留内联、文档写明回执继承派生处理器的预算、修正 KDoc」与本决定之间选择。KDoc 一并改正。
- 核查与裁定（协调者 2026-10-30，PR 4b 实施前的只读核查）：同上下文内联**不是**等待链依赖的性质——`handled(Saga.onX)` 由订阅 worker 按 `HandledEntry.derived` 发信号，派生命令的 `committed` 由其自身提交发出，跨上下文的回执本已经独立订阅 `wow.receipts.<ctx>` 到达（eShop 结账链即依赖它），`WaitState` 缓存早到的结果，顺序无关；没有决定把内联定为等待性质（D192：回执与等待链无关）。但改为每个回执单独订阅的代价实在：同上下文的拒绝不进日志（D47），须先持久写入回执主题才能推进 Saga 的检查点，每条这样的命令多一次传输写入，连只在本上下文内的 Saga（含内存与单节点）也要有回执主题；同一聚合内的顺序变弱（v1 的补偿可能在 Saga 处理 v2 之后才执行）；失败的回执处理器会单独重试与停放，而聚合后续事件照常流向 Saga；`REJECTED` 的 Saga 跳不再在 `derived` 中列出补偿。换来的只是每个处理器自己的重试与停放身份，对等待链无益。**裁定：保留内联，写明规则，不做本节原决定。**规则：同上下文的回执在派生它的 `@OnEvent` 处理之内执行，受该处理器（或处理器类）的 `@Retry` 预算约束，停放记在该订阅名下，重试即重跑该处理，由确定性 ID 保证无害；跨上下文的回执在 `wow.receipts.<ctx>` 中执行，受宿主对该订阅名的重试设置约束；回执处理器上的 `@Retry` 仍为编译错误，于是一个处理器的预算不取决于它的命令发往何处。PR 4b 随之：改正 `OnRejected`、`OnCommitted`、`CommandReceipt` 的 KDoc（去掉「每个处理器是自己的订阅」与 4a 中「4b 之前」的说法），编译器对回执处理器上 `@Retry` 的提示写明两种情形，`Retry.kt` 写明类上的 `@Retry` 也作用于 `@OnSchedule`，并以测试钉住同上下文拒绝处理器抛错时按派生处理器的预算重跑、停放在 `@OnEvent` 订阅名下。

### S15 失败分类的手段

- 决定：API 提供一个处理器抛出即立即停放的异常（如 `UnrecoverableHandlingException`，名字在 PR 里定）；`@Retry` 另设 `systemic` 列表，`recoverable` 不再兼指「非系统性」（`Retry.kt:44-47`）。
- 不做：在配置里由运维重新分类（`recoverable`/`unrecoverable` 不进 `wow.subscriptions.[…].retry`）。

### 失败分类规则归各后端（#5195 之后）

- 问题：#5195 把 `FailureClassification` 与 `BuiltInFailures` 移进 `:kernel`（测试 DSL 与运行时共用，正确），同时把既有的技术知识带进了内核：`java.sql` 的 `SQLTransientConnectionException`、`SQLNonTransientConnectionException` 与 `java.net`、`java.net.http` 的异常。
- 决定：每种技术的规则移到拥有该技术的模块，作为 `FailureClassifier` 经现有 SPI 注册：JDBC/PostgreSQL 后端一个，HTTP 客户端模块一个；内核的内置规则只保留 Wow 自己的 SPI 异常（`Storage*`、`Transport*`、`CommandRejected`、`WowProblem`）。测试证明各后端的分类器被找到并生效。

## 6. 形状与编译期检查（PR 4c）

### S17 `@OnSchedule`

- 决定：`JobScope.SHARDED` 取代 `sharded: Boolean`（`OnSchedule.kt:41-42`，`sharded` 只在 SYSTEM 下有效）；配置键叫 `cron`（今为 `wow.schedules.<name>.expression`，`WowProperties.kt:1026`）；一个任务的设置在一个命名空间下（今重试按订阅名为键）；节拍在订阅过滤里按聚合 ID（任务名）过滤，不再每个任务读全部节拍再丢弃（`ProcessorBinding.kt:327`）。

### S18 注解与编译期检查

- 决定：去掉 `OnEvent`、`OnRejected`、`OnCommitted`、`OnSchedule` 的 `ANNOTATION_CLASS` 目标与 `@Inherited`（编译器按精确注解类匹配，`compiler/…/symbol/Overrides.kt:47-48`，元注解的处理器静默不生效；函数上 `@Inherited` 无效）；发布者返回值上的 `@Blocking` 给出警告（`Blocking.kt:22-25` 的承诺，`SubscriptionResolver.kt:411`）；回执类型不在 Saga 派生 ∪ 声明的命令里时警告（如 `CommandRejected<ReserveStok>` 永不运行）；上下文参数接受其超类型（今须精确，`SubscriptionResolver.kt:628`）。基类上的 `@Retry` 是否继承在 PR 里明确并写进 KDoc。
- 不做：元注解。

### S25 Manifest 的起点

- 问题：订阅对端流时代码按 JOIN（`SubscriptionStart.kt:105`：已在读则 END，否则 BEGINNING），而 Manifest 的 `SubscriptionStart` 只有 `BEGINNING`、`END`（`api/manifest/…/Manifest.kt:691-697`），JOIN 写成 null。
- 决定：Manifest 增加 `JOIN`；五处页面的错误说法进文档重写（DG-22）。

### S27 eShop

- 决定：`PaymentGatewaySaga` 的「只告警一次」（`private val warned = AtomicBoolean()`，`:49`）移入缓存适配器，处理器不留状态；以 `@Sends(derives)` 取代为返回 `CommandRequest<*>` 而写的变通；保留显式的空 `@OnRejected`（表达「接受这个拒绝」的意图）。失败路径的测试随测试 DSL（已合并）补上：网关不可用而停放、`SupplierDeliverySaga` 的来源失败、`OrderPaymentSaga` 停放的 `CancelOrder`（#5195 已加）、`ShipmentNotifier` 的 `@Retry(timeout)`；`SupplierDeliverySagaTest.kt:51` 改用 `expectCommands`。
- 不做：`@OnRejected(accept = […])` 之类声明式接受。

### 4b、4c 实施中定下的细则（协调者 2026-10-30）

- 失败分类器的注册（4b）：各技术的 `FailureClassifier` 由所属模块（`:postgres`、`:client`）在 `META-INF/services` 中声明、经 `ServiceLoader` 发现，与 `StoreProvider` 等端口同法；内核的 `FailureClassification` 加载它们，测试 DSL 与运行时分类一致。顺序：处理器的 `@Retry`、应用的分类器、各模块的分类器、运行时自己的、内核内置的 Wow SPI 规则。立即停放的异常为 `UnrecoverableHandlingException`，`@Retry(systemic)` 另列系统性故障。
- 定时任务（4c，S17）：一个任务的全部设置（规则 `cron`、`zone`、`misfire`、`enabled`，以及运行它的订阅的 `retry.*`、`parked.*`）都在 `wow.schedules.<任务>` 下；在 `wow.subscriptions.[…]` 里写任务的订阅即启动失败并指向前者；`expression` 改名 `cron`（`JobRule` 同名）；分片任务的订阅按其各分区实例的 ID 过滤节拍。
- `@Retry` 的继承（4c，S18）：处理器类上的 `@Retry` 取自身的，否则取最近的父类的，整体继承、不逐项合并；实现带 `@Retry` 的接口是编译错误（接口没有唯一的最近者）。
- 测试 DSL 执行处理器的 `@Retry(timeout)`（4c，S27 的方案 B）：与 `SubscriptionWorker` 一样按真实时间以 `withTimeoutOrNull` 取消，`@Blocking` 处理器在虚拟线程上；`HandlerTimeoutException` 移入 `:kernel`；示例只核对处理器的预算，超时路径由 DSL 自己的测试以短超时证明（不用虚拟时间：`@Blocking` 处理器在真实线程上等待）。
- 「只告警一次」移入 CoCache 的 `StateViewCaches` 适配器（4c，S27）：每次中断告警一次，处理器不留状态。

## 7. 测试 DSL（已落地 #5195）

- S19：`reads()` 按运行时跳过不含其事件类型的流（`ProcessorBinding.kt:136`）；两个 eShop Saga 测试的错误期望已改。
- S20：失败分类只有一份，`FailureClassification`、`BuiltInFailures`、`HandlingRejectedException`、`CallRejectedHandlingException` 在 `:kernel`（`@InternalWowApi`），运行时在其上组合 `ExecutorFailures`；拒绝立即停放；没有 `@OnRejected` 的 `whenRejected` 可断言 `expectParked()`。
- S21：`ScheduleSpec` 带任务的 `@Retry`、`answerCalls`、`givenQueries`，有 `scheduleSpecTests`，上下文取自生成的绑定（`ProcessorBinding.context`）。
- S22：`@WowTestDsl`；`ProcessorExpectations` 上有显式的 `whenRejected`、`whenCommitted`。
- S23：`whenEvent` 推进给定的聚合，另有重投与乱序。
- S24：`expect` 默认为空；`expectDeterministic` 只给 Saga；`expectCommand` 与 `expectCommandEnvelope` 分开；调用者参数名与聚合 DSL 一致。

## 8. 文档

页面上的偏差在最终文档重写时处理，登记在[文档方案](docs-plan.md) §11（DG-21 起）；代码中的 KDoc 随各自的 PR 改正。
