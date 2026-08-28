---
title: Event Processor
description: Run side effects for committed events while making function matching, reactive completion, idempotency, ordering, replay, and failure boundaries explicit.
outline: deep
---

# Event Processor

An event processor runs ordinary application side effects after a domain event has been appended. It does not extend the source aggregate transaction: a processing failure cannot remove the event, and a successful side effect does not become authoritative event history.

## When to Use an Ordinary Event Processor

Use a Processor to send notifications, write audit records, call external services, update integration state, or invalidate caches. Use a [Saga](../event/saga.md) when an event must generate follow-up commands for other aggregates.

The ordinary event processor owns its side-effect contract: completion, idempotency key, permitted retries, ordering requirements, and failure recovery must all be explicit at this boundary.

## Define Event Functions

`@EventProcessor` is also a Spring component marker. The framework registers both:

- methods named by convention as `onEvent` / `onStateEvent`;
- methods with other names explicitly annotated with `@OnEvent` / `@OnStateEvent`.

```kotlin
@EventProcessor
class OrderNotificationProcessor(
    private val notificationPort: NotificationPort,
) {
    @OnEvent
    fun onOrderCreated(event: DomainEvent<OrderCreated>): Mono<Void> =
        notificationPort.sendCreated(
            operationId = event.id,
            order = event.body,
        ).then()
}
```

The first parameter may be the event body, `DomainEvent<T>`, or `DomainEventExchange<T>`. Later parameters are injected from the exchange or service provider. Use the full message or exchange only when message metadata is required.

## Domain Events and State Events

`@OnEvent` consumes event facts from the domain-event bus. `@OnStateEvent` consumes a state event containing the event stream and the aggregate's latest state. Its first parameter is still the concrete domain event, and the state can be injected as a later parameter:

```kotlin
@EventProcessor
class OrderStateNotifier {
    @OnStateEvent
    fun onCreated(event: OrderCreated, state: OrderState): Mono<Void> =
        notifyCurrentStatus(event.orderId, state.status)
}
```

Use `@OnEvent` when event facts are sufficient. Use `@OnStateEvent` only when the aggregate state carried by that state event is required. Do not switch an ordinary processor to a state-event function merely to read query data.

## Filtering and Function Matching

The registrar first selects functions by event-body type and aggregate topic. If `@OnEvent` or `@OnStateEvent` does not declare aggregate names, topics are resolved from the event body's model metadata. Declare the source explicitly when it must be restricted:

```kotlin
@OnEvent("order")
fun onOrderCreated(event: OrderCreated): Mono<Void> = handle(event)
```

The Dispatcher finds every matching function for each event in the stream. A type or topic mismatch is not invoked; compensation replay also restricts dispatch to the target function in the failure record. When waiting for `EVENT_HANDLED`, specify `contextName`, `processorName`, and `functionName` as narrowly as possible so another function at the same stage cannot satisfy the wait early.

## Reactive Side Effects

The return value is the real side-effect completion boundary. The framework adapts synchronous functions, suspending functions, `Mono`, `Flux`, Reactive Streams `Publisher`, and Kotlin `Flow` into one reactive invocation chain.

```kotlin
@OnEvent
fun onOrderCreated(event: DomainEvent<OrderCreated>): Mono<Void> =
    integrationRepository.upsert(
        operationId = event.id,
        value = mapOrder(event.body),
    ).then()
```

Do not call `block()` or start a detached `subscribe()` inside the function. The Dispatcher can observe completion, errors, and retries only through the returned publisher. If an API is necessarily blocking, use `@Blocking` so the existing accessor moves the call to a scheduler that permits blocking.

## Idempotency, Ordering, and Replay

Event processing does not provide an exactly-once side effect. Immediate retry, durable compensation replay, or an operational resend can invoke the same function again.

- Use `event.id`, the event-stream ID, or a stable aggregate-ID-plus-version key at the target system. An in-process “seen” set is not durable idempotency.
- Events in one received stream are handled in order with `concatMap`.
- Multiple functions matching the same event execute through `flatMap`; do not depend on function order.
- Do not infer global order across aggregates, instances, or external systems. If a target requires order, persist the source aggregate and version and handle gaps explicitly.
- Replay must preserve the original event identity and established idempotency rule; invoking the function again is not a new business fact.

## Failures, Retry, and the Compensation Entry Point

Propagate failures through the returned publisher. Swallowing an error makes the Dispatcher observe success; throwing cannot roll back the already appended source event.

The current runtime `RetryableFilter` performs bounded immediate retry only for errors marked `RECOVERABLE`, with a default of up to 3 retries and a 2-second minimum backoff. This retry exists only in the current processing call and does not recover work after a process crash.

Failures that must eventually be handled belong in Compensation. When that module is enabled, a still-failing event function can produce a durable failure record. `@Retry` supplies classification and retry specifications, while `@Retry(enabled = false)` opts that function out of durable recording. See [Event Compensation](./compensation.md) for the complete lifecycle; this page does not duplicate its state machine, configuration, Dashboard, or deployment.

## Testing and Completion Signal

Start with a reactive unit test of the function and verify both the effect and its idempotency key:

```kotlin
@Test
fun `uses event id as operation id`() {
    val event = orderCreatedDomainEvent(id = "event-1")

    StepVerifier.create(processor.onOrderCreated(event))
        .verifyComplete()

    verify { notificationPort.sendCreated("event-1", event.body) }
}
```

Add metadata/Dispatcher or integration coverage only when relying on topics, state injection, function selection, retry, or real external persistence.

**Completion signal:** success and failure publishers are observable; repeated input does not duplicate the business effect; ordering assumptions are verified; and completing the matching function produces `EVENT_HANDLED`. That stage proves only this Processor function completed, not that another downstream branch or external system is finally consistent. See [Completion Semantics](../command/completion.md).
