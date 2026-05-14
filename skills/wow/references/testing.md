# Wow Testing Patterns Reference

## Test Suite Overview

Wow provides a Given→When→Expect test pattern:
- **Given**: Previous domain events to initialize aggregate state
- **When**: Current command or event to trigger changes
- **Expect**: Verify results meet expectations

Use `me.ahoo.test.asserts.assert` for assertions — NOT AssertJ's `assertThat()`.

```kotlin
import me.ahoo.test.asserts.assert
```

## AggregateSpec

Testing aggregates using Given-When-Expect pattern.

### Basic Structure

```kotlin
class CartSpec : AggregateSpec<Cart, CartState>(
    {
        on {
            val ownerId = generateGlobalId()
            givenOwnerId(ownerId)

            val addCartItem = AddCartItem(productId = "product-1", quantity = 1)
            whenCommand(addCartItem) {
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

**Fork Use Cases:**
- Sequential Operations: Order → Payment → Shipping
- Error Scenarios: Invalid operations in different states
- Alternative Paths: Different command sequences from same point
- Aggregate Lifecycle: Deletion, recovery, behavior after deletion

### Reference Points

```kotlin
whenCommand(CreateOrder(...)) {
    expectEventType(OrderCreated::class)
    ref("order-created")  // Mark this point
    expectState { status.assert().isEqualTo(OrderStatus.CREATED) }
}

// Branch from marked point
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
                whenCommand(PayOrder(totalAmount)) {
                    expectEventType(OrderPaid::class)
                    expectState { status.assert().isEqualTo(OrderStatus.PAID) }

                    fork("Ship Order") {
                        whenCommand(ShipOrder(aggregateId.id)) {
                            expectEventType(OrderShipped::class)
                        }
                    }
                }
            }

            fork("Ship Before Payment") {
                whenCommand(ShipOrder(aggregateId.id)) {
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

    inject {
        register(DefaultCreateOrderSpec(inventoryService, pricingService))
    }

    whenCommand(CreateOrder(items, address, false)) {
        expectNoError()
    }
}
```

### Testing Delete/Recover

```kotlin
// Delete aggregate
whenCommand(DefaultDeleteAggregate) {
    expectEventType(DefaultAggregateDeleted::class)
}

// Verify deleted state
fork("Cannot operate after delete") {
    whenCommand(SomeCommand(...)) {
        expectErrorType(IllegalAccessDeletedAggregateException::class)
    }
}

// Recover
fork("Recover aggregate") {
    whenCommand(DefaultRecoverAggregate) {
        expectEventType(DefaultAggregateRecovered::class)
    }
}
```

## SagaSpec

Testing stateless sagas with Given-When-Expect pattern.

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
| `expectCommand<T> { }` | Assert full `CommandMessage<T>` (includes `aggregateId`, headers) |
| `expectCommandBody<T> { }` | Assert command body content (`T.() -> Unit`) |

### Conditional Saga Testing

```kotlin
class CartSagaSpec : SagaSpec<CartSaga>({
    on {
        name("From cart - should remove items")
        whenEvent(
            event = mockk<OrderCreated> {
                every { items } returns listOf(orderItem)
                every { fromCart } returns true
            },
            ownerId = ownerId
        ) {
            expectCommandType(RemoveCartItem::class)
            expectCommand<RemoveCartItem> {
                aggregateId.id.assert().isEqualTo(ownerId)
                body.productIds.assert().hasSize(1)
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

### Multiple Event Handlers

```kotlin
class TransferSagaSpec : SagaSpec<TransferSaga>({
    on {
        whenEvent(Prepared("to", 1)) {
            expectCommandType(Entry::class)
            expectCommandBody<Entry> {
                id.assert().isEqualTo("to")
                amount.assert().isEqualTo(1)
            }
        }
    }
    on {
        whenEvent(AmountEntered("sourceId", 1)) {
            expectCommandType(Confirm::class)
        }
    }
    on {
        whenEvent(EntryFailed("sourceId", 1)) {
            expectCommandType(UnlockAmount::class)
        }
    }
})
```

## SagaVerifier (Fluent API)

For programmatic saga testing:

```kotlin
// Standalone with reified generics
sagaVerifier<CartSaga>()
    .whenEvent(mockOrderCreatedEvent)
    .expectNoCommand()
    .verify()

// Extension function on Class
CartSaga::class.java.sagaVerifier()
    .whenEvent(mockOrderCreatedEvent)
    .expectCommandType(RemoveCartItem::class)
    .verify()
```

The verifier pre-configures an in-memory command bus, test validator, and no-op idempotency checker for isolated testing.

## Projection Testing

Projection processors are tested using standard unit testing with MockK, since they are regular Spring components:

```kotlin
class OrderProjectorTest {
    private val repository = mockk<OrderSummaryRepository>()
    private val projector = OrderProjector(repository)

    @Test
    fun `on OrderCreated should create order summary`() {
        val event = OrderCreated(
            orderId = "order-001",
            customerId = "customer-001",
            items = listOf(OrderItem(productId = "prod-001", quantity = 2))
        )
        every { repository.save(any()) } returns Mono.empty()

        val result = projector.onEvent(event).block()

        verify(exactly = 1) { repository.save(any()) }
    }
}
```

## AggregateVerifier (Fluent API)

For programmatic aggregate testing, use extension function on `Class<C>` or standalone function with reified generics:

```kotlin
// Extension function on Class
Cart::class.java.aggregateVerifier<Cart, CartState>()
    .given()
    .whenCommand(addCartItem)
    .expectEventType(CartItemAdded::class)
    .expectState { items.assert().hasSize(1) }
    .verify()

// Standalone with reified generics
aggregateVerifier<Cart, CartState>()
    .given()
    .whenCommand(addCartItem)
    .expectNoError()
    .verify()

// With custom aggregate ID
aggregateVerifier<Cart, CartState>(aggregateId = "cart-123")
    .given()
    .whenCommand(addCartItem)
    .verify()
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

Wow provides built-in commands for testing aggregate lifecycle:

```kotlin
// Delete aggregate
whenCommand(DefaultDeleteAggregate) {
    expectEventType(DefaultAggregateDeleted::class)
}

// Recover deleted aggregate
whenCommand(DefaultRecoverAggregate) {
    expectEventType(DefaultAggregateRecovered::class)
}
```

Built-in exceptions for error assertions:

```kotlin
expectErrorType(IllegalAccessDeletedAggregateException::class)
expectErrorType(CommandExpectVersionConflictException::class)
expectErrorType(IllegalStateException::class)
expectErrorType(DomainEventException::class)
```

## Running Tests

```bash
# Run specific test class
./gradlew test --tests "me.ahoo.wow.example.domain.cart.CartSpec"

# Run with coverage
./gradlew domain:jacocoTestReport

# Verify coverage (80% minimum on domain modules)
./gradlew domain:jacocoTestCoverageVerification
```

## Best Practices

1. **Test Coverage Target**: ≥85% for domain models
2. **Use fork() for related operations** within same scenario
3. **Use separate on {} blocks** for unrelated scenarios
4. **Avoid deep nesting** (>3 levels) — use `ref()` and `fork(ref, ...)`
5. **Use descriptive names** for fork scenarios: `fork("Pay Order")`
6. **Test error cases** — verify behavior in invalid states
7. **Test deletion/recovery** — verify aggregate lifecycle behavior
8. **Use FluentAssert** — `me.ahoo.test.asserts.assert`, not AssertJ's `assertThat()`
