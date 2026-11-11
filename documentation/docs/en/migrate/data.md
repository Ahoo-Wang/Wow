---
title: Migrate the Data
description: Move a 9.x service's event history, reserved keys and unresolved compensation executions into V10 with migrate-data, check that V10 rebuilds the same states, and get ready to cut over.
outline: deep
---

# Migrate the Data

This page walks through moving a 9.x service's data into V10 with the `migrate-data` tool and cutting over. By the end, the V10 log holds the whole history, V10 rebuilds the same state as 9.x for every aggregate you can compare, and V10 serves the traffic. Every command and setting is listed in the [migrate-data reference](../reference/migrate-data).

There are two ways to cut over:

| | Offline, the default | Online, for large systems |
| --- | --- | --- |
| 9.x writes stop | Before the import, for the whole migration | Only for the final pass, after the history is imported while 9.x serves |
| Steps | Import, compare, snapshots, start V10 | Import, follow 9.x, V10 on standby, then a short final pass and activation |
| Use it when | The service can stop writing for as long as a full import takes | A full import takes longer than the service can stop writing |

The offline path is shorter and has fewer moving parts, so use it unless the write stop is too long. Measure it in a rehearsal: on 50,000 orders, the offline path stopped writes for 136.7 seconds, of which the full import took 43.1; the online path stopped them for 102.8 seconds, of which its final pass took 19.7 ([Cut Over](./cutover)). The online path pays off only once the history is much larger than the final window.

## What moves, and what does not

| 9.x data | V10 result |
| --- | --- |
| Event streams in the MongoDB event store | Entries of the V10 log, on PostgreSQL or MongoDB |
| Snapshots | Not copied: compared with the states V10 rebuilds, then V10 writes its own snapshots |
| Confirmed `PrepareKey` keys | Confirmed reservations |
| Unresolved executions of the compensation service | Parked aggregates in the V10 service's failure ledger |
| Custom projections and their views | Not migrated: their V10 subscriptions rebuild them from the log |
| Tags, applied and derived | Access attributes of the V10 state: the applied ones written into the domain event the 9.x tags event maps to, the derived ones derived by V10 |

Each imported stream keeps its version, command id, request id and commit time, so idempotency and history carry over. Its tenant and aggregate id become the V10 aggregate reference. The owner and space follow the V10 aggregate's ownership, the operator comes from the 9.x operator header, and the 9.x trace id becomes the chain's root. Headers the map does not list, framework headers and wait headers are dropped.

## Before you start

