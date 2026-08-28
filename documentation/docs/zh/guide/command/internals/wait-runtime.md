---
title: 命令等待运行时
description: 理解 WaitPlan Header、阶段信号、等待状态、句柄协调、远程回调与 fire-and-forget 边界。
outline: deep
---

# 命令等待运行时

等待运行时把“处理到哪里”建模为可路由的 `WaitSignal`，而不是阻塞命令线程。应用如何选择阶段见[完成语义](../completion.md)，如何调用 Gateway 见[发送命令](../sending.md)；本页只解释信号如何产生、传输和归约。

## WaitPlan Header

`WaitPlan` 包含 `waitCommandId`、`WaitTarget` 和 `supportVoidCommand`。`DefaultCommandGateway` 先注册本地 handle，再通过 `WaitPlan.propagate` 把三类信息写入命令 Header：

- 等待关联 ID：`command_wait_id`；
- 回调地址：`command_wait_endpoint`；
- 目标：阶段、可选函数身份，或 chain 及其 tail 描述。

先注册再发送可避免本地快速处理在 handle 可见前返回信号。Header 会由 `WaitPlanMessagePropagator` 沿相关消息继续传播；普通阶段目标只从命令向后传播，chain 目标还会穿过事件和 Saga 命令，并在非命令消息上保留 tail 信息。

处理器通过 `Header.extractWaitPlan` 重建 `ExtractedWaitPlan`。缺少关联 ID、endpoint 或可解析 target 时，等待通知保持 no-op；Header 不是调用端本地对象的远程引用。

## Notifier Filter

每个阶段由所在 Dispatcher 的外层 Filter 观察：`PROCESSED`、`SNAPSHOT`、`PROJECTED`、`EVENT_HANDLED` 与 `SAGA_HANDLED` 分别挂到对应处理链。Filter 先执行 `next.filter(exchange)`，再由 `MonoCommandWaitNotifier` 在完成或错误信号上组装 `WaitSignal`。

通知前有两层裁剪：处理阶段必须属于目标所需的阶段集合，具体信号还必须满足目标阶段和可选函数匹配。`PROJECTED` 信号携带 `isLastProjection`；`SAGA_HANDLED` 信号还携带该 Saga 实际发送的后续 `commandId`。

`SENT` 不来自这些 Dispatcher Filter。带等待 Header 的普通 `send` 在 `CommandBus.send` 完成或报错时通知；`sendAndWait` 和 `sendAndWaitStream` 则直接把 `SENT` 信号交给已注册 handle，`sendAndWaitForSent` 是不注册 handle 的快路径。非流式 handle 等待后续阶段时可跳过成功 `SENT`，流式 handle 会保留它。

## WaitSignal

`WaitSignal` 是一次阶段观察，核心字段包括：

| 字段 | 运行时用途 |
| --- | --- |
| `waitCommandId` | 在 `WaitCoordinator` 中定位 handle |
| `commandId` | 区分主命令与 chain 后续命令 |
| `stage` / `function` | 选择阶段和函数 |
| `aggregateId` / `aggregateVersion` | 关联聚合与已知版本 |
| `errorCode` / `errorMsg` / `bindingErrors` | 表达该阶段成功或失败 |
| `result` | 累计命令/处理函数结果 |
| `isLastProjection` | 区分投影流的最后一条事件 |
| `commands` | `SAGA_HANDLED` 产生的后续命令 ID |

信号是观察记录，不是全局事务提交。不同分支可以并发到达，等待状态负责判断何时满足合同。

## StageWaitState

`StageWaitState` 处理单一阶段目标。它忽略目标不需要的信号，接受前置和目标信号，并累计非空 `result`。

- 前置阶段失败时立即以该失败信号完成；
- `PROJECTED` 只有匹配且 `isLastProjection == true` 的信号可成为最终信号；
- `SNAPSHOT`、`PROJECTED`、`EVENT_HANDLED`、`SAGA_HANDLED` 的目标信号即使先到，也要等 `PROCESSED` 被观察后才完成；
- `SENT` 与 `PROCESSED` 不需要额外的 processed 门槛。

这使状态机适应分布式乱序，但不会把平行分支误建模为线性阶段。

## ChainWaitState

`ChainWaitState` 表达“主 Saga 函数 + 它实际发出的命令”。它先等待匹配的主 `SAGA_HANDLED` 信号，从 `commands` 建立每条 tail 命令的 `StageWaitState`，再等待所有 tail 状态完成。

tail 信号可能先于主 Saga 信号到达。状态机会按到达序号暂存候选信号；主信号确认 command ID 后只重放对应项，未被确认的项不能完成 chain。结果字段也按观察序号合并，较晚信号覆盖同名 key。

主链或已经完成的 tail 出现失败时可提前结束；成功链只有在主信号、`PROCESSED` 和所有 tail 状态都满足后完成。Chain 的应用语义见[完成语义](../completion.md#链式等待)。

## Handle/Coordinator

`DefaultWaitCoordinator` 用 `ConcurrentHashMap<waitCommandId, WaitHandle>` 路由信号。同一个 `waitCommandId` 只能注册一个 handle；未知 ID 或被状态机忽略的信号返回 `false`。

`DefaultWaitLastHandle` 使用 `Sinks.one` 只保留最终信号；`DefaultWaitStreamHandle` 使用单订阅 unicast sink，缓冲并发到达的 accepted 信号。两者都在锁内归约状态，并在完成、错误或取消时幂等注销。

handle 本身不应用 timeout。`DefaultCommandGateway` 把 `WaitPlan.timeout` 作为包含预检、发送和等待的端到端期限；`Mono.using` / `Flux.using` 保证超时、取消和正常终止都释放 handle。释放观察资源不会撤销已发送命令。

## 远程回调

等待发起节点的 endpoint 随 Header 到达处理节点。`WebClientCommandWaitNotifier` 先从 `waitCommandId` 的 machine ID 判断等待是否属于当前 JVM：

```text
本机 waitCommandId -> WaitCoordinator.signal
远程 waitCommandId -> HTTP POST endpoint -> CommandWaitHandlerFunction -> WaitCoordinator.signal
```

远程 POST 使用 JSON `WaitSignal`，并经过 `RemoteWaitNotifyPolicy` 的 retry/scheduler。接收端反序列化为 `SimpleWaitSignal` 后交给本地 coordinator，HTTP 只返回空成功响应。endpoint 是运行时回调地址，不应被当作业务 API。

## fire-and-forget 错误边界

阶段 Filter 调用 `notifyAndForget`，不会等待通知完成。默认实现主动订阅 `notify`，失败记录 endpoint、wait/command ID 和 stage；本地 notifier 同步调用 coordinator，并把异常记录后丢弃。通知错误不会覆盖原处理链的成功或失败。

这条边界带来两个必须区分的结果：

- 处理链成功但通知失败：命令可能已经完成，调用方只会超时或缺少阶段信号；
- 处理链失败：notifier 尝试发送失败信号，同时原错误仍沿处理链传播。

因此等待超时是“未观察到结果”，不是命令回滚证明。查询与重试流程见[失败与幂等](../reliability.md#超时后的查询与重试)。
