# V10 数据协议

日期：2026-10-12。基线：`v10` `4224` 之后。状态：草案（提案，待拍板 D55～D60）。上位文档：[00 目标架构](00-architecture.md)（A1、A6、A11）。相关决定：D15、D17、D22、D25、D46、D48、D49、D50、D51。

本文定义 V10 中跨越进程、存储或版本边界的数据结构：命令、事件流、`StateEvent`、快照、命令结果、等待信号、Kafka 信封。这些结构一旦落盘或上线，就只能按 §9 的规则演进，所以在 M4（运行时）与 M6（Kafka）之前定下。

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
| 聚合引用 | `context.aggregate/tenant/id` | `AggregateRef.toString()`，同时是预留的所有者（D38） |
| 命令类型 | `context.aggregate.CommandName` | 模型；默认取类的简单名，`@Name` 覆盖（**新增**，D55） |
| 事件类型 | `context.aggregate.EventName` | 模型（已实现），旧名作别名 |
| 错误码 | `wow.*`（框架）、领域自定 | `Rejection.code`，登记在 Manifest |
| 事件标识 | `context.aggregate/tenant/id@version#index` | 由（引用，版本，事件序号）推导，不另存（D57） |

## 3. 命令

```kotlin
data class Command<C : Any>(
    val id: String,
    val requestId: String,            // 租户内幂等键（D46）
    val ref: AggregateRef,
    val body: C,
    val expectedVersion: Int? = null,
    val ownerId: String? = null,      // 准入得到的作用域（D49）
    val spaceId: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val causation: Causation? = null, // 新增：因果链（§4）
    val wait: WaitPlan = WaitPlan.COMMITTED, // 新增：等待计划（D15），默认 COMMITTED
)
```

- **线上形式**（Kafka 命令主题、集群转发）：`{ type, id, requestId, ref, body, expectedVersion, ownerId, spaceId, headers, causation }`，`type` 是命令类型名，接收方按 Manifest 解码 `body`。等待计划不随命令传给目标上下文，只把 `WaitContext` 放进传输段（D25）。
- **指纹**（D46，修正，D55）：`SHA-256(类型名 + 规范化 JSON(body))`。规范化：键按码点排序，去掉值为 null 的键。现在的实现用 Java 类名，一改类名，同一请求的重试就会被判成「请求 ID 被占用」。剩余风险：部署前后给命令加了带默认值的字段，重试的规范化 JSON 会变。这种情况记为已知限制：跨部署的重试极少，被误判时得到明确的错误码，不会产生错误的事实。
- **`headers` 只放准入允许的请求事实**（例如调用方应用）。准入按白名单从请求里取，取到的都会写进事件流（§4），所以追踪 ID 这类只为投递服务的东西不放这里，放传输段。

## 4. 事件流（事实）

```kotlin
data class EventStreamRecord(
    val ref: AggregateRef,
    val version: Int,
    val commandId: String,
    val requestId: String,
    val fingerprint: String,
    val commitTime: Long,
    val events: List<EventRecord>,          // name、revision、payload
    val scope: AggregateScope,              // 新增（D56）
    val causation: Causation? = null,       // 新增
    val headers: Map<String, String> = emptyMap(), // 新增：来自命令
)

data class AggregateScope(val ownerId: String?, val spaceId: String?) // 提交后聚合的所有者与空间
data class Causation(val correlationId: String, val causationId: String)
```