- **The rewritten source.** The V10 service compiles and its domain tests pass. Its build has produced the merged Manifest of each service whose aggregates you migrate.
- **The map.** The `wow-migrate` Skill wrote `migration-map.json` while rewriting the source. It names, for each 9.x aggregate, its V10 type and every command and event mapping. See [The map](../reference/migrate-data#the-map). For a large service, `draft-map` drafts the renames from the 9.x store and the Manifest, and lists what it cannot match for you to resolve ([Draft the map](../reference/migrate-data#draft-the-map)).
- **Reads on the 9.x store.** The tool only reads the 9.x MongoDB store, unless you let it create the index the tail needs, and except for the rollback's `export`, which writes back to it.
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

Or run that release's image, `ahoowang/wow-migrate-data:<version>`, whose arguments are the command line's ([Running](../reference/migrate-data#running)):

```bash
docker run --rm -v /path/to/migration:/config:ro -e WOW_PG_PASSWORD \
  ahoowang/wow-migrate-data:<version> check --config /config/migrate-data.json
```

### Run it next to the stores

A long import waits on its stores, stream by stream. When they are in a Kubernetes cluster, run the tool inside it, not through a port-forward or a VPN for hours. [`deploy/migrate-data`](https://github.com/Ahoo-Wang/Wow/tree/main/deploy/migrate-data) runs each command as a Job of the image, and `tail` as a Deployment of one replica:

- **The service's classes.** An init container copies the service's `lib` from the image its nodes run into a volume the tool reads (`"classpath": ["/service/lib"]`). The configuration names no `manifests`: the tool reads each service's Manifest from its modules' fragments in that `lib`, as the node does at startup.
- **The configuration and the secrets.** The configuration and the map come from a ConfigMap, the passwords from a Secret, which `env:NAME` reads.
- **The checkpoints.** They are kept in the target (`"checkpoint": "target:<name>"`), so a Pod that restarts on another node resumes where the last one stopped. One run of each kind writes them at a time; a second one waits for the first's lease, or fails, naming it ([Checkpoints](../reference/migrate-data#checkpoints)).
- **The network.** A NetworkPolicy admits nothing, and lets the pods reach DNS and their stores alone: the 9.x MongoDB and the V10 target.

A Job ends with the tool's exit code. A failed check, a usage error and differences fail it at once, since running it again changes nothing. A Pod lost to its node or its memory limit is run again, and resumes from the checkpoints.

In the rehearsal's kind cluster, the full import of 50,000 orders took 99 seconds inside the cluster, 114 seconds from the cluster's host through the stores' NodePorts, and 227 seconds through `kubectl port-forward`. Across a real network the gap grows with the round trip, which bounds the import stream by stream ([Limits](#limits)).

## 2. Check the map

`check` scans the 9.x store and compares what it finds with the map and the V10 Manifests. It writes nothing. It fails when:

- an event type, revision or command name the store holds has no mapping;
- a mapping names a V10 command, event or subscription the Manifest does not have, or a revision higher than the Manifest's;
- a retired event is not retired in the Manifest;
- dropping events would leave a stream with no events: a version cannot be empty, so map such an event as retired instead;
- a `PrepareKey` the store holds has no reservation mapping;
- a 9.x processor with unresolved compensation executions has no subscription mapping;
- an event stream collection has no `(aggregateId, version)` index, which 9.x creates, unique, on each one when it starts, and which the import walks the instances in: the problem prints the command that creates it;
- an instance has an aggregate id V10 refuses (empty, blank or `(none)`), a 9.x data defect: the problem lists each, as the map's `excluded` names it (`{ "aggregate": …, "stream": "(empty)", "reason": … }`), so a person can decide what its streams were and exclude it ([The map](../reference/migrate-data#top-level)).

Fix the map, or the source, and check again until it passes. `import --dry-run` goes one step further: it reads and converts every stream without writing.

**Events V10 does not read.** V10 reads events with Jackson 3's Kotlin module, which refuses a `null` element in a collection of a non-null type, such as a `List<String>` holding `null`: a shape some 9.x events stored. Wow keeps that strictness for new data, so a 9.x event of such a shape does not decode in V10, and the state that holds it does not rebuild, in `compare`, in `snapshot` or in the service. `check --decode` reads every 9.x event with the V10 models and lists, in one pass, each one that does not read, with its type, revision, stream and the reason. Do not loosen the class for old data: give each type it lists an `@Upgrade` that cleans the old shape, such as dropping the `null` elements, defaulting a field an older shape lacked (a number, or a field a peer's newer class requires) or turning a stored Wow query type into the service's own ([Wow types in events and state](./source#events-and-state)), from the revision the map gives the 9.x events (`toRevision`, 1 for the 9.x shape), raising the class's `@Revision` above it if it is not already, and run it again until it passes.

**History the code no longer has.** 9.x streams keep events, commands and aggregates that the 9.x code deleted long ago, and 9.x snapshots keep the effects of those events. Map each by what it did, not by whether its class exists; `draft-map` cannot tell these apart:

- an aggregate the code no longer has, whose streams 9.x never loads: exclude it, with its reason, and its `PrepareKey`s;
- an event that deleted its aggregate, a 9.x domain event that implemented `AggregateDeleted` or a removal event: give it `lifecycle: { "to": "wow.lifecycle.AggregateDeleted" }`, or map it to `wow.lifecycle.AggregateDeleted` when its class is gone. Never `retire` it: a retired deletion leaves the aggregate alive;
- an event that changed no state, such as a failure or a notice: `retire`;
- an event that changed state and whose class 9.x deleted: add a history-only event to the V10 aggregate, with its sourcing function and no command producing it, and map the 9.x name to it. Retiring it drops its effect, which the 9.x snapshots still hold;
- a command 9.x deleted: map it to the V10 command closest to it, since a stream records its command only as metadata.

A wrong choice shows in `compare` (step 5), as `$deleted` or as fields 9.x changed and V10 did not.

**Tags.** 9.x tags become access attributes of the V10 state ([Tags and attribute-based access](./source#tags-and-attribute-based-access)). The map names, for each tag key the store holds, the attribute it becomes and whether 9.x's tags event applied it or the 9.x state derived it, and maps the tags event, with `"tags": true`, to the domain event that sets them ([The map](../reference/migrate-data#tags)). The check reads the store's tags events and snapshots, and fails on a key the map does not name and on values the import would write that an attribute cannot hold: two values or more for a single attribute, or a value that does not read as the integer or boolean its attribute is. It counts them and names up to three streams that hold them. Before importing, make such an attribute hold several values, map the key to another, or correct the 9.x data: the import fails the stream that holds one. The same values in the snapshots of a derived key are a warning: 9.x merged what its state derived into the tags it kept, so a snapshot can hold a key's former values beside its current one, and `compare` reports them.

## 3. Stop 9.x writing

Stop every 9.x node that writes, and the 9.x compensation service. 9.x may keep serving reads from its own store, but nothing may commit until V10 takes over: a stream 9.x commits after the import is not in V10.

## 4. Import

`import` checks again, then imports every mapped aggregate, the reserved keys and the unresolved compensation executions. It records its progress in its checkpoints, a file or the V10 target ([Checkpoints](../reference/migrate-data#checkpoints)), so a run that stops resumes where it stopped, repeating at most the few pages it had in flight. It reads the next page while it appends one, and walks `readParallelism` ranges of ids at once, each its own scan of the 9.x store. Appends are idempotent: a stream already in the log is not written twice. It reads each aggregate in 9.x's own `(aggregateId, version)` index, a page of instances and their streams in one scan, so a page costs the same wherever it is in the store.

**A map change needs a fresh target.** `import`, `--restart` included, passes over a stream the log already holds, as it was converted then: a mapping change, a lifecycle added or an event no longer retired, does not reach the streams imported before it. Import the aggregates the change touches into a fresh target, or remove them from the target first, then compare again.

- **Reserved keys.** A key 9.x holds forever becomes a confirmed reservation of the aggregate whose id its value holds, in the tenant that aggregate's 9.x streams record, and in the namespace and tenancy the map gives. Keys with a time to live, and expired keys, are skipped. A key whose owner cannot be found, or appears in several tenants, is printed as `UNRESOLVED` and not imported.
- **Unresolved executions.** Each `FAILED` or `PREPARED` execution of the 9.x compensation service becomes a parked aggregate of the mapped V10 subscription, from the failed version, with its retry state and its recoverable mark. After the cut-over, the V10 service retries it, and the ops console lists it. An execution that cannot be placed is printed as `UNRESOLVED`.

## 5. Compare

`compare` rebuilds each aggregate that has a 9.x snapshot to the snapshot's version, with the V10 model, and compares:

- the state, as normalized JSON: fields in any order, a null field the same as a missing one, numbers by value, arrays in order and by length (a null element still counts);
- the deleted mark, the owner and the space;
- the access attributes the map's `tags` name, as `$access.<attribute>`: the 9.x snapshot's tags converted as the import converts them, against the attributes V10 derives from the rebuilt state, each side normalized the same way, so the order of a key's values is never a difference;
- whether the V10 log holds the snapshot's version at all.

It also checks the reservations and the parked entries both ways. Each difference is printed with the aggregate, its version and the fields:

```text
DIFFERS example-service.order (0)/0VY3GwaK4OmV00E v4: paidAmount: 9.x 25.00, V10 20.00
```

The summary of each aggregate gives the share of instances that have a 9.x snapshot, how many were compared, and the differing fields by kind. The command exits with `3` when anything differs.

**The imported streams themselves.** A state is compared at its 9.x snapshot's version, so a stream after an instance's latest snapshot is not seen: with 9.x's `version_offset` snapshot strategy, before 9.x writes the snapshot, or for an instance without one. So `compare` also reads the streams 9.x committed since each full import started (less the tail's `lookbackMillis`), on the tail's commit-time index, and checks that the log holds the latest of each instance, by version and command, one log read per instance. It reads up to `final.lookbackMillis` before the tail's checkpoint: during the dual run the tail may still be reading what came after, and after `import --final` that last window is what the final pass read once 9.x had stopped. Each stream missing is printed, and the command exits with `3`:

```text
MISSING view-store.view t0/0VZwlu3X00016xF v4: 9.x stream 0VZwlu3X00016xG (command 0VZwlu3X00016xH), committed at 2026-11-03T22:33:01.200Z, is not in the log, which holds v3
```

Import them before the cut-over: one `tail --once` whose `lookbackMillis` reaches back to the earliest one, or `import --restart`. A check that finds nothing missing records how far it read, so the next one reads only what 9.x committed since, less the look-back; `--restart` reads again from each import's start. An aggregate the tail never followed is not checked: its full import, after 9.x stopped, walked every stream.

**Do not cut over on an unexplained difference.** Zero differences may not be reachable: 9.x snapshots can carry defects that V10 does not reproduce, and some aggregates have no snapshot to compare with. So review the differences kind by kind. Each kind must be explained, as a known 9.x defect or an intended change of semantics, and signed off. Where a field exists on one side only by design, list it in the map's `state.ignore`, and where the Skill renamed a state field, in `state.fields`. Review the kinds in a rehearsal, so the real cut-over only confirms them.

`compare --sample 10` compares 10% of the instances, always the same ones, for quicker iterations.

**Compare in the service's time zone.** A state that turns a date into an instant with the system's zone, `LocalDate.atStartOfDay(ZoneId.systemDefault())` say, rebuilds to another instant in another zone: run `compare` in the zone 9.x ran in (`-Duser.timezone`, or `TZ`), as the V10 service must run.

**A stopped comparison resumes on the same inputs only.** `compare` records its progress after each page, so a run that stops resumes where it stopped and keeps what it found before. Otherwise what it compared no longer stands, and it starts again from the first instance and says why, `<aggregate> is compared in full again from the first instance: <reason>`, when:

- the sample changed since the saved comparison, stopped or completed;
- an earlier version stopped it, which recorded no inputs;
- the map, a Manifest, the classpath (the V10 models), the time zone or the tool changed since it stopped.

**Compared again, only what changed.** Once a full comparison of an aggregate has completed, `compare` compares again only the instances whose result can have changed since the last comparison started:

- the 9.x snapshots taken since then, by their `snapshotTime`, going back the final pass's look-back window (`final.lookbackMillis`), which covers the 9.x nodes' clocks running behind the tool's;
- the instances 9.x committed streams of since then, read on the tail's commit-time index;
- the instances the V10 log holds entries of after where it ended when the last comparison started;
- the instances the last comparison found differing.

Its summary gives how many it compared, and how many instances differ in all; each difference is printed again. It records where it started, so the next `compare` goes on from there. It checks the reservations and the parked entries in full, on every run.

The result is the full comparison's. One instance's comparison reads its 9.x snapshot, its V10 log up to the snapshot's version, and the map, the Manifests, the V10 classes, the sample, the time zone and the tool, and nothing else. The V10 log only grows. So an instance unchanged on both sides compares as it did. That holds when:

- 9.x stamps each snapshot with the time it takes it, by a clock no further behind the tool's than the look-back window. A snapshot stamped earlier than that is not read again;
- the map, the Manifests and the files of the classpath, the sample, the time zone and the tool are the ones the last comparison ran with. The tool checks this with a fingerprint of them.

Otherwise it compares in full, and says why, `<aggregate> is compared in full again: <reason>`, when:

- the last comparison ran with an earlier version, which recorded no position to compare again from;
- the map, a Manifest, the classpath (the V10 models), the sample, the time zone or the tool changed since the last comparison;
- more than 1000 instances differed, too many to record;
- the 9.x store has no commit-time index to read what 9.x committed since. The tail creates it.

`compare --restart` compares in full whatever was recorded. **`compare` is still required before switching traffic**: comparing again makes it short, and its result is the one to sign off.

**Classify each difference** before explaining it:

- **Shape**, a field one side has by design: a V10 field with its default, a field only an older 9.x snapshot holds, a peer's newer class, a number 9.x stored as a string, a decimal 9.x stored with less precision. List it in `state.ignore` once explained.
- **Environment**, which a rerun under the right conditions removes: what 9.x wrote after the import and the tail did not follow, a value computed from the current time, the time zone.
- **Real**: a mapping that dropped an event's effect or a deletion, or logic that changed after the 9.x snapshot was written. Fix the map or the code, or have the domain's owners sign the difference off.

## 6. Write the snapshots

`snapshot`, or `import --snapshots`, writes the V10 snapshot of every imported aggregate at its last version, so V10's queries have data the moment traffic arrives. The V10 service's own snapshot subscription then finds them in place. It also stamps each instance's history with its access attributes ([The access attributes](#_7-the-access-attributes)).

Once a walk has completed, `snapshot` catches up instead: it writes the snapshot of each instance the V10 log holds entries of after where the log ended when that walk started, or the last catch-up, and records where the log ends now. So a walk during the dual run leaves the cut-over only the instances changed since. `snapshot --restart` walks every instance again.

This is safe because a V10 snapshot is a cache of the log: V10 loads an aggregate from its snapshot and the log after it. An instance whose log did not change has the history its snapshot was written from. On MongoDB, an append in flight while the walk read where the log ended can land below that point, and the catch-up does not see it: that instance keeps its older snapshot, and V10 replays the rest of its log when it loads it. A change of the V10 models between two runs is not covered, by the catch-up or by `--restart`: neither writes a snapshot again at a version the store holds.

## 7. The access attributes

The import writes the tags the map names into the domain event the 9.x tags event maps to, so the state holds its access attributes at every version, and the snapshots `snapshot` writes carry them. The streams the import appends carry none. `snapshot` stamps them: beside each instance's snapshot, it stamps the instance's whole history with the attributes of the state it rebuilt, as of the history's last version, as the V10 nodes' access walk would ([Rebuild and Replay](../operate/replay#access-attributes)). Its catch-up stamps again the instances the log changed since, so the final pass's `snapshot` leaves no imported stream without attributes, and none waits for the activation. A stream stamped as of a later version keeps its attributes.

- **The walk is recorded.** Once every 9.x aggregate mapped to a V10 type has written its snapshots without a failure, `snapshot` records the type's access walk as completed for the type's access schema, and prints `<type>: its access walk is recorded as completed for the schema [<schema>]: <n> instances stamped.` The V10 nodes then do not walk the type again when they start or are activated, and `wow.access.walk.pending` reads 0 for it. A stream without attributes they read after that, one `snapshot` did not reach, is stamped by the built-in subscription `wow.access`. A V10 model whose access attributes differ from those `snapshot` ran with walks the type again.
- **What waits for it.** An imported stream not yet stamped is left out of an events query under an access policy, and of an events query, without a policy, by a caller acting as an owner or in a space. A caller of the whole tenant still reads it. A state read, a snapshot query, a version, a time and a trace are not affected: they decide on the state.
- **What it costs.** One stamp of each instance's history, beside its snapshot, under the same `concurrency`. In the final pass, `snapshot` runs inside the write stop of an online cut-over: time it in the rehearsal.
- **Without the snapshots.** When `snapshot` has not run, or failed for an instance of a type, the V10 nodes stamp the type's history once they start, or once they are activated from standby: one node at a time walks it, taking at most `wow.access.walk-share` of the log store's time, 25% by default, so it takes the imported instances divided by its pace, which slows under the commands' load ([Rebuild and Replay](../operate/replay#access-attributes) gives the pace and how to raise it in a maintenance window). Without the share, the rehearsal's 50,000 orders, three to four streams each, took three to four minutes, about 250 a second, on one PostgreSQL, longer than an online cut-over's write stop; run `snapshot` so the nodes have nothing to walk. `wow.access.walk.pending` reads 1 for each type until its walk completes, and the walking node logs `The access walk of <type> is completed: <n> aggregates stamped.` Streams appended after the walk began are stamped as `wow.access` reads them: its lag is `wow.subscription.lag` of the subscription `wow.access`, normally seconds ([Metrics](../reference/observability#metrics)). Switching traffic before then hides those streams from the queries above until they are stamped, and nothing else.

## 8. Start V10 and switch traffic

1. Start the V10 service's nodes. A saga or a reactor of the service's own context starts at the end of its log, so it does not act on the imported 9.x history; a projection starts at the beginning and builds its read model from it. Its transport sink publishes the imported history too, each record up to the cut-over the import recorded marked imported: a peer's saga or reactor passes over it, and a peer's projection builds from it ([Published Streams](../guide/published-streams#history-imported-from-9x)).
2. Switch traffic to V10, and move every external consumer of 9.x topics to the V10 published streams. There is no 9.x-format sink.

The first start after an import is longer than the next ones: each partition catches up on the imported history before the node owns it. Measure it in the rehearsal, and give the node's start that much time.

V10 parks the aggregate, not the event: a parked aggregate's later events wait while it is parked, and retrying it replays its streams from the failed version on, including streams 9.x had already handled after the failure. Handlers must tolerate that, as they tolerate any redelivery.

## Online cut-over for large systems

When a full import takes longer than the service can stop writing, import the history while 9.x still serves, follow what it keeps writing, and stop its writes only for a short final pass.

### Import while 9.x serves

Run steps 1 and 2, then `import` (step 4) without stopping 9.x. 9.x keeps changing keys and executions while you migrate: each import from the start reconciles what earlier runs imported, releasing a key 9.x gave up and resolving an entry whose execution 9.x has since settled.

### Follow 9.x

`tail` follows the streams 9.x commits after the import. 9.x has no position to resume from, so the tail scans by commit time, going back a window each pass, five minutes by default, to catch streams that became visible late; the log's version key absorbs the overlap. Each pass ends at the latest stream committed when it starts, so under steady writes a pass still ends, saves the tail's checkpoint and goes on to the next aggregate, and a stream that becomes visible within the window of its commit time is read by a later pass.

The tail reads each event stream collection by a `(createTime, _id)` index, which 9.x does not create. Without it, the tail refuses to start and prints the command that creates it. Creating it writes to the 9.x store, so it is a prerequisite of the cut-over on the 9.x side: build it before the dual run, when it suits the 9.x database, or let the tool create it (`source.createCommitTimeIndex`). A store the tool reads with read-only credentials needs its owner to build it. `check` warns of each mapped collection without it.

`tail` runs until it is stopped. `tail --once` makes one pass. Keep `tail` running until 9.x stops writing: the cut-over's final pass is then `import --final`, which reads from where the tail stopped instead of walking every stream again. Stop the tail before the final pass, which takes the same role: on Kubernetes, delete the tail's Deployment, which ends its current pass and releases its lease.

### V10 on standby

Through this dual run, start the V10 service's nodes on standby, with [`wow.node.standby`](../reference/configuration) set to `true`. A node on standby serves state reads and queries of what the tail imports, and refuses every command with [`wow.node.standby`](../reference/error-codes) (503), so nothing writes to V10 before the cut-over. It joins no lease group: no subscription handles anything, no parked entry is retried, and no job or schedule runs. A saga or a reactor that starts at the end of its stream starts where the stream ends when the node is activated, so it never acts on the imported 9.x history.

Import only while the V10 nodes are on standby or stopped. An import claims each type's access walk right after its first streams of that type, so that the nodes walk them. A node that accepts commands can, in the milliseconds before that claim, record the type's walk as completed over those streams, which carry no attributes: an events query under an access policy then leaves them out. If streams were imported into a service whose nodes were active, run `snapshot`, which stamps the history of every imported instance ([The access attributes](#_7-the-access-attributes)).

### Compare and snapshot during the dual run

Once the import is done and the tail follows 9.x, run `compare` and `snapshot`. Each walks every instance once and records where it started. At the cut-over, each then works only on what changed since (steps 5 and 6), which keeps the write stop short. Review the differences the full comparison finds now, so the cut-over only confirms them.

### The cut-over

1. Stop 9.x writing, and stop the 9.x compensation service.
2. Run the final pass. If `tail` followed 9.x until it stopped, run `import --final`: one tail pass from the tail's checkpoint, then the reserved keys and the compensation executions walked again, since the tail follows neither. It refuses, writing nothing, when an aggregate's tail did not run continuously. Otherwise, or after it refuses, run `import --restart`: a complete pass from the first instance, which takes as long as the whole history.
3. Run `compare` and review the result (step 5). After a full comparison during the dual run, it compares only what changed since, unless it says why it compares in full.
4. Run `snapshot` (step 6). After a walk during the dual run, it writes only the instances the log changed since.
5. Activate every V10 node: `POST /wow/node/activate` on its internal port, with `Wow-Service: <the node's service>` and a JSON body naming who activates it and why (`by`, `reason`). The node joins its lease groups, starts its subscriptions, retries and jobs, then admits commands; the activation ends the write stop. Its subscriptions start reading once the node has started every partition it takes, so the imported history does not slow the activation down: the projections catch up on it afterwards, at most [`wow.subscription.max-catch-ups`](../reference/configuration) (2) partitions of the node at a time, so the commands it admits meanwhile keep the store, and the `subscriptions` health shows their lag until they have. `GET /wow/node/standby` answers a node's status. A node left on standby keeps refusing commands and takes no partition.
6. Switch traffic to V10, and move every external consumer of 9.x topics to the V10 published streams.

## Roll back

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
- The import's speed is bound per stream more than per event: an aggregate whose instances hold one stream each imports at the store's round trip. Plan the window from the rehearsal's measurement per aggregate, not from the event count.
