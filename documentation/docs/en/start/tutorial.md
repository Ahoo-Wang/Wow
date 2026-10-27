---
title: 'Tutorial: Two Contexts End to End'
description: Follow a basket checkout into an order across two bounded contexts of the eShop, through an aggregate, a cross-context saga, a projection, a query and the web UI's typed client.
---

# Tutorial: Two Contexts End to End

The [Quickstart](./quickstart) builds one service. A system is several, each a bounded context that owns its aggregates, talking through commands and events. This tutorial follows one flow of the eShop in the Wow repository, a buyer checking out a basket that becomes an order, from the basket context into the ordering context, and back out to a read model and a web page. Every sample is the eShop's own code.

## Run it

From a clone of the Wow repository, with JDK 21, start the eShop's five services in one JVM on the memory backend, their topics kept in process:

```bash
./gradlew :eshop-e2e:eshopLocal
```

Then add an item to Alice's basket and check it out, waiting until the order the checkout submits is committed in the ordering context:

```bash
BUYER=(-H 'Wow-User: alice' -H 'Wow-Owner: alice')
curl -s -X POST localhost:8082/eshop-basket/basket/alice/add-basket-item "${BUYER[@]}" \
  -H 'Content-Type: application/json' -d '{"productId":"mug","unitPrice":8.50,"quantity":2}'
curl -s -X POST localhost:8082/eshop-basket/basket/alice/checkout-basket "${BUYER[@]}" \
  -H 'Content-Type: application/json' -d '{}' \
  -H 'Wow-Wait: committed > handled(eshop-basket/BasketCheckoutSaga.onBasketCheckedOut) > committed(eshop-ordering)'
curl -s localhost:8083/eshop-ordering/order/alice-1/state "${BUYER[@]}"
```

