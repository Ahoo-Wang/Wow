# Moving 9.x data into V10

The job: a 9.x service's history has to become the V10 service's log, and someone must be able to show that nothing was lost or changed before traffic moves. The tool is `wow-migrate-data` (artifact `me.ahoo.wow:wow-migrate-data`, main class `me.ahoo.wow.migrate.MigrateDataKt`, the same version as the V10 services; from a Wow checkout at that tag, `./gradlew :migrate-data:run --args="…"`). This page is how to use it as a rehearsal and as the cut-over's data path. Confirm every command, option and exit code in the tool's own usage (`migrate-data` with no arguments) and its README at the exact tag: they are the authority, not this page.

## What the tool does, and what it does not

It reads the 9.x MongoDB event store and writes the V10 log (PostgreSQL or MongoDB), with the V10 models of the migrated source to rebuild states. Per 9.x aggregate it keeps the stream's aggregate id, tenant, version, request id and commit time, so a stream keeps its identity across the move; 9.x transport headers go, and a header becomes a V10 request fact only when the map lists it. It also moves the confirmed 9.x `PrepareKey`s into V10 reservations and the 9.x compensation service's unresolved executions into the V10 failure ledger (the ops service then lists them).

It does not move: a 9.x Redis or Elasticsearch event store (move it to MongoDB on 9.x first), 9.x snapshots (V10 snapshots are rebuilt from the imported log), projections' read models (the V10 subscriptions rebuild them), or anything of a context the map does not name. External consumers of the 9.x event bus (a BI's Kafka engine tables among them) are moved to the V10 Kafka Sink at the cut-over; there is no 9.x-format Sink.

## Before anything runs

- **Source on the last 9.x minor**, the only line V10 imports from. Record the exact 9.x and V10 versions.
- **The source migration is done and compiles**, with its domain tests green, and it produced `migration-map.json` (the source part of this Skill writes it while rewriting the domain). The map is the contract between source and data: every event type the 9.x store holds, every `PrepareKey`, every processor with unresolved executions must be in it.
- **The V10 Manifests are built** (`build/wow/manifest.json` of each service) and the services' domain jars are on the tool's `classpath`, so `compare` and `snapshot` rebuild states with the same evolve and upgrade functions the services run.
- **An isolated target** for the rehearsal: an empty V10 database, never the production one, and a copy of the 9.x data or read-only access to it. Writing to the target, and any write to the 9.x side (the tail's index), each need the user's explicit authorization for that exact environment.
- **Secrets by reference:** the configuration takes `env:NAME` for passwords; never write one into the file or print it.

## The map, checked first

`check` compares the map with the event types, `PrepareKey`s and failed executions the 9.x store actually holds and with the V10 Manifests, and writes nothing. A gap stops every later command, so fix the map (or the source) until it passes:

| Map entry | What decides it |
| --- | --- |
| An event, `from` → `to` with `toRevision` | `toRevision` is the revision the 9.x payload has in V10: 1 for the 9.x shape; the V10 class's `@Upgrade` functions read it on from there. At most the Manifest's revision |
| `retire: true` | An event the V10 aggregate retired (`@AggregateRoot(retiredEvents)`): written as stored, skipped on load, its stream still a version. The name must be in the Manifest's `retired` |
| `drop: true` | Leaves the event out. A stream whose every event is dropped would leave its version empty, so `check` refuses it: use `retire` there |
| `reservations` | Each 9.x `PrepareKey` name to its V10 namespace and `tenancy` (`TENANT` or `SYSTEM`, the one the V10 code reserves with), and the path of the owner's id in the value |
| `subscriptions` | Each 9.x processor with unresolved executions to its V10 subscription (`<processor>.<function>`) |
| `state` | Only for `compare`: 9.x state field renames, and fields one side has by design (`ignore`), each of which is an explained difference |
| `headers` | The 9.x headers kept as request facts, by their V10 names; every other header is dropped |

## The rehearsal

Run it end to end on the isolated target, more than once, and keep each run's output as evidence:

1. **`check`**: passes, or the map is wrong.
2. **`import --dry-run`**, then **`import`**: every mapped aggregate, then the reservations and the unresolved executions, resuming from the checkpoint. Interrupt one run on purpose and resume it: the evidence that a stopped run repeats at most a page and duplicates nothing (the log's version key absorbs them).
3. **`tail`** while 9.x keeps writing: it follows what 9.x commits after the import by re-reading a window of commit time. It needs a `(createTime, _id)` index on each `<aggregate>_event_stream`; the tool prints the command, and creating it is a write to 9.x that needs its own authorization.
4. **`compare`**: each 9.x snapshot against the V10 state rebuilt at the snapshot's version, then the reservations and the parked entries. Exit `3` means differences. Zero is not always reachable: a 9.x snapshot can carry a 9.x defect that V10 fixed, and an instance without a snapshot cannot be compared. Classify every kind of difference (the report groups them by field) as a known 9.x defect or an intended change, and have a person sign each kind off. An unexplained kind blocks the cut-over. Report the share of instances that had a snapshot to compare.
5. **`snapshot`** (or `import --snapshots`): writes the V10 snapshots, so the V10 read side has no gap at the switch.
6. **`backfill-tags`**: the import writes streams without the aggregate's tags, so ABAC on the `events` model matches none of them until this runs. Run it before any ABAC-restricted event timeline is used.
7. **Start the V10 services on the target** and exercise the real surfaces: a command on a migrated aggregate appends at the next version, a state read and a query return the migrated state, the subscriptions rebuild the projections, and the ops console lists the parked entries.
8. **Rehearse the rollback** in both forms (`cutover-evidence.md`), including `export --dry-run` after the V10 services wrote on the target, where the tool provides `export`.

Measure each step's duration on a copy of production's size: it sets the cut-over window, and the final pass runs inside it.

## Exit codes and reports

`0` done, `1` failed (the check, or a step that stopped), `2` usage, `3` differences (`compare`; and the stream counts after `export`). Lines the operator must read: `DIFFERS` (an instance or entry that differs), `UNRESOLVED` (a key or execution not imported, with why), `RELEASED` and `RESOLVED` (a key or entry an earlier import made that 9.x has since given up), and, for `export`, `REFUSED` and `CONFLICT`. Report each with its count; never summarise a non-zero exit as done.

## Checkpoints

The checkpoint file keeps where every pass stands; `<checkpoint>.reservations.jsonl` beside it records the keys the import reserved. Keep the two together, per environment: a checkpoint from the rehearsal must never be used against production. Commands may run at once over one checkpoint (the tail beside `compare`), and `--restart` walks a pass from the first instance again, which the final pass at the cut-over uses.

## Limits to report

- A derived command's stream records only the chain's root as its cause (9.x kept only the root).
- Ids that collide across tenants are not reported by the tool; check for them where tenants share id ranges, and have a person decide.
- Reservations and parked entries imported before the import recorded their origin cannot be told from V10's own: rehearse on a fresh target.
- After the cut-over the V10 service owns the retries; a parked entry resolved in V10 then shows in a later `compare`.
