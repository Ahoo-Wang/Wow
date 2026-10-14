# wow-migrate-data

Moves a 9.x application's data into V10 (design [07 §3](../../documentation/designs/v10/07-migration.md)): it checks `migration-map.json`, imports the 9.x event streams into the V10 log, follows what 9.x keeps writing, compares every 9.x snapshot with the state V10 rebuilds, and writes the V10 snapshots. The 9.x source is a MongoDB event store (decision D213); the V10 target is PostgreSQL or MongoDB.

## Running

```bash
./gradlew :migrate-data:run --args="check --config /path/to/migrate-data.json"
```

| Command | Options | What it does |
| --- | --- | --- |
| `check` | | Checks `migration-map.json` against the 9.x store and the V10 Manifests. Writes nothing. |
| `import` | `--dry-run`, `--restart`, `--snapshots` | Checks, then imports every mapped 9.x aggregate, resuming from the checkpoint. `--dry-run` appends nothing; `--restart` is the final complete pass after 9.x stops writing; `--snapshots` then runs `snapshot`. |
| `tail` | `--once` | Follows what 9.x commits after the import. It needs a `(createTime, _id)` index on each `<aggregate>_event_stream`; it prints the command, or creates it with `source.createCommitTimeIndex`. |
| `compare` | `--sample <percent>`, `--restart` | Compares each 9.x snapshot with the V10 state rebuilt from the imported log at the snapshot's version. |
| `snapshot` | `--restart` | Writes the V10 snapshot of every imported instance at its last version. |

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
    "password": "env:WOW_PG_PASSWORD"
  },
  "partitions": { "example": 16 },
  "classpath": ["../order-service/build/install/order-service/lib"]
}
```

- Relative paths resolve against the configuration file; `env:NAME` reads an environment variable.
- `classpath` lists the services' domain modules: jars, or directories of classes and jars. `compare` and `snapshot` rebuild states with the V10 models found there (the compiler's `ModelProvider`, decision D107), so the same evolve and upgrade functions the service runs. Dependencies the models take are stand-ins: the tool runs no decide function. Without `classpath`, the models are the ones on the tool's own class path.
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

## Checkpoints

The checkpoint file (format 1) keeps, per 9.x aggregate, where the import, the tail, the comparison and the snapshot writing stand. A stopped run repeats at most one page. A resumed comparison keeps the counts of the earlier runs, so it still exits with `3` when they found differences. `--restart` starts a pass again from the first instance, and another `--sample` restarts the comparison.

## Limits

- Only a 9.x MongoDB store is read (D213). A 9.x Redis store must be moved to MongoDB first.
- A derived command's stream records only the chain's root as its cause, since 9.x keeps only the root.
- A stream whose events are all dropped is refused until V10 has a retired-event marker (roadmap P.6).
- An instance without a 9.x snapshot cannot be compared; the report gives the share that can.
- Ids that collide across tenants are not reported yet.
