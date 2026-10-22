---
title: Why Wow
description: What Wow does for a team that writes a domain model, the principles it is built on, what it assumes about a deployment, and when it is not the right tool.
---

# Why Wow

Wow lets a team write only the domain model and get a system it can run: its routes, its contract, its clients, its read models, and the machinery that keeps them consistent. Everything outside the domain model is code Wow owes you.

## What it does

Every Wow system does three things, and every mechanism in it follows from one of them:

1. **Write.** A command is decided against the latest committed state and turned into events, appended in one atomic commit. Two writers to the same aggregate never both win: the version decides, and the loser is decided again.
2. **Deliver.** Every committed event reaches every observer at least once: sagas, projections, reactors, other contexts, the warehouse. Any observer can start again from the beginning.
3. **Use.** From the same domain model come the HTTP routes, the OpenAPI document, typed TypeScript clients, queries over every aggregate's state and history, data views and dashboards, without more code.

## The principles

| | Principle | In practice |
| --- | --- | --- |
| 1 | One write | Appending to the log is the only write. Publishing, snapshots, projections, indexes and the warehouse all read the log |
| 2 | The log is the bus | A subscription reads the log from its own checkpoint. Between contexts, events travel as published streams |
| 3 | Decide, then evolve | A decision produces events; evolving applies them to the state, without I/O. The state can be an ordinary mutable object: the kernel keeps every version's view stable |
| 4 | Bound at build time | The compiler generates the models: no reflection or scanning at run time |
| 5 | Coroutines first | `suspend` and `Flow` throughout; Reactor code still works through an adapter |
| 6 | Identity is one value | An aggregate's context, type, tenant and id are decided once, at admission. Every unique key includes the tenant |
| 7 | Read models replay | Every read model can be rebuilt from the log |
| 8 | Failures are data | A failure is recorded in a ledger and parks the one aggregate that failed. Nothing is skipped |
| 9 | One contract | The Manifest the compiler writes is the source of routes, OpenAPI, clients, views and compatibility checks |
| 10 | No host lock-in | The runtime does not depend on Spring; Spring Boot is one host |
| 11 | State is a read model | Every aggregate's latest state can be queried, with no projection written |

What these add up to, as guarantees a release must keep:

- a crash at any moment loses no committed fact;
- every read model, snapshots included, can be rebuilt from the beginning;
- two tenants using the same ids never affect each other;
- in a steady state, the store's read load does not grow with the number of subscriptions;
- outside the domain model, you write no infrastructure code.

[Concepts](../concepts/) explains each of them from first principles.

## What it assumes

- **One cluster per system.** All the services of a system run in the same cluster and can reach each other directly: waits that follow a command across services, and the channels between a service's nodes, are built on that. The internal port is never exposed outside the cluster, and traffic inside it is authenticated too ([Secure a Deployment](../operate/security)).
- **A durable store and, between contexts, Kafka.** Each service keeps its log on PostgreSQL or MongoDB. Contexts reach each other's events and commands through Kafka.
- **JDK 21 and Gradle.** Models and the contract are bound at build time by KSP and the Wow Gradle plugin, which has no Maven counterpart.

## When it is not the right tool

- **Plain CRUD over a few tables.** Wow earns its keep where decisions, history and consequences matter. A service whose rules fit in a database constraint pays for a log it does not need.
- **History you must erase.** Events are never deleted, and Wow offers no way to erase or shred them. Personal data that must be removable on request belongs outside the events, in a read model you can delete from ([Personal data in events](../operate/security#personal-data-in-events)).
- **Services spread across clusters** that cannot reach each other directly, or a store other than PostgreSQL or MongoDB for the log.
- **A Maven-only build**, or a JVM older than 21.

## From 9.x

Wow 10 is a redesign of 9.x, not an upgrade: one log in place of the buses, compile-time binding in place of reflection, a failure ledger in place of acknowledged failures, and the Manifest in place of OpenAPI extensions. A 9.x service moves once, with an agent Skill for the source and a tool for the data ([Migrating from 9.x](../migrate/)).

Next: build a service in the [Quickstart](./quickstart).
