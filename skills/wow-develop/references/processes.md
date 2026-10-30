# Processes: sagas, projections, reactors

The job: something must happen because something was committed. Choose the kind by what the reaction does, then make it safe to run twice. Verify the annotations and handler shapes in the target's `me.ahoo.wow.api.annotation` and `me.ahoo.wow.api.subscribe`.

## Choose the kind

| The reaction | Kind | Returns |
| --- | --- | --- |
| Changes an aggregate (this context's or another's) | `@Saga` | The commands it derives |
| Writes a read model of its own (a table, an index) | `@Projection` | Nothing |
| Calls the outside world (mail, a payment gateway, a webhook) | `@Reactor` | Nothing; it sends no command |
| Runs on a clock, not on an event | `@Saga` with `@OnSchedule(name, cron, …)` | The commands it derives |

One kind per class. Each handler (`@OnEvent`) is its own subscription `<processor>.<function>` (or `<processor>.<name>` with `@Name` on the function, which keeps a renamed handler's subscription), with its own checkpoint, retries and parked entries. Constructor parameters are beans.

## Handler input

- `StateEvent<S>`: the commit (`ref`, `version`, `events`, `state` after the commit, `commandId`, `requestId`, `commandType`, `commitTime`, `scope`, `causation`, `headers`, `id`, `correlationId`).
- For one kind of event, prefer `StateTypedEvent<S, E>` (adds `event`, `eventIndex`) or `E` itself: called once per `E` of the commit, with the state after the whole commit. The compiler infers the aggregate from `E`, and the node skips commits without one. Avoid `event.events.filterIsInstance<E>()`.
- Optionally the context: a projection's or a reactor's `SubscriptionContext`; every handler of a saga, `@OnEvent`, `@OnRejected`, `@OnCommitted` or `@OnSchedule`, takes the one `SagaContext` (`ids`, `schedule`, `cancel`, `call`), or the `SubscriptionContext` it extends. Its `invocationKey` is the idempotency key of an outside call; `executionId` is per commit.
- `suspend`, or a Reactor return type (needs `wow-reactor`), or `@Blocking` for a handler that blocks its thread (JDBC): it then runs on a virtual thread.

## Sagas

A whole saga of the eShop: typed handlers, a receipt of a committed command (`@OnCommitted`), a compensation (`@OnRejected`), and a rejection accepted with `Unit`.

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt -->

```kotlin
package me.ahoo.wow.example.eshop.ordering.domain.order

import me.ahoo.wow.api.annotation.OnCommitted
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.OnRejected
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.StateTypedEvent
import me.ahoo.wow.example.eshop.inventory.api.stock.CommitReservation
import me.ahoo.wow.example.eshop.inventory.api.stock.ReleaseReservation
import me.ahoo.wow.example.eshop.inventory.api.stock.ReserveStock
import me.ahoo.wow.example.eshop.ordering.api.order.AwaitStockValidation
import me.ahoo.wow.example.eshop.ordering.api.order.CancelOrder
import me.ahoo.wow.example.eshop.ordering.api.order.ConfirmOrderStock
import me.ahoo.wow.example.eshop.ordering.api.order.OrderAwaitingValidation
import me.ahoo.wow.example.eshop.ordering.api.order.OrderCancelled
import me.ahoo.wow.example.eshop.ordering.api.order.OrderShipped
import me.ahoo.wow.example.eshop.ordering.api.order.OrderSubmitted

/**
 * The order process, after the classic eShop's `OrderProcessor`: it drives an order through the stock validation in
 * the inventory context, and compensates when the inventory refuses.
 *
 * - [onOrderSubmitted]: a submitted order moves on to await its stock validation.
 * - [onOrderAwaitingValidation]: the order reserves each product's stock in the inventory, its lines of one product
 *   added up. The inventory is another context: the commands go to its command topic, `wow.eshop-inventory.commands`.
 * - [onReserveCommitted]: a reservation the inventory commits comes back as a receipt on `wow.eshop-ordering.receipts`
 *   and confirms the order's product: the receipt names the stock, whose id is the product's.
 * - [onReserveRejected]: a reservation the inventory rejects (a short stock, a product it has no stock of) comes back
 *   as a receipt on the same topic and cancels the order.
 * - [onOrderCancelled]: a cancelled order releases what the inventory reserved for it; a product reserved for nothing
 *   releases nothing.
 * - [onOrderShipped]: a shipped order commits its reservations.
 *
 * [onReserveCommitted] is a hop of a wait chain: a checkout's `Wow-Wait` follows the reservations to their commit in
 * the inventory, then their receipts back to it, and on into the confirmations it derives.
 *
 * Each handler names only its target's id: the contract the inventory api's jar carries assigns
 * the reservations to the inventory's stock (whose id is the product's), the ordering's own Manifest assigns
 * the order's commands to the order, and each command is in the tenant of the commit or receipt it answers.
 */
@Saga
class OrderProcessSaga {
    @OnEvent
    fun onOrderSubmitted(
        event: StateTypedEvent<OrderState, OrderSubmitted>
    ): CommandRequest<AwaitStockValidation> = CommandRequest.of(AwaitStockValidation, event.ref.id)

    @OnEvent
    fun onOrderAwaitingValidation(
        event: StateTypedEvent<OrderState, OrderAwaitingValidation>
    ): List<CommandRequest<ReserveStock>> = event.state.quantities().map { (productId, quantity) ->
        CommandRequest.of(ReserveStock(event.ref.id, quantity), productId)
    }

    /** A product the inventory reserved confirms it on the order that asked for it. */
    @OnCommitted
    fun onReserveCommitted(
        committed: CommandReceipt.Committed<ReserveStock>
    ): CommandRequest<ConfirmOrderStock> =
        CommandRequest.of(ConfirmOrderStock(committed.ref.id), committed.source.id)

    /** A product the inventory cannot reserve cancels the order that asked for it. */
    @OnRejected
    fun onReserveRejected(rejected: CommandReceipt.Rejected<ReserveStock>): CommandRequest<CancelOrder> =
        CommandRequest.of(
            CancelOrder("${rejected.ref.id} cannot be reserved: ${rejected.error.message}"),
            rejected.source.id,
        )

    @OnEvent
    fun onOrderCancelled(
        event: StateTypedEvent<OrderState, OrderCancelled>
    ): List<CommandRequest<ReleaseReservation>> = event.state.products().map { productId ->
        CommandRequest.of(ReleaseReservation(event.ref.id), productId)
    }

    /**
     * A release the inventory rejects needs no compensation: it rejects one only for a product it has no stock of,
     * which reserved nothing either. Without this handler, the rejection would park the order for the saga; returning
     * `Unit` accepts it and derives nothing.
     */
    @Suppress("UnusedParameter")
    @OnRejected
    fun onReleaseRejected(rejected: CommandReceipt.Rejected<ReleaseReservation>) = Unit

    @OnEvent
    fun onOrderShipped(
        event: StateTypedEvent<OrderState, OrderShipped>
    ): List<CommandRequest<CommitReservation>> = event.state.products().map { productId ->
        CommandRequest.of(CommitReservation(event.ref.id), productId)
    }
}
```

- A saga returns `CommandRequest`s (one, or an `Iterable`, a `Sequence` or a `Flow` of them); the runtime gives each a deterministic id, so a handler run twice derives the same commands, which the target deduplicates. `CommandRequest.of(body, id)`, or `CommandRequest.of(body)` for a creation without an id, goes to the aggregate the local Manifest or a peer api jar's contract assigns to the body's class, in the handled commit's tenant; `CommandRequest.to(ref, body)` names the full ref (another tenant, a class several aggregates decide). A `requestId` is a business key for the id; `wait` and `timeout` are refused.
- `@Sends(derives = [A::class, B::class], schedules = [...], calls = [...])` (command classes, or a sealed type) declares what a saga's handler of any kind sends. A handler whose return type names its commands (`CommandRequest<X>`) needs no `derives`; one that returns untyped requests (`CommandRequest<*>`, `Any`) must declare them, or the build fails. The build also fails on `@Sends` outside a saga's handler, and on a class neither the service nor a peer contract decides. At run time, a derive, a `schedule` or a `call` of a class its `@Sends` does not declare fails the handling as unrecoverable: the aggregate parks at once.
- **Rejections:** `@OnRejected` taking `CommandReceipt.Rejected<C>` (and optionally `SagaContext`) returns compensating commands, or `Unit` to accept the rejection. It also takes the rejection of a `call` the handler lets through: that handling is aborted and derives nothing. Without one, a rejected derived or called command **parks the aggregate** for the saga. Decide for each derived or called command which rejections are expected.
- **Committed receipts:** `@OnCommitted` taking `CommandReceipt.Committed<C>`: the derived command committed, possibly in another context.
- **Both receipts:** a handler annotated `@OnRejected` and `@OnCommitted` takes a `CommandReceipt<C>` and tells the two apart with `when`; each annotation keeps its own rule (a rejection is not retried, a commit's receipt is retried by the host). One annotation with `CommandReceipt<C>`, or an annotation over the other receipt's type, fails the build.
- **Scheduled commands:** `SagaContext.schedule(key, at|after, request)` / `cancel(key, target)` record into the context, sent after the returned commands; at least once, never early. `@OnSchedule(name, cron, zone, scope, misfire)` for a cron job, its `scope` `SYSTEM` (one instance), `TENANT` (one per tenant) or `SHARDED` (one per log partition); the host configures it under `wow.schedules.<name>.*` alone: `cron`, `zone`, `misfire`, `enabled`, and its subscription's `retry` and `parked`.

## Projections

A handler writes its read model with a guard by version (`upsert(…, ifNewer = event.version)`): a commit may be handled more than once, and a rebuild replays from the beginning. Keep the read model's key the aggregate id (and tenant). A projection that must answer queries the snapshot cannot is the only reason to write one: the snapshot is already a read model (`reads.md`).

## Reactors

A reactor calls the outside world with the context's `invocationKey` as the outside call's idempotency key, so a retry is not a second payment or a second mail. It sends no command; a reaction that changes an aggregate is a saga.

## Start positions

Without a checkpoint, projections and handlers of another context's stream start from the beginning; reactors and sagas of the own log from the end. `@OnEvent(start = …)` declares it, and the host overrides it with `wow.subscriptions.[<context>.<name>].start` (`beginning`, `end`, or an ISO-8601 instant). A new projection over existing history starts at the beginning; a new reactor that must not mail every past customer starts at the end.

## Failures: retry, then park

1. **In process:** a failing handler is retried (`@Retry(maxAttempts, initialBackoff, maxBackoff, multiplier, timeout, recoverable, unrecoverable, systemic)` on the handler or its processor, a processor without one taking its nearest superclass's, over the host's `wow.subscription.retry.*` and `wow.subscriptions.[<context>.<name>].retry.*`, a job's `wow.schedules.<name>.retry.*`). An exception classified unrecoverable is not retried.
2. **Parked:** then the **aggregate** (not the event) is parked in the failure ledger for that subscription: its later commits wait, other aggregates go on. The long-horizon retries follow `wow.subscription.parked.*` (per subscription `wow.subscriptions.[<context>.<name>].parked.*`). A retry handles its commits from the failed version on, including ones already handled, so handlers must tolerate redelivery.
3. **Systemic:** when several aggregates fail within a window (`wow.subscription.systemic.*`), the subscription pauses its partition and probes until a handling succeeds, instead of parking everything.
4. **Backpressure:** a downstream that pushes back (`wow.command.busy`, `wow.wait.timeout`, `wow.query.busy`, a 429 with `Retry-After`) throttles the partition and retries, then parks; it never pauses the partition.

Operators see parked entries with `wow.http.ops.enabled` (`/wow/ops/parked`: list, `retry`, `skip`, `mark-recoverable`, `apply-retry-spec`, authorized by the host's `CommandAuthorizer`; an entry is `/wow/ops/parked/{<context>.<name>}/{context}/{aggregate}/{tenant}/{id}`; `skip` requires the body `{"failedAt": …}`, the entry's `failedAt`, and `retry` takes it optionally, both refusing a parking since replaced as 409 `wow.ledger.stale`; `GET /wow/ops/subscriptions` reads each subscription's positions, lag, paused partitions and parked count, and the checkpoints left under old names), a webhook (`wow.ops.webhook.*`), and the ops console (`wow.ops.publish.enabled`: `auto` by default, publishing once the ops service is deployed on the same Kafka).

## Idempotency, summed up

A commit can reach a handler more than once (retries, an operator's retry, a rebuild). Sagas are safe by deterministic command ids; projections by a version guard; reactors by the invocation key. Anything else a handler writes needs its own guard.
