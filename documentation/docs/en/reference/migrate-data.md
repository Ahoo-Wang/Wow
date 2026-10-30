---
title: migrate-data CLI
description: The commands, options, exit codes, configuration file, migration map and checkpoint files of the migrate-data tool.
outline: deep
---

# migrate-data CLI

`migrate-data` moves a 9.x service's data into V10. This page lists everything it reads and does; [Migrate the data](../migrate/data) explains the procedure.

## Running

```bash
./gradlew :migrate-data:run --args="<command> --config <file> [options]"
```

Run it from a checkout of the Wow release you migrate to. It reads a 9.x MongoDB event store and writes a V10 log on PostgreSQL or MongoDB.

## Commands

| Command | Options | What it does |
| --- | --- | --- |
| `check` | | Checks the map against the 9.x store and the V10 Manifests. Writes nothing. |
| `import` | `--dry-run`, `--restart` or `--final`, `--snapshots` | Checks, then imports every mapped 9.x aggregate, then the reserved keys and the unresolved compensation executions, resuming from the checkpoint. |
| `tail` | `--once` | Follows what 9.x commits after the import, until stopped. |
| `compare` | `--sample <percent>`, `--restart` | Compares each 9.x snapshot with the state V10 rebuilds from the log at the snapshot's version, then the reservations and the parked entries. |
| `snapshot` | `--restart` | Writes the V10 snapshot of every imported aggregate at its last version. |
| `backfill-tags` | `--restart`, `--force` | Records the tags of every V10 stream appended without them, then rewrites the Elasticsearch events documents indexed without them. |
| `export` | `--dry-run`, `--restart` | Writes back to the 9.x store the streams V10 wrote after the cut-over, for a rollback; refuses streams with V10-only types. |

| Option | Meaning |
| --- | --- |
| `--config <file>` | The configuration file. Required by every command. |
| `--dry-run` | `import` reads and converts every stream but writes nothing and saves no checkpoint; `export` scans and prints its verdict but writes nothing. |
| `--restart` | Walks every instance again from the first, ignoring where the last pass stopped. With `import`, the final complete pass after 9.x stops writing; it also reconciles reserved keys and parked entries imported earlier. |
| `--final` | `import`'s shorter final pass after 9.x stops writing, when the tail followed 9.x until it stopped: one tail pass from the tail's checkpoint, reading `final.lookbackMillis` before it, then the reserved keys and the compensation executions walked again from the first. It refuses, writing nothing, when an aggregate's import did not complete or its tail's last pass ended more than `final.maxTailAgeMillis` ago; run `import --restart` then. Not with `--restart` or `--dry-run`. |
| `--snapshots` | After `import`, runs `snapshot`. |
| `--once` | `tail` makes one pass and ends. |
| `--sample <percent>` | `compare` compares that share of the instances, chosen by a hash of tenant and id, so every run compares the same ones. Another sample restarts the comparison. |
| `--force` | `backfill-tags` rewrites the Elasticsearch documents even while a log still holds a stream without tags, leaving those documents as they are. |

An option a command does not take is a usage error.

### Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Done. |
| `1` | Failed: the check, a step that stopped, or `import --final` refused. |
| `2` | Usage error. |
| `3` | `compare` found differences, in this run or in earlier runs it resumed; or, after `export`, 9.x and V10 hold different numbers of streams of an aggregate. |

### Output

Lines that need attention start with a marker:

| Marker | Meaning |
| --- | --- |
| `PROBLEM` | The check found something that stops the import. |
| `FAILED` | A step stopped, with the reason; or `import --final` refused an aggregate, whose import did not complete or whose tail did not follow 9.x until it stopped, and wrote nothing. |
| `WARNING` | The check found something that does not stop it, such as a mapping nothing uses. |
| `DIFFERS` | `compare` found a difference: a state, a reservation or a parked entry. |
| `UNRESOLVED` | A reserved key or a compensation execution could not be placed, and was not imported. |
| `RELEASED` | A reservation imported earlier was released, because 9.x no longer confirms it to that owner. |
| `RESOLVED` | A parked entry imported earlier was resolved, because its 9.x execution is settled. |
| `REFUSED` | `export` found a stream with a type 9.x cannot read: an aggregate no 9.x aggregate maps to, or an event beyond the map's revision. Nothing is exported. |
| `CONFLICT` | `export` found a stream 9.x holds at the same version with another command or tenant. Nothing is exported. |
| `VERDICT` | `export --dry-run`'s conclusion for a context: switch back, export N streams, or no rollback by export since a given time. |
| `EQUAL`, `DIFFERS` | After `export`, whether 9.x holds as many streams of an aggregate as V10. |
| `CUTOVER` | After a pass that appended to a context's log (`import`, `import --restart`, `import --final`, each round of `tail`), where the imported history now ends: the cut-over, recorded at the log's head in each partition. |

## Configuration file

A JSON file. Relative paths resolve against the file's directory. A string written `env:NAME` is read from the environment variable `NAME`. A property the tool does not define is refused.

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

| Property | Default | Meaning |
| --- | --- | --- |
| `map` | required | The migration map. |
| `manifests` | required | The merged V10 Manifests of the services the streams go to. |
| `checkpoint` | `migrate-data.checkpoint.json` | The checkpoint file. |
| `source.uri`, `source.database` | required | The 9.x MongoDB event store. |
| `source.createCommitTimeIndex` | `false` | Lets `tail` create the `(createTime, _id)` index it needs on each event stream collection. Otherwise it refuses to start without it and prints the command. |
| `target.type` | required | `postgres` or `mongo`. |
| `target.jdbcUrl`, `target.username`, `target.password` | | The PostgreSQL target. |
| `target.poolSize` | `16` | The most connections to the PostgreSQL target. |
| `target.uri`, `target.database` | | The MongoDB target, a replica set. |
| `partitions` | | Each V10 context's partition count, which must be the services' own. |
| `classpath` | the tool's own class path | The services' V10 domain modules, each a jar or a directory of classes or jars. `compare`, `snapshot` and `backfill-tags` rebuild states with their models. The dependencies the models take are stand-ins: the tool runs no decide function. |
| `compensation.database` | | The 9.x compensation service's database. Without `compensation`, nothing of the compensation service is read. |
| `compensation.uri` | the source's | The server of that database. |
| `compensation.service` | | The V10 service whose failure ledger receives the unresolved executions: its `wow.service`, or `spring.application.name`. |
| `elasticsearch.uri`, `elasticsearch.indexPrefix` | | The cluster of the services' events indices, and their index prefix. Only `backfill-tags` writes to it. |
| `pageSize` | `500` | Instances imported between two checkpoints; streams the tail reads at once. |
| `streamBatch` | `200` | Streams of one instance read from the 9.x store at once. |
| `concurrency` | `8` | Instances imported at a time. |
| `lookbackMillis` | `300000` | How far before the last commit time it read the tail scans again. |
| `pollIntervalMillis` | `1000` | The pause between two tail passes. |
| `final.lookbackMillis` | `60000` | How far before the tail's checkpoint `import --final` reads again. It covers the clocks of 9.x's nodes and the commits in flight when 9.x stopped, whose commit time is set before the write is visible. Make it twice the larger of the measured clock skew and the slowest 9.x commit. |
| `final.maxTailAgeMillis` | `600000` | How recently the tail's last pass must have ended for `import --final` to accept it. |

A service whose aggregates keep their logs in different V10 stores is migrated by one run per store, each with a map of the aggregates that store holds.

## The map

`migration-map.json`, format 1, records how each 9.x name and shape becomes its V10 one. The `wow-migrate` Skill writes it while rewriting the source; the tool checks it before it writes anything. A property the format does not define is refused.

<!-- sample: migration/migrate-data/src/test/resources/docs/migration-map.json -->