In development the servers read the caller from the `Wow-User` and `Wow-Owner` headers to keep the example short; a real service authenticates it ([Secure a Deployment](../operate/security)). The [eShop README](https://github.com/Ahoo-Wang/Wow/tree/main/example/eshop) runs the services apart, on Kafka and PostgreSQL.

## 1. The basket decides

A command states the shape it must have with Jakarta constraints, checked when it is admitted, before any aggregate sees it; one that breaks them is refused with `wow.command.invalid`:

<!-- sample: example/eshop/basket/basket-api/src/main/kotlin/me/ahoo/wow/example/eshop/basket/api/basket/BasketCommands.kt#add-item -->

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

The basket context's `Basket` aggregate then takes the command, looks at its state, and decides the events. Adding an item checks only what needs the state: that the basket is the caller's own:

<!-- sample: example/eshop/basket/basket-domain/src/main/kotlin/me/ahoo/wow/example/eshop/basket/domain/basket/Basket.kt#add-item -->

```kotlin
@OnCommand(produces = [BasketItemAdded::class])
fun onCommand(command: Command<AddBasketItem>): Decision {
    val owner = command.scope.ownerId
    if (owner != null && owner != command.ref.id) {
        return Decision.reject(Rejections.NOT_OWNER, "The basket ${command.ref.id} is not $owner's.")
    }
    val adding = command.body
    return Decision.events(BasketItemAdded(adding.productId, adding.unitPrice, adding.quantity))
}
```

Checking out refuses an empty basket, names the order it will become, and returns that id to the caller through the command's result:

<!-- sample: example/eshop/basket/basket-domain/src/main/kotlin/me/ahoo/wow/example/eshop/basket/domain/basket/Basket.kt#checkout -->

```kotlin
@Suppress("UnusedParameter") // The command has no body: its type is what the function handles.
@OnCommand(produces = [BasketCheckedOut::class])
fun onCommand(command: CheckoutBasket, scope: DecideScope): Decision {
    if (state.items.isEmpty()) {
        return Decision.reject(Rejections.EMPTY, "The basket is empty.")
    }
    val orderId = "${scope.command.ref.id}-${state.checkouts + 1}"
    scope.putResult(ORDER_ID_RESULT, orderId)
    return Decision.events(BasketCheckedOut(orderId, state.items))
}
```

Neither function writes anything. The kernel appends the events in one commit, at the next version, or refuses the command with its rejection code ([Aggregates](../guide/aggregates)).

## 2. A saga crosses into ordering

The basket context does not create orders: the ordering context owns them. A saga in the basket context handles `BasketCheckedOut` and calls the ordering context's `SubmitOrder`, whose class it knows from the ordering context's `*-api` jar:

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

`context.command` gives the derived command an id derived from the commit, so a retry of the saga sends the same command, and the ordering context answers it as a duplicate instead of creating a second order. `calls = [SubmitOrder::class]` declares the call in the contract, and the service refuses to start when no peer decides it ([Sagas](../guide/sagas)).

In the ordering context, `SubmitOrder`'s constraints were checked on admission, so the `Order` aggregate records the order:

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#submit -->

```kotlin
@OnCommand(produces = [OrderSubmitted::class])
fun onCommand(command: SubmitOrder): Decision = Decision.events(OrderSubmitted(command.items))
```

From there the ordering context's own saga reserves the stock in the inventory context and asks the payment context for the payment, each step a command and the events it commits ([The order flow](https://github.com/Ahoo-Wang/Wow/tree/main/example/eshop#the-order-flow)).

## 3. A read model follows

Every order is queryable from its snapshot without a line of code, but a buyer's order list wants its own shape. A projection follows the ordering context's log and keeps a summary per order, guarded by the version so a replayed commit never moves it back:

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

A projection that fails is retried, then parks its one order in the failure ledger; the others go on ([Projections](../guide/projections)). The summaries can be dropped and rebuilt from the log at any time.

The orders themselves are queried through the snapshot query route, here Alice's paid orders:

```bash
curl -s -X POST localhost:8083/eshop-ordering/order/snapshot/query "${BUYER[@]}" \
  -H 'Content-Type: application/json' \
  -d '{"kind":"list","filter":{"op":"EQ","field":"state.status","value":"PAID"}}'
```

Each caller sees only its own orders: the order took its owner from the command that created it ([Query Snapshots](../guide/queries)).

## 4. The web page waits

The eShop's web UI calls the services through TypeScript clients generated from their Manifests ([Generate a Client](../typescript/generate)). Its checkout sends `CheckoutBasket` with a wait that follows the whole chain, every saga on the way and the commit it leads to, until the payment is recorded on the order, and shows each hop as it arrives:

<!-- typecheck: skip — compiled with the rest of the eShop web UI -->

<!-- sample: example/eshop/web/src/shop/checkout.ts#checkout -->

```ts
/**
 * Checks `basketId` out, waiting for the whole chain as server-sent events:
 * `onProgress` gets each hop's result as it arrives. An admission refusal
 * (an empty basket, say) rejects with its `WowProblemError`; a hop that does
 * not succeed ends the progress with its result as the `failure`, and a chain
 * that ends before its plan does with its last result as `ended`.
 */
export async function checkout(
  clients: ShopClients,
  basketId: string,
  onProgress: (progress: CheckoutProgress) => void,
): Promise<CheckoutProgress> {
  const stream = await clients.basket.basket.commands.checkoutBasketStream(
    basketId,
    {},
    {
      wait: CHECKOUT_TO_PAID.map((step) => step.hop),
      timeout: CHECKOUT_TIMEOUT,
    },
  );
  const results: CommandResult[] = [];
  let progress: CheckoutProgress = { results, done: false };
  const reader = stream.getReader();
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    results.push(value);
    progress = {
      results: [...results],
      orderId: progress.orderId ?? orderIdOf(value),
      failure: value.status === "SUCCEEDED" ? undefined : value,
      ended: value.chainEnded ? value : undefined,
      done: false,
    };
    onProgress(progress);
    if (progress.failure || progress.ended) break;
  }
  progress = { ...progress, done: true };
  onProgress(progress);
  return progress;
}
```

A refusal at admission, an empty basket say, rejects with its problem; a hop that does not succeed ends the progress with its result; and a chain a saga ends early, a declined payment, ends with the hops that will not come ([Send Commands](../typescript/commands)).

## What you have seen

Two contexts, each a service with its own log, wrote nothing outside their aggregates: the routes, the cross-context command, the duplicate check, the read model's retries, the query and the typed client came from the domain model. Next: [Guide](../guide/) for each of these in depth, or [Running Wow](../operate/) to deploy them.
