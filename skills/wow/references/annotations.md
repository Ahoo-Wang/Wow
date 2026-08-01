# Wow Framework Annotations Reference

## Contents

- [API Metadata Annotations](#api-metadata-annotations)
- [Core Annotations](#core-annotations)
- [Event Handler Annotations](#event-handler-annotations)
- [Command Annotations](#command-annotations)
- [Aggregate Route & Routing](#aggregate-route--routing)
- [Event Annotations](#event-annotations)
- [Bounded Context](#bounded-context)
- [Multi-Tenancy Annotations](#multi-tenancy-annotations)
- [Aggregate Patterns](#aggregate-patterns)
- [Annotation Naming Convention](#annotation-naming-convention)
- [Special Built-in Events](#special-built-in-events)
- [Configuration Conditions](#configuration-conditions)

## API Metadata Annotations

### @Summary

`me.ahoo.wow.api.annotation.Summary` provides concise title metadata for classes and properties. In the current source, `wow-schema` resolves it into schema title metadata.

Use it on every command and domain event that is part of the API/domain contract:

```kotlin
import me.ahoo.wow.api.annotation.Summary

@Summary("Create order")
data class CreateOrder(...)

@Summary("Order created")
data class OrderCreated(...)
```

### @Description

`me.ahoo.wow.api.annotation.Description` provides longer description metadata for classes and properties. In the current source, `wow-schema` resolves it into schema description metadata.

Use it together with `@Summary` on commands and domain events that are part of the API/domain contract:

```kotlin
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary

@Summary("Create order")
@Description("Creates an order and initializes the order aggregate.")
data class CreateOrder(...)
```

When the description is long, use Kotlin raw string syntax. Do not use `.trimIndent()` in annotation arguments because annotation values must be compile-time constants.

```kotlin
@Description(
    """Creates an order from selected items.
The command initializes the order aggregate and records shipping information.
When fromCart is true, a saga may remove items from the cart after the order is created."""
)
data class CreateOrder(...)
```

Both annotations target classes and fields/properties. Prefer putting stable reusable field metadata on capability interfaces so repeated fields share one definition.

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

Marks a method as an event sourcing handler used to rebuild aggregate state. The annotation may be omitted only when the function is named exactly `onSourcing` and declares exactly one value parameter. The runtime derives the supported event body type from that first parameter: either the direct body type or the generic body type carried by `DomainEvent<T>` / `DomainEventExchange<T>`. A descriptive name such as `onCartItemAdded`, or an `onSourcing` function with additional exchange-derived parameters, must declare `@OnSourcing` to be discovered by the runtime parser.

Aggregate sourcing invokes the function with a fresh `SimpleDomainEventExchange`; that exchange does not carry a `ServiceProvider`. Additional parameters must be extractable from the exchange itself. Sourcing must not depend on IOC services or external side effects.

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
- Domain event exchange: `DomainEventExchange<CartItemAdded>`

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

Marks a function for blocking-aware scheduling. Functions invoked through `MonoMethodAccessorFactory` are wrapped by `BlockingMonoFunctionAccessor`: when subscription starts on a Reactor non-blocking thread, Wow applies `subscribeOn(Schedulers.boundedElastic())`; on a blockable thread, the publisher is left unchanged. `CommandBuilderRewriter` uses a separate registration path: an `@Blocking` `rewrite` method is wrapped by `BlockingCommandBuilderRewriter`, which always applies `subscribeOn` to its configured scheduler. The annotation does not convert a reactive return type into a synchronous function.

```kotlin
@Blocking
fun onEvent(event: OrderPaid) {
    emailService.sendNotification(...)
}
```

### @Retry

Configures retry behavior for event handlers, including Saga handlers.

```kotlin
@Retry(maxRetries = 5, minBackoff = 60, executionTimeout = 10)
fun onEvent(event: DomainEvent<OrderCreated>): CommandBuilder? { ... }
```

**Parameters:**
| Parameter | Type | Default | Description |
|---|---|---|---|
| `enabled` | Boolean | `true` | Set `false` to disable this retry policy |
| `maxRetries` | Int | `10` | Maximum retry attempts |
| `minBackoff` | Int | `180` | Initial backoff in seconds (grows exponentially: `minBackoff * 2^retries`) |
| `executionTimeout` | Int | `120` | Lease timeout in seconds for a `PREPARED` compensation attempt. After expiry, the scheduler may recover an attempt that never reported success or failure. It does not apply a Reactor timeout or cancel the handler. |
| `recoverable` | Array | `[]` | Exception types that trigger retries |
| `unrecoverable` | Array | `[]` | Exception types that fail immediately |

Saga guidance:

- Put `@Retry` on the `onEvent` handler whose failure policy differs from the default.
- Use `recoverable` for transient infrastructure or downstream failures.
- Use `unrecoverable` for domain errors that should fail fast and not retry.

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

Configures the REST route contract for a command. `wow-openapi` parses this metadata and builds the endpoint contract consumed by `wow-webflux`.

```kotlin
@CommandRoute(action = "", method = CommandRoute.Method.DELETE, appendIdPath = CommandRoute.AppendPath.ALWAYS)
object DefaultDeleteAggregate : DeleteAggregate
```

**Parameters:**
| Parameter | Default | Description |
|---|---|---|
| `action` | `__{command_name}__` | Action/sub-resource segment; the default is derived from command metadata |
| `enabled` | `true` | Whether route generation is enabled |
| `method` | `DEFAULT` | Resolves to `POST` for create commands, `DELETE` for delete commands, otherwise `PUT` |
| `prefix` | `""` | Path prefix |
| `appendIdPath` | `DEFAULT` | Aggregate ID path policy |
| `appendTenantPath` | `DEFAULT` | Tenant ID path policy |
| `appendOwnerPath` | `DEFAULT` | Owner ID path policy |

`@CommandRoute.PathVariable` and `@CommandRoute.HeaderVariable` both default to `name = ""`, `nestedPath = []`, and `required = true`.

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
| `resourceName` | `""` (empty; falls back to the aggregate metadata name, normally Pascal-to-snake unless overridden by `@Name`) | Custom API path segment |
| `enabled` | `true` | Set `false` to disable automatic route generation |
| `spaced` | `false` | Include the `Wow-Space-Id` header parameter in generated aggregate routes |
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
| `packageScopes` | Package marker classes used to discover context members |
| `aggregates` | Array of `@Aggregate` definitions within the context |

Each nested `BoundedContext.Aggregate` declares `name`, with optional `tenantId`, `id`, `scopes`, and `packageScopes`.

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

    @OnSourcing
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

## Configuration Conditions

The current source uses Spring Boot `@ConfigurationProperties` classes plus conditional annotations such as `@ConditionalOnWowEnabled` and `@ConditionalOnCommandLocalFirstEnabled`. Do not use a generic `@Enabled` annotation unless it exists in the target checkout.
