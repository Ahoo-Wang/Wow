# Wow Testing Patterns Reference

## Test Suite Overview

Wow provides a Given→When→Expect test pattern:
- **Given**: Previous domain events to initialize aggregate state
- **When**: Current command to trigger state changes
- **Expect**: Verify state changes meet expectations

## AggregateSpec

Testing aggregates using Given-When-Expect pattern.

### Basic Structure

```kotlin
class CartSpec : AggregateSpec<Cart, CartState>(
    {
        on {
            // Given
            val ownerId = generateGlobalId()
            givenOwnerId(ownerId)

            // When
            val addCartItem = AddCartItem(productId = "product-1", quantity = 1)
            whenCommand(addCartItem) {
                // Then
                expectNoError()
                expectEventType(CartItemAdded::class)
                expectState {
                    items.assert().hasSize(1)
                }
            }
        }
    }
)
```

### GivenDsl Methods

| Method | Description |
|--------|-------------|
| `givenOwnerId(id)` | Set the aggregate ID |
| `givenEvent(event)` | Initialize with a domain event |
| `givenEvent(events)` | Initialize with multiple events |
| `givenState(state, version)` | Initialize with direct state |
| `inject { register(...) }` | Inject mock services |
| `name("description")` | Name this test scenario |

### WhenDsl Methods

| Method | Description |
|--------|-------------|
| `whenCommand(cmd)` | Execute a command |
| `whenCommand(cmd) { }` | Execute with expectations |

### ExpectDsl Methods

| Method | Description |
|--------|-------------|
| `expectNoError()` | Assert no error occurred |
| `expectError()` | Assert an error occurred |
| `expectErrorType<T>()` | Assert specific error type |
| `expectError(expected: E.() -> Unit)` | Assert error content |
| `expectEventType<T>()` | Assert specific event type was emitted |
| `expectEventBody<T> { }` | Assert event body content |
| `expectEventCount(n)` | Assert number of events |
| `expectEventStream { }` | Assert complete event stream |
| `expectState { }` | Assert aggregate state |
| `expectStateAggregate { }` | Assert aggregate metadata |
| `expect(expected: ExpectedResult<S>.() -> Unit)` | Complete result expectations |

### Branching with fork()

| Method | Description |
|--------|-------------|
| `fork(name) { }` | Create a branch test scenario |
| `fork(ref, name) { }` | Branch from a reference point |
| `fork(ref, name, verifyError) { }` | Branch with error verification |
| `ref("name")` | Mark a verification point |

### Fork Use Cases

- **Sequential Operations**: Order → Payment → Shipping
- **Error Scenarios**: Invalid operations in different states
- **Alternative Paths**: Different command sequences from same point
- **Aggregate Lifecycle**: Deletion, recovery, behavior after deletion
- **Business Rules**: Constraints across state transitions

### Reference Points

Mark points for cross-scenario branching:

```kotlin
whenCommand(CreateOrder(...)) {
    expectEventType(OrderCreated::class)
    ref("order-created")  // Mark this point
    expectState { status.assert().isEqualTo(OrderStatus.CREATED) }
}

// Later, branch from marked point
fork("order-created", "Pay Order") {
    whenCommand(PayOrder(...)) {
        expectEventType(OrderPaid::class)
    }
}
```

### Complete Example with Forks

```kotlin
class OrderSpec : AggregateSpec<Order, OrderState>({
    on {
        val ownerId = generateGlobalId()
        givenOwnerId(ownerId)

        val createOrder = CreateOrder(items, address, false)
        whenCommand(createOrder) {
            expectNoError()
            expectEventType(OrderCreated::class)

            fork("Pay Order") {
                val payOrder = PayOrder(totalAmount)
                whenCommand(payOrder) {
                    expectEventType(OrderPaid::class)
                    expectState { status.assert().isEqualTo(OrderStatus.PAID) }

                    fork("Ship Order") {
                        val shipOrder = ShipOrder(aggregateId.id)
                        whenCommand(shipOrder) {
                            expectEventType(OrderShipped::class)
                        }
                    }
                }
            }

            fork("Ship Before Payment") {
                val shipOrder = ShipOrder(aggregateId.id)
                whenCommand(shipOrder) {
                    expectErrorType(IllegalStateException::class)
                }
            }
        }
    }
})
```

### Injecting Mock Services

```kotlin
on {
    val inventoryService = object : InventoryService {
        override fun getInventory(productId: String) = quantity.toMono()
    }
    val pricingService = object : PricingService {
        override fun getProductPrice(productId: String) = price.toMono()
    }

    inject {
        register(DefaultCreateOrderSpec(inventoryService, pricingService))
    }

    whenCommand(CreateOrder(items, address, false)) {
        expectNoError()
    }
}
```

## SagaSpec

Testing stateless sagas.

### Basic Structure

```kotlin
class TransferSagaSpec : SagaSpec<TransferSaga>({
    on {
        val prepared = Prepared("to", 1)
        whenEvent(prepared) {
            expectNoError()
            expectCommandType(Entry::class)
            expectCommandBody<Entry> {
                id.assert().isEqualTo(prepared.to)
                amount.assert().isEqualTo(prepared.amount)
            }
        }
    }
})
```

