---
title: Migrate the Data
description: Move a 9.x service's event history, reserved keys and unresolved compensation executions into V10 with migrate-data, check that V10 rebuilds the same states, and get ready to cut over.
outline: deep
---

# Migrate the Data

This page walks through moving a 9.x service's data into V10 with the `migrate-data` tool, while 9.x keeps serving. By the end, the V10 log holds the whole history, V10 rebuilds the same state as 9.x for every aggregate you can compare, and you are ready to cut over. Every command and setting is listed in the [migrate-data reference](../reference/migrate-data).

## What moves, and what does not

| 9.x data | V10 result |
| --- | --- |
| Event streams in the MongoDB event store | Entries of the V10 log, on PostgreSQL or MongoDB |
| Snapshots | Not copied: compared with the states V10 rebuilds, then V10 writes its own snapshots |
| Confirmed `PrepareKey` keys | Confirmed reservations |
| Unresolved executions of the compensation service | Parked aggregates in the V10 service's failure ledger |
| Custom projections and their views | Not migrated: their V10 subscriptions rebuild them from the log |

Each imported stream keeps its version, command id, request id and commit time, so idempotency and history carry over. Its tenant and aggregate id become the V10 aggregate reference. The owner and space follow the V10 aggregate's ownership, the operator comes from the 9.x operator header, and the 9.x trace id becomes the chain's root. Headers the map does not list, framework headers and wait headers are dropped.

## Before you start

