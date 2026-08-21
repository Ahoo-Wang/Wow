---
title: Backup, Restore, and Replay
description: Design EventStore recovery, state rebuilds, projection reconciliation, message offsets, and rollback gates for a Wow application.
outline: deep
---

# Backup, Restore, and Replay

Database vendor documentation explains how to back up a database. This page defines what a restored Wow application must prove. The goal is not merely to start a process, but to restore a consistent flow:

```text
EventStore → aggregate state/snapshots → projections and queries → sagas/event processors
```

## Separate Authoritative and Derived Data

| Data | Role | Recovery requirement |
| --- | --- | --- |
| Domain event streams | Authoritative aggregate history | Must be restored without losing event order, count, or `requestId` |
| Snapshots | Aggregate-loading checkpoints and optional query store | Rebuildable from events, but backups can reduce RTO |
| Custom projections/query models | Read-oriented derived data | Must have a replay/rebuild procedure or an independently verified backup |
| Broker messages and consumer offsets | Asynchronous work that may still be pending | Must be coordinated with the EventStore recovery point |
| Compensation records | Failure processing and operator recovery state | Incomplete failures cannot disappear during restore |
| Unique indexes, context ownership, and routing metadata | Idempotency, tenant isolation, and backend ownership | Restore and validate them with business data |

The event stream is authoritative for aggregate state, but that does not make every other store disposable. External side effects, compensation work, and consumer offsets cannot be recovered by aggregate replay alone.

## Questions the Recovery Plan Must Answer

1. What are the RPO and RTO?
2. Is the recovery point a database timestamp, event version, or release window?
3. How are commands, consumers, and scheduled jobs prevented from writing during recovery?
4. Which projections, sagas, or processors call non-repeatable external systems?
5. If broker offsets are earlier than the restore point, how are duplicates handled? If later, how are skipped events recovered?
6. Can the rollback application read event revisions written after the selected restore point?

Without these answers, “backup succeeded” is not evidence that recovery works.

## Backup Procedure

### 1. Freeze the Inventory

Record, for each bounded context and aggregate:

- EventStore, SnapshotStore, and query backends;
- topics, consumer groups, partitions, and offsets;
- tenant, owner, space, and storage routing;
- `requestId` and aggregate uniqueness indexes;
- compensation stores, context ownership records, and encryption-key versions;
- application version, Wow version, configuration digest, and schema/revision distribution.

### 2. Choose a Consistent Cutoff

The simplest reliable method is to stop command ingress and asynchronous consumers, drain admitted work, and then create backups. For online backups, use backend point-in-time snapshots and record the actual cutoff of every database and broker. Do not assume snapshots across systems are atomic.

### 3. Preserve Evidence with the Backup

Keep at least:

- event-stream count and maximum version per aggregate/tenant;
- revision and event-name distribution;
- snapshot count, maximum version, and update time;
- critical projection counts and business totals;
- consumer-group offsets and lag;
- backup checksums, tool versions, and restore commands.

A backup file without a baseline cannot reveal missing or duplicated data after restore.

## Isolated Restore Sequence

1. **Create an isolated environment** with no business traffic or external side effects and separate databases, topics, and credentials.
2. **Restore the EventStore and auxiliary metadata**, including uniqueness indexes, context ownership, and routing records.
3. **Validate stream structure** per aggregate: contiguous versions, head version, event count, revisions, and deserialization.
4. **Start the candidate with ingress closed**, pointing only to restored copies and non-production external systems.
5. **Restore or rebuild snapshots** through generated snapshot-regeneration routes or adapter batch tooling; no snapshot version may exceed the EventStore head.
6. **Rebuild projections and query models** with an explicit clear, replay, idempotency, and resume policy for each projection.
7. **Coordinate broker offsets**: before rewinding, prove every handler is repeatable; before keeping later offsets, prove no event will be skipped.
8. **Restore compensation work**, distinguishing pending, running, succeeded, and unrecoverable operations to prevent duplicate external calls.
9. **Reconcile and accept** before opening read-only traffic and then gradually restoring command ingress.

Never experiment with replay directly against production stores, and do not allow a recovery environment to call real payment, notification, or third-party APIs.

## Reconciliation Matrix

| Boundary | Verify at least |
| --- | --- |
| Event streams | Aggregate/tenant counts, contiguous versions, head version, event names, and revision distribution |
| Idempotency | `requestId` uniqueness per aggregate; retrying a handled request is still rejected |
| State | Replay from versions `1..head` matches the pre-restore baseline or business totals |
| Snapshots | `snapshot.version <= event head`; sampled state matches a full replay |
| Projections | Row counts, critical monetary/quantity totals, tenant isolation, deletion state, and index plans |
| Sagas/processors | Redelivery creates no duplicate commands, charges, notifications, or omissions |
| Broker | Topics/partitions, consumer-group offsets, lag, and dead-letter/retry queues |
| Runtime | Health checks, traces, metrics, alerts, and graceful shutdown still work |

Sampling finds only some defects. High-risk domains such as money, inventory, and authorization require full business reconciliation.

## Acceptance Requests

Keep repeatable evidence for at least:

1. loading an aggregate that requires its full event stream;
2. reading a restored snapshot and comparing it with full replay;
3. querying one custom projection and tracing it to source events;
4. retrying a historical command with its stable `requestId` and observing no duplicate execution;
5. submitting a new test command and verifying `PROCESSED`, `SNAPSHOT`, and any required `PROJECTED` stage;
6. restarting the recovery environment and rechecking state and consumer offsets.

## Rollback Gates

- Keep the original backup read-only and do not overwrite the last known-good restore point.
- Isolate writes produced by the recovery drill from production namespaces.
- Record the combination of application, configuration, event upgraders, and database schema versions.
- Prove the previous application can read the current event revisions before rollback.
- If traffic has already reopened, stop it and freeze incremental writes before choosing roll-forward or rollback.

“Restore the database” is not a complete rollback. Broker offsets, external side effects, and events written after recovery must also be handled.

## Drill Completion

Run drills from an empty environment rather than restoring over an existing test database. Record duration, data volume, reconciliation results, untested boundaries, and ownership. Mark recovery complete only when measured restore time meets RTO, data loss meets RPO, and full reconciliation passes for high-risk domains.

## Related Pages

- [Event Store](./eventstore.md)
- [Snapshot](./snapshot.md)
- [Event Evolution](./advanced/event-evolution.md)
- [Event Compensation](./event-compensation.md)
- [MongoDB](./extensions/mongo.md), [Redis](./extensions/redis.md), and [Elasticsearch](./extensions/elasticsearch.md)
- [BI Deployment and Recovery](./bi-operations.md)
- [Production Best Practices](./best-practices.md)
