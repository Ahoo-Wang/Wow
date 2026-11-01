# Cut-over and rollback, 9.x to V10

The job: move traffic from a 9.x service to its V10 service with the history intact, and keep a way back that is real. 9.x and V10 never write the same data at once: there is no mixed-version protocol between them.

**Offline is the default** (decision D267 ⑳): stop 9.x's writes, import everything, compare, write the snapshots and tags, start V10. It has the fewest steps and the least that can go wrong, and most 9.x systems import within a maintenance window; the rehearsal's timing proves whether this one does. **Online is the option** for a system whose import does not fit a window it can stop for: 9.x keeps serving while the data is imported and followed, V10 waits on standby, and the write stop shrinks to a final pass. Choose by the rehearsal's measured import time against the stop the business accepts, and record the choice. Every step below needs the authorization of its own scope: rehearsal writes, production reads, production data writes, the traffic switch and the release are separate.

## Inventory before any write

For every store and stream, recorded in the migration matrix:

- environment, owner, database or namespace, bounded context, and the 9.x and V10 versions;
- the 9.x store (MongoDB event store, `PrepareKey` collections, the compensation database) and the V10 target (PostgreSQL or MongoDB log, snapshots, reservations, failure ledger);
- streams, aggregates and size per aggregate type, and the share with a 9.x snapshot;
- every writer and reader of the 9.x data, how each is stopped, and every external consumer of the 9.x event bus with the V10 Kafka Sink it moves to;
- the backup of each 9.x store and a restore that was tested;
- the map (`migration-map.json`) and the V10 Manifests it was checked against.

Do not start a write with an unknown writer, an unknown owner or an untested backup.

## Evidence the rehearsal must leave

From an end-to-end rehearsal on an isolated target (`data.md`):

1. `check` passing on the production-shaped map.
2. An interrupted `import` resumed without duplicates, and a re-run that writes nothing.
3. `compare` with every kind of difference explained and signed off, and the share of instances compared.
4. The V10 services started on the target: a command on a migrated aggregate appends at the next version, reads and queries return the migrated state, projections rebuilt, parked entries listed.
5. Timings on production-sized data: the whole `import` (with `compare`, `snapshot`, `backfill-tags`) for an offline cut-over, which sets the window and decides whether offline fits; or the final pass for an online one.
6. Both rollback paths below rehearsed, with the list of what cannot go back.

## Before the cut-over: the point of no easy return

Rolling back after V10 has written needs every stream V10 wrote to have a 9.x form. List, from the map and the V10 Manifests, each V10 event type that has none, and give the list to the user before the switch:

- **An event whose V10 revision moved past the map's `toRevision`**: the V10 class was upgraded (`@Upgrade`) beyond the shape 9.x stored, often at the migration itself (a 9.x `OrderCreated` imported as revision 1 that V10 writes as revision 2). Every stream V10 appends with the new revision has no way back to 9.x: a new order created in V10 blocks the rollback.
- **An event or aggregate V10 added** that no 9.x name maps to.

Each such type, once V10 writes it in production, makes the export refuse (decision D267 ⑪): the first such write is **the end of the rollback window**, and the cut-over plan names it as such. Either accept that the cut-over is one-way from that write, or keep those shapes and types out of the first V10 release until the window has closed. It is the user's decision; record it.

## The offline switch (default)

1. **Announce the window; stop 9.x's writes.** Stop ingress to the 9.x services and drain their command queues, and stop the 9.x compensation service with them. Check that no 9.x writer is left (the inventory), and that in-flight work meets the agreed gate.
2. **Take and verify the backup** of the 9.x stores.
3. **Import:** `check`, then `import` (the streams, then the reservations and the unresolved executions; resumable from its checkpoint if it stops).
4. **Gate:** `compare` shows only the signed-off kinds; no unexplained `UNRESOLVED` key or execution. Otherwise stop here and restart 9.x: V10 has written nothing.
5. **Prepare the read side:** `snapshot`, and `backfill-tags` where ABAC reads the `events` model.
6. **Start the V10 services** against the production target and probe the critical paths: a command on a migrated aggregate, a read, a query, a subscription, the ops service's parked entries.
7. **Move the external consumers** of the 9.x bus to the V10 Kafka Sink.
8. **Switch traffic to V10**, then scale V10 out. Keep 9.x stopped, not deleted.
9. **Observe** the agreed signals: errors and problem codes, latency, command waits, subscription lag and parked aggregates, and the consumers. The rollback decision is taken on them.

