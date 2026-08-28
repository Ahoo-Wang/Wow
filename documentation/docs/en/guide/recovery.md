---
title: Backup, Restore, and Replay
description: Treat EventStore as aggregate authority, then restore Wow snapshots, projections, message offsets, compensation state, and rollback evidence.
outline: deep
---

# Backup, Restore, and Replay

Database tools create backup files. Wow recovery is complete only after this flow is restored and proven:

```text
EventStore → Aggregate/StateEvent → Snapshot/Projection/Processor/Saga
                     ↕
                Broker offsets
```

A recovery operation may execute handlers again. Isolate traffic and external effects and prove idempotency before any replay.

## Separate Authoritative and Derived Data

| Data | Wow role | Recovery owner and requirement |
| --- | --- | --- |
| DomainEventStream | Authoritative aggregate history | EventStore owner restores version order, revisions, request IDs, and uniqueness constraints |
| Snapshot | Aggregate-load checkpoint; with `all`, it can also be a current-state query store | Snapshot owner restores a backup or rebuilds from EventStore |
| Projection/external read model | Query-oriented derived state | Each projection owner supplies clear, resume, replay, idempotency, and reconciliation procedures |
| Broker message/offset | Asynchronous work not yet complete | Bus owner coordinates it with the EventStore cutoff to avoid omissions or unverified duplicates |
| Compensation record | Automatic/operator failure-recovery state | Compensation owner preserves two independent dimensions: `ExecutionFailedStatus` (`FAILED` / `PREPARED` / `SUCCEEDED`) and `RecoverableType` (`RECOVERABLE` / `UNKNOWN` / `UNRECOVERABLE`) |
| PrepareKey, context/schema/index, storage-route configuration | Uniqueness, context ownership, and actual binding | Backend and application-configuration owners restore and validate together |
| External side effect | Payment, notification, third-party write | Cannot be rolled back from EventStore automatically; application owner reconciles it |

“Events can be replayed” means aggregate state can be reconstructed. It does not restore a broker record that is already gone or reverse an external effect that already occurred.

## Questions the Recovery Plan Must Answer

1. What are the RPO and RTO for every EventStore binding, SnapshotStore binding, projection store, and broker?
2. Is a consistent cutoff represented by database time, event version, consumer offset, or a shutdown window?
3. Who can close command ingress, dispatchers, schedulers, and real external systems?
4. Which handlers are safe to repeat, and what is each idempotency key?
5. How are duplicates handled when an offset precedes the EventStore cutoff? How are omissions recovered when it follows the cutoff?
6. Can the candidate and rollback application read every event name/revision and backend layout in the backup?
7. Who authorizes, audits, rate-limits, and approves modifying built-in routes?

Without every answer, the valid claim is “a backup was created,” not “recovery was verified.”

## Backup Procedure

### 1. Freeze the Inventory

Build the actual inventory from effective configuration and `storage-routing` instead of assuming every aggregate uses the default backend:

- `context.aggregate` to EventStore/SnapshotStore binding mapping;
- Kafka topic/partition/group/offset or Redis Stream/group/pending state;
- EventStore, Snapshot, Projection, PrepareKey, compensation, and schema/index stores;
- application build identity, locked Wow dependencies, redacted configuration digest, and event-revision distribution;
- idempotency key, external system, and owner for every modifying handler.

### 2. Choose a Consistent Cutoff

The easiest flow to prove is: remove command ingress, stop schedulers that create work, wait for admitted WowRuntime work to quiesce, stop consumers and record offsets, then back up every backend. For online snapshots, record the actual cutoff of each system. Databases and a broker do not become one atomic snapshot merely because commands started at the same time.

### 3. Preserve Evidence with the Backup

Keep a machine-comparable baseline next to the backup:

- stream count, event count, and head version per context/aggregate/tenant;
- event-name/revision distribution and count of deserialization failures;
- snapshot count, maximum version, and violations of `snapshot.version <= event head`;
- high-risk projection totals and tenant/owner/space isolation results;
- consumer offsets, lag, pending entries, retries, and compensation counts grouped separately by `ExecutionFailedStatus` and `RecoverableType`;
- checksums, tool arguments, elapsed time, and actual cutoff.

Without a pre-restore baseline, missing or duplicated data cannot be separated from a historical defect.

## Isolated Restore Sequence

