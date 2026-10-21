---
title: Write a Saga
description: Derive commands from committed events, act on their success and their rejections, call another context synchronously, and schedule commands, with deterministic requests.
outline: deep
---

# Write a Saga

A saga turns what one aggregate decided into commands for others: an order awaiting validation reserves its stock, a confirmed reservation confirms the order, a refused one cancels it. The saga holds no state of its own. It reads committed events and returns commands, and the runtime sends them. This page writes the eShop's order process. [Subscriptions, Failures and Replay](../concepts/subscriptions) explains how a saga is delivered its events.

## Derive commands from events

A saga is a class annotated `@Saga`. Each handler, annotated `@OnEvent` or named `onEvent`, takes a committed event and the `SagaContext`, and returns the commands it derives:

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#derive -->

```kotlin
@OnEvent
fun onOrderSubmitted(
    event: StateTypedEvent<OrderState, OrderSubmitted>,
    context: SagaContext,
): Command<AwaitStockValidation> = context.command(event.ref.id, AwaitStockValidation)
```

- **What it takes.** A `StateTypedEvent<S, E>` calls the handler once for each event `E` in a committed stream, with `event` that event and `state` the aggregate's state after the whole commit. A `StateEvent<S>` calls it once per stream, with all its `events`. The state type `S` selects the aggregates of the module's context whose state it is.
- **What it returns.** One `Command`, a list of them, or `null`, an empty list or `Unit` for none. Each command is built with the context's `command` functions (below).
- **Which commands.** When the return type names the command classes (`Command<ReserveStock>`, a list of them, a sealed type), the compiler knows them. When it does not, as with `List<Command<*>>`, declare them in `@OnEvent(derives = [...])`, so the Manifest lists them and a wait can follow the saga into their contexts.
- **Its name.** Each handler is a subscription of its own, `<processor>.<function>`: `OrderProcessSaga.onOrderAwaitingValidation`.
- **Its dependencies.** The saga's constructor parameters, bound from the application's beans.

## Build the commands

| Call | Derives |
| --- | --- |
| `context.command(id, body)` | A command to the aggregate `id` of the type that decides `body`'s class, in the tenant of the commit |
| `context.command(body)` | A creating command to a new aggregate of that type, whose id the target gives it |
| `context.command(ref, body)` | A command to the aggregate `ref` names, of any type and tenant |
| `context.command(type, body)` | A creating command to a new aggregate of `type`, whose id the target gives it |

`command(id, body)` and `command(body)` find the target's type from the commands each context declares: this service's own Manifest, and the contract each peer's api jar carries. The call fails when no context on the class path decides the class, or when several aggregates do; write the full ref then.

Every derived command is deterministic:

- Its id and request id come from the execution (`subscription:ref@version`) and the order of the calls, so handling the commit again derives the same requests, and each target decides each one once.
- It carries the operator, the owner and the space of the commit it is derived from. An aggregate it creates belongs to the same owner: a basket's checkout creates an order that belongs to the basket's buyer. The target's scope guard checks that owner, so a command to another owner's aggregate is refused, unless the target belongs to its tenant (`Ownership.TENANT`, such as a product's stock).
- `context.ids` draws an id for an aggregate the command creates. Handling the commit again draws a new one, so use it only for the creating command itself, whose request id keeps the target from creating it twice. A command built with an `AggregateType` leaves the id to the target and keeps the first one on every retry.

## Where the commands go

| The target | How it is sent | The saga's hop is done when |
| --- | --- | --- |
| This context | The node decides it within the saga's handling | It is committed |
| Another context | Its command topic, `wow.<context>.commands` | The broker has it |

A service that executes the commands of other contexts sets `wow.kafka.command-intake: true`. A node whose sagas send another context's commands needs a command transport, Kafka (`wow.kafka.bootstrap-servers`) in production, and does not start without one. [Kafka](../operate/kafka) describes the topics.

## Act on a command's success

A saga that must act once a derived command is committed declares an `@OnCommitted` handler of the command's type. It takes a `CommandCommitted<C>` and returns the commands it derives in turn:

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#committed -->

```kotlin
/** A product the inventory reserved confirms it on the order that asked for it. */
@OnCommitted
fun onReserveCommitted(
    committed: CommandCommitted<ReserveStock>,
    context: SagaContext
): Command<ConfirmOrderStock> =
    context.command(committed.executionId.ref.id, ConfirmOrderStock(committed.aggregateId))
```

`executionId.ref` is the aggregate whose commit derived the command, here the order; `aggregateId` is the aggregate the command was committed on, here the product's stock. Only the commands an `@OnCommitted` handler takes ask for a receipt. A command to another context carries the request with it, and the target sends the receipt back on `wow.<sender>.receipts` once the command is committed. The receipt carries the command's result, not the target's state.

## Handle a rejection

A derived command may be refused: the stock is short, the order was cancelled meanwhile. A rejection is a business outcome, so the saga handles it in an `@OnRejected` handler of the command's type, which takes a `CommandRejected<C>` and returns the commands that compensate:

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#rejected -->

```kotlin
/** A product the inventory cannot reserve cancels the order that asked for it. */
@OnRejected
fun onReserveRejected(rejected: CommandRejected<ReserveStock>, context: SagaContext): Command<CancelOrder> =
    context.command(
        rejected.executionId.ref.id,
        CancelOrder("${rejected.ref.id} cannot be reserved: ${rejected.error.message}"),
    )
```

`CommandRejected` names the command (`commandId`, `commandType`, `ref`), its `error` (code, message, violations, extensions) and the saga's `executionId`; it does not carry the command's body. A rejection from another context comes back as a receipt. The runtime hands each rejection over once.

A rejection the saga has no handler for parks the aggregate for the saga's subscription, as a failure would. A rejection that needs no compensation is accepted by a handler that returns `Unit`:

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#release-rejected -->

```kotlin
/**
 * A release the inventory rejects needs no compensation: it rejects one only for a product it has no stock of,
 * which reserved nothing either. Without this handler, the rejection would park the order for the saga; returning
 * `Unit` accepts it and derives nothing.
 */
@Suppress("UnusedParameter")
@OnRejected
fun onReleaseRejected(rejected: CommandRejected<ReleaseReservation>) = Unit
```

`CommandRejected<*>` and `CommandCommitted<*>` take every command the saga derives.

## Call another context synchronously

By default a saga derives its commands and the command topic delivers them. When the target being online is a precondition and the saga needs the result now, the handler calls the target's command gateway instead, with `context.call`:

<!-- sample: example/eshop/basket/basket-domain/src/main/kotlin/me/ahoo/wow/example/eshop/basket/domain/basket/BasketCheckoutSaga.kt#call -->

```kotlin
@OnEvent(calls = [SubmitOrder::class])
suspend fun onBasketCheckedOut(event: StateTypedEvent<BasketState, BasketCheckedOut>, context: SagaContext) {
    val checkedOut = event.event
    context.call(
        context.command(
            ORDER.ref(event.ref.tenantId, checkedOut.orderId),
            SubmitOrder(checkedOut.items.map { OrderItem(it.productId, it.unitPrice, it.quantity) }),
        ),
    )
}
```

- The handler declares the classes it calls in `@OnEvent(calls = [...])`. The Manifest lists them, a wait follows the call, and `call` refuses any other class.
- `call` returns the command's result once it is committed on the target. Build the command with `context.command` first: its request id makes every retry the same request, which the target decides once.
- A refusal throws `CallRejectedException`, a business outcome that is not retried. A call that reaches no result throws `CallFailedException`: the handler fails and is retried, then parks the aggregate.
- The node reaches the target on its internal port, at `wow.calls.contexts.<context>.url`, and does not start when a handler calls a context without one. A command of the saga's own context is derived, never called.
- `call` is a `suspend` function, so a saga written in Java derives its commands instead.

## Schedule a command

`context.schedule(key, rule, target, command)` returns a command that asks `target` to schedule `command` for itself under `key`; `context.cancel(key, target)` cancels it. Return them with the other commands. The handler declares the scheduled commands' full type names in `@OnEvent(schedules = [...])`. An aggregate can also schedule commands from its own decision, with the `DecideScope`: [Write an Aggregate](./aggregates#ask-the-kernel-for-more).

## Where it starts

A saga's subscription that has no checkpoint in a partition starts at the end of its stream, so deploying it does not act on the history again; one that reads another context's stream starts at the beginning. `@OnEvent(start = ...)` overrides it, and so does the host's `wow.subscriptions.[<processor.function>].start`. After its first checkpoint a subscription goes on from there.

## Test it

`sagaSpec` hands a saga committed events, receipts and rejections, and checks the commands it derives: [Test Domain Code](./testing#sagas).

## Next

- [Publish and Consume Streams](./published-streams): read another context's events in a saga.