- **`scope` 是这次提交之后聚合的所有者与空间**，不是发命令的人（D56）：
  - 创建时取自命令的 `ownerId`、`spaceId`。
  - 之后只由「转移所有者、转移空间」这两个生命周期命令改变（[01](01-programming-model.md) §6）。
  - 内核把它和状态一起维护：`CachedState` 增加 `scope`，快照加载时一并恢复。
  - 这样每条事实都自带作用域：投影、查询授权（ABAC）、快照订阅直接读取，不必回放历史。9.x 的 `DomainEventStream.ownerId` 也是这个语义。
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
)
```

`EventLog.append` 改为接收 `(stream, transport)`。`findByRequestId`、`read` 只返回事实；`tail` 返回带传输段的 `LogEntry`，供订阅与信号使用。

## 5. StateEvent（订阅与发布的单位）

```kotlin
data class StateEvent(
    val position: LogPosition,      // 发布到 Kafka 后，下游用偏移量；位置只在上下文内有意义
    val stream: EventStreamRecord,  // 事实
    val state: StateView,           // 提交后的状态视图
)
data class StateView(val version: Int, val schemaFingerprint: String, val json: String)
```

- **状态视图**：提交时把状态序列化一次（[01](01-programming-model.md) §2 的版本视图）。属主节点直接使用这个视图；重放和属主切换时，由快照加尾部演化得到。
- **进程内**：处理函数拿到解码后的事件和状态的**副本**（由视图反序列化，不是活的实例）。
- **Kafka 上**：`payload` 就是上面的结构（`state.json` 原样嵌入）。事件先升级到当前 revision 再发布（F10）。schema 是上下文的公开语言，登记在 Manifest 里。
- **快照**就是最后一个 `StateEvent` 折叠成的视图加元数据（§6），两者字段同源：`state.json` 对应 `SnapshotRecord.state`，`stream.scope` 对应 `ownerId`、`spaceId`。

## 6. 快照

`SnapshotRecord` 保持现状（[03](03-log-and-snapshot.md) §3）。有两点对齐：

- `ownerId`、`spaceId` 取自最后一条事件流的 `scope`（D56），不再需要另外的来源。
- `schemaFingerprint` 与 `StateView.schemaFingerprint` 用同一种算法（由内核的 `EventCodec` 计算）。

## 7. 命令结果（对外，D60）

内核的 `CommandOutcome` 是内部结局。对外（HTTP 响应、SSE、等待信号、`wow-client`）用一个结构：

```kotlin
data class CommandResult(
    val format: Int,
    val commandId: String,
    val requestId: String,
    val correlationId: String,
    val ref: AggregateRef,
    val hop: Hop,                    // 这个结果对应等待链上的哪一跳：(context, stage, subscription?)
    val status: Status,              // SUCCEEDED / REJECTED / FAILED
    val version: Int? = null,        // 提交或未变化时的版本
    val duplicate: Boolean = false,  // 同一请求早已提交，这次没有再决定
    val error: ErrorInfo? = null,    // code、message、details（REJECTED、FAILED）
    val results: Map<String, Any> = emptyMap(), // putResult（D51：只在首次提交时有）
    val signalTime: Long,
)
```

| 内核结局 | `status` | 说明 |
|---|---|---|
| `Committed` | SUCCEEDED | `version` 为新版本，带 `results` |
| `AlreadyCommitted` | SUCCEEDED | `duplicate = true`，没有 `results` |
| `Unchanged` | SUCCEEDED | `version` 不变 |
| `Rejected` | REJECTED | `error.code` 为拒绝码 |
| `Contended` | FAILED | `wow.command.contended`，可以用同一请求 ID 重试 |
| `Unknown` | FAILED | `wow.command.unknown`，可以用同一请求 ID 重试，幂等保证不会写两次 |
| 等待超时 | FAILED | `wow.wait.timeout`；只说明没等到，不代表命令失败 |

- **HTTP**：成功返回 200 与 `CommandResult`。拒绝和失败返回 `application/problem+json`（RFC 9457）：`type` 指向 Manifest 的错误码，扩展成员带上 `CommandResult` 的字段，客户端只需一种解析方式。
- **SSE**：等待链上每一跳到达时推送一个 `CommandResult`，最后一跳之后关闭。
- 与 9.x 相比，去掉了 `function`（处理函数信息）和 `bindingErrors`（改由准入的校验错误进 `error.details`）。

## 8. 等待信号与 Kafka 信封

**等待信号**（信号入口与集群通道）：

```kotlin
data class WaitSignal(val format: Int, val waitId: String, val result: CommandResult)
```

去重键是（`waitId`，`result.hop`，来源）。来源指发信号的上下文与订阅（[04](04-subscriptions.md) §6.2）。

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

## 9. 格式版本与演进（D59）

- 有版本号的结构：日志条目的事实与传输段、快照记录、`StateEvent`、命令的线上形式、`CommandResult`、`WaitSignal`、Kafka 信封、集群通道消息。
- 日志与快照的格式版本按表记录（schema 元数据），不按行存；线上结构每条消息带 `format`。
- **加法不升版本**：新增带默认值的可选字段，旧读者忽略未知字段，新读者为缺失字段取默认值（R5-1）。
- **主版本内的混部**（D48）：写者在一个次版本里只能「开始读」新字段，下一个次版本才开始写，保证相邻两个次版本能互读。
- **不兼容的变化**只在下一个主版本由迁移工具转换（D22）。

## 10. 对现有代码的改动

D55～D60 拍板后，由一个工作包一次改完。M3 的三个后端都还没有生产数据，现在改成本最低。

| 改动 | 位置 |
|---|---|
| 命令类型名与指纹（类型名加规范化 JSON） | `wow-kernel`（模型、`EventCodec`）、`wow-compiler` 生成代码 |
| `Command` 增加 `causation`、`wait` | `wow-api` |
| `EventStreamRecord` 增加 `scope`、`causation`、`headers` | `wow-spi`、内存、PostgreSQL、MongoDB 日志、`EventLogSpec` |
| `LogEntry.transport`、`append(stream, transport)` | 同上；D50 的预留确认依赖它 |
| 内核维护 `scope`，生命周期命令改变 `scope` | `wow-kernel`（`CachedState`、生命周期，路线图 2.4） |
| `StateEvent`、`StateView` | `wow-kernel`（提交时序列化），订阅运行时使用 |
| 对外 `CommandResult`、`WaitSignal` | `wow-api`（M4 的等待与 `wow-http` 使用） |

## 11. 待细化

- `WaitPlan` 与 `WaitContext` 的具体字段（链的表示、超时），随 M4 的等待（路线图 4.7）定。
- 集群通道消息的种类与字段（转发、信号中继、新条目推送）。
- 大状态的 `StateView`：超过阈值时是否只发布事件、不附状态。
