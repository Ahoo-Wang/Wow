# Test Suite

*Unit testing* is an important means to ensure code quality and meet expected business requirements, but in traditional architectures, unit testing is often a quite difficult task because you need to consider database connections, transaction management, data cleanup, and other issues.

With the _Wow_ framework, you will discover that the test suite based on the _Given->When->Expect_ pattern makes unit testing exceptionally simple.
You only need to focus on whether the domain model meets expectations, without worrying about database connections and other issues.

:::tip
In actual applications, we set the lower threshold for domain model unit test coverage to **85%**, which can be easily achieved.
Without deliberate requirements, developers even voluntarily increase coverage to **95%**.
Therefore, each code commit becomes relaxed and comfortable, because you are confident that your code has been thoroughly tested and truly benefits from unit testing.
:::

In projects of the same R&D level, our testing team found in system _API_ testing that Wow framework-based projects have only **1/3** the number of _BUGs_ compared to traditional architecture projects.

- Given: Previous domain events, used to initialize aggregate root state.
- When: Current command executed, used to trigger aggregate root state changes.
- Expect: Expected results, used to verify whether aggregate root state changes meet expectations.

![Test Coverage](/images/getting-started/test-coverage.png)

## Installation

::: code-group
```kotlin [Gradle(Kotlin)]
testImplementation("me.ahoo.wow:wow-test")
```
```groovy [Gradle(Groovy)]
testImplementation 'me.ahoo.wow:wow-test'
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-test</artifactId>
    <version>${wow.version}</version>
    <scope>test</scope>
</dependency>
```
:::

## Testing Aggregate Roots

Use `AggregateSpec` for comprehensive aggregate testing:

```kotlin
class CartSpec : AggregateSpec<Cart, CartState>(
    {
        on {
            val ownerId = generateGlobalId()
            val addCartItem = AddCartItem(
                productId = "productId",
                quantity = 1,
            )
            givenOwnerId(ownerId)
            whenCommand(addCartItem) {
                expectNoError()
                expectEventType(CartItemAdded::class)
                expectState {
                    items.assert().hasSize(1)
                }
                expectStateAggregate {
                    ownerId.assert().isEqualTo(ownerId)
                }
                fork {
                    val removeCartItem = RemoveCartItem(
                        productIds = setOf(addCartItem.productId),
                    )
                    whenCommand(removeCartItem) {
                        expectEventType(CartItemRemoved::class)
                    }
                }
                fork {
                    whenCommand(DefaultDeleteAggregate) {
                        expectEventType(DefaultAggregateDeleted::class)
                        expectStateAggregate {
                            deleted.assert().isTrue()
                        }

                        fork {
                            whenCommand(DefaultDeleteAggregate) {
                                expectErrorType(IllegalAccessDeletedAggregateException::class)
                            }
                        }
                        fork {
                            whenCommand(DefaultRecoverAggregate) {
                                expectNoError()
                                expectStateAggregate {
                                    deleted.assert().isFalse()
                                }
                                fork {
                                    whenCommand(DefaultRecoverAggregate) {
                                        expectErrorType(IllegalStateException::class)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
)
```

![CartSpec-Results](/images/test-suite/CartSpec-Results.png)

## Testing Saga

Use `SagaSpec` to test stateless Saga behavior:

```kotlin
class CartSagaSpec : SagaSpec<CartSaga>({
    on {
        val ownerId = generateGlobalId()
        val orderItem = OrderItem(
            id = generateGlobalId(),
            productId = generateGlobalId(),
            price = BigDecimal.valueOf(10),
            quantity = 10,
        )
        whenEvent(
            event = mockk<OrderCreated> {
                every {
                    items
                } returns listOf(orderItem)
                every {
                    fromCart
                } returns true
            },
            ownerId = ownerId
        ) {
            expectCommandType(RemoveCartItem::class)
            expectCommand<RemoveCartItem> {
                aggregateId.id.assert().isEqualTo(ownerId)
                body.productIds.assert().hasSize(1)
                body.productIds.assert().first().isEqualTo(orderItem.productId)
            }
        }
    }
    on {
        name("NotFromCart")
        val orderItem = OrderItem(
            id = generateGlobalId(),
            productId = generateGlobalId(),
            price = BigDecimal.valueOf(10),
            quantity = 10,
        )
        whenEvent(
            event = mockk<OrderCreated> {
                every {
                    items
                } returns listOf(orderItem)
                every {
                    fromCart
                } returns false
            },
            ownerId = generateGlobalId()
        ) {
            expectNoCommand()
        }
    }
})
```

