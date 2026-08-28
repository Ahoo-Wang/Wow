---
title: Migrate Wow v6 to v8
description: Upgrade a pinned Wow v6 system to a pinned v8 target with separate source, runtime, storage, data, and cutover gates.
---

# Migrate Wow v6 to v8

This guide is for an application with existing Wow v6 event/storage history. It is not a rolling dependency bump.
Pin the exact source and target tags, commits, build contracts, backend layouts, and rollback datasets.

The worked matrix uses self-consistent tags verified from repository objects:

| Contract | Source `v6.20.16` (`744d4b1358a3`) | Target `v8.13.1` (`67402d32a76d`) |
|---|---|---|
| `gradle.properties` version | `6.20.16` | `8.13.1` |
| Java toolchain | 17 | 17 |
| Gradle wrapper | 9.4.1 | 9.7.1 |
| Spring Boot | 3.5.11 | 4.1.1 |
| Kotlin / KSP | 2.3.20 / 2.3.6 | 2.4.10 / 2.3.11 |
| CosId / CoAPI / CoCache | 2.15.2 / 1.12.8 / 3.10.5 | 3.2.1 / 2.2.0 / 4.3.0 |

Verify the tag name, `tag^{commit}`, `gradle.properties`, version catalog, wrapper URL/checksum, and your deployed
dependency graph together. Do not substitute another “latest v6” or current `main` and assume the matrix still holds.

## Migration Overview

| Stage | Gate | Required evidence |
|---|---|---|
| 0. Pin baseline | Scope/source | deployed v6 artifact/version, tag commit, clean tests, storage inventory, restorable backup |
| 1. Align platform | Source/runtime | Gradle/JDK/Boot/Jackson/Kotlin/KSP and third-party starter matrix; dependency report |
| 2. Adapt application | Source/runtime | compiled domain/server/tests, regenerated KSP/OpenAPI/schema, startup/readiness/shutdown |
| 3. Convert storage | Storage/data | offline manifest, key/collection/index inventory, checksums, versions, request IDs, replay |
| 4. Hard cutover | Runtime/data | stopped/drained old writers, one target instance, isolated read/write/replay/query checks |
| 5. Production admission | Cutover | approved image/revision, live traffic, metrics/traces/alerts, reconciliation, rollback window |

Do not run old and new writers against a changed storage contract. Rehearse on a copy, then stop ingress, drain all
v6 writers, take the final backup/watermark, migrate once, and start one v8 instance. Scale only after acceptance.

Rollback has two branches: before the first v8 production write, reconnect the immutable v6 dataset and binary;
afterwards, stop v8 and reverse-migrate/replay the new writes before starting v6. Restoring only the cutover backup
would lose accepted work.

## Spring Boot 4 and Jackson 3

The pinned source `v6.20.16` uses Spring Boot 3.5.11; the target uses Boot 4.1.1. Audit application code and every
third-party starter for Boot 4 modularization and configuration changes. In this repository, most Jackson 3 classes
move from `com.fasterxml.jackson` to `tools.jackson`; Jackson annotations remain in their compatible annotation
namespace. Diff the actual source imports and configured `ObjectMapper` modules rather than applying a blind rename.

Pay particular attention to:

- custom serializers/deserializers, mix-ins, modules, and direct `ObjectMapper`/`JsonNode` use;
- Boot auto-configuration imports and starter names;
- remote configuration keys whose Boot 4 prefix changed;
- Mongo/Redis/Elasticsearch client and property binding;
- generated OpenAPI/schema and downstream client compatibility.

Compile success does not prove wire compatibility. Deserialize representative v6 commands/events/snapshots with the
target serializer and compare their materialized state and regenerated contracts.

