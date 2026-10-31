---
title: Write a Saga
description: Derive commands from committed events, act on their success and their rejections, call another context synchronously, and schedule commands, with deterministic requests.
outline: deep
---

# Write a Saga

A saga turns what one aggregate decided into commands for others: an order awaiting validation reserves its stock, a confirmed reservation confirms the order, a refused one cancels it. The saga holds no state of its own. It reads committed events and returns commands, and the runtime sends them. This page writes the eShop's order process. [Subscriptions, Failures and Replay](../concepts/subscriptions) explains how a saga is delivered its events.

## Derive commands from events

A saga is a class annotated `@Saga`. Each handler, annotated `@OnEvent`, takes a committed event and, if it needs it, the `SagaContext`, and returns the commands it derives as `CommandRequest`s:

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#derive -->

```kotlin
@OnEvent
fun onOrderSubmitted(
    event: StateTypedEvent<OrderState, OrderSubmitted>
): CommandRequest<AwaitStockValidation> = CommandRequest.of(AwaitStockValidation, event.ref.id)
```

- **What it takes.** A `StateTypedEvent<S, E>` calls the handler once for each event `E` in a committed stream, with `event` that event and `state` the aggregate's state after the whole commit. A `StateEvent<S>` calls it once per stream, with all its `events`. The state type `S` selects the aggregates of the module's context whose state it is.
- **What it returns.** One `CommandRequest`, several (a `List`, any `Iterable`, a `Sequence` or a `Flow`), or `null`, an empty list or `Unit` for none. The runtime turns each request into a deterministic command (below).
- **Which commands.** When the return type names the command classes (`CommandRequest<ReserveStock>`, a list of them, a sealed type), the compiler knows them. When it does not, as with `List<CommandRequest<*>>`, declare them in `@Sends(derives = [...])`, so the Manifest lists them and a wait can follow the saga into their contexts; a handler that does neither does not compile. The runtime holds the handler to them: sending a class it does not declare fails the handling for good, and the aggregate parks at once.
- **Its name.** Each handler is a subscription of its own, `<processor>.<function>`: `OrderProcessSaga.onOrderAwaitingValidation`. `@Name("…")` on the function names it instead, so renaming the function with `@Name("<its former name>")` keeps the subscription, its checkpoint and its executions.
- **Its dependencies.** The saga's constructor parameters, bound from the application's beans.

## Build the commands

| Request | Derives |
| --- | --- |
| `CommandRequest.of(body, id)` | A command to the aggregate `id` of the type that decides `body`'s class, in the tenant of the commit |
| `CommandRequest.of(body)` | A creating command to a new aggregate of that type, whose id the target gives it; a command whose body names its aggregate (`@AggregateId`), to that aggregate |
| `CommandRequest.to(ref, body)` | A command to the aggregate `ref` names, of any type and tenant |
| `CommandRequest.of(body, type = type)` | A creating command to a new aggregate of `type`, whose id the target gives it |

