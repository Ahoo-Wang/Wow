# Aggregate Root Modeling

## Patterns

### Aggregate Pattern (Recommended)

The aggregate pattern places command functions and sourcing functions (containing aggregate state data) in separate classes. This approach avoids the problem of command functions directly modifying aggregate state data (by setting the `setter` accessor to `private`).
At the same time, separation of responsibilities allows the aggregate root's command functions to focus more on command processing, while sourcing functions focus on aggregate state data changes.

#### Simple Aggregation Pattern

```
StateAggregate ←--state-- CommandAggregate(@AggregateRoot)
```

#### Complex Aggregation Pattern

```
StateAggregate ←--state-- CommandAggregate
  ├── StateAggregateA ←--state-- CommandAggregateA(@AggregateRoot)
  └── StateAggregateB ←--state-- CommandAggregateB(@AggregateRoot)
```

### Single Class Pattern

Command + sourcing + state in one class. **Avoid** — violates event sourcing principles:
- Command functions can directly modify state
- State changes cannot be traced via events
- Use only for simple prototypes

### Inheritance Pattern

Command aggregate inherits from state aggregate with `private set` on setters.

## Conventions

### Command Aggregate Root

- Add `@AggregateRoot` annotation for `wow-compiler` metadata generation
- `@OnCommand` is optional if method is named `onCommand`
- First parameter: specific command, `CommandMessage<C>`, or `CommandExchange<C>`
- Other parameters resolved from IOC container (use `@Name` for qualified injection)
- Return value: one or more domain events
- `@OnCommand(returns = [...])` is required when return type is `Any` or polymorphic

```kotlin
@AggregateRoot
class Cart(private val state: CartState) {

    @OnCommand(returns = [CartItemAdded::class, CartQuantityChanged::class])
    fun onCommand(command: AddCartItem): Any {
        require(state.items.size < MAX_CART_ITEM_SIZE) {
            "Shopping cart can only add up to [$MAX_CART_ITEM_SIZE] items."
        }
        state.items.firstOrNull {
            it.productId == command.productId
        }?.let {
            return CartQuantityChanged(
                changed = it.copy(quantity = it.quantity + command.quantity),
            )
        }
        val added = CartItem(
            productId = command.productId,
            quantity = command.quantity,
        )
        return CartItemAdded(
            added = added,
        )
    }
}
```

### AfterCommand Hook

Post-processing hook that executes after command handler completes. Non-null return values are appended as additional events.

```kotlin
@AfterCommand(include = [CreateOrder::class])
fun afterCreateOrder(exchange: ServerCommandExchange<*>): OrderConfirmed? {
    val result = exchange.getCommandInvokeResult<OrderCreated>()
    return null
}
```

### Error Handling with OnError

```kotlin
@OnError
fun onError(command: CreateOrder, error: Throwable) {
    // Handle error, log or publish error event
}
```

Can also accept `eventStream: DomainEventStream?` as a third parameter.

### State Aggregate Root

- Must define aggregate root ID field in constructor
- Sourcing functions apply domain events to state
- `@OnSourcing` optional if method named `onSourcing`
- Sourcing parameters: specific event or `DomainEvent<T>`
- No return value — state is mutated in place
- **Must be deterministic and side-effect-free**

```kotlin
class CartState(val id: String) {
    var items: List<CartItem> = listOf()
        private set

    @OnSourcing
    fun onCartItemAdded(cartItemAdded: CartItemAdded) {
        items = items + cartItemAdded.added
    }
}
```

## Bounded Context

A bounded context defines a coherent area of the domain with its own ubiquitous language and rules.

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

Every `AggregateId` includes a `contextName`, ensuring commands and events are routed within the correct bounded context.

## Aggregate Lifecycle

### State Machine

```
NEW (version=0) → STORED → SOURCED → STORED (cycle per command)
                             ↓
                         EXPIRED (unrecoverable error)

STORED → DELETED (DefaultDeleteAggregate)
DELETED → STORED (DefaultRecoverAggregate)
```

### Command Processing Phases

1. **Validation Gates** (6 sequential checks):
   - Version check (optimistic concurrency)
   - Initialization check (`initialized || isCreate || allowCreate`)
   - Owner check (multi-tenancy)
   - Space check (multi-tenancy)
   - CommandState check (`STORED` — serial processing)
   - Delete check (reject unless `RecoverAggregate`)

2. **Command Execution**: `@OnCommand` handler produces events

3. **Event Sourcing**: `@OnSourcing` methods apply events to state deterministically

4. **Event Persistence**: Atomic append to EventStore with version conflict check

5. **Event Publication**: Events published to DomainEventBus

### Version Lifecycle

| Constant | Value | Meaning |
|---|---|---|
| `UNINITIALIZED_VERSION` | `0` | Just created, no events |
| `INITIAL_VERSION` | `1` | First event applied |
| `initialized` | `version > 0` | Has any events |
| `expectedNextVersion` | `version + 1` | Next expected version |

### Special Built-in Events

Handled automatically without explicit `@OnSourcing`:

| Event | Effect |
|---|---|
| `AggregateDeleted` | `deleted = true` |
| `AggregateRecovered` | `deleted = false` |
| `OwnerTransferred` | `ownerId = toOwnerId` |
| `SpaceTransferred` | `spaceId = toSpaceId` |
| `ResourceTagsApplied` | `tags = event.tags` |

### Missing @OnSourcing

If no matching `@OnSourcing` handler exists for an event, the framework:
- Does NOT throw an error
- Logs a debug message
- Still updates the aggregate version

This allows forward-compatible state evolution.

### State Rebuild Strategy

When loading an existing aggregate:
- **Snapshot-based**: Load snapshot, replay only incremental events after snapshot version
- **Full replay**: No snapshot → replay all events from version 1

Point-in-time reconstruction is supported via `tailEventTime` parameter.

## Multi-Tenancy

Every `AggregateId` includes `tenantId`:

- `@StaticTenantId` on aggregate class — fixed tenant
- `@TenantId` on command parameter — extract from command body
- `BoundedContext.Aggregate(tenantId = "...")` — static tenant assignment

## Aggregate Routing

```kotlin
@AggregateRoute(
    resourceName = "sales-order",
    spaced = true,
    owner = AggregateRoute.Owner.ALWAYS
)
class Order(private val state: OrderState) { ... }
```

| Attribute | Description |
|---|---|
| `resourceName` | Custom API path segment |
| `enabled` | Set `false` to disable route generation |
| `owner` | Ownership: `NEVER`, `ALWAYS`, `AGGREGATE_ID` |

## Aggregate Scheduler

Each aggregate gets a dedicated Reactor Scheduler to control concurrent execution:

```kotlin
fun interface AggregateSchedulerSupplier {
    fun getOrInitialize(namedAggregate: NamedAggregate): Scheduler
}
```

Default implementation creates a parallel scheduler per aggregate: `Schedulers.newParallel("$name-${namedAggregate.aggregateName}")`.

## Event Naming Convention

```kotlin
// Good - past tense + specific behavior
@Event
data class OrderCreated(
    val orderId: String,
    val customerId: String,
    val items: List<OrderItem>,
    val totalAmount: BigDecimal
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
