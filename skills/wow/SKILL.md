---
name: wow
description: |
  Wow framework assistant for building reactive DDD + Event Sourcing + CQRS microservices in Kotlin/Java on JVM 17+ with Spring Boot.

  Use this skill whenever the user mentions:
  - DDD, Domain-Driven Design, aggregate roots, entities, value objects, bounded context
  - CQRS, Command Query Responsibility Segregation, read models, write models
  - Event Sourcing, events, event store, snapshots, domain events
  - Distributed transactions, saga, orchestration, compensation, stateless saga
  - Projection processors, read model updates, query services, event processors
  - Command gateway, wait strategies, command bus, wait chains
  - Wow framework, wow-project-template, wow-compiler, wow-test, wow-tck
  - AggregateSpec, SagaSpec, AggregateVerifier, SagaVerifier, Given-When-Expect testing pattern
  - Annotations: @AggregateRoot, @OnCommand, @OnSourcing, @OnEvent, @StatelessSaga, @ProjectionProcessor, @EventProcessor, @AfterCommand, @OnError, @OnStateEvent, @Retry, @BoundedContext, @CreateAggregate, @CommandRoute
  - PrepareKey, uniqueness constraints, event compensation, execution failed
  - Multi-tenancy, optimistic concurrency, aggregate lifecycle, soft delete/recover
  - Kotlin microservice, reactive programming, Project Reactor, WebFlux

  Also trigger when the user asks about designing a domain model, building an event-sourced aggregate, implementing a saga, creating a projection, writing Wow tests, configuring a Wow application, or any task that involves creating/modifying Kotlin files in a Wow-based project. Use this skill even if the user doesn't explicitly say "Wow" but is working in a project with Wow dependencies or Wow-style code patterns (aggregates with state classes, @AggregateRoot, command/event data classes).
compatibility: Kotlin 2.3, JVM 17+, Spring Boot 4.x, Gradle, MongoDB, Kafka, Reactor
---

# Wow Framework Skill

Wow is a modern reactive CQRS microservice framework based on DDD and Event Sourcing. This skill helps you build Wow-based applications.

## Architecture Overview

```
Write Path: Command → Aggregate → Event → EventStore → EventBus
                                       ↓
Read Path:                     Projection → ReadModel
Saga Path:                     EventBus → Saga → CommandBus
```

## Module Structure

| Module | Purpose |
|--------|---------|
| `wow-api` | Public annotations and interfaces (`@AggregateRoot`, `@OnCommand`, `@Event`, etc.) |
| `wow-core` | Core runtime (aggregates, command bus, event sourcing, projections, sagas) |
| `wow-compiler` | KSP processor — generates command routing, event handling metadata, OpenAPI specs |
| `wow-test` | `AggregateSpec`, `SagaSpec`, `AggregateVerifier`, `SagaVerifier` — Given-When-Expect testing |
| `wow-mongo` | MongoDB event store and snapshot support |
| `wow-kafka` | Kafka command/event bus |
| `wow-query` | Query DSL and query services |
| `wow-spring-boot-starter` | Auto-configuration with feature capabilities |

## Aggregate Root Modeling

Wow uses the **Aggregate Pattern** with separate Command Aggregate and State Aggregate classes.

**State Aggregate** — holds state and handles event sourcing:
```kotlin
class CartState(val id: String) {
    var items: List<CartItem> = listOf()
        private set

    fun onCartItemAdded(event: CartItemAdded) {
        items = items + event.added
    }
}
```

**Command Aggregate** — handles commands and returns events:
```kotlin
@AggregateRoot(commands = [AddCartItem::class, RemoveCartItem::class])
class Cart(private val state: CartState) {

    fun onCommand(command: AddCartItem): Any {
        require(state.items.size < MAX_CART_ITEM_SIZE) {
            "Shopping cart can only add up to [$MAX_CART_ITEM_SIZE] items."
        }
        state.items.firstOrNull { it.productId == command.productId }?.let {
            return CartQuantityChanged(changed = it.copy(quantity = it.quantity + command.quantity))
        }
        return CartItemAdded(added = CartItem(productId = command.productId, quantity = command.quantity))
    }
}
```

### Key Conventions

1. **Annotations are optional** if naming convention is followed: `onCommand`, `onSourcing`, `onEvent`
2. **@AggregateRoot(commands = [...])** specifies which commands this aggregate handles
3. **Command handler return types**: single event, `Any` (polymorphic), or `Iterable` (multiple events)
4. **@OnCommand(returns = [...])** is required when return type is `Any` or `Iterable`
5. **Setter accessors on state** must be `private set` — state is only mutated through sourcing

