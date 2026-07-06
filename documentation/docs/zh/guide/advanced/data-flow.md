---
title: 数据流
description: 追踪 Wow 框架中的完整数据流 — 从命令到达、事件持久化、投影更新到 Saga 处理。
---

# 数据流

本页追踪数据在 Wow 框架中流动的完整生命周期，从命令到达 Gateway 到投影、Saga 和快照完成更新的全过程。

## 高层管道

```mermaid
graph LR
    CMD["命令<br>到达"] --> GW["CommandGateway<br>验证 + 幂等性"]
    GW --> CB["CommandBus<br>路由"]
    CB --> AP["AggregateProcessor<br>执行"]
    AP --> ES["EventStore<br>持久化"]
    AP --> DEB["DomainEventBus<br>发布"]
    DEB --> PROJ["投影"]
    DEB --> SAGA["Saga"]
    DEB --> SNAP["快照"]
    ES --> WS["WaitPlan<br>通知"]



```

<!-- Sources:
  wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt
  wow-core/src/main/kotlin/me/ahoo/wow/command/CommandBus.kt
  wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/AggregateProcessor.kt
  wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/EventStore.kt
  wow-core/src/main/kotlin/me/ahoo/wow/event/DomainEventBus.kt
-->

## 阶段一：命令到达与 Gateway 处理

旅程始于客户端通过 `CommandGateway` 发送命令。这可以通过 WebFlux 端点或直接调用 Gateway 来完成。

```mermaid
sequenceDiagram
    autonumber
    actor Client as 客户端
    participant CG as DefaultCommandGateway
    participant Validator as Jakarta Validator
    participant Idempotency as IdempotencyChecker
    participant CB as CommandBus

    Client->>CG: sendAndWait(command, waitPlan)
    CG->>Idempotency: idempotencyCheck(command)
    Note right of Idempotency: 检查 requestId 唯一性。<br>若重复：DuplicateRequestIdException
    Idempotency-->>CG: 检查通过
    CG->>Validator: validate(commandBody)
    Note right of Validator: 自验证 (CommandValidator)<br>+ Jakarta Bean Validation
    Validator-->>CG: 验证通过
    CG->>CG: waitPlan.propagate(endpoint, header)
    CG->>CG: waitPlanRegistrar.register(waitPlan)
    CG->>CB: send(command)
    CB-->>CG: 已发送
    CG->>CG: waitPlan.next(sentSignal)
    CG-->>Client: Mono<CommandResult>


```

<!-- Sources:
  wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt:45
  wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt:62
  wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt:77
  wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt:99
  wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt:205
-->

### 验证

`DefaultCommandGateway` 执行两个级别的验证：

