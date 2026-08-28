---
title: Events and Collaboration
description: Choose the right event collaboration mechanism for ordinary side effects, cross-aggregate commands, read-model updates, and durable processing-failure recovery.
outline: deep
---

# Events and Collaboration

After a domain event enters EventStore, it is authoritative history. The downstream collaboration in this section runs after the append. It consumes committed facts, but its effects are not part of the source aggregate transaction and cannot roll back an appended event.

## Choose a Processing Mechanism

| Need | Choose | Responsibility boundary |
| --- | --- | --- |
| Ordinary side effects such as notifications, audit, cache invalidation, or external integration | [Processor](./processor.md) | Invoke matching functions; the side effect owns idempotency and recovery |
| An event must generate cross-aggregate follow-up commands | [Saga](../event/saga.md) | Map the event to 0..N commands and cross the command-send boundary |
| A failed processing function needs durable recording, scheduling, and replay | [Event Compensation](./compensation.md) | Persist failure-recovery state; it does not express a business reverse action |
| Maintain a query read model | Projection | Own only the read model, not ordinary integrations or cross-aggregate orchestration |

Choose from the required business result, not from annotation names. One flow may use several branches, but their completion signals remain independent.

## Typical Collaboration Path

```text
command -> aggregate -> append domain event
                         |-> Processor -> ordinary side effect
                         |-> Saga -> 0..N follow-up commands
                         `-> processing failure -> Compensation
```

- The source event is already committed; a downstream failure is not an EventStore rollback.
- `EVENT_HANDLED` means only that the matching Processor function completed.
- `SAGA_HANDLED` means only that the matching Saga function completed and its command sends completed; it does not mean those commands were processed.
- For caller-visible waiting at these stages, see [Completion Semantics](../command/completion.md).

## Reading Order

1. Start with [Event Processor](./processor.md) for ordinary application or integration side effects.
2. Read [Saga](../event/saga.md) when an event must drive other aggregates.
3. When a processing failure needs durable recovery, continue to [Event Compensation](./compensation.md).
