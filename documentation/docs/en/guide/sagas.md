---
title: Write a Saga
description: Derive commands from committed events, act on their success and their rejections, call another context synchronously, and schedule commands, with deterministic requests.
outline: deep
---

# Write a Saga

A saga turns what one aggregate decided into commands for others: an order awaiting validation reserves its stock, a confirmed reservation confirms the order, a refused one cancels it. The saga holds no state of its own. It reads committed events and returns commands, and the runtime sends them. This page writes the eShop's order process. [Subscriptions, Failures and Replay](../concepts/subscriptions) explains how a saga is delivered its events.

## Derive commands from events

A saga is a class annotated `@Saga`. Each handler, annotated `@OnEvent`, takes a committed event and the `SagaContext`, and returns the commands it derives:

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
- **Its name.** Each handler is a subscription of its own, `<processor>.<function>`: `OrderProcessSaga.onOrderAwaitingValidation`. `@Name("…")` on the function names it instead, so renaming the function with `@Name("<its former name>")` keeps the subscription, its checkpoint and its executions.
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

- Its id and request id come from the execution (`subscription:ref@version`) and what it asks: its target, its command type, and how many commands of that type to that target the execution derived before it. Handling the commit again derives the same requests in whatever order the handler builds them, even when a read answers in another order, and each target decides each one once.
- It carries the operator, the owner and the space of the commit it is derived from. An aggregate it creates belongs to the same owner: a basket's checkout creates an order that belongs to the basket's buyer. Derived commands are trusted, so the target's scope guard does not check them: a wrong target is not refused. Test the targets in `sagaSpec`.
- `context.ids` draws an id for an aggregate the command creates. Handling the commit again draws a new one, so use it only for the creating command itself, whose request id keeps the target from creating it twice. A command built with an `AggregateType` leaves the id to the target and keeps the first one on every retry.

## Where the commands go

| The target | How it is sent | The saga's hop is done when |
| --- | --- | --- |
| This context | The node decides it within the saga's handling | It is committed |
| Another context | Its command topic, `wow.<context>.commands` | The broker has it |

A service that executes the commands of other contexts sets `wow.kafka.command-intake.enabled: true`. A node whose sagas send another context's commands needs a command transport, Kafka (`wow.kafka.bootstrap-servers`) in production, and does not start without one. [Kafka](../operate/kafka) describes the topics.

## Learn that a command succeeded

A derived command answers the saga nothing by default: the saga's hop ends once the command is committed in its own context, or once the broker has it for another. Its success already has a fact of its own, the events the target commits, so a saga learns of it the way it learns of anything:

- **Follow the target's stream.** Handle the event the success produces, as any other event. The payment saga requests a payment, and pays the order once that payment's own `PaymentSucceeded` is committed:

  <!-- sample: example/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/OrderPaymentSaga.kt#peer-command -->

  ```kotlin
  @OnEvent
  fun onPaymentSucceeded(
      event: StateTypedEvent<PaymentState, PaymentSucceeded>,
      context: SagaContext,
  ): Command<PayOrder> = context.command(event.state.orderId, PayOrder(event.ref.id, event.state.amount))
  ```

  A target of another context is followed on its published stream: the handler takes that context's event class, whose aggregate its contract names ([Publish and Consume Streams](./published-streams)).

- **Set a deadline.** When the success may never come, schedule the command that compensates, and cancel it when the success arrives:

  <!-- sample: fragment -->

  ```kotlin
  @OnEvent(schedules = [CancelOrder::class])
  fun onOrderAwaitingStock(event: StateTypedEvent<OrderState, OrderAwaitingStock>, context: SagaContext) = listOf(
      context.command(STOCK.ref(event.ref.tenantId, event.state.productId), ReserveStock(event.ref.id)),
      context.schedule(STOCK_DEADLINE, ScheduleRule.after(10.minutes), event.ref, CancelOrder("No stock in time.")),
  )

  @OnEvent
  fun onOrderStockConfirmed(event: StateTypedEvent<OrderState, OrderStockConfirmed>, context: SagaContext) =
      context.cancel(STOCK_DEADLINE, event.ref)
  ```

  The target schedules the command for itself, so it must decide it. A scheduled command the target refuses when it falls due is logged and not retried, and no `@OnRejected` sees it: cancel the schedule on the success, as above, or give the target a command it refuses by its state once the success is in. Only an `@OnEvent` handler schedules or cancels.

