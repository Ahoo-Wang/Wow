# V10 数据协议

日期：2026-10-12。基线：`v10` `4224` 之后。状态：已采纳（D55～D60 已定）。上位文档：[00 目标架构](00-architecture.md)（A1、A6、A11）。相关决定：D15、D17、D22、D25、D46、D48、D49、D50、D51、D132、D135、D136、D142、D163、D164。

本文定义 V10 中跨越进程、存储或版本边界的数据结构：命令、事件流、`StateEventRecord`、快照、命令结果、等待信号、Kafka 信封。这些结构一旦落盘或上线，就只能按 §9 的规则演进，所以在 M4（运行时）与 M6（Kafka）之前定下。

## 1. 原则

1. **事实、视图、传输三层分开。**
   - **事实**：事件流，只写一次，永不修改，进日志（A1）。
   - **视图**：状态与快照，由事实推导，可丢弃、可重建（D54）。
   - **传输元数据**：等待上下文、追踪、预留确认等，只为投递服务，不属于事实。领域代码与查询看不到，迁移与重放会丢弃（D25）。
2. **用名字，不用类名。** 命令、事件、错误码都用模型给的稳定名字（`上下文.聚合.名字`），类可以改名、换包，历史不受影响。
3. **线上一律 JSON。** 载荷是 JSON 文本，结构的 schema 在 Manifest 里（D17）；有基准证据再换二进制（同 D26）。
4. **每个结构带格式版本**（§9），加字段不升版本（R5-1），不兼容的变化只在主版本的迁移层处理（D22）。

## 2. 名字

| 名字 | 形式 | 来源 |
|---|---|---|
| 聚合类型 | `context.aggregate` | 模型 |
| 聚合引用 | `context.aggregate/tenant/id` | `AggregateRef.toString()`；预留与定时器的所有者是结构化的 `AggregateRef`（类型、租户、ID 分列），不用这个字符串（D225） |
| 命令类型 | `context.aggregate.CommandName` | 模型；默认取类的简单名，`@Name` 覆盖（**新增**，D55） |
| 事件类型 | `context.aggregate.EventName` | 模型（已实现），旧名作别名 |
| 错误码 | `wow.*`（框架）、领域自定 | `Rejection.code`，登记在 Manifest |
| 事件标识 | `context.aggregate/tenant/id@version#index` | 由（引用，版本，事件序号）推导，不另存（D57） |

## 3. 命令

```kotlin
class Command<C : Any>(   // 普通类，构造函数 @InternalWowApi，经公开工厂创建（D216 ⑤）
    val id: String,
    val requestId: String,            // 幂等键，在租户加聚合类型内唯一（D46、D162）
    val ref: AggregateRef,
    val body: C,
    val expectedVersion: Int? = null,
    val scope: Scope = Scope.NONE,    // 准入得到的调用方作用域（D49、D56）
    val headers: Map<String, String> = emptyMap(),
    val causation: Causation? = null, // 新增：因果链（§4）
    val operator: Operator? = null,   // 操作人（D163）：准入由 Caller 转成；派生命令继承根命令的操作人
    val wait: WaitPlan = WaitPlan.SNAPSHOT, // 新增：等待计划（D61～D64、D69），默认等快照；只留在发起节点
)
```

- **线上形式**（Kafka 命令主题、集群转发）：`{ type, id, requestId, ref, body, expectedVersion, scope, headers, causation, operator, replyTo }`，`type` 是命令类型名，接收方按 Manifest 解码 `body`。`replyTo: {context, subscription, ref, version, committed}` 可选，只在跨上下文命令上出现，告诉目标拒绝回执发往哪个上下文、交给哪个 Saga 的哪次提交（D136，格式 1 内的加法）；`committed` 标记还要成功回执（D192）。等待计划不随命令传给目标上下文，只把 `WaitContext` 放进传输段（D25）。
- **请求 ID 复用的识别只比命令类型名**（D46，按性能取舍修正，D55）：事件流记下产生它的命令类型名（§4 的 `commandType`），同一请求 ID 再来时，类型名不同就拒绝（`wow.command.request-id-reused`），相同就当作重试。
  - 不再给命令体算哈希：命令体在写路径上本来不序列化，只为指纹序列化一次再哈希，是 H3 测出的主要成本之一；规范化 JSON 还有「跨部署加字段即误判」的问题。
  - 已知限制：同一类型、不同内容的命令复用请求 ID 时，后者被当作重试，得到前者的结果。请求 ID 由调用方按「一次业务请求一个」生成，这是调用方的契约（文档与 `wow-client` 默认每次生成新 ID）。
  - 类型名来自模型，不用 Java 类名：改类名不影响重试的识别。
