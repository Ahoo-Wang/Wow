# wow-migrate-data

Moves a 9.x application's data into V10 (design [07 §3](../../documentation/designs/v10/07-migration.md)): it checks `migration-map.json`, imports the 9.x event streams into the V10 log, the 9.x `PrepareKey` keys into the V10 reservations and the 9.x compensation service's unresolved executions into the V10 failure ledger, follows what 9.x keeps writing, compares every 9.x snapshot with the state V10 rebuilds, and writes the V10 snapshots. The 9.x source is a MongoDB event store (decision D213); the V10 target is PostgreSQL or MongoDB.

## Running

```bash
./gradlew :migrate-data:run --args="check --config /path/to/migrate-data.json"
```

| Command | Options | What it does |
| --- | --- | --- |
| `check` | | Checks `migration-map.json` against the 9.x store and the V10 Manifests. Writes nothing. |
| `import` | `--dry-run`, `--restart` or `--final`, `--snapshots` | Checks, then imports every mapped 9.x aggregate, then the reservations and the unresolved compensation executions, resuming from the checkpoint. `--dry-run` writes nothing; `--restart` is the final complete pass after 9.x stops writing; `--final` is the shorter final pass when the tail followed 9.x until it stopped (see [The final pass](#the-final-pass)); `--snapshots` then runs `snapshot`. |
| `tail` | `--once` | Follows what 9.x commits after the import. It needs a `(createTime, _id)` index on each `<aggregate>_event_stream`; it prints the command, or creates it with `source.createCommitTimeIndex`. |
| `compare` | `--sample <percent>`, `--restart` | Compares each 9.x snapshot with the V10 state rebuilt from the imported log at the snapshot's version, then the reservations and the parked entries. |
| `snapshot` | `--restart` | Writes the V10 snapshot of every imported instance at its last version. |
| `backfill-tags` | `--restart`, `--force` | Records the tags of every V10 stream appended without them, then re-sinks the Elasticsearch events documents indexed without them (see [Backfilling the tags](#backfilling-the-tags)). |

Exit codes: `0` done, `1` failed (the check, or a step that stopped), `2` usage error, `3` `compare` found differences.

## Configuration

```json
{
  "map": "migration-map.json",
  "manifests": ["build/wow/manifest.json"],
  "checkpoint": "migrate-data.checkpoint.json",
  "source": { "uri": "mongodb://localhost:27017", "database": "example_db" },
  "target": {
    "type": "postgres",
    "jdbcUrl": "jdbc:postgresql://localhost:5432/wow",
    "username": "wow",
    "password": "env:WOW_PG_PASSWORD",
    "poolSize": 16
  },
  "partitions": { "example": 16 },
  "classpath": ["../order-service/build/install/order-service/lib"],
  "compensation": { "database": "compensation_db", "service": "order-service" },
  "elasticsearch": { "uri": "http://localhost:9200", "indexPrefix": "" }
}
```

- Relative paths resolve against the configuration file; `env:NAME` reads an environment variable.
- `classpath` lists the services' domain modules: jars, or directories of classes and jars. `compare` and `snapshot` rebuild states with the V10 models found there (the compiler's `ModelProvider`, decision D107), so the same evolve and upgrade functions the service runs. Dependencies the models take are stand-ins: the tool runs no decide function. Without `classpath`, the models are the ones on the tool's own class path.
- `compensation` is optional: the 9.x compensation service's database (`database`, on the server of `uri`, the source's when left out) and the V10 service whose failure ledger receives its unresolved executions (`service`, the service's `wow.service` or `spring.application.name`). Without it, nothing of the compensation service is read.
- A PostgreSQL target is reached through a HikariCP pool of at most `poolSize` connections (16 by default), closed when the command ends: one connection per call would run the client out of ports on a large log. HikariCP is a runtime dependency of the tool, its version the Spring Boot BOM's.
- `elasticsearch` is optional: the cluster of the services' events indices (`uri`) and their `index-prefix` (`indexPrefix`), which only `backfill-tags` writes to.
- `pageSize`, `streamBatch`, `concurrency`, `lookbackMillis` and `pollIntervalMillis` tune the passes.

## `migration-map.json`, format 1

The format is described in `MigrationMap`. Roadmap 8b.2 adds an optional `state` per aggregate, read only by `compare`; a map without it reads as before, so the format stays 1:

```json
{
  "from": "example-service.order",
  "to": "example.order",
  "state": { "fields": { "orderStatus": "status" }, "ignore": ["payable", "items.totalPrice"] }
}
```

- `fields` renames top-level fields of the 9.x state to their V10 names.
- `ignore` leaves fields out on both sides, by their V10 name; a dotted path goes into objects and through arrays. Use it only for a field one side has by design, which is an explained difference.

## Retired and dropped events

A 9.x event the V10 aggregate no longer has (the 9.x `DroppedEvent`) is mapped in one of two ways:

```json
{ "from": "order_archived", "to": "OrderArchived", "retire": true },
{ "from": "order_noted", "drop": true }
```

- `retire` writes the record as stored under the name `to`, which the V10 aggregate lists in `@AggregateRoot(retiredEvents)` (decision D219). Loading skips it, so its stream still counts as a version. The check fails when `to` is not in the aggregate's retired events in the Manifest.
- `drop` leaves the event out of its stream. A stream whose every event is dropped would leave its version empty, which the log cannot hold, so the check counts those streams and fails before anything is imported. Map such events with `retire` instead.

## The final pass

Once 9.x has stopped writing, the final pass imports what it committed since the tail last read. `import --restart` walks every instance again, so it takes as long as the whole history: about 97 s for the roughly 296,000 streams of the 8c.1 rehearsal, half its write stop. When the tail followed 9.x until it stopped, `import --final` replaces it:

1. It checks each mapped aggregate: its full import completed, and its tail's last pass ended at most `final.maxTailAgeMillis` ago (10 minutes by default). Otherwise it prints why and writes nothing; the final pass is then `import --restart`.
2. It runs one tail pass from the tail's checkpoint, reading again `final.lookbackMillis` before it (5 minutes by default). The window covers the clocks of 9.x's nodes and the commits in flight when 9.x stopped, which may carry an earlier `createTime` than commits already read.
3. It walks the reservations and the compensation executions again from the first, as `--restart` does, since the tail follows neither.

`compare` stays the gate after the final pass, whichever ran. Keep `import --restart` for a migration whose tail did not run continuously.

```json
"final": { "lookbackMillis": 300000, "maxTailAgeMillis": 600000 }
```

## Events that moved the owner

9.x applied any event implementing its `OwnerTransferred` as an owner transfer: the aggregate's owner became the event's `toOwnerId`. A domain event of that kind (a view's `ViewAudienceChanged`) names the field in its mapping, so the imported stream records the owner it moved to, as it records the owner of a `wow.lifecycle.OwnerTransferred`:

```json
{ "from": "view_audience_changed", "to": "ViewAudienceChanged", "owner": "toOwnerId" }
```

A blank or missing value leaves the stream's own owner. An aggregate whose V10 ownership is `TENANT` records none either way.

## Reservations (9.x `PrepareKey`)

9.x's `MongoPrepareKey` keeps each `PrepareKey` in a collection `prepare_<name>`, one document per key: `{_id: <key>, value: <the prepared value>, ttlAt: <Date>}`. The value is the `PrepareKey<V>`'s `V`: a string, or a document of its fields. A key prepared forever has `ttlAt` at 9.x's `TTL_FOREVER` (6666-06-06). 9.x keys have no tenant.

`import` reserves the confirmed keys in the V10 reservation store after the event streams (decisions D225, D227 ③), so a key 9.x holds is not free in V10 at the cut-over. Each `PrepareKey` the store holds needs a mapping on the aggregate whose instances own its keys:

```json
{
  "from": "example-service.user",
  "to": "identity.user",
  "reservations": [
    { "from": "username", "namespace": "user.name", "tenancy": "SYSTEM", "ownerId": "userId" }
  ]
}
```

- `from` is the 9.x `PrepareKey` name, `namespace` the V10 namespace the decide function reserves the key in.
- `tenancy` is `TENANT` (the default of `DecideScope.reserve`: unique within the owner's tenant) or `SYSTEM` (unique across tenants, as every 9.x key was). It must be the tenancy the V10 code reserves with.
- `ownerId` is the dotted path of the owner's id in the 9.x value; without it the value itself is the id.

The owner is that aggregate's instance with the id, in the tenant its 9.x streams record. A key prepared forever becomes a confirmed reservation; a key prepared with a time to live (a decision in flight, or one that never committed) and an expired key are skipped. A key whose owner has no 9.x stream, or whose id is in streams of several tenants, is printed as `UNRESOLVED` and not imported. A key another owner already holds in V10, which this import did not reserve, stops the import. The import is checkpointed per `PrepareKey` and idempotent: a key already reserved for its owner is skipped.

9.x keeps serving while the migration runs, so a key it confirmed may be rolled back (a rename) or reprepared for another owner after an import reserved it. The import records each key it reserves in `<checkpoint>.reservations.jsonl`, beside the checkpoint file, before reserving it. A walk from the first key (the first `import`, or `--restart`) first releases each recorded key that V10 still holds for the recorded owner and 9.x no longer confirms to that owner (printed as `RELEASED`), then reserves what 9.x holds now, so a key that moved is reserved for its new owner instead of stopping the import. A key V10 holds without a record, which V10 reserved itself, is never released; a recorded key V10 no longer holds for its owner is forgotten. `compare` checks both ways: every confirmed 9.x key is reserved in V10 for the same owner, and every recorded key V10 still holds is still confirmed to that owner in 9.x; it prints each that is not.

The reservation store is the target backend's, under its default name, as the V10 host uses it. `tail` does not follow `PrepareKey`s: the final pass (`import --final` or `--restart`), once 9.x has stopped writing, reserves the keys 9.x confirmed since and releases those it gave up.

## Compensation (9.x `ExecutionFailed`)

The 9.x compensation service keeps each failed processor execution as an `execution_failed` aggregate in its own MongoDB database, with its snapshot in `execution_failed_snapshot`: `{_id, contextName: "compensation-service", version, deleted, state: {eventId: {id, aggregateId: {contextName, aggregateName, aggregateId, tenantId}, version}, function: {functionKind, contextName, processorName, name}, error: {errorCode, errorMsg, stackTrace}, executeAt, retrySpec: {maxRetries, minBackoff, executionTimeout}, retryState: {retries, retryAt, timeoutAt, nextRetryAt}, status, recoverable}}`. The tool reads these snapshots, which 9.x keeps at every version and reads itself to find the next retries.

An execution is **unresolved** while its status is not `SUCCEEDED` and its aggregate is not deleted: `FAILED`, and `PREPARED` (a retry in flight at the cut-over). `import` parks each unresolved execution in the failure ledger of the V10 service (decisions D14, D141, D229 G-1), after the event streams, so the V10 service retries it automatically (roadmap P.1) and the ops service and console list it. Succeeded executions and deleted (skipped) ones are history: they are not imported, and their trail stays in the 9.x compensation database.

Each 9.x processor whose functions have unresolved executions needs a mapping to the V10 subscriptions, named `<processor>.<function>`:

```json
"subscriptions": [
  { "from": "example-service.CartSaga" },
  { "from": "example-service.OrderSaga", "to": "OrderSaga", "functions": { "onEvent": "onOrderCreated" } },
  { "from": "example-service.OrderProjector", "functions": { "onOrderPaid": "Reports.onOrderPaid" } }
]
```

- `from` is the 9.x `contextName.processorName` the execution records.
- `to` is the V10 processor name; it defaults to the 9.x one.
- `functions` renames a 9.x function to its V10 name. A value with a dot is a whole subscription name, for a function that moved to another processor. A function not listed keeps its name.

The check fails when a 9.x processor function with unresolved executions in a context this map migrates (the contexts of its aggregates and subscription mappings) has no mapping, when a processor is mapped twice, or when a mapped subscription is not in the Manifest. A mapping no unresolved execution uses is a warning. The executions of other services' contexts, which share the compensation database, are left for their own runs.

Each execution becomes one parked entry of its subscription:

| V10 parked entry | From the 9.x execution |
| --- | --- |
| subscription | the mapping of `function` |
| aggregate | the mapped V10 type of `eventId.aggregateId`, with its id and tenant |
| from version | `eventId.version` (the import keeps the versions) |
| reason | `<errorCode>: <errorMsg> [9.x execution <id>]`; the ops console groups by the part before the colon, and the mark names the execution the entry was imported for. No stack trace (D215) |
| failed at | `executeAt`, the last attempt |
| retries, retry at | `retryState.retries`, and `retryState.retryAt` once a retry was made |
| retry spec | `retrySpec`, seconds |
| recoverable | `recoverable` (9.x `MarkRecoverable`) |

An execution whose processor is not mapped, whose aggregate this map does not import, whose subscription reads another context's published events (it parks by topic offset, which a 9.x execution has none of), or whose version the V10 log does not hold is printed as `UNRESOLVED` and not imported; the check warns about the first three before the import. The import is checkpointed and idempotent: an aggregate the subscription already parks from that version or an earlier one is left as it is.

9.x keeps retrying while the migration runs, so an execution an early import parked may succeed, or be skipped, before the cut-over. A walk from the first execution (the first `import`, or `--restart`) first resolves each entry whose reason names a 9.x execution that is no longer unresolved, or no longer parks that aggregate from that version under the map (printed as `RESOLVED`), then parks the executions still unresolved, so an aggregate another execution still holds is parked again from that one's version. An entry without the mark, which V10 parked itself or parked again after a failed resume, is never touched. `compare` checks both ways: every unresolved execution has an entry of its subscription and aggregate from its version or an earlier one, and every entry with the mark still stands for an unresolved execution; it prints each that does not as `DIFFERS` and exits with `3`.

Cut-over notes:

- V10 parks the aggregate, not the event (D14): its later events wait while it is parked, and a resume handles its streams from the failed version on, including those 9.x already handled after the failure. Handlers must tolerate that, as they tolerate any redelivery.
- Two executions of one subscription and aggregate become one entry, from the earlier version, with the retry state of the first one imported.
- `tail` does not follow the compensation service. Stop the 9.x compensation service with the application, then run the final pass (`import --final` or `--restart`).
- After the cut-over the V10 service owns the retries, and an entry resolved in V10 is no longer parked, so a later `compare` reports it.
- Entries and keys imported before the import recorded their origin (the reason's mark, the keys file) cannot be told from V10's own: run the import from a fresh V10 target, or with `--restart` after removing them.

## Comparing states

`compare` reads `<aggregate>_snapshot`, one document per instance, in aggregate id order, a page at a time. For each 9.x snapshot it replays the V10 log up to the snapshot's version and compares:

- the state, as normalised JSON: fields in any order, `null` the same as missing, numbers by value (`25.00` is `25.0`), arrays in order;
- the deleted mark, the owner and the space (narrowed by the V10 ownership, as the import narrows them), and the tags;
- a version the V10 log does not hold.

Each instance that differs is printed with its 9.x aggregate, tenant and id, version and fields:

```text
DIFFERS example-service.order (0)/0VY3GwaK4OmV00E v4: paidAmount: 9.x 25.00, V10 20.00
```

The summary of each aggregate gives the share of instances that have a 9.x snapshot, how many were compared and differ, and the differing fields by kind (`items[].price`). Design 07 §3 step 4 asks that each kind be explained (a known 9.x defect or an intended change) and signed off before the cut-over. `--sample 10` compares 10% of the instances, chosen by a hash of tenant and id, so every run compares the same ones.

## Writing snapshots

`snapshot` (or `import --snapshots`) replays each imported instance's V10 log and saves its snapshot in the context's snapshot store, on the target backend. It is idempotent: a snapshot the store already holds at that version is skipped. The V10 host still claims and backfills its snapshot tables (decision D172); its snapshot subscription then finds these snapshots and passes over them.

## Backfilling the tags

`backfill-tags [--restart] [--force]` records the aggregate's tags on every V10 stream appended without them, the streams `import` writes among them (decision D259): until then ABAC on the `events` model matches none of those streams. It runs in this order, and prints it:

1. Each aggregate of the configured contexts' Manifests is replayed with its V10 model, and each stream without tags gets the tags the aggregate had after it. Only missing tags are written, a page of aggregates at a time, resuming from the checkpoint; `--restart` walks from the first again.
2. With `elasticsearch` configured, the events index documents indexed without tags are overwritten from the log, and nothing else. This is refused while a log still holds a stream without tags (an old node appended it meanwhile: run again), unless `--force`, which leaves those documents as they are. An overwritten document is no longer listed, so a run again overwrites nothing.

On 100,000 aggregates of 2 streams each (page 500, concurrency 8, containers on a laptop) the backfill took 12.7 s on PostgreSQL (0.13 ms per aggregate) and 44.9 s on MongoDB (0.45 ms per aggregate): `TagsBackfillCostSpike`, run with `-Dwow.spike.tags=true`.

## Checkpoints

The checkpoint file (format 1) keeps, per 9.x aggregate, where the import, the tail (and when its last pass ended), the comparison and the snapshot writing stand; per `PrepareKey`, where its reservation import stands; and where the compensation import stands; per V10 aggregate type, where the tags backfill stands. The keys the reservation import reserved are kept beside it, in `<checkpoint>.reservations.jsonl`: keep the two together. Commands may run at once over the same checkpoint file, the tail beside `compare` or `snapshot`: each change of it holds a lock on `<checkpoint>.lock` (left in place, empty) and replaces the file atomically after forcing the new content to the disk, so none loses another's progress. A stopped run repeats at most one page. A resumed comparison keeps the counts of the earlier runs, so it still exits with `3` when they found differences. `--restart` starts a pass again from the first instance, and another `--sample` restarts the comparison.

## Limits

- Only a 9.x MongoDB store is read (D213). A 9.x Redis store must be moved to MongoDB first, and so must a 9.x compensation service whose snapshots are in Elasticsearch.
- A derived command's stream records only the chain's root as its cause, since 9.x keeps only the root.
- An instance without a 9.x snapshot cannot be compared; the report gives the share that can.
- Ids that collide across tenants are not reported yet.
