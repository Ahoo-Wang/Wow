---
title: Define Commands
description: Define command payloads, target-aggregate metadata, and handlers that return domain events only.
outline: deep
---

# Define Commands

A command is an imperative payload requesting a state change. It describes what a caller wants to happen; the [aggregate](../domain/aggregate.md) decides from current state whether it is allowed and represents the fact that happened as domain events.

## Command Payloads and Command Messages

A command payload is usually a Kotlin `data class` or `object`. When sent, `toCommandMessage()` wraps the payload with a command ID, request ID, aggregate identity, owner, space, headers, expected version, and creation flags in `CommandMessage<C>`.

```kotlin
@CreateAggregate
data class CreateOrder(
    val items: List<Item>,
    val address: ShippingAddress,
    val fromCart: Boolean,
)
```

The payload expresses request data; it is not the runtime envelope. To control versions, put `@AggregateVersion` on a payload property; `aggregateVersion` on the command message is used for optimistic concurrency checking.

## Target Aggregate and Command Metadata

`CommandMetadataParser` derives a command type's name, target aggregate, aggregate ID, tenant, owner, expected version, and create, allow-create, and void flags. A command can supply its target by implementing `NamedAggregate`, or by using `@AggregateName`; `@AggregateId` selects the target ID, and an unannotated property conventionally named `id` is used as a fallback.

`@TenantId`, `@OwnerId`, and `@AggregateVersion` provide their respective metadata; `@StaticAggregateId` and `@StaticTenantId` provide static values. Constructing a `CommandMessage` fails if neither the command nor the call arguments can resolve a target aggregate.

## Command Handling Functions

A command handling function does only three things: read current state, check business invariants, and return one or more domain events. Database writes, event publication, and projection updates belong to the runtime processing chain, not aggregate decisions.

```kotlin
@AggregateRoot
class Cart(private val state: CartState) {
    fun onCommand(command: ChangeQuantity): CartQuantityChanged {
        val item = state.items.firstOrNull { it.productId == command.productId }
            ?: throw IllegalArgumentException("Product does not exist")
        return CartQuantityChanged(item.copy(quantity = command.quantity))
    }
}
```

The conventional name `onCommand` is discovered automatically; use `@OnCommand(returns = [...])` for another function name or when the return type cannot statically express the event set. The first parameter can be a concrete command, `CommandMessage<C>`, or `ServerCommandExchange<C>`; later parameters can be resolved by the IoC container. A handler may return one event, multiple events, or a reactive type; external checks must stay in the reactive chain.

## Create, Allow-Create, and Void Commands

`@CreateAggregate` marks a creation command. Its expected version is the uninitialized version, and it starts from fresh state rather than restoring existing event history.

`@AllowCreate` permits on-demand creation when the target aggregate does not exist; without it, an ordinary command whose target is absent fails. `AddCartItem` is an existing allow-create example.

`@VoidCommand` does not mean “a handler with no return value.” It is still sent to the command bus and becomes an `isVoid` command, but `CommandDispatcher` acknowledges and filters it before aggregate dispatch. It therefore does not invoke an aggregate root, emit events, or update state. Mount it on an aggregate through `@AggregateRoot(commands = [...])`, as `ViewCart` does.

## AfterCommand and OnError

The `afterCommand` convention or `@AfterCommand` declares a post-command function after the main command succeeds. Post-command functions are ordered by `@Order`; `include` and `exclude` select command types, and returned events are appended to the same event stream after the main command events.

The `onError` convention or `@OnError` declares an error handling function. The runtime first records the original error on the exchange, then invokes the matching error function; unless that function clears the exchange error, the original error still propagates. Use it to observe or perform framework-supported recovery, not as a second write path around business invariants.

## Input Validation and Business-Invariant Boundaries

The call boundary owns payload shape and field constraints, such as Jakarta Validation, `CommandValidator`, and request-ID prechecks. The aggregate owns business invariants that depend on current state, such as cart capacity or order lifecycle.

Do not skip aggregate checks because field validation passed: the same command may be accepted or rejected under different event histories. For every invariant, test Given history, When command, Expect event or error, and the sourced state.

## Next: Send Commands

Once the command is defined, use [Send Commands](./sending.md) to send its `CommandMessage`, then use [Completion Semantics](./completion.md) to choose a wait stage that meets the caller's response contract.
