---
title: Aggregate Modeling
description: Start from business invariants and separate command decisions from event-sourced state in Wow.
outline: deep
---

# Aggregate Modeling

A Wow aggregate splits a business change in two directions: command handlers use current state to decide what may happen, while sourcing functions use domain events that already happened to reconstruct what the state is now. The core of modeling is not the number of annotations; it is making every state change explainable by an event.

::: tip Completion signal
Modeling is complete when every invariant has an explicit success event, rejection result, and deterministic sourcing result. The next step is to express those behaviors as Given → When → Expect specifications in the [Test Suite](./test-suite.md).
:::

## Write Invariants Before Handlers

For the repository's cart, the business rules can first be written as a decision table:

| Current state and command | Decision result | State after sourcing |
| --- | --- | --- |
| Product is absent; add it | `CartItemAdded` | Add the product to `items` |
| Product already exists; add it again | `CartQuantityChanged` | Replace that product's quantity |
| Item count has reached `MAX_CART_ITEM_SIZE` | Reject the command | State is unchanged and no business event is emitted |
| Remove a set of products | `CartItemRemoved` | Filter the matching `productId` values |

This table determines three kinds of code: commands express intent, events express facts, and the state object changes only while sourcing events.

## Recommended Pattern: Compose Command and State Objects

The `Cart` and `Order` aggregates in `example-domain` separate the command-handling object from the state object:

```text
Command -> Command Aggregate -> Domain Event -> State Aggregate
                    reads state                 mutates state
```

The command aggregate holds a read-only view of state and returns one or more events:

```kotlin
@AggregateRoot(commands = [MountedCommand::class, ViewCart::class, MockVariableCommand::class])
class Cart(private val state: CartState) {

    @OnCommand(returns = [CartItemAdded::class, CartQuantityChanged::class])
    fun onCommand(command: AddCartItem): Any {
        require(state.items.size < MAX_CART_ITEM_SIZE) {
            "Cart can contain at most [$MAX_CART_ITEM_SIZE] products."
        }
        state.items.firstOrNull { it.productId == command.productId }?.let {
            return CartQuantityChanged(
                changed = it.copy(quantity = it.quantity + command.quantity),
            )
        }
        return CartItemAdded(
            added = CartItem(command.productId, command.quantity),
        )
    }
}
```

The state object keeps setters private and updates only through sourcing functions:

```kotlin
class CartState(val id: String) {
    var items: List<CartItem> = listOf()
        private set

    @OnSourcing
    fun onCartItemAdded(event: CartItemAdded) {
        items = items + event.added
    }

    @OnSourcing
    fun onCartQuantityChanged(event: CartQuantityChanged) {
        items = items.map {
            if (it.productId == event.changed.productId) event.changed else it
        }
    }
}
```

This separation prevents a command handler from casually mutating state and allows the same event history to be replayed repeatedly.

## Other Supported Organizations

Wow's handler discovery does not require every aggregate to use one class structure. Choose according to model size:

| Organization | When it fits | Boundary that must hold |
| --- | --- | --- |
| Command object composed with state object | Default for most aggregates | Command side reads state; state side handles events |
| Command object inherits the state object | Existing models organize capabilities through inheritance | State setters remain private and command handlers do not mutate state directly |
| Commands and state in one class | Very small models or compatibility with existing code | Structure and tests must still prevent command paths from writing state directly |

Composition is usually easiest to review because the decision maker and state mutator are immediately visible. Complex aggregates may also use command and state base classes, but do not add inheritance layers for hypothetical reuse.

Wow supports both Kotlin and Java. See the [Bank Transfer example](../reference/example/transfer) for a complete Java organization.

## Command-Handling Conventions

- `@AggregateRoot` marks aggregate intent explicitly and participates in metadata generation; public models should prefer the explicit marker.
- The conventional name `onCommand` makes `@OnCommand` optional. Use the annotation for a custom function name or additional return-event metadata.
- The first parameter may be a concrete command, `CommandMessage<C>`, or `ServerCommandExchange<C>`; later parameters can be resolved from the IoC container.
- A handler may return one event, multiple events, or a reactive type. When the return type cannot statically describe the event set, declare it with `@OnCommand(returns = [...])`.
- Keep external checks reactive. `Order` uses the injected `CreateOrderSpec` and returns `Mono<OrderCreated>` without blocking the command path.

A command handler should do three things: read current state, check invariants, and return events. Database writes, event publication, and projection updates belong to the runtime, not to aggregate decisions.

## Sourcing Conventions

- The state constructor provides the aggregate ID; repository examples use the conventional name `id`.
- The conventional name `onSourcing` makes `@OnSourcing` optional; other names should be marked explicitly.
- A parameter may be the event body or a domain event containing metadata.
- Sourcing functions do not return events, call external services, read the current time, or generate randomness.
- The order of multiple returned events is part of the contract. State handles only events that change this aggregate; notification events for other components may leave state unchanged.

Determinism is a hard requirement: the same initial state and event sequence must produce the same result, or history replay, snapshot verification, and recovery cannot be trusted.

## Lifecycle Invariants

The order example demonstrates state-machine invariants:

| Command | Allowed state | Event and next state |
| --- | --- | --- |
| `ChangeAddress` | `CREATED` | `AddressChanged`; state remains `CREATED` |
| `PayOrder` | `CREATED` | `OrderPaid`; fully paid moves to `PAID` |
| `ShipOrder` | `PAID` | `OrderShipped`; moves to `SHIPPED` |
| `ReceiptOrder` | `SHIPPED` | `OrderReceived`; moves to `RECEIVED` |

Reject invalid transitions on the command side; the state side should not guess command intent. Delete and recover are Wow aggregate lifecycle operations and also require tests for access after deletion, successful recovery, and repeated recovery failure.

## Routing, Post-Command, and Error Hooks

Routing configuration is part of the public contract. For example:

```kotlin
@AggregateRoute(enabled = false)
class InternalAggregate(val id: String)
```

Disabling routing affects automatic command routes only; it does not change the aggregate's command or sourcing semantics.

`@AfterCommand` can append events after the main command succeeds. Multiple hooks can be ordered with `@Order`, while `include` and `exclude` limit command types. `@OnError` can observe command failures and perform framework-supported error handling. These hooks must not become a second write path around core invariants: any result that changes aggregate facts should still be expressed as an explicit domain event.

## Move From Model to Test

Prepare four items for every invariant before entering domain tests:

1. Given: which historical events are replayed, or whether the aggregate starts uninitialized;
2. When: which command runs, including owner, space, or injected services;
3. Expect event/error: which events are emitted, or which error rejects the command;
4. Expect state: which state fields and aggregate metadata must hold after sourcing.

After domain specifications pass, move to [Testing Wow Applications](./application-testing.md) to verify KSP metadata, Spring wiring, HTTP, real storage, recovery, and security boundaries. A passing domain DSL does not mean the application is ready to release.
