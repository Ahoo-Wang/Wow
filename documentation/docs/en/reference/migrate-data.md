---
title: migrate-data CLI
description: The commands, options, exit codes, configuration file, migration map and checkpoint files of the migrate-data tool.
outline: deep
---

# migrate-data CLI

`migrate-data` moves a 9.x service's data into V10. This page lists everything it reads and does; [Migrate the data](../migrate/data) explains the procedure.

## Running

From a checkout of the Wow release you migrate to:

```bash
./gradlew :migrate-data:run --args="<command> --config <file> [options]"
```

Or from that release's image, `ahoowang/wow-migrate-data:<version>` (also on `ghcr.io/ahoo-wang/` and `registry.cn-shanghai.aliyuncs.com/ahoo/`), whose entry point is the command line:

```bash
docker run --rm -v /path/to/migration:/config:ro -e WOW_PG_PASSWORD \
  ahoowang/wow-migrate-data:<version> <command> --config /config/migrate-data.json [options]
```

It reads a 9.x MongoDB event store and writes a V10 log on PostgreSQL or MongoDB.

The image holds the tool alone, on JDK 25. Mount into it:

- the configuration and the map;
- the services' classes (`classpath`);
- a writable place for the checkpoint file, or keep the checkpoints in the target.

It runs as user 10001. Its heap is 75% of the container's memory limit (`JAVA_OPTS=-XX:MaxRAMPercentage=75.0`); a `JAVA_OPTS` of your own replaces that. On Kubernetes, [`deploy/migrate-data`](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/migrate-data) runs each command as a Job next to the stores ([Run it next to the stores](../migrate/data#run-it-next-to-the-stores)).

`migrate-data --help` prints the usage and exits with `0`.

## Commands

