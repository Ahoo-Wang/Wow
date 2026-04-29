---
name: wow
description: |
  Wow framework assistant for building DDD + Event Sourcing + CQRS microservices. Use this skill whenever the user mentions:
  - DDD, Domain-Driven Design, aggregate roots, entities, value objects
  - CQRS, Command Query Responsibility Segregation, read models, write models
  - Event Sourcing, events, event store, snapshots
  - Distributed transactions, saga, orchestration, compensation
  - Projection processors, read model updates, query services
  - Command gateway, wait strategies, command bus
  - Wow framework, wow-project-template
  - Unit testing with Given-When-Expect pattern, AggregateSpec, SagaSpec

  This skill helps create, modify, and test Wow framework components including aggregates, sagas, projections, and command handlers.
compatibility: Kotlin, Spring Boot, Gradle, MongoDB, Kafka
---

# Wow Framework Skill

Wow is a modern reactive CQRS microservice framework based on DDD and Event Sourcing. This skill helps you build Wow-based applications.

## Architecture Overview

```
Write Path: Command → Aggregate → Event → EventStore → EventBus
                                       ↓
Read Path:                     Projection → ReadModel
```

## Module Structure

| Module | Purpose |
|--------|---------|
| `wow-api` | Public annotations and interfaces |
| `wow-core` | Core runtime (commands, events, sagas, projections) |
| `wow-mongo` | MongoDB event store and snapshot support |
| `wow-kafka` | Kafka message bus support |
| `wow-query` | Query DSL and query services |
| `wow-test` | AggregateSpec, SagaSpec, ProjectionSpec |

## Aggregate Root Modeling

Wow uses the **Simple Aggregation Pattern** with separate Command Aggregate and State Aggregate classes.

### Complete Example (Cart Aggregate)

**CartState.kt** - Holds state and handles event sourcing:
```kotlin
class CartState(val id: String) : ICartInfo {
    override var items: List<CartItem> = listOf()
        private set

    fun onCartItemAdded(cartItemAdded: CartItemAdded) {
        items = items + cartItemAdded.added
    }

    fun onCartItemRemoved(cartItemRemoved: CartItemRemoved) {
        items = items.filter { !cartItemRemoved.productIds.contains(it.productId) }
    }

    fun onCartQuantityChanged(cartQuantityChanged: CartQuantityChanged) {
        items = items.map {
            if (it.productId == cartQuantityChanged.changed.productId) {
                cartQuantityChanged.changed
            } else {
                it
            }
        }
    }
}
```

**Cart.kt** - Handles commands and returns events:
```kotlin
@AggregateRoot(commands = [AddCartItem::class, RemoveCartItem::class])
class Cart(private val state: CartState) {

    fun onCommand(command: AddCartItem): Any {
        require(state.items.size < MAX_CART_ITEM_SIZE) {
            "Shopping cart can only add up to [$MAX_CART_ITEM_SIZE] items."
        }
        state.items.firstOrNull {
            it.productId == command.productId
        }?.let {
            return CartQuantityChanged(changed = it.copy(quantity = it.quantity + command.quantity))
        }
        return CartItemAdded(added = CartItem(productId = command.productId, quantity = command.quantity))
    }

    fun onCommand(command: RemoveCartItem): CartItemRemoved {
        return CartItemRemoved(productIds = command.productIds)
    }
}
```

### Key Conventions

1. **Annotations are optional** if naming convention is followed:
   - `@OnCommand` optional if method is named `onCommand`
   - `@OnSourcing` optional if method is named `onSourcing`
   - `@OnEvent` optional if method is named `onEvent`

2. **@AggregateRoot annotation** specifies which commands this aggregate handles:
   ```kotlin
   @AggregateRoot(commands = [CreateOrder::class, UpdateOrder::class])
   ```

3. **Command handler return types**:
   - Single event: return the event directly
   - Multiple events: return `Any` (list of events or single event)
   - Error: throw an exception

4. **Sourcing handler parameters**:
   - Specific event: `onCartItemAdded(event: CartItemAdded)`
   - Generic domain event: `onSourcing(event: DomainEvent<CartItemAdded>)`

### Aggregate Root Annotation Specifying Route

```kotlin
@StaticTenantId
@AggregateRoot(commands = [MountedCommand::class])
@AggregateRoute(owner = AggregateRoute.Owner.AGGREGATE_ID)
class Cart(private val state: CartState) {
    // ...
}
```

## Command Gateway

Send commands and wait for results using wait strategies.

### Basic Usage

```kotlin
val command = CreateAccount(balance = 1000, name = "John").toCommandMessage()

// Wait for command to be processed by aggregate
commandGateway.sendAndWaitForProcessed(command)
    .doOnSuccess { result ->
        if (result.succeeded) {
            println("Command processed! Version: ${result.aggregateVersion}")
        }
    }
```

### Wait Strategy Methods

| Method | Description |
|--------|-------------|
| `sendAndWaitForSent(command)` | Wait until command is published to bus |
| `sendAndWaitForProcessed(command)` | Wait until aggregate processes command |
| `sendAndWaitForSnapshot(command)` | Wait until snapshot is created |

