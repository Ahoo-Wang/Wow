---
title: BI Deployment and Recovery
description: Ownership, Deploy, Reset, interruption recovery, acceptance, and rollback for Wow BI.
---

# BI Deployment and Recovery

This runbook applies to BI layout 8. A deployment of another layout is not migrated in place: DEPLOY rejects it, and a
confirmed RESET drops it and rebuilds it with the current layout (see [Upgrade](#upgrade)).

## Operational Boundary

One writer owns one physical BI scope: `database`, `consumerDatabase`, `consumerGroupNamespace`, and topology. Catalog
inspection, script generation, review, and ordered execution must run under the same external lock; the generator
provides no distributed lock.

| Operation | Data impact | Required inspection |
|---|---|---|
| `DEPLOY` | Creates missing objects, repairs drifted computed objects, drops computed objects and queues that are no longer desired; stores are only created or kept, never dropped | ClickHouse inspector in production |
| `RESET` | Drops and rebuilds every object this scope owns and starts a new replay generation | Available authoritative inspection plus `replayFromEarliestConfirmed=true` |

`wow.bi.script.enabled` defaults to `true`. Protect `/wow/bi/script` as an administrative route or disable it. The
default NoOp inspector is for first-time/offline previews only and cannot authorize Reset.

The SQL executor must preserve statement order and stop on the first error. Never run two scripts concurrently or
replay an old file after the catalog changes.

## Ownership and the Anchor

Every BI object's comment starts with `wow-bi:` and records the layout, `deploymentId`, object kind, and owning
aggregate. Only objects whose `deploymentId` matches the current scope are changed or dropped; familiar table names
never establish ownership.

The `__wow_bi_deployment` anchor is the last statement of every script and records the deployment-level facts:

- the phase (`STABLE` or `RESETTING`), configuration fingerprint, topology fingerprint, and consumer identity;
- the durable inventory: every store and queue that has been created, either `ACTIVE`, or `RETIRED` (the aggregate
  was removed and the store was kept for its data).

Durable objects are created before they are recorded, so the inventory can lag the catalog but never run ahead of it.
A recorded store or queue that disappears therefore means lost data or lost Kafka offsets: DEPLOY refuses and asks for
RESET instead of quietly creating an empty table.

## Operation Decision

| Observed catalog state | Operation | Reason |
|---|---|---|
| Empty target scope | `DEPLOY` | Installs stores, ingress, views, and a `STABLE` anchor |
| Current scope and matching durable contracts | `DEPLOY` | Idempotent reconciliation: stores, queues, views, and consumers that exist with matching definitions stay untouched and the script only rewrites the anchor; ingestion is not paused |
| Computed view/materialized-view drift | `DEPLOY` | Replaces the drifted definitions; consumer drift pauses and recreates that stream's whole consumer chain |
| A desired store/queue is missing and not recorded | `DEPLOY` | First creation, or completion after an interruption |
| A recorded store/queue is missing | Back up, then confirmed `RESET` | Data or offsets were lost |
| Store, Kafka queue, configuration, or topology contract drift | Confirmed `RESET` (a topology change needs a new scope) | The generator does not mutate durable contracts in place |
| An object or the anchor uses another layout | Confirmed `RESET` | See [Upgrade](#upgrade) |
| Anchor phase is `RESETTING` | Continue `RESET` with the exact same physical-scope configuration | Reuses the recorded reset consumer identity |
| Anchor is `STABLE` but ingress is incomplete | `DEPLOY` | Recreates missing queue/consumer materialized views |

## Preflight

1. Pin the application/Wow version, BI layout, request options, and generated client version.
2. Stop all old BI consumers/writers for the scope and acquire the external lock.
3. Configure `wow.bi.script.inspector.type=CLICKHOUSE`; verify endpoints, credentials, timeouts, and replica access.
4. Record database, consumer database, namespace, topology, cluster/installation, topic prefix, Kafka servers, offset
   storage, and the configuration fingerprint.
5. Back up or clone the ClickHouse scope; retain the anchor comment, object DDL, row counts, aggregate max versions,
   Kafka offsets, and retention evidence.
6. Before Reset, prove that the required history still exists and that the new group starts from earliest; with Keeper
   offsets, verify its prerequisites.
7. Generate JSON, review `destructive` and every diagnostic, then review the ordered SQL. Stop on any unexplained
   diagnostic.

Local generator/module checks prove code and deterministic SQL only. They do not prove credentials, replica
consistency, Kafka retention, live traffic, or production change approval.

## Execute Deploy

1. Re-run inspection under the lock and generate `DEPLOY`; keep the request and inspection time.
2. Execute statements in response order and stop at the first failure.
3. After an interruption, discard the old script, inspect the new catalog state, and regenerate `DEPLOY` with the
   exact same scope configuration. Every statement can be re-run, so the regenerated script converges from the current
   catalog; guessing a resume point inside the old script is unsafe.
4. After the SQL completes, run authoritative inspection again and require a `STABLE` anchor and complete ingress.
5. Keep the external lock until acceptance below is complete.

## Execute Reset

Reset drops data in the managed BI scope and replays:

1. Obtain explicit approval for the full rebuild, confirm backups and Kafka retention, and keep all consumers stopped.
2. Generate `RESET` with `replayFromEarliestConfirmed=true` and require `destructive=true`.
3. Execute in order; if interrupted, inspect again:
   - anchor `RESETTING` → regenerate `RESET` with the exact same scope/configuration;
   - anchor `STABLE` but ingress missing → generate `DEPLOY`.
4. After Reset completes, generate and execute one fresh authoritative `DEPLOY`. Reset writes its anchor before Kafka
   ingress and records only the stores; this DEPLOY records the queues and completes the remaining reconciliation.
5. Keep the old scope/backup immutable for the rollback window.

## Acceptance

Accept a deployment only after recording all applicable evidence:

- the anchor is `STABLE`, and its layout, configuration, and topology fingerprints match the request;
- required stores, queues, consumers, public views, and expansion views exist with matching computed SQL/`TO` targets;
- every cluster replica has the same object structure and metadata;
- Kafka consumption progresses, earliest/latest offset samples are retained, and consumer errors are zero;
- command/state/latest/expansion row counts and representative aggregate max versions reconcile with sources;
- dashboards, alerts, and operational route authorization are verified against the deployed revision.

A green local build or SQL exit code is one input, not production acceptance.

## Upgrade

Layout 8 takes effect in Wow 9.4.0. It removes the ownership registry and records the deployment-level facts and the
durable inventory on the anchor. A deployment of an older layout is not migrated in place:

1. Confirm, as for [Execute Reset](#execute-reset), that Kafka retention covers the history to replay.
2. Generate and execute a confirmed `RESET` with 9.4.0. It recognizes the ownership of the old objects, drops them,
   and rebuilds the scope with layout 8.
3. Run the follow-up `DEPLOY` from step 4.
4. Drop the registry table that is no longer used:

   ```sql
   DROP TABLE IF EXISTS `<consumerDatabase>`.`__wow_bi_registry_<deploymentId>` [ON CLUSTER '<cluster>'] SYNC;
   ```

## Rollback

When rollback is required:

1. Stop consumers and reacquire the same scope lock.
2. Preserve post-cutover writes/offset progress.
3. Restore the old application, ClickHouse scope, offset state, and configuration snapshot as one unit.
4. Reconcile or explicitly discard post-cutover analytics data according to the approved plan.
5. Verify restored readers before reopening traffic.

Older versions reject a layout 8 deployment, so rolling back to a version before 9.4.0 must also restore the backed-up
ClickHouse scope.

Setting `wow.bi.script.enabled=false` removes only route/OpenAPI operation/inspector wiring. It does not stop
ClickHouse Kafka engines, restore data, or roll back offsets.

See [Business Intelligence](./bi) for the generated contract and [Wow v6 to v8 migration](./migration/v6-to-v8) for
cross-version gates.

<!-- Sources: BiObservedDeploymentPolicy, BiScriptAssembly (durableInventory), BiObjectMetadata/BiAnchorState,
ClickHouseCatalogReader, ClickHouseBiDeploymentInspector, and related tests -->