- **The rewritten source.** The V10 service compiles and its domain tests pass. Its build has produced the merged Manifest of each service whose aggregates you migrate.
- **The map.** The `wow-migrate` Skill wrote `migration-map.json` while rewriting the source. It names, for each 9.x aggregate, its V10 type and every command and event mapping. See [The map](../reference/migrate-data#the-map).
- **Reads on the 9.x store.** The tool only reads the 9.x MongoDB store, unless you let it create the index the tail needs.
- **An empty V10 target**, or one this tool alone has written to.
- **The V10 domain modules on a class path.** `compare` and `snapshot` rebuild states with the service's own V10 models, the same evolve and upgrade functions the service runs. List the modules in `classpath`.
- **The partition count of each V10 context**, the one the service will run with. A log refuses another.

Rehearse the whole procedure against a copy of production data before the real cut-over.

## 1. Configure

Write a configuration file next to the map. Relative paths resolve against the file, and `env:NAME` reads a secret from the environment:

<!-- sample: migration/migrate-data/src/test/resources/docs/migrate-data.json -->

```json
{
  "map": "migration-map.json",
  "manifests": ["../order-service/build/wow/manifest.json"],
  "checkpoint": "migrate-data.checkpoint.json",
  "source": {
    "uri": "mongodb://mongo-9x:27017",
    "database": "order_db"
  },
  "target": {
    "type": "postgres",
    "jdbcUrl": "jdbc:postgresql://postgres:5432/wow",
    "username": "wow",
    "password": "env:WOW_PG_PASSWORD",
    "poolSize": 16
  },
  "partitions": { "example": 64 },
  "classpath": ["../order-service/build/install/order-service/lib"],
  "compensation": {
    "database": "compensation_db",
    "service": "order-service"
  }
}
```

Run the tool from a checkout of the Wow release you migrate to:

```bash
./gradlew :migrate-data:run --args="check --config /path/to/migrate-data.json"
```

## 2. Check the map

`check` scans the 9.x store and compares what it finds with the map and the V10 Manifests. It writes nothing. It fails when:

- an event type, revision or command name the store holds has no mapping;
- a mapping names a V10 command, event or subscription the Manifest does not have, or a revision higher than the Manifest's;
- a retired event is not retired in the Manifest;
- dropping events would leave a stream with no events: a version cannot be empty, so map such an event as retired instead;
- a `PrepareKey` the store holds has no reservation mapping;
- a 9.x processor with unresolved compensation executions has no subscription mapping.

Fix the map, or the source, and check again until it passes. `import --dry-run` goes one step further: it reads and converts every stream without writing.

## 3. Import

`import` checks again, then imports every mapped aggregate, the reserved keys and the unresolved compensation executions. It records its progress in the checkpoint file, so a run that stops resumes where it stopped, repeating at most one page. Appends are idempotent: a stream already in the log is not written twice.

- **Reserved keys.** A key 9.x holds forever becomes a confirmed reservation of the aggregate whose id its value holds, in the tenant that aggregate's 9.x streams record, and in the namespace and tenancy the map gives. Keys with a time to live, and expired keys, are skipped. A key whose owner cannot be found, or appears in several tenants, is printed as `UNRESOLVED` and not imported.
- **Unresolved executions.** Each `FAILED` or `PREPARED` execution of the 9.x compensation service becomes a parked aggregate of the mapped V10 subscription, from the failed version, with its retry state and its recoverable mark. After the cut-over, the V10 service retries it, and the ops console lists it. An execution that cannot be placed is printed as `UNRESOLVED`.

9.x keeps changing keys and executions while you migrate. Each import from the start reconciles what earlier runs imported: it releases a key 9.x gave up, and resolves an entry whose execution 9.x has since settled.

## 4. Follow 9.x

While 9.x still serves, `tail` follows the streams it commits after the import. 9.x has no position to resume from, so the tail scans by commit time, going back a window each pass, five minutes by default, to catch streams that became visible late; the log's version key absorbs the overlap.

The tail reads each event stream collection by a `(createTime, _id)` index. Without it, the tail refuses to start and prints the command that creates it; build it when it suits the 9.x database, or let the tool create it.

`tail` runs until it is stopped. `tail --once` makes one pass. Keep `tail` running until 9.x stops writing: the cut-over's final pass is then `import --final`, which reads from where the tail stopped instead of walking every stream again.

## 5. Compare

`compare` rebuilds each aggregate that has a 9.x snapshot to the snapshot's version, with the V10 model, and compares:

- the state, as normalized JSON: fields in any order, null the same as missing, numbers by value, arrays in order;
- the deleted mark, the owner, the space and the tags;
- whether the V10 log holds the snapshot's version at all.

It also checks the reservations and the parked entries both ways. Each difference is printed with the aggregate, its version and the fields:

```text
DIFFERS example-service.order (0)/0VY3GwaK4OmV00E v4: paidAmount: 9.x 25.00, V10 20.00
```

The summary of each aggregate gives the share of instances that have a 9.x snapshot, how many were compared, and the differing fields by kind. The command exits with `3` when anything differs.

**Do not cut over on an unexplained difference.** Zero differences may not be reachable: 9.x snapshots can carry defects that V10 does not reproduce, and some aggregates have no snapshot to compare with. So review the differences kind by kind. Each kind must be explained, as a known 9.x defect or an intended change of semantics, and signed off. Where a field exists on one side only by design, list it in the map's `state.ignore`, and where the Skill renamed a state field, in `state.fields`.

`compare --sample 10` compares 10% of the instances, always the same ones, for quicker iterations.

## 6. Write the snapshots

`snapshot`, or `import --snapshots`, writes the V10 snapshot of every imported aggregate at its last version, so V10's queries have data the moment traffic arrives. The V10 service's own snapshot subscription then finds them in place.

## 7. Record the tags

Streams the import appended carry no tags yet, and attribute-based access control on the `events` model matches no stream without tags. Run `backfill-tags` once the import is done: it replays each aggregate and records the tags it had after each stream. If the services copy event streams to Elasticsearch, it then rewrites the search documents indexed without tags.

## 8. Cut over

The cut-over is a blue-green switch:

1. Stop 9.x writing, and stop the 9.x compensation service.
2. Run the final pass. If `tail` followed 9.x until it stopped, run `import --final`: one tail pass from the tail's checkpoint, then the reserved keys and the compensation executions walked again, since the tail follows neither. It refuses, writing nothing, when an aggregate's tail did not run continuously. Otherwise, or after it refuses, run `import --restart`: a complete pass from the first instance, which takes as long as the whole history.
3. Run `compare` and review the result.
4. Run `snapshot` and `backfill-tags`.
5. Switch traffic to V10, and move every external consumer of 9.x topics to the V10 published streams. There is no 9.x-format sink.

V10 parks the aggregate, not the event: a parked aggregate's later events wait while it is parked, and resuming it replays its streams from the failed version on, including streams 9.x had already handled after the failure. Handlers must tolerate that, as they tolerate any redelivery.

## 9. Roll back

Until V10 writes anything, rolling back is switching traffic back to 9.x. Once V10 has served writes, 9.x lacks what V10 committed since the cut-over, and `export` writes it back into the 9.x store, for the event types 9.x already had.

**There is a point of no return.** An event V10 writes at a revision beyond the one the map imports into (an event the V10 model moved on with `@Upgrade`), or of a type 9.x never had, has no 9.x form. The first such event V10 commits closes the rollback window. Put that moment in the cut-over plan, and decide before then whether to roll back.

1. Stop V10's writes, and keep 9.x stopped. What V10 commits after `export` starts is not exported.
2. Run `export --dry-run`. It scans each V10 context's log and prints, per aggregate type, how many streams 9.x lacks. A stream with a V10-only type is printed as `REFUSED`, and a stream 9.x holds at the same version with other content as `CONFLICT`; either one stops the export before anything is written. Each context ends with a `VERDICT` line: roll back by switching back, roll back by export (and how many streams go back), or no rollback by export, with the time the window closed.
3. Run `export`. It scans again, writes the streams 9.x lacks, resuming from its checkpoint, and skips any stream 9.x already holds. It then compares, per aggregate, how many streams 9.x and V10 hold, and exits with `3` when they differ.
4. Start 9.x. Its snapshots still show the state before the exported streams until 9.x writes those aggregates again or regenerates its snapshots.

Only event streams go back. Reserved keys V10 made, aggregates it parked and its projections' views do not, and 9.x does not publish the exported streams on its event bus.

## Limits

- Only the 9.x MongoDB event store is read. Move a Redis or Elasticsearch event store, or a compensation service on Elasticsearch, to MongoDB in 9.x first.
- A command a saga sent records only the chain's root as its cause, because 9.x keeps only the root.
- An aggregate without a 9.x snapshot cannot be compared; the report gives the share that can.
- Aggregate ids that collide across tenants are not reported yet.
- Each V10 log store is migrated by its own run, with a map of the aggregates that store holds.
