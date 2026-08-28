---
title: Executive Guide
description: Decide whether to fund a bounded Wow pilot using a verifiable business slice, ownership, and operating evidence.
---

# Executive Guide

This page answers one question: **is a bounded Wow pilot worth funding for this business?**

Wow is not an end-user product, and it does not automatically provide business metrics, a production SLA, compliance certification, or organizational productivity gains. It is a framework for building applications around commands, domain events, event-sourced state, and explicit completion stages. [Introduction](../guide/introduction.md) is the authority for value and adoption cost.

## Decision inputs

### Does the business fit?

Prefer a business slice with known needs such as:

- decision rules and invariants matter more than CRUD field updates;
- the business needs to explain what happened and rebuild state from events;
- write acceptance, projections, and downstream processing need distinct completion stages;
- retry, idempotency, compensation, or audit are product responsibilities rather than incidental implementation details.

Choose a simpler architecture first when the need is mainly synchronous CRUD, business history has no value, or the organization cannot own eventual consistency and event evolution.

### Who owns the continuing cost?

Before the pilot, name owners for each responsibility area without inventing headcount or budget:

| Responsibility | Required answer |
|---|---|
| Domain model | Who approves commands, invariants, event semantics, and evolution rules? |
| Platform and dependencies | Who maintains JVM/Wow versions, storage, messaging, build, and release? |
| Operations | Who owns stage-aware monitoring, backup, replay, recovery, and bounded shutdown? |
| Product | Who defines user-visible waiting, failures, duplicate requests, and recovery? |
| Security and data | Who verifies authentication, authorization, tenant isolation, retention, and access audit? |

A capability without an owner is not an established capability.

## Pilot completion evidence

Select one real but reversible business slice and deliver observable evidence:

1. A domain specification proves that a command produces the expected event and state.
2. A running service accepts a real HTTP command.
3. The command result identifies the requested completion stage instead of reporting generic “success.”
4. Event-sourced state can be read or rebuilt at a version.
5. If the journey depends on a read model, projection visibility is verified separately.
6. At least one failure, duplicate-request, or recovery path is rehearsed and recorded.
7. Dependency, data, operations, security, and rollback ownership is assigned.
8. Unverified throughput, latency, availability, cost, or production-recovery capability is marked as missing evidence.

[Getting Started](../guide/getting-started.md) supplies a minimal technical proof, not production admission. A production decision also needs environment evidence from [Best Practices](../guide/best-practices.md) and [Observability](../guide/advanced/observability.md).

## Decision gate

| Outcome | Condition |
|---|---|
| Fund a bounded pilot | The business fits, the slice is reversible, owners are named, and the evidence above is the acceptance contract |
| Continue discovery | The value hypothesis is credible, but data, operations, security, or recovery ownership remains unresolved |
| Do not adopt now | A simpler design meets the need, or the organization is not ready to own event evolution and operations |

Do not substitute repository test counts, sample benchmarks, or a framework feature list for the business's baseline. Expand adoption only from the pilot's actual results against pre-agreed business outcomes and risk boundaries.

## Prioritized next path

1. **Validate business fit**: have product ownership complete the workflow decision in the [Product Manager Guide](./product-manager-guide.md).
2. **Validate technical and operating fit**: have architecture ownership complete the boundary and evidence matrix in the [Staff Engineer Guide](./staff-engineer-guide.md).
3. **Run the pilot**: establish first success with [Getting Started](../guide/getting-started.md), then replace it with the real business slice.
