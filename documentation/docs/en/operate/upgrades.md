---
title: Upgrade a Deployment
description: Upgrade a service with a rolling update, what may run side by side, how formats, state revisions, events and the Manifest keep an upgrade safe, and what a major version changes.
outline: deep
---

# Upgrade a Deployment

A service is upgraded by a rolling update: new nodes start beside the old ones, the partitions move to them, and the old ones stop. For a while, two versions run at once, against the same database and the same topics. This page explains what makes that safe, for an upgrade of Wow and for an upgrade of your own code.

## Two versions side by side

Within one major version, adjacent minor versions of Wow run side by side in one service and across services. Their internal protocols, the cluster channel, the wait signals, the wire forms, and their stored formats stay compatible:

- **Stored formats** are versioned per structure, not per row: each log, snapshot and timer table records its format in `wow_format`. Within a major version the format does not change, and readers ignore what they do not know. A column or a field added within a format is added in place where a store does that today: ClickHouse's `_state` ([When the state schema changes](./clickhouse#when-the-state-schema-changes)) and the Elasticsearch mappings ([A field mapped later](./elasticsearch#field-mapped-later)).
- **Wire forms** (the Kafka envelope, commands, receipts, wait signals) carry their format too. A new optional field is read by one minor version before the next one writes it.
- **A new value of an enumeration**, such as a result's status, a hop's stage or a Manifest's `creation`, follows the same order: readers first, then writers. A reader older than its writer reads a value it does not know leniently: Wow's clients and nodes read an unknown status, stage or function kind as `UNKNOWN`, and a Manifest reader leaves out what an unknown value belongs to (a command of an unknown `creation`, an aggregate of an unknown `ownership`, a subscription of an unknown kind or start), reads an error's unknown `retry` as none, and logs it. A server still refuses an unknown value in a request.
- **A Manifest that changes what a part of it means** lists a marker in `requires`. A reader that does not understand one refuses the Manifest instead of misreading it.
- **An incompatible change** waits for the next major version, whose migration handles it.

So upgrade one minor version at a time, and let each rollout finish before the next.

## Your code: events

An event's class may change between two versions of your service, and both versions read the log during the rollout:

- A new optional field with a default needs nothing.
- Any other change raises the event's revision, with an upgrade function from the old one ([Evolve Events](../guide/event-evolution)). Deploy the reading side first: once every node can read revision `n + 1`, deploy the code that writes it. Otherwise an old node may read a record it has no upgrade for.
- A consumer in another service reads the new revision as a `PublishedEvent` until its api is upgraded, and is not called for it in a handler of the event's class ([Publish and Consume Streams](../guide/published-streams#events-the-subscriber-has-no-class-for)).

## Your code: state

A change of the state's structure raises `@AggregateRoot(stateRevision = n)`. During the rollout, the new nodes claim the snapshot table at the new revision and rebuild it in place, while the old nodes keep writing at the old one; the store keeps an old node from overwriting a rebuilt snapshot. A rollback lowers the claim again only once no node of the higher revision is left ([Rebuild and Replay](./replay#snapshots-raise-the-state-revision)).