### Lifecycle Hooks

```kotlin
@AfterCommand(include = [CreateOrder::class])
fun afterCreateOrder(exchange: ServerCommandExchange<*>): AdditionalEvent? { ... }

@OnError
fun onError(command: CreateOrder, error: Throwable) { ... }
```

### Other Aggregate Patterns

- **Complex Aggregation**: Multiple command aggregates sharing a base state class
- **Single Class**: Command + state in one class (avoid — violates event sourcing principles)
- **Inheritance**: Command aggregate inherits from state aggregate

## Saga Pattern

Stateless saga for distributed transaction orchestration.

```kotlin
@StatelessSaga
class CartSaga {
    @Retry(maxRetries = 5, minBackoff = 60, executionTimeout = 10)
    fun onEvent(event: DomainEvent<OrderCreated>): CommandBuilder? {
        val orderCreated = event.body
        if (!orderCreated.fromCart) return null
        return RemoveCartItem(
            productIds = orderCreated.items.map { it.productId }.toSet(),
        ).commandBuilder().aggregateId(event.ownerId)
    }
}
```

### Saga Return Types

| Return Type | Behavior |
|---|---|
| `null` | No command sent |
| Command body | Wrapped into `CommandMessage` and sent |
| `CommandBuilder` | Fine-grained control over aggregateId, tenantId |
| `CommandMessage<*>` | Sent directly |
| `Iterable` of above | Multiple commands per event |
| `Mono<Void>` / `Mono.empty()` | Reactive no-op |

## Event & Projection Processors

### Event Processor

Handles domain events for cross-aggregate operations (notifications, external integrations):

```kotlin
@EventProcessor
class OrderEventProcessor(private val notificationService: NotificationService) {
    @OnEvent
    fun onOrderCreated(event: OrderCreated): Mono<Void> {
        return notificationService.sendOrderConfirmation(event.orderId)
    }
}
```

### Projection Processor

Maintains read models for CQRS:

```kotlin
@ProjectionProcessor
class OrderProjector(private val repository: OrderSummaryRepository) {
    fun onEvent(orderCreated: OrderCreated): Mono<Void> {
        return repository.save(OrderSummary.from(orderCreated))
    }

    fun onStateEvent(event: OrderPaid, state: OrderState): Mono<Void> {
        // Access both event and full aggregate state
    }

    @Blocking
    fun onEvent(orderShipped: OrderShipped) {
        emailService.sendNotification(...)
    }
}
```

## Command Gateway

```kotlin
val command = CreateAccount(balance = 1000, name = "John").toCommandMessage()

// Convenience methods
commandGateway.sendAndWaitForSent(command)
commandGateway.sendAndWaitForProcessed(command)
commandGateway.sendAndWaitForSnapshot(command)

// Wait strategies
WaitingForStage.sent(commandId)
WaitingForStage.processed(commandId)
WaitingForStage.snapshot(commandId)
WaitingForStage.projected(waitCommandId, contextName, processorName, functionName)
WaitingForStage.sagaHandled(...)

// Chain strategy — wait for saga + downstream processing
val waitStrategy = SimpleWaitingForChain.chain(tailStage = CommandStage.SNAPSHOT, ...)
commandGateway.sendAndWait(message, waitStrategy)
```

## Query DSL

```kotlin
singleQuery {
    condition { "status" eq "active" }
    projection { include("id", "name") }
    sort { "createdAt".desc() }
}.query(queryService)

pagedQuery {
    condition { tenantId(tenantId); "status" eq "ACTIVE" }
    pagination { index(1); size(20) }
}.query(queryService)
```

## Testing

### AggregateSpec

```kotlin
class CartSpec : AggregateSpec<Cart, CartState>({
    on {
        val ownerId = generateGlobalId()
        givenOwnerId(ownerId)
        whenCommand(AddCartItem(productId = "p1", quantity = 1)) {
            expectNoError()
            expectEventType(CartItemAdded::class)
            expectState { items.assert().hasSize(1) }

            fork("Remove item") {
                whenCommand(RemoveCartItem(productIds = setOf("p1"))) {
                    expectEventType(CartItemRemoved::class)
                    expectState { items.assert().isEmpty() }
                }
            }
        }
    }
})
```

### SagaSpec

```kotlin
class CartSagaSpec : SagaSpec<CartSaga>({
    on {
        whenEvent(
            event = mockk<OrderCreated> { every { fromCart } returns true },
            ownerId = ownerId
        ) {
            expectCommandType(RemoveCartItem::class)
            expectCommand<RemoveCartItem> {
                aggregateId.id.assert().isEqualTo(ownerId)
            }
        }
    }
})
```