```json
{
  "format": 1,
  "headers": { "app_id": "app_id" },
  "aggregates": [
    {
      "from": "order-service.order",
      "to": "example.order",
      "commands": [
        { "from": "create_order", "to": "CreateOrder" },
        { "from": "pay_order", "to": "PayOrder" }
      ],
      "events": [
        {
          "from": "order_created",
          "fromRevision": "0.0.1",
          "to": "OrderCreated",
          "toRevision": 1,
          "upgrade": "upgradeOrderCreatedFrom1"
        },
        { "from": "order_paid", "to": "OrderPaid" },
        {
          "from": "owner_transferred",
          "to": "wow.lifecycle.OwnerTransferred",
          "fields": { "toOwnerId": "ownerId" }
        },
        { "from": "order_archived", "to": "OrderArchived", "retire": true },
        { "from": "order_noted", "drop": true }
      ],
      "state": {
        "fields": { "orderStatus": "status" },
        "ignore": ["payable"]
      },
      "reservations": [
        { "from": "order_no", "namespace": "order.no", "tenancy": "SYSTEM", "ownerId": "orderId" }
      ]
    }
  ],
  "subscriptions": [
    { "from": "order-service.OrderSaga", "functions": { "onEvent": "onOrderCreated" } },
    { "from": "order-service.OrderProjector", "to": "OrderReports" }
  ]
}
```

A V10 name without a dot is a simple name in the aggregate's namespace (`OrderCreated` is `example.order.OrderCreated`); one with a dot is a full name, such as `wow.lifecycle.OwnerTransferred`.

### Top level

| Property | Meaning |
| --- | --- |
| `format` | `1`. |
| `aggregates` | One entry per 9.x aggregate; see below. |
| `headers` | The 9.x stream headers kept as request facts, 9.x key to V10 name. Every other header is dropped. A 9.x framework header (`trace_id`, `upstream_id`, `upstream_name`, `command_operator`, wait headers) cannot be listed: each becomes a field of its own or goes. |
| `subscriptions` | The 9.x processors with unresolved compensation executions, mapped to V10 subscriptions; see below. |

### Aggregates

| Property | Meaning |
| --- | --- |
| `from` | The 9.x aggregate, `contextName.aggregateName` as its streams record it. |
| `to` | The V10 aggregate type, `context.aggregate`. |
| `commands` | `{from, to}`: each 9.x command name a stream records (its `upstream_name` header) and its V10 command. Every command name the store holds needs one. |
| `defaultCommand` | The V10 command of a stream that records no command name. Without it, such a stream fails the check. |
| `events` | Each 9.x event; see below. |
| `state` | How a 9.x snapshot's state reads as the V10 state, for `compare` only: `fields` renames top-level fields, 9.x name to V10 name; `ignore` leaves fields out on both sides, by V10 name, a dotted path going into objects and through arrays. Use `ignore` only for a field one side has by design. |
| `reservations` | Each 9.x `PrepareKey` whose values name an instance of this aggregate; see below. Every `PrepareKey` the store holds needs one. |

### Events

| Property | Meaning |
| --- | --- |
| `from` | The 9.x event name. |
| `fromRevision` | The 9.x revision this entry maps; any revision when left out. |
| `to` | The V10 event name; for a retired event, the retired name. |
| `toRevision` | The revision the 9.x payload has in V10, 1 by default: a 9.x shape is revision 1, and the V10 upgrade functions read it up to the current revision. At most the Manifest's revision. |
| `fields` | Renames top-level payload fields, 9.x name to V10 name. For system events whose shape V10 fixes, such as 9.x `OwnerTransferred.toOwnerId`; a domain event's changes belong in its upgrade functions. |
| `upgrade` | The upgrade function the Skill wrote, recorded for the report; the tool does not run it. |
| `retire` | Writes the record as stored under the name `to`, which the V10 aggregate lists as retired, so loading skips it and the stream keeps its version. The check fails when `to` is not retired in the Manifest. |
| `drop` | Leaves the event out of its stream. A stream whose every event is dropped would have an empty version, which the check refuses: map such events with `retire`. |

### Reservations

