---
title: Aggregate and Invariants
description: Start from business boundaries and preserve aggregate invariants with event sourcing in Wow.
outline: deep
---

# Aggregate and Invariants

An aggregate is the consistency boundary for one event stream. It separates a business change into two clear responsibilities: the command side decides from current state, while the state side reconstructs the result only from domain events that already happened. Every state change should be explainable by an event.

An aggregate encloses state, business decisions, and invariants within one consistency boundary.

```mermaid
flowchart TB
    Context["Bounded context"] --> Aggregate["Aggregate boundary"]
    Intent["Business intent"] --> Decision["Aggregate decision"]
    Aggregate --> State["Current state"]
    Aggregate --> Decision
    State --> Decision
    Decision --> Invariant{"Invariants satisfied?"}
    Invariant -->|Yes| Event["Domain event"]
    Invariant -->|No| Reject["Reject command"]
    Event --> State
```

## Start With Business Boundaries

Write invariants before code. For a cart, the business rules can first be expressed as a decision table:

| Current state and intent | Decision result | State after sourcing |
| --- | --- | --- |
| Product is absent; add it | `CartItemAdded` | Add the product to `items` |
| Product already exists; add it again | `CartQuantityChanged` | Replace that product's quantity |
| Item count has reached `MAX_CART_ITEM_SIZE` | Reject the operation | State is unchanged and no business event is emitted |
| Remove a set of products | `CartItemRemoved` | Filter the matching `productId` values |

This table determines three kinds of model: commands express intent, events express facts, and the state object changes only while sourcing events. Modeling is complete when every invariant has an explicit success event, rejection result, and deterministic sourcing result.

## Bounded Context and Aggregate Identity

A bounded context owns a coherent business language and its aggregate names. An aggregate's runtime identity contains `contextName`, `aggregateName`, `tenantId`, and `id`; routing and storage must preserve the complete `AggregateId`.

`tenantId` is routing and isolation context, not a second ID namespace. Within one `NamedAggregate` (`contextName` + `aggregateName`), an `id` must be unique across tenants. See [Core Concepts](../core-concepts.md) for terminology and identity details.

## Aggregate Policies: Space and Owner

Since 9.3.0, an aggregate declares its own policies on the aggregate class; `@AggregateRoute` keeps only routing (`resourceName`, `enabled`):

```kotlin
@AggregateRoot
@AggregateRoute(resourceName = "sales-order")
@Spaced
@AggregateOwner(OwnerPolicy.ALWAYS)
class Order(private val state: OrderState)
```