- [`v6.20.16` version catalog](https://github.com/Ahoo-Wang/Wow/blob/v6.20.16/gradle/libs.versions.toml)
- [`v8.13.1` version catalog](https://github.com/Ahoo-Wang/Wow/blob/v8.13.1/gradle/libs.versions.toml)
- [Spring Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)

## General Upgrade Steps

### Upgrade Steps

1. Capture `./gradlew dependencies` and module-specific `dependencyInsight` output for Wow, Boot, Jackson, Reactor,
   Kotlin, and storage clients.
2. Pin the target platform; update wrapper and version constraints as one reviewed change.
3. Compile production and test sources; fix public API breaks without adding speculative compatibility bridges.
4. Regenerate KSP metadata, OpenAPI/schema, and clients; review the contract diff with consumers.
5. Run unit/module/integration tests plus real startup, readiness, message flow, and graceful shutdown.
6. Execute the storage/data rehearsal and reconciliation below before scheduling cutover.

### Dependency Version Update

Pin one target version through the application's existing version-management mechanism:

::: code-group
```kotlin [Gradle(Kotlin)]
implementation("me.ahoo.wow:wow-spring-boot-starter:8.13.1")
```
```xml [Maven]
<dependency>
    <groupId>me.ahoo.wow</groupId>
    <artifactId>wow-spring-boot-starter</artifactId>
    <version>8.13.1</version>
</dependency>
```
:::

Verify the resolved graph; a declared version does not prove every feature capability or transitive module selected
the same train.

### Breaking Changes Check

Classify every finding before implementation:

| Finding | Compatibility scope | Required action |
|---|---|---|
| Removed/changed Kotlin type or method | Source/JVM binary | Recompile callers and replace only used APIs |
| Changed JSON/message/schema | Wire | Contract test old payloads and downstream consumers |
| Changed lifecycle/config binding | Runtime | Startup/shutdown and environment configuration test |
| Changed key/collection/index/layout | Storage | Offline inventory/migration; no mixed writers |
| Changed derived snapshot/projection/BI shape | Data | Rebuild and reconcile from authoritative events |

Do not infer wire or storage compatibility from source compatibility.

## Unified Runtime Orchestration

Current v8 replaces independent dispatcher launchers with one `WowRuntime` and the `RuntimeComponent` contract. This
is source/runtime breaking but does not change event/snapshot/message formats. Migrate custom lifecycle owners,
`MessageReceiver` readiness/admission, Spring bean destruction, and shared shutdown settings using
[Runtime Orchestration Migration](./runtime-orchestration.md).

Verify all components prepare before processing opens, fatal errors stop the complete runtime, and graceful shutdown
closes admission and drains accepted work. Do not mix old launchers and the canonical runtime.

## Versioned Snapshot Checkpoint Removal

The versioned checkpoint feature that existed in earlier v8 releases is absent from the current target. Removed
contracts include `VersionedSnapshotStore`, `VersionIntervalCheckpointStrategy`, `CompositeSnapshotStrategy`, their
metrics/tracing decorators, and `SnapshotCheckpointProperties`. `wow.eventsourcing.snapshot.checkpoint.*` no longer
controls runtime behavior; checkpoint metrics/spans are not emitted.

Mongo `*_snapshot_checkpoint` collections are not read, written, scanned, migrated, or automatically deleted by the
target. Inventory and back them up before cutover. Retain them for a v6/earlier-v8 rollback, then remove them only after
the rollback window. The target uses `SnapshotStore` for the latest snapshot; missing target snapshots require event
replay and explicit regeneration if persistence is desired.

## Atomic SnapshotStore Saves

The current `SnapshotStore.save()` contract requires one atomic compare-and-write per aggregate:

- candidate version greater than or equal to stored version → replace the complete snapshot;
- candidate version lower than stored version → complete without writing;
- the compared version must come from the same materialized payload that is written.

Equal-version replacement intentionally permits regeneration to repair a stale payload. A client-side `load()` plus
unconditional write is not conformant. Audit every custom `SnapshotStore` and backend implementation for CAS,
conditional update, transaction, or an equivalent atomic primitive.

No snapshot data rewrite is required solely for this rule, but old writers can violate it; stop them before relying on
the guarantee. Mongo's implementation uses expressions requiring MongoDB 5.2+; the repository TCK pins MongoDB 6.0.6.

## Redis EventStore Canonical v2 Layout (introduced in v8.9.0)

Current Redis EventStore, SnapshotStore, and PrepareKey use canonical v2 keys only. The runtime neither reads nor
migrates incompatible layouts. Published v6/shared and v8.8 bucketed layouts use different event/request-ID/index
keys, and old runtimes cannot read new v2 writes. Treat Redis as a hard offline storage cutover.

The starter checks exact legacy sentinel keys for configured local aggregates and blocks startup when it finds one.
That guard is useful but incomplete: it does not scan Redis Cluster, discover removed metadata, cover direct/custom
stores, prove snapshot-only scopes, or detect evicted/corrupted sentinels. Passing startup is not migration evidence.

Canonical v2 also requires one `AggregateId.id` owner within a named aggregate across tenants and uses a 128-bucket
aggregate-ID index. Before migration:

1. stop ingress and every old writer; drain appends; take a consistent backup and final event/version baseline;
2. inventory every logical database/cluster primary for legacy event ZSETs, shared/per-stream request-ID SETs,
   aggregate-ID indexes, snapshots, and PrepareKey hashes;
3. pin historical context alias + aggregate name to the target canonical scope and resolve cross-tenant duplicate IDs;
4. use an empty target namespace/database where possible; never `FLUSHDB` a shared database;
5. run a separately reviewed, idempotent offline migrator with a durable manifest of source/target keys, types,
   cardinalities, checksums, status, and last verified batch;
6. preserve event ZSET members/scores and contiguous versions; derive target request-ID sets from committed event JSON,
   reporting any source/event symmetric difference instead of hiding it;
7. rebuild all 128 aggregate-ID buckets with the target codec and verify aggregate scans;
8. verify ordered checksums, first/last versions, request IDs, ID index, representative full state replay, and counts;
9. remove/move inventoried legacy keys only after verification, deleting sentinel keys last; keep the original dataset
   immutable through rollback;
10. start one v8 instance, test isolated IDs, explicitly regenerate/verify snapshots, then move controlled traffic.

A partial migration is resumable only when its manifest and source/target checksums match. Otherwise clean the target
or restart into a new empty scope; never allow the application to accept it as complete.

Before any v2 production write, rollback reconnects the untouched legacy dataset. After a v2 write, reverse-migrate or
replay those writes before v6 starts. Application code should depend on public `EventStore`, `SnapshotStore`, and
`PrepareKey`, not removed Redis key-converter internals.

Sources: [`RedisEventStore.kt`](https://github.com/Ahoo-Wang/Wow/blob/v8.13.1/wow-redis/src/main/kotlin/me/ahoo/wow/redis/eventsourcing/RedisEventStore.kt),
[`EventStreamKeyLayout.kt`](https://github.com/Ahoo-Wang/Wow/blob/v8.13.1/wow-redis/src/main/kotlin/me/ahoo/wow/redis/eventsourcing/EventStreamKeyLayout.kt),
[`RedisEventSourcingAutoConfiguration.kt`](https://github.com/Ahoo-Wang/Wow/blob/v8.13.1/wow-spring-boot-starter/src/main/kotlin/me/ahoo/wow/spring/boot/starter/redis/RedisEventSourcingAutoConfiguration.kt).

## Mongo Ownership Guard

The target retains aggregate-name-only collection names and adds a durable `wow_database_metadata` record whose
`boundedContext` owner uses layout version `1`. One Mongo database may belong to one bounded context.

Before deployment:

1. inventory every event-stream, snapshot, and prepare database plus `*_event_stream`, `*_snapshot`, and `prepare_*`
   collections;
2. split any database whose aggregate collections contain more than one context;
3. map prepare-only databases explicitly—the guard cannot infer context from legacy prepare documents, so the first
   target context would claim an unmarked database;
4. inspect managed indexes including key order, uniqueness, TTL, partial filter, collation, sparse, and hidden options;
5. deploy the verified owner first and retain the marker/collection inventory as evidence.

Do not edit/delete the ownership marker to bypass a conflict. Move or remove the conflicting data first; delete a
marker only when an empty database is intentionally reassigned.

Source: [`MongoDatabaseContextGuard.kt`](https://github.com/Ahoo-Wang/Wow/blob/v8.13.1/wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/MongoDatabaseContextGuard.kt).

## Verification Checklist

- [ ] exact source/target tags, commits, build versions, wrapper, catalogs, JDK, and resolved dependencies are recorded
- [ ] source, test, KSP, OpenAPI/schema, and downstream contract diffs are reviewed
- [ ] target startup/readiness/message flow/graceful shutdown and fatal failure paths pass
- [ ] event, snapshot, Redis, Mongo, PrepareKey, projection, and BI scopes are inventoried
- [ ] offline migration manifest, checksums, versions, request IDs, ID indexes, and replay reconcile
- [ ] all old writers are stopped before storage cutover; one target instance passes isolated read/write verification
- [ ] metrics/traces and Collector/backend receipt cover command, store, and downstream processing stages
- [ ] production image/revision, live traffic, alerts, business invariants, and rollback window are verified
- [ ] rollback before and after the first v8 write has an exercised data procedure

## Related Pages

| Page | Relationship |
|---|---|
| [Migration Guide](../migration.md) | Scope and evidence model |
| [Runtime Orchestration Migration](./runtime-orchestration.md) | Lifecycle source/runtime migration |
| [Runtime Lifecycle](../advanced/runtime-lifecycle.md) | Stable v8 runtime semantics |
| [Redis Extension](../extensions/redis.md) | Current Redis configuration and guards |
| [Mongo Extension](../extensions/mongo.md) | Current Mongo configuration and ownership |
| [BI Deployment and Recovery](../bi-operations.md) | BI ownership, reconciliation, and Reset |

<!-- Version facts verified from local v6.20.16 and v8.13.1 tags; storage/runtime facts from current source/tests. -->
