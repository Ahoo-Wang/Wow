---
title: Failures and Retries
description: What happens when a handler fails, how to give it its own retry budget and timeout, how failures are classified, how a command is retried at each layer, and how parked aggregates are retried and resumed.
outline: deep
---

# Failures and Retries

A saga, a projection or a reactor will sometimes fail: the mail service is down, a row violates a constraint, a bug meets a value nobody expected. Wow neither skips the event nor stops the subscription. It retries the handler, and when that does not help, it parks the one aggregate the handler failed on, while the subscription goes on with every other aggregate. This page shows how to tune that for a handler. [Subscriptions, Failures and Replay](../concepts/subscriptions#failures-park-one-aggregate) explains the model.

## What happens when a handler throws

1. **Retry.** The handler is called again within its retry budget: 5 attempts in all, waiting 100 ms after the first failure and twice as long after each next one, up to 5 s.
2. **Park.** When the budget is spent, the subscription records in the failure ledger the subscription, the aggregate, the version it stopped at and the error. The aggregate's later events wait; every other aggregate goes on.
3. **Retry later.** The parked aggregate is retried over a longer horizon: 10 times, the first after 3 minutes, each attempt allowed 2 minutes.
4. **Resume or skip.** When those retries are spent too, the aggregate waits for an operator, who resumes it, reading its stream again from the version it stopped at, or skips it, moving past the events it held back.

A rejection of a command a saga derived is not a failure: it is never retried and goes to the saga's `@OnRejected` handler ([Write a Saga](./sagas#handle-a-rejection)).

## Give a handler its own budget

A handler that calls a slow or flaky system needs a budget of its own. `@Retry` on a processor class applies to all its handlers; on a handler, it overrides its processor's, setting by setting:

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/ShipmentNotifier.kt#notifier -->

```kotlin
/**
 * Tells the buyer of a shipped order that it is on its way. The notification service is a remote call that may be
 * slow or briefly down, so the reactor gives it its own budget: five attempts, from one second apart up to thirty, and
 * ten seconds for each, before the order is parked for the handler in the failure ledger. A buyer the service does not
 * know is a failure no retry can fix, so it parks the order at once.
 *
 * A reactor starts at the end of the log: deploying it does not notify the buyers of the orders shipped before.
 */
@Reactor
@Retry(
    maxAttempts = 5,
    initialBackoff = "1s",
    maxBackoff = "30s",
    timeout = "10s",
    unrecoverable = [UnknownBuyerException::class],
)
class ShipmentNotifier(private val notifications: BuyerNotifications) {
    @OnEvent
    suspend fun onOrderShipped(event: StateTypedEvent<OrderState, OrderShipped>, context: ReactContext) {
        val buyer = event.scope.ownerId ?: return
        notifications.orderShipped(buyer, event.ref.id, key = context.invocationKey)
    }
}
```

| Setting | Means |
| --- | --- |
| `maxAttempts` | The calls in all, at least 1 |
| `initialBackoff` | The wait after the first failure; each next wait is twice as long |
| `maxBackoff` | The longest wait between attempts |
| `timeout` | How long one attempt may run: a longer one is cancelled and counts as failed |
| `unrecoverable` | Exception classes that park the aggregate at once, without a retry |
| `recoverable` | Exception classes that are retried within the budget, even where the subscription would take them for an outage |

Durations are written as Kotlin durations (`500ms`, `2s`, `1m 30s`) or ISO-8601 (`PT2S`). A setting left out comes from the processor's `@Retry`, else from the host's `wow.subscription.retry.*`.

A `@Blocking` handler runs on a virtual thread that a timeout cannot interrupt: its attempt counts as failed when the call returns.

The host overrides the annotation per subscription, setting by setting, without a new build:

```yaml
wow:
  subscription:
    retry:
      max-attempts: 5
  subscriptions:
    "[ShipmentNotifier.onOrderShipped]":
      retry:
        max-attempts: 8
        timeout: 20s
```

## How a failure is classified

Not every failure is the aggregate's own, so each one is classified before it is counted:

| Class | Examples | What happens |
| --- | --- | --- |
| Unrecoverable | A class in the handler's `unrecoverable` | The aggregate is parked at once |
| Recoverable | Most exceptions | Retried within the budget, then the aggregate is parked |
| Systemic | A store or a connection that is down; 3 aggregates of a partition failing within 10 seconds | The whole partition pauses and retries until it is back; no aggregate spends its budget or is parked |
| Backpressure | A command refused as busy (`wow.command.busy`), a wait that ran out, a query store's bulkhead, a 429 with `Retry-After` | The entry is retried with backoff and jitter, and the partition's concurrency is lowered until handling succeeds again; the aggregate is parked only when that budget is spent |

The handler's own `recoverable` and `unrecoverable` lists come first. When a class is in both, the nearer one in its class hierarchy decides. An application classifies its own exceptions for every handler with a `FailureClassifier` bean, which returns a class for the failures it knows and null for the rest. A failure nothing classifies is recoverable.

The handler above marks `UnknownBuyerException` unrecoverable: the notification service does not know the buyer, and asking again will not change that, so the order is parked at once for someone to look at.

## Parked aggregates

The failure ledger lives in the service's own store, beside the checkpoints. The long-horizon retries are set by `wow.subscription.parked.*`, or per subscription by `wow.subscriptions.[<processor.function>].parked.*`.

Operators see the parked aggregates of every service in the ops console, with the error and the version each stopped at, and resume or skip them there: [The Ops Service and Console](../operate/ops-console). A webhook can tell a team when an aggregate parks or spends its retries.

Parked timers are listed there too, under the subscription `wow.timers`, one entry per target aggregate. Nothing retries them automatically. Resuming the entry sends its timers again with fresh attempts; skipping it cancels them with the outcome `SKIPPED`.

Before resuming, fix the cause: deploy the corrected handler, or bring the outside system back. A resumed aggregate reads its stream again from the version it stopped at, so its handler sees every event it missed, in order.

## How a command is retried

This page so far is about handlers. A command is retried at several layers too, and every layer keeps its request id: a retry is the same request, so the target decides it once.

**In the kernel**, before the caller is answered:

- **Request ids.** A command whose request id its aggregate type already committed, in the same tenant, is not decided again. It is answered with that commit: `duplicate: true`, its version, but not the first answer's results. A request id another command type committed is refused, `wow.command.request-id-reused` (409). A command sent without a request id takes its command id.
- **Version conflicts.** Another writer appended first: the attempt is compensated and the command decided again on the newer state, at most 3 attempts in all. Then it fails with `wow.command.contended` (409), which may be sent again with the same request id.
- **Cached state.** A command refused on a cached state, or one that changed nothing, is checked against the log. When the log has moved past the cache, the command is decided again on the newer state, without spending an attempt.
- **Unknown outcomes.** When an append's answer is lost, the kernel reads the version the append would have taken. Its own commit is a success, another commit of the same request a duplicate, another request's commit a conflict. When it still cannot tell, it answers `wow.command.unknown` (503, `Retry-After: 1`): the command may have committed. A command forwarded to its partition's owner that gets no reply is answered the same, and is not run where it arrived.

**By source**, once the kernel has answered:

| Source | Who retries | How |
| --- | --- | --- |
| An HTTP caller | The caller | The server never retries a request. A 409 `wow.command.contended`, a 503 or a 429 is sent again with the same `Wow-Request-Id` ([Retry safely](./http#retry-safely)). Wow's Kotlin client does so itself: 3 attempts, 200 ms apart and doubling, waiting a `Retry-After` up to 5 s (`wow.peer.retry.*`), and it moves to another node of the peer when one cannot be reached or is closing. |
| A saga | Its subscription | A derived command's request id comes from the commit, its target and its command type, so handling the commit again sends the same requests. Each derived command is settled on its own: a rejection goes to `@OnRejected` and stops none of the others. Any other failure fails the handling, which is retried within its budget and then parks the commit's aggregate, as [above](#what-happens-when-a-handler-throws). A busy target is backpressure. |
| A timer | The timer | A fired command's request id is the timer's and its slot's. A rejection settles the timer: it is not retried, and no `@OnRejected` sees it. A failure leaves the timer pending, tried again from 1 s, doubling up to 5 min, 10 times in all (`wow.timers.max-attempts`), then parked. A busy target is tried again a second later, spending no attempt, up to 20 times in a row; the next refusal spends one, so a target that stays busy parks the timer in the end. |
| The Kafka command intake | Its subscription | A command delivered again is a duplicate. A rejection goes back to the sender as a receipt. A failure is retried within the subscription's budget and then parks the target aggregate at the command's offset, holding its later commands. A busy target is backpressure. |

**When a store is down**, a saga's failure is systemic, and so is the Kafka command intake's: the subscription pauses the partition and probes until a command or an event goes through again, and no aggregate spends its budget or parks ([How a failure is classified](#how-a-failure-is-classified)). The node's health reports the `subscriptions` component degraded while a partition is paused, and `wow.subscription.paused.partitions` counts them.

**An aggregate that parks** is retried later and then waits for an operator, as [Parked aggregates](#parked-aggregates) describes.

## Test it

A test fails a handler through its fake port and checks the error with `expectError`. Handling the same commit again is how a test shows that a retry is safe:

<!-- sample: example/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/ShipmentNotifierTest.kt#test -->

```kotlin
@Test
fun `a failed notification should be asked for again with the same key`() = reactorSpec(handlers) {
    val order = paid()
    failures += IllegalStateException("The mail service is down.")
    whenEvent(OrderShipped, by = ShipOrder::class) {
        // Within the notifier's own budget, the runtime asks again.
        expectRetried().assert().isInstanceOf(IllegalStateException::class.java)
    }
    whenEvent(OrderShipped, by = ShipOrder::class) { expectNoError() }
    sent.single().third.assert().isEqualTo("ShipmentNotifier.onOrderShipped:${order.ref}@3:0")
}
```

The retry budget itself is the runtime's: the test DSL calls the handler once per `whenEvent`.