No `tail`, no standby and no final pass: they exist only to shorten the stop, and offline does not need them.

## The online switch (option, for large systems)

Use it only when the rehearsal shows the full import does not fit the stop the business accepts. It adds the tail during the dual-run, V10 nodes on standby, and a shorter final pass (`data.md`).

### The dual-run window

From the first import until the switch, 9.x serves and V10 waits on standby. Start every V10 node with `wow.node.standby=true` against the production target: it serves state reads and queries of what the import writes, refuses every command with 503 `wow.node.standby` (Wow's clients treat it as retryable), and joins no lease or topic group, so it runs no subscription, retries no parked entry and runs no job, timer or snapshot maintenance. The read side can be checked against production data before the switch, and nothing V10 does can write. A standby node reports ready: `/actuator/health/readiness` is `UP`, its lease groups and topics marked `standby` rather than `DOWN`, so the load balancer can send it reads during the dual-run (traffic stays on 9.x until the switch decides otherwise). `GET /wow/node/standby` on a node's internal port says whether it is on standby.

### The switch

1. **Announce the window; stop 9.x's writes.** Stop ingress to the 9.x services and drain their command queues, and stop the 9.x compensation service with them. Check that no 9.x writer is left (the inventory), and that in-flight work meets the agreed gate.
2. **Take and verify the final backup** of the 9.x stores.
3. **Final pass**, then `compare`, `snapshot`, and `backfill-tags` where ABAC reads the `events` model:
   - **`import --final`** when `tail` followed 9.x continuously until it stopped. It first checks that every mapped aggregate's full import completed and that its tail's last pass ended within `final.maxTailAgeMillis` (10 minutes by default); otherwise it writes nothing, says why and exits `1`, and the final pass is `import --restart`. It then runs one tail pass from the checkpoint, reading again `final.lookbackMillis` before it (60 s by default; size it as twice the larger of the 9.x nodes' clock skew and the slowest 9.x commit latency), then the reservations and the unresolved executions again. Its cost is what 9.x committed since the tail last read.
   - **`import --restart`** when the tail did not run continuously: the complete pass over every instance, as long as the whole history (half the write stop of a 50,000-order rehearsal).
4. **Gate:** `compare` shows only the signed-off kinds, whichever pass ran; no unexplained `UNRESOLVED` key or execution. Otherwise stop here and go back to 9.x: the V10 nodes are still on standby and have written nothing.
5. **Probe the standby nodes** against the production target: reads and queries of migrated aggregates, the ops service's parked entries.
6. **Move the external consumers** of the 9.x bus to the V10 Kafka Sink.
7. **Activate every V10 node:** `POST /wow/node/activate` on each node's internal port, naming the node's service in `Wow-Service`, with a body `{"by": "<operator>", "reason": "<why>"}` (`by` is required: without it the node answers 400). The node joins its groups, starts the rest of its work and then admits commands; the activation ends the write stop. A subscription that starts at the end of its stream starts where the stream ends at the activation. Each activation is recorded (node, time, operator, reason) and returned by `GET /wow/node/standby` (also naming the service in `Wow-Service`); check every node reports it. An activation that fails stops the node: start it again on standby, find the cause in its log, and activate it before the switch; a node that is not on standby treats an activation as a no-op.
8. **Switch traffic to V10**, then scale V10 out. Keep 9.x stopped, not deleted.
9. **Observe** as offline does.

## Rollback

Two paths, never confused:

- **V10 has not written yet** (stopped at the gate or, online, before the activation; or traffic switched back before the first V10 commit): stop V10, switch traffic back, restart 9.x on its untouched stores. Nothing to reconcile.
- **V10 has written** (decision D32): 9.x does not have V10's streams, so restarting 9.x alone loses them. Stop V10's writes and keep 9.x stopped. Run `migrate-data export --dry-run`: it scans each V10 context's log up to its heads and prints per aggregate type how many streams 9.x lacks, `REFUSED` for each stream of a type with no 9.x form (as listed above) and `CONFLICT` for a stream 9.x holds otherwise; either stops the export before anything is written. Each context ends with a `VERDICT` line, which is the answer to give the user:
  - `roll back by switching back`: 9.x holds every stream V10 holds;
  - `roll back by export: N streams go back to 9.x`;
  - `no rollback by export: the window closed at <time>`: the commit time of the first stream V10 wrote that 9.x cannot read;
  - `no rollback by export: 9.x was written after the cut-over`: a `CONFLICT`, which a person resolves before anything else.

  Then `export`: it writes the missing streams by the map read backwards, resumable and idempotent, and checks that each 9.x aggregate holds as many streams as V10 holds of its type (`EQUAL`, or `DIFFERS` with exit `3`). Then start 9.x. It needs 9.x's unique `(aggregateId, version)` index on each stream collection and stops, printing the command, without it.
  - Only event streams go back. Reservations V10 made, entries it parked and its projections' views have no 9.x form: list them and reconcile them by hand.
  - 9.x's snapshots, which its queries read, show the state before the exported streams until 9.x writes the instance again or regenerates its snapshots; a 9.x consumer of the event bus does not see the exported streams.
  - `export` is a newer command of the tool. Confirm the pinned `wow-migrate-data` lists it; where it does not, there is no rollback after V10 writes: record `MISSING EVIDENCE` and say the cut-over is one-way.

"Redeploy 9.x" is not a rollback once V10 has written. Neither is a rollback rehearsed only before V10 wrote.

## An estate of services

9.x services that consume each other's events, send each other commands, call each other's Wow routes or share api jars cut over together (decision D379): V10 reads no 9.x topic, 9.x reads no V10 stream (no 9.x-format sink), Wow routes change, and no module mixes 9.x and V10 api jars. The site's Cut Over page, section *Cut over an estate*, is the procedure; check it at the pinned target. What the agent must not miss:

- **Find the estate** from each service's `runtimeClasspath` and its api module's (other services' `*-api` jars, cycles), its 9.x processors, sagas and projections of other services' events, the commands and clients it uses, the services its configuration names, and the 9.x brokers' consumer groups (`kafka-consumer-groups --describe --all-groups`). Report the table of services and links; a link not checked is `MISSING EVIDENCE`.
- **One write stop**, drained: stop ingress estate-wide, wait until no 9.x consumer group lags, then stop every 9.x node and compensation service; record the stop instant. Each service picks offline or online on its own; every `migrate-data` run (one per V10 log store) passes its own gate, and one failed gate sends the whole estate back to 9.x.
- **Imported history reaches peers marked** (X-38): each appending pass records the cut-over (`CUTOVER` line; checkpoint rows `wow.migrate/<context>` / `cutover` in the target), and an activated service's transport sink publishes every record up to it with `imported: true`. A peer's saga or reactor passes over those records, its projection builds from them; nothing to configure. Size the published topics' retention for the whole history. Confirm the pinned target prints `CUTOVER`; an older one does not mark the history, and a cross-context saga then acts on all of it again: record it, and set those subscriptions' `start` to the write stop.
- **V10 topics apart from 9.x topics**: a 9.x state topic and a V10 published stream share `wow.<context>.<aggregate>.state`; on a shared broker V10 needs `wow.kafka.topics.prefix`, else a V10 topic subscription stops at the first 9.x record (the read fails, logged with the topic and the prefix; nothing parks).
- **Start every node on standby, activate together**, callees of synchronous calls first; switch traffic only once every node is active.
- **Roll back the whole estate or none**: every service's `export --dry-run` verdict must allow it, so the earliest window to close closes the estate's.

## Report

Offline or online and why, for an estate its services, links and stop instant, the inventory, the rehearsal's evidence (each command with its exit code and counts), the signed-off difference kinds, the list of types with no way back and the user's decision on them, the window's timings, the switch's steps as done, and every gap as `MISSING EVIDENCE`. State separately: data imported and compared, V10 serving, external consumers moved, rollback rehearsed; none implies the next.
