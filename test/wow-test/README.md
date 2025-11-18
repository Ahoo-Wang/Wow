# wow-test

[![Build Status](https://github.com/Ahoo-Wang/Wow/workflows/CI/badge.svg)](https://github.com/Ahoo-Wang/Wow/actions)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Maven Central](https://img.shields.io/maven-central/v/me.ahoo.wow/wow-test)](https://central.sonatype.com/artifact/me.ahoo.wow/wow-test)
[![Version](https://img.shields.io/badge/version-6.5.2-blue.svg)](https://github.com/Ahoo-Wang/Wow/releases)

A comprehensive testing library for the WOW framework, providing fluent DSL for testing domain aggregates and stateless sagas using the Given/When/Expect pattern.

## Introduction

The `wow-test` library simplifies testing of domain-driven design components in the WOW framework. It offers:

- **Aggregate Testing**: Test command handling, event production, and state changes
- **Saga Testing**: Test stateless saga behavior and command generation
- **Fluent DSL**: Readable, chainable API for test scenarios
- **Isolation**: In-memory event stores and command buses for isolated testing
- **Dependency Injection**: Support for mocking services and dependencies

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    testImplementation("me.ahoo.wow:wow-test:${wow.version}")
}
```

Or for Maven:

```xml
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-test</artifactId>
    <version>${wow.version}</version>
    <scope>test</scope>
</dependency>
```

## Usage

### Aggregate Testing

Use `AggregateSpec` for comprehensive aggregate testing with the DSL:

```kotlin
import me.ahoo.wow.test.AggregateSpec

class CartSpec : AggregateSpec<Cart, CartState>({
    on {
        val ownerId = generateGlobalId()
        val addCartItem = AddCartItem(productId = "productId", quantity = 1)
        givenOwnerId(ownerId)
        whenCommand(addCartItem) {
            expectNoError()
            expectEventType(CartItemAdded::class)
            expectState { items.assert().hasSize(1) }
            expectStateAggregate { ownerId.assert().isEqualTo(ownerId) }

            fork("Remove Item") {
                val removeCartItem = RemoveCartItem(productIds = setOf(addCartItem.productId))
                whenCommand(removeCartItem) {
                    expectEventType(CartItemRemoved::class)
                    expectState { items.assert().isEmpty() }
                }
            }

            fork("Delete Aggregate", verifyError = false) {
                whenCommand(DefaultDeleteAggregate) {
                    expectEventType(DefaultAggregateDeleted::class)
                    expectStateAggregate { deleted.assert().isTrue() }

                    fork("Access Deleted Aggregate") {
                        whenCommand(addCartItem) {
                            expectErrorType(IllegalAccessDeletedAggregateException::class)
                        }
                    }
                }
            }
        }
    }
})
```

### Saga Testing

Use `SagaSpec` for testing stateless saga behavior:

```kotlin
import me.ahoo.wow.test.SagaSpec

class CartSagaSpec : SagaSpec<CartSaga>({
    on {
        val orderItem = OrderItem(id = generateGlobalId(), productId = generateGlobalId(), price = BigDecimal.TEN, quantity = 10)
        whenEvent(
            event = mockk<OrderCreated> {
                every { items } returns listOf(orderItem)
                every { fromCart } returns true
            },
            ownerId = generateGlobalId()
        ) {
            expectCommandType(RemoveCartItem::class)
            expectCommand<RemoveCartItem> {
                body.productIds.assert().hasSize(1)
                body.productIds.assert().first().isEqualTo(orderItem.productId)
            }
        }
    }
})
```

### Advanced Scenarios

For complex workflows with service injection and error handling:

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

Use `ref()` to mark verification points and `fork(ref, ...)` to branch from them across different test scenarios:

```kotlin
class OrderSpec : AggregateSpec<Order, OrderState>({
    on {
        val orderId = generateGlobalId()
        val createOrder = CreateOrder(/*...*/)

        whenCommand(createOrder) {
            expectEventType(OrderCreated::class)
            ref("order-created")  // Mark this verification point
            expectState { status.assert().isEqualTo(OrderStatus.CREATED) }
        }
    }

    // Branch from the marked point in a separate scenario
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

A specification class for testing aggregates using the Given/When/Expect pattern:

- `AggregateSpec<C, S>(block: AggregateDsl<S>.() -> Unit)`: Constructor taking a DSL block
- `on(block: GivenDsl<S>.() -> Unit)`: Defines a test scenario
- `fork(ref: String, name: String = "", verifyError: Boolean = false, block: ForkedVerifiedStageDsl<S>.() -> Unit)`: Creates branching test scenarios from a previously referenced verification point

### SagaSpec

A specification class for testing stateless sagas:

- `SagaSpec<T>(block: StatelessSagaDsl<T>.() -> Unit)`: Constructor taking a DSL block
- `on(block: WhenDsl<T>.() -> Unit)`: Defines a test scenario

### DSL Interfaces

#### AggregateDsl
- `on(block: GivenDsl<S>.() -> Unit)`: Defines a complete test scenario

#### GivenDsl
- `inject(block: ServiceProvider.() -> Unit)`: Injects services or dependencies
- `givenOwnerId(ownerId: String)`: Sets the owner ID for the aggregate
- `givenEvent(event: Any, block: WhenDsl<S>.() -> Unit)`: Initializes with domain events
- `givenEvent(events: Array<out Any>, block: WhenDsl<S>.() -> Unit)`: Initializes with multiple events
- `givenState(state: S, version: Int, block: WhenDsl<S>.() -> Unit)`: Initializes with direct state

#### WhenDsl
- `whenCommand(command: Any, header: Header, ownerId: String, block: ExpectDsl<S>.() -> Unit)`: Executes a command

#### ExpectDsl
- `expect(expected: ExpectedResult<S>.() -> Unit)`: Defines expectations for the complete test result
- `expectNoError()`: Asserts no errors occurred
- `expectError()`: Asserts that an error occurred during command processing
- `expectError(expected: E.() -> Unit)`: Defines expectations for a specific error that occurred
- `expectErrorType(errorType: KClass<out Throwable>)`: Asserts specific error type
- `expectEventType(eventType: KClass<out Any>)`: Asserts event type produced
- `expectEvent(expected: DomainEvent<E>.() -> Unit)`: Defines expectations for a specific domain event
- `expectEventBody(expected: E.() -> Unit)`: Defines expectations for a domain event's body content
- `expectEventCount(expected: Int)`: Defines expectations for the number of domain events produced
- `expectEventStream(expected: DomainEventStream.() -> Unit)`: Defines expectations for the complete domain event stream
- `expectEventIterator(expected: EventIterator.() -> Unit)`: Defines expectations for iterating through domain events
- `expectState(block: S.() -> Unit)`: Validates aggregate state
- `expectState(expected: Consumer<S>)`: Defines expectations for the aggregate's state using a Consumer (Java)
- `expectStateAggregate(block: StateAggregate<S>.() -> Unit)`: Validates aggregate metadata
- `ref(ref: String)`: Marks the current verification point with a reference name for later branching
- `fork(name: String = "", verifyError: Boolean = false, block: ForkedVerifiedStageDsl<S>.() -> Unit)`: Creates branching test scenarios from the current verified state

##### Fork Function Usage Scenarios

The `fork` function enables testing complex workflows and edge cases by creating independent test branches from a verified state:

- **Sequential Operations**: Test multi-step processes like order creation → payment → shipping
- **Error Scenarios**: Verify behavior when operations are attempted in invalid states
- **Alternative Paths**: Test different command sequences from the same starting point
- **Aggregate Lifecycle**: Test deletion, recovery, and post-deletion behavior
- **Business Rules**: Validate constraints and business logic across state transitions

**Reference Points with ref():**
The `ref()` method allows marking specific verification points for later branching. Use `AggregateDsl.fork(ref, ...)` to create branches from any previously marked point, enabling complex test flows across different `on` blocks.

**Best Practices:**
- Use descriptive names for forks to clarify test intent
- Use `ref()` to mark important verification points for cross-scenario branching
- Avoid deep nesting (more than 3 levels) - use `ref()` and `fork(ref, ...)` for complex branching instead
- Use forks for related operations, separate `on` blocks for unrelated scenarios

#### StatelessSagaDsl
- `on(block: WhenDsl<T>.() -> Unit)`: Defines a saga test scenario

#### Saga WhenDsl
- `functionFilter(filter: (MessageFunction<*, *, *>) -> Boolean)`: Filters message functions
- `functionName(functionName: String)`: Filters by function name
- `whenEvent(event: Any, state: Any?, ownerId: String, block: ExpectDsl<T>.() -> Unit)`: Triggers saga with event

#### Saga ExpectDsl
- `expectCommandType(commandType: KClass<out Any>)`: Asserts command type sent
- `expectCommand(block: CommandMessage<*>.() -> Unit)`: Validates command content
- `expectNoCommand()`: Asserts no commands were sent

## Examples

See the [example module](../../example/) for comprehensive test examples.