- **`headers` 只放准入允许的请求事实**（例如调用方应用）。准入按白名单从请求里取，取到的都会写进事件流（§4），所以追踪 ID 这类只为投递服务的东西不放这里，放传输段。

## 4. 事件流（事实）

```kotlin
data class EventStreamRecord(
    val ref: AggregateRef,
    val version: Int,
    val commandId: String,
    val requestId: String,
    val commandType: String,                // 取代 fingerprint：产生它的命令类型名（D55）
    val commitTime: Long,
    val events: List<EventRecord>,          // name、revision、payload
    val scope: Scope,                       // 新增：提交后聚合的作用域（D56）
    val causation: Causation? = null,       // 新增
    val headers: Map<String, String> = emptyMap(), // 新增：来自命令
    val operator: Operator? = null,         // 操作人（D163），审计的主依据
)

data class Scope(val ownerId: String?, val spaceId: String?) // wow-api 的值类型：所有者与空间的组合
data class Causation(val correlationId: String, val causationId: String)
data class Operator(val id: String, val tenantId: String, val crossScope: Boolean = false) // wow-api（D163）
```

- **`Scope` 是一个值类型**（所有者与空间的组合），命令、事件流、快照、查询授权都用它，不再散放两个字段（D56）。位置决定语义：命令上的是准入得到的调用方作用域，事件流与快照上的是聚合的归属。
- **事件流的 `scope` 是这次提交之后聚合的作用域**，不是发命令的人：
  - 创建时取自命令的 `scope`。
  - 之后只由「转移所有者、转移空间」这两个生命周期命令改变（[01](01-programming-model.md) §6）。
  - 内核把它和状态一起维护：`CachedState` 增加 `scope`，快照加载时一并恢复。
  - 这样每条事实都自带作用域：投影、查询授权（ABAC）、快照订阅直接读取，不必回放历史。9.x 的 `DomainEventStream.ownerId` 也是这个语义。
- **操作人**（D163）：`operator` 记这条事实在谁名下。`Caller` 是一次请求的调用方，只活在准入期间；`Operator` 是它写进事实的子集（`id = subject`、操作人自己的租户、是否代操作）。Saga 派生命令、补偿与台账操作继承根命令的操作人，「谁触发的」看 `causation`。角色、属性与可识别个人身份的信息不进事实。
  - 存储（#4381）：PostgreSQL 日志用三个平铺列，快照与定时器用 `jsonb`；MongoDB 用子文档；ClickHouse 的 `_state` 不存 `first_operator`（取 `version = 1` 的操作人）。ClickHouse 的事件列为 `Array(Tuple(name, revision, payload))`，数字另存 `state_numbers`（D164）。
  - 命令转换（D182）在事件流 headers 留下 `wow.transformed-from` 与 `wow.transformed-from-id`。
- **定时意图**（D166，D198 修订）：不再是事实上的字段，而是内置系统事件 `wow.ScheduleSet(key, rule, target, command)` 与 `wow.ScheduleCancelled(key)`，与领域事件同一次提交；定时器存储由内置订阅 `timers` 从它们派生，可重建；快照系统字段 `schedules` 记键与规则。
- **删除与标签**不放进每条事实。它们是生命周期事件本身，快照订阅按事件演化元数据（`deleted`、`tags`）。
- **因果链**：
  - `correlationId` 是根命令 ID。用户直接发的命令不带 `causation`，它自己就是根，相当于 `correlationId = commandId`。
  - `causationId` 是直接上游：Saga 派生的命令填它所处理的事件流的标识（`ref@version`），跨上下文命令同理。
  - 等待链按 `correlationId` 关联（[04](04-subscriptions.md) §6）。
- **传输段**不在事实里。`LogEntry` 增加 `transport: Transport?`，存在后端的独立列或字段里：