### AggregateVerifier (Fluent API)

```kotlin
Cart::class.java.aggregateVerifier<Cart, CartState>()
    .given()
    .whenCommand(AddCartItem(productId = "p1", quantity = 1))
    .expectEventType(CartItemAdded::class)
    .expectState { items.assert().hasSize(1) }
    .verify()

// Or using reified generics:
aggregateVerifier<Cart, CartState>()
    .given()
    .whenCommand(addCartItem)
    .expectNoError()
    .verify()
```

### SagaVerifier (Fluent API)

```kotlin
sagaVerifier<CartSaga>()
    .whenEvent(mockOrderCreatedEvent)
    .expectNoCommand()
    .verify()
```

## Aggregate Lifecycle

- **Creation**: `@CreateAggregate` command, version starts at 0
- **Command Processing**: Validate → Execute → Source → Persist
- **Deletion**: `DefaultDeleteAggregate` → soft delete (`deleted = true`)
- **Recovery**: `DefaultRecoverAggregate` → restore active state
- **Serial Processing**: Only one command per aggregate at a time (CommandState: STORED → SOURCED → STORED)
- **Optimistic Concurrency**: `aggregateVersion` on commands for conflict detection

## Bounded Context

```kotlin
@BoundedContext(
    name = "example",
    alias = "ex",
    aggregates = [
        BoundedContext.Aggregate(name = "order"),
        BoundedContext.Aggregate(name = "cart")
    ]
)
object ExampleBoundedContext
```

## Multi-Tenancy

Every `AggregateId` includes `tenantId`:
- `@StaticTenantId` — fixed tenant on aggregate
- `@TenantId` on command parameter — extract from command body
- `@AggregateRoute(owner = ...)` — ownership validation (NEVER, ALWAYS, AGGREGATE_ID)

## PrepareKey (Uniqueness Constraints)

For application-level uniqueness in EventSourcing:

```kotlin
@PreparableKey(name = "username_idx")
interface UsernamePrepare : PrepareKey<String>

@AggregateRoot
class User(private val state: UserState) {
    fun onRegister(register: Register, usernamePrepare: UsernamePrepare): Mono<Registered> {
        return usernamePrepare.usingPrepare(key = register.username, value = ...) {
            require(it) { "username already registered." }
            Registered(...).toMono()
        }
    }
}
```

## Event Compensation

Failed event handlers are tracked as `ExecutionFailed` aggregates with automatic retry:
- `@Retry` annotation configures maxRetries, minBackoff, executionTimeout
- `@Retry(enabled = false)` to disable for specific handlers
- Exponential backoff: `nextRetryAt = now + minBackoff * 2^retries`
- Compensation dashboard for monitoring and manual intervention

## Project Structure

```
src/main/kotlin/
├── domain/
│   ├── cart/
│   │   ├── Cart.kt              # Command aggregate
│   │   ├── CartState.kt         # State aggregate
│   │   ├── CartSaga.kt          # Saga (optional)
│   │   └── CartProjector.kt     # Projection (optional)
│   └── order/
│       ├── Order.kt
│       └── OrderState.kt
└── api/
    ├── command/
    │   └── AddCartItem.kt
    └── event/
        └── CartItemAdded.kt
```

## Dependencies

```kotlin
dependencies {
    implementation("me.ahoo.wow:wow-api")
    implementation("me.ahoo.wow:wow-core")
    implementation("me.ahoo.wow:wow-mongo")
    implementation("me.ahoo.wow:wow-kafka")
    implementation("me.ahoo.wow:wow-query")
    testImplementation("me.ahoo.wow:wow-test")
}
```

## References

| Reference | When to Use |
|-----------|-------------|
| `references/annotations.md` | All annotation parameters, aggregate patterns, command/sourcing/event handler details |
| `references/dsl.md` | Complete Query DSL operators (100+), pagination, nested queries, date operators |
| `references/testing.md` | AggregateSpec/SagaSpec DSL, AggregateVerifier/SagaVerifier fluent API, fork/ref patterns, FluentAssert, mockk |
| `references/modeling.md` | Aggregate patterns: Simple/Complex/Single/Inheritance, bounded context, lifecycle hooks |
| `references/command-gateway.md` | Wait strategies, idempotency, validation, LocalFirst mode, CommandRewriter, WaitingForChain |
| `references/prepare-key.md` | Uniqueness constraints for EventSourcing, TTL support, user registration scenarios |
| `references/configuration.md` | Complete YAML configuration reference for all Wow modules |
