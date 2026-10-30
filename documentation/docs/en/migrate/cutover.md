---
title: Cut Over
description: Move a 9.x service, or an estate of services that depend on each other, to V10 with its history intact, offline by default or online for a large system, with the evidence a rehearsal must leave and the two ways back.
---

# Cut Over

The cut-over moves traffic from a 9.x service to its V10 service. 9.x and V10 never write the same data at once: there is no mixed-version protocol between them, so the cut-over is one write stop, and its length is what you choose between.

- **Offline, the default.** Stop 9.x's writes, import everything, compare, write the snapshots and the tags, start V10. It has the fewest steps and the least that can go wrong.
- **Online, the option for a large system** whose import does not fit a window it can stop for. 9.x keeps serving while the data is imported and followed, V10 waits on standby, and the write stop shrinks to a final pass.

A service that consumes another 9.x service's events, sends it commands, calls its routes or shares api jars with it does not cut over alone: the whole group moves in one window, as [Cut over an estate](#cut-over-an-estate) describes.

[Migrate the Data](./data) describes each command of `migrate-data`; this page is the switch around them.

## Offline or online

Choose by the rehearsal's measured import time against the stop the business accepts, and record the choice. The rehearsal of the 9.x example and its view store, 50,000 orders and 12,500 views over four tenants, ran both paths on one runner:

| Path | Write stop, 9.x stopped to V10 serving | Where the time went |
| --- | --- | --- |
| Offline | 243.4 s | The full import 98.2 s, compare 23.5 s, snapshots 43.8 s, tags 35.4 s, start of both services 36.3 s |
| Online | 159.5 s | The final pass 15.2 s, compare 24.2 s, snapshots 46.5 s, tags 38.0 s, activation of both services 29.3 s |

The offline stop grows with the whole history, the online one with what 9.x wrote since the tail last read, plus the steps both share. A history that imports within the window you can stop for goes offline.

## Inventory before any write

Record, for every store and stream:

- the environment, the owner, the database, the bounded context, and the 9.x and V10 versions;
- the 9.x stores (the MongoDB event store, the `PrepareKey` collections, the compensation database) and the V10 targets (the log, the snapshots, the reservations, the failure ledger);
- the streams and aggregates per aggregate type, and the share with a 9.x snapshot;
- every writer and reader of the 9.x data and how each is stopped, and every external consumer of the 9.x event bus, with the V10 Kafka stream it moves to;
- the 9.x topics and the V10 topics, and their brokers. A 9.x state topic is named `wow.<context>.<aggregate>.state`, as a V10 published stream is by default: on a broker 9.x used, give V10 a `wow.kafka.topics.prefix`, or V10's subscriptions meet 9.x records in those topics and, after a rollback, 9.x meets V10's;
- the backup of each 9.x store, and a restore that was tested;
- the migration map, and the V10 Manifests it was checked against.

Do not start a write with an unknown writer, an unknown owner or an untested backup.

## What the rehearsal must leave

An end-to-end rehearsal on an isolated target, on production-sized data, leaves:

1. `check` passing on the production map;
2. an interrupted `import` resumed without duplicates, and a second run that writes nothing;
3. `compare` with every kind of difference explained and signed off;
4. the V10 services started on the target: a command on a migrated aggregate appends at the next version, reads and queries answer the migrated state, projections rebuilt, parked entries listed;
5. the timings: the whole import with `compare`, `snapshot` and `backfill-tags` for offline, or the final pass for online;
6. both rollbacks rehearsed, with the list of what cannot go back.

## The point of no easy return

Rolling back after V10 has written needs every stream V10 wrote to have a 9.x form. From the map and the V10 Manifests, list each V10 event type that has none, and give the list to whoever decides the cut-over:

- **An event past its map's `toRevision`.** A V10 class upgraded beyond the shape 9.x stored, often at the migration itself: a 9.x `OrderCreated` imported as revision 1 that V10 writes as revision 2. Every new order V10 creates blocks the rollback.
- **An event or an aggregate V10 added**, which no 9.x name maps to.

