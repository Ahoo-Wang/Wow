# Wow Framework Annotations Reference

## Core Annotations

### @AggregateRoot

Marks a class as an aggregate root. The compiler generates metadata for command handling.

```kotlin
@AggregateRoot(commands = [CreateOrder::class, UpdateOrder::class])
class Order(private val state: OrderState) {
    // Command handlers...
}
```

**Parameters:**
- `commands`: Array of command classes this aggregate handles
- `route`: Optional aggregate route configuration

### @OnCommand

Marks a method as a command handler. Optional if method is named `onCommand`.

```kotlin
@OnCommand(returns = [OrderCreated::class, OrderUpdated::class])
fun onCommand(cmd: CreateOrder): OrderCreated { ... }
```

**Parameters:**
- `returns`: Array of event types this handler can return

**Handler parameter types:**
- Specific command: `AddCartItem`
- Command message: `CommandMessage<AddCartItem>`
- Command exchange: `CommandExchange<AddCartItem>`
- Other parameters are resolved from IOC container

### @OnSourcing

Marks a method as an event sourcing handler. Used in State Aggregates to rebuild state from events. Optional if method is named `onSourcing`.

```kotlin
@OnSourcing
fun onOrderCreated(event: OrderCreated) {
    items = event.items
    status = OrderStatus.CREATED
}

@OnSourcing
fun onSourcing(event: DomainEvent<OrderPaid>) {  // Generic form
    // ...
}
```

**Handler parameter types:**
- Specific event: `CartItemAdded`
- Domain event: `DomainEvent<CartItemAdded>`

### @StatelessSaga

Marks a class as a stateless saga for distributed transaction orchestration.

```kotlin
@StatelessSaga
class TransferSaga {
    @OnEvent
    fun onPrepared(event: Prepared): Entry { ... }
}
```

### @OnEvent

Marks a method as an event handler in Sagas and Projections. Optional if method is named `onEvent`.

```kotlin
@OnEvent
fun onOrderCreated(event: OrderCreated) { ... }

// Listen to specific aggregate
@OnEvent("cart")
fun onCartEvent(event: Any) { ... }
```

**Return types:**
- `null` or `Nothing?` - no command sent
- Single command - `Command`
- `CommandBuilder` - for aggregateId-aware commands
- `List<Command>` - multiple commands
- `Mono<Void>` - async handling in projections

### @ProjectionProcessor

Marks a class as a projection processor for maintaining read models.

```kotlin
@ProjectionProcessor
class OrderProjector {
    fun onEvent(event: OrderCreated) { ... }
    
    fun onStateEvent(event: OrderPaid, state: OrderState) { ... }
}
```

### @Blocking

Marks a method as a blocking operation in projections.

```kotlin
@Blocking
fun onEvent(event: OrderPaid) {
    emailService.sendNotification(...)
}
```

### @Retry

Used in sagas for retry configuration on event handlers.

```kotlin
@Retry(maxRetries = 5, minBackoff = 60, executionTimeout = 10)
fun onEvent(event: DomainEvent<OrderCreated>): CommandBuilder? { ... }
```

**Parameters:**
- `maxRetries`: Maximum number of retry attempts
- `minBackoff`: Minimum backoff time in seconds  
- `executionTimeout`: Timeout for each execution in seconds

## Annotation Naming Convention

Most annotations are **optional** if you follow the naming convention:

| Annotation | Optional if method named |
|------------|-------------------------|
| `@OnCommand` | `onCommand` |
| `@OnEvent` | `onEvent` |
| `@OnSourcing` | `onSourcing` |

## Event Annotations

### @Event

Marks a data class as a domain event.

```kotlin
@Event
data class OrderCreated(
    val orderId: String,
    val items: List<OrderItem>
)

@Event(revision = "2.0")
data class OrderShipped(
    val orderId: String,
    val trackingNumber: String
)
```

**Parameters:**
- `revision`: Version string for event evolution

## Aggregate Patterns

### Simple Aggregation Pattern (Recommended)

Separate Command Aggregate and State Aggregate classes:

```kotlin
// State Aggregate - holds state and handles sourcing
class CartState(val id: String) {
    var items: List<CartItem> = listOf()
        private set
    
    fun onCartItemAdded(event: CartItemAdded) {
        items = items + event.added
    }
}

// Command Aggregate - handles commands
@AggregateRoot
class Cart(private val state: CartState) {
    fun onCommand(cmd: AddCartItem): Any { ... }
}
```

### Complex Aggregation Pattern

Multiple related aggregates sharing a base state:

```kotlin
class OrderState(val id: String) { ... }
class OrderStateA : OrderState(id) { ... }
class OrderStateB : OrderState(id) { ... }

@AggregateRoot
class OrderA(private val state: OrderStateA) { ... }

@AggregateRoot  
class OrderB(private val state: OrderStateB) { ... }
```

### Inheritance Pattern

Command Aggregate inherits from State Aggregate:

```kotlin
abstract class OrderState(val id: String) {
    var items: List<OrderItem> = listOf()
    fun onOrderCreated(e: OrderCreated) { ... }
}

@AggregateRoot
class Order(state: OrderState) : OrderState(state.id) {
    fun onCommand(cmd: CreateOrder): OrderCreated { ... }
}
```

## Command Gateway Annotations

### @StaticTenantId

Marks an aggregate as having a static (non-changeable) tenant ID.

### @AggregateRoute

Configures aggregate routing.

```kotlin
@AggregateRoot(commands = [...])
@AggregateRoute(owner = AggregateRoute.Owner.AGGREGATE_ID)
class Cart(private val state: CartState) { ... }
```

**Owner values:**

| Owner | Description |
|-------|-------------|
| `AGGREGATE_ID` | Route by the aggregate ID (default) |
| `TENANT_ID` | Route by the tenant ID |
| `GROUP` | Route by a named group |

## Configuration Annotations

### @Enabled

Enable/disable components:

```kotlin
@Configuration
@Enabled(properties = ["wow.command.enabled=true"])
class CommandConfiguration { ... }
```