### Saga WhenDsl Methods

| Method | Description |
|--------|-------------|
| `whenEvent(event, ownerId)` | Trigger saga with event |
| `name("description")` | Name this test scenario |
| `functionFilter { }` | Filter message functions |
| `functionName("name")` | Filter by function name |

### Saga ExpectDsl Methods

| Method | Description |
|--------|-------------|
| `expectNoError()` | Assert no error occurred |
| `expectNoCommand()` | Assert no command was sent |
| `expectCommandType<T>()` | Assert specific command type was sent |
| `expectCommand<T> { }` | Assert command content |

### Full Saga Example

```kotlin
class CartSagaSpec : SagaSpec<CartSaga>({
    on {
        name("From cart - should remove items")
        val orderItem = OrderItem(...)
        whenEvent(
            event = mockk<OrderCreated> {
                every { items } returns listOf(orderItem)
                every { fromCart } returns true
            },
            ownerId = ownerId
        ) {
            expectCommandType(RemoveCartItem::class)
            expectCommand<RemoveCartItem> {
                body.productIds.assert().contains(orderItem.productId)
            }
        }
    }

    on {
        name("Not from cart - should do nothing")
        whenEvent(
            event = mockk<OrderCreated> {
                every { items } returns listOf(orderItem)
                every { fromCart } returns false
            },
            ownerId = ownerId
        ) {
            expectNoCommand()
        }
    }
})
```

## ProjectionSpec

Testing projection processors for maintaining read models.

### Basic Structure

```kotlin
class OrderProjectorSpec : ProjectionSpec<OrderProjector, OrderState>({
    on {
        val event = OrderCreated(orderId = "order-1", items = listOf)
        whenEvent(event) {
            expectNoError()
            expectState {
                id.assert().isEqualTo("order-1")
            }
        }
    }
})
```

### Projection WhenDsl Methods

| Method | Description |
|--------|-------------|
| `whenEvent(event)` | Trigger projection with event |
| `name("description")` | Name this test scenario |

### Projection ExpectDsl Methods

| Method | Description |
|--------|-------------|
| `expectNoError()` | Assert no error occurred |
| `expectError()` | Assert an error occurred |
| `expectState { }` | Assert projected state |

### Complete Projection Example

```kotlin
class OrderProjectorSpec : ProjectionSpec<OrderProjector, OrderState>({
    on {
        name("OrderCreated - should project state")
        val created = OrderCreated(
            orderId = "order-1",
            items = listOf(OrderItem("product-1", 2))
        )
        whenEvent(created) {
            expectNoError()
            expectState {
                id.assert().isEqualTo("order-1")
                items.assert().hasSize(1)
            }
        }
    }

    on {
        name("OrderPaid - should update status")
        givenEvent(OrderCreated(...))
        val paid = OrderPaid(orderId = "order-1")
        whenEvent(paid) {
            expectNoError()
            expectState {
                status.assert().isEqualTo(OrderStatus.PAID)
            }
        }
    }
})
```

## FluentAssert Assertions

Use `me.ahoo.test.asserts.assert` for assertions:

```kotlin
import me.ahoo.test.asserts.assert

// Primitives
42.assert().isGreaterThan(0)
"hello".assert().startsWith("hel")

// Collections
listOf(1, 2, 3).assert().hasSize(3).contains(2)

// Nested assertions
expectState {
    items.assert().hasSize(1)
    items.first().productId.assert().isEqualTo("product-1")
}
```

## Test Fixtures

### generateGlobalId

Generate a unique aggregate ID for testing:

```kotlin
val ownerId = generateGlobalId()
```

### mockk

Use mockk for mocking events:

```kotlin
import io.mockk.mockk
import io.mockk.every

val mockOrderCreated = mockk<OrderCreated> {
    every { items } returns listOf(orderItem)
    every { fromCart } returns true
}
```

## Default Test Commands/Events

Wow provides built-in commands for testing:

```kotlin
// Delete aggregate
whenCommand(DefaultDeleteAggregate) {
    expectEventType(DefaultAggregateDeleted::class)
}

// Recover deleted aggregate
whenCommand(DefaultRecoverAggregate) { ... }
```

Built-in exceptions:

```kotlin
expectErrorType(IllegalAccessDeletedAggregateException::class)
expectErrorType(IllegalStateException::class)
expectErrorType(DomainEventException::class)
```

## Running Tests

```bash
# Run specific test class
./gradlew test --tests "me.ahoo.wow.example.domain.cart.CartSpec"

# Run with coverage
./gradlew domain:jacocoTestReport

# Verify coverage
./gradlew domain:jacocoTestCoverageVerification
```

## Best Practices

1. **Test Coverage Target**: ≥85% for domain models
2. **Use fork() for related operations** within same scenario
3. **Use separate on {} blocks** for unrelated scenarios
4. **Avoid deep nesting** (>3 levels) - use `ref()` and `fork(ref, ...)`
5. **Use descriptive names** for fork scenarios
6. **Test error cases** - verify behavior in invalid states