A command whose body names its aggregate ([Name the aggregate in the body](./aggregates#name-the-aggregate-in-the-body)) needs no id, in this context or another: a request that names one beside the body names the same aggregate, and one that names another, or a body that names a blank id, fails the handler. A request without a type finds the target's type from the commands each context declares: this service's own Manifest, and the contract each peer's api jar carries. The handler fails when no context on the class path decides the class, or when several aggregates do; give the request its type then. `targetTenant` and `targetScope` send the command to another tenant or scope, and `expectedVersion` asks the target for that version.

Every derived command is deterministic:

- Its id and request id come from the execution (`subscription:ref@version`) and what it asks: its target, its command type, and how many commands of that type to that target the execution derived before it. Handling the commit again derives the same requests in whatever order the handler builds them, even when a read answers in another order, and each target decides each one once. A request that gives a `requestId` takes it as its business key instead of the count, so a command keeps its id whatever else the handler derives.
- It carries the operator, the owner and the space of the commit it is derived from. An aggregate it creates belongs to the same owner: a basket's checkout creates an order that belongs to the basket's buyer. Derived commands are trusted, so the target's scope guard does not check them: a wrong target is not refused. Test the targets in `sagaSpec`.
- The subscription sends it and waits for nothing: a request with a `wait` or a `timeout` fails the handler.
- `context.ids` draws an id for an aggregate the command creates. Handling the commit again draws a new one, so use it only for the creating command itself, whose request id keeps the target from creating it twice. A creating request without an id leaves the id to the target and keeps the first one on every retry.

## Where the commands go

| The target | How it is sent | The saga's hop is done when |
| --- | --- | --- |
| This context | The node decides it within the saga's handling | It is committed |
| Another context | Its command topic, `wow.<context>.commands` | The broker has it |

A service that executes the commands of other contexts sets `wow.kafka.command-intake.enabled: true`. A node whose sagas send another context's commands needs a command transport, Kafka (`wow.kafka.bootstrap-servers`) in production, and does not start without one. [Kafka](../operate/kafka) describes the topics.

## Learn that a command succeeded

A derived command answers the saga nothing by default: the saga's hop ends once the command is committed in its own context, or once the broker has it for another. Its success already has a fact of its own, the events the target commits, so a saga learns of it the way it learns of anything:

- **Follow the target's stream.** Handle the event the success produces, as any other event. The payment saga requests a payment, and pays the order once that payment's own `PaymentSucceeded` is committed:

  <!-- sample: examples/eshop/payment/payment-domain/src/main/kotlin/me/ahoo/wow/example/eshop/payment/domain/payment/OrderPaymentSaga.kt#peer-command -->

  ```kotlin
  @OnEvent
  fun onPaymentSucceeded(
      event: StateTypedEvent<PaymentState, PaymentSucceeded>
  ): CommandRequest<PayOrder> = CommandRequest.of(PayOrder(event.ref.id, event.state.amount), event.state.orderId)
  ```

  A target of another context is followed on its published stream: the handler takes that context's event class, whose aggregate its contract names ([Publish and Consume Streams](./published-streams)).

- **Set a deadline.** When the success may never come, schedule the command that compensates, and cancel it when the success arrives:

  <!-- sample: fragment -->

  ```kotlin
  @OnEvent
  @Sends(schedules = [CancelOrder::class])
  fun onOrderAwaitingStock(
      event: StateTypedEvent<OrderState, OrderAwaitingStock>,
      context: SagaContext,
  ): CommandRequest<ReserveStock> {
      val deadline = CommandRequest.of(CancelOrder("No stock in time."), event.ref.id)
      context.schedule(STOCK_DEADLINE, ScheduleRule.after(10.minutes), deadline)
      return CommandRequest.of(ReserveStock(event.ref.id), event.state.productId)
  }

  @OnEvent
  fun onOrderStockConfirmed(event: StateTypedEvent<OrderState, OrderStockConfirmed>, context: SagaContext) {
      context.cancel(STOCK_DEADLINE, event.ref)
  }
  ```

  The target schedules the command for itself, so it must decide it. A scheduled command the target refuses when it falls due is logged and not retried, and no `@OnRejected` sees it: cancel the schedule on the success, as above, or give the target a command it refuses by its state once the success is in. Any handler of the saga schedules and cancels, an `@OnRejected` or `@OnCommitted` one included.

- **A success that writes nothing** produces no event, so the saga sees nothing: a target that accepts a command with `Decision.NoOp` commits nothing. When someone must know, let the target decide a business event instead.

### Opt in to a receipt {#act-on-a-command-s-success}

A saga that must know that its own command committed, because the target's stream is not published to it or because the success produces no event, declares an `@OnCommitted` handler of the command's type. It takes a `CommandReceipt.Committed<C>` and returns the commands it derives in turn:

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#committed -->

```kotlin
/** A product the inventory reserved confirms it on the order that asked for it. */
@OnCommitted
fun onReserveCommitted(
    committed: CommandReceipt.Committed<ReserveStock>
): CommandRequest<ConfirmOrderStock> =
    CommandRequest.of(ConfirmOrderStock(committed.ref.id), committed.source.id)
```

`source` is the aggregate whose commit derived the command, here the order; `ref` is the aggregate the command was committed on, here the product's stock. Only the commands an `@OnCommitted` handler takes ask for a receipt, so no other command costs one. A command to another context carries the request with it, and the target sends the receipt back on `wow.<sender>.receipts` once the command is committed. The receipt carries the command's result, not the target's state.

A receipt comes for a command the target accepted without change too (`Decision.NoOp`), with its version unchanged, and for one it had already committed, marked as a duplicate. An `@OnCommitted` handler, like an `@OnRejected` one, takes the `SagaContext` when it needs it: it derives, schedules and calls as an `@OnEvent` handler does, and declares what it sends in its own `@Sends`.

## Handle a rejection

A derived command may be refused: the stock is short, the order was cancelled meanwhile. A rejection is a business outcome, so the saga handles it in an `@OnRejected` handler of the command's type, which takes a `CommandReceipt.Rejected<C>` and returns the commands that compensate:

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#rejected -->

```kotlin
/** A product the inventory cannot reserve cancels the order that asked for it. */
@OnRejected
fun onReserveRejected(rejected: CommandReceipt.Rejected<ReserveStock>): CommandRequest<CancelOrder> =
    CommandRequest.of(
        CancelOrder("${rejected.ref.id} cannot be reserved: ${rejected.error.message}"),
        rejected.source.id,
    )
```

`CommandReceipt.Rejected` names the command (`commandId`, `commandType`, `ref`), its `error` (code, message, violations, extensions), the saga's `executionId` and its `source`, the aggregate whose commit derived the command; it does not carry the command's body. A rejection from another context comes back as a receipt. The runtime hands each rejection over once.

Each derived command is settled on its own: a rejected command stops none of the others, and each rejection goes to its handler. The commands to one aggregate run in the order the saga derived them; those to different aggregates run concurrently, so work that depends on another command's outcome is not derived beside it: derive it from the event the first command commits. The rejections of the saga's own context's commands that it has no handler for park the aggregate once for the saga's subscription, as a failure would, listing every rejected command. A command to another context asks for a receipt only when the saga has a handler of its type, so the target logs a rejection no handler takes, and it goes no further. A rejection that needs no compensation is accepted by a handler that returns `Unit`:

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#release-rejected -->

```kotlin
/**
 * A release the inventory rejects needs no compensation: it rejects one only for a product it has no stock of,
 * which reserved nothing either. Without this handler, the rejection would park the order for the saga; returning
 * `Unit` accepts it and derives nothing.
 */
@Suppress("UnusedParameter")
@OnRejected
fun onReleaseRejected(rejected: CommandReceipt.Rejected<ReleaseReservation>) = Unit
```

`CommandReceipt.Rejected<*>` and `CommandReceipt.Committed<*>` take every command the saga derives, and `Rejected<*>` the commands it calls too.

## Call another context synchronously

By default a saga derives its commands and the command topic delivers them. When the target being online is a precondition and the saga needs the result now, the handler calls the target's command gateway instead, with `context.call`:

<!-- sample: examples/eshop/basket/basket-domain/src/main/kotlin/me/ahoo/wow/example/eshop/basket/domain/basket/BasketCheckoutSaga.kt#call -->

```kotlin
@OnEvent
@Sends(calls = [SubmitOrder::class])
suspend fun onBasketCheckedOut(event: StateTypedEvent<BasketState, BasketCheckedOut>, context: SagaContext) {
    val checkedOut = event.event
    context.call(
        CommandRequest.of(SubmitOrder(checkedOut.items.map { OrderItem(it.productId, it.unitPrice, it.quantity) })),
    )
}
```

- The handler declares the classes it calls in `@Sends(calls = [...])`. The Manifest lists them, a wait follows the call, and `call` refuses any other class.
- `call` takes a `CommandRequest`, derived as a returned one is, and returns the command's result once it is committed on the target: its deterministic request id makes every retry the same request, which the target decides once. A call waits for the commit, so its request takes no `wait` or `timeout`.
- A creation may name no id, as the checkout's `SubmitOrder` does: the target gives the aggregate its id (the ordering numbers the order, `SO-261031-42`), and the result names it. A retry or a replay is the same request, answered with the same aggregate, and takes no other id.
- A refusal throws `CommandRejectedException`, a business outcome that is not retried. A handler that lets it through hands it to the saga's `@OnRejected` handler of the called command, as a derived command's rejection; without one, the aggregate parks at once. A call that fails or reaches no result throws `WowProblemException`, retryable with the same request id: the handler fails and is retried, then parks the aggregate. These are the exceptions a `CommandClient` throws.
- The node reaches the target on its internal port: its nodes are found by DNS from the service its contract names, or at `wow.peers.<context>.url` ([Configure a peer](./calling-services#configure-a-peer-over-http)). A node does not start when a handler calls a context it cannot reach. A command of the saga's own context is derived, never called.
- `call` is a `suspend` function, so a saga written in Java derives its commands instead.

## Schedule a command

`context.schedule(key, rule, request)` asks the request's target to schedule its command for itself under `key`; `context.cancel(key, target)` cancels it. Both record into the context: the runtime sends them after the commands the handler returns, in the order the handler called them. Any handler of the saga may schedule, an `@OnSchedule` job's too. The handler declares the scheduled commands' classes in `@Sends(schedules = [...])`, which the Manifest lists by full type name. An aggregate can also schedule commands from its own decision, with the `DecideScope`: [Write an Aggregate](./aggregates#ask-the-kernel-for-more).

## Where it starts

A saga's subscription that has no checkpoint in a partition starts at the end of its own context's log, so deploying it does not act on the history again. One that reads another context's stream starts where its service joins the stream: at the beginning when no other subscription of the service has read it yet, so a service that joins later still gets what was published before, else at the end, so a handler added later does not send commands for events its service read long ago (`JOIN` in the Manifest). `@OnEvent(start = ...)` overrides it, and so does the host's `wow.subscriptions.[<context>.<processor.function>].start`. After its first checkpoint a subscription goes on from there.

## Test it

`sagaSpec` hands a saga committed events, receipts and rejections, and checks the commands it derives: [Test Domain Code](./testing#sagas).

## Next

- [Publish and Consume Streams](./published-streams): read another context's events in a saga.
