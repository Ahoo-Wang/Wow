---
title: "Traditional CRUD vs Wow: From Shipping Endpoints to Shipping a Domain Model"
description: "Compare common CRUD layering with Wow's model-driven runtime while separating framework capability, application responsibility, and repository evidence."
outline: deep
---

# Traditional CRUD vs Wow: From Shipping Endpoints to Shipping a Domain Model

![Traditional layered architecture compared with a Wow domain model service](/images/articles/traditional-vs-wow-architecture/cover.png)

An order feature can start with a controller, service, and repository, or with commands, aggregate invariants, and domain events. Both paths can ship software. They differ in where business decisions live and which costs the team accepts.

This article's argument is: **when domain rules, state history, and cross-aggregate collaboration dominate complexity, a domain model is usually a clearer delivery unit than an endpoint.** This is not a quality judgment about every “traditional architecture,” nor a Wow guarantee about delivery speed or defect rates.

## Define the Comparison First

“Conventional CRUD” here means a common workflow: a request passes through a controller and service, which reads and updates current state inside a transaction. Mature systems can also have strong domain models, events, and excellent tests; non-Wow does not mean anemic.

“Wow” means the command dispatch, event sourcing, snapshots, projections, sagas, wait stages, metadata, and testing capabilities in the current repository. [Architecture Overview](../guide/advanced/architecture.md) owns exact component responsibilities; [Introduction](../guide/introduction.md#fit-boundary) owns the fit and adoption-cost boundary.

## Two Ways to Organize Delivery

| Decision | Common CRUD layering | Current Wow approach |
| --- | --- | --- |
| Modeling start | resource, endpoint, and current data | business command, aggregate boundary, and invariant |
| State change | update current records in a transaction | aggregate produces events; sourcing functions apply them |
| History | design a separate audit/history mechanism | EventStore retains versioned domain-event history |
| Reads | reuse the write model or build custom queries | sourced aggregate state and projected views are distinct paths |
| Cross-aggregate work | organize service calls, messages, or workflows | saga consumes an event and sends another command |
| Domain verification | team selects unit/integration layers | `AggregateSpec` / `SagaSpec` express Given → When → Expect |

This table is an architecture view, not a complete contract. [Core Concepts](../guide/core-concepts.md) owns the canonical command, event, state, projection, and saga vocabulary.

## What Wow Connects to the Domain Model

```mermaid
flowchart LR
    Command --> Aggregate[Aggregate decision]
    Aggregate --> Event[Domain event]
    Event --> State[Sourced state]
    Event --> Projection
    Event --> Saga
    Saga --> Command
```

The current Wow runtime connects the same domain artifacts to:

- command messages and aggregate processing;
- event append and state reconstruction;
- snapshots, projections, and query paths;
- event handlers and sagas;
- WebFlux/OpenAPI metadata;
- aggregate and saga specifications.

That is the engineering meaning of “domain model as a service”: reusable runtime capabilities are assembled around the model. It does not mean one aggregate class automatically supplies a correct API, security, capacity, or recovery plan.

## What the Current Repository Example Proves

The cart example separates responsibilities:

- [`Cart.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-domain/src/main/kotlin/me/ahoo/wow/example/domain/cart/Cart.kt) handles `AddCartItem` and produces either `CartItemAdded` or `CartQuantityChanged` from current state;
- [`CartState.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-domain/src/main/kotlin/me/ahoo/wow/example/domain/cart/CartState.kt) applies those events through sourcing functions;
- [`CartSaga.kt`](https://github.com/Ahoo-Wang/Wow/blob/main/example/example-domain/src/main/kotlin/me/ahoo/wow/example/domain/cart/CartSaga.kt) sends a remove-items command after an order created from a cart;
- `CartSpec` and `CartSagaSpec` cover accepted, rejected, and no-follow-up-command branches.

[Kotlin Order and Cart](../reference/example/order.md) is the canonical walkthrough, and `./gradlew :example-domain:check` is the focused gate for this example.

This evidence proves the example behavior and test layer. It does not prove that every application writes a fixed percentage less code, gains a fixed productivity improvement, or reaches production capacity automatically. This rewrite removes those unsupported quantitative inferences.

## Cost Moves; It Does Not Disappear

Wow can reduce the need for each business service to re-own command routing, event-store abstraction, state replay, wait coordination, and domain-test setup. The team still owns:

- correct aggregate boundaries and event contracts;
- authorization, tenancy, owner/space rules, and external-side-effect idempotency;
- persisted-event evolution and replay compatibility;
- selection and verification of storage, messaging, and query adapters;
- environment evidence for capacity, backup, recovery, alerting, and rollback.

The value therefore depends on whether the problem needs these capabilities. If current state and one database transaction fully express the business, event history, asynchronous projections, and message recovery may be unnecessary cost.

## When Wow Is Worth Evaluating

Ask four questions:

1. Are there business invariants and illegal state transitions that need one explicit guardian?
2. Does the reason state changed, historical versions, or replay have business value?
3. Must writes and multiple read models evolve independently?
4. Do cross-aggregate flows need explicit progress, idempotency, and recovery boundaries?

If most answers are no, clear CRUD is usually simpler. If most are yes, validate Wow with one real business slice before attempting a system-wide migration.

## Minimal Adoption Path

1. Write the command, invariant, event, and state for one core scenario.
2. Prove accepted and rejected paths with the [Domain Test Suite](../guide/test-suite.md).
3. Follow [Getting Started](../guide/getting-started.md) to expose one real command and read sourced state.
4. Add a projection only for a real query requirement, and a saga only for a real cross-aggregate flow.
5. Use [Application Testing](../guide/application-testing.md) for real adapters, restart, redelivery, and security boundaries.
6. Complete [Production Best Practices](../guide/best-practices.md) and [Backup, Restore, and Replay](../guide/recovery.md) gates before production.

## Conclusion

“From endpoints to a domain model” does not mean replacing controllers with more architecture vocabulary. It means placing decisions, facts, and verification behind stable boundaries while the framework owns reusable runtime mechanics.

Keep simple problems simple. A complex domain may justify event sourcing, eventual consistency, and operational cost; labels such as “traditional” and “modern” do not.
