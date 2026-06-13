# Command Wait Refactor Design

## Goal

重构 `wow-core/src/main/kotlin/me/ahoo/wow/command/wait/`，让等待策略的职责边界更清楚，同时保持命令等待热路径的性能不退化。主要验收指标是 `CommandWriteE2EBenchmark.sendAndWaitProcessed` 的 JMH `gc.alloc.rate.norm`，吞吐量作为辅助信号。

## Current Context

当前 `command/wait` 目录包含三类职责：

- 等待策略 API 和状态机：`WaitStrategy`、`WaitingFor`、`WaitingForStage`、`WaitingForAfterProcessed`、`SimpleWaitingForChain`。
- 等待策略传播和提取：`ExtractedWaitStrategy`、header key、stage/function/chain materialized 策略。
- 处理完成通知：`NotifierFilters`、`MonoCommandWaitNotifier`、`CommandWaitNotifier`、`WaitStrategyRegistrar`。

主要结构问题是 `WaitingFor` 同时承担 sink 生命周期、信号缓存、最终信号聚合、完成状态和 finally hook 编排；`WaitingForAfterProcessed` 又覆盖了一套 `waitingLast()` 聚合逻辑。链式等待还维护自己的子状态。这个形态让行为边界和性能优化边界都不够清晰。

已知性能背景：当前 checked-in quick benchmark 报告中，Framework E2E `sendAndWaitProcessed` 约在 `5.0-6.9 KB/op` 分配区间。历史实验表明，降低等待路径分配的关键在于避免 `waitingLast()` 默认经过 `waiting().collectList()`，并让仅订阅 final signal 的路径不必创建流式 `Sinks.Many`。

## Scope

- 保持 public API、包名、header 协议字段、等待策略语义兼容。
- 只在 `wow-core` 的 command wait 模块内做职责拆分和必要测试调整。
- 新增 `internal` 状态组件 `WaitSignalJournal`，但不暴露为外部 API。
- 保留 `waiting()` 流式语义和 `waitingLast()` 最终信号语义。
- 保留 `LocalCommandWaitNotifier.notifyAndForget()` 同步本地转发并捕获异常的快路径。
- 最终用窄单元测试和 quick E2E benchmark 验证。

## Non-Goals

- 不改变 Gradle 模块结构、feature variants、发布配置或 CI workflow。
- 不改 wire/header 字段名称或跨进程等待协议。
- 不引入新的第三方依赖。
- 不把远端 wait notifier、WebFlux endpoint、Kafka/Mongo/Redis 等基础设施路径纳入本轮重构。
- 不以组件 benchmark 作为主要收益结论；组件 benchmark 可辅助定位，但最终结论以 quick E2E 为准。

## Selected Approach

采用“状态存储组件化，吸收低分配路径”的方案。

`WaitingFor` 和具体子类只表达“等什么”：阶段依赖、函数匹配、链式等待完成条件、前置阶段失败时的 fail-fast 规则。它们不直接管理 Reactor sink、信号列表或 final signal 聚合细节。

新增一个 `internal` 状态组件 `WaitSignalJournal`，只表达“怎么等”：缓存收到的 `WaitSignal`，为 `waiting()` 提供 replay 和后续流式信号，为 `waitingLast()` 提供 final `Mono`，维护 completed/failed/cancelled 状态，并支持终止后订阅的重放行为。

传播和通知层保持现有职责。`ExtractedWaitStrategy` 继续从 header 还原 materialized 策略；`MonoCommandWaitNotifier` 继续在处理阶段完成或失败时构造 `WaitSignal`；`CommandWaitNotifier.notifyAndForget(extracted, signal)` 继续先判断 `shouldNotify(signal)` 再通知 endpoint。

## State Model

`WaitSignalJournal` 维护四类状态：

- `active`：可以接收信号，可以按订阅类型物化 sink。
- `completed`：不再接收新信号；`waitingLast()` 返回 final signal 或 empty；`waiting()` replay 已缓存信号后完成。
- `failed`：不再接收新信号；`waiting()` 和 `waitingLast()` 传播 terminal error。
- `cancelled`：订阅取消后标记；后续信号忽略。

信号缓存使用轻量结构保存到达顺序。只有 `waiting()` 被订阅时才创建 `Sinks.Many`，用于 replay 后继续流式发送。`waitingLast()` 使用 `Sinks.One` 或已完成的 final result，避免 `Flux.collectList()` 成为默认 final wait 路径。

`onFinally` 必须最多执行一次。hook 自身异常只记录日志，不影响命令处理管线。

