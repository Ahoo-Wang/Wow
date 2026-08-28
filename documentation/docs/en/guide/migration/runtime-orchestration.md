---
title: Runtime Orchestration Migration
description: Move custom dispatchers, message sources, and Spring ownership to the one-shot WowRuntime.
---

# Runtime Orchestration Migration

This is a source/runtime migration from independent lifecycle owners to one `WowRuntime`. It does not change the
event, snapshot, or message wire formats, so it requires no data rewrite by itself. If the same release also changes a
storage layout, treat that as a separate storage/data gate.

The stable target model is described in [Runtime Lifecycle](../advanced/runtime-lifecycle.md).

## Migration at a Glance

| Previous contract | Current contract | Migration consequence |
|---|---|---|
| `Lifecycle`/independent `MessageDispatcherLauncher` | `RuntimeComponent` graph owned by one `WowRuntime` | Remove competing start/stop owners |
| Constructor or Spring destruction acquires/releases runtime resources | Inert construction; acquire in `prepare`/`start`, release through runtime | Remove `@PostConstruct`, `@PreDestroy`, `DisposableBean`, inferred `close()` ownership |
| Subscription/demand implies ready | `MessageReceiver.messages`, hot replayable `readiness`, explicit open/close processing | Preserve readiness and admission callbacks |
| Per-component timeout | One runtime `shutdownTimeout` and `shutdownQuietPeriod` | Size one shared deadline for the complete graph |
| Restart the same object/context | One-shot runtime | Create a new runtime/ApplicationContext after termination |

Startup prepares every component before any component opens processing. Normal shutdown first enters a quiet window
while global activity admission remains open; admitted tail activity resets and can extend that window. After one
complete idle quiet period, the runtime closes global admission, quiesces component intake in registration order,
waits for admitted work to drain, and stops prepared components in reverse order under one deadline. Startup failure
rolls back prepared components in reverse order; fatal runtime failure closes admission immediately, skips the quiet
window, and enters the complete-runtime cleanup path.

## 1. Replace Lifecycle Ownership

Delete application-defined dispatcher launchers and every direct `dispatcher.start()`, `stop()`, or `close()` call.
Recompile custom dispatcher subclasses because dispatcher lifecycle is now the runtime template. Put additional owned
resources in a separate `RuntimeComponent` rather than overriding framework lifecycle.

### Non-Spring applications

Construct exactly one runtime from the ordered component list. `start()` is cold and must be subscribed:

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

Blocking is appropriate here only at the process/bootstrap boundary. Core handlers and dispatch paths remain reactive.

### Spring Boot applications

The starter creates the canonical `wowRuntime` and `wowRuntimeLifecycle`. Declare runtime participants as local
singleton `RuntimeComponent` beans and use `@Order` when ordering matters. The default runtime discovers only
components owned by the current ApplicationContext; parent/child contexts remain separate ownership scopes.

A custom runtime must be the only local `WowRuntime`, be named `wowRuntime`, be declared directly rather than through
a `FactoryBean`, and suppress Spring's inferred close owner:

```kotlin
@Bean(WOW_RUNTIME_BEAN_NAME, destroyMethod = "")
fun customWowRuntime(): WowRuntime = WowRuntime(
    components = components,
    shutdownTimeout = Duration.ofSeconds(60),
    shutdownQuietPeriod = Duration.ofSeconds(1),
)
```

When a custom canonical runtime is supplied, its component topology is authoritative; starter discovery does not add
more components. Do not declare another `WowRuntimeLifecycle` or make runtime components independently implement
Spring `Lifecycle`/`SmartLifecycle`, `DisposableBean`, `@PreDestroy`, or an explicit destroy method. The starter checks
singleton and competing-owner invariants and fails startup rather than accepting ambiguous destruction.

A custom ingress `SmartLifecycle` should have a phase greater than `WOW_RUNTIME_PHASE`, so it starts after runtime
readiness and stops before Wow. The starter configures the runtime phase timeout from the selected runtime deadline
plus its small completion margin.

## 2. Migrate Custom Runtime Participants

Implement the full contract with inert construction:

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

Contract checklist:

- `prepare` completes only when admitted work can be retained without loss, while processing is still closed;
- `start` is prompt and opens processing after the global readiness barrier;
- `quiesce` promptly and idempotently closes intake;
- `stopGracefully` drains already accepted work and releases resources;
- `forceStop` is non-blocking, idempotent, repeat-safe, and safe even before `prepare`;
- construction does not open threads, subscriptions, sockets, or schedulers owned by the runtime.

Before accepting one complete asynchronous operation, call `runtimeContext.tryAcquire()`. A `null` result means global
admission is closed. Close the returned `RuntimeActivity` only after the complete chain terminates, including nested
asynchronous side effects. Call `runtimeContext.reportFailure(error)` for a terminal pipeline failure that should stop
the whole runtime.

Cancelling the startup subscription force-stops the one-shot runtime. Preparation publishers must respond to
cancellation/force cleanup promptly.

