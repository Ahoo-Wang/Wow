---
title: Write an Aggregate
description: Write an aggregate's commands, its command functions and its state; take domain services, create aggregates, add results, reserve keys and schedule commands from a decision.
outline: deep
---

# Write an Aggregate

An aggregate is the unit that decides: it takes one command at a time, looks at its own state, and decides the events. This page writes one, with the eShop's order as the example. [Deciding and Evolving](../concepts/programming-model) explains the model behind it.

## Where the code goes

An aggregate spans two modules of its context:

| Module | Holds | Read by |
| --- | --- | --- |
| `*-api` | The commands and events, plain data classes | The service, and every peer that sends its commands or reads its events |
| `*-domain` | The root class and its state class | The service only |

The compiler reads the domain module at build time and generates the aggregate's model, its binding and its part of the Manifest. Nothing is scanned at run time.

## Write the commands

A command is a data class of the api module. It holds what the caller asks, never who the aggregate is: the id comes from the route or from the saga that sends the command, the tenant, owner and space from the caller, and the expected version from `If-Match`.

<!-- sample: examples/eshop/ordering/ordering-api/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/api/order/OrderCommands.kt#submit-order -->

```kotlin
/**
 * Submits an order of [items]. The basket context sends it when its buyer checks out, with the order's id fixed by the
 * basket. The order belongs to the buyer: a saga's command carries the scope of the basket it is derived from, whose
 * owner is its buyer, and the order takes its owner from the command that creates it. An order has
 * items, each valid ([OrderItem]'s constraints): the gateway checks that before the order decides.
 */
@CreateAggregate
@Summary("Submit an order")
data class SubmitOrder(
    @field:NotEmpty @field:Valid val items: List<OrderItem>,
)
```

`@Summary` gives the command a one-line summary for the Manifest and the OpenAPI document. A command that takes no data is a `data object`, such as `ShipOrder`.

### Check its shape

A rule about the command alone, such as a positive quantity, a name that is not blank or an order that has items, is a Jakarta Bean Validation constraint on the command class, with Kotlin's `@field:` target:

<!-- sample: examples/eshop/basket/basket-api/src/main/kotlin/me/ahoo/wow/example/eshop/basket/api/basket/BasketCommands.kt#add-item -->

```kotlin
/**
 * Adds [quantity] of [productId] at [unitPrice] to the basket, creating the basket on its first item. Adding a product
 * already in the basket adds to its quantity, at the new price. The constraints are the command's shape: the gateway
 * checks them before the basket decides, and answers a violation `wow.command.invalid` (400).
 */
@AllowCreate
@Summary("Add an item to the basket")
data class AddBasketItem(
    val productId: String,
    @field:PositiveOrZero val unitPrice: BigDecimal,
    @field:Positive val quantity: Int,
)
```

The gateway checks the constraints of every command it takes before the aggregate decides: a command from a route, from an in-process `CommandClient`, from a saga or from another context. A violation is answered `wow.command.invalid` (400), and the problem lists each one with its property path (`items[0].quantity`), its constraint (`Positive`) and its message. A nested value is checked when its property is `@field:Valid`, as `SubmitOrder`'s items are. The api module depends on `jakarta.validation:jakarta.validation-api`, and the service needs a Bean Validation provider ([Host on Spring Boot](./spring-boot#what-the-class-path-decides)).

A rule that needs the aggregate's state or the caller stays in the command function, as a rejection with an error code ([Rejections and Error Codes](./rejections)): the order's function below has nothing left to check.

Whether a command may create its aggregate is part of its class:

| The command class | Is accepted when |
| --- | --- |
| `@CreateAggregate` | The aggregate does not exist yet; otherwise `wow.aggregate.exists` |
| `@AllowCreate` | Whether or not the aggregate exists |
| Neither | The aggregate exists; otherwise `wow.aggregate.not-found` |

Each command is served at `POST /<context>/<aggregate>/{id}/<command>`, the command's name in kebab case, as the Manifest lists its routes: `POST /eshop-ordering/order/{id}/submit-order`. A creating command is also served without `{id}`, and the server gives the aggregate its id. Admission first looks for the aggregate the same request id already created and reuses its id, so a creation sent twice creates one aggregate. An aggregate whose id is its owner's, one per owner, serves every command without `{id}` too, as the caller's own, and generates no id ([One aggregate per owner](./ownership#one-aggregate-per-owner)).

## Write the root

The root is a class annotated `@AggregateRoot`, built from its state. Its command functions decide: each takes a command and returns what it decided.

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#submit -->

```kotlin
fun onCommand(command: SubmitOrder): OrderSubmitted = OrderSubmitted(command.items)
```

The compiler finds every non-private function annotated `@OnCommand`, or named `onCommand`. The first parameter is the command; the return value is the decision:

| Returns | Means |
| --- | --- |
| `Decision.events(e1, e2, …)` | These events, appended together as one event stream |
| `Decision.NoOp` | The command is accepted and changes nothing; nothing is written |
| `Decision.reject(code, message)` | The command is refused; nothing is written, and the caller gets the code ([Rejections and Error Codes](./rejections)) |
| An event, or a list of events | These events |
| `Unit`, `null` or an empty list | Nothing changes |