9.x keeps each `PrepareKey` in a collection `prepare_<name>`, one document per key. A key prepared forever becomes a confirmed reservation; a key with a time to live, or an expired one, is skipped.

| Property | Meaning |
| --- | --- |
| `from` | The 9.x `PrepareKey` name. |
| `namespace` | The V10 namespace decide reserves the key in. |
| `tenancy` | `TENANT` (the default: unique within the owner's tenant) or `SYSTEM` (unique across tenants, as every 9.x key was). It must be the tenancy the V10 code reserves with. |
| `ownerId` | The dotted path of the owner's id in the 9.x value; without it, the value itself is the id. The owner's tenant is the one its 9.x streams record. |

A key whose owner has no 9.x stream, or whose id appears in streams of several tenants, is `UNRESOLVED`. A key another owner already holds in V10, which this tool did not reserve, stops the import.

### Subscriptions

A V10 subscription is named `<processor>.<function>`.

| Property | Meaning |
| --- | --- |
| `from` | The 9.x processor, `contextName.processorName` as a 9.x execution records it. |
| `to` | The V10 processor name; the 9.x one by default. |
| `functions` | Renames 9.x functions: a value without a dot is the V10 function name; one with a dot is a whole subscription name, for a function that moved to another processor. |

The check fails when a processor with unresolved executions in a context this map migrates has no mapping, when a processor is mapped twice, or when a resulting subscription is not in the Manifest.

Each unresolved execution becomes one parked entry:

| Parked entry | From the 9.x execution |
| --- | --- |
| Subscription | the mapping of its function |
| Aggregate | the mapped V10 type of its aggregate, with its id and tenant |
| From version | its event's version |
| Reason | `<errorCode>: <errorMsg> [9.x execution <id>]`, without the stack trace |
| Failed at | its last attempt |
| Retries, retry at | its retry count, and its last retry time |
| Retry spec | its retry spec |
| Recoverable | its recoverable mark |

An execution whose processor is not mapped, whose aggregate the map does not import, whose subscription reads another context's published stream, or whose version the V10 log does not hold, is `UNRESOLVED`. Two executions of one subscription and aggregate become one entry, from the earlier version.

## Export

`export` reads the map backwards. Each V10 stream gets its 9.x aggregate, event names and revisions, field names and kept headers back; the root command's id, the command id, the command's 9.x name and the operator's id become the 9.x `trace_id`, `upstream_id`, `upstream_name` and `command_operator` headers. A stream's 9.x id is derived from its reference and version, the same on every run. Each event gets the body type 9.x recorded for its name and revision, and decimals are written as `Decimal128`.

It writes into the 9.x store of `source`, and only into a collection with 9.x's unique `(aggregateId, version)` index, which 9.x creates when it starts; without it, `export` stops and prints the command that creates it. Its checkpoint is the last log position written per V10 context and partition.

## Checkpoints

The checkpoint file, format 1, keeps where each pass stands: per 9.x aggregate, the import, the tail and when its last pass ended, the comparison and the snapshot writing; per `PrepareKey`, the reservation import; the compensation import; per V10 aggregate type, the tags backfill; per V10 context and partition, the export.

Beside it, `<checkpoint>.reservations.jsonl` records each key the import reserved, before reserving it, so a later import can release a key 9.x gave up. Keep the two files together.

Commands may run at the same time over one checkpoint file, such as `tail` beside `compare`: each change holds a lock on `<checkpoint>.lock` and replaces the file atomically, so none loses another's progress. A stopped run repeats at most one page.

The cut-over is kept in the V10 target, beside the log: one checkpoint row per partition, in the group `wow.migrate/<context>` under the name `cutover`, which each pass moves forward and never back. The service's subscriptions see the entries up to it as imported, and its transport sink publishes them marked so ([History imported from 9.x](../guide/published-streams#history-imported-from-9x)). A dry run records none.

Reserved keys and parked entries imported before the tool recorded their origin cannot be told from V10's own: import into a fresh V10 target, or remove them before an `import --restart`.
