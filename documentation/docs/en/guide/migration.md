---
title: Migration Guide
description: Select a Wow migration path and keep source, runtime, storage, data, and production evidence separate.
---

# Migration Guide

“Migration” is not one compatibility claim. Record five independent scopes before changing anything:

| Scope | Question | Typical evidence |
|---|---|---|
| Source | Does application code compile against the pinned target API? | compiler, unit tests, generated metadata diff |
| Runtime | Does the target lifecycle/configuration start, become ready, process work, and stop correctly? | integration tests, readiness, graceful-shutdown trace/log |
| Storage | Can the target read/write the exact event, snapshot, Redis/Mongo, and BI layouts? | tag-to-tag contract diff, offline inventory, format test |
| Data | Are counts, versions, request IDs, indexes, replayed state, and read models reconciled? | manifest, checksums, representative/full reconciliation |
| Cutover | Is the approved production revision live and observable, with a rehearsed rollback? | deployment digest/revision, live traffic, alert and rollback evidence |

A green local build can close the source gate. It does not close the other four.

## Choose a Migration Path

| Current system | Primary path | Why |
|---|---|---|
| CRUD/transaction scripts/direct table writes, no Wow history | [Migrating from Traditional Architecture](./migration/traditional-architecture.md) | Establish commands, aggregates, events, import, and traffic ownership |
| Exact Wow v6 tag | [Migrate Wow v6 to v8](./migration/v6-to-v8.md) | Diff pinned platform/API/storage contracts and perform a hard data cutover where required |
| Wow v8 with custom dispatcher/message-bus/Spring lifecycle ownership | [Runtime Orchestration Migration](./migration/runtime-orchestration.md) | Move lifecycle source code to the unified `WowRuntime`; this is not automatically a data migration |
| Wow v8.16.x using old query APIs or `SnapshotRepository` | [V9 Query Migration](./query/v9-query-migration.md) | Migrate Gateway/Backend, filters, masking, SnapshotStore, and Spring bean names |

Do not combine first adoption and a v6→v8 upgrade into one undifferentiated release. Select a bounded context and an
exact source/target version for each change window.

## Documentation Boundaries

| Page | Owns | Does not own |
|---|---|---|
| Traditional architecture | Domain boundary, historical import, shadow catch-up, read/write cutover | Wow version/platform upgrade assumptions |
| v6→v8 | Pinned Gradle/platform matrix, source breaks, storage formats, data cutover | Redesigning every domain |
| Runtime orchestration | `RuntimeComponent`, message receiver admission, Spring lifecycle ownership, shutdown | Event/snapshot format conversion unless another section requires it |
| V9 query migration | Query Gateway/Backend, filter/masking, SnapshotStore naming, and the Condition migration window | Deployment or production cutover proof |
| Runtime lifecycle | Stable post-migration semantics | The migration procedure itself |