### WaitStrategy API

```kotlin
WaitingForStage.sent(commandId)
WaitingForStage.processed(commandId)
WaitingForStage.snapshot(commandId)
WaitingForStage.projected(waitCommandId, contextName, processorName, functionName)
WaitingForStage.eventHandled(...)
WaitingForStage.sagaHandled(...)
```

### Error Handling

```kotlin
commandGateway.sendAndWaitForProcessed(command)
    .doOnError { error ->
        when (error) {
            is CommandResultException -> {
                val result = error.commandResult
                println("Error: ${result.errorCode} - ${result.errorMsg}")
                result.bindingErrors.forEach {
                    println("Field ${it.name}: ${it.msg}")
                }
            }
            is CommandValidationException -> { ... }
            is DuplicateRequestIdException -> { ... }
        }
    }
```

## Saga Pattern

Use stateless saga for distributed transaction orchestration.

### Complete Saga Example

```kotlin
@StatelessSaga
class CartSaga {

    @Retry(maxRetries = 5, minBackoff = 60, executionTimeout = 10)
    fun onEvent(event: DomainEvent<OrderCreated>): CommandBuilder? {
        val orderCreated = event.body
        if (!orderCreated.fromCart) {
            return null
        }
        return RemoveCartItem(
            productIds = orderCreated.items.map { it.productId }.toSet(),
        ).commandBuilder().aggregateId(event.ownerId)
    }
}
```

### Saga Return Types

```kotlin
// Return null - no command sent
fun onEvent(event: NoOpEvent): Nothing? = null

// Single command
fun onEvent(event: Event1): Command1 { ... }

// Return CommandBuilder for aggregateId-aware commands
fun onEvent(event: Event): CommandBuilder {
    return SomeCommand(...).commandBuilder().aggregateId(event.aggregateId)
}

// Multiple commands
fun onEvent(event: Event): List<Command> { ... }
```

### @Retry Annotation (for Sagas)

```kotlin
@Retry(maxRetries = 5, minBackoff = 60, executionTimeout = 10)
fun onEvent(event: DomainEvent<OrderCreated>): CommandBuilder? {
    // ...
}
```

### State Event Handler

Sagas can access aggregate state with `onStateEvent`:

```kotlin
fun onStateEvent(orderCreated: OrderCreated, state: OrderState): Mono<Void> {
    // Access both event and current state
    return Mono.empty()
}
```

## Projection Processor

Maintain read models for CQRS.

### Basic Projection

```kotlin
@ProjectionProcessor
class OrderProjector {

    @Blocking
    fun onEvent(orderCreated: OrderCreated) {
        // Synchronous handling
    }

    fun onStateEvent(orderCreated: OrderCreated, state: OrderState): Mono<Void> {
        // Access full aggregate state
        return Mono.empty()
    }

    fun onEvent(orderPaid: OrderPaid): Mono<Void> {
        return Mono.empty()
    }
}
```

### Key Projection Patterns

1. **@Blocking** - marks synchronous blocking operations
2. **onStateEvent(event, state)** - access both event and full aggregate state
3. **Return Mono<Void>** - reactive handling for async operations

## Query Service DSL

Query read models using the Query DSL.

### Query Functions

```kotlin
// Single result
singleQuery {
    where { "status" eq "active" }
    projection { "id", "name" }
    orderBy { "createdAt" desc }
    limit(10)
}.query(queryService)

// List of results
listQuery {
    where { "age" gt 18 }
    orderBy { "name" asc }
    offset(0)
    limit(20)
}.query(queryService)

// Paged query
pagedQuery {
    where { "tenantId" eq tenantId }
    page(1)
    pageSize(20)
}.query(queryService)
```

### Condition DSL

```kotlin
condition {
    deleted(DeletionState.ALL)
    tenantId(tenantId)
    and {
        "status" eq "ACTIVE"
        "amount" gt 100
    }
    or {
        "type" eq "VIP"
        "type" eq "PREMIUM"
    }
}
```

### Operators

| Operator | Description |
|----------|-------------|
| `eq` / `ne` | Equals / Not equals |
| `gt` / `lt` / `gte` / `lte` | Comparison |
| `contains` | String contains |
| `isIn` / `notIn` | In list / not in list |
| `between` | Range |
| `startsWith` / `endsWith` | String prefix/suffix |
| `isNull()` / `notNull()` | Null check |
| `isTrue()` / `isFalse()` | Boolean check |
| `today()` / `thisWeek()` / `thisMonth()` / `lastMonth()` | Date ranges |
| `recentDays(n)` / `earlierDays(n)` | Relative date ranges |

## Testing

Wow provides `AggregateSpec` and `SagaSpec` for Given-When-Expect testing.

### AggregateSpec Example