The first such write in production ends the rollback window. Either accept that the cut-over is one-way from that write, or keep those shapes and types out of the first V10 release until the window has closed. The rehearsal's own refusal shows it: once V10 created orders and views at revision 2, `export --dry-run` refused both types.

## The offline switch

1. **Announce the window, stop 9.x's writes.** Stop ingress to the 9.x services, drain their command queues, and stop the compensation service with them. Check that no 9.x writer is left.
2. **Take and verify the backup** of the 9.x stores.
3. **Import:** `check`, then `import`.
4. **Gate:** `compare` shows only the signed-off kinds of difference, and no unexplained unresolved key or execution. Otherwise stop here and restart 9.x: V10 has written nothing.
5. **Prepare the read side:** `snapshot`, and `backfill-tags` where ABAC reads the events model.
6. **Start the V10 services** and probe the critical paths: a command on a migrated aggregate, a read, a query, a subscription, the parked entries.
7. **Move the external consumers** of the 9.x bus to V10's published streams.
8. **Switch traffic to V10**, then scale it out. Keep 9.x stopped, not deleted.
9. **Observe** the agreed signals: errors and problem codes, latency, waits, subscription lag, parked aggregates and the consumers. The rollback decision is taken on them.

## The online switch

From the first import until the switch, 9.x serves and V10 waits: `tail` follows what 9.x writes, and every V10 node starts with `wow.node.standby=true`. A node on standby serves reads and queries of what the import writes, refuses every command with 503 `wow.node.standby`, and runs no subscription, parked retry, job or snapshot maintenance, so nothing V10 does can write. It reports ready, so the read side can be checked against production data before the switch.