The [release notes](https://github.com/Ahoo-Wang/Wow/releases) describe version changes. The selected tag's source,
tests, and build files are the exact contract; `main` is evidence for the current target only.

## Shared Completion Gates

Advance only when the current gate has reproducible evidence:

1. **Scope:** pin bounded context, source tag, target tag, datasets/stores, owners, and exclusions.
2. **Baseline:** make source tests green; inventory events/snapshots/keys/collections/read models; create and restore-test
   a backup.
3. **Rehearsal:** run the same migration tool and manifest against a production-shaped isolated copy.
4. **Verification:** compile, start, process, replay, reconcile, and gracefully stop the target; verify failure paths.
5. **Cutover:** stop admission, drain old writers, migrate once, start one target instance, then move a controlled
   traffic slice.
6. **Observation:** verify metrics/traces, backend versions, projection/BI lag, alerts, and business invariants.
7. **Closure:** remove old writers/data/bridges only after the rollback window ends.

Rollback must say what happens before and after the first target-version production write. Restoring only the old
binary after a new storage-format write is not a rollback.

## Legacy Link Navigation

The former single-page topics now live in the three focused guides. These headings and explicit aliases preserve old
deep links.

### Version Upgrade Guide

<span id="upgrade-steps"></span>
<span id="dependency-version-update"></span>
<span id="breaking-changes-check"></span>

See [v6 → v8: General Upgrade Steps](./migration/v6-to-v8.md#general-upgrade-steps).

### Migrating from Traditional Architecture

<span id="migration-strategy"></span>
<span id="gradual-migration"></span>
<span id="migration-steps"></span>

See [Traditional Architecture: Migration Overview](./migration/traditional-architecture.md#migration-overview).

### Data Migration

<span id="historical-data-import"></span>

See [Traditional Architecture: Import and Catch Up with One Writer](./migration/traditional-architecture.md#_2-import-and-catch-up-with-one-writer).

### Code Migration

<span id="from-crud-to-command-pattern"></span>
<span id="from-direct-queries-to-query-snapshots"></span>

See [Migrate the Boundary Before the Tables](./migration/traditional-architecture.md#_1-migrate-the-boundary-before-the-tables)
and [Reconcile, Then Move Reads and Writes Separately](./migration/traditional-architecture.md#_3-reconcile-then-move-reads-and-writes-separately).

### Compatibility Notes

<span id="data-format-compatibility"></span>
<span id="event-upgrades"></span>
<span id="message-format-compatibility"></span>

See [Continue Evolving the Domain Model](./migration/traditional-architecture.md#_4-continue-evolving-the-domain-model)
and [v6 → v8: Breaking Changes Check](./migration/v6-to-v8.md#breaking-changes-check).

### Known Issues

<span id="version-specific-issues"></span>
<span id="common-migration-issues"></span>

See the [Release Notes](https://github.com/Ahoo-Wang/Wow/releases) and
[Troubleshooting](./troubleshooting.md). Reproduce a failure against the exact pinned tag before applying a workaround.

### Migration Checklist

Use the [Traditional Architecture Completion Checklist](./migration/traditional-architecture.md#completion-checklist)
or [v6 → v8 Verification Checklist](./migration/v6-to-v8.md#verification-checklist), then add environment-specific
production admission evidence.

### Rollback Plan

Use the selected guide's rollback procedure and the before/after-first-write distinction in
[Shared Completion Gates](#shared-completion-gates).

### Unified Runtime Orchestration

See [Runtime Orchestration Migration](./migration/runtime-orchestration.md).

<span id="versioned-snapshot-checkpoint-removal"></span>

### Removal of Versioned Snapshot Checkpoints

See [v6 → v8: Versioned Snapshot Checkpoint Removal](./migration/v6-to-v8.md#versioned-snapshot-checkpoint-removal).

### Atomic SnapshotStore Saves

See [v6 → v8: Atomic SnapshotStore Saves](./migration/v6-to-v8.md#atomic-snapshotstore-saves).

### Redis EventStore Canonical v2 Layout (introduced in v8.9.0)

See [v6 → v8: Redis EventStore Canonical v2 Layout](./migration/v6-to-v8.md#redis-eventstore-canonical-v2-layout-introduced-in-v8-9-0).

### Mongo Ownership Guard

See [v6 → v8: Mongo Ownership Guard](./migration/v6-to-v8.md#mongo-ownership-guard).

## Related Pages

| Page | Relationship |
|---|---|
| [Migrating from Traditional Architecture](./migration/traditional-architecture.md) | First adoption and traffic ownership |
| [Migrate Wow v6 to v8](./migration/v6-to-v8.md) | Existing Wow platform/storage upgrade |
| [Runtime Orchestration Migration](./migration/runtime-orchestration.md) | Unified lifecycle source migration |
| [V9 Query Migration](./query/v9-query-migration.md) | V8.16.x to V9 query and SnapshotStore source migration |
| [Runtime Lifecycle](./advanced/runtime-lifecycle.md) | Stable runtime model after migration |
| [Troubleshooting](./troubleshooting.md) | Evidence-first diagnosis when a gate fails |

<!-- Sources: current migration subpages, v6/v8 tags, WowRuntime, SnapshotStore, Redis/Mongo guards -->
