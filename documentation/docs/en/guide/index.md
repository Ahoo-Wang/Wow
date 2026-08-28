---
title: Wow Documentation Map
description: Choose the shortest path through the Wow documentation for your learning goal or engineering task.
outline: deep
---

# Wow Documentation Map

Start with first success, then continue with the task in front of you.

## 30-Minute First-Success Target

Complete these confirmed gates in order:

1. Create from [wow-project-template](https://github.com/Ahoo-Wang/wow-project-template).
2. Confirm the selected Wow version.
3. Pass the domain test.
4. Start the server.
5. Send a real HTTP command and inspect the command result.
6. Load versioned sourced state.

See [Getting Started](./getting-started.md) for the complete steps and completion semantics.

The functional gates are verified; 30 minutes remains a target because first-time developer wall-clock completion has not been measured.

## Continue Building

Continue from the three entry points: [Domain Model](./domain/), [Commands](./command/), and [Events and Collaboration](./event/). Then use [Projection](./projection.md) and [Query Service](./query.md) for the read side.

## Prepare for Production

Start with [Production Best Practices](./best-practices.md), then verify [Backup, Restore, and Replay](./recovery.md), [Application Testing](./application-testing.md), [Observability](./advanced/observability.md), and [Troubleshooting](./troubleshooting.md).

## Look Up Exact Facts

Use [Configuration Reference](../reference/config/core.md), [Examples](../reference/example/order.md), and [Ecosystem](../reference/ecosystem.md) for exact facts. Use the API documentation in the top navigation for Kotlin and Java symbols and signatures.

## Evaluate or Contribute by Role

[Onboarding](../onboarding/) routes contributors, staff engineers, executives, and product managers by the decision they need to make; [Articles](../articles/) explain specific trade-offs.

## Continue by Task

| Task | Read first | Then read | Done when |
| --- | --- | --- | --- |
| Decide whether Wow fits | [Introduction](./introduction.md) | [Production Best Practices](./best-practices.md) | You can explain the benefits, operating costs, and poor-fit cases |
| Run a first application | [Getting Started](./getting-started.md) | [Configuration](./configuration.md) | Domain tests pass, a real command reaches `SNAPSHOT`, and state can be loaded |
| Add Wow to an existing Spring Boot service | [Existing Project](./existing-project.md) | [Spring Boot Starter](./extensions/spring-boot-starter.md) | KSP metadata, generated routes, command handling, and snapshot loading all work |
| Study a complete Kotlin application | [Order and Cart](../reference/example/order.md) | [Application Testing](./application-testing.md) | You can trace commands, events, state, sagas, projections, and restart recovery |
| Model an aggregate and invariants | [Domain Model](./domain/) | [Aggregate and Invariants](./domain/aggregate.md) | Commands emit domain events and replay produces verified state |
| Build application release gates | [Application Testing](./application-testing.md) | [Production Best Practices](./best-practices.md) | Domain, HTTP, real-adapter, recovery, and security-negative evidence exists |
| Evolve persisted events | [Event Evolution](./domain/event-evolution.md) | [Event Sourcing](./domain/event-sourcing.md) | Upgrader registration, ordering, historical replay, and rollback have evidence |
| Expose writes and completion semantics | [Commands](./command/) | [Completion Semantics](./command/completion.md) | You can distinguish `SENT`, `PROCESSED`, `SNAPSHOT`, and `PROJECTED` |
| Build a query model | [Projection](./projection.md) | [Query Service](./query.md) | The projection is retry-safe and idempotent, with a clear query boundary |
| Coordinate across aggregates | [Events and Collaboration](./event/) | [Saga](./event/saga.md) | Success, retry, and unrecoverable paths are tested |
| Choose messaging and storage | [Module Dependencies](./advanced/module-dependencies.md) | [Extensions](./extensions/spring-boot-starter.md) | Only the required backends and starter capabilities are included |
| Prepare for production | [Production Best Practices](./best-practices.md) | [Backup, Restore, and Replay](./recovery.md) | Idempotency, recovery, capacity, alerts, and rollback have evidence |
| Diagnose a failure or hang | [Troubleshooting](./troubleshooting.md) | The relevant core or extension page | The failed stage is known instead of merely having a larger timeout |
| Migrate a system or version | [Migration Guide](./migration.md) | The selected migration path | Inventory, reconciliation, cutover, and rollback gates are complete |

## Use Each Documentation Type for Its Job

- **Guide** explains why and how to complete a task.
- **Reference** provides exact configuration, examples, and ecosystem resources.
- **API** is available from the top navigation and provides Kotlin and Java symbols and signatures through Dokka.
- **[Onboarding](../onboarding/)** provides role-specific paths for contributors, architects, executives, and product managers.
- **[Articles](../articles/)** explain trade-offs through concrete problems; they do not replace API or configuration reference.

::: warning Version and source of truth
Documentation explains the repository but does not replace it. If prose differs from the public contracts, configuration classes, tests, or release notes for the tag you selected, follow that version's source.
:::