```kotlin
data class LogEntry(val position: LogPosition, val stream: EventStreamRecord, val transport: Transport? = null)
data class Transport(
    val wait: WaitContext? = null,                 // 只在等待超出 COMMITTED 时写（D25）
    val reservations: List<ReservationKey> = emptyList(), // 本次尝试做过的预留，由订阅确认（D50）
    val trace: Map<String, String> = emptyMap(),   // W3C traceparent 等
    val releases: List<ReservationKey> = emptyList(), // 提交后释放的键，由订阅兜底（D225）
)   // 记录类型将改为普通类（D228）
```

`EventLog.append` 改为接收 `(stream, transport)`。`findByRequestId`、`read` 只返回事实；`tail` 返回带传输段的 `LogEntry`，供订阅与信号使用。

## 5. StateEventRecord（订阅与发布的单位）

线上与存储形式叫 `StateEventRecord`，与 `EventStreamRecord`、`SnapshotRecord` 同一命名惯例；处理函数拿到的类型化形式是 `wow-api` 的 `StateEvent<S>`（D81）。`StateEventRecord` 与 `StateView` 定义在 `wow-spi`（D132），因为 Sink 所在的后端只能依赖 `wow-spi`。

```kotlin
data class StateEventRecord(
    val position: LogPosition,      // 发布到 Kafka 后，下游用偏移量；位置只在上下文内有意义
    val stream: EventStreamRecord,  // 事实
    val state: StateView,           // 提交后的状态视图
)
data class StateView(val version: Int, val schemaFingerprint: String, val state: JsonNode)
```

- **状态视图**：提交时把状态序列化一次（[01](01-programming-model.md) §2 的版本视图）。属主节点直接使用这个视图；重放和属主切换时，由快照加尾部演化得到。
- **进程内**：处理函数拿到 `StateEvent<S>`：事件已解码并升级到当前 revision，状态是由视图反序列化的**副本**（不是活的实例），字段展平，不带日志位置（位置只供运行时记检查点）。
- **Kafka 上发布的是 `StateEventRecord`，不是单纯的事件流**（D58）：消费端拿到提交后的聚合状态，不必再去请求上游服务。
  - **默认总是附带状态。** 超大状态的聚合可在编译期用 `@AggregateRoot(publishState = false)` 关闭（写进 Manifest，没有运行时配置，D156），关闭后 `state` 为 null。
  - **载荷是嵌入的 JSON 对象，不是转义后的字符串**：事件的 `payload`、状态都原样嵌入，人可以直接读，下游可以直接按路径取值。
  - 事件先升级到当前 revision 再发布（F10）。schema 是上下文的公开语言，登记在 Manifest 里。
- **快照**就是最后一个 `StateEventRecord` 折叠成的视图加元数据（§6），两者字段同源：`state` 对应 `SnapshotRecord.state`，`stream.scope` 对应快照的作用域。

## 6. 快照

`SnapshotRecord` 保持现状（[03](03-log-and-snapshot.md) §3）。有两点对齐：

- `ownerId`、`spaceId` 合并为 `scope: Scope`，取自最后一条事件流的 `scope`（D56），不再需要另外的来源。
- 带 `operator`（最后一条事件流的操作人）与 `firstOperator`（创建时的操作人），都是可查询的系统字段（D163）。
- `schemaFingerprint` 与 `StateView.schemaFingerprint` 用同一种算法（由内核的 `EventCodec` 计算）。

## 7. 命令结果（对外，D60）

内核的 `CommandOutcome` 是内部结局。对外（HTTP 响应、SSE、等待信号、`wow-client`）用一个结构：

```kotlin
class CommandResult(
    val format: Int,
    val commandId: String,
    val requestId: String,
    val correlationId: String,
    val ref: AggregateRef,
    val hop: Hop,                    // 这个结果对应等待链上的哪一跳：(context, stage, subscription?)
    val function: FunctionInfo,      // 谁处理的：(kind, context, processor, name)
    val status: Status,              // SUCCEEDED / REJECTED / FAILED
    val version: Int? = null,        // 提交或未变化时的版本
    val duplicate: Boolean = false,  // 同一请求早已提交，这次没有再决定
    val error: ErrorInfo? = null,    // code、message、violations（REJECTED、FAILED）
    val results: Map<String, Any> = emptyMap(), // putResult（D51：只在首次提交时有）
    val derived: List<String> = emptyList(), // Saga 跳：从这次提交派生的命令 ID（D62）
    val signalTime: Long,
    val chainEnded: Boolean = false, // 链在计划之前结束：这一跳之后没有派生（D212）
    val pending: List<Hop> = emptyList(), // chainEnded 时，计划里未到达的跳，按顺序
)
```