1. **Create an empty isolated environment** with separate databases/indexes/topics/Streams/consumer groups/credentials and no path to real payment, notification, or third-party writes.
2. **Restore EventStore and its constraints**, including events, context/schema data, unique indexes, and every actual binding selected by routing.
3. **Validate event history** per stream: initial version, continuity, head, request IDs, event names/revisions, and deserialization.
4. **Start the candidate with ingress closed** and prove effective configuration points only to restored copies; verify capabilities, templates/indexes, and bean wiring.
5. **Restore or rebuild snapshots** by sampling one aggregate before cursor-based batches. No result version may exceed its EventStore head.
6. **Rebuild projections/query models** for one target function at a time while recording after-id/offset, failures, and resume points. Wow does not supply a universal application-projection clear command.
7. **Coordinate broker offsets**: prove handler idempotency before rewinding; prove no post-cutoff event is skipped before retaining later offsets.
8. **Restore both compensation dimensions**: preserve `ExecutionFailedStatus` as `FAILED`, `PREPARED`, or `SUCCEEDED`, and independently preserve `RecoverableType` as `RECOVERABLE`, `UNKNOWN`, or `UNRECOVERABLE`. Do not infer either dimension from the other, and do not erase failure records because work was redelivered.
9. **Open traffic in stages after reconciliation**: read-only queries, controlled test commands, then business ingress and schedulers.

When `webflux-support` wires the relevant aggregate route, runtime OpenAPI lists these recovery operations:

| Operation | Method and route suffix | Actual behavior |
| --- | --- | --- |
| Regenerate Aggregate Snapshot | `PUT .../{aggregateId}/snapshot` | Replay one aggregate from EventStore and save its Snapshot |
| Batch Regenerate Aggregate Snapshot | `PUT .../snapshot/{afterId}/{limit}` | Rebuild by aggregate-id cursor |
| Resend State Event | `POST .../state/{afterId}/{limit}` | Reconstruct state from EventStore and send StateEvents with a compensation target |
| Event Compensate | `PUT .../{aggregateId}/{version}/compensate` | Compensate one DomainEventStream for the target in the request body |

The full prefix, tenant parameters, and operation ID depend on the aggregate route contract. Read them from the **candidate runtime OpenAPI** instead of guessing from an example. These modifying routes have no separate universal management switch. Put them on a controlled management plane with authorization, audit, batch bounds, and approval. StateEvent resend is not replay of every DomainEvent handler, and Event Compensate is not a full projection rebuild.

## Reconciliation Matrix

| Boundary | Verify at least |
| --- | --- |
| EventStore | Stream counts, version continuity, heads, request-id uniqueness, event names/revisions |
| Aggregate state | Full sourcing from `1..head` matches the business baseline |
| Snapshot | `snapshot.version <= event head`; sampled content equals full replay |
| Projection | Rows, high-risk money/inventory/authorization totals, tenant isolation, deletion state, query plans |
| Processor/Saga | Redelivery creates no duplicate commands, charges, notifications, or omissions |
| Broker | Topic/Stream, partition/group, offsets, lag, pending entries, failure queues |
| Compensation | `ExecutionFailedStatus` distribution, independent `RecoverableType` distribution, retry count, target function, operator decision, external effect |
| Runtime | Stage latency, error rate, traces, alerts, and graceful shutdown still meet the candidate baseline |

Money, inventory, and authorization domains require full business reconciliation. Sampling supplements full structural checks; it does not replace them.

## Acceptance Requests

Execute and retain evidence for at least:

1. Loading an aggregate with no usable Snapshot that requires full replay.
2. Rebuilding that aggregate's Snapshot and comparing its state and version with full replay.
3. Querying a rebuilt projection and tracing it to source event revisions.
4. Retrying the same logical command with a historical `requestId` and observing no second business execution.
5. Sending a new test command and verifying the required `PROCESSED`, `SNAPSHOT`, and exact function stages.
6. Restarting candidate instances and proving EventStore head, Snapshot, consumer offset, and compensation state do not regress.
7. Injecting one recoverable failure and proving the operation resumes from after-id/offset instead of blindly replaying from the beginning.

## Rollback Gates

- Keep the original backup and first restore result read-only. Put rebuild writes in a disposable isolated namespace.
- Prove the rollback binary can read every current event revision, configuration key, and storage layout. Starting is not proof it can process newer events.
- Events, broker offsets, and external effects created after traffic opens are absent from the old backup. Stop traffic and freeze the delta before choosing roll-forward or a coordinated code/data rollback.
- Snapshot/projection rebuilds can be discarded and repeated. EventStore history, compensation records, and external effects cannot be rolled back the same way.
- Retain after-id/limit, target function, caller, timestamp, result, and failure detail for every batch operation.

## Drill Completion

Schedule drills by business RPO/RTO and change risk, starting from a real backup in an empty environment. Completion requires measured restore time within RTO, actual data loss within RPO, all structural checks and high-risk business reconciliation passing, and rollback boundaries verified. Record any uncovered backend, handler, or external system as `MISSING EVIDENCE`; green unit tests cannot replace it.

## Related Pages

- [Event Sourcing](./domain/event-sourcing.md)
- [Snapshots](./domain/snapshot.md)
- [Event Evolution](./domain/event-evolution.md)
- [Event Compensation](./event/compensation.md)
- [MongoDB](./extensions/mongo.md), [Redis](./extensions/redis.md), and [Elasticsearch](./extensions/elasticsearch.md)
- [BI Deployment and Recovery](./bi-operations.md)
- [Production Best Practices](./best-practices.md)
