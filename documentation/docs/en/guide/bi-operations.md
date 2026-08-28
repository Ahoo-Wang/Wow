---
title: BI Deployment and Recovery
description: Ownership, Deploy, Reset, interruption recovery, acceptance, and rollback for Wow BI.
---

# BI Deployment and Recovery

This runbook applies to the current Wow BI protocol/layout only. It does not migrate an older registry or SQL layout
in place. Archive the old physical scope and offsets before introducing the current owner.

## Operational Boundary

One writer owns one physical BI scope: `database`, `consumerDatabase`, `consumerGroupNamespace`, and topology. The
same external lock must cover catalog inspection, script generation, review, and ordered execution. The internal
ownership registry makes interrupted DDL recoverable; it is not a distributed lock.

| Operation | Data effect | Required inspection |
|---|---|---|
| `DEPLOY` | Creates missing objects, repairs computed objects, resumes owned pending work, retires/removes owned stale objects according to the plan | ClickHouse inspector for production |
| `RESET` | Drops and rebuilds the owned current layout and starts a replay generation | Available authoritative inspection plus `replayFromEarliestConfirmed=true` |

`wow.bi.script.enabled` defaults to `true`. Protect `/wow/bi/script` as an administrative route or disable it. The
default NoOp inspector is limited to initial/offline preview and cannot authorize Reset.

The SQL executor must preserve statement order and stop at the first error. Never run two scripts concurrently or
replay an old file after catalog state changes.

## Operation Decision

| Observed catalog/registry state | Action | Reason |
|---|---|---|
| Empty target scope | `DEPLOY` | Installs registry, stores, ingress, views, and a `STABLE` anchor |
| Current scope and matching durable contracts | `DEPLOY` | Idempotent reconciliation |
| Computed view/materialized-view drift | `DEPLOY` | Records `PENDING_UPDATE`, replaces definition, verifies, then returns to `ACTIVE` |
| Owned `PENDING_CREATE`, `PENDING_UPDATE`, or `PENDING_DROP` | Regenerate the same `DEPLOY` | Registry is write-ahead recovery evidence |
| Missing `ACTIVE`/`RETIRED` object or surviving `TOMBSTONE` | Confirmed `RESET` after backup | Catalog no longer matches recoverable ownership state |
| Store, Kafka queue, or topology contract drift | Confirmed `RESET` | The generator does not mutate these durable contracts in place |
| Registry engine/comment/sort key/columns invalid or older protocol/layout | Archive/drop the incompatible scope, then `RESET` | Ownership cannot be trusted |
| Anchor phase `RESETTING` | Continue `RESET` with identical physical-scope configuration | Reuses the recorded reset consumer identity |
| `STABLE` anchor but incomplete ingress | `DEPLOY` | Recreates missing queue/consumer materialized views |

Do not infer ownership from a familiar table name. Only a validated current registry and `wow-bi:` metadata may
authorize destructive cleanup.

## Preflight

1. Pin the application/Wow version, BI protocol/layout, request options, and generated client version.
2. Stop every old BI consumer/writer for this scope and acquire the external lock.
3. Configure `wow.bi.script.inspector.type=CLICKHOUSE`; verify endpoints, credentials, timeout, and replica access.
4. Record database, consumer database, namespace, topology, cluster/installation, topic prefix, Kafka servers, offset
   storage, and configuration fingerprint.
5. Back up/clone the ClickHouse scope; capture registry HEAD/entries, anchor Comment, object DDL, row counts, aggregate
   max versions, Kafka offsets, and retention evidence.
6. For Reset, prove the required history still exists and a new group will use earliest. Verify Keeper prerequisites
   when Keeper offsets are selected.
7. Generate JSON, review `destructive` and every diagnostic, then review the ordered SQL. An unexplained diagnostic is
   a stop condition.

Local generator/module checks validate code and deterministic SQL. They do not prove credentials, replica agreement,
Kafka retention, live traffic, or production change admission.

## Execute Deploy

1. Reinspect and regenerate `DEPLOY` while holding the lock; retain the request and inspection timestamp.
2. Execute statements exactly in response order, stopping on the first failure.
3. On interruption, discard the old script, inspect the new catalog state, and regenerate `DEPLOY` with identical
   scope configuration.
4. After SQL completes, run a fresh authoritative inspection and require a `STABLE` anchor, registry HEAD consistency,
   no unexplained pending state, and complete ingress.
5. Keep the lock until the acceptance checks below finish.

The registry persists pending mutation state before object DDL, then records `ACTIVE`/`TOMBSTONE` only after
verification. This is why regeneration is safe and guessed statement resumption is not.

## Execute Reset

Reset is a data-loss/replay operation inside the owned BI scope:

1. Obtain explicit approval for full rebuild, confirm backups and Kafka retention, and keep all consumers stopped.
2. Generate `RESET` with `replayFromEarliestConfirmed=true`; require `destructive=true`.
3. Execute in order. If interrupted, inspect again:
   - `RESETTING` anchor → regenerate `RESET` with identical scope/configuration;
   - `STABLE` anchor with missing ingress → generate `DEPLOY`;
   - incompatible/missing registry → stop and restore or manually archive; do not guess ownership.
4. When Reset finishes, generate one fresh authoritative `DEPLOY` and execute any remaining reconciliation.
5. Keep the old scope/backup immutable through the rollback window.

## Acceptance

Accept the deployment only when all applicable evidence is recorded:

- inspector validates registry engine, replication path, sorting key, Comment, complete column schema, HEAD revision,
  and object snapshot fingerprint;
- anchor is `STABLE`; no unexplained pending entry exists; no `TOMBSTONE` object survives;
- required stores, queues, consumers, public views, and expansion views exist, and computed SQL/`TO` targets match;
- every cluster replica agrees on object shape and metadata;
- Kafka consumption advances, earliest/latest offset samples are retained, and consumer errors remain zero;
- command/state/latest/expansion row counts and representative aggregate maximum versions reconcile to the source;
- dashboards, alerts, and the operational route's authorization are verified against the deployed revision.

A green local build or SQL exit code is only one item in this list, not production admission.

## Rollback

Prefer completing current-version recovery while the registry is in a current pending state. Older clients may reject
protocol/layout 3/7 or misread pending phases.

If rollback is required:

1. stop consumers and regain the same scope lock;
2. capture writes/offset progress since cutover;
3. restore the previous application, ClickHouse scope, offset state, and configuration snapshot as one unit;
4. reconcile or intentionally discard post-cutover analytical data under an approved plan;
5. verify the restored reader before reopening traffic.

Setting `wow.bi.script.enabled=false` only removes the route/OpenAPI operation/inspector wiring. It does not stop
ClickHouse Kafka engines, restore data, or roll back offsets.

See [Business Intelligence](./bi) for the generated contract and
[Migrate Wow v6 to v8](./migration/v6-to-v8) for cross-version gates.

<!-- Sources: BiOwnershipRegistry/Plan, ClickHouseOwnershipRegistryRenderer/CatalogReader,
BiScriptAssembly/Operation, ClickHouseBiDeploymentInspector, and related tests -->