## Final Signal Rules

- 普通 stage 默认从已缓存信号中选择 `signalTime` 最大的信号；如时间相同，保留后到达的信号。
- 多个信号的 `result` 按到达顺序合并，后到达的同名 key 覆盖前面的 key。
- 如果只有一个信号，`waitingLast()` 可以直接返回原信号，避免不必要 copy。
- `WaitingForAfterProcessed` 的 final signal 优先使用目标 stage 的匹配信号，而不是按时间最后一个信号；这保持 snapshot/projected/eventHandled/sagaHandled 的现有语义。
- 如果手动完成时还没有目标 stage 信号，则退回到已有信号中的最后语义。
- `waiting()` 仍然发出所有收到的信号，不因 final signal 规则过滤中间信号。

## Chain Waiting

`SimpleWaitingForChain` 保持现有外部行为：先等待主命令的 `SAGA_HANDLED` 匹配函数，再为 saga 发出的 tail commands 创建 tail stage 等待。所有 tail waiting 完成后主 chain 完成。

本轮不改 chain header 协议。实现上可以让 chain 继续复用 `WaitingFor` 的 journal，并将“主 saga 是否完成、tail commands 是否全部完成”的判断留在 `SimpleWaitingForChain` 内。若需要调整 final signal 选择，必须先补测试锁住当前行为。

## Error Handling

- `next(signal)` 在策略完成、失败或取消后忽略新信号并记录 warn。
- 前置阶段失败时继续 fail-fast complete，例如等待 `SNAPSHOT` 时 `PROCESSED` 失败会结束等待。
- `error(Throwable)` 进入 failed terminal；已订阅和后订阅的 `waiting()` / `waitingLast()` 都能看到该 error。
- 本地 `notifyAndForget` 继续捕获 registrar 异常，避免 fire-and-forget 通知破坏处理管线。
- `MonoCommandWaitNotifier` 继续把 retry exhausted 包装错误还原为 cause，并用现有 `ErrorInfo` 转换逻辑生成失败信号。

## Testing

优先补充或调整以下测试：

- Journal/lifecycle：完成前订阅、完成后订阅、取消、错误、空完成、单信号不 copy、多信号 result 合并、`waiting()` replay。
- Stage strategy：`processed`、`snapshot`、`projected`、`eventHandled`、`sagaHandled` 的完成条件、函数匹配、projection last-only、前置失败 fail-fast。
- Chain strategy：主 saga 匹配、tail command 创建、所有 tail 完成后 chain 完成、无 tail command 立即完成、非主命令 tail 信号分发。
- Notification layer：filter 生成正确 stage signal，`LocalCommandWaitNotifier` 本地快路径行为不变，`notifyAndForget` 继续吞掉异常。
- Propagation：stage/function/chain header 提取和传播兼容。

建议验证命令：

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.command.wait.*"
./gradlew :wow-core:test --tests "me.ahoo.wow.messaging.propagation.*Wait*"
```

如果测试过滤表达式没有覆盖子包或在 Gradle/JUnit 下不匹配，改用更窄的具体测试类列表，或者运行 `./gradlew :wow-core:test`。

## Benchmark Validation

实现完成后先读取 checked-in quick reports 作为背景，再运行 quick E2E。若要声明性能收益，必须使用同机同窗口 baseline 和 after-run 对比；checked-in report 只作为定位背景。主要看 `gc.alloc.rate.norm`，吞吐量只作为辅助。

推荐验证：

```bash
./gradlew :wow-benchmarks:benchmarkQuickE2E :wow-benchmarks:generateBenchmarkReport
```

报告结论必须包含：

- 同机同窗口 baseline；如果没有重跑 baseline，只能把 checked-in quick report 写成背景，不能据此声明收益。
- 重构后的 quick E2E 数值。
- `CommandWriteE2EBenchmark.sendAndWaitProcessed` matched scenarios 的 `gc.alloc.rate.norm` 对比。
- 如果收益不明显或结果在噪声内，结论写成架构清洁且性能基本持平，不夸大 throughput 波动。

## Compatibility And Risks

兼容性目标是外部 API 零变化。主要风险集中在 Reactor sink 生命周期、订阅取消、完成后订阅、错误重放和 `onFinally` 触发次数。实现必须先用测试锁住这些边界，再改状态组件。

另一个风险是过度吸收历史实验代码。历史实现只能作为参考，最终实现要适配当前 `main` 上的最近提交，尤其是本地 `notifyAndForget` 同步快路径。