| Command | Options | What it does |
| --- | --- | --- |
| `draft-map` | `--out <file>`, `--force`, `--dry-run` | Drafts the map from the names the 9.x store holds and the V10 Manifests, and lists what it cannot match as exceptions: see [Draft the map](#draft-the-map). |
| `check` | `--decode` | Checks the map against the 9.x store and the V10 Manifests. Writes nothing. |
| `import` | `--dry-run`, `--restart` or `--final`, `--snapshots` | Checks, then imports every mapped 9.x aggregate, then the reserved keys and the unresolved compensation executions, resuming from the checkpoint. |
| `tail` | `--once` | Follows what 9.x commits after the import, until stopped. |
| `compare` | `--sample <percent>`, `--restart` | Compares each 9.x snapshot with the state V10 rebuilds from the log at the snapshot's version, checks that the log holds every stream 9.x committed since the import up to where the tail has read, then compares the reservations and the parked entries. Once a full comparison has completed, compares again only the instances changed on either side since and those that differed, or compares in full and says why ([Compare](../migrate/data#_5-compare)). |
| `snapshot` | `--restart` | Writes the V10 snapshot of every imported aggregate at its last version, and stamps its history with the state's access attributes; records the access walk of each V10 type whose snapshots all completed without a failure ([The access attributes](../migrate/data#_7-the-access-attributes)). Prints each instance whose state does not rebuild, goes on, and fails once it has walked every aggregate; the next run walks those aggregates again, passing over the snapshots written. Once a walk has completed, writes only the snapshots of the instances the V10 log changed since ([Write the snapshots](../migrate/data#_6-write-the-snapshots)). |
| `export` | `--dry-run`, `--restart` | Writes back to the 9.x store the streams V10 wrote after the cut-over, for a rollback; refuses streams with V10-only types. |

| Option | Meaning |
| --- | --- |
| `--config <file>` | The configuration file. Required by every command. |
| `--decode` | After a mapping check that passed, `check` reads every 9.x event of the mapped aggregates with the services' V10 models (the configuration's `classpath`), as the service reads it when it loads the aggregate, upgrades included. It prints a `PROBLEM decode:` line for each event that does not read, with its 9.x type and revision, its stream and the reason, and goes on, then a count by type; it fails when one does not read. An instance whose streams the store holds without a field the import needs is printed too, and the walk goes on with the next instance. |
| `--dry-run` | `import` reads and converts every stream but writes nothing and saves no checkpoint; `export` scans and prints its verdict but writes nothing; `draft-map` prints the draft and writes nothing. |
| `--out <file>` | `draft-map` writes the draft there instead of the map the configuration names. |
| `--restart` | Walks every instance again from the first, ignoring where the last pass stopped. With `import`, the final complete pass after 9.x stops writing; it also reconciles reserved keys and parked entries imported earlier. A stream the log already holds is passed over as it was converted: a map change after an import reaches only a fresh target ([Migrate the Data](../migrate/data#_4-import)). With `compare`, the check of the imported streams also reads again from each import's start. |
| `--final` | `import`'s shorter final pass after 9.x stops writing, when the tail followed 9.x until it stopped: one tail pass from the tail's checkpoint, reading `final.lookbackMillis` before it, then the reserved keys and the compensation executions walked again from the first. It refuses, writing nothing, when an aggregate's import did not complete or its tail's last pass ended more than `final.maxTailAgeMillis` ago; run `import --restart` then. Not with `--restart` or `--dry-run`. |
| `--snapshots` | After `import`, runs `snapshot`. |
| `--once` | `tail` makes one pass and ends. |
| `--sample <percent>` | `compare` compares that share of the instances, chosen by a hash of tenant and id, so every run compares the same ones. Another sample restarts the comparison. |
| `--force` | `draft-map` replaces an existing map. |

An option a command does not take is a usage error.

### Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Done. |
| `1` | Failed: the check, a step that stopped, or `import --final` refused. |
| `2` | Usage error. |
| `3` | `compare` found differences, in this run or in earlier runs it resumed, or a stream 9.x committed that the log does not hold; or, after `export`, 9.x and V10 hold different numbers of streams of an aggregate. |

### Output

Lines that need attention start with a marker:

| Marker | Meaning |
| --- | --- |
| `PROBLEM` | The check found something that stops the import. |
| `FAILED` | A step stopped, with the reason; or `import --final` refused an aggregate, whose import did not complete or whose tail did not follow 9.x until it stopped, and wrote nothing; or `snapshot` could not write an instance's snapshot. |
| `WARNING` | The check found something that does not stop it, such as a mapping nothing uses. |
| `DIFFERS` | `compare` found a difference: a state, a reservation or a parked entry. |
| `MISSING` | `compare` found a stream 9.x committed since the import, before where the tail has read, that the log does not hold at its version with its command. |
| `UNRESOLVED` | A reserved key or a compensation execution could not be placed, and was not imported. |
| `RELEASED` | A reservation imported earlier was released, because 9.x no longer confirms it to that owner. |
| `RESOLVED` | A parked entry imported earlier was resolved, because its 9.x execution is settled. |
| `REFUSED` | `export` found a stream with a type 9.x cannot read: an aggregate no 9.x aggregate maps to, or an event beyond the map's revision. Nothing is exported. |
| `CONFLICT` | `export` found a stream 9.x holds at the same version with another command or tenant. Nothing is exported. |
| `VERDICT` | `export`'s conclusion for a context, printed by every run, `--dry-run` included: switch back, export N streams, no rollback by export since a given time, or none because 9.x was written after the cut-over. |
| `EQUAL`, `DIFFERS` | After `export`, whether 9.x holds as many streams of an aggregate as V10. |
| `UNMATCHED`, `AMBIGUOUS`, `UNSOURCED`, `RENAMED`, `REVIEW` | `draft-map`'s exceptions, also written into the draft: see [Draft the map](#draft-the-map). |
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
| `manifests` | the classpath's | The merged V10 Manifests of the services the streams go to. Left out, the tool assembles each service's Manifest from the fragments of its modules on `classpath`, as the service's node does at startup, one per `classpath` entry. It fails, naming them, when no entry holds a context the map imports into. |
| `checkpoint` | `migrate-data.checkpoint.json` | The checkpoint file; or `target:<name>`, which keeps the checkpoints in the V10 target under that name (letters, digits, `.`, `_` and `-`, at most 128): see [Checkpoints](#checkpoints). |
| `source.uri`, `source.database` | required | The 9.x MongoDB event store. `draft-map` and `check` fail when the database holds no event stream, naming it: a misspelt name, or an unset `env:` variable's, reads as an empty store. |
| `source.createCommitTimeIndex` | `false` | Lets `tail` create the `(createTime, _id)` index it needs on each event stream collection, a write to the 9.x store. Otherwise it refuses to start without it and prints the command. `check` warns of each mapped collection without it, and creates none. |
| `target.type` | required | `postgres` or `mongo`. |
| `target.jdbcUrl`, `target.username`, `target.password` | | The PostgreSQL target. |
| `target.poolSize` | `16` | The most connections to the PostgreSQL target. |
| `target.uri`, `target.database` | | The MongoDB target, a replica set. |
| `partitions` | | Each V10 context's partition count, which must be the services' own. |
| `classpath` | the tool's own class path | The services' V10 domain modules, each a jar or a directory of classes or jars, such as a service's distribution `lib`. `compare` and `snapshot` rebuild states with their models, `check --decode` reads the 9.x events with them, and without `manifests` the Manifests come from them. The tool runs no decide function, so it stands in for each dependency the models take that is an interface, and gives a `java.time.Clock` or `java.time.ZoneId` the system's UTC one. A model taking a dependency of another class fails, naming it: take it as an interface in the model. |
| `compensation.database` | | The 9.x compensation service's database. Without `compensation`, nothing of the compensation service is read. |
| `compensation.uri` | the source's | The server of that database. |
| `compensation.service` | | The V10 service whose failure ledger receives the unresolved executions: its `wow.service`, or `spring.application.name`. |
| `pageSize` | `500` | Instances imported between two checkpoints, read with their streams in one scan, at most ten streams per instance of the page: a page of larger instances holds fewer. Also the streams the tail reads at once. |
| `streamBatch` | `200` | Streams of one instance read from the 9.x store at once, for the rest of an instance its page held only in part. |
| `concurrency` | `8` | The groups a page of the full import is appended in, at once: each group of whole instances is one call of the log (group commit), at most 1,000 streams. Also the instances the tail imports at a time. More groups need a larger `target.poolSize`, but in a 500,000-order bench 16 and 32 groups were slower than 8: each group commit waited longer on PostgreSQL. |
| `readParallelism` | `2` | The ranges of ids the full import walks at once, each its own scan of the 9.x store. They are split by ids sampled from the store when the import first starts, and kept until it completes. Each range is one more scan 9.x runs while it serves production. In a 500,000-order bench, 2 ranges imported 7 to 16% faster than 1, and 8 ranges 3 to 4% faster again. 9.x's MongoDB then used 0.47 CPU cores against 0.44 at 1, with the same operation rates. A probe reading one order's streams, as 9.x serves a command, saw its p99 rise from 13–22 ms at 1 range to 47–70 ms at 2 and 87–103 ms at 8, on a machine the stores and the tool shared. Through the dual run, keep 2, or 1 where 9.x's latency budget is tight; once 9.x has stopped, up to 8 ([Import](../migrate/data#_4-import)). |
| `lookbackMillis` | `300000` | How far before the last commit time it read the tail scans again. Each pass ends at the latest stream committed when it starts, so a stream is read when it becomes visible within this window of its commit time: make it larger than the clock skew of 9.x's nodes plus the slowest 9.x commit. |
| `pollIntervalMillis` | `1000` | The pause between two tail passes. |
| `final.lookbackMillis` | `60000` | How far before the tail's checkpoint `import --final` reads again. It covers the clocks of 9.x's nodes and the commits in flight when 9.x stopped, whose commit time is set before the write is visible. Make it twice the larger of the measured clock skew and the slowest 9.x commit. |
| `final.maxTailAgeMillis` | `600000` | How recently the tail's last pass must have ended for `import --final` to accept it. |

A service whose aggregates keep their logs in different V10 stores is migrated by one run per store, each with a map of the aggregates that store holds, which excludes the others (`excluded`).

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
        { "from": "pay_order", "to": "PayOrder" },
        { "from": "default_apply_resource_tags", "to": "TagOrder" }
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
        { "from": "default_resource_tags_applied", "to": "OrderTagged", "tags": true },
        { "from": "order_archived", "to": "OrderArchived", "retire": true },
        { "from": "order_noted", "drop": true }
      ],
      "state": {
        "fields": { "orderStatus": "status" },
        "ignore": ["payable"]
      },
      "reservations": [
        { "from": "order_no", "namespace": "order.no", "tenancy": "SYSTEM", "ownerId": "orderId" }
      ],
      "tags": [
        { "key": "region", "to": "region" },
        { "key": "team", "to": "teams" },
        { "key": "channel", "to": "channel", "source": "DERIVED" },
        { "key": "legacy", "reason": "No access policy reads it: 9.x set it for a report retired in 2024" }
      ]
    }
  ],
  "subscriptions": [
    { "from": "order-service.OrderSaga", "functions": { "onEvent": "onOrderCreated" } },
    { "from": "order-service.OrderProjector", "to": "OrderReports" }
  ],
  "excluded": [
    { "aggregate": "order-service.audit", "reason": "The audit service's V10 store takes it" },
    { "aggregate": "order-service.order", "stream": "(empty)", "reason": "A 9.x defect: an order stored without an id" }
  ]
}
```

A V10 name without a dot is a simple name in the aggregate's namespace (`OrderCreated` is `example.order.OrderCreated`); one with a dot is a full name, such as `wow.lifecycle.OwnerTransferred`.

### Top level

| Property | Meaning |
| --- | --- |
| `format` | `1`. |
| `aggregates` | One entry per 9.x aggregate; see below. |
| `headers` | The 9.x stream headers kept as request facts, 9.x key to V10 name. Every other header is dropped. A 9.x framework header (`trace_id`, `upstream_id`, `upstream_name`, `command_operator`, `local_first`, wait headers) cannot be listed: each becomes a field of its own or goes. An older 9.x wrote them with dots (`trace.id`, `upstream.id`, `upstream.name`, `command.operator`, `command.wait.*`), which read as the same headers, and some of its streams name their command only as `command.name`. |
| `subscriptions` | The 9.x processors with unresolved compensation executions, mapped to V10 subscriptions; see below. |
| `exceptions` | What `draft-map` could not match, one line each, for a person to resolve and then delete. The check warns of each line it still finds. |
| `excluded` | The 9.x aggregates (`aggregate`, `contextName.aggregateName`) and `PrepareKey`s (`prepareKey`) the map leaves out on purpose, each with its `reason`, which is required. An entry with `aggregate` and `stream` leaves out the streams of one instance of that aggregate, named by its 9.x aggregate id, in every tenant: an instance V10 cannot take, such as one whose id it refuses (below). The check warns of each with its reason, where it would otherwise fail; it fails an entry the map also maps, or excludes twice, and warns of one the store does not hold, and of an instance's streams whose aggregate the map does not import. `import`, `snapshot` and `compare` read none of them, and of an excluded instance nothing is imported: neither its streams nor the reserved keys it owns or the compensation executions on it, each printed `UNRESOLVED` with the entry's reason. `draft-map` never writes one. |

V10 refuses some ids 9.x took: an empty aggregate id (or none at all), a blank one, and `(none)`, the id V10 gives a request whose aggregate admission has not chosen yet. An instance with such an id cannot be imported, a 9.x data defect. The check fails with one `PROBLEM` per aggregate, listing each such id with the number of its streams and its tenants, written as the JSON string an `excluded` entry names it by: the empty id is `"(empty)"`, so it can be copied. A stream that records no tenant, or a blank one, which V10 refuses too, is listed the same way in a `PROBLEM` of its own, by its aggregate id. Decide what each instance's streams were, then exclude it, as the sample does with `{ "aggregate": "order-service.order", "stream": "(empty)", "reason": "…" }`.

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
| `tags` | Each 9.x tag key the aggregate's tags events wrote or its snapshots hold, and the V10 access attribute it becomes; see below. Every key the store holds needs one. |

### Events

| Property | Meaning |
| --- | --- |
| `from` | The 9.x event name. |
| `fromRevision` | The 9.x revision this entry maps; any revision when left out. |
| `to` | The V10 event name; for a retired event, the retired name. |
| `toRevision` | The revision the 9.x payload has in V10, 1 by default: a 9.x shape is revision 1, and the V10 upgrade functions read it up to the current revision. At most the Manifest's revision. |
| `fields` | Renames top-level payload fields, 9.x name to V10 name. For system events whose shape V10 fixes, such as 9.x `OwnerTransferred.toOwnerId`; a domain event's changes belong in its upgrade functions. |
| `upgrade` | The upgrade function the Skill wrote, recorded for the report; the tool does not run it. |
| `retire` | Writes the record as stored under the name `to`, which the V10 aggregate lists as retired, so loading skips it and the stream keeps its version. The check fails when `to` is not retired in the Manifest. Only for an event that changed no state: a retired deletion or state change is lost ([History the code no longer has](../migrate/data#_2-check-the-map)). |
| `lifecycle` | `{to, fields}`: the system lifecycle event written right after this event in the same stream, for a 9.x domain event that implemented one (`UserDeleted : AggregateDeleted`). `to` is `wow.lifecycle.AggregateDeleted`, `AggregateRestored`, `OwnerTransferred` or `SpaceTransferred`; `fields` builds its payload from the 9.x payload, 9.x name to V10 name, and gives `ownerId` or `spaceId` for the last two. 9.x `ResourceTagsApplied` has none: map it with `tags`. Not with `drop` or `retire`. |
| `drop` | Leaves the event out of its stream. A stream whose every event is dropped would have an empty version, which the check refuses: map such events with `retire`. |
| `tags` | For 9.x's tags event (`default_resource_tags_applied`, or a domain event implementing `ResourceTagsApplied`): its `tags` become fields of the V10 event `to`, one per key mapped as `APPLIED`, named as its access attribute, each value converted by the attribute's Manifest entry. Every such field is written, an absent key as `null` or `[]`, as 9.x replaced the whole map; the payload's other fields are kept, renamed by `fields`. Not with `drop` or `retire`. |

### Tags

9.x gave an aggregate tags two ways: its tags command (`default_apply_resource_tags`) set them, its event (`default_resource_tags_applied`) replacing the whole map, and a state implementing `StateAggregateTagsExtractor` derived them on every commit, 9.x keeping what it returned: an extractor that merged the tags it had (as the 9.x example's did) kept every value ever applied or derived since the last tags event. V10 has no tags: each key becomes an access attribute of the state (`@Access`), a field a domain event sets or a function of the state ([Tags and attribute-based access](../migrate/source#tags-and-attribute-based-access)).

| Property | Meaning |
| --- | --- |
| `key` | The 9.x tag key. |
| `to` | The V10 access attribute, as the aggregate's Manifest declares it. Left out, the key is left out of the migration. |
| `source` | Where its values come from. `APPLIED`, the default: the tags event set it, and the mapped tags event writes the state field. `DERIVED`: the 9.x state derived it, and V10 derives it with an `@Access` function. `BOTH`: set and derived, the extractor having the last word as in 9.x, so V10 derives it and the tags event leaves the values it set out. |
| `reason` | Required without `to`: why the key is left out. Leaving a key out changes who may read the data, so the map records why, and the check prints it as a warning. |

A value converts by the attribute's Manifest entry: a multiple attribute holds the key's values without duplicates, a single one its one value or `null`; an `INTEGER` attribute reads each value as an integer, a `BOOLEAN` one `true` or `false` in any case.

The check fails when:

- a key the tags events wrote or the snapshots hold has no mapping, or one names an attribute the V10 aggregate does not declare;
- a key is mapped as `DERIVED` though tags events wrote it, or as `APPLIED` though only snapshots hold it;
- the tags events wrote values of an `APPLIED` key its attribute cannot hold: two values or more for a single attribute, a value that is no integer or boolean for such an attribute. The problem counts them and names up to three streams that hold them;
- the tags event is mapped without `"tags": true`, or is dropped while it writes mapped keys.

It warns of a key left out, quoting its reason; of a key mapped as `BOTH`; of a mapped key the store does not hold; of another event whose payload holds a `tags` object; and of snapshots holding values a derived key's attribute cannot hold, naming up to three instances. The import writes none of those, but `compare` reports them: 9.x merged what its state derived into the tags it kept, so a snapshot can hold a key's former values beside its current one.

`compare` compares each attribute a key maps to as `$access.<attribute>`: the 9.x snapshot's tags converted as the import converts them, against the attributes V10 derives from the rebuilt state, each side normalized the same way, so the order of a multiple attribute's values is never a difference. The state comparison leaves the attribute out of the state. When the state field has another name than its attribute (`@Access("org") val orgPath`), list the field in `state.ignore`.

```text
DIFFERS example-service.order (0)/0VY3GwaK4OmV00E v6: $access.region: 9.x "west", V10 "east"
```

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

### Draft the map

Most of a map renames 9.x's `snake_case` names to V10's class names. `draft-map` writes those entries from what the 9.x store holds and the V10 Manifests, and writes the map the configuration names, or `--out`. It never replaces an existing file without `--force`; `--dry-run` prints the draft instead.

It matches names, the most certain way first:

1. **The 9.x lifecycle.** `default_aggregate_deleted`, `default_aggregate_recovered` and `owner_transferred` become `wow.lifecycle.AggregateDeleted`, `AggregateRestored` and `OwnerTransferred`, the last only for an aggregate with an owner. `default_delete_aggregate` and `default_recover_aggregate` become the lifecycle commands the V10 aggregate enables. The 9.x tags event `default_resource_tags_applied` becomes, with `"tags": true`, the one event of the V10 aggregate with a word of tags in its name (`OrderTagged`, `TagsAssigned`), and the tags command `default_apply_resource_tags` its one command of such a word (`TagOrder`); without one, the draft names `<Aggregate>Tagged` and `Tag<Aggregate>`, marked `REVIEW`. It drafts the `tags` table from where each key came from, a key the tags events wrote as `APPLIED` and one only the snapshots hold as `DERIVED`, each mapped to the access attribute of the same words, or marked `UNMATCHED`.
2. **Exactly.** The 9.x name is the V10 name, one of its former names, its full name (`example.order.OrderCreated`) or its class's qualified name.
3. **By the words.** The last part of the 9.x name and the V10 name, compared without case, `_`, `-` and dots, so `order_created` and `order-created` match `OrderCreated`.

At each step, one candidate is a match and several are ambiguous; none goes on to the next step. A 9.x name matching an event the V10 aggregate retired is mapped with `retire`. A mounted command is never a match, because a stream records the command it was turned into.

A 9.x context matches the V10 context of the same name, or of its name without a `service` suffix (`order-service` is `order`), or the Manifest's only context. A 9.x aggregate matches the V10 aggregate of its name in that context. If none has its name, it matches the aggregate whose events cover at least half of its own, when exactly one does best.

Whatever it cannot match it leaves out of the mappings, so the check refuses the draft until a person maps it. It lists each such case in the draft's `exceptions` and prints it:

| Marker | What a person does |
| --- | --- |
| `UNMATCHED` | Map the 9.x aggregate, event, command, `PrepareKey` or tag key it names. No V10 name matched it. |
| `AMBIGUOUS` | Choose among the V10 names that matched alike. |
| `UNSOURCED` | Confirm that the V10 aggregate, event or command has no 9.x source. Usually it is new in V10. |
| `RENAMED` | Confirm that the 9.x aggregate became the V10 aggregate its events matched. |
| `REVIEW` | Split an event the store holds at several revisions by `fromRevision` if their shapes differ, set `defaultCommand` for streams that record no command, or confirm a 9.x context mapped to the Manifest's only context. |

It writes no `headers`, `state`, reservation or subscription mappings: those need a person. Delete `exceptions` once every line is resolved; until then, the check prints each line as a warning.

## Export

`export` reads the map backwards. Each V10 stream gets its 9.x aggregate, event names and revisions, field names and kept headers back, and the tags event its `tags`: each attribute it sets under its 9.x key, its values as strings, an attribute without a value as no key; the root command's id, the command id, the command's 9.x name and the operator's id become the 9.x `trace_id`, `upstream_id`, `upstream_name` and `command_operator` headers. A stream's 9.x id is derived from its reference and version, the same on every run. Each event gets the body type 9.x recorded for its name and revision, and decimals are written as `Decimal128`.

It writes into the 9.x store of `source`, and only into a collection with 9.x's unique `(aggregateId, version)` index, which 9.x creates when it starts; without it, `export` stops and prints the command that creates it. Its checkpoint is the last log position written per V10 context and partition.

## Checkpoints

The checkpoints, format 1, keep where each pass stands:

- per 9.x aggregate: the import, the tail and when its last pass ended, the comparison, the snapshot writing, and how far `compare` found every stream 9.x committed in the log; the comparison and the snapshot writing also record where the V10 log ended when they started, the comparison its fingerprint and the instances that differed (at most 1000), which `compare` and `snapshot` go on from;
- per `PrepareKey`: the reservation import;
- the compensation import;
- per V10 context and partition: the export.

The import also records each key it reserves, before reserving it, so a later import can release a key 9.x gave up. A stopped run repeats at most the pages it had in flight: two being appended, and the one read ahead.

**In a file**, the default. The checkpoint file sits beside `<checkpoint>.reservations.jsonl`, the reserved keys. Keep the two files together. Each change holds a lock on `<checkpoint>.lock` and replaces the file atomically.

**In the target**, with `"checkpoint": "target:<name>"`. A run on another machine, or a Pod that restarted, finds them there, beside the log:

- the checkpoints of each name are one row of `wow_migrate_checkpoint` (a PostgreSQL table, or a MongoDB collection of that name), holding what the file holds;
- the reserved keys are rows of `wow_migrate_imported_key`;
- each change is a compare-and-set on the stored version, retried on a conflict.

### One run of each kind at a time

A run that writes the checkpoints holds the role of its command until it ends:

| Role | Commands |
| --- | --- |
| `import` | `import`, `import --final`, `tail`, `export` |
| `compare` | `compare` |
| `snapshot` | `snapshot`, `import --snapshots` |
| `export` | `export` |

`export` holds the `import` role too, since it writes into the 9.x store that the import and the tail read. Runs of different roles share the checkpoints, each writing its own entries, such as `tail` beside `compare`. `check`, `draft-map` and the dry runs hold no role.

- **A checkpoint file.** Each role is an operating-system lock on `<checkpoint>.<role>.lock`, which the system frees when the process ends, however it ends. A run that finds the lock held fails at once, naming the run the lock file records.
- **Checkpoints in the target.** Each role is a lease in the target's own lease store (`wow_lease`, group `wow.migrate.checkpoint/<name>`): 30 seconds, renewed every 10. A run that stopped without releasing its lease no longer renews it, so the lease expires. A run that finds a role held waits up to one lease for it to end, then takes it. If the holder keeps renewing it, the run fails, naming it:

  ```text
  FAILED The checkpoint target:order-service is in use: tail on migrate-data-tail-5c7d9 (pid 1, run 019a…) holds its import lease, renewed while it runs (now until 2026-11-03T10:15:30Z). One import, import --final, tail or export runs on a checkpoint at a time: stop that run, or wait for it to end.
  ```

  Every write carries the epochs of the run's leases. Suppose a run pauses longer than its lease, and another run takes the lease and writes. The first run's next write then fails, before it learns it lost the lease, and it stops without writing:

  ```text
  FAILED This run lost its import lease of the checkpoint target:order-service to another run, which has written the checkpoint since (lease epoch 2, this run's 1). It stops without writing.
  ```

The cut-over is kept in the V10 target, beside the log: one checkpoint row per partition, in the group `wow.migrate/<context>` under the name `cutover`, which each pass moves forward and never back. The service's subscriptions see the entries up to it as imported, and its transport sink publishes them marked so ([History imported from 9.x](../guide/published-streams#history-imported-from-9x)). A dry run records none.

Reserved keys and parked entries imported before the tool recorded their origin cannot be told from V10's own: import into a fresh V10 target, or remove them before an `import --restart`.
