# Cut-over and rollback, 9.x to V10

The job: move traffic from a 9.x service to its V10 service with the history intact, and keep a way back that is real. 9.x and V10 never write the same data at once: there is no mixed-version protocol between them. The 9.x cluster keeps serving while the data is imported and followed (`data.md`); the switch is a short stop of 9.x's writes, a final pass, and traffic to V10. Every step below needs the authorization of its own scope: rehearsal writes, production reads, production data writes, the traffic switch and the release are separate.

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
5. Timings of the final pass on production-sized data, which set the window.
6. Both rollback paths below rehearsed, with the list of what cannot go back.

## Before the cut-over: the point of no easy return

Rolling back after V10 has written needs every stream V10 wrote to have a 9.x form. List, from the map and the V10 Manifests, each V10 event type that has none, and give the list to the user before the switch:

- **An event whose V10 revision moved past the map's `toRevision`**: the V10 class was upgraded (`@Upgrade`) beyond the shape 9.x stored, often at the migration itself (a 9.x `OrderCreated` imported as revision 1 that V10 writes as revision 2). Every stream V10 appends with the new revision has no way back to 9.x: a new order created in V10 blocks the rollback.
- **An event or aggregate V10 added** that no 9.x name maps to.

Each such type, once V10 writes it in production, makes the export refuse. Either accept that the cut-over is one-way from the first such write, or keep those shapes and types out of the first V10 release until the rollback window closes. It is the user's decision; record it.

## The switch

1. **Announce the window; stop 9.x's writes.** Stop ingress to the 9.x services and drain their command queues, and stop the 9.x compensation service with them. Check that no 9.x writer is left (the inventory), and that in-flight work meets the agreed gate.
2. **Take and verify the final backup** of the 9.x stores.
3. **Final pass:** let `tail` drain and stop it; run `import --restart` (the complete pass: the streams, the reservations 9.x confirmed or released since, the executions still unresolved); then `compare` and `snapshot`; then `backfill-tags` where ABAC reads the `events` model.
4. **Gate:** `compare` shows only the signed-off kinds; no unexplained `UNRESOLVED` key or execution. Otherwise stop here and go back to 9.x (nothing was written to V10 in production yet).
5. **Start one V10 instance per service** against the production target and probe the critical paths: a command, a read, a query, a subscription, the ops service.
6. **Move the external consumers** of the 9.x bus to the V10 Kafka Sink.
7. **Switch traffic to V10**, then scale V10 out. Keep 9.x stopped, not deleted.
8. **Observe** the agreed signals: errors and problem codes, latency, command waits, subscription lag and parked aggregates, and the consumers. The rollback decision is taken on them.

## Rollback

Two paths, never confused:

- **V10 has not written yet** (stopped at step 4, or traffic switched back before the first V10 commit): stop V10, switch traffic back, restart 9.x on its untouched stores. Nothing to reconcile.
- **V10 has written** (decision D32): 9.x does not have V10's streams, so restarting 9.x alone loses them. Stop V10's writes and keep 9.x stopped. Run `migrate-data export --dry-run`: it scans each V10 context's log up to its heads and prints per aggregate type how many streams 9.x lacks, `REFUSED` for each stream of a type with no 9.x form (as listed above) and `CONFLICT` for a stream 9.x holds otherwise; either stops the export before anything is written. Then `export`: it writes the missing streams by the map read backwards, resumable and idempotent, and checks that each 9.x aggregate holds as many streams as V10 holds of its type (`EQUAL`, or `DIFFERS` with exit `3`). Then start 9.x. It needs 9.x's unique `(aggregateId, version)` index on each stream collection and stops, printing the command, without it.
  - Only event streams go back. Reservations V10 made, entries it parked and its projections' views have no 9.x form: list them and reconcile them by hand.
  - 9.x's snapshots, which its queries read, show the state before the exported streams until 9.x writes the instance again or regenerates its snapshots; a 9.x consumer of the event bus does not see the exported streams.
  - `export` is a newer command of the tool. Confirm the pinned `wow-migrate-data` lists it; where it does not, there is no rollback after V10 writes: record `MISSING EVIDENCE` and say the cut-over is one-way.

"Redeploy 9.x" is not a rollback once V10 has written. Neither is a rollback rehearsed only before V10 wrote.

## Report

The inventory, the rehearsal's evidence (each command with its exit code and counts), the signed-off difference kinds, the list of types with no way back and the user's decision on them, the window's timings, the switch's steps as done, and every gap as `MISSING EVIDENCE`. State separately: data imported and compared, V10 serving, external consumers moved, rollback rehearsed; none implies the next.