![CartSagaSpec-Results](/images/test-suite/CartSagaSpec-Results.png)


## Advanced Scenarios

For complex workflows that include service injection and error handling:

```kotlin
class OrderSpec : AggregateSpec<Order, OrderState>({
    on {
        val ownerId = generateGlobalId()
        val orderItem = CreateOrder.Item(productId = generateGlobalId(), price = BigDecimal.TEN, quantity = 10)

        givenOwnerId(ownerId)

        // Inject mock services
        val inventoryService = object : InventoryService {
            override fun getInventory(productId: String) = orderItem.quantity.toMono()
        }
        val pricingService = object : PricingService {
            override fun getProductPrice(productId: String) = orderItem.price.toMono()
        }

        inject { register(DefaultCreateOrderSpec(inventoryService, pricingService)) }

        whenCommand(CreateOrder(listOf(orderItem), shippingAddress, false)) {
            expectNoError()
            expectEventType(OrderCreated::class)
            expectState { status.assert().isEqualTo(OrderStatus.CREATED) }

            fork("Pay Order") {
                val payOrder = PayOrder(generateGlobalId(), orderItem.price * BigDecimal(orderItem.quantity))
                whenCommand(payOrder) {
                    expectEventType(OrderPaid::class)
                    expectState { status.assert().isEqualTo(OrderStatus.PAID) }

                    fork("Ship Order") {
                        whenCommand(ShipOrder(stateAggregate.aggregateId.id)) {
                            expectEventType(OrderShipped::class)
                            expectState { status.assert().isEqualTo(OrderStatus.SHIPPED) }
                        }
                    }

                    fork("Duplicate Payment") {
                        whenCommand(PayOrder(generateGlobalId(), orderItem.price * BigDecimal(orderItem.quantity))) {
                            expectErrorType(DomainEventException::class)
                            expectEventType(OrderPayDuplicated::class)
                        }
                    }
                }
            }

            fork("Invalid Operation") {
                whenCommand(ShipOrder(stateAggregate.aggregateId.id)) {
                    expectErrorType(IllegalStateException::class)
                    expectState { status.assert().isEqualTo(OrderStatus.CREATED) }
                }
            }
        }
    }
})
```

### Reference Points and Cross-Scenario Branching

Use `ref()` to mark verification points and use `fork(ref, ...)` to branch from them to different test scenarios:

```kotlin
class OrderSpec : AggregateSpec<Order, OrderState>({
    on {
        val orderId = generateGlobalId()
        val createOrder = CreateOrder(/*...*/)

        whenCommand(createOrder) {
            expectEventType(OrderCreated::class)
            ref("order-created")  // 标记此验证点
            expectState { status.assert().isEqualTo(OrderStatus.CREATED) }
        }
    }

    // 在单独的场景中从标记点分支
    fork("order-created", "Pay Order") {
        val payOrder = PayOrder(/*...*/)
        whenCommand(payOrder) {
            expectEventType(OrderPaid::class)
            expectState { status.assert().isEqualTo(OrderStatus.PAID) }
        }
    }

    fork("order-created", "Cancel Order") {
        val cancelOrder = CancelOrder(/*...*/)
        whenCommand(cancelOrder) {
            expectEventType(OrderCancelled::class)
            expectState { status.assert().isEqualTo(OrderStatus.CANCELLED) }
        }
    }
})
```

## API Reference

### AggregateSpec

Specification class for testing aggregates using the Given/When/Expect pattern:

- `AggregateSpec<C, S>(block: AggregateDsl<S>.() -> Unit)`: Constructor that accepts a DSL block

### SagaSpec

Specification class for testing stateless Sagas:

- `SagaSpec<T>(block: StatelessSagaDsl<T>.() -> Unit)`: Constructor that accepts a DSL block
- `on(block: WhenDsl<T>.() -> Unit)`: Define test scenarios

### DSL Interfaces

