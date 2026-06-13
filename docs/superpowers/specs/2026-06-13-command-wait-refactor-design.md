# Command Wait Architecture Redesign

## Goal

重设 command wait 架构，删除以 `WaitStrategy` 为中心的旧模型，改成以不可变等待计划、低分配 final wait、显式 streaming wait、集中协调器和纯语义 reducer 组成的新模型。

这次设计接受 public API breaking change。目标不是保留旧 facade，而是在完成实现时清理 `WaitStrategy`、`WaitingFor` 继承树、`WaitStrategyRegistrar` 和 strategy 命名传播层带来的技术债。

性能验收仍以 `CommandWriteE2EBenchmark.sendAndWaitProcessed` 的 JMH `gc.alloc.rate.norm` 为主，吞吐量为辅助信号。`sendAndWait` final result 路径必须避免 `Flux.collectList()`、默认 unicast sink 和 streaming 成本。

## Current Problems

当前 wait 模型的问题不是单点实现低效，而是职责边界错误。

1. `WaitStrategy` 同时描述“等什么”和“怎么运行”。它既负责 header propagation 和 shouldNotify，又暴露 `waiting()`、`waitingLast()`、`next()`、`complete()`、`error()`、`onFinally()` 等运行态方法。
2. `WaitingFor` 构造时立即创建 unicast `Sinks.Many`。即使调用方只需要 final result，也先承担 streaming wait 的对象分配和生命周期成本。
3. `waitingLast()` 由 `waiting().collectList()` 派生，导致 final wait 依赖流式实现、信号列表、排序和 copy 聚合。
4. `WaitingForAfterProcessed`、`WaitingForStage`、`SimpleWaitingForChain` 把完成语义压进继承结构。stage、function、chain、fail-fast、result merge 分散在多个可变对象中。
5. 生命周期分散在 `DefaultCommandGateway`、`WaitStrategyRegistrar`、`WaitingFor.onFinally` 和 `LocalCommandWaitNotifier` 之间。注册、传播、取消、错误和清理不能在一个边界内推理。
6. 命名技术债明显：代码外观仍是 Strategy，但真实需求是 wait intent、runtime handle、notification dispatch 和 signal reduction。

## New Architecture

### WaitPlan

`WaitPlan` 是不可变等待意图，不持有 sink，不接收 signal，不暴露运行态方法。

职责：

- 保存 `waitCommandId`。
- 描述目标 stage。
- 描述可选 function 匹配条件。
- 描述可选 chain tail。
- 声明是否支持 void command。
- 提供 header propagation 所需数据。

建议形态：

```kotlin
interface WaitPlan : WaitCommandIdCapable {
    val target: WaitTarget
    val supportVoidCommand: Boolean
    fun propagate(endpoint: CommandWaitEndpoint, header: Header)
}

sealed interface WaitTarget
data class StageWaitTarget(...)
data class ChainWaitTarget(...)
```

现有 `WaitingForStage.processed(commandId)` 这类工厂迁移为 `CommandWait.processed(commandId)`，返回 `WaitPlan`。

### WaitLastHandle

`WaitLastHandle` 是 final result 专用运行态。

职责：

- 暴露 `await(): Mono<WaitSignal>`。
- 接收被 `WaitCoordinator` 分发的 signal。
- 使用 `WaitSignalReducer` 更新 final state。
- 完成、失败或取消时通知 coordinator 清理。

禁止事项：

- 不创建 `Flux` sink。
- 不实现 `stream()`。
- 不通过 `stream().collectList()` 得到 final result。
- 不保存完整信号列表，除非 reducer 的具体语义必须保留多个信号的 result merge。

建议形态：

```kotlin
interface WaitLastHandle : WaitCommandIdCapable {
    fun await(): Mono<WaitSignal>
    fun cancel()
}
```

### WaitStreamHandle

`WaitStreamHandle` 是 progress stream 专用运行态。

职责：

- 暴露 `stream(): Flux<WaitSignal>`。
- 维护 replay、后续 signal 推送、完成、错误和取消。
- 仍使用同一个 `WaitSignalReducer` 判断何时 complete。

它是显式高成本路径。只有 `CommandGateway.sendAndWaitStream` 创建它。

建议形态：

```kotlin
interface WaitStreamHandle : WaitCommandIdCapable {
    fun stream(): Flux<WaitSignal>
    fun cancel()
}
```

`WaitLastHandle` 和 `WaitStreamHandle` 不互相继承，避免 final wait 被 streaming 语义污染。