1. **自验证**：如果命令体实现了 `CommandValidator`，首先调用其 `validate()` 方法。[[wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt:62](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt#L62)]

2. **Bean 验证**：Jakarta `Validator` 检查所有约束注解（`@NotNull`、`@Size` 等）。[[wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt:66](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt#L66)]

### 幂等性检查

发送前，Gateway 检查命令的 `requestId` 是否已在该聚合根上处理过。`AggregateIdempotencyCheckerProvider` 提供每个聚合的检查器。如果检测到重复，抛出 `DuplicateRequestIdException`。[[wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt:77](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt#L77)]

### 等待计划注册

如果提供了等待计划，Gateway：

1. 将等待端点传播到命令消息头
2. 通过 `WaitCoordinator` 注册 `WaitHandle` 以进行信号路由
3. 在完成（成功、错误或取消）时设置清理

[[wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt:217](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt#L217)]

## 阶段二：命令分发

命令总线将命令路由到相应的 `AggregateProcessor`。`CommandDispatcher` 订阅命令总线并为每个聚合创建分发器：

```mermaid
sequenceDiagram
    autonumber
    participant CB as CommandBus
    participant CD as CommandDispatcher
    participant ACD as AggregateCommandDispatcher
    participant CH as CommandHandler<br>(过滤器链)
    participant AP as AggregateProcessor<br>(CommandAggregate)

    CB->>CD: receive(namedAggregates)
    CD->>CD: newAggregateDispatcher(namedAggregate)
    CD->>ACD: 分发消息
    ACD->>CH: filter(exchange)
    Note right of CH: 过滤器链执行：<br>1. AggregateProcessorFilter<br>2. SendDomainEventStreamFilter<br>3. ... 其他过滤器
    CH->>AP: process(exchange)
    AP-->>CH: DomainEventStream
    CH-->>ACD: 处理完成


```

<!-- Sources:
  wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/dispatcher/CommandDispatcher.kt
  wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/dispatcher/SendDomainEventStreamFilter.kt
-->

### CommandDispatcher

`CommandDispatcher` 为所有本地注册的聚合订阅 `CommandBus`。它为每个聚合类型创建 `AggregateCommandDispatcher`，确保同一聚合 ID 的命令通过 `AggregateScheduler` 顺序处理。[[wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/dispatcher/CommandDispatcher.kt:34](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/dispatcher/CommandDispatcher.kt#L34)]

### 过滤器链

命令处理器使用过滤器链模式。链中的两个关键过滤器：

1. **AggregateProcessorFilter** — 调用 `AggregateProcessor.process()` 方法
2. **SendDomainEventStreamFilter** — 将产生的 `DomainEventStream` 发布到 `DomainEventBus`

[[wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/dispatcher/SendDomainEventStreamFilter.kt:26](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/dispatcher/SendDomainEventStreamFilter.kt#L26)]

## 阶段三：聚合处理

这是写端的核心。`CommandAggregate` 处理命令并产生领域事件。

```mermaid
sequenceDiagram
    autonumber
    participant Exchange as ServerCommandExchange
    participant CA as SimpleCommandAggregate
    participant State as StateAggregate
    participant CF as CommandFunction
    participant ES as EventStore

    Exchange->>CA: process(exchange)
    CA->>CA: setFunction + setAggregateVersion
    CA->>CA: 检查 aggregateVersion vs state.version
    Note right of CA: CommandExpectVersionConflictException<br>版本不匹配时
    CA->>CA: 检查初始化状态
    Note right of CA: NotFoundResourceException<br>未初始化且非创建命令时
    CA->>CA: 检查 ownerId + spaceId
    CA->>CA: 检查已删除状态
    CA->>CF: invoke(exchange)
    Note right of CF: 执行领域特定的<br>命令处理方法
    CF-->>CA: DomainEventStream
    CA->>State: onSourcing(eventStream)
    Note right of State: 将事件应用到内存状态。<br>CommandState: STORED -) SOURCED
    State-->>CA: 状态已更新
    CA->>ES: append(eventStream)
    Note right of ES: 乐观并发检查。<br>冲突时抛出 EventVersionConflictException。
    ES-->>CA: 已持久化
    Note right of CA: CommandState: SOURCED -> STORED
    CA-->>Exchange: DomainEventStream


```

<!-- Sources:
  wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/SimpleCommandAggregate.kt:43
  wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/CommandAggregate.kt:41
  wow-core/src/main/kotlin/me/ahoo/wow/modeling/state/StateAggregate.kt:31
  wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/EventStore.kt:27
-->

### 预处理检查

`SimpleCommandAggregate` 在执行命令函数前执行多项验证检查：

1. **版本冲突检查** — 如果命令携带预期的 `aggregateVersion`，必须与当前状态版本匹配。[[wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/SimpleCommandAggregate.kt:92](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/SimpleCommandAggregate.kt#L92)]

2. **初始化检查** — 如果聚合根未初始化，非创建命令将被拒绝。[[wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/SimpleCommandAggregate.kt:99](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/SimpleCommandAggregate.kt#L99)]

3. **所有权检查** — 如果命令指定了 `ownerId`，必须与聚合根的所有者匹配。[[wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/SimpleCommandAggregate.kt:102](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/SimpleCommandAggregate.kt#L102)]

4. **删除检查** — 如果聚合根处于已删除状态，除 `RecoverAggregate` 外的命令将被拒绝。[[wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/SimpleCommandAggregate.kt:111](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/SimpleCommandAggregate.kt#L111)]

### CommandState 状态机

`CommandState` 枚举管理处理生命周期：

```mermaid
stateDiagram-v2
    [*] --> STORED : 聚合根已初始化
    STORED --> SOURCED : onSourcing(eventStream)<br>事件已应用到状态
    SOURCED --> STORED : onStore(eventStore, eventStream)<br>事件持久化成功
    SOURCED --> EXPIRED : 存储失败
    STORED --> EXPIRED : 处理过程中出错
    EXPIRED --> [*]

```

<!-- Sources:
  wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/CommandAggregate.kt:65
-->

[[wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/CommandAggregate.kt:65](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/CommandAggregate.kt#L65)]

### 状态上的 Event Sourcing

命令函数产生 `DomainEventStream` 后，事件通过 `onSourcing()` 应用到 `StateAggregate`。这在事件持久化之前更新内存状态。如果没有找到匹配的 Sourcing 方法，事件会被静默忽略（但版本号仍会更新）。[[wow-core/src/main/kotlin/me/ahoo/wow/modeling/state/StateAggregate.kt:31](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/state/StateAggregate.kt#L31)]

### 事件持久化

事件通过 `append()` 持久化到 `EventStore`。此操作是原子性的，强制执行：

- **版本排序** — 事件版本必须等于 `expectedNextVersion`（当前版本 + 1）
- **聚合 ID 唯一性** — 新聚合根的第一个事件必须使用唯一的聚合 ID
- **请求 ID 去重** — 防止同一命令产生两次事件

[[wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/EventStore.kt:38](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/EventStore.kt#L38)]

## 阶段四：事件发布

事件流持久化后，`SendDomainEventStreamFilter` 将其发布到 `DomainEventBus`：

```mermaid
sequenceDiagram
    autonumber
    participant Filter as SendDomainEventStreamFilter
    participant DEB as DomainEventBus
    participant Local as LocalDomainEventBus
    participant Dist as DistributedDomainEventBus

    Filter->>DEB: send(eventStream)
    alt 仅本地部署
        DEB->>Local: send(eventStream)
        Local-->>Local: 进程内投递
    else 分布式部署
        DEB->>Dist: send(eventStream)
        Dist->>Dist: 发布到 Kafka 主题
        Dist-->>Dist: 所有订阅者接收
    end


```

<!-- Sources:
  wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/dispatcher/SendDomainEventStreamFilter.kt:33
  wow-core/src/main/kotlin/me/ahoo/wow/event/DomainEventBus.kt:39
-->

`DomainEventBus` 接口支持两种拓扑：

- **LocalDomainEventBus** — 单实例部署的进程内事件投递
- **DistributedDomainEventBus** — 通过 Kafka 实现跨进程投递的分布式部署

[[wow-core/src/main/kotlin/me/ahoo/wow/event/DomainEventBus.kt:55](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/event/DomainEventBus.kt#L55)]

## 阶段五：事件分发到处理器

`DomainEventDispatcher` 从总线接收事件并分发给已注册的处理器。它使用**组合模式**将事件流分发与状态事件分发分离：

```mermaid
sequenceDiagram
    autonumber
    participant DEB as DomainEventBus
    participant SEB as StateEventBus
    participant CED as CompositeEventDispatcher
    participant ESD as EventStreamDispatcher
    participant SED as StateEventDispatcher
    participant PROJ as ProjectionHandler
    participant SAGA as StatelessSagaHandler
    participant SNAP as SnapshotStrategy

    CED->>CED: start()
    Note right of CED: 启动两个子分发器

    par 事件流路径
        DEB->>ESD: 接收事件流
        ESD->>ESD: 按 FunctionKind.EVENT 过滤
        ESD->>PROJ: handle(eventExchange)
        ESD->>SAGA: handle(eventExchange)
    and 状态事件路径
        SEB->>SED: 接收状态事件
        SED->>SED: 按 FunctionKind.STATE_EVENT 过滤
        SED->>SNAP: onEvent(stateEventExchange)
    end


```

<!-- Sources:
  wow-core/src/main/kotlin/me/ahoo/wow/event/dispatcher/DomainEventDispatcher.kt:44
  wow-core/src/main/kotlin/me/ahoo/wow/event/dispatcher/CompositeEventDispatcher.kt:64
  wow-core/src/main/kotlin/me/ahoo/wow/event/dispatcher/EventStreamDispatcher.kt:27
  wow-core/src/main/kotlin/me/ahoo/wow/event/dispatcher/StateEventDispatcher.kt:27
  wow-core/src/main/kotlin/me/ahoo/wow/projection/ProjectionHandler.kt:27
  wow-core/src/main/kotlin/me/ahoo/wow/saga/stateless/StatelessSagaHandler.kt:27
  wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/snapshot/SnapshotStrategy.kt:30
-->

### CompositeEventDispatcher

`CompositeEventDispatcher` 管理两个并行子分发器：

1. **EventStreamDispatcher** — 订阅 `DomainEventBus`，分发给具有 `FunctionKind.EVENT` 的处理器（投影和 Saga）
2. **StateEventDispatcher** — 订阅 `StateEventBus`，分发给具有 `FunctionKind.STATE_EVENT` 的处理器（快照策略）

两个子分发器都使用 `AggregateSchedulerSupplier` 确保每个聚合的排序保证。相同聚合 ID 的事件始终按顺序处理，即使跨越不同的处理器类型。[[wow-core/src/main/kotlin/me/ahoo/wow/event/dispatcher/CompositeEventDispatcher.kt:96](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/event/dispatcher/CompositeEventDispatcher.kt#L96)]

### 投影处理

投影接收领域事件并更新读模型。`DefaultProjectionHandler` 使用带有 `LogResumeErrorHandler` 的过滤器链实现容错 — 如果投影失败，错误会被记录，处理继续进行下一个事件。[[wow-core/src/main/kotlin/me/ahoo/wow/projection/ProjectionHandler.kt:36](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/projection/ProjectionHandler.kt#L36)]

### Saga 处理

无状态 Saga 接收领域事件并可以产生新命令。`DefaultStatelessSagaHandler` 也使用过滤器链模式。Saga 在不维护自身状态的情况下协调跨聚合边界的长时间运行业务流程。[[wow-core/src/main/kotlin/me/ahoo/wow/saga/stateless/StatelessSagaHandler.kt:36](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/saga/stateless/StatelessSagaHandler.kt#L36)]

### 快照创建

快照策略评估状态事件并在满足条件时创建快照：

- **SimpleSnapshotStrategy** — 每个事件后创建快照
- **VersionOffsetSnapshotStrategy** — 按可配置的版本间隔创建快照

[[wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/snapshot/SimpleSnapshotStrategy.kt:25](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/snapshot/SimpleSnapshotStrategy.kt#L25)]

## 阶段六：等待计划通知

命令处理完成后，已注册的等待 handle 在每个处理阶段接收信号：

```mermaid
sequenceDiagram
    autonumber
    participant CG as CommandGateway
    participant CB as CommandBus
    participant AP as AggregateProcessor
    participant DEB as DomainEventBus
    participant WC as WaitCoordinator
    participant WH as WaitHandle
    participant Client as 客户端

    CG->>WC: createLast/createStream(waitPlan)
    WC-->>CG: WaitLastHandle / WaitStreamHandle
    CG->>CB: send(command)
    CB-->>CG: 命令已接受
    CG->>WH: next(SENT 信号)
    WH-->>Client: stage=SENT

    CB->>AP: 处理命令
    AP-->>AP: 产生事件 + 持久化
    AP->>WC: signal(PROCESSED 信号)
    WC->>WH: next(PROCESSED 信号)
    WH-->>Client: stage=PROCESSED

    AP->>DEB: 发布事件
    DEB->>DEB: 分发到投影
    DEB->>WC: signal(PROJECTED 信号)
    WC->>WH: next(PROJECTED 信号)
    WH-->>Client: stage=PROJECTED

    DEB->>DEB: 分发到快照
    DEB->>WC: signal(SNAPSHOT 信号)
    WC->>WH: next(SNAPSHOT 信号)
    WH-->>Client: stage=SNAPSHOT

    WH->>WH: complete/error/cancel
    WH->>WC: 注销


```

<!-- Sources:
  wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt:217-280
  wow-core/src/main/kotlin/me/ahoo/wow/command/wait/WaitCoordinator.kt:18-72
  wow-core/src/main/kotlin/me/ahoo/wow/command/wait/WaitHandle.kt:22-223
-->

### 等待阶段

`WaitPlan` 支持在不同处理阶段等待：

| 阶段 | 含义 |
|-------|---------|
| `SENT` | 命令已被 `CommandBus` 接受 |
| `PROCESSED` | 命令已被聚合根执行，事件已持久化 |
| `PROJECTED` | 投影已处理事件 |
| `SNAPSHOT` | 快照已创建 |

`CommandWait` 工厂为每个阶段创建 `WaitPlan`：

- `CommandWait.sent(commandId)` — 等待命令发送
- `CommandWait.processed(commandId)` — 等待事件持久化
- `CommandWait.snapshot(commandId)` — 等待快照创建

[[wow-core/src/main/kotlin/me/ahoo/wow/command/CommandGateway.kt:145](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/command/CommandGateway.kt#L145)]

### 信号路由

当下游处理器（投影、Saga、快照）完成时，它通过 `CommandWaitNotifier` 发送 `WaitSignal`。`WaitCoordinator` 根据 `waitCommandId` 查找已注册的 `WaitHandle`，并将信号转发给 handle。handle 内部持有 `WaitState` 状态机：`StageWaitState` 规约单阶段等待，`ChainWaitState` 跟踪 Saga 链 tail，并在主链信号确认 tail 命令 ID 后回放暂存的 tail 信号。[[WaitCoordinator.kt:62](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/command/wait/WaitCoordinator.kt#L62)] [[WaitState.kt:56](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/command/wait/WaitState.kt#L56)] [[ChainWaitState.kt:143](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/command/wait/chain/ChainWaitState.kt#L143)]

## 聚合加载（读路径）

当需要为新命令加载聚合根时，框架重建其状态：

```mermaid
sequenceDiagram
    autonumber
    participant Factory as AggregateProcessorFactory
    participant SR as SnapshotStore
    participant ES as EventStore
    participant SA as StateAggregate

    Factory->>SR: load(aggregateId)
    alt 快照存在
        SR-->>Factory: 带状态和版本的快照
        Factory->>SA: 从快照初始化
    else 无快照
        Factory->>SA: 初始化为空（version=0）
    end

    Factory->>ES: load(aggregateId, snapshotVersion + 1)
    ES-->>Factory: 剩余事件流

    loop 对每个事件流
        Factory->>SA: onSourcing(eventStream)
        Note right of SA: 重放事件以重建状态
    end

    Factory-->>Factory: CommandAggregate 就绪


```

<!-- Sources:
  wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/EventStoreStateAggregateRepository.kt
  wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/snapshot/SnapshotStore.kt:27
  wow-core/src/main/kotlin/me/ahoo/wow/modeling/state/StateAggregate.kt:31
-->

加载过程：

1. **加载快照** — 如果聚合根存在快照，从该状态和版本开始
2. **加载剩余事件** — 从 `EventStore` 获取快照版本之后的所有事件
3. **重放事件** — 通过 `onSourcing()` 将每个事件流应用到 `StateAggregate`

`EventStore.load()` 方法支持按版本范围或时间范围加载，默认从版本 1 加载所有事件。[[wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/EventStore.kt:54](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/eventsourcing/EventStore.kt#L54)]

## 错误处理

数据流在每个阶段都包含错误处理：

```mermaid
graph TB
    subgraph Errors["错误处理"]
        direction TB
        E1["DuplicateRequestIdException<br>幂等性检查失败"]
        E2["ConstraintViolationException<br>验证失败"]
        E3["CommandExpectVersionConflictException<br>乐观并发冲突"]
        E4["NotFoundResourceException<br>聚合根未初始化"]
        E5["IllegalAccessOwnerAggregateException<br>所有权不匹配"]
        E6["IllegalAccessDeletedAggregateException<br>聚合根已删除"]
        E7["EventVersionConflictException<br>Event Store 版本冲突"]
        E8["CommandResultException<br>包装的处理错误"]
    end

    subgraph Handlers["错误恢复"]
        ER1["错误函数注册表<br>每个命令类型的错误处理器"]
        ER2["LogResumeErrorHandler<br>投影/Saga 记录日志并继续"]
        ER3["RetryableAggregateProcessor<br>版本冲突时自动重试"]
    end

    E1 --> E8
    E2 --> E8
    E3 --> ER3
    E7 --> ER3
    E4 --> ER1
    E5 --> ER1
    E6 --> ER1
    ER1 --> E8



```

<!-- Sources:
  wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/SimpleCommandAggregate.kt:150
  wow-core/src/main/kotlin/me/ahoo/wow/projection/ProjectionHandler.kt:36
  wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/RetryableAggregateProcessor.kt
-->

### 错误函数

`SimpleCommandAggregate` 支持每个命令类型的错误函数。如果为命令类型注册了错误函数，处理失败时会调用该函数，允许聚合根产生补偿事件。[[wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/SimpleCommandAggregate.kt:150](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/modeling/command/SimpleCommandAggregate.kt#L150)]

### 投影/Saga 错误恢复

投影和 Saga 使用 `LogResumeErrorHandler` — 错误被记录但处理继续进行下一个事件。这确保失败的投影不会阻塞其他处理器。

## 相关页面

- [架构概览](./overview) — 分层架构和 CQRS 模式
- [模块依赖](./module-dependencies) — 详细的模块依赖图