1. **Announce the window, stop 9.x's writes**, as offline does.
2. **Take and verify the final backup.**
3. **Final pass.** `import --final` when the tail followed 9.x until it stopped: one tail pass from its checkpoint, looking back to catch commits that became visible late, then the reservations and the unresolved executions again. It refuses, writing nothing, when an import is incomplete or the tail has fallen behind; the final pass is then `import --restart`, as long as the whole history. Then `compare`, `snapshot` and `backfill-tags`.
4. **Gate**, as offline does. Otherwise go back to 9.x: the V10 nodes are still on standby and have written nothing.
5. **Probe the standby nodes** against the production target.
6. **Move the external consumers.**
7. **Activate every V10 node:** `POST /wow/node/activate` on each node's internal port, with `Wow-Service: <the node's service>` and `{"by": "<operator>", "reason": "<why>"}`. The node joins its groups, starts its work and then admits commands. The activation ends the write stop; `GET /wow/node/standby` shows each node's record of it.
8. **Switch traffic to V10**, then scale it out. Keep 9.x stopped.
9. **Observe**, as offline does.

## Roll back

Two paths, never confused:

- **V10 has not written yet:** stopped at the gate, still on standby, or switched back before the first V10 commit. Stop V10, switch traffic back, and restart 9.x on its untouched stores. Nothing to reconcile.
- **V10 has written.** 9.x does not have V10's streams, so restarting it alone loses them. Stop V10's writes, keep 9.x stopped, and run `migrate-data export --dry-run`. Per context it prints a verdict: switch back, export N streams, the window closed at a given time, or 9.x was written after the cut-over, which a person resolves first. Then `export` writes the streams V10 added back to the 9.x store by the map read backwards, resumable and idempotent, checks that every 9.x aggregate holds as many streams as V10 holds of its type, and 9.x starts again. In the rehearsal, 16,914 order and 16,815 view commands written by V10 were exported and checked in 52.9 s.

Only event streams go back: reservations V10 made, entries it parked and its projections' views have no 9.x form, and are reconciled by hand. 9.x's snapshots show the state before the exported streams until 9.x writes the instance again or regenerates them. Redeploying 9.x is not a rollback once V10 has written.

Cutting over again after a rollback by export imports what 9.x wrote since and records the cut-over again, past the streams V10 wrote in its first window. A stream of that window the transport sink had not published before V10 stopped then goes out marked imported, and peers' sagas pass over it. Before stopping V10 for the rollback, let every subscription's backlog reach zero (`wow.subscription.backlog`).

## Cut over an estate

An estate is a set of 9.x services that cannot run on two major versions: each consumes another's events, sends it commands, calls its routes or depends on its api jar. The estate cuts over as one: one write stop for every service, every service's data imported, every node activated, and one decision to roll back. A service with no such link to the others cuts over on its own.

### Why it moves together

Nothing crosses between a 9.x service and a V10 one:

- **V10 does not read 9.x topics.** A V10 service reads V10 published streams and command topics only, never the 9.x `.event`, `.state` and `.command` topics. A V10 service whose upstream is still on 9.x receives nothing from it.
- **9.x does not read V10.** There is no 9.x-format sink, so a 9.x service whose upstream moved receives nothing either, and the commands it sends on the 9.x command topics reach no V10 service.
- **The routes change.** A V10 service serves V10 routes and headers only ([HTTP and TypeScript Clients](./http-and-clients)): a 9.x client of its command, snapshot or event routes stops working when it cuts over.
- **API jars do not mix.** A V10 service reads each peer's contract from the peer's V10 api jar, a 9.x service needs its peers' 9.x api jars, and no module can have both on its class path. An api that depends on another service's api brings it along: when the payment api depends on the order api, the order service on V10 cannot depend on the 9.x payment api, which brings the 9.x order api back.

There is no bridge between 9.x topics and V10 streams. A call to a route the service writes itself, which its V10 version serves unchanged, does not tie two services.

### Find the estate

Draw a graph with a node per 9.x service and an edge for each link below; the estate is every service connected to the one you migrate, directly or through others. Build it from the builds, the source and the configuration, and check it against the brokers.

1. **API modules.** List the other services' api jars on each service's runtime class path, and on its own api module's (`com.example` being the services' group; a module of the same build shows as `project :<name>-api`):

   ```bash
   ./gradlew -q :order-server:dependencies --configuration runtimeClasspath | grep -o -E 'com\.example:[a-z0-9-]+-api:[^ ]+' | sort -u
   ```

   ```bash
   ./gradlew -q :order-api:dependencies --configuration runtimeClasspath | grep -o -E 'com\.example:[a-z0-9-]+-api:[^ ]+' | sort -u
   ```

   The first lists every api jar the service runs with, the second the api jars its own api brings to its consumers. Two api modules that depend on each other, directly or through others, form a cycle.

2. **Events.** The 9.x event processors, sagas and projections (`@EventProcessor`, `@StatelessSaga`, `@ProjectionProcessor`) whose `@OnEvent` or `@OnStateEvent` functions take an event or state class of another service's api.
3. **Commands and reads.** The command classes of another service's api that the service sends, through `CommandGateway` or from a saga, and its clients of another service: `wow-apiclient` interfaces (`@CoApi`), snapshot query clients, CoCache caches of its state.
4. **Configuration.** The other services each service's configuration and its gateway's routes name: base URLs and service ids (`lb://payment-service`). Each is a call to account for in step 3.
5. **The brokers.** Every consumer group on the 9.x brokers and the topics it reads:

   ```bash
   kafka-consumer-groups --bootstrap-server kafka-9x:9092 --describe --all-groups
   ```

   A group of one service that reads another context's `.event` or `.state` topic is an edge of step 2. A group of no service of yours is an external consumer, which moves to the V10 published streams in the same window.

Record the estate as a table:

| Service | Consumes the events of | Sends commands to or calls | Its api depends on |
| --- | --- | --- | --- |
| order | payment | inventory | the payment api |
| payment | order | order | the order api |
| inventory | order | — | — |

Order and payment consume each other's events and their api modules form a cycle; inventory consumes the order's events. The three are one estate. Every front end and gateway that calls one of them changes with it, and so does every external consumer of their 9.x topics.

Their source migrations go together as well: each V10 service compiles against its peers' V10 api jars, so the api modules are rewritten first, in the order of their dependencies, those on a cycle together.

### One window