它在 Kotlin 里是普通类，不是 data 类（D216 ⑤）：构造函数标 `@InternalWowApi`，用户用 `CommandResult.succeeded`、`rejected`、`failed` 构造，Wow 自己用不进 ABI 的 `copyWith`。这样按 D59 在格式 1 内加字段时，公开的签名不变。其他线上信封（`Command`、`ErrorInfo`、`Rejection`、`StateEvent`、`CommandCommitted`、`CommandRejected`、`WaitPlan`、`Hop`、`WaitSignal`、`LedgerEntry`、`CommandRequest`）与运行时的 `*Options` 同样处理。

| 内核结局 | `status` | 说明 |
|---|---|---|
| `Committed` | SUCCEEDED | `version` 为新版本，带 `results` |
| `AlreadyCommitted` | SUCCEEDED | `duplicate = true`，没有 `results` |
| `Unchanged` | SUCCEEDED | `version` 不变 |
| `Rejected` | REJECTED | `error.code` 为拒绝码 |
| `Contended` | FAILED | `wow.command.contended`，可以用同一请求 ID 重试 |
| `Unknown` | FAILED | `wow.command.unknown`，可以用同一请求 ID 重试，幂等保证不会写两次 |
| 等待超时 | FAILED | `wow.wait.timeout`；只说明没等到，不代表命令失败 |

- **HTTP**：成功返回 200 与 `CommandResult`。拒绝和失败返回 `application/problem+json`（RFC 9457）：`type` 为 `urn:wow:error:<码>`，扩展成员有 `code`、`violations`，完整的 `CommandResult` 嵌在 `result` 下（D101；展平会让 `status` 与 RFC 9457 的整数状态冲突），客户端仍只需一种解析方式。
- **SSE**：等待链上每一跳到达时推送一个 `CommandResult`，最后一跳之后关闭；链提前结束时（D212），最后一个结果带 `chainEnded` 与 `pending`，同步响应返回的也是它。
- **`function` 保留**：SSE 流式推送每一跳的结果时，客户端要知道是谁处理的（命令函数、哪个投影器、哪个 Saga 函数）。`kind` 取 COMMAND、PROJECT、REACT、SAGA、SNAPSHOT；订阅由 `处理器.函数` 编成（[04](04-subscriptions.md) §1），所以 `processor` 与 `name` 直接来自订阅定义。
- **`bindingErrors` 改名保留**：字段级校验错误是 `error.violations: List<FieldViolation(path, code, message)>`，客户端表单直接使用；HTTP 上作为 problem+json 的扩展成员 `violations` 输出。

## 8. 等待信号与 Kafka 信封

**等待信号**（信号入口与集群通道）：

```kotlin
data class WaitSignal(val format: Int, val waitId: String, val result: CommandResult)
```

去重键是（`waitId`，`result.hop`，来源）。来源指发信号的上下文与订阅（[04](04-subscriptions.md) §6.3）。

**Kafka 信封**（D58）：

```text
key      = 聚合引用（同一聚合进同一 Kafka 分区，保持聚合内顺序）
headers  = wow-format, wow-kind, wow-type
value    = { "format": 1, "kind": "state-event" | "command" | "command-receipt",
             "id": …, "type": …, "payload": {…}, "transport": {…} }
```

- 三种 `kind` 的 `id` 和 `payload`：
  - `state-event`：`id` 是 `ref@version`，`payload` 是 §5 的结构。
  - `command`：`id` 是命令 ID，`payload` 是 §3 的线上形式。
  - `command-receipt`：`id` 是命令 ID，`payload` 是 `CommandResult`，即跨上下文命令的回执（D47、R7）。
- 下游按 `id` 去重。`transport` 只放传输段（D25）。
- **主题**（D135）：`state-event` 发往 `wow.<ctx>.<agg>.state`（每个聚合类型一个，即 Manifest `published[].topic`，可覆盖）；`command` 发往 `wow.<ctx>.commands`，分区数等于该上下文日志的分区数 P，生产者用日志的 `Partitioner` 选分区；`command-receipt` 发往 `wow.<replyTo.context>.receipts`（D136）。

**ClickHouse Sink 的表**（D142，D164 修订）：Sink 每个聚合类型只写一张基表，另外两张表由 ClickHouse 物化视图生成：