- **A success that writes nothing** produces no event, so the saga sees nothing: a target that accepts a command with `Decision.NoOp` commits nothing. When someone must know, let the target decide a business event instead.

### Opt in to a receipt {#act-on-a-command-s-success}

A saga that must know that its own command committed, because the target's stream is not published to it or because the success produces no event, declares an `@OnCommitted` handler of the command's type. It takes a `CommandCommitted<C>` and returns the commands it derives in turn:

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#committed -->

```kotlin
/** A product the inventory reserved confirms it on the order that asked for it. */
@OnCommitted
fun onReserveCommitted(
    committed: CommandCommitted<ReserveStock>,
    context: ReceiptContext
): Command<ConfirmOrderStock> =
    context.command(committed.source.id, ConfirmOrderStock(committed.ref.id))
```

`source` is the aggregate whose commit derived the command, here the order; `ref` is the aggregate the command was committed on, here the product's stock. Only the commands an `@OnCommitted` handler takes ask for a receipt, so no other command costs one. A command to another context carries the request with it, and the target sends the receipt back on `wow.<sender>.receipts` once the command is committed. The receipt carries the command's result, not the target's state.

A receipt comes for a command the target accepted without change too (`Decision.NoOp`), with its version unchanged. An `@OnCommitted` handler, like an `@OnRejected` one, takes a `ReceiptContext`: it derives commands, and neither schedules nor calls.

## Handle a rejection

A derived command may be refused: the stock is short, the order was cancelled meanwhile. A rejection is a business outcome, so the saga handles it in an `@OnRejected` handler of the command's type, which takes a `CommandRejected<C>` and returns the commands that compensate:

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#rejected -->

```kotlin
/** A product the inventory cannot reserve cancels the order that asked for it. */
@OnRejected
fun onReserveRejected(rejected: CommandRejected<ReserveStock>, context: ReceiptContext): Command<CancelOrder> =
    context.command(
        rejected.source.id,
        CancelOrder("${rejected.ref.id} cannot be reserved: ${rejected.error.message}"),
    )
```

`CommandRejected` names the command (`commandId`, `commandType`, `ref`), its `error` (code, message, violations, extensions), the saga's `executionId` and its `source`, the aggregate whose commit derived the command; it does not carry the command's body. A rejection from another context comes back as a receipt. The runtime hands each rejection over once.

Each derived command is settled on its own: a rejected command stops none of the others, and each rejection goes to its handler. The commands to one aggregate run in the order the saga derived them; those to different aggregates run concurrently, so work that depends on another command's outcome is not derived beside it: derive it from the event the first command commits. The rejections the saga has no handler for park the aggregate once for the saga's subscription, as a failure would, listing every rejected command. A rejection that needs no compensation is accepted by a handler that returns `Unit`:

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
- The node reaches the target on its internal port: its nodes are found by DNS from the service its contract names, or at `wow.peers.<context>.url` ([Configure a peer](./calling-services#configure-a-peer-over-http)). A node does not start when a handler calls a context it cannot reach. A command of the saga's own context is derived, never called.
- `call` is a `suspend` function, so a saga written in Java derives its commands instead.

## Schedule a command

`context.schedule(key, rule, target, command)` returns a command that asks `target` to schedule `command` for itself under `key`; `context.cancel(key, target)` cancels it. Return them with the other commands. The handler declares the scheduled commands' full type names in `@OnEvent(schedules = [...])`. An aggregate can also schedule commands from its own decision, with the `DecideScope`: [Write an Aggregate](./aggregates#ask-the-kernel-for-more).

## Where it starts

A saga's subscription that has no checkpoint in a partition starts at the end of its stream, so deploying it does not act on the history again; one that reads another context's stream starts at the beginning. `@OnEvent(start = ...)` overrides it, and so does the host's `wow.subscriptions.[<processor.function>].start`. After its first checkpoint a subscription goes on from there.

## Test it

`sagaSpec` hands a saga committed events, receipts and rejections, and checks the commands it derives: [Test Domain Code](./testing#sagas).

## Next

- [Publish and Consume Streams](./published-streams): read another context's events in a saga.