#### AggregateDsl
- `on(block: GivenDsl<S>.() -> Unit)`: Define complete test scenarios
- `fork(ref: String, name: String = "", verifyError: Boolean = false, block: ForkedVerifiedStageDsl<S>.() -> Unit)`: Create branch test scenarios from previously referenced ExpectStage

#### GivenDsl
- `name(name: String)`: Set the name for this test scenario
- `inject(block: ServiceProvider.() -> Unit)`: Inject services or dependencies
- `givenOwnerId(ownerId: String)`: Set owner ID for the aggregate
- `givenEvent(event: Any, block: WhenDsl<S>.() -> Unit)`: Initialize with domain events
- `givenEvent(events: Array<out Any>, block: WhenDsl<S>.() -> Unit)`: Initialize with multiple events
- `givenState(state: S, version: Int, block: WhenDsl<S>.() -> Unit)`: Initialize with direct state

#### WhenDsl
- `name(name: String)`: Set the name for this test scenario
- `whenCommand(command: Any, header: Header, ownerId: String, block: ExpectDsl<S>.() -> Unit)`: Execute command

#### ExpectDsl
- `expect(expected: ExpectedResult<S>.() -> Unit)`: Define expectations for complete test results
- `expectNoError()`: Assert no errors occurred
- `expectError()`: Assert an error occurred during command processing
- `expectError(expected: E.() -> Unit)`: Define expectations for specific errors that occurred
- `expectErrorType(errorType: KClass<out Throwable>)`: Assert specific error type
- `expectEventType(eventType: KClass<out Any>)`: Assert generated event type
- `expectEvent(expected: DomainEvent<E>.() -> Unit)`: Define expectations for specific domain events
- `expectEventBody(expected: E.() -> Unit)`: Define expectations for domain event body content
- `expectEventCount(expected: Int)`: Define expectations for number of domain events generated
- `expectEventStream(expected: DomainEventStream.() -> Unit)`: Define expectations for complete domain event stream
- `expectEventIterator(expected: EventIterator.() -> Unit)`: Define expectations for iterating domain events
- `expectState(block: S.() -> Unit)`: Verify aggregate state
- `expectState(expected: Consumer<S>)`: Define expectations for aggregate state using Consumer (Java)
- `expectStateAggregate(block: StateAggregate<S>.() -> Unit)`: Verify aggregate metadata
- `ref(ref: String)`: Mark current verification point for subsequent branching
- `fork(name: String = "", verifyError: Boolean = false, block: ForkedVerifiedStageDsl<S>.() -> Unit)`: Create branch test scenarios from current verification state

##### Fork Function Use Cases

The `fork` function tests complex workflows and edge cases by creating independent test branches from verification states:

- **Sequential Operations**: Test multi-step processes like Order Creation → Payment → Shipping
- **Error Scenarios**: Verify behavior when attempting operations in invalid states
- **Alternative Paths**: Test different command sequences from the same starting point
- **Aggregate Lifecycle**: Test deletion, recovery, and behavior after deletion
- **Business Rules**: Verify constraints and business logic across state transitions

**Reference Points and ref():**
The `ref()` method allows marking specific verification points for subsequent branching. Use `AggregateDsl.fork(ref, ...)` to create branches from any previously marked point, enabling complex test flows across different `on` blocks.

**Best Practices:**
- Use descriptive names for fork to clarify test intent
- Use `ref()` to mark important verification points for cross-scenario branching
- Avoid deep nesting (over 3 levels) - use `ref()` and `fork(ref, ...)` for complex branching
- Use fork for related operations, separate `on` blocks for unrelated scenarios

#### StatelessSagaDsl
- `on(block: WhenDsl<T>.() -> Unit)`: Define Saga test scenarios

#### Saga WhenDsl
- `functionFilter(filter: (MessageFunction<*, *, *>) -> Boolean)`: Filter message functions
- `functionName(functionName: String)`: Filter by function name
- `whenEvent(event: Any, state: Any?, ownerId: String, block: ExpectDsl<T>.() -> Unit)`: Trigger Saga with event

#### Saga ExpectDsl
- `expectCommandType(commandType: KClass<out Any>)`: Assert sent command type
- `expectCommand(block: CommandMessage<*>.() -> Unit)`: Verify command content
- `expectNoCommand()`: Assert no commands were sent