| 表 | 谁写 | 引擎与键 | 内容 |
|---|---|---|---|
| `<ctx>_<agg>_state_events` | Sink | MergeTree，按 `commit_time` 的月分区，`ORDER BY (tenant_id, aggregate_id, version)` | 每个 `StateEventRecord` 一行：系统列、`operator`、事件数组与状态的 JSON；即完整历史 |
| `<ctx>_<agg>_state` | 物化视图 | ReplacingMergeTree(version)，ORDER BY (tenant_id, aggregate_id) | 当前状态 |
| `<ctx>_<agg>_events` | 物化视图（`ARRAY JOIN events`） | MergeTree | 每个事件一行 |
| `<ctx>_<agg>_state_flat` | 视图（建在 `_state` 上） | — | Manifest 状态 schema 的顶层标量展开为类型化列，作为数据集源表；嵌套展开推迟 |

按聚合可配 `history.ttl`（历史保留期）或 `history=false`（只留当前状态）；物化视图链路开 `deduplicate_blocks_in_dependent_materialized_views`，重试插入不重复。

插入以 `ref@version` 为去重令牌；`wow.clickhouse.sink.ddl`（`auto`、`validate`、`none`）决定建表还是校验。

## 9. 格式版本与演进（D59）

- 有版本号的结构：日志条目的事实与传输段、快照记录、`StateEventRecord`、命令的线上形式、`CommandResult`、`WaitSignal`、Kafka 信封、集群通道消息。
- 日志与快照的格式版本按表记录（schema 元数据），不按行存；线上结构每条消息带 `format`。
- **加法不升版本**：新增带默认值的可选字段，旧读者忽略未知字段，新读者为缺失字段取默认值（R5-1）。
- **主版本内的混部**（D48）：写者在一个次版本里只能「开始读」新字段，下一个次版本才开始写，保证相邻两个次版本能互读。
- **不兼容的变化**只在下一个主版本由迁移工具转换（D22）。

## 10. 对现有代码的改动

由一个工作包一次改完。M3 的三个后端都还没有生产数据，现在改成本最低。

| 改动 | 位置 |
|---|---|
| 命令类型名；事件流以 `commandType` 取代 `fingerprint`，内核不再给命令体算哈希 | `wow-kernel`（模型、`EventCodec`）、`wow-compiler` 生成代码 |
| `Command` 以 `scope: Scope` 取代 `ownerId`、`spaceId`，增加 `causation`、`wait` | `wow-api` |
| `EventStreamRecord` 增加 `scope`、`causation`、`headers`；`SnapshotRecord` 以 `scope` 取代两个字段 | `wow-spi`、内存、PostgreSQL、MongoDB 日志、`EventLogSpec` |
| `LogEntry.transport`、`append(stream, transport)` | 同上；D50 的预留确认依赖它 |
| 内核维护 `scope`，生命周期命令改变 `scope` | `wow-kernel`（`CachedState`、生命周期，路线图 2.4） |
| `StateEventRecord`、`StateView` | `wow-spi`（D132，原写 `wow-kernel`；提交时由内核序列化），订阅运行时与 Sink 使用；类型化的 `StateEvent<S>` 在 `wow-api`（已合入） |
| 对外 `CommandResult`、`WaitSignal` | `wow-api`（M4 的等待与 `wow-http` 使用） |

**集群通道的命令转发**（D78，Ahoo-Wang/Wow#4251，格式 1）：

- 请求：`{v, command: {type, id, requestId, ref, body, expectedVersion, scope, headers, causation, operator, replyTo}, wait: {waitId, origin, remaining, deadline}, deadline, trace}`，请求头带本服务的集群令牌 `Wow-Cluster-Token`（D226）；`wait` 只在有等待时出现，`origin` 始终是发起节点。
- 响应：`{v, result, reached, refused}`。`reached` 是属主当场就能定下的后续跳（命令无变化时空转的 `HANDLED`、重复请求按检查点补查到的结果，D63），否则发起方分不清无变化与已提交；`refused` 为 `not-owner`、`expired`、`unknown-type`、`too-large` 之一，发起方据此在本地执行；请求已发出后超时或失败，发起方返回结果不确定，不在本地执行（D223）。
- 编解码器的 Jackson 模块集合固定（只有 Kotlin 模块），不从类路径自动发现，保证不同宿主的线上形式一致（D48）。

## 11. 待细化

- 新条目推送的消息随订阅侧定义。
