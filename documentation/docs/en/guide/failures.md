---
title: Failures and Retries
description: What happens when a handler fails, how to give it its own retry budget and timeout, how failures are classified, and how parked aggregates are retried and resumed.
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

Before resuming, fix the cause: deploy the corrected handler, or bring the outside system back. A resumed aggregate reads its stream again from the version it stopped at, so its handler sees every event it missed, in order.

## Test it

A test fails a handler through its fake port and checks the error with `expectError`. Handling the same commit again is how a test shows that a retry is safe:

<!-- sample: example/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/ShipmentNotifierTest.kt#test -->

```kotlin
@Test
fun `a failed notification should be asked for again with the same key`() = reactorSpec(handlers) {
    failures += IllegalStateException("The mail service is down.")
    whenEvent(shipped()) { expectError<IllegalStateException>() }
    whenEvent(shipped()) { expectNoError() }
    sent.single().third.assert().isEqualTo("ShipmentNotifier.onOrderShipped:$order@6:0")
}
```

The retry budget itself is the runtime's: the test DSL calls the handler once per `whenEvent`.