### WaitCoordinator

`WaitCoordinator` 是 wait runtime 的唯一协调边界。

职责：

- 根据 `WaitPlan` 创建 `WaitLastHandle` 或 `WaitStreamHandle`。
- 注册运行态 handle。
- 在命令发送前传播 wait header。
- 接收本地或远端 `WaitSignal` 并分发给 handle。
- 处理 command send 成功/失败的 `SENT` signal。
- 处理完成、错误、取消和 unregister。

建议形态：

```kotlin
interface WaitCoordinator {
    fun createLast(plan: WaitPlan): WaitLastHandle
    fun createStream(plan: WaitPlan): WaitStreamHandle
    fun propagate(plan: WaitPlan, endpoint: CommandWaitEndpoint, header: Header)
    fun signal(signal: WaitSignal): Boolean
    fun unregister(waitCommandId: String)
}
```

`DefaultCommandGateway` 不再直接依赖 registrar，也不再通过 `onFinally` 清理旧策略。

### WaitSignalReducer

`WaitSignalReducer` 是纯语义组件，用来消除当前继承结构里的隐式状态规则。

职责：

- 判断 previous stage。
- 执行前置阶段失败 fail-fast。
- 判断目标 stage 是否满足。
- 判断 function 是否匹配。
- 处理 projection last-only。
- 处理 chain 主 saga 和 tail commands 完成条件。
- 合并 result，后到达 signal 覆盖同名 key。
- 选择 final signal。

建议形态：

```kotlin
interface WaitSignalReducer {
    fun reduce(state: WaitReductionState, signal: WaitSignal): WaitReduction
}

data class WaitReduction(
    val state: WaitReductionState,
    val signals: List<WaitSignal> = emptyList(),
    val completed: Boolean = false,
    val finalSignal: WaitSignal? = null,
    val error: Throwable? = null,
)
```

final handle 可以只保存 reducer 必需的压缩状态；stream handle 可以额外 replay reducer 接受的信号。

## Public API

`CommandGateway` 改用 `WaitPlan`：

```kotlin
interface CommandGateway : CommandBus {
    fun <C : Any> sendAndWait(
        command: CommandMessage<C>,
        waitPlan: WaitPlan
    ): Mono<CommandResult>

    fun <C : Any> sendAndWaitStream(
        command: CommandMessage<C>,
        waitPlan: WaitPlan
    ): Flux<CommandResult>
}
```

便捷 API 保留，但内部改为 plan：

```kotlin
fun <C : Any> sendAndWaitForProcessed(command: CommandMessage<C>): Mono<CommandResult> =
    sendAndWait(command, CommandWait.processed(command.commandId))
```

新的工厂入口：

```kotlin
object CommandWait {
    fun sent(waitCommandId: String): WaitPlan
    fun processed(waitCommandId: String): WaitPlan
    fun snapshot(waitCommandId: String): WaitPlan
    fun projected(waitCommandId: String, contextName: String, processorName: String = "", functionName: String = ""): WaitPlan
    fun eventHandled(waitCommandId: String, contextName: String, processorName: String = "", functionName: String = ""): WaitPlan
    fun sagaHandled(waitCommandId: String, contextName: String, processorName: String = "", functionName: String = ""): WaitPlan
    fun chain(waitCommandId: String, function: NamedFunctionInfoData, tail: WaitChainTail): WaitPlan
}
```

## Signal Flow

`sendAndWait`：

1. Gateway 创建或接收 `WaitPlan`。
2. Gateway 请求 `WaitCoordinator.createLast(plan)`。
3. Coordinator 注册 `WaitLastHandle` 并传播 wait header。
4. Gateway 执行 command check 和 command bus send。
5. send 成功或失败时，coordinator 直接写入 `SENT` signal。
6. 下游处理阶段通过 `CommandWaitNotifier` 发布 signal。
7. Coordinator 按 `waitCommandId` 分发 signal。
8. `WaitLastHandle` 通过 reducer 完成 final signal。
9. Coordinator unregister。

`sendAndWaitStream` 相同，但创建 `WaitStreamHandle`，并且 streaming sink 只在该路径存在。

## Header And Wire Compatibility

本设计第一阶段不改 header 字段含义，降低跨模块迁移风险。旧字段可以保留名称，内部提取结果改为 `WaitPlan`：

- command wait endpoint
- wait command id
- wait stage
- wait function
- wait chain
- wait chain tail