```kotlin
class CartSpec : AggregateSpec<Cart, CartState>(
    {
        on {
            val ownerId = generateGlobalId()
            val addCartItem = AddCartItem(productId = "productId", quantity = 1)
            givenOwnerId(ownerId)
            whenCommand(addCartItem) {
                expectNoError()
                expectEventType(CartItemAdded::class)
                expectState { items.assert().hasSize(1) }
                expectStateAggregate { ownerId.assert().isEqualTo(ownerId) }

                fork(name = "Remove CartItem") {
                    val removeCartItem = RemoveCartItem(productIds = setOf(addCartItem.productId))
                    whenCommand(removeCartItem) {
                        expectEventType(CartItemRemoved::class)
                        expectState { items.assert().isEmpty() }
                    }
                }
            }
        }
    }
)
```

### SagaSpec Example

```kotlin
class CartSagaSpec : SagaSpec<CartSaga>({
    on {
        val ownerId = generateGlobalId()
        val orderItem = OrderItem(
            id = generateGlobalId(),
            productId = generateGlobalId(),
            price = BigDecimal(10),
            quantity = 10,
        )
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
})
```

### Testing DSL Reference

**AggregateSpec DSL:**

| Method | Description |
|--------|-------------|
| `givenOwnerId(id)` | Set aggregate ID |
| `givenEvent(event)` | Initialize with domain events |
| `givenState(state, version)` | Initialize with direct state |
| `inject { register(...) }` | Inject mock services |
| `whenCommand(cmd)` | Execute command |
| `expectNoError()` | Assert no error |
| `expectErrorType<T>()` | Assert error type |
| `expectEventType<T>()` | Assert event type |
| `expectEventBody<T> { }` | Assert event body content |
| `expectState { }` | Assert aggregate state |
| `expectStateAggregate { }` | Assert aggregate metadata |
| `expectEventCount(n)` | Assert number of events |
| `fork(name) { }` | Branch test scenario |
| `ref("name")` | Mark verification point |

**SagaSpec DSL:**

| Method | Description |
|--------|-------------|
| `whenEvent(event, ownerId)` | Trigger saga with event |
| `expectCommandType<T>()` | Assert command type sent |
| `expectCommand<T> { }` | Assert command content |
| `expectNoCommand()` | Assert no command sent |

## Event Modeling

### Event Naming Convention

```kotlin
// Good - past tense + specific behavior
@Event
data class OrderCreated(
    val orderId: String,
    val customerId: String,
    val items: List<OrderItem>,
    val totalAmount: BigDecimal
)

@Event
data class OrderPaid(
    val orderId: String,
    val paidAmount: BigDecimal,
    val paymentId: String
)

// Avoid - vague naming
data class OrderUpdated(val orderId: String)  // What changed?
```

### Event Revision

```kotlin
@Event(revision = "2.0")
data class OrderShipped(
    val orderId: String,
    val trackingNumber: String,
    val shippedAt: Instant
)
```

## Project Structure

```
src/main/kotlin/
├── domain/
│   ├── cart/
│   │   ├── Cart.kt              # Command aggregate
│   │   ├── CartState.kt         # State aggregate
│   │   ├── CartItem.kt         # Value object
│   │   └── CartSaga.kt         # Saga (optional)
│   ├── order/
│   │   ├── Order.kt
│   │   └── OrderState.kt
│   └── projection/
│       └── OrderProjector.kt    # Projection processor
└── api/
    ├── command/
    │   └── AddCartItem.kt      # Command
    └── event/
        └── CartItemAdded.kt     # Event
```

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("me.ahoo.wow:wow-api")
    implementation("me.ahoo.wow:wow-core")
    implementation("me.ahoo.wow:wow-mongo")    // MongoDB support
    implementation("me.ahoo.wow:wow-kafka")     // Kafka support
    implementation("me.ahoo.wow:wow-query")     // Query DSL

    testImplementation("me.ahoo.wow:wow-test")  // Test utilities
}
```

## Best Practices

1. **Simple Aggregation Pattern**: Use separate Command Aggregate and State Aggregate classes
2. **Keep Aggregates Small**: One aggregate per transaction boundary
3. **Saga for Cross-Aggregate Workflows**: Use stateless saga for distributed transactions
4. **Event Naming**: Past tense + specific business action (OrderCreated, not OrderUpdate)
5. **Use Projections for Read Models**: Separate read/write concerns with projection processors
6. **@Blocking for Sync Operations**: Mark blocking projection handlers
7. **@Retry for Resilient Sagas**: Handle transient failures in saga event handlers

## References

### Internal References

| Reference | When to Use |
|-----------|-------------|
| `references/annotations.md` | Annotation parameters, @Retry, @Event revision, naming conventions, all aggregate patterns |
| `references/dsl.md` | Complete Query DSL operators (100+), pagination, nested queries, date operators, query rewriting |
| `references/testing.md` | AggregateSpec/SagaSpec DSL, fork/ref patterns, FluentAssert, mockk usage, default commands |
| `references/modeling.md` | Aggregate patterns: Simple/Complex aggregation, single class pattern, inheritance pattern |
| `references/command-gateway.md` | Wait strategies, idempotency, validation, LocalFirst mode, CommandRewriter |
| `references/prepare-key.md` | Uniqueness constraints for EventSourcing, TTL support, user registration scenarios |

These references are loaded as needed - you don't need to read them proactively.