The warehouse's flat view follows the same rule: a writer replaces it only with a newer definition, so an old node that restarts does not undo a new one's view ([ClickHouse](./clickhouse#schema-management)).

## Your code: the contract

The commands, events, state and errors a service exposes are its contract with its clients and peers. The Gradle plugin compares each build's Manifest with the committed baseline and fails a change that breaks a client: a removed command or event, a new required field, a field removed or retyped. Record an intended change with `updateWowManifestBaseline` and review it with the code ([Test a Service](../guide/testing-services#check-the-contract)). Within the baseline's compatibility line, the service's major version, the update refuses a breaking change: it needs the service's next major version, which records the break and the new line, or a new command beside the old one.
or a new command beside the old one.

**The order of a rollout that adds a property to a command.** A service refuses a body that gives a property its command does not have (`unknown-property`), so its nodes are upgraded before its clients send the new property, and a service that calls another is upgraded after the one it calls. Between the nodes of one service the property is ignored: a node of the version before reads a newer node's forward or logged command, its saga's derived command among them, without it.

## Subscriptions during a rollout

A partition moves to a new node with its checkpoints, and the new node's subscriptions go on from there. A handler added in the new version starts by its kind ([Subscriptions](../concepts/subscriptions#replay-and-backfill)): a projection at the beginning; a reactor or a saga of its own context's log at the end; a reactor's or a saga's handler of another context's stream where its service joins it, at the beginning when no other subscription of the service has read it, else at the end. `@OnEvent(start = ...)` or `wow.subscriptions.[<context>.<processor.function>].start` overrides it.

A subscription's name is its identity, so check the renames before the rollout:

- **A renamed processor or handler is a new subscription.** It starts by the same rule, as if new: a saga or a reactor at the end, skipping whatever its old name had not handled yet, a projection at the beginning, which rebuilds its read model. Its old name's checkpoint and parked entries stay behind, unread.
- **To keep its position, pin the old name** with `@Name` on the renamed class or function.
- **The new nodes warn** at their first start of the checkpoints under names no subscription has any more; `GET /wow/ops/subscriptions` lists them ([The Ops Service and Console](./ops-console#rename-and-rewind)).
- **A new name is the only rewind**: no checkpoint is ever set back. Deploying a handler under a new name with an earlier `start` is how a stream is read again.

## Calls while a node goes

A node that a rollout stops goes with notice. It hands its partitions off and drains while its internal port still answers, then closes its connections. A saga's call that reaches it meanwhile is answered, and one that comes after fails to connect and goes to the service's next node within the same attempt, at a cost of at most the connect timeout (`wow.peer.connect-timeout`, 2 seconds).

A node lost without notice, such as a pod deleted by force or a machine gone, costs more. A call sent on a connection to it gets no answer and ends at its wait and a grace after it, 12 seconds by default, then its subscription retries it. The node's address stays among the service's nodes until their list is read again, at most 30 seconds later, so a call that lands on another connection to it pays the same.

## A fixed tenant across services

A type's fixed tenant (`wow.aggregates.[<context>.<aggregate>].tenant`) is its own service's configuration. A command that goes to another service, a saga's, a timer's or a caller's relayed through the command topic, says the tenant its sender acted for (`actingTenant`, [Commands on a topic](../reference/protocols#commands-on-a-topic)), and the target's service decides its own type's tenant from it ([A fixed tenant](../guide/ownership#a-fixed-tenant)). 10.0.0-beta.12 and earlier send none, and their intakes apply no fixed tenant. So while either side of such a command still runs one of those nodes:

- **An older sender, a newer target.** The command says no acting tenant, so the tenant its ref names counts as named: one to a type the target fixes to another tenant is refused as `wow.command.malformed`. A saga's `@OnRejected` handler takes it, or the subscription parks the aggregate that derived it; nothing lands outside the fixed tenant. Retry the parked entries once the sender's nodes all run the new version: the command is derived again, with its acting tenant, and lands in the fixed tenant.
- **A newer sender, an older target.** The older intake reads no acting tenant and applies no fixed tenant: it runs the command in the tenant its ref names, where the target's other nodes do not look for it. Upgrade a service that fixes a type's tenant before the services that send commands to that type.
- **Within one service**, an older node still derives and fires its commands in the tenant of their execution, as before, until it stops.

**The ref of ACCEPTED and of COMMITTED.** A command relayed through the command topic is answered `ACCEPTED` by the node that sent it, with the ref its sender gave, in the sender's tenant. Its `COMMITTED`, which the target's intake signals, names the tenant the target decided. A client that waits only for `ACCEPTED` of a command to another service's fixed type reads the sender's tenant in it; wait for `COMMITTED` to read where the command landed.

## Sensitive values on a published stream {#sensitive-values-on-a-published-stream}

10.0.0-beta.14 and earlier publish every value as committed, `@Sensitive` ones included. A later release masks them as it writes, every `DISPLAY` value too unless the owner declares the stream revealing ([Sensitive values](../guide/published-streams#sensitive-values)). What was published before stays:

- **On the topic.** The records an older node wrote keep their values until the topic's retention removes them; the transport sink does not publish them again. To remove them sooner, start a new topic (`@AggregateRoot(topic)`, a breaking change of the contract) and delete the old one once its consumers read the new. The new topic carries only what is published from then on, so a projection rebuilt from its beginning starts from that point.
- **During the rollout.** An older node of the owner publishes raw values until it stops, so a consumer may receive raw and masked records side by side meanwhile.
- **A consumer that needs `DISPLAY` values raw** receives them masked once the owner upgrades, unless the owner declares the stream revealing. Declare it in the owner's release that masks them, so its consumers never see the masks.

## Longer aggregate ids {#longer-aggregate-ids}

10.0.0-beta.16 and earlier refuse an aggregate id longer than 256 characters percent-encoded, which is 28 characters of Chinese, Japanese or Korean. A later release takes up to 512 bytes in UTF-8, which is 170 of them ([Aggregate ids](../reference/http#aggregate-ids)). An id is longer than before only where both ends take it:

- **The gateway first.** CoSec 8.0.0-beta.5 and earlier refuse a path segment longer than 256 characters as received, encoded, with 400 before the service sees the request. Upgrade the gateway to CoSec 8.0.0-beta.6 or later, which counts a segment decoded, before the services.
- **Then every node.** While a service still runs older nodes, they refuse a longer id as `wow.command.malformed`, and so does an older peer that relays a call. A creation that a newer node took may be followed by a command that an older node refuses. A saga's command to an older target is refused the same way. Let no client or saga create an aggregate with a longer id until every node of the service, and of each service that sends commands to it, runs the new version.
- **Routes.** The build refuses a Manifest whose route by id takes more than 512 characters besides the id, or has a segment of more than 256 characters, naming the route and the name to shorten.
- **Elasticsearch.** A document's id is the tenant and the aggregate id. It stays as it was while it is at most 512 bytes in UTF-8, so indices need no rebuild. A longer one, a long id behind a long tenant, which an earlier release failed to write, is its prefix and the SHA-256 of the whole.

## Settings that must not change

`wow.log.partitions` is fixed once the log holds entries, and the nodes of one service share their store layout ([Configure a Service](./configuration#what-must-match-across-nodes)).

## Jackson {#jackson}

Wow's JSON runs on Jackson 3.1 and 3.2, the two minors its tests run on. 3.1 is the one Spring Boot 4.2 manages, which a Maven build under Boot's parent and a Gradle build with Spring's dependency-management plugin resolve. 3.2 is the one a Gradle build that imports Boot's BOM with `platform(...)` resolves where springdoc is on the class path, since swagger-core 3 asks for it. On either, with Jackson's modules at one version, there is nothing to do.

On another Jackson, Wow says what to change before the first read:

- **At build time.** `checkWowJackson`, part of `check`, fails when `jackson-core`, `jackson-databind` or `jackson-module-kotlin` resolve at a minor Wow's JSON was not verified on, or at two minors, naming the versions, what asked for them and the change to make. It checks a domain module's test class path (`me.ahoo.wow`) and a service's runtime and test class paths (`me.ahoo.wow.service`).
- **At start.** The Spring Boot host does not start on such a Jackson, and names the versions it found and the change to make. This covers a Maven build and a build without Wow's Gradle plugin. The host reads each module's version from the Maven metadata its jar carries (`META-INF/maven/.../pom.properties`), so a jar repackaged without it, a shaded or stripped jar, is not seen: such a build relies on the build-time check, or on its own.

A Jackson module only the application uses, a data format or a datatype, is the application's to keep in line: Wow checks the modules its own JSON runs on.

To keep one Jackson, Spring Boot's, on every class path of a Gradle 9 build, put Jackson's BOM in a dependency scope that every resolvable configuration extends, at the version Boot's BOM gives, read from it rather than copied:

```kotlin
// One Jackson, Spring Boot's, on every class path this build resolves. A dependency scope: nothing is published.
val bootBom = "org.springframework.boot:spring-boot-dependencies:4.2.0"
val jacksonPlatform = configurations.dependencyScope("jacksonPlatform") {
    withDependencies {
        val databind = "tools.jackson.core:jackson-databind"
        val bootJackson = project.configurations
            .detachedConfiguration(project.dependencies.platform(bootBom), project.dependencies.create(databind))
            .incoming.resolutionResult.allComponents.mapNotNull { it.moduleVersion }
            .single { "${it.group}:${it.name}" == databind }.version
        add(project.dependencies.enforcedPlatform("tools.jackson:jackson-bom:$bootJackson"))
    }
}
configurations.matching { it.isCanBeResolved && !it.isCanBeConsumed && it.name != jacksonPlatform.name }
    .configureEach { extendsFrom(jacksonPlatform.get()) }
```

A dependency scope is never published, so the module's POM and Gradle metadata name no enforced platform. The version moves with `bootBom`, so the update that moves Spring Boot moves Jackson with it. Declared on `implementation` instead, the enforced platform would be published, and Gradle refuses to write a publication's metadata that holds one; declared on `testRuntimeClasspath`, it is refused at once, since no dependency is declared on a resolvable configuration.

## A major version

A new major version may change formats and protocols. Its nodes do not run beside the previous major's: it comes with a migration, run before or during a cut-over, as V10 does from 9.x ([Migrating from 9.x](../migrate/)).

## From a 10.0.0 pre-release

A 10.0.0 pre-release (`alpha`, `beta`, `rc`) makes no compatibility promise for what it stores or for its API. Its stores are not carried over: start this version on empty stores. A MongoDB log a pre-release wrote has no tenant-scoped access keys, so an equality on the owner or the space finds none of its streams until a stamp writes their attributes again. A service coming from 9.x follows [Migrating from 9.x](../migrate/).

Where a node's ports listen, and when it runs for development, follow what you wrote rather than the machine ([Configure a Service](./configuration#development-and-production)):

- **In a development profile** (`wow.dev-profiles`) the internal port binds `127.0.0.1` unless `wow.internal.host` or `wow.node.address` names an address. The public port is unchanged: it listens where `server.address` says, and a node that takes its callers from request headers names that address at startup when it is not the loopback interface. A profile that bound the internal port to the loopback interface where Spring Boot detects no cloud platform (`spring.config.activate.on-cloud-platform: none` with `wow.internal.host: 127.0.0.1`) can go. A development node that must be reached from another machine names its addresses.
- **Outside a development profile** the internal port binds, as before, the address this host's name resolves to, but a node refuses to start when that is a public address: the port takes no credential. Name the private address with `wow.internal.host`; an address you name is taken as written.
- **A host name that resolves to a loopback address** (`127.0.1.1` in Debian's `/etc/hosts`) no longer makes a node a development one. Such a node without a development profile is a deployment: without a durable backend, or without a `CallerResolver`, it refuses to start where it used to run in memory and serve the anonymous caller. Name `wow.internal.host`, and give it its backend and resolver, or activate a development profile on purpose.

Wow's operational commands, a scheduled job's, the snapshot rebuild and an operator's actions on parked entries with their read, are allowed on the public port and the operator's routes only when an authorizer decides them ([Authorize commands](./security#authorize-commands)). An application whose own `CommandAuthorizer` allowed them by having no objection, or ruled on them inside a `when` on `action.isOperational`, now has them refused (403 `wow.auth.forbidden`), and the host says so at startup. Move that rule into a bean of its own, `CommandAuthorizer.operational { caller, action -> … }`, which is asked about them alone; an authorizer class that rules on them overrides `decidesOperational` to `true`. A service whose operators CoSec decides (`wow.cosec.authorizer.enabled`) changes nothing. A caller's roles are `caller.roles` and `caller.hasRole(…)`: `DevelopmentHeaders.ROLES_ATTRIBUTE` and `CoSecCallerResolver.ROLES` are gone, replaced by `Caller.ROLES`, and a test caller takes them as `Caller.of(…, roles = listOf(…))`. `CommandGateway.authorizes` is `decidesOperational`.

A service identity is taken on the internal port alone. An application whose own `CallerResolver` returned one (`Caller.ofService`, or `service = true`) for a request to the public port has that request refused (403 `wow.auth.forbidden`) on the public routes, the operator's routes and the subscription routes, where it used to be trusted as a service: by access policies, by the operational authorizer, by a revealed read. Return an ordinary caller with its roles instead (`Caller.of(subject = …, roles = listOf(…))`); a service that calls another reaches its internal port, where it names itself ([Close the internal port](./security#close-the-internal-port)).

A pre-release that listed its snapshot stores changes its configuration before this version starts. The keys `wow.snapshot.stores`, `wow.snapshot.primary`, and an aggregate's `snapshot.stores` and `snapshot.primary` are gone, and each refuses the start, naming itself. Name each type's one store with `wow.snapshot.store` or the aggregate's `snapshot.store`. A deployment that kept a search copy, `snapshot.stores: [main, search]` with `query.snapshot.store: search`, drops `snapshot.stores` and keeps `query.snapshot.store`: the copy's subscription keeps its name, `wow.snapshot:search`, so its index and its checkpoints are kept, and nothing is read again.

A pre-release refused an Elasticsearch store as an aggregate type's own snapshot store; this version takes it ([Elasticsearch](./elasticsearch#only-snapshot-store)). A deployment that keeps its snapshots on its database with a copy on Elasticsearch changes nothing. One that wants the type's state stored once names the Elasticsearch store in the type's `snapshot.store`. The store is then filled from the log, and the type answers `rebuilding` meanwhile ([Rebuild and Replay](./replay)).

This version runs springdoc-openapi 3.2.1, on swagger-core 3.0 and Jackson 3:

- **Renamed artifacts.** An application that declares swagger's artifacts itself renames them: `io.swagger.core.v3:swagger-annotations-jakarta`, `swagger-models-jakarta` and `swagger-core-jakarta` are `swagger-annotations`, `swagger-models` and `swagger-core`, version 3.0.0, which springdoc's BOM manages. Code that changed `Json.mapper()` or `Json31.mapper()` customizes swagger's mappers with `ObjectMapperFactory.addCustomizer` instead: Jackson 3's mappers are immutable.
- **The application's own schemas.** What springdoc derives from the application's classes may read otherwise. A getter that starts with an acronym keeps its case (`getETag()` is `ETag`, not `etag`); the non-null properties of a Kotlin class are listed as `required`; the parameter of a single public constructor that names no property is a `writeOnly` property. A client generated from `/v3/api-docs` sees these. The Wow routes' document is unchanged, in `/v3/api-docs` as in `GET /wow/openapi`.
- **Jackson.** swagger-core 3 asks for Jackson 3.2, above the 3.1 Spring Boot 4.2.0 manages, and a Gradle build that imports Boot's BOM with `platform(...)` resolves 3.2 where springdoc is on the class path. Wow's JSON runs on both; [Jackson](#jackson) says what the build and the start check, and how to keep one Jackson.

This version runs CoSec 8.0.0-beta.7 and CoCache 8.2.0. From 8.0.0-beta.6 there is nothing to act on; from beta.5, CoSec's [Upgrading: from 8.0.0-beta.5](https://cosec.ahoo.me/operations/upgrading-previews#from-8-0-0-beta-5) has every change; the ones a Wow deployment meets:

- **A defect of CoCache 8.1.0 on a Redis Cluster.** Wow 10.0.0-beta.13 to beta.16 run Spring Boot 4.2 and name CoCache 8.1.0. Under Spring Boot 4.2's Lettuce, CoCache 8.1.0's Redis backend (`cocache-redis`) connected to a Redis Cluster rejects the first command to each primary: `Currently not connected. Commands are rejected.` A service that shares states through `wow-cocache` ([CoCache](../guide/extensions/cocache)) on a Redis Cluster saw its first writes and reads of the cache fail. A standalone Redis and Sentinel are not affected. CoCache 8.2.0 connects to every primary first: `wow-cocache` now names it, and an application that declares CoCache's starter and `cocache-redis` itself moves them to 8.2.0 too, where the fix is. With 8.2.0, an entry that decodes to `null` fails its read where it read as absent; Wow writes none.
- **Seventeen `cosec.*` keys are gone**: the stages' timeouts, now shares of `cosec.pipeline.timeout`, and the bounds of caches, queues and snapshots, now shares of the heap. A service or gateway that sets one does not start, naming it and what replaced it. Wow sets none of them; remove any your configuration sets.
- **The forwarded-header strategy is `server.request.header.forwarded.strategy`**, Spring Boot 4.2's key, where `native` is `web-server`. The host sets it to `web-server` unless the application sets it or the deprecated `server.forward-headers-strategy`; Spring Boot does not start with both ([Record the client address](./security#record-the-client-address)). An application that set the deprecated key keeps its value, and the host's startup line names the key to move to.
- **The CoSec deployment in front.** A Wow service in gateway mode holds no documents, no Redis and no control plane, so it changes nothing beyond the version. The gateway and a control plane follow CoSec's page: upgrade every control-plane instance first, then the gateways, whose status records now come in two parts; a data-plane Redis user needs the new resident keys; a gateway keeps as many documents as its heap allows ([Configure the CoSec Gateway](./security#configure-the-cosec-gateway)).
- **Tests of a service in gateway mode.** A test that starts one states the boundary with `IdentityHeader.ONLY_GATEWAY_CAN_REACH` of `cosec-testkit` (`@SpringBootTest(properties = [IdentityHeader.ONLY_GATEWAY_CAN_REACH])`), in place of a test configuration of its own. A launcher that takes a property map, such as `wow-test-spring-boot`'s `wowServer`, takes its key alone, from CoSec 8.0.0-beta.7: `mapOf(IdentityHeader.ONLY_GATEWAY_CAN_REACH_KEY to "true")`.

The failure ledger's parked entries are named and reached in bounded form, so an entry of any aggregate that may exist is recorded by the ops service and reached by an operator through a gateway ([Ops Console](./ops-console)):

- **The ops service's `parked` aggregate is named by a hash.** An entry's id is `p` and 32 hexadecimal digits, the first 128 bits of the SHA-256 of its service, subscription and aggregate, where it joined them. The service, subscription, tenant and id are the entry's fields, as before. The ops service records an entry only under the id its fields hash to. Start the ops service of this version on an empty store, as every store of a pre-release: entries recorded under the earlier ids would stay unresolved, the services' later records going to the new ones. The services' own ledgers keep their entries, and their next scan publishes them again.
- **A service's operator routes take the entry in the body.** Each is a `POST` under `/wow/ops/parked` whose path holds no data: `list` (`subscription`, `after`, `limit` in the body), `entry`, `retry`, `skip`, `mark-recoverable` and `apply-retry-spec`, the entry named by `subscription`, `context`, `aggregate`, `tenantId` and `id`. A script, a webhook receiver or a gateway policy that built `GET /wow/ops/parked?…`, `GET /wow/ops/parked/{subscription}/{context}/{aggregate}/{tenant}/{id}` or its `POST …/retry` paths moves to these.
- **`Ledger.parkedId` answers the hash**, with the same signature; code that parsed its earlier form reads the entry's fields instead.

This version can write CoSec's refusals as Wow's problems, off by default: `wow.cosec.problems: wow` ([CoSec's refusals](./security#cosec-refusals)). Left at `cosec`, every response is byte for byte what it was. It is built on CoSec's `ProblemRenderer`, experimental in CoSec 8, so its shape may change with CoSec's release candidate. Two codes are new, `wow.request.rate-limited` (429) and `wow.auth.unavailable` (503), and only these refusals answer with them.