代码命名应从 `extractWaitStrategy()` 改为 `extractWaitPlan()`，从 `WaitStrategyMessagePropagator` 改为 `WaitPlanMessagePropagator` 或更具体的 `CommandWaitPlanPropagator`。

## Technical Debt Cleanup

实现完成时必须删除或替换以下旧概念，不能留下 deprecated facade：

- `WaitStrategy`
- `WaitStrategy.Materialized`
- `WaitStrategy.FunctionMaterialized`
- `WaitStrategyRegistrar`
- `SimpleWaitStrategyRegistrar`
- `WaitingFor`
- `WaitingForStage`
- `WaitingForAfterProcessed`
- `WaitingForProcessed` / `WaitingForSnapshot` / `WaitingForProjected` / `WaitingForEventHandled` / `WaitingForSagaHandled` / `WaitingForSent`
- `SimpleWaitingForChain` 运行态继承实现
- `WaitStrategyMessagePropagator`
- `TracingWaitStrategy`
- 所有 `waiting()` / `waitingLast()` / `next()` / `complete()` / `onFinally()` 旧运行态 API

允许保留的只有语义等价的新类型、工厂入口和协议字段。

## Migration Scope

必须同步更新：

- `wow-core` command wait runtime。
- `DefaultCommandGateway` 生命周期编排。
- `CommandWaitNotifier` 本地分发逻辑。
- message propagation 提取和传播逻辑。
- chain wait 实现。
- OpenTelemetry wait tracing 命名和包装点。
- tests、contract tests、TCK 中的 wait API 使用。
- example 和 documentation 中的 `WaitingForStage.*` 使用。

不在本轮改变：

- Gradle module structure。
- CI/publish 配置。
- wire/header 字段名，除非实现中发现无法兼容。
- 外部基础设施存储或 transport 行为。

## Testing

单元测试重点：

- `WaitPlan` factory 和 header propagation/extraction。
- `WaitSignalReducer` stage wait：sent、processed、snapshot、projected、eventHandled、sagaHandled。
- reducer fail-fast：等待后续 stage 时 previous stage 失败应完成为失败 result。
- reducer function matching。
- reducer projection last-only。
- reducer chain：主 saga 匹配、tail command 创建、所有 tail 完成、无 tail command 立即完成。
- `WaitLastHandle`：无 stream sink、单信号完成、多信号 result merge、错误、取消、完成后 await。
- `WaitStreamHandle`：replay、中间 signal、完成、错误、取消。
- `WaitCoordinator`：register、signal dispatch、unregister、send 成功/失败 signal、本地同步 notify。

建议验证命令：

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.command.wait.*"
./gradlew :wow-core:test --tests "me.ahoo.wow.messaging.propagation.*Wait*"
./gradlew :wow-opentelemetry:test --tests "me.ahoo.wow.opentelemetry.wait.*"
```

如果测试过滤不覆盖子包，则运行 `./gradlew :wow-core:test`。

## Benchmark Validation

实现完成后用同机同窗口 baseline 验证，不用 checked-in report 直接声明收益。主要看 `gc.alloc.rate.norm`。

推荐 quick E2E：

```bash
./gradlew :wow-benchmarks:benchmarkQuickE2E :wow-benchmarks:generateBenchmarkReport
```

若要声明性能收益，必须报告：

- baseline commit 和 after commit。
- `CommandWriteE2EBenchmark.sendAndWaitProcessed` 各 scenario 的 `gc.alloc.rate.norm`。
- `sendAndWaitSent` 是否保持现有快路径水平。
- throughput 只作为辅助，不盖过 allocation 结论。

## Risks

- 这是 public API breaking change，需要一次性迁移内部、测试、示例和文档。
- `WaitSignalReducer` 必须精确复刻旧 stage/chain 语义，否则会造成行为回归。
- final handle 和 stream handle 分离后，两个路径必须共享 reducer，避免语义漂移。
- local notify 的同步快路径必须保留，否则可能抵消架构收益。
- OpenTelemetry 原先包装 `WaitStrategy`，需要移动到 coordinator/handle 边界。

## Decision

采用破坏性新架构：

```text
WaitPlan            不可变等待意图
WaitLastHandle      final result 低分配运行态
WaitStreamHandle    progress stream 运行态
WaitCoordinator     注册、传播、分发、清理
WaitSignalReducer   stage/chain/fail-fast/final signal 语义
```

最终完成时删除 `WaitStrategy` 和相关技术债，不保留兼容 facade。
