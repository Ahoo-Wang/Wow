---
title: 运行时编排迁移
description: 把自定义 dispatcher、message source 与 Spring 所有权迁移到一次性 WowRuntime。
---

# 运行时编排迁移

这是从独立生命周期 owner 迁移到单一 `WowRuntime` 的源码/运行时迁移。它不改变 event、snapshot 或
message wire format，因此本身无需重写数据；如果同一发布还修改存储布局，必须单独建立存储/数据门禁。

稳定目标模型见 [运行时生命周期](../advanced/runtime-lifecycle.md)。

## 迁移总览

| 旧契约 | 当前契约 | 迁移结果 |
|---|---|---|
| `Lifecycle`/独立 `MessageDispatcherLauncher` | 一个 `WowRuntime` 拥有 `RuntimeComponent` graph | 删除竞争的 start/stop owner |
| constructor 或 Spring destruction 获取/释放运行资源 | construction inert；在 `prepare`/`start` 获取，只由 runtime 释放 | 删除 `@PostConstruct`、`@PreDestroy`、`DisposableBean` 与推断的 `close()` owner |
| subscription/demand 表示 ready | `MessageReceiver.messages`、hot replayable `readiness`、显式开关 processing | 保留 readiness 与 admission callback |
| 每组件 timeout | 一个 runtime `shutdownTimeout` 与 `shutdownQuietPeriod` | 为完整 graph 规划同一 deadline |
| 重启同一 object/context | one-shot runtime | 终止后创建新 runtime/ApplicationContext |

启动时所有组件先完成 prepare，之后才允许任何组件打开 processing。停机时先关闭全局 activity admission，
再关闭组件 intake，等待稳定 quiet period，并在一个 deadline 内反向停止已 prepare 组件。启动失败会反向
回滚已 prepare 组件；runtime fatal failure 进入同一全局停机路径。

## 1. 替换生命周期所有权

删除应用自定义 dispatcher launcher，以及所有直接 `dispatcher.start()`、`stop()`、`close()` 调用。
自定义 dispatcher subclass 必须重新编译，因为 lifecycle 已成为 runtime template。额外受管资源放入独立
`RuntimeComponent`，不要覆盖框架 lifecycle。

### 非 Spring 应用

使用有序 component list 构造唯一 runtime。`start()` 是 cold publisher，必须订阅：

```kotlin
val runtime = WowRuntime(
    components = listOf(commandDispatcher, eventDispatcher, customComponent),
    shutdownTimeout = Duration.ofSeconds(60),
    shutdownQuietPeriod = Duration.ofSeconds(1),
)

runtime.start().block()
try {
    runApplication()
} finally {
    runtime.stopGracefully().block()
}
```

这里只在 process/bootstrap 边界阻塞；core handler 与 dispatch path 仍保持响应式。

### Spring Boot 应用

starter 创建 canonical `wowRuntime` 与 `wowRuntimeLifecycle`。把参与者声明为当前 context 的 singleton
`RuntimeComponent` Bean；顺序有要求时使用 `@Order`。默认 runtime 只发现当前 ApplicationContext 拥有的
component，parent/child context 保持不同 ownership scope。

自定义 runtime 必须是当前 context 唯一的 `WowRuntime`，名称必须为 `wowRuntime`，必须直接声明而非通过
`FactoryBean`，并关闭 Spring 推断的 close owner：

```kotlin
@Bean(WOW_RUNTIME_BEAN_NAME, destroyMethod = "")
fun customWowRuntime(): WowRuntime = WowRuntime(
    components = components,
    shutdownTimeout = Duration.ofSeconds(60),
    shutdownQuietPeriod = Duration.ofSeconds(1),
)
```

提供自定义 canonical runtime 后，其 component topology 即为权威，starter discovery 不再追加 component。
不要声明第二个 `WowRuntimeLifecycle`，也不要让受管 component 独立实现 Spring `Lifecycle`/`SmartLifecycle`、
`DisposableBean`、`@PreDestroy` 或显式 destroy method。starter 会校验 singleton 与竞争 owner，不接受模糊
destruction。

自定义 ingress `SmartLifecycle` 的 phase 应大于 `WOW_RUNTIME_PHASE`，从而在 runtime ready 后启动，并在
Wow 之前停止。starter 使用选定 runtime deadline 加少量完成余量配置该 shutdown phase timeout。

## 2. 迁移自定义运行时参与者

以 inert construction 实现完整契约：

```kotlin
class PartnerFeed : RuntimeComponent {
    private lateinit var runtimeContext: RuntimeContext

    override fun prepare(runtimeContext: RuntimeContext): Mono<Void> = Mono.fromRunnable {
        this.runtimeContext = runtimeContext
        prepareSourceWithoutOpeningProcessing()
    }

    override fun start() = openIntake()
    override fun quiesce() = closeIntake()
    override fun stopGracefully(): Mono<Void> = drainAndClose()
    override fun forceStop() = disposePromptly()
}
```

契约检查：

- `prepare` 只有在新工作可无损保留且 processing 仍关闭时才完成；
- `start` 必须快速，并在全局 readiness barrier 后打开 processing；
- `quiesce` 快速、幂等地关闭 intake；
- `stopGracefully` 排空已接受工作并释放资源；
- `forceStop` 非阻塞、幂等、可重复，并且在 `prepare` 前调用也安全；
- constructor 不打开 runtime 拥有的 thread、subscription、socket 或 scheduler。