The estate shares one write stop. Each service still chooses [offline or online](#offline-or-online) by its own import time, and the estate's stop lasts as long as its slowest service's path: a service that imports online follows 9.x with `tail` before the window, one that imports offline does its whole import inside it.

1. **Stop every 9.x writer of the estate.** Stop ingress to every service: gateways, front ends, scheduled jobs, external producers. Let the estate drain until no 9.x consumer group of the estate lags on any topic (`kafka-consumer-groups --describe` shows each group's `LAG`), and look again until a pass finds none, since a command a saga sends makes events that others consume. Then stop every 9.x node and every compensation service, and record when the last writer stopped: the **stop instant**.

   V10 does not take up what 9.x had not handled: a saga or reactor of its own context starts at the end of its log, and one of another context's stream passes over the imported history (step 4). An event a 9.x consumer had not handled when writes stopped is handled by no one.

2. **Take and verify the backup** of every 9.x store of the estate.
3. **Import every service.** Each V10 log store is a `migrate-data` run of its own, with its own configuration, map and checkpoint, so the runs go side by side: `check` and `import` offline, `import --final` online. Then, per service, the gate: `compare` shows only the signed-off kinds of difference; then `snapshot` and `backfill-tags`. A service that fails its gate stops the estate: restart every 9.x service, since no V10 service has written.
4. **Give the published topics room for the history.** Each import records where the imported history ends in the service's log, its cut-over. Once a service is active, its transport sink publishes its whole history, each record up to the cut-over marked imported ([Published Streams](../guide/published-streams#history-imported-from-9x)). A peer's saga or reactor passes over the imported records, since 9.x already acted on them, and a peer's projection builds its read model from them. Give the published topics a retention that holds that history.

5. **Start every V10 node on standby**, the offline services' too (`wow.node.standby=true`), and probe each service's reads and queries against the imported data.
6. **Move the external consumers** of every service's 9.x topics to the V10 published streams.
7. **Activate every node of every service** with `POST /wow/node/activate`, as [the online switch](#the-online-switch) does. Jobs and sagas start at the activation, and a node on standby refuses commands, a synchronous call among them: activate a service before the services that call it synchronously (`SagaContext.call`, the `CommandClient` and `QueryApi` over `wow.peers`) where those calls have no cycle. A command sent on a command topic waits there until its context is activated.
8. **Switch the estate's traffic to V10** once every node of every service is active, front ends and gateways with it, then scale out. Keep every 9.x service stopped.
9. **Observe** every service, and the flows that cross them: an order paid, its stock reserved.

### Rehearse the estate

Rehearse each service as [What the rehearsal must leave](#what-the-rehearsal-must-leave) says, then the estate as a whole: on isolated targets and an isolated broker, with copies of every service's production data taken at the same moment.

1. The window end to end, in the order above, every service's `migrate-data` runs side by side, timed: the estate's stop, and each step's share of it.
2. No saga or reactor acts on the imported history: between the activation and the first request, the only commands sent are the scheduled jobs'.
3. The flows that cross services: a new order is paid and its stock reserved, each service answering the others through their topics and calls.
4. The estate's rollback, both paths, with every service's `export --dry-run` verdict.

### Roll back the estate

The estate rolls back as a whole or not at all: a service back on 9.x alone has lost its links with the others, still on V10.

- **No V10 service has written yet:** stop every V10 service, switch the traffic back, restart every 9.x service on its untouched stores.
- **A V10 service has written.** Stop ingress to every V10 service, let the estate drain until every subscription's backlog is zero (`wow.subscription.backlog`), and stop every V10 node. Run `export --dry-run` on every service's log store. The estate rolls back by export only when every verdict allows it: the first service whose window closes closes the estate's, so the estate's [point of no easy return](#the-point-of-no-easy-return) is the earliest of its services'. Then `export` every service, and start every 9.x service.

9.x does not publish the exported streams, so no 9.x consumer sees what V10 wrote in the window. What V10's sagas did in answer is already in the exported streams of the services they wrote to, but the read models of 9.x projections miss those streams: reconcile them by hand, with the rest that does not go back.

## Report

Record offline or online and why; for an estate, its services, the links that tie them and its stop instant; the inventory; the rehearsal's evidence, each command with its exit code and counts; the signed-off kinds of difference; the types with no way back and the decision taken on them; the window's timings; and the switch's steps as done. Data imported, V10 serving, consumers moved and rollback rehearsed are four separate facts: none implies the next.
