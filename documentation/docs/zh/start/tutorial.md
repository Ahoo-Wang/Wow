---
title: 教程：端到端的两个上下文
description: 跟随一次购物篮结账在 eShop 的两个限界上下文之间变成订单，途经聚合、跨上下文的 Saga、投影、查询和 Web 界面的类型化客户端。
---

# 教程：端到端的两个上下文

[快速开始](./quickstart)构建一个服务。而一个系统由多个服务组成，每个服务都是一个拥有自己聚合的限界上下文，彼此通过命令和事件通信。本教程跟随 Wow 仓库中 eShop 的一条流程：买家对购物篮结账，购物篮变成订单，从 basket 上下文进入 ordering 上下文，再出来到读模型和网页。每个示例都是 eShop 自己的代码。

## 运行它 {#run-it}

在 Wow 仓库的克隆中，用 JDK 21，在一个 JVM 中以内存后端启动 eShop 的五个服务，它们的 topic 保留在进程内：

```bash
./gradlew :eshop-e2e:eshopLocal
```

然后把一件商品加入 Alice 的购物篮并结账，一直等到结账所提交的订单在 ordering 上下文中提交：

```bash
BUYER=(-H 'Wow-User: alice' -H 'Wow-Owner: alice')
curl -s -X POST localhost:8082/eshop-basket/basket/alice/add-basket-item "${BUYER[@]}" \
  -H 'Content-Type: application/json' -d '{"productId":"mug","unitPrice":8.50,"quantity":2}'
curl -s -X POST localhost:8082/eshop-basket/basket/alice/checkout-basket "${BUYER[@]}" \
  -H 'Content-Type: application/json' -d '{}' \
  -H 'Wow-Wait: committed > handled(eshop-basket/BasketCheckoutSaga.onBasketCheckedOut) > committed(eshop-ordering)'
curl -s localhost:8083/eshop-ordering/order/alice-1/state "${BUYER[@]}"
```

为了让示例简短，开发中的服务从 `Wow-User` 和 `Wow-Owner` 请求头读取调用方；真实的服务要认证调用方（[保护部署](../operate/security)）。[eShop README](https://github.com/Ahoo-Wang/Wow/tree/main/example/eshop) 把这些服务分开运行在 Kafka 和 PostgreSQL 上。

## 1. 购物篮做出决定 {#_1-the-basket-decides}

命令用 Jakarta 约束声明它必须具有的形状，这些约束在命令准入时、任何聚合看到它之前检查；违反约束的命令以 `wow.command.invalid` 被拒绝：

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

然后 basket 上下文的 `Basket` 聚合接收这条命令，查看它的状态，并决定事件。添加商品时只检查需要状态的东西：购物篮属于调用方本人：

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

结账拒绝空的购物篮，为它将要变成的订单命名，并通过命令的结果把这个 ID 返回给调用方：

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

两个函数都不写入任何东西。内核在下一个版本以一次提交追加这些事件，或者以拒绝码拒绝这条命令（[编写聚合](../guide/aggregates)）。

## 2. Saga 跨入 ordering {#_2-a-saga-crosses-into-ordering}

basket 上下文不创建订单：订单归 ordering 上下文所有。basket 上下文中的一个 Saga 处理 `BasketCheckedOut`，并调用 ordering 上下文的 `SubmitOrder`，它从 ordering 上下文的 `*-api` jar 得知这个类：

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

`context.command` 为推导出的命令赋予一个从提交推导出的 ID，所以 Saga 重试时发送的是同一条命令，ordering 上下文把它当作重复命令回答，而不会创建第二个订单。`calls = [SubmitOrder::class]` 在契约中声明这次调用，没有对端处理它时，服务拒绝启动（[Saga](../guide/sagas)）。

在 ordering 上下文中，`SubmitOrder` 的约束已在准入时检查过，所以 `Order` 聚合直接记录这个订单：

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#submit -->

```kotlin
@OnCommand(produces = [OrderSubmitted::class])
fun onCommand(command: SubmitOrder): Decision = Decision.events(OrderSubmitted(command.items))
```

从这里开始，ordering 上下文自己的 Saga 在 inventory 上下文中预留库存，并向 payment 上下文请求支付，每一步都是一条命令以及它提交的事件（[订单流程](https://github.com/Ahoo-Wang/Wow/tree/main/example/eshop#the-order-flow)）。

## 3. 读模型随之更新 {#_3-a-read-model-follows}

每个订单都无需一行代码就能从它的快照查询，但买家的订单列表需要自己的形状。一个投影跟随 ordering 上下文的日志，为每个订单维护一份摘要，并以版本作为守卫，让重放的提交永远不会让它倒退：

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

失败的投影会重试，然后把它的那一个订单停放在失败台账中；其他订单照常进行（[投影](../guide/projections)）。摘要随时可以删除并从日志重建。

订单本身通过快照查询路由查询，这里查询 Alice 已支付的订单：

```bash
curl -s -X POST localhost:8083/eshop-ordering/order/snapshot/query "${BUYER[@]}" \
  -H 'Content-Type: application/json' \
  -d '{"kind":"list","filter":{"op":"EQ","field":"state.status","value":"PAID"}}'
```

每个调用方只看到自己的订单：订单从创建它的命令取得了它的所有者（[查询快照](../guide/queries)）。

## 4. 网页等待结果 {#_4-the-web-page-waits}

eShop 的 Web 界面通过从各服务的 Manifest 生成的 TypeScript 客户端调用服务（[生成客户端](../typescript/generate)）。它的结账发送 `CheckoutBasket`，带着一个跟随整条链的等待，即途中的每个 Saga 以及它导向的提交，直到支付记录到订单上，并在每一跳到达时显示它：

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
      wait: {
        plan: CHECKOUT_TO_PAID.map((step) => step.hop),
        timeout: CHECKOUT_TIMEOUT,
      },
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

准入时的拒绝，比如购物篮为空，会带着它的 problem reject；没有成功的一跳会带着它的结果结束进度；被 Saga 提前结束的链，比如支付被拒，会以那些不会到来的跳结束（[发送命令](../typescript/commands)）。

## 你看到了什么 {#what-you-have-seen}

两个上下文，各自是一个有自己日志的服务，在聚合之外什么都没写：路由、跨上下文的命令、重复检查、读模型的重试、查询和类型化客户端都来自领域模型。下一步：在[开发指南](../guide/)中深入了解其中的每一项，或者在[运行 Wow](../operate/) 中部署它们。
