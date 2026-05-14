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

### @OnCommand

Marks a method as a command handler. Optional if method is named `onCommand`.

```kotlin
@OnCommand(returns = [OrderCreated::class, OrderUpdated::class])
fun onCommand(cmd: CreateOrder): OrderCreated { ... }
```

**Parameters:**
- `returns`: Array of event types this handler can return. **Required** when:
  - Return type is `Any` or `Object` (polymorphic returns)
  - A single command can produce multiple different event types
  - The compiler cannot infer the event type from the return statement

**Handler parameter types:**
- Specific command: `AddCartItem`
- Command message: `CommandMessage<AddCartItem>`
- Command exchange: `CommandExchange<AddCartItem>`
- Other parameters are resolved from IOC container (use `@Name` for qualified injection)

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

**Rules:**
- Must be deterministic — same events always produce same state
- Must have no side effects (no external service calls, no writes)
- Applied sequentially in event order

### @AfterCommand

Post-processing hook that executes after the main command handler completes. If the method returns a non-null value, it is appended as an additional domain event.

```kotlin
@AfterCommand
fun afterCreateOrder(exchange: ServerCommandExchange<*>): OrderConfirmed? {
    val result = exchange.getCommandInvokeResult<OrderCreated>()
    return null
}
```

**Filter parameters:**
- `include`: Array of command classes to trigger this hook
- `exclude`: Array of command classes to skip

```kotlin
@AfterCommand(include = [CreateOrder::class], exclude = [CancelOrder::class])
fun onAfterCommand(exchange: ServerCommandExchange<*>): AdditionalEvent? { ... }
```

Multiple `@AfterCommand` functions are supported, with execution order controlled by `@Order`.

### @OnError

Error handler that executes when command processing fails:

```kotlin
@OnError
fun onError(command: CreateOrder, error: Throwable) {
    // Log or publish error event
}
```

Can also accept `eventStream: DomainEventStream?` as a third parameter.

## Event Handler Annotations

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

Marks a method as an event handler in Sagas, Projections, and Event Processors. Optional if method is named `onEvent`.

```kotlin
@OnEvent
fun onOrderCreated(event: OrderCreated) { ... }

// Listen to specific aggregate
@OnEvent("cart")
fun onCartEvent(event: Any) { ... }
```

**Return types:**
| Return Type | Behavior |
|---|---|
| `null` / `Nothing?` | No command sent |
| Command body | Wrapped into `CommandMessage` and sent |
| `CommandBuilder` | Fine-grained control over aggregateId, tenantId |
| `CommandMessage<*>` | Sent directly |
| `Iterable` of above | Multiple commands per event |
| `Mono<Void>` / `Mono.empty()` | Reactive no-op |

### @OnStateEvent

Marks a method as a state-aware event handler. Provides access to both the event and the aggregate state.

```kotlin
@ProjectionProcessor
class OrderProjection {
    fun onStateEvent(event: OrderPaid, state: OrderState) { ... }
    fun onStateEvent(event: OrderPaid, state: ReadOnlyStateAggregate<OrderState>) { ... }
}
```

Optional if method is named `onStateEvent`.

### @ProjectionProcessor

Marks a class as a projection processor for maintaining read models.

```kotlin
@ProjectionProcessor
class OrderProjector {
    fun onEvent(event: OrderCreated) { ... }
    fun onStateEvent(event: OrderPaid, state: OrderState) { ... }
}
```

### @EventProcessor

Marks a class as a general-purpose event processor for cross-aggregate operations (notifications, external integrations).

```kotlin
@EventProcessor
class OrderEventProcessor(
    private val notificationService: NotificationService
) {
    @OnEvent
    fun onOrderCreated(event: OrderCreated): Mono<Void> {
        return notificationService.sendOrderConfirmation(event.orderId)
    }
}
```

### @Blocking

Marks a method as a blocking operation in projections/event processors.

```kotlin
@Blocking
fun onEvent(event: OrderPaid) {
    emailService.sendNotification(...)
}
```

### @Retry

Configures retry and compensation behavior for event handlers.

```kotlin
@Retry(maxRetries = 5, minBackoff = 60, executionTimeout = 10)
fun onEvent(event: DomainEvent<OrderCreated>): CommandBuilder? { ... }
```