A function that returns `Decision` does not tell the compiler which events it can produce, so it lists them in `@OnCommand(produces = [...])`. The Manifest publishes that list. A function whose return type already names its events, such as an event class or a sealed type of them, does not repeat them.

The aggregate is named after the root class in snake case (`Order` is `order`, `ViewPreferences` is `view_preferences`), or by `@Name`.

## Read the admitted command

A command function that needs what admission learned about the request takes `Command<X>` instead of `X`:

| Member | Holds |
| --- | --- |
| `body` | The command `X` |
| `ref` | The aggregate: its context, name, tenant and id |
| `scope` | The caller's owner and space |
| `headers` | The request facts the host keeps (`wow.http.request-facts`), such as the calling application |
| `causation`, `operator` | The chain the command belongs to, and whose name it is recorded in |

A feedback form that records the application it was sent from reads the request fact:

<!-- sample: fragment -->

```kotlin
fun onCommand(command: Command<SubmitFeedback>): FeedbackSubmitted =
    FeedbackSubmitted(command.body.text, app = command.headers["app_id"])
```

A function need not check that the caller may reach the aggregate: the kernel does it from the scope ([What the kernel checks](./ownership#what-the-kernel-checks)). An aggregate that exists once per owner, such as a basket, takes its owner's id ([One aggregate per owner](./ownership#one-aggregate-per-owner)).

## Take domain services

The parameters after the command are domain services, or the `DecideScope`. A service becomes a parameter of the generated model, and the host binds it once, when it builds the model: it takes the application's only bean of that type, or its primary one. `@Name("…")` on the parameter takes the bean of that name instead, so two parameters of one type can take two beans.

The order takes its payment policy as a service, and schedules its own cancellation with the `DecideScope` once its stock is confirmed:

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#confirm-stock -->

```kotlin
/**
 * Confirms one product. A confirmation the order does not wait for changes nothing: one read again from the
 * stock stream, or one that arrives after another product's shortage cancelled the order (that reservation is
 * released when the inventory hears of the cancellation).
 */
// Decision: the last confirmation produces two events, the item's and the order's.
@OnCommand(
    produces = [OrderStockItemConfirmed::class, OrderStockConfirmed::class],
    schedules = [CancelOrder::class],
)
fun onCommand(command: ConfirmOrderStock, paymentPolicy: PaymentPolicy, scope: DecideScope): Decision {
    val waiting = state.status == OrderStatus.AWAITING_VALIDATION &&
        command.productId in state.products() &&
        command.productId !in state.confirmedProducts
    if (!waiting) {
        return Decision.NoOp
    }
    val confirmed = OrderStockItemConfirmed(command.productId)
    if (state.confirmedProducts + command.productId != state.products()) {
        return Decision.events(confirmed)
    }
    scope.schedule(
        PAYMENT_GRACE_PERIOD,
        ScheduleRule.after(paymentPolicy.gracePeriod),
        CancelOrder(PAYMENT_TIMEOUT)
    )
    return Decision.events(confirmed, OrderStockConfirmed)
}
```

A service may do I/O. A function that blocks its thread, such as one that calls JDBC, is annotated `@Blocking` and runs on a virtual thread. A function may also be `suspend`, or return a Reactor `Mono`, which needs `wow-reactor` on the class path.

## Ask the kernel for more

The `DecideScope` is what a decision can ask of the kernel besides its events:

| Member | Does |
| --- | --- |
| `command` | The command being decided, as `Command<*>` |
| `ids` | The node's id generators, for an id the decision needs |
| `putResult(key, value)` | Adds a value to what the caller is answered once the events are appended. It is not stored: a repeated request is not answered it again |
| `reject(code, message)` | Rejects the command with a code, optionally with `extensions` for the problem ([Rejections](./rejections#reject-with-a-code)) |
| `reserve(namespace, key)` | Reserves a unique key, such as a user name, for this aggregate; `false` when another aggregate holds it. A `ReservationTenancy` argument widens its scope ([Unique Keys](./unique-keys#reserve-a-key)) |
| `release(namespace, key)` | Releases a key this aggregate holds, once the events are appended; with the `ReservationTenancy` it was reserved with |
| `onAppendFailure { … }` | Undoes an outside act when the events are surely not appended ([below](#undo-an-outside-act)) |
| `afterAppend { … }` | Runs once the events are appended; a failure is logged, not raised ([below](#undo-an-outside-act)) |
| `schedule(key, rule, command, target)` | Schedules a command, to this aggregate unless `target` names another. It is written with the events, or not at all |
| `cancelSchedule(key)` | Cancels the schedule this aggregate keeps under `key` |

A checkout answers the caller with the id of the order it submits:

<!-- sample: examples/eshop/basket/basket-domain/src/main/kotlin/me/ahoo/wow/example/eshop/basket/domain/basket/Basket.kt#checkout -->

```kotlin
@Suppress("UnusedParameter") // The command has no body: its type is what the function handles.
fun onCommand(command: CheckoutBasket, scope: DecideScope): BasketCheckedOut {
    if (state.items.isEmpty()) {
        reject(Rejections.EMPTY, "The basket is empty.")
    }
    val orderId = "${scope.command.ref.id}-${state.checkouts + 1}"
    scope.putResult(ORDER_ID_RESULT, orderId)
    return BasketCheckedOut(orderId, state.items)
}
```

The caller reads it from the result's `results`, `{"orderId": "alice-1"}`.

A function that schedules a command declares the command's class in `@OnCommand(schedules = [...])`, as the order does with `CancelOrder::class`: the compiler cannot read the calls in a function's body, and the Manifest lists them by full type name.

## Undo an outside act

A decision may act outside the aggregate before its events are appended: authorize a card, hold a seat in another system. `onAppendFailure` registers how to undo the act, and the kernel runs it when it knows the events were not appended:

<!-- sample: fragment -->

```kotlin
@OnCommand
suspend fun onCommand(command: PayOrder, scope: DecideScope): Decision {
    val authorization = payments.authorize(state.totalAmount, key = scope.command.requestId)
    scope.onAppendFailure { payments.void(authorization) }
    return Decision.events(OrderPaid(authorization.id))
}
```

The compensation runs when:

- the decision refuses the command, in any of [the three ways](./rejections), or appends nothing (`Decision.NoOp`);
- the decide function throws;
- the append meets a version conflict. Each attempt undoes its own act before the command is decided again on the newer state;
- the store refuses the append, or fails in a way that surely wrote nothing;
- the append finds the request already committed by another attempt.

It does not run when the append's outcome is unknown. The kernel first reads the version the append would have taken. Only when that still cannot tell does it answer `wow.command.unknown`, and then the events may be committed. Nor can it run when the process dies. An act that must not outlive a lost command needs a clean-up of its own besides: an expiry in the outside system, or a reconciliation that reads the log.

- **Order.** Compensations run latest first, one after another, and each is awaited, even when the caller has gone away. There is no timeout: a compensation that hangs holds the aggregate's commands, so bound its call.
- **Failure.** A failing compensation does not stop the others. It is logged, or added to the failure it undoes, and the command's answer does not change.
- **Twice.** A command refused on a cached state that the log has moved past is decided again, its first attempt compensated first. The outside act may then be done twice and undone once, so key it on the command, as `requestId` does above.
- **Reserved keys.** `reserve` registers its own compensation: the key the attempt held is released when the events are not appended. When the outcome is unknown, or the process dies, the hold expires after 30 seconds.

`afterAppend` is the other side: it runs once the events are appended, before the caller is answered, in the order the actions were registered. It does not run on a duplicate, nor on any outcome that appended nothing. A failure is logged, and the events stay committed. Subscriptions may see the commit before the action ends, and a process that dies in between skips it. So it suits work that may be lost, such as warming a cache. Work that must follow every commit belongs in a reactor or a saga, which reads the committed events.

## Write the state

The state class holds a sourcing function for each event, annotated `@OnSourcing` or named `onSourcing`. A sourcing function applies one event: it takes no service and does no I/O.

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderState.kt#sourcing -->

```kotlin
fun onSourcing(event: OrderSubmitted) {
    items = event.items
    totalAmount = event.items.fold(BigDecimal.ZERO) { total, item ->
        total + item.unitPrice.multiply(BigDecimal(item.quantity))
    }
    status = OrderStatus.SUBMITTED
}
```

- Every event the aggregate can produce needs a sourcing function, even one that changes nothing.
- A sourcing function that changes the state in place returns nothing. One that returns the state type returns the new state instead, for an immutable state.
- Give every property a value before the first event. The kernel serializes the state as it is, for snapshots and for the published stream.
- A command function never changes the state, it only reads it. The test DSL compares the state before and after each decision and fails the test if it changed.

## Run code after every command

A function annotated `@AfterCommand`, or named `afterCommand`, runs after the command functions it applies to: those in `include`, all of them when `include` is empty, less those in `exclude`. It sees the state the command function saw, and returns events, which are appended after the command's own in the same stream. It does not run when the command is rejected, and it cannot reject. When it fails, the whole command fails and nothing is written.

## Lifecycle commands

Every aggregate also handles five system commands of `me.ahoo.wow.api.lifecycle`: `DeleteAggregate`, `RestoreAggregate`, `TransferOwner`, `TransferSpace` and `ApplyTags`. They change the aggregate's metadata, not its state, and a deleted aggregate refuses every command but `RestoreAggregate`. `@AggregateRoot(lifecycle = [...])` narrows the list; an empty list enables none.

## Test it

Test each command function with `aggregateSpec`: given these events, when this command, expect these events and this state. [Test Domain Code](./testing) shows how.

## Next

- [Rejections and Error Codes](./rejections): declare the codes a command is refused with.
- [Write a Saga](./sagas): derive commands from what an aggregate decided.
