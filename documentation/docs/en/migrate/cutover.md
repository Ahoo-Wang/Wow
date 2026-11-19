---
title: Cut Over
description: Move an estate of 9.x services that depend on each other to V10 with its history intact, offline in one window, the recommended path, or online for an import that does not fit the window; the order to prepare in, the front ends, the evidence a rehearsal must leave and the two ways back.
---

# Cut Over

The cut-over moves traffic from 9.x services to their V10 services. 9.x and V10 never write the same data at once: there is no mixed-version protocol between them, so the cut-over is one write stop.

**The recommended path is offline, the whole estate in one window.** An estate is every service connected to the one you migrate by events, commands, calls or api jars ([Find the estate](#find-the-estate)). Its preparation can go in order, one service after another; its go-live cannot: a V10 service reads no 9.x topic, and a 9.x service none of V10's, the api jars of the two versions cannot share a class path, and the routes, headers and error bodies all change. So a service that others depend on cannot go live first on its own, and an old front end cannot run against a new service.

[Migrate the Data](./data) describes each command of `migrate-data`; this page is the switch around them.

## The recommended path

1. **List the estate and its API dependency graph, cycles included**, before any code ([Find the estate](#find-the-estate)).
2. **Prepare in dependency order:** shared libraries, then API types, then domains and contracts, then servers and configuration; base services before the services that consume them ([Prepare in dependency order](#prepare-in-dependency-order)).
3. **Develop the front ends** once the services' contracts are stable, and release them in the same window ([Front ends](#front-ends)).
4. **Rehearse the estate** on copies of its data ([What the rehearsal must leave](#what-the-rehearsal-must-leave)).
5. **Cut over in one window:** stop every 9.x service, import every service's data, start V10 and let it catch up, verify, release the front ends, open the traffic. The decision to go back to 9.x is taken before the traffic opens ([One window](#one-window)).

An estate whose import does not fit a window it can stop for imports while 9.x serves instead ([Online](#online)). A service with no link to any other cuts over on its own ([A service with no links](#a-service-with-no-links)).

## Find the estate

List the estate and its API dependency graph, cycles included, before any code: the order of everything that follows comes from it.

### Why it moves together

Nothing crosses between a 9.x service and a V10 one:

- **V10 does not read 9.x topics.** A V10 service reads V10 published streams and command topics only, never the 9.x `.event`, `.state` and `.command` topics. A V10 service whose upstream is still on 9.x receives nothing from it.
- **9.x does not read V10.** There is no 9.x-format sink, so a 9.x service whose upstream moved receives nothing either, and the commands it sends on the 9.x command topics reach no V10 service.
- **The routes change.** A V10 service serves V10 routes and headers only ([HTTP and TypeScript Clients](./http-and-clients)): a 9.x client of its command, snapshot or event routes stops working when it cuts over.
- **API jars do not mix.** A V10 service reads each peer's contract from the peer's V10 api jar, a 9.x service needs its peers' 9.x api jars, and no module can have both on its class path. An api that depends on another service's api brings it along: when the payment api depends on the order api, the order service on V10 cannot depend on the 9.x payment api, which brings the 9.x order api back.

There is no bridge between 9.x topics and V10 streams. A call to a route the service writes itself, which its V10 version serves unchanged, does not tie two services.

### Draw the graph {#draw-the-graph}

Draw a graph with a node per 9.x service, and per library the services share, and an edge for each link below; the estate is every service connected to the one you migrate, directly or through others. Build it from the builds, the source and the configuration, and check it against the brokers.

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
6. **Shared libraries.** The libraries several services depend on that are no service's api: ids, values and interfaces they share. Each is a node of its own, prepared before the api modules that use it. A shared api module that carries another library's types, such as a service's api that exposes a security library's model, pins every consumer to that library's major version: settle that node before the window. Either the library moves with the estate, or the api stops exposing its types.

Record the estate as a table:

| Service | Consumes the events of | Sends commands to or calls | Its api depends on |
| --- | --- | --- | --- |
| order | payment | inventory | the payment api |
| payment | order | order | the order api |
| inventory | order | — | — |

Order and payment consume each other's events and their api modules form a cycle; inventory consumes the order's events. The three are one estate. Every front end and gateway that calls one of them changes with it, and so does every external consumer of their 9.x topics.

**Inside one service, the unit is an aggregate with its closure.** A service whose domain is rewritten part by part rewrites an aggregate together with what it reads by typed query and, for a saga, the aggregates whose commands it sends: a state's typed queries (`<State>Properties`) exist only where that state's root is compiled. [Migrate the Source](./source#the-build) says how to find that closure.

## Prepare in dependency order

Each V10 module compiles against the V10 modules it depends on, so the estate is prepared from the graph's leaves up:

1. **Shared libraries.** Plain libraries without the Wow plugin. An event class an aggregate emits does not belong in one: each context declares its own.
2. **API types**, each service's api module in the order of their dependencies.
3. **Domains and contracts.** Each service's domain, and the contract its api module publishes from it.
4. **Servers and configuration**, with the clients each server uses to call its peers.

Base services come before the services that consume them, and modules on a cycle go together. Within one build a cycle is prepared at once. For services on a cycle across repositories, one side's api module is first published without its contract, the other side builds against it, then the first publishes in full; every later release builds against its peers' previous versions, as in 9.x. The types-only publication is `./gradlew :<api>:publish -Pwow.contract.publish=false`, which builds no domain and says so in one line, under a version of its own that orders before the full release, such as `1.0.0-types` before `1.0.0`: a published version cannot gain its contract later. A service of that build refuses to merge its Manifest, naming the property, and a service resolving the types-only version is told to depend on the module's full release.

Preparing in order releases nothing: every service stays on 9.x in production until the window.

### A service that reads its peers' streams {#a-service-that-reads-its-peers-streams}

A service whose processors read another context's published streams starts only with that context's V10 contract on its class path, the one its api module publishes. So the first local start of a downstream service needs each of its upstreams' V10 api modules. Without one, it refuses to start before it opens a store:

```text
The service's contracts do not hold together:
- subscription <context>.<processor>.<function> reads context <peer>, which no Manifest declares
```

A types-only release of the upstream (`-Pwow.contract.publish=false`) carries no contract, so the downstream does not start against it either: it is told to depend on the module's full release.

There is no stand-in for development. A peer's contract comes only from the class path, from its api module's `META-INF/wow/contract.json`: `wow.peers.<context>.contract: manifest` serves calls to the peer, never subscriptions to its streams, and the test server's fake peer serves calls alone and needs the contract too. A downstream service is therefore started locally only once each upstream's api module and domain are on V10 and its api module is published with its contract. Until then its sources can be migrated, but it does not start. This is the order above: base services first.

## Front ends

A front end is developed once the contracts of the services it calls are stable, with clients generated from their Manifests ([HTTP and TypeScript Clients](./http-and-clients)), and released in the window, after the services are verified and before the traffic opens. An old front end cannot run against a V10 service, because V10 forces these changes on every caller:

| What changes | Where |
| --- | --- |
| The routes: one command route per command, the tenant and owner from the caller, never the path | [Routes](./http-and-clients#routes) |
| The command headers: `Wow-Wait`, `Wow-Request-Id`, `If-Match`, `Wow-Scope` for the 9.x `Command-*` headers | [Headers](./http-and-clients#headers) |
| Errors: an RFC 9457 problem (`application/problem+json`), and every code renamed | [Errors](./http-and-clients#errors) |
| Ids: the aggregate id moves from a header to the route, or into the body for a command whose body names its aggregate | [What changes without a type error](./http-and-clients#what-changes-without-a-type-error) |
| Snapshot queries: one query route per aggregate, the operation in the body, whole records answered | [Routes](./http-and-clients#routes), [Client code](./http-and-clients#client-code) |
| On a CoSec host, a new sign-in, and the app taken from the token | [Authenticate callers](../operate/security#authenticate-callers) |

## Inventory before any write

Record, for every store and stream:

- the environment, the owner, the database, the bounded context, and the 9.x and V10 versions;
- the 9.x stores (the MongoDB event store, the `PrepareKey` collections, the compensation database) and the V10 targets (the log, the snapshots, the reservations, the failure ledger);
- the streams and aggregates per aggregate type, and the share with a 9.x snapshot;
- every writer and reader of the 9.x data and how each is stopped, and every external consumer of the 9.x event bus, with the V10 Kafka stream it moves to;
- every external producer of commands on the 9.x command topics (`wow.<context>.<aggregate>.command`), found from the topics' ACLs, the brokers' producer client ids and the code that names them, with how it moves ([Move the external command producers](#move-the-external-command-producers));
- the 9.x topics and the V10 topics, and their brokers. A 9.x state topic is named `wow.<context>.<aggregate>.state`, as a V10 published stream is by default: on a broker 9.x used, give V10 a `wow.kafka.topics.prefix`. Without it, a V10 subscription stops at the first 9.x record it reads, logging the topic and the prefix to set, and after a rollback 9.x meets V10's records;
- the backup of each 9.x store, and a restore that was tested;
- what the 9.x side must provide: the `(createTime, _id)` index the tail reads each event stream collection by, created by the store's owner (`check` warns of each one missing), the 9.x services' time zone, and the state of their CosId segments;
- the migration map, and the V10 Manifests it was checked against;
- where `migrate-data` runs and how it reaches each store: next to them, such as a Job in their Kubernetes cluster ([Run it next to the stores](./data#run-it-next-to-the-stores)), rather than through a port-forward or a VPN for the whole import.

Do not start a write with an unknown writer, an unknown owner or an untested backup.

## What the rehearsal must leave

An end-to-end rehearsal on an isolated target, on production-sized data, leaves:

1. `check` passing on the production map, and `check --decode`: every 9.x event reads with the V10 models;
2. an interrupted `import` resumed without duplicates, and a second run that writes nothing;
3. `compare` with every kind of difference explained and signed off;
4. the V10 services started on the target: a command on a migrated aggregate appends at the next version, reads and queries answer the migrated state, projections rebuilt, parked entries listed;
   - started with a clean environment that holds only the configuration the rehearsal names, so no variable a shell inherited points a service at shared infrastructure, and their logs naming no address outside the target;
   - in the 9.x services' time zone (`TZ`), when domain code turns dates into instants with the system's zone;
   - a first start on the migrated target, every partition acquired and kept;
5. the timings: the whole import with `compare` and `snapshot` for offline, or the final pass for online;
6. both rollbacks rehearsed, with the list of what cannot go back.

### Rehearse the estate

Rehearse each service as [What the rehearsal must leave](#what-the-rehearsal-must-leave) says, then the estate as a whole: on isolated targets and an isolated broker, with copies of every service's production data taken at the same moment.

1. The window end to end, in the order above, every service's `migrate-data` runs side by side, timed: the estate's stop, and each step's share of it.
2. No saga or reactor acts on the imported history: between the activation and the first request, the only commands sent are the scheduled jobs'.
3. The flows that cross services: a new order is paid and its stock reserved, each service answering the others through their topics and calls.
4. The estate's rollback, both paths, with every service's `export --dry-run` verdict.

## The point of no easy return

Rolling back after V10 has written needs every stream V10 wrote to have a 9.x form. From the map and the V10 Manifests, list each V10 event type that has none, and give the list to whoever decides the cut-over:

- **An event past its map's `toRevision`.** A V10 class upgraded beyond the shape 9.x stored, often at the migration itself: a 9.x `OrderCreated` imported as revision 1 that V10 writes as revision 2. Every new order V10 creates blocks the rollback.
- **An event or an aggregate V10 added**, which no 9.x name maps to.

The first such write in production ends the rollback window. Either accept that the cut-over is one-way from that write, or keep those shapes and types out of the first V10 release until the window has closed. The rehearsal's own refusal shows it: once V10 created orders and views at revision 2, `export --dry-run` refused both types.

## One window

The estate shares one write stop: every 9.x service stops, every service's data is imported, V10 starts and catches up, it is verified, the front ends are released, and the traffic opens. Offline, every service does its whole import inside the window. A service whose import does not fit imports online and follows 9.x with `tail` before the window ([Online](#online)); the estate's stop then lasts as long as its slowest service's path.

1. **Stop every 9.x writer of the estate.** Stop ingress to every service: gateways, the old front ends, scheduled jobs, external producers. Let the estate drain until no 9.x consumer group of the estate lags on any topic (`kafka-consumer-groups --describe` shows each group's `LAG`), and look again until a pass finds none, since a command a saga sends makes events that others consume. Then stop every 9.x node and every compensation service, and record when the last writer stopped: the **stop instant**.

   V10 does not take up what 9.x had not handled: a saga or reactor of its own context starts at the end of its log, and one of another context's stream passes over the imported history (step 4). An event a 9.x consumer had not handled when writes stopped is handled by no one.

2. **Take and verify the backup** of every 9.x store of the estate.
3. **Import every service.** Each V10 log store is a `migrate-data` run of its own, with its own configuration, map and checkpoint, so the runs go side by side: `check` and `import` offline, `import --final` online. Then, per service, the gate: `compare` shows only the signed-off kinds of difference; then `snapshot`. A service that fails its gate stops the estate: restart every 9.x service, since no V10 service has written.
4. **Give the published topics room for the history.** Each import records where the imported history ends in the service's log, its cut-over. Once a service is active, its transport sink publishes its whole history, each record up to the cut-over marked imported ([Published Streams](../guide/published-streams#history-imported-from-9x)). A peer's saga or reactor passes over the imported records, since 9.x already acted on them, and a peer's projection builds its read model from them. Give the published topics a retention that holds that history.

5. **Start every V10 node on standby**, the offline services' too (`wow.node.standby=true`), and probe each service's reads and queries against the imported data.
6. **Move the external consumers** of every service's 9.x topics to the V10 published streams.
7. **Activate every node of every service** with `POST /wow/node/activate`, as [the online switch](#the-online-switch) does. Jobs and sagas start at the activation, and a node on standby refuses commands, a synchronous call among them: activate a service before the services that call it synchronously (`SagaContext.call`, the `CommandClient` and `QueryApi` over `wow.peers`) where those calls have no cycle. A command sent on a command topic waits there until its context is activated.
8. **Let V10 catch up and verify it.** Each node's subscriptions catch up on the imported history, and each transport sink publishes it: wait until no subscription has a backlog (`wow.subscription.backlog`). Check the reads and queries, the subscriptions and the parked entries of every service. A command sent to check is a write of V10's.
9. **Decide: go on, or go back to 9.x.** Decide now, before the traffic opens. Until then V10 has written nothing but its scheduled jobs' commands and any probe's: go back by restarting 9.x if it wrote nothing, or by `export` if it did ([Roll back](#roll-back)). Once the traffic opens, V10 writes what 9.x has no form for, and the way back closes at the first such write ([The point of no easy return](#the-point-of-no-easy-return)).
10. **Release the front ends, then open the traffic** once every node of every service is active: gateways and external command producers with it, then scale out. Keep every 9.x service stopped.
11. **Observe** every service, and the flows that cross them: an order paid, its stock reserved.

### Who creates the topics {#who-creates-the-topics}

A context's topics are created by its own nodes or by an operator, never by a reader. `wow.kafka.ddl`, over `wow.ddl`, decides which:

- **`auto`, the default.** Each node creates the topics its context owns when it starts, on standby too: its command topic when it takes commands from it, its receipts topic, and the streams it publishes. A topic that exists is never altered.
- **`validate`.** The node creates none, and refuses to start while one is missing, or while its command topic has another number of partitions than its log (`The topics of node … are not as it needs them (topic DDL validate)`). The operator creates them first, as the sample deployments under `deploy/` describe with `kafka-topics --create`.
- **No reader creates a topic it reads.** Its consumers never create a topic by asking for it, and the `wow-service` chart runs no topic job.

A node whose peers' topics do not exist yet still starts. It waits for each topic, retrying without end, and once it is activated its readiness stays down while it waits: the `topics` component of `/actuator/health/readiness`, unless `wow.health.topics.required` is `false`. On standby that component is up, marked `standby`. So in the window, with `auto`, starting every node on standby (step 5) creates every context's topics before any node is activated. With `validate`, the operator creates every context's topics before step 5. Either way they exist before the activations of step 7, whatever order the services start in. Unlike a missing topic, which is waited for, brokers whose names do not resolve at a node's start stop it: the node exits, naming the hosts and `wow.kafka.bootstrap-servers`, and its orchestrator restarts it.

## Online, when the import does not fit the window {#online}

Choose online for a service whose measured import does not fit the window the business can stop writes for. It costs more than the offline window: `tail` follows 9.x for as long as the dual run lasts, V10 nodes wait on standby, `compare` and `snapshot` walk the history during the dual run and again over what changed, and the switch has more steps that can go wrong. In an estate, each service chooses by its own import time, and the others still import offline inside the window.

### Offline or online {#offline-or-online}

Choose by the rehearsal's measured import time against the stop the business accepts, and record the choice. The rehearsal of the 9.x example and its view store, 50,000 orders (half of them tagged) and 12,500 views over four tenants, ran both paths on one runner:

| Path | Write stop, 9.x stopped to V10 serving | Where the time went |
| --- | --- | --- |
| Offline | 136.7 s | The full import 43.1 s, compare 25.1 s, snapshots 44.1 s, start of both services 18.0 s |
| Online | 102.8 s | The final pass 19.7 s, compare 27.8 s, snapshots 47.1 s, activation of both services 1.8 s |

`snapshot` also stamps the imported history with its access attributes ([The access attributes](./data#_7-the-access-attributes)), inside the write stop: time it in your rehearsal; the table above was measured before it did. The offline stop grows with the whole history. The online one grows with what changed since the dual run's last tail pass, comparison and snapshot walk, plus the activation. A history that imports within the window you can stop for goes offline.

### The online switch {#the-online-switch}

From the first import until the switch, 9.x serves and V10 waits: `tail` follows what 9.x writes, and every V10 node starts with `wow.node.standby=true`. A node on standby serves reads and queries of what the import writes, refuses every command with 503 `wow.node.standby`, and runs no subscription, parked retry, job or snapshot maintenance, so nothing V10 does can write. It reports ready, so the read side can be checked against production data before the switch.

Once the import is done and the tail has caught up, run `compare` and `snapshot` during the dual run. Each walks every instance once and records where it started, and the differences the full comparison finds are reviewed and signed off then.

1. **Announce the window, stop 9.x's writes**, as offline does.
2. **Take and verify the final backup.**
3. **Final pass.** Stop the tail first: the final pass takes its role, and waits for its lease. `import --final` when the tail followed 9.x until it stopped: one tail pass from its checkpoint, looking back to catch commits that became visible late, then the reservations and the unresolved executions again. It refuses, writing nothing, when an import is incomplete or the tail has fallen behind; the final pass is then `import --restart`, as long as the whole history. Then `compare` and `snapshot`, which after their walks during the dual run read only what changed since. `compare` compares the instances changed on either side and those that differed, and gives the result a full comparison would ([Compare](./data#_5-compare)). It names each aggregate it compares in full again, and why. `snapshot` writes the instances the V10 log changed ([Write the snapshots](./data#_6-write-the-snapshots)). The gate still needs this `compare`: its result is the one to sign off.
4. **Gate**, as offline does. Otherwise go back to 9.x: the V10 nodes are still on standby and have written nothing.
5. **Probe the standby nodes** against the production target.
6. **Move the external consumers.**
7. **Activate every V10 node:** `POST /wow/node/activate` on each node's internal port, with `Wow-Service: <the node's service>` and `{"by": "<operator>", "reason": "<why>"}`. The node joins its groups, starts its work and then admits commands. The activation ends the write stop; `GET /wow/node/standby` shows each node's record of it.
8. **Switch traffic to V10**, the external command producers with it, then scale it out. Keep 9.x stopped.
9. **Observe**, as offline does.

## Move the external command producers

A producer outside the 9.x services that writes commands to a Kafka command topic, such as a batch job, a gateway or another system's connector, does not move with the data: V10 reads no 9.x command record, and 9.x reads none of V10's. The producer changes with its service, in the same window, in four things:

- **The topic.** 9.x had a command topic per aggregate, `wow.<context>.<aggregate>.command`. V10 has one per context, `wow.<context>.commands`, after the topic prefix when `wow.kafka.topics.prefix` is set, with exactly as many partitions as the context's log. The service reads it when it takes other contexts' commands (`wow.kafka.command-intake.enabled: true`) or routes an aggregate type through it ([Kafka](../operate/kafka#durable-command-intake)).
- **The record.** A record is a Kafka envelope of kind `command`, `{format, kind, id, type, payload, transport}`, whose `payload` is the command's wire form, `CommandWire`: the command type, id and request id, the aggregate reference, the body, the expected version, the scope, operator, headers and causation. [Commands on a topic](../reference/protocols#commands-on-a-topic) lists the fields and shows a record. A record in the 9.x form is not executed: it is logged as an error, and when its key names an aggregate that aggregate is parked in the failure ledger at its offset, otherwise the record is passed over.
- **The key and the partition.** The key is the aggregate reference as text, and the record goes to the partition the log's partitioner gives that aggregate, not to the one Kafka's default partitioner picks: the node that owns the partition runs the command, so a record in another partition reaches a node that does not own its aggregate. [Commands on a topic](../reference/protocols#commands-on-a-topic) gives the hash.
- **No admission.** The intake executes what the record says. Its operator, tenant and scope are the producer's claims, and no authentication or route check runs; only the command's validation does. Whoever can write the topic can name any operator, so restrict it ([Restrict the command topics](../operate/security#restrict-the-command-topics)).
- **Access policies.** A record of an aggregate with an access policy names its caller (`caller`: the subject and the claims the policy compares), and is held to the policy as that caller; one that names none, and no `causation`, is refused. A `causation` asserts a command the server derived from a commit, which is trusted without a caller, so a producer that is not a saga writes none. A 9.x producer named no caller: give its records the caller it acts for, or send through the command route, which resolves the caller itself. The claims travel in the record, and the topic's ACLs are the only boundary.

Prefer leaving the topic. A producer that only needs to send commands can call the command route over HTTP, which authenticates and admits it ([HTTP and TypeScript Clients](./http-and-clients#routes)), or, from a JVM application that is not a Wow service, `WowClient` ([Calling Other Services](../guide/calling-services#outside-a-wow-host)). Keep a Kafka producer for one that must write to a topic, and have it write the V10 record.

In the switch the producers are traffic. They stop with the 9.x ingress in the first step, stay stopped through the import, the gate and the probes, and restart on V10's form with the traffic switch, step 8 of either path (online, after step 7 has activated every node). Never earlier against a V10 node: a command it executes is V10's first write, after which the way back is the export. A producer that cannot change by then stays stopped, since its 9.x records reach no V10 service and nothing translates them. An estate's producers move with the estate ([One window](#one-window)).

## Roll back

Two paths, never confused:

- **V10 has not written yet:** stopped at the gate, still on standby, or switched back before the first V10 commit. Stop V10, switch traffic back, and restart 9.x on its untouched stores. Nothing to reconcile.
- **V10 has written.** 9.x does not have V10's streams, so restarting it alone loses them. Stop V10's writes, keep 9.x stopped, and run `migrate-data export --dry-run`. Per context it prints a verdict: switch back, export N streams, the window closed at a given time, or 9.x was written after the cut-over, which a person resolves first. Then `export` writes the streams V10 added back to the 9.x store by the map read backwards, resumable and idempotent, checks that every 9.x aggregate holds as many streams as V10 holds of its type, and 9.x starts again. In the rehearsal, 16,914 order and 16,815 view commands written by V10 were exported and checked in 52.9 s.

Only event streams go back: reservations V10 made, entries it parked and its projections' views have no 9.x form, and are reconciled by hand. The external command producers go back with the traffic, to the 9.x topics and the 9.x record: keep each one's 9.x build or configuration until the cut-over is final. 9.x's snapshots show the state before the exported streams until 9.x writes the instance again or regenerates them. Redeploying 9.x is not a rollback once V10 has written.

Cutting over again after a rollback by export imports what 9.x wrote since and records the cut-over again, past the streams V10 wrote in its first window. A stream of that window the transport sink had not published before V10 stopped then goes out marked imported, and peers' sagas pass over it. Before stopping V10 for the rollback, let every subscription's backlog reach zero (`wow.subscription.backlog`).

### Roll back the estate

The estate rolls back as a whole or not at all: a service back on 9.x alone has lost its links with the others, still on V10.

- **No V10 service has written yet:** stop every V10 service, switch the traffic back, restart every 9.x service on its untouched stores.
- **A V10 service has written.** Stop ingress to every V10 service, let the estate drain until every subscription's backlog is zero (`wow.subscription.backlog`), and stop every V10 node. Run `export --dry-run` on every service's log store. The estate rolls back by export only when every verdict allows it: the first service whose window closes closes the estate's, so the estate's [point of no easy return](#the-point-of-no-easy-return) is the earliest of its services'. Then `export` every service, and start every 9.x service.

9.x does not publish the exported streams, so no 9.x consumer sees what V10 wrote in the window. What V10's sagas did in answer is already in the exported streams of the services they wrote to, but the read models of 9.x projections miss those streams: reconcile them by hand, with the rest that does not go back.

## A service with no links

A 9.x service that consumes no other service's events, sends no other service commands, calls no other service's routes and shares no api jar with another cuts over on its own. Its window is the estate's with one service: the [offline switch](#the-offline-switch) below, or [online](#online) when its import does not fit.

### The offline switch

1. **Announce the window, stop 9.x's writes.** Stop ingress to the 9.x services, external command producers included, drain their command queues, and stop the compensation service with them. Check that no 9.x writer is left.
2. **Take and verify the backup** of the 9.x stores.
3. **Import:** `check`, then `import`.
4. **Gate:** `compare` shows only the signed-off kinds of difference, and no unexplained unresolved key or execution. Otherwise stop here and restart 9.x: V10 has written nothing.
5. **Prepare the read side:** `snapshot`.
6. **Start the V10 services** and probe the critical paths: a command on a migrated aggregate, a read, a query, a subscription, the parked entries.
7. **Move the external consumers** of the 9.x bus to V10's published streams.
8. **Switch traffic to V10**, the external command producers with it ([below](#move-the-external-command-producers)), then scale it out. Keep 9.x stopped, not deleted.
9. **Observe** the agreed signals: errors and problem codes, latency, waits, subscription lag, parked aggregates and the consumers. The rollback decision is taken on them.

## Report

Record offline or online and why; the estate, its graph with its cycles, the links that tie its services and its stop instant; the order its modules were prepared in; the inventory; the rehearsal's evidence, each command with its exit code and counts; the signed-off kinds of difference; the types with no way back and the decision taken on them; the window's timings; and the switch's steps as done. Data imported, V10 serving, consumers moved and rollback rehearsed are four separate facts: none implies the next.