接受一个完整异步操作前调用 `runtimeContext.tryAcquire()`；返回 `null` 表示全局 admission 已关闭。
只有包括嵌套异步 side effect 在内的完整链终止后，才关闭返回的 `RuntimeActivity`。会使整个 runtime 停止
的 terminal pipeline failure 应调用 `runtimeContext.reportFailure(error)`。

取消 startup subscription 会 force-stop 该 one-shot runtime，因此 prepare publisher 必须快速响应取消/
force cleanup。

## 3. 迁移消息源

自定义异步 transport 必须返回并保留 `MessageReceiver` 的四个部分：

```kotlin
override fun receiver(subscription: MessageSubscription): MessageReceiver<Exchange> = MessageReceiver(
    messages = singleUseMessages,
    readiness = hotReplayableReadiness,
    processingAdmission = ::openConsumption,
    processingQuiescence = ::closeConsumption,
)
```

runtime 先订阅 `messages`，再等待 `readiness`；所有 component ready 后调用 `openProcessing`，停机时在物理
取消前调用 `closeProcessing`。`mapMessages` 会保留这些 callback。一个 receiver 只允许一个 message
subscriber。

只有 `WowRuntime` 拥有的 dispatcher 才使用 `runtimeReceiver()`；普通自定义 consumer 应使用
`receiver()`，除非它实现同一 local admission receipt protocol。`LocalMessageBus.sendIfSubscribed()` 的保守
默认值是 `false`。subscriber count 或 sink acceptance 都不能证明每个目标 receiver 已获取 processing
admission。

Transport 检查：

- Redis readiness 必须创建所需 consumer group，但在 admission 打开前不处理消息；
- Kafka readiness 只有建立保守 assignment boundary 后才完成；runtime 启动前先创建 topic；
- tracing/metrics wrapper 必须原样委派 `runtimeReceiver()`，不能退回 `receiver()`。

## 4. 更新相邻扩展

- 自定义 `AggregateSchedulerSupplier` 必须同时支持 graceful 与 force shutdown；force cleanup 同步释放 graceful
  path 可能拥有的全部 scheduler。
- `AutoRegistrar` 是初始化工作（`SmartInitializingSingleton`），不是 runtime lifecycle owner。删除对旧
  launcher phase 的调用或排序依赖。
- 自定义 store/message-bus decorator 必须保留原 delegate close ownership，避免重复 close。
- 过去单独实例化 launcher 的测试，应改为启动一个 `WowRuntime` 并断言完整 component 顺序与共享
  termination result。

## 5. 检查停机配置

| 属性 | 默认值 | 约束 |
|---|---|---|
| `wow.shutdown-timeout` | `60s` | 正数；完整 runtime 共用一个 deadline |
| `wow.shutdown-quiet-period` | `1s` | 非负且严格小于 timeout |

两个 Duration 都必须能表示为 signed 64-bit nanoseconds。`stop(timeout)` 只改变调用方阻塞等待，不会替换
runtime 配置的 shutdown deadline。

使用接近生产的数值测试：

1. 所有 component prepare 完成后才出现任何 `start`；
2. 启动失败/取消按反向顺序回滚；
3. admission 关闭后拒绝新 activity，已接收工作继续 drain；
4. quiet-period 内出现 activity 会重新开始 stable-quiet 等待；
5. graceful stop 按 component 反向顺序执行；
6. deadline、quiesce failure、stop failure 与显式 force stop 都只释放一次资源；
7. 首个 terminal failure 可观测，Starter 在意外终止时关闭 Spring context。

## 部署与回滚

把生命周期迁移作为单一 topology 发布：禁止旧 launcher 与 canonical runtime 混用。切换前运行 module/
application-context tests、真实 startup/readiness、message-flow smoke test、graceful shutdown 以及 fatal/
deadline 演练。

发布期间验证精确 deployed revision、ingress 打开前 readiness、没有重复 consumer、receiver lag、termination
log/trace，以及 fatal failure 后 process exit。本地测试只能证明实现，不能证明生产准入。

回滚时完整停止新 ApplicationContext，并同时部署旧 binary 与旧 launcher configuration。禁止重启已终止
runtime，也不要在同一 context 中恢复旧 launcher。该迁移本身不改变 wire/storage format，因此无需数据
转换；但如果同一发布的其他变更产生了新格式写入，必须按对应数据迁移计划回滚。

## 源码参考

| 源码 | 契约 |
|---|---|
| [`WowRuntime.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/runtime/WowRuntime.kt) | readiness barrier、one-shot state、共享 deadline、terminal failure |
| [`RuntimeComponent.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/runtime/RuntimeComponent.kt) | 参与者生命周期 |
| [`RuntimeContext.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/runtime/RuntimeContext.kt) | activity admission 与 failure reporting |
| [`MessageReceiver.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/messaging/MessageReceiver.kt) | readiness 与 processing admission |
| [`WowRuntimeLifecycle.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring/src/main/kotlin/me/ahoo/wow/spring/WowRuntimeLifecycle.kt) | Spring bridge |
| [`WowAutoConfiguration.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/WowAutoConfiguration.kt) | composition 与 exclusive-owner 校验 |

## 相关页面

| 页面 | 关系 |
|---|---|
| [迁移指南](../migration.md) | 迁移范围与证据门禁 |
| [运行时生命周期](../advanced/runtime-lifecycle.md) | 稳定目标行为 |
| [配置](../configuration.md) | 运行设置与环境边界 |
| [Spring Boot Starter](../extensions/spring-boot-starter.md) | Starter 集成 |

<!-- Sources: current runtime implementation/tests, v6.21.5 launcher sources, Spring auto-configuration/tests,
MessageBus/MessageReceiver and Kafka/Redis receivers -->