**Parameters:**
| Parameter | Type | Default | Description |
|---|---|---|---|
| `enabled` | Boolean | `true` | Set `false` to disable compensation |
| `maxRetries` | Int | `10` | Maximum retry attempts |
| `minBackoff` | Int | `180` | Initial backoff in seconds (grows exponentially: `minBackoff * 2^retries`) |
| `executionTimeout` | Int | `120` | Max time per execution in seconds |
| `recoverable` | Array | `[]` | Exception types that trigger retries |
| `unrecoverable` | Array | `[]` | Exception types that fail immediately |

## Command Annotations

### @CreateAggregate

Marks a command as an aggregate initializer.

```kotlin
@CreateAggregate
data class CreateUserCommand(
    @AggregateId
    val userId: String,
    val email: String
)
```

### @AllowCreate

Permits a command to create an aggregate if it does not already exist.

### @VoidCommand

Marks a command as fire-and-forget (no response expected).

### @CommandRoute

Configures REST route for a command. Used by `wow-compiler` to generate API endpoints.

```kotlin
@CommandRoute(action = "", method = CommandRoute.Method.DELETE, appendIdPath = CommandRoute.AppendPath.ALWAYS)
object DefaultDeleteAggregate : DeleteAggregate
```

## Aggregate Route & Routing

### @AggregateRoute

Configures aggregate REST API routing and ownership.

```kotlin
@AggregateRoot(commands = [...])
@AggregateRoute(
    resourceName = "sales-order",
    spaced = true,
    owner = AggregateRoute.Owner.ALWAYS
)
class Order(private val state: OrderState) { ... }
```

**Parameters:**
| Attribute | Default | Description |
|---|---|---|
| `resourceName` | lowercased class name | Custom API path segment |
| `enabled` | `true` | Set `false` to disable automatic route generation |
| `spaced` | `false` | Space-separate the resource name in URL paths |
| `owner` | `NEVER` | Ownership policy: `NEVER`, `ALWAYS`, or `AGGREGATE_ID` |

Disable route generation entirely:

```kotlin
@AggregateRoot
@AggregateRoute(enabled = false)
class InternalAggregate(val id: String) { ... }
```

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
- `revision`: Version string for event evolution/backward compatibility

## Bounded Context

### @BoundedContext

Declares a bounded context boundary.

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

**Parameters:**
| Parameter | Description |
|---|---|
| `name` | Unique context identifier used for routing |
| `alias` | Shorter reference name |
| `description` | Human-readable purpose |
| `scopes` | Boundary scope identifiers |
| `aggregates` | Array of `@Aggregate` definitions within the context |

## Multi-Tenancy Annotations

### @StaticTenantId

Marks an aggregate as having a static (non-changeable) tenant ID.

### @TenantId

Used on a command parameter to extract tenant from the command body.

## Aggregate Patterns

### Simple Aggregation Pattern (Recommended)

Separate Command Aggregate and State Aggregate classes:

```kotlin
class CartState(val id: String) {
    var items: List<CartItem> = listOf()
        private set
    fun onCartItemAdded(event: CartItemAdded) {
        items = items + event.added
    }
}

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

@AggregateRoot
class OrderA(private val state: OrderStateA) { ... }
```

### Single Class Pattern

Command + state in one class. **Avoid** — violates event sourcing principles by allowing direct state mutation in command handlers.

### Inheritance Pattern

Command aggregate inherits from state aggregate with `private set` on setters.

## Annotation Naming Convention

| Annotation | Optional if method named |
|------------|-------------------------|
| `@OnCommand` | `onCommand` |
| `@OnEvent` | `onEvent` |
| `@OnSourcing` | `onSourcing` |
| `@OnStateEvent` | `onStateEvent` |
| `@OnError` | `onError` |

## Special Built-in Events

The framework automatically handles these events without explicit `@OnSourcing` methods:

| Event | Effect |
|---|---|
| `AggregateDeleted` | Sets `deleted = true` |
| `AggregateRecovered` | Sets `deleted = false` |
| `OwnerTransferred` | Updates `ownerId` |
| `SpaceTransferred` | Updates `spaceId` |
| `ResourceTagsApplied` | Updates `tags` (ABAC) |

## Configuration Annotations

### @Enabled

Enable/disable components:

```kotlin
@Configuration
@Enabled(properties = ["wow.command.enabled=true"])
class CommandConfiguration { ... }
```