| Declaration | Policy | When absent |
|---|---|---|
| `@Spaced` (`@Spaced(false)` declares the opposite) | commands and queries take a space, see [Enabling Space](../data-access.md#enabling-space) | not spaced |
| `@AggregateOwner(OwnerPolicy.NEVER \| ALWAYS \| AGGREGATE_ID)` | ownership, see [Ownership Routing Policy](../data-access.md#ownership-routing-policy) | `NEVER` |
| `@StaticTenantId("…")` | every instance belongs to one fixed tenant | dynamic tenant |

Each policy is resolved in one place, `AggregateMetadata` (`spaced`, `owner`, `staticTenantId`): the aggregate-level declaration first, then the place 9.2 read it, then the default. The deprecated `@AggregateRoute(spaced = …, owner = …)` and its `AggregateRoute.Owner` enum are still read when the new annotation is absent, and count as declared only when they differ from their default (`spaced = true`, `owner` other than `NEVER`), so an aggregate that is not touched keeps its 9.2 behaviour.

In a hierarchy the nearest class that declares a policy decides, so `@Spaced(false)` or `@AggregateOwner(OwnerPolicy.NEVER)` on an aggregate overrides `@AggregateRoute(spaced = true, owner = …)` on its supertype. Two declarations of one policy that disagree are an error instead of one silently winning:

- `@Spaced(false)` with `@AggregateRoute(spaced = true)` on the same class, or `@AggregateOwner` with a different `@AggregateRoute(owner = …)` on the same class;
- `@StaticTenantId` with a different `tenantId` for the same aggregate in `@BoundedContext.Aggregate` or in a hand-written `META-INF/wow-metadata.json`. Before 9.3.0 these disagreed silently: the runtime used `@StaticTenantId`, while the generated metadata kept the bounded context's value;
- two `META-INF/wow-metadata.json` resources on the classpath that give the same aggregate a different `tenantId` (or `type`, or a bounded context a different alias). Before 9.3.0 the second resource was logged and dropped, so the result depended on classpath order; a resource that cannot be parsed is still logged and skipped.

The application fails at startup, naming the aggregate (and, for two resources, both resource URLs), and the Wow KSP processor fails the compilation with the aggregate named in the error. Two cases that booted on 9.2 stop:

- an api module whose `@BoundedContext.Aggregate(tenantId = "a")` and a domain module whose aggregate carries `@StaticTenantId("b")`: the startup fails;
- a bare `@StaticTenantId` (the default tenant) on an aggregate whose bounded context in the same module names another tenant: the compilation fails. The processor also records a non-default policy in the generated `META-INF/wow-metadata.json` (`"spaced": true`, `"owner": "ALWAYS"`); 9.2 nodes ignore these fields and `GET /wow/metadata` does not return them.

### Migrating from `@AggregateRoute(spaced, owner)`

| Up to 9.2 | Since 9.3.0 |
|---|---|
| `@AggregateRoute(spaced = true)` | `@Spaced` |
| `@AggregateRoute(owner = AggregateRoute.Owner.X)` | `@AggregateOwner(OwnerPolicy.X)` |
| `@AggregateRoute(resourceName = "r", spaced = true, owner = …)` | `@AggregateRoute(resourceName = "r")` with both annotations |

Remove an `@AggregateRoute` that carried nothing else. The change is declaration-only: routes, the OpenAPI document, the space and owner of commands, and storage stay the same, so nodes built before and after it run side by side. Code that reads the policy uses `AggregateMetadata.spaced` and `AggregateMetadata.owner`, and `AggregateRouteMetadata.ownerPolicy` (and its `OwnerPolicy` constructor) in place of `owner`. The deprecated forms are removed in 10.0.0.

## State, Domain Events, and Invariants

`Cart` reads `CartState` and returns events; `CartState` keeps setters private and updates only in sourcing functions:

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

A state object must expose one of `ctor()`, `ctor(id)`, or `ctor(id, tenantId)`; it may have at most two parameters and each must be a `String`. `onSourcing` is the conventional name; another name needs `@OnSourcing`. A sourcing function returns no event, calls no external service, and reads neither current time nor randomness.

## Recommended Aggregate Organization

Use a command object composed with a state object by default:

```text
Command -> Command aggregate -> Domain event -> State aggregate
                   reads state                 mutates state
```

`Cart` and `Order` both use this structure, so the decision maker and state mutator are immediately visible. A command object may instead inherit a state object, or commands and state may share one very small class; in all cases, command paths must not mutate state directly and state setters must remain private.

Do not add inheritance layers for hypothetical reuse. Wow supports both Kotlin and Java; see the [Bank Transfer example](../../reference/example/transfer) for a complete Java organization.

## Deterministic State Evolution

The same initial state and event sequence must produce the same result. Otherwise, history replay, snapshot verification, and recovery cannot be trusted.

When one handling result contains multiple events, their order is also a contract. State consumes only events that change this aggregate; notification events for other components may leave it unchanged. This makes the same history replayable without new results caused by environment or execution time.

## Lifecycle Invariants

The order example makes allowed state transitions explicit on the command side:

| Command | Allowed state | Event and next state |
| --- | --- | --- |
| `ChangeAddress` | `CREATED` | `AddressChanged`; state remains `CREATED` |
| `PayOrder` | `CREATED` | `OrderPaid`; fully paid moves to `PAID` |
| `ShipOrder` | `PAID` | `OrderShipped`; moves to `SHIPPED` |
| `ReceiptOrder` | `SHIPPED` | `OrderReceived`; moves to `RECEIVED` |

Reject invalid transitions on the command side; the state side does not infer command intent. Delete and recover are aggregate lifecycle operations too: test access rejection after deletion, successful recovery, and repeated recovery failure.

## Continue to Command Definition

With aggregate boundaries and invariants clear, continue to [Define Commands](../command/definition.md): give an intent its payload, target-aggregate metadata, and handling function.
