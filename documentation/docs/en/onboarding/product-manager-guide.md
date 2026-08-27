---
title: Product Manager Guide
description: Decide whether a product workflow needs Wow's command, event, state, and completion-stage model.
---

# Product Manager Guide

This page answers one question: **does this product workflow need Wow's command → event → state model?**

Wow is an application framework, not a ready-made business product. Start the product decision from user intent, business facts, visible state, and failure recovery—not from Kafka, MongoDB, or a module list.

## Decision inputs

Select one real workflow and answer these questions with domain and engineering ownership:

| Input | Required answer |
|---|---|
| User intent | What does the user ask the system to do? Is it a command that can be accepted or rejected? |
| Business fact | What immutable fact happened after acceptance? Can business stakeholders understand the event name? |
| Current state | Which state is rebuilt from events, and which data is only a derived read model? |
| Completion stage | Does the user need to know the request was sent, processed, snapshotted, or visible in a projection? |
| Failure experience | How do validation, domain rejection, duplicate requests, timeouts, and downstream failures differ? |
| Recovery ownership | Which failures retry automatically, and which need an operator? Compensation does not erase facts that happened. |
| Data and security | Who defines identity, tenant scope, retention, deletion, audit, and sensitive-field policy? |

Use [Command Gateway](../guide/command-gateway.md) for exact command stages and [Projection](../guide/projection.md) for the read-model boundary.

## Fit and non-fit

### Better fit

- The workflow has business invariants that need one protected boundary.
- The business needs traceable change history or versioned state reconstruction.
- Write acceptance, domain processing, snapshot completion, and projection visibility must be expressed separately.
- Duplicate requests, asynchronous processing, and recovery are explicit product cases.

### Prefer a simpler design first

- The workflow is mainly synchronous CRUD and change history has no business value.
- The experience requires immediate consistency, but the team will not design waiting, timeouts, and recovery.
- The product only needs report reads, not a command-side domain model.
- The adoption reason is technology standardization rather than a specific business decision that benefits.

## Product acceptance slice

Before roadmap commitment, write one verifiable workflow contract:

```text
Given  known business state and permissions
When   the user sends a command
Then   the system accepts it or rejects it with an explicit reason
And    acceptance produces a named domain event
And    the event rebuilds versioned state
And    UI/API promises only the requested completion stage
And    timeout, duplicate, and downstream failure have actionable paths
```

Completion evidence includes at least:

- a domain specification covering acceptance and rejection;
- a real interface result with aggregate ID, version, and observable requested stage;
- separate proof of `PROJECTED` or actual read-model visibility when the product depends on a query screen;
- retry and operator recovery described without claiming transaction rollback;
- application or platform ownership for retention, deletion, permissions, and operator audit;
- latency, throughput, availability, and cost targets derived from this product's environment and acceptance baseline—not framework samples.

Repository examples and tests can establish framework behavior; they cannot replace product production data, user research, or service-level evidence.

## Prioritized next path

1. **Workflow fits**: turn commands, events, state, and invariants into a specification with [Modeling](../guide/modeling.md).
2. **An asynchronous read model is required**: continue with [Projection](../guide/projection.md) and [Query](../guide/query.md), and choose the user-visible completion stage.
3. **Failure recovery is required**: use [Event Compensation](../guide/event-compensation.md) and [Recovery](../guide/recovery.md) to define automatic and operator boundaries.
4. **Workflow does not fit**: stop introducing Wow; choosing the simpler design needs no extra ceremony.