## 3. Migrate Message Sources

Custom asynchronous transports must return a `MessageReceiver` with four preserved pieces:

```kotlin
override fun receiver(subscription: MessageSubscription): MessageReceiver<Exchange> = MessageReceiver(
    messages = singleUseMessages,
    readiness = hotReplayableReadiness,
    processingAdmission = ::openConsumption,
    processingQuiescence = ::closeConsumption,
)
```

The runtime subscribes to `messages` first, then waits for `readiness`; after every component is ready, it calls
`openProcessing`. On shutdown it calls `closeProcessing` before physical cancellation. `mapMessages` preserves these
callbacks. A receiver supports exactly one message subscriber.

Use `runtimeReceiver()` only for a dispatcher owned by `WowRuntime`; ordinary custom consumers should use
`receiver()` unless they implement the same local admission receipt protocol. The conservative default
`LocalMessageBus.sendIfSubscribed()` is `false`. Subscriber count or sink acceptance alone cannot prove that every
targeted receiver acquired processing admission.

Transport checks:

- Redis readiness must create the required consumer groups without processing messages before admission opens;
- Kafka readiness must complete only after its conservative assignment boundary is established; provision topics
  before runtime startup;
- wrappers for tracing or metrics must delegate `runtimeReceiver()` unchanged, not fall back to `receiver()`.

## 4. Update Adjacent Extensions

- Custom `AggregateSchedulerSupplier` implementations must support both graceful and force shutdown; force cleanup
  synchronously disposes every scheduler the graceful path could own.
- `AutoRegistrar` is initialization work (`SmartInitializingSingleton`), not a runtime lifecycle owner. Remove calls or
  ordering based on the deleted launcher phase.
- Custom store/message-bus decorators must preserve original delegate close ownership and avoid closing it twice.
- Tests that instantiated individual launchers should instead start one `WowRuntime` and assert the complete component
  order and shared termination result.

## 5. Review Shutdown Configuration

| Property | Default | Constraint |
|---|---|---|
| `wow.shutdown-timeout` | `60s` | Positive; one deadline for the complete runtime |
| `wow.shutdown-quiet-period` | `1s` | Non-negative and strictly shorter than the timeout |

Both durations must fit signed 64-bit nanoseconds. `stop(timeout)` changes only that caller's blocking wait; it does
not replace the runtime's configured shutdown deadline.

Test these cases with production-like values:

1. all components prepare before any `start`;
2. startup failure/cancellation rolls back in reverse order;
3. normal shutdown continues to admit tail activity during the quiet window;
4. tail activity resets the quiet timer; stable idle closes admission before component `quiesce`;
5. component `quiesce` runs in registration order, then graceful stop runs in reverse order;
6. deadline, quiesce failure, stop failure, and explicit force stop release resources once;
7. the first terminal failure remains observable and the Spring context closes on unexpected termination.

## Deployment and Rollback

Deploy the lifecycle migration as one topology: do not mix old launchers with the canonical runtime. Before cutover,
run module/application-context tests, a real startup/readiness check, message-flow smoke tests, graceful shutdown, and
fatal/deadline drills.

During rollout verify the exact deployed revision, readiness before ingress opens, absence of duplicate consumers,
receiver lag, termination logs/traces, and process exit on fatal failure. Local tests prove the implementation only;
they do not prove production admission.

Rollback by completely stopping the new ApplicationContext and deploying the previous binary together with its
previous launcher configuration. Never attempt to restart a terminated runtime or restore old launchers inside the
same context. Because this migration alone changes no wire/storage format, rollback needs no data conversion unless
another change in the same release created new-format writes.

## References

| Source | Contract |
|---|---|
| [`WowRuntime.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/runtime/WowRuntime.kt) | Readiness barrier, one-shot state, shared deadline, terminal failure |
| [`RuntimeComponent.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/runtime/RuntimeComponent.kt) | Participant lifecycle |
| [`RuntimeContext.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/runtime/RuntimeContext.kt) | Activity admission and failure reporting |
| [`MessageReceiver.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-core/src/main/kotlin/me/ahoo/wow/messaging/MessageReceiver.kt) | Readiness and processing admission |
| [`WowRuntimeLifecycle.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring/src/main/kotlin/me/ahoo/wow/spring/WowRuntimeLifecycle.kt) | Spring bridge |
| [`WowAutoConfiguration.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/WowAutoConfiguration.kt) | Composition and exclusive-owner validation |

## Related Pages

| Page | Relationship |
|---|---|
| [Migration Guide](../migration.md) | Migration scopes and evidence gates |
| [Runtime Lifecycle](../advanced/runtime-lifecycle.md) | Stable target behavior |
| [Configuration](../configuration.md) | Runtime settings and environment boundaries |
| [Spring Boot Starter](../extensions/spring-boot-starter.md) | Starter integration |

<!-- Sources: current runtime implementation/tests, v6.21.5 launcher sources, Spring auto-configuration/tests,
MessageBus/MessageReceiver and Kafka/Redis receivers -->
