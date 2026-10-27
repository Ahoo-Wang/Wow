---
title: Projections and Reactors
description: Write a read model from committed events with a projection, act on them with a reactor, keep both idempotent, choose where they start, and test them.
outline: deep
---

# Projections and Reactors

A projection and a reactor both follow an aggregate's committed events and return nothing. A **projection** writes a read model, such as a table a page reads. A **reactor** acts on the outside world: it sends a notification or calls another system. A processor that sends commands is a saga instead: [Write a Saga](./sagas). This page writes one of each for the eShop's order. [Subscriptions, Failures and Replay](../concepts/subscriptions) explains how they are delivered.

## Do you need a projection?

Every aggregate already keeps a snapshot of its state, and the snapshot is queryable. Write a projection when the read model needs another shape than the state (a row per buyer, a summary across aggregates), or another store (a search index, a table another system reads).

## Write a projection

A projection is a class annotated `@Projection`. Each handler, annotated `@OnEvent` or named `onEvent`, takes a committed event; its dependencies are the constructor's parameters, bound from the application's beans:

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderSummaryProjection.kt#project -->

```kotlin
@Blocking
@OnEvent
fun onEvent(event: StateEvent<OrderState>) {
    val state = event.state
    summaries.save(
        OrderSummary(
            tenantId = event.ref.tenantId,
            orderId = event.ref.id,
            buyerId = event.scope.ownerId,
            status = state.status,
            totalAmount = state.totalAmount,
            units = state.quantities().values.sum(),
            updatedAt = event.commitTime,
        ),
        version = event.version,
    )
}
```

- `StateEvent<OrderState>` calls the handler once for each commit of every order, with `state` the order's state after the commit. Take `StateTypedEvent<S, E>` instead to be called only for the events of type `E`, once per such event.
- `scope.ownerId` is the aggregate's owner after the commit, here the buyer; `commitTime` is when it committed.
- `@Blocking` runs the handler on a virtual thread, since it writes to a store outside Wow. A handler may also be `suspend`, or return a Reactor `Mono`.
- The handler is a subscription of its own, `OrderSummaryProjection.onEvent`.

## Keep it idempotent

A commit may be handled more than once: after a failed attempt, a replay, or a lease handover between nodes. A projection's write is therefore guarded by the version it writes, so handling a commit again, or an older commit late, never replaces a newer row. The order summaries keep the newest version of each order:

<!-- sample: example/eshop/ordering/ordering-server/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/server/OrderingPorts.kt#save -->

```kotlin
override fun save(summary: OrderSummary, version: Int) {
    summaries.merge(summary.tenantId to summary.orderId, Versioned(version, summary)) { saved, newer ->
        if (newer.version > saved.version) newer else saved
    }
}
```

In SQL the same guard is an upsert that only updates a row whose version is lower than the commit's.

## Write a reactor

A reactor is a class annotated `@Reactor`. Its handlers take the same events, and the context gives each call an idempotency key for the outside call:

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

`context.invocationKey` is the same every time the same commit is handled by the same handler: `executionId` (`subscription:ref@version`) for a handler of the whole stream, `executionId:eventIndex` for a handler of one event type. Pass it to the outside system as its idempotency key, so a notification asked for twice is sent once. `@Retry` gives the reactor its own retry budget: [Failures and Retries](./failures).

## Where they start

A subscription that has no checkpoint yet in a partition starts by its kind:

| Kind | Starts | So that |
| --- | --- | --- |
| Projection | At the beginning of the log | Deploying it fills its read model with the history |
| Reactor | At the end of the log | Deploying it does not notify anyone about the past |
| Either, on another context's stream | At the beginning of the topic | A service that joins later still gets what was published before |

`@OnEvent(start = ...)` overrides it, and so does the host's `wow.subscriptions.[<processor.function>].start`, `beginning`, `end` or an ISO-8601 instant. Either way it applies only until the subscription has a checkpoint; after that it goes on from there.

## Rebuild a read model

Because a projection's position is its checkpoint, its read model can always be rebuilt from the log. To change a read model's shape, deploy the new projection under a new name: it starts at the beginning, catches up, and the reads switch to its store once it has. Then remove the old one. [Subscriptions, Failures and Replay](../concepts/subscriptions#replay-and-backfill) describes the options.

## Test them

`projectionSpec` and `reactorSpec` hand the generated handlers committed events, and the test checks what the fake port then holds. Handing the same commit twice is how a test shows that a projection is idempotent:

<!-- sample: example/eshop/ordering/ordering-domain/src/test/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderSummaryProjectionTest.kt#test -->

```kotlin
@Test
fun `the same commit handled again should save the same summary`() = projectionSpec(handlers) {
    submitted()
    whenEvent(OrderAwaitingValidation, by = AwaitStockValidation::class) { expectNoError() }
    whenEvent(OrderAwaitingValidation, by = AwaitStockValidation::class) { expectNoError() }
    saved.distinct().size.assert().isEqualTo(1)
}
```

[Test Domain Code](./testing#projections-and-reactors) describes the specs.
