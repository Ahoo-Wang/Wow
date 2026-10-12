---
name: b75-migrate-v9-to-v10-domain
tags: [behavior, migration, read-only, v9-to-v10, source-rewrite, downstream-application]
runs: 3
max_turns: 10
allowed_tools: [Read, Glob, Grep, Skill]
---

只读规划把下游服务的订单领域模块从 Wow 9.9.0 迁到 V10（源码目标固定为 Wow `v10` 分支 commit 267683da7d7a9773db417ab12b542d6b3546bf33，尚未发布）。给出逐项改写与改写后的关键代码，不改动文件、不迁移数据、不部署。模块现状（Kotlin，KSP 直接用 `wow-compiler`，依赖 `wow-spring-boot-starter`）：

```kotlin
@BoundedContext(name = "example-service", alias = "example",
    aggregates = [BoundedContext.Aggregate("cart", tenantId = TenantId.DEFAULT_TENANT_ID, packageScopes = [AddCartItem::class])])
object ExampleService

@CommandRoute(action = "address", method = CommandRoute.Method.PUT, appendIdPath = CommandRoute.AppendPath.ALWAYS)
data class ChangeAddress(val shippingAddress: ShippingAddress)

@CommandRoute(action = "package", appendIdPath = CommandRoute.AppendPath.ALWAYS, method = CommandRoute.Method.PATCH)
data class ReceiptOrder(@CommandRoute.PathVariable @AggregateId val id: String)

data class OrderCreated(val orderId: String, val items: List<OrderItem>, val address: ShippingAddress, val fromCart: Boolean)

data class OrderItem(val id: String, val productId: String, val price: BigDecimal, val quantity: Int) {
    val totalPrice: BigDecimal get() = price.multiply(quantity.toBigDecimal())
}

@AggregateRoot
@AggregateRoute(resourceName = "sales-order")
@Spaced
@AggregateOwner(OwnerPolicy.ALWAYS)
class Order(private val state: OrderState) {
    fun onCommand(
        command: CommandMessage<CreateOrder>,
        @Name("createOrderSpec") specification: CreateOrderSpec, // Mono 接口，库存不足或价格变化时抛异常
        commandResultAccessor: CommandResultAccessor,
    ): Mono<OrderCreated> { /* require(items 非空)；逐项 specification.require；
        OrderCreated(orderId = command.aggregateId.id, items 的 id 用 GlobalIdGenerator)；
        setCommandResult("totalAmount", ...) */ }

    fun onError(createOrder: CreateOrder, throwable: Throwable, eventStream: DomainEventStream?): Mono<Void> =
        Mono.empty<Void>().also { log.error("onError", throwable) }

    fun onCommand(changeAddress: ChangeAddress): AddressChanged {
        check(state.status == OrderStatus.CREATED) { "cannot modify the address" }
        return AddressChanged(changeAddress.shippingAddress)
    }

    fun onCommand(shipOrder: ServerCommandExchange<ShipOrder>): OrderShipped { check(state.status == OrderStatus.PAID) { "cannot ship" }; return OrderShipped }

    fun onCommand(payOrder: PayOrder): Iterable<*> // 不可支付时返回 OrderPayDuplicated，超付时返回 OrderPaid + OrderOverPaid
}

class OrderState(val id: String) : StatusCapable<OrderStatus>, StateAggregateTagsExtractor<OrderState> {
    lateinit var items: List<OrderItem>; private set
    lateinit var address: ShippingAddress; private set
    fun onSourcing(e: OrderCreated) { /* ... */ }
    private fun onSourcing(e: OrderPaid) { /* ... */ }
    // OrderOverPaid、OrderPayDuplicated 没有 onSourcing
}

@StatelessSaga
class CartSaga {
    @Retry(maxRetries = 5, minBackoff = 60, executionTimeout = 10)
    @OnEvent
    fun onOrderCreated(event: DomainEvent<OrderCreated>): CommandBuilder? = /* fromCart 时向 event.ownerId 的购物车发 RemoveCartItem */
}
```

购物车聚合另有 `@StaticTenantId`。前端用 `Command-Wait-Stage`、`Command-Request-Id`、`Command-Aggregate-Version` 请求头，按 `errorCode`/`errorMsg` 解析错误。生产事件库中已有 9.x 的 `order_created` 事件。
