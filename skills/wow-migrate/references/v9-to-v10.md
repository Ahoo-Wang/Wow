# Wow 9.x to V10 Domain Source Migration

Use this reference only when a downstream domain module written against Wow 9.x is rewritten for V10, or a 9.x platform module of the Wow repository is ported to a V10 project under `platform/`. It maps 9.x constructs to their V10 form; the before/after pairs come from the hand port of the 9.x example (`example-api`, `example-domain`) to V10 (the first V10 example, the shop, since folded into the eShop). The `main` branch (V10) holds no 9.x source: read a 9.x module of the Wow repository (the example, `view-store/`, `compensation/`, `dataset-registry/`, the `wow-*` modules) on `v9` or on the side branch `v10-9x-baseline`, the last V10 commit that had them. For a complete V10 application to compare against, read the eShop (`examples/eshop` on the `main` branch): five contexts, each an API, a domain and a server module, with their end-to-end tests. For a 9.x domain that came with a Spring Boot starter of its own routes, reserved owners and owner moves, read the V10 view store (`platform/view-store`), ported from the 9.x `view-store/` (on `main`) with this reference (roadmap 7.2a).

Status: first draft. V10 is still moving: verify every annotation, signature, route and configuration key against the pinned V10 target, not against this page. V10 ships pre-releases only (`v10.0.0-alpha.<n>` tags, without a compatibility promise): pin the exact pre-release, or a `main` commit as a source baseline, and check that every artifact the build names exists for that version. For `10.0.0-alpha.3`, `wow-bom`, `wow-kernel`, `wow-gradle-plugin` and the plugin marker of `me.ahoo.wow` are on Maven Central, so the plugin resolves with `mavenCentral()` in `pluginManagement.repositories`; whether the Gradle Plugin Portal serves it was not confirmed. Record what you could not resolve as `MISSING EVIDENCE`.

## Preconditions and gates

- Upgrade the application to the last 9.x minor first. V10 migrates from that minor only; the migration layer lives through 10.x and leaves in 11.0.
- V10 needs JDK 21, Kotlin with KSP, and Jackson 3 (`tools.jackson`) in domain code that touches JSON.
- V10 builds with **Gradle only** (decision D239 ②): models and the Manifest are bound at build time by KSP and the Gradle plugin's Manifest tasks, which have no Maven counterpart. A 9.x service built with Maven moves its build to Gradle as part of the migration, or stays on 9.x; say which before any source rewrite. A Java domain is supported with limits ([Java domains](#java-domains)).
- Source migration is a semantic rewrite done here; stored history is converted by the data tool `wow-migrate-data`. Never hand-edit stored events to fit the new source.
- Accept the source when the domain compiles under the V10 compiler and its domain tests pass. Accept the data only when every aggregate rebuilt by V10 matches its 9.x snapshot or each difference class is explained and signed off (`references/cutover-evidence.md`).
- Each rewrite that changes an event's or command's name, context, revision or shape becomes an entry of `migration-map.json`, which the data tool checks against the event types in the 9.x store and the V10 Manifest before it imports anything; a 9.x event or command the store holds without an entry stops the import. Format 1 (`migration/migrate-data`, `MigrationMap`): `{"format": 1, "headers": {"<9.x request header>": "<V10 name>"}, "aggregates": [{"from": "<9.x contextName.aggregateName>", "to": "<V10 context.aggregate>", "commands": [{"from": "create_order", "to": "CreateOrder"}], "events": [{"from": "order_created", "fromRevision": "0.0.1", "to": "OrderCreated", "toRevision": 1, "upgrade": "upgradeOrderCreatedFrom1"}, {"from": "order_archived", "to": "OrderArchived", "retire": true}, {"from": "order_noted", "drop": true}]}]}`. A 9.x event the V10 aggregate no longer has (the 9.x `DroppedEvent`) is mapped with `"retire": true` to a name the aggregate lists in `@AggregateRoot(retiredEvents)` (see [Retired events](#retired-events-9x-droppedevent)): the record is imported under that name and skipped when V10 loads it, so its stream keeps its version, and the check fails when the name is not retired. `"drop": true` leaves the event out of its stream; the check refuses a map that would leave a stream with no event (a command whose only event is dropped), so prefer `retire` for deleted events. A V10 name without a dot is in the target aggregate's namespace; a system name (`wow.lifecycle.OwnerTransferred`) is written whole, with `"fields": {"toOwnerId": "ownerId"}` to rename a 9.x lifecycle event's fields. A 9.x domain event that implements a lifecycle event (`UserDeleted : AggregateDeleted`, `ViewClaimed : OwnerTransferred`, `AggregateRecovered`, `SpaceTransferred`, `ResourceTagsApplied`) maps to its domain event with a `lifecycle` entry, the system event the import writes right after it in the same stream: `{"from": "user_deleted", "to": "UserDeleted", "lifecycle": {"to": "wow.lifecycle.AggregateDeleted"}}`, `{"from": "view_claimed", "to": "ViewClaimed", "lifecycle": {"to": "wow.lifecycle.OwnerTransferred", "fields": {"toOwnerId": "ownerId"}}}`; `to` is one of `AggregateDeleted`, `AggregateRestored` (9.x `AggregateRecovered`), `OwnerTransferred`, `SpaceTransferred`, `TagsApplied` (9.x `ResourceTagsApplied`), and `fields` renames the 9.x fields its payload takes (`ownerId`, `spaceId`, `tags`), each of which the 9.x payload must hold. The imported stream records the owner or space the written event moved it to. `toRevision` is the revision the stored 9.x shape has in V10 (1 unless the 9.x store holds a later 9.x shape); `upgrade` names the V10 upgrade function and is recorded, not run. Map every 9.x command the streams name (their `upstream_name`), not only renamed ones, and every event and command the 9.x code can produce, not only those the store holds when the check runs: 9.x keeps writing until the cut-over, and an event it first stores then (a 9.x `OrderPayDuplicated` of a payment made twice, say) stops `tail` and the final `import`. When a rewrite renames a state property, add `"state": {"fields": {"<9.x name>": "<V10 name>"}}` to the aggregate's entry; list a property only one side has by design in `"state": {"ignore": ["<V10 name or dotted path>"]}` and report it as an explained difference. The data tool's `compare` reads them when it compares each 9.x snapshot with the rebuilt V10 state. Every 9.x `PrepareKey` the store holds needs an entry under the aggregate whose instances own its keys, `"reservations": [{"from": "<9.x PrepareKey name>", "namespace": "<V10 namespace>", "tenancy": "TENANT" | "SYSTEM", "ownerId": "<dotted path of the owner's id in the 9.x value>"}]`, with the namespace and tenancy the V10 decide function reserves the key with (see [Reserved keys](#reserved-keys-9x-preparekey)); the import reserves each confirmed 9.x key for its owner in V10, and a `PrepareKey` without an entry fails the check.
- The rest of format 1 (`MigrationMap.kt` of `wow-migrate-data`; the tool refuses a property the format does not define):
  - `"defaultCommand": "<V10 command>"` on an aggregate entry names the command of a 9.x stream that records no command name; without it such a stream fails the check.
  - `"headers"` keeps 9.x stream headers as V10 request facts, every other header is dropped, and a 9.x framework header cannot be listed. The application id 9.x CoSec recorded (`app_id`) is the V10 standard fact of the same name (`RequestFacts.APP_ID`), so keep it as `"headers": {"app_id": "app_id"}` when the domain or its clients read it from history; 9.x `device_id` is V10's CoSec fact `CoSec-Device-Id`.
  - `"subscriptions"`, at the top level beside `"aggregates"`: the 9.x processors whose failed executions the 9.x compensation service still holds, `[{"from": "<9.x contextName.processorName>", "to": "<V10 processor>", "functions": {"<9.x function>": "<V10 function or processor.function>"}}]`. Each unresolved execution of a mapped processor is imported as a parked entry of the V10 subscription `<to>.<function>` (`to` defaults to the 9.x processor name, a function to its own name, and a value with a dot is the whole subscription name). The check fails when that subscription is not in the Manifest, when the map does not import the aggregate the execution is about, or when the subscription reads another context's published stream, which parks by topic offset. The import runs only when the tool's configuration names the compensation database (`"compensation": {"database": "compensation_db", "service": "<V10 service>"}`). See [Compensation and failed executions](#compensation-and-failed-executions).
- `wow-migrate-data` reads a 9.x **MongoDB** event store only, and the 9.x `PrepareKey`s kept in that MongoDB (`prepare_<name>` collections). History or `PrepareKey`s kept in a 9.x **Redis** store (`wow-redis`) cannot be imported yet: report it as `MISSING EVIDENCE` for the data migration and do not plan a cut-over on it. A cut-over without the reservations imported leaves every key 9.x holds free in V10, so uniqueness silently breaks. The tool writes nothing to the 9.x store unless told to; its tail needs a `(createTime, _id)` index on each `<aggregate>_event_stream` collection, which the operator creates (or lets the tool create with `source.createCommitTimeIndex`).
- The import writes each stream without the aggregate's tags (decision D259), so ABAC on the `events` model matches none of them until `migrate-data backfill-tags --config <file>` has run: it replays every aggregate of the configured contexts and records the tags after each stream, resumable and idempotent, then, with an `elasticsearch` section in the configuration, overwrites the events index documents indexed without tags from the log. Run it after the import and before ABAC-restricted event timelines are used; it refuses the re-sink while a log still holds streams without tags, unless `--force`.
- Rolling back after the cut-over (decision D32): before V10 has written, switch traffic back to 9.x. Once V10 has written, stop V10, keep 9.x stopped and run `migrate-data export --config <file>` (`--dry-run` first): it writes back to the 9.x store the streams V10 wrote, by the map read backwards, resumable and idempotent, then checks that each 9.x aggregate holds as many streams as V10 holds of its type. It refuses, writing nothing, when a stream 9.x lacks holds a V10-only type (an aggregate no 9.x aggregate maps to, or an event at a revision or name the map does not reach), and names each type; report such types to the user as the point of no easy return before the cut-over. Only event streams go back: reservations, parked entries and views V10 made after the cut-over do not.
- When a rewrite is uncertain, ask the user, or leave `TODO(wow-migrate)` in code that does not compile. Never guess a semantic rewrite into code that compiles.

## Build and dependencies

| 9.x | V10 |
|---|---|
| `wow-spring-boot-starter` with capability variants (`mongo-support`, `kafka-support`, ...) | `wow-spring-boot` (wiring only) plus one module per technology the host uses: `wow-postgres`, `wow-mongo`, `wow-kafka`, `wow-http`, `wow-cosec` |
| `wow-core`, `wow-spring`, `wow-webflux`, `wow-rest-contract`, `wow-mock`, `wow-metadata`, `wow-schema` | Replaced: `wow-kernel` and `wow-runtime` (core), `wow-http` (routes, problem+json, SSE), `wow-manifest` (contract) |
| `wow-redis`, `wow-bi` | Removed (no Redis backend; ClickHouse sink replaces BI sync) |
| `wow-apiclient` | Removed (decision D187); see [Clients of other services](#clients-of-other-services) |
| `wow-cocache` | `wow-cocache` of the V10 version in a new shape (decision D364): the owner writes, readers inject `StateViewCache<V>`; see [State caches](#state-caches-9x-wow-cocache) |
| `wow-compensation-*` (the 9.x compensation service) | Retired: each node's failure ledger and its operator routes, see [Compensation and failed executions](#compensation-and-failed-executions) |
| `wow-opentelemetry` (the per-component instrumenters, the `wow.operation` meters) | `wow-observability`, settings under `wow.observability.*`: every metric and span is renamed, with no alias, so rebuild dashboards and alerts from the docs page Migrate › Metrics and Spans (`/migrate/metrics`) |
| `wow-query` (protocol, DSL and engine) | `wow-query-api` (query types, the Kotlin DSL `me.ahoo.wow.api.query.dsl`, the field annotations) for domain and client code; `wow-query` (the engine, `QueryGateway`), which `wow-http` brings to a server; see [Queries](#queries) |
| `wow-bom` 9.x | `wow-bom` of the V10 version, which constrains the V10 modules only |
| `wow-models` (`EnumText`, `StatusCapable`, the `*Capable` interfaces, the `Tree` aggregate) | `wow-models` of the V10 version (the project `:models`, `extensions/models`, decision D187): the same packages (`me.ahoo.wow.models.common`, `me.ahoo.wow.models.tree`), so imports stay; a `Tree` subclass binds through the compiler (annotate its concrete create command `@AllowCreate`) |
| `ksp("me.ahoo.wow:wow-compiler")` by hand | Gradle plugins by role, of one version, each adding `platform("me.ahoo.wow:wow-bom:<its version>")`: `id("me.ahoo.wow")` on the domain module (KSP with `wow-compiler`, `wow-kernel`, `generateWowManifestFragment`, the `integrationTest` suite), `id("me.ahoo.wow.api")` on the api module, which declares `wowContract(project(":<x>-domain"))`, and `id("me.ahoo.wow.service")` on the server (`mergeWowManifest`, `checkWowManifest`); a single-module service applies `me.ahoo.wow.service` alone |
| `wow-test` (`AggregateSpec<A, S>`) | The functional `aggregateSpec` and `sagaSpec` DSL (`me.ahoo.wow.test`), still the artifact `wow-test` (the project `:test-dsl` in the Wow repository): `testImplementation("me.ahoo.wow:wow-test:<version>")` |
| In-memory stores and buses (`InMemoryEventStore`, `InMemorySnapshotStore`, ..., `wow-mock`) | No public in-memory store class: domain tests use `wow-test` (`aggregateSpec`, `sagaSpec` run on in-memory stores), a host uses `wow.backend: memory`, and a test without Kafka gives an `InMemoryTopics` bean (`me.ahoo.wow.kernel.memory`). The kernel's other `InMemory*` stores are `@InternalWowApi` |
| Domain handlers returning `Mono`/`Flux` | `suspend`, or keep Reactor through `wow-reactor` (compile error without it) |

A target pinned to an earlier V10 pre-release renames its observability names as well:

- The span attributes and metric tags `wow.aggregate`, `wow.command` and `wow.version` are `wow.aggregate.name`, `wow.command.name` and `wow.aggregate.version`. `wow.aggregate.name` is 9.x's attribute again.
- A dataset's query is tagged `wow.dataset` and timed in `wow.query.dataset.duration`.
- Every tag value is lower case: `wow.query.type` (`list`, not `LIST`).
- A timer's recorded outcome is lower case too, and one committed before is `duplicate` (it was `ALREADY_COMMITTED`). Timers recorded by a pre-release are not carried.
- An application's `QueryObserver.start` takes the query's `QueryModel`, and an `Observation` is told an `ObservedOutcome`, not a string.
- A subscription's meters are tagged `wow.group` and `wow.subscription`: `wow.subscription.lag` (it was `wow.context`), `wow.subscription.parked.aggregates` and `.parked.notices`. Each context's `snapshot` subscription is its own series.
- `wow.kafka.offset.gaps` is `wow.subscription.offset.gaps`, tagged `messaging.destination.name`, and is counted only with `wow-observability`. `PartitionedSource.offsetGaps` is gone: a transport tells `TransportConfig.observer` of each gap (`ObservedEvent.OffsetGap`).
- The admission span is INTERNAL, the child of the request's server span when one is current. The topic spans are `send <topic>` and `process <topic>`, with `messaging.system` and `messaging.operation.name`.
- `Observer.handling` takes the subscription's group first.
- A rejection and a refusal are logged under the logger `wow.command.rejection`, with SLF4J key-values, instead of under the kernel's and the gateway's own loggers.


Artifact ids keep the `wow-` prefix (`me.ahoo.wow:wow-kernel`); V10 Gradle project names in the Wow repository do not. No module may depend on both a 9.x and a V10 module.

Before, the 9.x example domain:

```kotlin
plugins {
    alias(libs.plugins.ksp)
}
dependencies {
    api(project(":example-api"))
    api(project(":wow-spring"))
    ksp(project(":wow-compiler"))
    testImplementation(project(":wow-test"))
}
```

After, a downstream V10 domain module:

```kotlin
plugins {
    kotlin("jvm")
    // Brings wow-kernel and the BOM of its version.
    id("me.ahoo.wow") version "<pinned V10 version>"
}
dependencies {
    api(project(":ordering-api"))
}
```

and its api module, which declares the domain module whose contract it carries:

```kotlin
plugins {
    kotlin("jvm")
    // Brings wow-api and the BOM of its version.
    id("me.ahoo.wow.api") version "<pinned V10 version>"
}
dependencies {
    wowContract(project(":ordering-domain"))
}
```

The eShop's modules apply the plugins as a downstream build does: `me.ahoo.wow` on each domain module, `me.ahoo.wow.api` on its api module, `me.ahoo.wow.service` on its server. The plugin passes the KSP options itself (`wow.model=generate`, `wow.manifest.module=<project path>`); `wow { context = "…" }` sets `wow.context`, needed only when the module has no single `@BoundedContext`.

## Annotation mapping

V10 annotations keep the package `me.ahoo.wow.api.annotation`, so kept names need no import change, but they lose every 9.x-only attribute. The V10 compiler reports any other annotation of that package as a compile error naming its replacement.

| 9.x | V10 | Rewrite |
|---|---|---|
| `@BoundedContext(name, alias, description, scopes, packageScopes, aggregates)` | `@BoundedContext(name, description)` | The aggregate list comes from `@AggregateRoot`; a fixed tenant moves to host configuration. The context name is part of every command, event and route name, so decide it explicitly (the port chose the 9.x alias `example` over `example-service`; the eShop names its contexts `eshop-<context>`) and record it in the map. |
| `@AggregateRoot(commands = [...])` | `@AggregateRoot(lifecycle = [...], published, publishState, topic, ownership, idSource, retiredEvents)` | A 9.x mounted command becomes a class annotated `@MountedCommand(aggregate = "<aggregate>", transformsTo = [...])` (decision D182): served under the aggregate's routes, decided by no aggregate, and turned into a command an aggregate decides by exactly one `CommandTransformer` of the service (the service does not start with none or two). `retiredEvents` lists deleted events (see [Retired events](#retired-events-9x-droppedevent)). `lifecycle` lists the system commands kept (all five by default). `published = false` keeps the aggregate's state events out of the context's published streams, `publishState = false` publishes them without the state, and `topic` overrides `wow.<context>.<aggregate>.state` (a Kafka topic name, at most 249 characters). |
| `@OnCommand(returns = [...])` | `@OnCommand(produces = [...])` | Needed only when the function returns `Decision`; a concrete or sealed return type is inferred. |
| `@OnSourcing`, `@CreateAggregate`, `@AllowCreate`, `@AfterCommand(include, exclude)`, `@Description`, `@Summary`, `@WowSpi` | Same | Unchanged. |
| `@Blocking` | Same | Keep it on a function that blocks its thread (JDBC, a blocking client): a command function (`@OnCommand`), an after hook, a subscription handler (`@OnEvent`, `@OnRejected`, `@OnCommitted`) or a job (decision D227 ①). Its call then runs on a virtual thread; an unmarked synchronous function runs inline as pure computation, so a blocking one left unmarked stalls the worker. It is a compile error on `@OnSourcing`, `@Upgrade` and `@Tags` functions, and a warning on a function returning a Reactor publisher. |
| `DroppedEvent` (an `EventUpgrader` returning `toDroppedEventRecord()`) | `@AggregateRoot(retiredEvents = ["<stored name>"])` | See [Retired events](#retired-events-9x-droppedevent) (decision D219). |
| `@Name` on a class | Same | Names an aggregate, processor, command or event. |
| `@Name` on a parameter | Same, as a qualifier | The binding index resolves the service by type and qualifier. |
| `@Event(revision = "0.0.1")` | `@Revision(n)` | Integer revision; a migrated 9.x shape is revision 1. |
| `EventUpgrader` (SPI class) | `@Upgrade(event = X::class, from = n)` on a function | See [Events](#events). |
| none | `@Alias("OldName")` | Former names of a command or event, for later renames. |
| none | `@ErrorCode(description, status = 422)` | On a `const val` rejection code (in Java, a `static final String` field). A code without a dot is a local name (decision D259): declare it in an `object` nested in the `@AggregateRoot` class (`object Rejections { const val WRONG_STATUS = "wrong-status" }`); the compiler, the kernel and the Manifest name it `context.aggregate.wrong-status`, and peers read it from the api's generated `<Context>Contract.<Aggregate>.Errors`. A code with a dot is full (`context.code` for one the context shares). |
| `@StatelessSaga` | `@Saga` | See [Sagas, projections, reactors](#sagas-projections-reactors). |
| `@ProjectionProcessor` | `@Projection` | Same. |
| `@EventProcessor` | `@Reactor` | Same. |
| `@OnEvent(vararg aggregates)`, `@OnStateEvent`, `@OnMessage` | `@OnEvent` | One handler kind, taking a `StateEvent`. Another context's events are taken by type (`StateTypedEvent<<State>View, E>`: the api's event class and the state view its api generates), the aggregate found from its contract; `aggregates = [<Context>Contract.<Aggregate>.QUALIFIED_NAME]` names it for a handler of the whole stream; a saga may declare `derives = [Command::class]`: command classes of its context or a peer's api, or a sealed type for its subclasses (decision D259); a returned `Command<S>` of a sealed `S` derives them without it, and the service's build fails on a class neither the service nor a peer contract decides. A handler that sends nothing returns `Unit`. |
| none | `@OnRejected` | A saga's handler for the rejection of a command it derived (`CommandRejected<C>`, or `CommandRejected<*>` for any), returning the commands that compensate, or `Unit` to accept the rejection (decision D259; an `@OnCommitted` handler likewise). Without one, a rejected derived command parks the aggregate at once; 9.x had no such outcome. |
| `@Retry(maxRetries, minBackoff, executionTimeout)` | `@Retry(maxAttempts, initialBackoff, maxBackoff, multiplier, timeout)` and `wow.subscriptions.[<context>.<name>].parked.*` | V10's `@Retry`, on a processor or its `@OnEvent` handler, is the in-process budget before the aggregate parks. The 9.x settings are the parked entry's long-horizon retries: `wow.subscriptions.[<context>.<processor.function>].parked.max-retries`, `initial-backoff` (9.x `minBackoff`) and `timeout` (9.x `executionTimeout`). |
| `@Retry(recoverable, unrecoverable)`, `RecoverableExceptionProvider`, `RecoverableException`, `RecoverableType` | `@Retry(recoverable, unrecoverable)` and `FailureClassifier` (`me.ahoo.wow.spi.subscription`) | Same attributes; the nearer listed class decides. A `RecoverableExceptionProvider` becomes a `FailureClassifier` bean returning a `FailureClass`: 9.x `UNRECOVERABLE` → `UNRECOVERABLE` (parks at once, no automatic retries), `RECOVERABLE` and `UNKNOWN` → `RECOVERABLE` (retried within the budget, then parked). A store, broker or connection that is down is `SYSTEMIC` (V10 only): the subscription pauses the partition and retries until it is back, parking nothing (`wow.subscription.systemic.*`). `@Retry(enabled = false)` has no V10 form. |
| `@OnError` | Removed | Return a rejection, with the events to record when the refusal should be kept (`reject(code, message, events = listOf(...))`); compensate in a `@Saga`'s `@OnRejected` handler. |
| `@PreparableKey` | Removed | `DecideScope.reserve(namespace, key, tenancy)`; decide the tenancy of every key. See [Reserved keys](#reserved-keys-9x-preparekey). |
| `@AggregateId`, `@TenantId`, `@OwnerId`, `@AggregateVersion` on command fields | Removed (compile error) | See [Command bodies](#command-bodies-carry-no-identity). |
| `@StaticTenantId`, `@StaticAggregateId` | Removed | Fixed tenant is host configuration. |
| `@AggregateName` | `@Name` on the aggregate root | |
| `@AggregateRoute`, `@CommandRoute` (`PathVariable`, `HeaderVariable`) | Removed | Routes come from the Manifest. |
| `@AggregateOwner(OwnerPolicy.*)`, `@Spaced` | Removed; `@AggregateRoot(ownership = Ownership.OWNER \| TENANT)` | Owner and space come from the principal. `OwnerPolicy.ALWAYS` (and no policy) is `Ownership.OWNER`, the default: an owner-level caller reads and writes only its own. `OwnerPolicy.AGGREGATE_ID`, an aggregate whose id is its owner's id (a user's cart), is `@AggregateRoot(idSource = AggregateIdSource.OWNER)` (`me.ahoo.wow.api.aggregate.AggregateIdSource`) with the default `Ownership.OWNER`. A resource 9.x kept under one reserved owner for the whole tenant (such as `(shared)`) is `Ownership.TENANT`: no owner recorded, every caller of the tenant reads it, writes guarded by the host's `CommandAuthorizer`. See [Ownership and owner moves](#ownership-and-owner-moves). |
| `@VoidCommand`, `@Order`, `@EntityObject`, `@ValueObject`, `@Generated` | Removed | No counterpart; drop them. |
| `@QueryReference`, `@QueryAlias`, `@Sensitive` (with `Mask`), `@QueryTemporal`, `@QueryDuration`, `@QueryDecimal`, `@QueryMoney` | Same names and package, `me.ahoo.wow.api.query.annotation`, in `wow-query-api` | Keep them; add `implementation("me.ahoo.wow:wow-query-api")` to the module that declares them (the kernel does not bring it). The Gradle plugin writes them into the state's schema as `x-wow-*` keywords, which the query admission, masking and descriptors read. They target fields and getters; their KDoc writes `@field:QueryAlias(...)` on Kotlin properties. |
| none | `@QueryIndex(name, on, with)` | New in V10: declares a query index on a state property, or on the state class with `on`; the backend creates it as the `ddl` of its store (`wow.ddl`: `auto`, `validate`, `none`) says. Add one where a 9.x deployment had a hand-made index on the snapshot collection, and report it. |
| `@Tag`, `@Schema(name)` (swagger) | Not read | Schemas come from Jackson introspection at build time. |

## Command bodies carry no identity

A command body holds business data only. The aggregate identity is `Command<X>.ref` (`AggregateRef(type, tenantId, id)`), taken from the route or the command message; owner and space are `Command<X>.scope`, taken from the authenticated principal. Delete identity fields from bodies and rewrite every read of them to `command.ref`. A creating command whose id the client chooses uses the route with `{id}`; without it the server generates the id.

Before:

```kotlin
@CommandRoute(action = "package", appendIdPath = CommandRoute.AppendPath.ALWAYS, method = CommandRoute.Method.PATCH)
data class ReceiptOrder(
    @CommandRoute.PathVariable
    @AggregateId
    val id: String,
)

@CommandRoute(action = "package", appendOwnerPath = CommandRoute.AppendPath.NEVER, method = CommandRoute.Method.POST)
data class ShipOrder(
    @CommandRoute.PathVariable
    val id: String
)
```

After:

```kotlin
@Summary("Receive the order")
data object ReceiptOrder

@Summary("Ship the order")
data object ShipOrder
```

Events lose identity fields the same way: the 9.x `OrderCreated.orderId` is now the stream's `ref`, which forces a revision bump and an upgrade function.

## Command functions

| 9.x | V10 |
|---|---|
| `CommandMessage<X>` | `Command<X>` |
| `command.aggregateId.id` | `command.ref.id` |
| `command.header[k]` | `command.headers[k]` |
| `command.ownerId`, `command.spaceId` | `command.scope.ownerId`, `command.scope.spaceId` |
| `ServerCommandExchange<X>` with the body unused | `X` |
| `CommandResultAccessor.setCommandResult(k, v)` | `DecideScope.putResult(k, v)`; returned with this commit only, not persisted, absent from a duplicate's answer |
| `require`/`check`/thrown exception | `Decision.reject(code, message)` with the code declared by `@ErrorCode` |
| An error event (one implementing `ErrorInfo`) returned to record a refusal, usually marked `IgnoreSourcing` | `reject(code, message, events = listOf(TheEvent(...)))`: the events are ordinary events of the aggregate, committed with the rejection, and the caller gets the rejection with the version they reached. Give each one a sourcing function, empty (`fun onSourcing(event: TheEvent) = Unit`) for one that changes no state, as `IgnoreSourcing` meant; drop `ErrorInfo` from it. The refused decision's schedules are not committed and its reservations are released. A creation cannot carry them (its aggregate does not exist): reject it without events. Audit sinks of rejections have no V10 counterpart yet; a rejection is counted by `wow.command.rejections` and logged at INFO |
| `onError(...)` | Delete when it only logs; compensation becomes a `@Saga` |
| `CommandValidator.validate()` on the body, or a `require` on the command's own fields | Jakarta constraints on the command class (`@field:Positive`, `@field:NotBlank`, `@field:Valid`), checked at admission for every command (`wow.command.invalid`, 400, with `violations`); a rule that needs the state, or one Jakarta cannot express, is a rejection in decide |
| Injected service; `@Name("x")` parameter | Parameter of the generated factory, bound once by the host; `@Name("x")` qualifies it |
| `GlobalIdGenerator.generateAsString()`, `NamedAggregate.generateId()` inside decide | `scope.ids.next()`, `scope.ids.next(type)` on the `DecideScope` (decision D171): the node's generators (UUIDv7, or CosId with `wow-cosid`), fixed ids in the test DSL. A decision decided again after a version conflict takes new ids, which is safe: the conflicting attempt appended nothing, and a retried request is answered from its first commit (`Wow-Request-Id`), not decided again. Derive an id from `command.ref` only when a client must know it before the answer. A saga takes ids from `SagaContext.ids`; the commands it derives keep their deterministic ids |
| Return `Any`, event, list, `Mono` | `Decision` (`events`, `NoOp`, `reject`), an event, a list, a sealed type, or a publisher through `wow-reactor` |

9.x framework types in a V10 signature are compile errors, so these rewrites are mandatory. A function's first parameter is the body `X` or `Command<X>`; later parameters are services or a `DecideScope`. Every non-private function is bound; private functions are ignored.

`Command<X>` is erased on the JVM: two functions `onCommand(command: Command<A>)` and `onCommand(command: Command<B>)` of one aggregate do not compile (platform declaration clash). Name each such function after its command (`onCreate`, `onSave`) and annotate it `@OnCommand`; overloads that take the bodies themselves (`onCommand(command: A)`) keep the one name.

Request facts a 9.x handler read from `CommandMessage.header`:

- **The application id** (9.x `app_id`, which CoSec's `CoSec-App-Id` request header set) is the standard request fact `app_id` (`RequestFacts.APP_ID`, decisions D232, D236): read `command.headers[RequestFacts.APP_ID]`. The edge records it from the caller its `CallerResolver` authenticated (`Caller.app`), never from a request header, so it cannot be listed in `wow.http.request-facts`. With CoSec (`wow-cosec`) it is recorded only when CoSec authorized the request by that application's roles; a service behind a gateway in CoSec's inject mode records the injected application only with `wow.cosec.trust-injected-app: true` (default false). A command a saga, an `@OnRejected` or `@OnCommitted` handler or a timer derives inherits it (`RequestFacts.PROPAGATED`), as 9.x's `CoSecMessagePropagator` propagated it. An ABAC rule or query policy that read the 9.x header reads `Caller.app` instead.
- `remote_ip` and `user_agent` are standard facts too, on the public port only, and are not propagated.
- **Any other header** reaches `command.headers` only when the host lists it in `wow.http.request-facts`, under the name listed there (CoSec's `CoSec-Device-Id` is listed for you when CoSec is wired).

A 9.x `CommandMessage.aggregateId.tenantId` is `command.ref.tenantId`; the authenticated user is `command.operator?.id` (null for an anonymous caller).

Before, `Order.onCommand(CreateOrder)` in 9.x:

```kotlin
fun onCommand(
    command: CommandMessage<CreateOrder>,
    @Name("createOrderSpec") specification: CreateOrderSpec,
    commandResultAccessor: CommandResultAccessor
): Mono<OrderCreated> {
    val createOrder = command.body
    require(createOrder.items.isNotEmpty()) { "items can not be empty." }
    return Flux.fromIterable(createOrder.items)
        .flatMap(specification::require) // throws InventoryShortageException, PriceInconsistencyException
        .then(
            OrderCreated(
                orderId = command.aggregateId.id,
                items = createOrder.items.map {
                    OrderItem(GlobalIdGenerator.generateAsString(), it.productId, it.price, it.quantity)
                },
                address = createOrder.address,
                fromCart = createOrder.fromCart,
            ).toMono().doOnNext { orderCreated ->
                commandResultAccessor.setCommandResult(OrderState::totalAmount.name, orderCreated.items.sumOf { it.totalPrice })
            }
        )
}
```

After, V10:

```kotlin
@OnCommand(produces = [OrderCreated::class])
suspend fun onCommand(
    command: Command<CreateOrder>,
    inventoryService: InventoryService,
    pricingService: PricingService,
    scope: DecideScope,
): Decision {
    val placing = command.body
    if (placing.items.isEmpty()) {
        return Decision.reject(OrderRejections.NO_ITEMS, "An order needs at least one item.")
    }
    placing.items.forEach { item ->
        val price = pricingService.getProductPrice(item.productId)
        if (price.compareTo(item.price) != 0) {
            return Decision.reject(OrderRejections.PRICE_CHANGED, "The price of ${item.productId} is now $price.")
        }
        val inventory = inventoryService.getInventory(item.productId)
        if (item.quantity > inventory) {
            return Decision.reject(OrderRejections.INSUFFICIENT_INVENTORY, "Only $inventory in stock.")
        }
    }
    val created = OrderCreated(
        items = placing.items.map { item ->
            OrderItem(scope.ids.next(), item.productId, item.price, item.quantity)
        },
        address = placing.address,
        fromCart = placing.fromCart,
    )
    scope.putResult("totalAmount", created.totalAmount())
    return Decision.events(created)
}
```

The 9.x `CreateOrderSpec` indirection existed to inject one qualified bean; the port injects the two services directly and turns their `Mono` APIs into `suspend` functions. Keeping the indirection with `@Name("createOrderSpec")` on the parameter is also valid. The host supplies the services by type and qualifier (in the eShop, Spring beans, such as the payment server's `PaymentGateway`).

This is the one-to-one port. The eShop keeps the stock out of the order: the ordering's `OrderProcessSaga` reserves each product in the `eshop-inventory` context, another service, with a cross-context command; a reservation the inventory rejects comes back to the saga's `@OnRejected` handler, which cancels the order (`examples/eshop/README.md`). Make that move only when the stock belongs to another context; a 9.x service call inside decide stays a service parameter otherwise.

Rejections are values with stable codes. Declare them beside the aggregate so the compiler lists them in the context's Manifest `errors`; codes must not start with `wow.` and must be unique in the module. The HTTP status defaults to 422. Prefer local names (decision D259): in an `object` nested in the aggregate root, `const val WRONG_STATUS = "wrong-status"` is `example.order.wrong-status` everywhere outside the decide function (results, receipts, HTTP, the Manifest, the generated `ExampleContract.Order.Errors.WRONG_STATUS` peers compare with), and a test's `expectRejected` takes either form. Full codes, as below, stay valid; a 9.x code whose text clients match keeps its exact text either way.

A 9.x domain exception with its own error code (a `WowException` subclass, an error-code object) becomes one `@ErrorCode` per code, its 9.x HTTP status kept with `status` so clients that map statuses keep working; a `NotFoundResourceException` the domain threw (an aggregate of another application read as none, say) is a domain code with `status = 404`, not `wow.aggregate.not-found`. A rejection carries a code and a message only: 9.x `bindingErrors` (a list of the ids that blocked the command) have no place in it, so say them in the message and report the change to the clients that read them.

```kotlin
object OrderRejections {
    @ErrorCode(description = "A product has less stock than the quantity ordered.")
    const val INSUFFICIENT_INVENTORY = "example.order.insufficient-inventory"

    @ErrorCode(description = "The order's status does not allow the command.", status = 409)
    const val WRONG_STATUS = "example.order.wrong-status"
}
```

Before:

```kotlin
fun onCommand(changeAddress: ChangeAddress): AddressChanged {
    check(OrderStatus.CREATED == state.status) {
        "The current order[${state.id}] status[${state.status}] cannot modify the address"
    }
    return AddressChanged(changeAddress.shippingAddress)
}
```

After:

```kotlin
@OnCommand(produces = [AddressChanged::class])
fun onCommand(command: ChangeAddress): Decision =
    if (state.status == OrderStatus.CREATED) {
        Decision.events(AddressChanged(command.shippingAddress))
    } else {
        Decision.reject(OrderRejections.WRONG_STATUS, "The order is ${state.status}.")
    }
```

Keep a domain outcome an event when 9.x recorded it as one: `PayOrder` on a non-payable order still produces `OrderPayDuplicated` because the payment service must refund. Its V10 return type is the sealed `List<OrderPayment>`, so `produces` is inferred.

Ownership checks: `@AggregateOwner(OwnerPolicy.AGGREGATE_ID)` becomes `@AggregateRoot(idSource = AggregateIdSource.OWNER)`, and the command functions check no owner. Delete a 9.x check that compared the caller with the aggregate id, and its rejection code:

```kotlin
@AggregateRoot(idSource = AggregateIdSource.OWNER)
class Cart(private val state: CartState) {
    @OnCommand(produces = [CartItemAdded::class, CartQuantityChanged::class])
    fun onCommand(command: AddCartItem): Decision {
        // ...
    }
}
```

Such an aggregate exists once per owner in its tenant, and its id is the owner's:

- an owner-level caller addresses its own by no id, on the routes without `{id}` ([Routing](#routing)); naming another owner's id is 403 `wow.aggregate.forbidden`;
- a caller with no owner (a tenant-level caller, a service, a saga) names the id, else 400 `wow.command.malformed` (`wow.query.malformed` on the state read; 401 `wow.auth.unauthenticated` for an unauthenticated caller);
- a derived or scheduled command without an id takes the owner of its scope;
- the kernel records the id as the owner, whoever creates the aggregate, and refuses another owner, on creation too;
- no route generates an id, and `TransferOwner` is left out of the default lifecycle; the compiler refuses it when listed in `lifecycle`, a command producing `OwnerTransferred`, and `ownership = TENANT`.

## Events

- **Names.** A V10 type is named `context.aggregate.Name`, `Name` being the class's simple name unless `@Name` overrides it. 9.x named types in snake_case from the class (`create_order`, `order_created`) unless `@Name` said otherwise. Do not add `@Name` only to keep the 9.x spelling; map 9.x name to V10 name in `migration-map.json` and leave renames after the migration to `@Alias`.
- **Revisions.** Replace `@Event(revision = "...")` with `@Revision(n)`. When the V10 shape differs from the stored 9.x shape (an identity field removed, a computed property dropped), the V10 class gets a higher revision and an upgrade chain from revision 1. The compiler checks the chain has no gap and no duplicate.
- **Upgrade functions.** `@Upgrade(event, from)` replaces `EventUpgrader`: a top-level function from `ObjectNode` to `ObjectNode` (Jackson 3, `tools.jackson.databind.node`), upgrading revision `from` to `from + 1`.
- **JSON shape.** Computed getters are serialized by Jackson; make them functions (`totalPrice()`) so they leave the event's JSON. `ErrorInfo` on an event becomes plain fields.

Before:

```kotlin
data class OrderCreated(
    val orderId: String,
    val items: List<OrderItem>,
    val address: ShippingAddress,
    val fromCart: Boolean
)

data class OrderItem(/* ... */) : Identifier, CreateOrderItem {
    val totalPrice: BigDecimal
        get() = price.multiply(quantity.toBigDecimal())
}
```

After:

```kotlin
@Revision(2)
@Summary("The order was placed")
data class OrderCreated(
    val items: List<OrderItem>,
    val address: ShippingAddress,
    val fromCart: Boolean,
) {
    fun totalAmount(): BigDecimal = items.fold(BigDecimal.ZERO) { total, item -> total + item.totalPrice() }
}

@Upgrade(event = OrderCreated::class, from = 1)
fun upgradeOrderCreatedFrom1(node: ObjectNode): ObjectNode {
    node.remove("orderId")
    node.get("items")?.forEach { item -> (item as? ObjectNode)?.remove("totalPrice") }
    return node
}
```

Cover each upgrade with a test that decodes a real 9.x payload through the generated model.

### Retired events (9.x `DroppedEvent`)

An event the domain no longer has is retired, not dropped from history (decision D219): delete its class and its sourcing function, and list the name the log stores it under in `@AggregateRoot(retiredEvents)`. A record of a retired event is skipped when the aggregate loads (its stream still counts as a version) and passed over by subscriptions; a name neither current nor retired still fails to load. A name without a dot is within the aggregate (`OrderArchived` is `context.aggregate.OrderArchived`); a name with dots is the full stored name. The compiler rejects a retired name that is a current event's or command's name or former name (`@Alias`). The Manifest lists the retired names (`retired`), and the compatibility gate takes an event moved from `events` to `retired` for a retirement, not a breaking removal.

Before, 9.x:

```kotlin
class OrderArchivedUpgrader : EventUpgrader {
    override val eventNamedAggregate: EventNamedAggregate =
        "example-service.order".toNamedAggregate().toEventNamedAggregate("order_archived")

    override fun upgrade(domainEventRecord: DomainEventRecord): DomainEventRecord =
        domainEventRecord.toDroppedEventRecord()
}
```

After, V10:

```kotlin
@AggregateRoot(retiredEvents = ["OrderArchived"])
class Order(private val state: OrderState) { /* no OrderArchived class, no onSourcing for it */ }
```

In `migration-map.json`, map the 9.x event to that name with `"retire": true` (`{"from": "order_archived", "to": "OrderArchived", "retire": true}`): the data tool imports its records under the retired name and checks that the aggregate retired it. Do not map a deleted event with `"drop": true` when a command produced it alone: its streams would be left empty and the check refuses the map.

## State classes

- `onSourcing` functions must be non-private; V10 ignores private functions, and an event without an evolve function fails when encoded.
- Every event a command can produce needs a sourcing function, even an empty one (`fun onSourcing(event: OrderOverPaid) = Unit`).
- Every property needs a value before creation: the kernel serializes state for snapshots and the test DSL's purity check, and an uninitialized `lateinit` cannot be read. Make it nullable or give it a default.
- Mutable state stays the default style; only annotations and signatures change. Decide must not mutate state outside events; the test DSL fails such code.
- Drop the 9.x framework interfaces on state: `ReadOnlyStateAggregate` and swagger `@Schema`. `StatusCapable` and the other `wow-models` interfaces stay, from the V10 `wow-models`.
- `StateAggregateTagsExtractor` becomes a `@Tags` member of the state (decision D220): a function without parameters, or a property, returning `Map<String, Collection<String>>`, pure (no I/O, not `suspend`, no `@Blocking`). Derived keys override, per key, the tags `ApplyTags` applied, and are derived again after every commit and load, so a state change replaces a key's values instead of merging them as 9.x did: return every key it owns on every call, an empty list for none. `ApplyTags` cannot set a derived key.
- Drop the state's `id` unless the domain reads it: a no-argument constructor is the recommended form (decision D203); the id is the snapshot's `aggregateId` and `command.ref.id` in decide. The compiler also accepts `(id)` and `(id, tenantId)`.
- Jackson trees in commands, events and state (`ObjectNode`, `JsonNode`, a free-form config) stay Jackson trees: every API module has Jackson's databind through `wow-api`, and a peer reads such a state field as an `ObjectNode` (a free value as a `JsonNode`) in the API's generated state view. A typed class is better where the shape is known; the Manifest schema of a free-form field is a plain object. A query can neither filter nor project into such a field (`state.config.kind` is `UNKNOWN_FIELD`), so a value clients filter or list by is kept as a field of its own beside it (`state.kind`), and their queries change.

Before:

```kotlin
class OrderState(val id: String) : StatusCapable<OrderStatus>, StateAggregateTagsExtractor<OrderState> {
    lateinit var items: List<OrderItem>
        private set
    lateinit var address: ShippingAddress
        private set
    // ...
    private fun onSourcing(orderPaid: OrderPaid) { /* ... */ }
}
```

After:

```kotlin
class OrderState {
    var items: List<OrderItem> = emptyList()
        private set
    var address: ShippingAddress? = null
        private set
    // ...
    fun onSourcing(event: OrderPaid) { /* ... */ }

    @Suppress("UnusedParameter")
    fun onSourcing(event: OrderOverPaid) = Unit
}
```

## Sagas, projections, reactors

The compiler generates the subscription bindings of annotated processors (decision D149: a `ProcessorBinding` per handler, exposed through the generated `ModelProvider`, which the runtime turns into subscriptions), so rewrite sagas, projections and reactors directly; leave no `TODO(wow-migrate)` waiting for bindings.

- The class carries exactly one of `@Projection`, `@Reactor`, `@Saga`; its handlers are `@OnEvent` functions (only the annotation declares one), none private.
- A handler takes `StateEvent<S>` (the commit's events plus the state after it), or, for one kind of event, `StateTypedEvent<S, E>` or the event `E` itself (decision D264: called once per `E` of the stream, in order, `state` the state after the whole commit), and optionally its kind's context (`SubscriptionContext`, `SubscriptionContext`, `SagaContext`), nothing else. Services go to the processor's constructor.
- The source aggregate follows from `S`, or from `E`. A 9.x `@OnEvent fun onX(event: DomainEventStream/X)` maps to a handler of `StateTypedEvent<S, X>`, not to `StateEvent` with `filterIsInstance`; another context's `E` is its api's class, whose aggregate its contract names (DX-1c).
- A handler of another context's aggregates (a typed handler of its api's events, or `@OnEvent(aggregates = [<Context>Contract.<Aggregate>.QUALIFIED_NAME])`, the 9.x cross-context event processor) reads that context's published stream from Kafka: `S` is the state view the upstream api generates from its contract (`OrderStateView`: the state's fields, required ones non-null, a domain type copied, free-shape content an `ObjectNode`; delete a hand-written 9.x view class of the peer's state), `event.events` are the upstream api's classes at the contract's revision, else `PublishedEvent` (name, revision, JSON text), and a handler of each event (`StateTypedEvent<OrderStateView, E>`) takes the api class, and a stream the upstream publishes without its state has no view and needs a nullable `S`, or the service does not start.
- A projection or reactor returns nothing; a saga returns the commands it derives as `CommandRequest`s (one, or an `Iterable`, a `Sequence` or a `Flow` of them), `CommandRequest.to(ref, body)`; the runtime gives them deterministic ids and request ids. `CommandRequest.of(body, id)` (or `CommandRequest.of(body)` for a creation without an id) leaves the aggregate type to the contracts and the tenant to the handled commit. Reactor returns go through `wow-reactor`.
- Each handler is its own subscription named `processor.function`; the processor name is `@Name` or the class name. Derive the subscription names from the 9.x processor names and report them.
- Use `context.invocationKey` as the idempotency key of external calls (`executionId` is per stream, `invocationKey` per call of a handler of each event); side effects must tolerate at-least-once delivery and cross-aggregate reordering.
- 9.x `@Retry`'s settings become the subscription's parked retries (`wow.subscriptions.[<context>.<name>].parked.*`, its `executionTimeout` as `timeout`); failures park the aggregate in the failure ledger. V10's `@Retry(maxAttempts, initialBackoff, maxBackoff, multiplier, timeout)` sets the in-process budget before it parks.

Before:

```kotlin
@StatelessSaga
class CartSaga {
    @Retry(maxRetries = 5, minBackoff = 60, executionTimeout = 10)
    @OnEvent
    fun onOrderCreated(event: DomainEvent<OrderCreated>): CommandBuilder? {
        val orderCreated = event.body
        if (!orderCreated.fromCart) {
            return null
        }
        return RemoveCartItem(productIds = orderCreated.items.map { it.productId }.toSet())
            .commandBuilder()
            .aggregateId(event.ownerId)
    }
}
```

After:

```kotlin
@Saga
class CartSaga {
    @OnEvent
    fun onOrderCreated(event: StateTypedEvent<OrderState, OrderCreated>): List<CommandRequest<RemoveCartItem>> {
        val created = event.event
        val customer = event.scope.ownerId
        if (!created.fromCart || customer == null) {
            return emptyList()
        }
        val cart = AggregateType(ExampleContext.NAME, ExampleContext.CART).ref(event.ref.tenantId, customer)
        return listOf(CommandRequest.to(cart, RemoveCartItem(created.items.map { it.productId }.toSet())))
    }
}
```

A 9.x saga or `onStateEvent` handler that only logs has no V10 counterpart worth keeping; delete it and say so.

## Reserved keys (9.x `PrepareKey`)

A 9.x `@PreparableKey` interface and its `prepare`/`usingPrepare`/`rollback`/`reprepare` calls become reservations taken in the decide function (decisions D38, D225): `scope.reserve(namespace, key, tenancy)` returns false when another aggregate holds the key (return a rejection then); the kernel confirms the reservation once the events are appended and gives it up when they are not, so `usingPrepare`'s rollback has no counterpart. A rename (`reprepare`) reserves the new key and `scope.release(namespace, oldKey, tenancy)`s the old one in the same decision; a release applies only once the events are appended. Deleting an aggregate releases none of its keys: its delete command releases those it should give up.

**Tenancy is a decision per key.** A 9.x `PrepareKey` was global: one key across every tenant. A V10 key is unique within its bounded context and, by default (`reserve(namespace, key)`, `ReservationTenancy.TENANT`), within the aggregate's tenant, so another tenant may hold the same key. Copying `reserve(namespace, key)` therefore turns a globally unique login name into one unique per tenant, silently. For each key, decide:

- `ReservationTenancy.SYSTEM`: the key must stay unique across tenants (a login name, an e-mail address used to sign in, an external id shared by all tenants);
- `ReservationTenancy.TENANT`: the key is the tenant's own business (an order number, a SKU, a code that only needs to be unique inside the tenant).

When the code and the request do not say which, ask the user, and record the answer; never pick one silently. A single-tenant application behaves the same either way, but say which you chose. Write the same `tenancy` and namespace into the `reservations` entry of `migration-map.json`.

Before, 9.x:

```kotlin
@PreparableKey(name = "username")
interface UsernamePrepareKey : PrepareKey<String>

fun onCommand(command: CommandMessage<Register>, usernamePrepareKey: UsernamePrepareKey): Mono<Registered> =
    usernamePrepareKey.usingPrepare(command.body.username, command.aggregateId.id) { prepared ->
        require(prepared) { "username is already reserved" }
        Registered(command.body.username).toMono()
    }
```

After, V10 (a login name unique across tenants, as the user confirmed):

```kotlin
@OnCommand(produces = [Registered::class])
suspend fun onCommand(command: Register, scope: DecideScope): Decision =
    if (scope.reserve(UserNamespaces.NAME, command.username, ReservationTenancy.SYSTEM)) {
        Decision.events(Registered(command.username))
    } else {
        Decision.reject(UserRejections.NAME_TAKEN, "${command.username} is taken.")
    }
```

`migration-map.json`, under the user aggregate: `"reservations": [{"from": "username", "namespace": "user.name", "tenancy": "SYSTEM"}]` (the 9.x value is the user's id; give `"ownerId": "<path>"` when it is an object holding the id). The data tool reserves each key 9.x prepared forever for its owner, in the tenant the owner's 9.x streams record, and skips pending and expired keys; `compare` checks every confirmed key is reserved for the same owner. A 9.x lookup of an aggregate by its key (`PrepareKey.get`) becomes a `CommandTransformer` (decision D182) that finds the command's aggregate, or a query; check the pinned target for what it offers.

## Routing

`@AggregateRoute` and `@CommandRoute` are removed. Every command route is derived from the Manifest:

- command on an existing aggregate: `POST /{context}/{aggregate}/{id}/{command-kebab}`;
- creating command: also `POST /{context}/{aggregate}/{command-kebab}`, the server generating the id;
- state: `GET /{context}/{aggregate}/{id}/state`, with `?version=`;
- lifecycle: `DELETE /{context}/{aggregate}/{id}` and `POST /{context}/{aggregate}/{id}/wow.lifecycle.<kebab>`;
- an aggregate whose id is its owner's (`idSource = AggregateIdSource.OWNER`, 9.x `OwnerPolicy.AGGREGATE_ID`): beside every route by id, the caller's own by no id, `POST /{context}/{aggregate}/{command-kebab}` for every command and lifecycle command, `DELETE /{context}/{aggregate}`, and `GET /{context}/{aggregate}/state` and `GET /{context}/{aggregate}/state/trace`; none generates an id.
  A 9.x client that put the user id in both the owner segment and the id (`POST /owner/{ownerId}/cart/{ownerId}/add_cart_item`) sends `POST /example/cart/add-cart-item`.

There are no tenant or owner route variants, no `PUT`/`PATCH`, no custom action segments, and no path or header variables bound into bodies. The aggregate segment is the aggregate's name; renaming it with `@Name` renames the aggregate.

Routes of the 9.x example, from its generated OpenAPI, next to its V10 port's (context `example`):

| 9.x (example) | V10 (port) |
|---|---|
| `POST /tenant/{tenantId}/owner/{ownerId}/sales-order` (`CreateOrder`, `action = ""`) | `POST /example/order/create-order`, or `POST /example/order/{id}/create-order` with a client-chosen id |
| `PUT /tenant/{tenantId}/owner/{ownerId}/sales-order/{id}/address` (`ChangeAddress`) | `POST /example/order/{id}/change-address` |
| `POST /tenant/{tenantId}/sales-order/{id}/pay` (`PayOrder`) | `POST /example/order/{id}/pay-order` |
| `POST /tenant/{tenantId}/sales-order/{id}/package` (`ShipOrder`) | `POST /example/order/{id}/ship-order` |
| `PATCH /tenant/{tenantId}/owner/{ownerId}/sales-order/{id}/package` (`ReceiptOrder`) | `POST /example/order/{id}/receipt-order` |
| `DELETE /tenant/{tenantId}/owner/{ownerId}/sales-order/{id}` | `DELETE /example/order/{id}` |
| `PUT /tenant/{tenantId}/owner/{ownerId}/sales-order/{id}/recover` | `POST /example/order/{id}/wow.lifecycle.restore-aggregate` |
| `PUT /tenant/{tenantId}/owner/{ownerId}/sales-order/{id}/tags` | `POST /example/order/{id}/wow.lifecycle.apply-tags` |
| `GET /tenant/{tenantId}/owner/{ownerId}/sales-order/{id}/state/{version}` | `GET /example/order/{id}/state?version={version}` |
| `MockVariableCommand` with path and header variables | No equivalent; dropped |

9.x also served query, resend, regenerate and compensation routes per aggregate. Their V10 counterparts, all from the Manifest and served by `wow-http` (verify each on the pinned target and report every one a client uses):

| 9.x route | V10 |
|---|---|
| `POST {aggregate}/snapshot/single`, `list`, `paged`, `cursor`, `count`, `aggregation` (and their `/state` variants) | `POST /{context}/{aggregate}/snapshot/query`, the operation in the body's `kind`: `single`, `list`, `paged`, `cursor`, `count` or `aggregate`, beside the query's own fields (`filter`, `sort`, `projection`, …); `GET` of the same path answers the model's capability descriptor (9.x `{aggregate}/snapshot/schema`, which needs no refresh: the schema comes from the Manifest). A list answers a stream with `Accept: text/event-stream`. There is no `/state` variant: the record is the snapshot, its state under `state` (see [Queries](#queries)) |
| `POST {aggregate}/event/list`, `paged`, `cursor`, `count`, `aggregation`; `GET …/{id}/event/{headVersion}/{tailVersion}` | `POST /{context}/{aggregate}/events/query` (decision D235): one record per committed event stream (`aggregateId`, `version`, `commitTime`, `commandType`, `requestId`, `operator`, `events: [{name, revision, body}]`), so `count` counts commits; filter `aggregateId` and a `version` range for one aggregate's streams; an event condition is an element match on `events`, and per-event analytics expand it in an `aggregate` query (`expand("events")`) |
| `GET …/{id}/state/tracing` | `GET /{context}/{aggregate}/{id}/state/trace`; a past state is `GET …/{id}/state?version=` or `?at=` |
| `PUT …/{id}/snapshot` (regenerate), `PUT …/snapshot/{afterId}/{limit}` (batch regenerate) | `POST /{context}/snapshot/{aggregate}/wow.snapshot.rebuild` (decision D172) with `{"ids": [...]}`: rebuilds those aggregates' snapshots from the log in the background (a bounded list of ids per request, `SnapshotRebuilds.MAX_IDS`; no "after id" sweep) |
| `POST …/state/{afterId}/{limit}` (state-event resend), `PUT …/{id}/{version}/compensate` (re-dispatch one version) | No targeted resend or replay: deferred to 10.x (09 §17 G-9); report it. A version whose handling failed is a parked entry, resumed under `/wow/ops/parked` |
| The 9.x compensation service's routes and dashboard | The node's parked entries under `/wow/ops/parked` (`wow.http.ops.enabled: true`), see [Compensation and failed executions](#compensation-and-failed-executions) |
| Datasets (`wow-query` dataset routes) | `POST /wow/dataset/{context}/{dataset}/query` and its `GET`; `GET /wow/dataset` lists those the caller sees |

Read the 9.x paths from the application's generated 9.x OpenAPI, not from annotations; every client, gateway rule and CoSec policy that names a 9.x path must change.

## Tenancy and scope

- `@StaticTenantId` and `BoundedContext.Aggregate(tenantId = ...)` become host configuration per aggregate type:

  ```yaml
  wow:
    aggregates:
      "[example.cart]":
        tenant: "(0)"
  ```

- Tenant, owner and space come from the authenticated principal. A principal without a tenant gets `wow.tenant.default` (`(0)`). An administrator acting in another scope sends the `Wow-Scope` header (an RFC 8941 dictionary of `tenant`, `owner`, `space`); a caller without permission gets 403 `wow.auth.forbidden`, never a silent fallback.
- **Development callers** (decision D334). In a development profile (a profile of `wow.dev-profiles`), with CoSec off (`cosec.enabled=false`) and no `CallerResolver` bean of the application's, the host reads the caller from `Wow-User` (the subject), `Wow-Owner` (the owner of its scope; without it, a tenant-level caller, never the user by default), `Wow-Roles` (comma-separated, the `roles` attribute), `Wow-Tenant` (`wow.tenant.default` without it) and `Wow-App` (the application, `Caller.app`); without `Wow-User` the request is the anonymous caller. Drop a 9.x local-run filter or resolver that faked identities from its own headers. Outside a development profile the host never reads these headers: a node in development by its loopback internal port alone serves every request as the anonymous caller, and any other node without a resolver refuses to start.
- `@AggregateOwner(OwnerPolicy.ALWAYS)` and `@Spaced` disappear: the scope is always recorded from the principal. `OwnerPolicy.AGGREGATE_ID` becomes `@AggregateRoot(idSource = AggregateIdSource.OWNER)`, as shown above.
- Ids are unique per tenant only; the data import reports ids that collide across tenants.

## Ownership and owner moves

Read this when the 9.x domain uses the owner segment of its paths as data: an audience (`owner/(shared)` for shared, a user for personal), a reserved owner, or an event that implements `OwnerTransferred` (the 9.x view store, ported in V10 work package 7.2a, is the reference: `platform/view-store`).

- **Who owns what** (decision D189). Choose `Ownership` per aggregate as the annotation mapping says. A creation without an owner (an anonymous or tenant-level caller) records none; when the domain needs one, reject it with a code of its own rather than inventing an owner.
- **Reserved owners.** A V10 caller acts as another owner only by naming it in `Wow-Scope`, which admission accepts only from a caller with `crossScope`. To keep 9.x paths such as `owner/(shared)` open to everyone while other owners stay closed, the host's `CallerResolver` grants `crossScope` only when `Wow-Scope` names exactly a reserved scope, and returns the caller with that scope already as its own: a `CommandTransformer` sees only `CommandInput.caller`, not the scope `Wow-Scope` names. Who may write under a reserved owner is the `CommandAuthorizer`'s decision (`CommandAction.scope`), which replaces the 9.x gateway's path rules. This is a security decision: record it for the user, never infer it.
- **Moving an owner.** No 9.x marker interface moves the owner in V10: a command changes its aggregate's lifecycle by producing the lifecycle event beside its own event, in one commit (decision D336). Rewrite a 9.x command whose event implemented `OwnerTransferred` as a command of the aggregate that produces its domain event and `OwnerTransferred(ownerId)`: `ClaimView(toOwnerId)` produces `ViewClaimed(toOwnerId)` and `OwnerTransferred(toOwnerId)`, listed in `@OnCommand(produces = [ViewClaimed::class, OwnerTransferred::class])`; the stream records the new owner, and the move is one version. An aggregate of its tenant (`Ownership.TENANT`) has no owner, and one whose id is its owner's (`idSource = AggregateIdSource.OWNER`) keeps it: the compiler refuses a command that produces `OwnerTransferred` on either. An annotated model's decide function never takes `wow.lifecycle.TransferOwner` or any other lifecycle command, which the kernel decides: the compiler refuses it (a model of the kernel's DSL may override one, deciding that command's own lifecycle event beside its own events). Who may move an owner is the `CommandAuthorizer`'s decision on the command. A move that must run apart from the domain change (another authorization, after a confirmation) is a `@Saga` that derives `TransferOwner` from the domain event instead (derived commands keep the commit's scope, decision D199), as the V10 view store does; a caller that must see it waits for it: `Wow-Wait: handled(<Saga>.<function>)>snapshot`.
- **The user a command is for.** A 9.x route that took the target owner from the caller's own path (a claim sent to `owner/{me}`) reads it from `command.operator?.id` instead; never from the body, which anyone can fill.

A target pinned to an earlier V10 pre-release that copied the eShop's basket changes with it: the basket declares `@AggregateRoot(idSource = AggregateIdSource.OWNER)`, its `eshop-basket.basket.not-owner` rejection code is removed (the kernel answers another owner with `wow.aggregate.forbidden`), and `POST /eshop-basket/basket/add-basket-item` no longer creates a basket under a generated id: it is the buyer's own basket, whose id is the buyer's user id. A client that sent `POST /eshop-basket/basket/{id}/…` with the buyer's id still works; one that stored a generated basket id reads the buyer's basket by no id instead.

## Routes and handlers of a 9.x starter

A 9.x starter's own `RouterFunction` routes have no V10 counterpart: a V10 service serves the Manifest's routes. Rewrite each by what it did, and list every change for the clients (report it as a REST contract difference, never emulate the 9.x path):

| 9.x starter route | V10 |
|---|---|
| A write to an aggregate whose id the handler derived (from the owner, the application, a key) and dispatched with `CommandHandler` | A `CommandTransformer` of the command (decision D182) that derives the id; the route without an id (`POST /{context}/{aggregate}/{command}`) is served for it. Annotate a command that does not create its aggregate `@Unaddressed` (decision D210), so the Manifest, its baseline and the generated clients list that route too. Derive the id from what the caller cannot choose, and send a request that names an id to the derived one all the same. |
| A lookup of that aggregate (`GET …/preferences`) | A snapshot query (`POST /{context}/{aggregate}/snapshot/query`) filtered by what the id was derived from; nothing found means never written (version 0). |
| A route that answered "already so" without a command, so the version did not move | `Decision.NoOp` in the aggregate: the command is answered at the unchanged version. |
| A replay route that found a write by its request id (`…/requests/{requestId}`) | Not needed: a retry with the same `Wow-Request-Id` is answered as a duplicate with the version it committed (`GET …/{id}/state?version=` reads it), and a creation without an id keeps the id of its first attempt (decision D191). |
| `Command-Aggregate-Version: 0` for "never written" | `If-Match: "0"`; a stale version is 412 `wow.aggregate.version-mismatch`. |
| A query policy reading request headers (an application) | A `QueryPolicy` bean reading `QueryCaller.attributes`, which the `CallerResolver` fills from the header; only snapshot models carry the state fields it filters on. |
| A custom delete or recover command (a command implementing 9.x `DeleteAggregate` or `RecoverAggregate`, an event implementing `AggregateDeleted` or `AggregateRecovered`) | By what it adds to the built-in one (decision D336). **A check of who may delete**: a `CommandAuthorizer` rule on `wow.lifecycle.DeleteAggregate`, on the same route, `DELETE /{context}/{aggregate}/{id}`. **A domain rule** (a cart with items is not deleted) **or fields of its own** (a reason): a command of the aggregate that produces its domain event and the lifecycle event in one commit, `CloseCart(reason)` deciding `Decision.events(CartClosed(reason), AggregateDeleted)` with `@OnCommand(produces = [CartClosed::class, AggregateDeleted::class])`, or a rejection; the kernel marks the aggregate deleted and drops its schedules, as `DeleteAggregate` does. Its route is the command's own (`POST /{context}/{aggregate}/{id}/close-cart`): tell the clients. Remove `DeleteAggregate` from `@AggregateRoot(lifecycle)` when only the domain command may delete; the composed event still deletes. A model written with the kernel's DSL instead may keep the built-in route with a `decide<DeleteAggregate>` of its own, which decides `AggregateDeleted` beside its own events, `NoOp` or a rejection; an annotated model's decide function never takes a lifecycle command, which the compiler refuses. **A domain event beside the unchanged built-in delete**: `@AfterCommand(include = [DeleteAggregate::class])`. A recover command is decided on a deleted aggregate only with `@OnCommand(onDeleted = true)` and produces `AggregateRestored` (9.x `AggregateRecovered`) beside its own events; without the mark a deleted aggregate refuses it (`wow.aggregate.deleted`). The data: map the 9.x event to the domain event with a `lifecycle` entry in `migration-map.json` ([Preconditions and gates](#preconditions-and-gates)), so the import writes both. |
| A read-only route over what configuration declares (the view store's configured system views) | Kept as a route of the service's own: a `RouterFunction` bean ordered first (`@Order(Ordered.HIGHEST_PRECEDENCE)`), since Wow's routes answer every other path under a context's prefix with a 404; it authenticates with the service's `CallerResolver` and answers errors as `Problem`s. It is not in the Manifest or the OpenAPI document, so report it to the clients separately. |
| Routes closed by a `WebFilter` (event streams, recover, tags) | Narrow `@AggregateRoot(lifecycle)` to the lifecycle commands kept; deny the rest in the `CommandAuthorizer`. |

A domain service a 9.x handler injected (`Flux`-returning port, say) becomes a `suspend` port bound as a Spring bean of its type; one that reads the node's own queries (`HttpQueries`) looks them up lazily (`ObjectProvider`), since the queries are built with the node that binds the service.

## Host configuration

V10 host keys live under `wow.*` but none of the 9.x keys carry over: `wow.service`, `wow.node.*`, `wow.internal.port` (default 9090), `wow.backend` (chosen from the class path), `wow.log.*`, `wow.cluster.*`, `wow.wait.*`, `wow.tenant.default`, `wow.http.*`, `wow.aggregates.*`. Map each 9.x key to a V10 key or record it as removed; never leave a 9.x key in place expecting it to bind.

A target pinned to an earlier V10 pre-release renamed two things that are not 9.x keys: an aggregate type routed through its context's command topic is `wow.aggregates.[<context>.<aggregate>].routing: topic` (it was `kafka`), and the subscriptions that write the published streams are named `transport-sink.<aggregate>` (they were `kafka-sink.<aggregate>`). A subscription's name is its checkpoint key, so a service upgraded in place from such a pre-release writes every published stream again from the beginning; its consumers see each commit again and must deduplicate it by its aggregate and version.

A target pinned to an earlier V10 pre-release that read `WowProperties.mongo` or `WowProperties.postgres` in its own code reads its store's options instead: those classes are gone, and the keys (`wow.mongo.*`, `wow.postgres.*`) are unchanged. A store's `wow.stores.<name>.*` now takes only the options its store type declares: any other key fails the start, naming the declared ones.

A target pinned to an earlier V10 pre-release writes a store's provider settings beside its own keys: `wow.stores.<name>.options.<key>` is `wow.stores.<name>.<key>` (`index-prefix`, `query-client`, `pool-gauge`, `query-read-preference`, `sharding`), as `data-source`, `client` and `database` already were; the host reads `type`, `ddl` and `query` itself. A few values changed with it: `wow.mongo.sharding` (and a store's `sharding`) is `auto` or `none` (it was `off`), and Elasticsearch's `refresh` is `wait-for`, `immediate` or `none` (it was `wait_for`, `true`, `false`). A value outside an option's set fails the start, naming the key and the values.

A target pinned to an earlier V10 pre-release renames these host keys, with no alias; an old key refuses the start, pointing at the migration guide:

- `wow.cluster.router` and `wow.aggregates.[..].routing: cluster|kafka` are one key, `wow.routing: auto|partitioned|local|topic`, with the same values at `wow.aggregates.[<context>.<aggregate>].routing` (`auto` takes `wow.routing`'s; `partitioned` or `local` there must be the node's).
- `wow.ops.enabled` is `wow.http.ops.enabled`; `wow.ops.webhook.*` and `wow.ops.publish.*` stay.
- `wow.cosec.mode` is `gateway` or `disabled` (it was `off`).
- `wow.health.sample-interval` and `wow.observability.sample-interval` are one `wow.node.sample-interval`. The node's one sampler counts parked entries up to `wow.node.max-parked` (it was `wow.observability.max-parked`), for its health and its gauges alike; `wow.health.subscriptions.parked-degraded-at` is at most it.
- `wow.subscription.read-retry.max-attempts` is `read-retry.error-after`, and `read-retry.timeout` is gone.
- `wow.cluster.max-in-flight-per-peer` is `max-in-flight-per-node`; `wow.query.cross-tenant.enabled` is `wow.query.allow-cross-tenant`; `wow.snapshot.interval`, `.batch` and `.pages` are `wow.snapshot.maintenance-interval`, `.rebuild.page-size` and `.rebuild.max-pages`; `wow.kafka.topic-prefix` is `wow.kafka.topics.prefix`.
- `wow.shared-states.max-bytes` is a size (`64MB`); a number is still read as bytes.
- `wow.query.memory.enabled` is `true` by default, and an aggregate's `clickhouse.history.enabled` is `auto`, `true` or `false`.
- The analytics catalog service drops `wow.analytics.catalog.engine` (ClickHouse is its one engine); `approval.sources` and `approval.publications` are `require-source-approval` and `require-publication-approval`, and `datasets.serve` is `datasets.serving.enabled`.

A target pinned to an earlier V10 pre-release changes in how its authorizers compose: an application's own `CommandAuthorizer` no longer switches CoSec's off; both must allow. Every `CommandAuthorizer` bean is asked in its order (`@Order`), and the first denial is the answer, so an authorizer allows (has no objection to) every command outside its own concern. `CallAuthorizer` is gone: a rule for the service identities' calls is a `CommandAuthorizer` that checks `caller.service`. `CoSecAuthorizers` is gone: an authorizer of the application's own roles that should decide only without CoSec takes `ObjectProvider<CoSecCommandAuthorizer>` and allows every command when there is one. Every `Observer` bean is told, so OpenTelemetry's observer runs beside the application's; and two beans of a single seam (`CallerResolver`, `GlobalIdGenerator`, `MaskStrategyResolver`, `DatasetVisibility`, `CommandCaller`, `QueryCountCache`) fail the start unless one is `@Primary`.

A target pinned to an earlier V10 pre-release with `wow.cosec.authorizer.enabled` decides differently now: CoSec's authorizers evaluate each command and service-route call from the caller's application and in its space, as the web filter does, so a permission of the application's roles allows it. Before, they named no application, so no app-role permission applied and every command that only an app role allows was implicitly denied. Review what the application's roles allow before upgrading such a deployment.

## REST clients

| 9.x | V10 |
|---|---|
| `Command-Wait-Stage`, `Command-Wait-Context`, `Command-Wait-Processor`, `Command-Wait-Tail-*` | `Wow-Wait`: `hop (">" hop)*`, hops `accepted`, `committed`, `snapshot`, `handled(subscription)`; default `snapshot` |
| `Command-Wait-Timeout` | `Wow-Wait-Timeout` (`500ms`, `10s` or ISO-8601) |
| `Command-Request-Id` | `Wow-Request-Id` |
| `Command-Aggregate-Version` | `If-Match: "<version>"` (412 `wow.aggregate.version-mismatch`); reads answer `ETag` |
| `Command-Tenant-Id`, `Command-Owner-Id`, tenant/owner route prefixes | Principal scope; `Wow-Scope` for cross-scope callers; `Wow-Space` selects among the principal's spaces |
| `Command-Aggregate-Id` | The route's `{id}` |
| `/wow/command/send`, `/wow/command/wait` | Per-command routes above |
| `ErrorInfo` body (`errorCode`, `errorMsg`, `bindingErrors`) | RFC 9457 `application/problem+json`: `type` `urn:wow:error:<code>`, `code`, `violations`, and the full `CommandResult` under `result` once an aggregate ref exists |

Framework codes and statuses: `wow.auth.unauthenticated` 401; `wow.auth.forbidden` and `wow.aggregate.forbidden` 403; `wow.command.malformed`, `wow.command.invalid`, `wow.wait.invalid`, `wow.command.unhandled` 400; `wow.aggregate.not-found` 404; `wow.aggregate.exists`, `wow.command.request-id-reused`, `wow.command.contended` 409; `wow.aggregate.version-mismatch` 409, or 412 with `If-Match`; `wow.aggregate.deleted` 410; `wow.command.busy` 429; `wow.command.unknown`, `wow.node.closing`, `wow.node.standby` 503; `wow.subscription.parked` 500; `wow.wait.timeout` 504; domain rejections 422 unless their `@ErrorCode` says otherwise.

Kotlin callers of 9.x `CommandGateway` move to the host's `CommandClient` bean (`me.ahoo.wow.api.client`, decisions D257–D259, D337), which takes one `CommandRequest`: the command object, the aggregate's id (none for a creation or an `@Unaddressed` command) and how to send it. The aggregate type comes from the command's class (the service's models, its peers' contracts) unless the request sets it; a lifecycle command (`DeleteAggregate`, ...) always sets it. The tenant and scope are the caller's unless the request targets others:

| 9.x | V10 |
|---|---|
| `commandGateway.sendAndWaitForSnapshot(command.toCommandMessage(aggregateId = id))` | `commandClient.of(caller).wait(command, id)`: the server's default plan; `wait(command)` for a creation whose id the server assigns |
| `toCommandMessage(aggregateId = id, tenantId = t)`, another tenant than the caller's | `wait(CommandRequest.to(OrderingContract.Order.ref(t, id), command))` (the api jar's generated `<Context>Contract`): `t` is the target tenant, a cross-scope request unless it is the caller's own |
| `send(message, WaitStrategy)` with `waitStage = PROCESSED` / `SNAPSHOT` | `wait(CommandRequest.of(command, id, wait = WaitPlan.COMMITTED))` (or `WaitPlan.SNAPSHOT`, any `Wow-Wait` plan), `timeout = …` |
| `send(message)` (`SENT`) | `send(command, id)`: answers at ACCEPTED |
| `CommandResultFlux` / per-stage results | `stream(request)` (or `stream(command, id)`), a flow of one result per hop |
| `@AggregateId`, `@TenantId`, `@OwnerId` on the command body | Gone from the body (decision D68): the request's id; the tenant, owner and space are the caller's, `targetTenant`/`targetScope` for a cross-scope caller |
| `requestId`, `aggregateVersion` on the message | `CommandRequest.of(command, id, requestId = …, expectedVersion = …)` |
| `CommandResult` with `errorCode` / `CommandResultException` | Only a result that succeeded is answered: a rejection throws `CommandRejectedException` (its `code` and `result`), a technical failure `WowProblemException` (`code`, HTTP `status`, `retryHint`) |
| A command to an aggregate of another service through the Kafka command bus | The same call: a command whose aggregate's context is a peer's (its `*-api` on the class path, decision D234) goes to that context's command topic `wow.<context>.commands`, as a saga's does (review M-1). Waits: `send` (ACCEPTED, once the broker acknowledged it) and `wait` up to the peer's COMMITTED, the default; a later hop (`snapshot`, `handled(…)`) is refused with `wow.wait.invalid`. Without Kafka (`wow.kafka.bootstrap-servers`) such a command is refused as `wow.command.unhandled` |

The client runs the gateway's admission in process: the caller is authorized, the command validated (Bean validation, transformers) and not serialized for an aggregate the node serves. Give it a caller with `of(caller)`: the bean sends as an anonymous one. Code that needs the lower level keeps `CommandGateway` (`me.ahoo.wow.runtime.admission`) with `ServerCommandRequest.of(command, ref)` (decision D258). A Reactor caller wraps the `suspend` calls in `mono { … }` (`kotlinx-coroutines-reactor`); a Java caller uses `JavaCommandClient.of(commandClient)`. TypeScript clients are regenerated from the V10 Manifest and rewritten with the `wow-client` skill; do not hand-patch generated clients.

A target pinned to an earlier V10 pre-release spelled Wow's own enumerations in upper snake case on the wire. They are lower-kebab now, and readers accept nothing else:

- A command result's `status` (`succeeded`, `rejected`, `failed`), `hop.stage` (`accepted`, `committed`, `handled`) and `function.kind` (`command`, `saga`, …). Compare against these in client code, and regenerate TypeScript clients.
- The Manifest's `creation` (`none`, `required`, `allowed`), `ownership` (`owner`, `tenant`), a subscription's `kind` and `start`, and a job's `scope` (`system`, `tenant`) and `misfire` (`coalesce`, `catch-up`). Re-record each Manifest baseline (`./gradlew updateWowManifestBaseline`) and regenerate TypeScript clients from it. A hand-written Manifest or contract fixture must use the new spelling: an entry with an unknown value is left out when read.
- A command constraint violation's `code` is the constraint's name in lower-kebab: `NotBlank` is `not-blank`, `PositiveOrZero` is `positive-or-zero`.
- CoSec's `wow.creation` attribute is lower-kebab too, so update a policy condition that matches it.
- The failure ledger's values on the ops routes, in the ops service and console, and in the generic ops webhook are lower-kebab: an entry's `status` (`scheduled`, `exhausted`, `unrecoverable`, `resolved`), `recoverable` (`recoverable`, `unrecoverable`, `unknown`), `request` and `requested`, an action's or a resolution's `outcome` (`resumed`, `skipped`, `resolved`, `not-parked`), and the webhook's `event` (`parked`, `exhausted`). A webhook receiver that matches on `event` must change. The ledger's storage columns keep their spelling.

Pre-release data is not carried: JSON that Wow stored in the old spelling, such as a snapshot's `schedules` with its `misfire`, is not read in the new one. Nodes and services exchange these values too (cluster replies, wait signals, peer calls' results and contracts), so a deployment that mixes nodes or services from both sides of this change is not supported: upgrade them together. HTTP methods, storage columns and a domain's own enumerations keep their spelling, and so does the query protocol for now.

A target pinned to an earlier V10 pre-release also changed two request rules:

- `If-Match` takes entity tags only (RFC 9110). A bare `3` is 400 `wow.request.malformed`; send `"3"`, or the `ETag` a state read answered (`"3-<digest>"`), which is taken by its version. Tags naming two different versions are 400, since a command takes one expected version. A weak tag or a tag that names no version is 412, and so is `*` on a command that always creates its aggregate. On any other command `*` adds no condition.
- An aggregate ref whose id the server has not chosen yet has `"id": null`, in a command result and wherever else a ref is written. `(none)` is never an id: a ref that names it is refused, and so is a command sent to the id `(none)`. Client code that compared a result's `ref.id` with `(none)` checks for null instead. In TypeScript only a refused result can name no id: `RefusedResult`, whose `ref` is a `CommandRef` (`id: string | null`), is the type of `CommandRejectedError.result` and of `WowProblemError.result`. A `CommandResult` still names its id.

The query protocol is lower-kebab too, in requests, in the descriptor (`GET` of a query route) and in the problems. Readers accept nothing else, so a client, a saved view and a dataset document that spell the old values must change:

| What | Before | After |
|---|---|---|
| A filter's `op` | `"EQ"`, `"NOT_IN"`, `"AND"`, `"ELEMENT_MATCH"`, `"MATCH_ALL"` | `"eq"`, `"not-in"`, `"and"`, `"element-match"`, `"match-all"` |
| A sort's `direction`, a page's `total` | `"ASC"`, `"EXACT"` | `"asc"`, `"exact"` |
| A deletion filter's `state` | `"ACTIVE"`, `"ALL"` | `"active"`, `"all"` |
| An aggregation's `type` | `"TERMS"`, `"DATE_HISTOGRAM"`, `"DISTINCT_COUNT"`, `"METRIC_REF"` | `"terms"`, `"date-histogram"`, `"distinct-count"`, `"metric-ref"` |
| Its `function`, `unit`, `part` | `"SUM"`, `"DAY"`, `"DAY_OF_WEEK"` | `"sum"`, `"day"`, `"day-of-week"` |
| A violation `code` | `"UNKNOWN_FIELD"` | `"unknown-field"` |
| The descriptor's `model`, a field's `types`, `kind` and `semantic.type` | `"SNAPSHOT"`, `"STRING"`, `"SCALAR"`, `"TEMPORAL_EPOCH"` | `"snapshot"`, `"string"`, `"scalar"`, `"temporal-epoch"` |
| A dataset document's `types` and `sensitive.level` | `"DECIMAL"`, `"CONFIDENTIAL"` | `"decimal"`, `"confidential"` |

A custom query model or value type is spelled the same way, lower-kebab like the built-ins (`[a-z][a-z0-9]*(-[a-z0-9]+)*`): a dataset or schema configuration that names `CUSTOM_MODEL` or `"types": ["STRING"]` is refused when it is read, with the spelling it takes (`custom-model`, `["string"]`). A domain's own values are unchanged: a filter's `value`, an enum field's values in the descriptor, and a JDK `timeUnit` are written as before. In TypeScript the wow-client DSL members keep their names (`FilterOperator.NOT_IN`) and only their values change; code that compared a DSL value with a literal compares it with the member instead.

The TypeScript command client takes Kotlin's name and model (D356). Regenerate the clients, then move hand-written code:

| Before | After |
|---|---|
| `new CommandGateway(options, manifest)` | `new CommandClient(options, { manifest })`; without a Manifest it fetches the service's once (`GET /wow/manifest`) |
| `resolveRejected: true`, then reading `result.status` and `result.error` | `try`/`catch`: a rejected command rejects with a `CommandRejectedError` (`code`, `result`, `problem`); anything else with a `WowProblemError` |
| A `WowProblemError` whose `result.status` is `rejected` | A `CommandRejectedError`, its `problem` set when the service answered one |
| `wait: [wait.committed()]`, `wait: 'committed'` | `wait: WaitPlan.COMMITTED`, `WaitPlan.of(wait.committed())`, `parseWaitPlan('committed')`; `.within(ms)` for its timeout, `timeout` still overriding it |
| A request's `routes` | The client's Manifest; a generated client carries its context's routes (`manifest.ts`) |
| A request's `bodyNumbers` | `new CommandClient(options, { bodyNumbers: { '<context>.<aggregate>.<Command>': marks } })` |
| `ResolvingRejected<O>`, the overloads that answer `CommandResult \| RefusedResult` | One signature: `Promise<CommandResult>` |
| A generated client's `.gateway` | `.commands` on `<Context>Client`, `.client` on `<Aggregate>CommandClient` |

```ts
import { CommandRejectedError } from '@ahoo-wang/wow-client';

try {
  await orders.payOrder('order-1', { amount: '39.80', paymentId: 'pay-1' });
} catch (error) {
  if (!(error instanceof CommandRejectedError)) throw error;
  console.warn(error.code, error.result.error?.message);
}
```

## Tests

Rewrite `AggregateSpec<A, S>({ on { ... } })` to the functional DSL on the generated model: `given` events, `whenCommand` with an optional `scope`, request-fact `headers` (9.x `whenCommand(command, header, ownerId)`) and `operator`, then `expectEvents`, `expectRejected(code)`, `expectState`, `expectScope`. Keep every 9.x scenario; add one for each new rejection code, for the kernel's scope guard where an ownership policy was removed, and for each upgrade function. 9.x `givenState(state, version)` stays `givenState`; `expectError` by exception type is `expectError<T>()`, and an error code is `expectRejected(code)`. 9.x `SagaSpec` becomes `sagaSpec(<saga>Handlers(services…))) { whenEvent(stateEvent(…)) { expectCommand… } }`.

**Behaviour change: consecutive `whenCommand` calls chain.** In 9.x, sibling `whenCommand(…) { … }` calls inside one `givenEvent(…) { … }`, `givenState(…) { … }` or `on { … }` block each started from that given: they were independent scenarios. In V10 the spec is one sequence, so each `whenCommand` runs on the aggregate the previous one left. A 9.x spec rewritten call for call compiles and then fails, or worse passes against the wrong state. Rewrite every group of sibling `whenCommand` calls on one given into `fork(…)` branches after the given, one fork per 9.x sibling, named after it; keep a `whenCommand` unforked only when it is the given's single command, or when the 9.x code nested it in the previous command's `fork`, which already chained. Separate 9.x `on { … }` blocks are separate specs: one `aggregateSpec` (or one top-level `fork`) each.

```kotlin
// 9.x: both commands start from CartItemAdded
on {
    givenEvent(CartItemAdded("apple")) {
        whenCommand(RemoveCartItem(setOf("apple"))) { expectEventType(CartItemRemoved::class) }
        whenCommand(AddCartItem("apple")) { expectEventType(CartQuantityChanged::class) }
    }
}

// V10: the siblings are forks of the given
aggregateSpec(model) {
    given(CartItemAdded("apple"))
    fork("remove") { whenCommand(RemoveCartItem(setOf("apple"))) { expectEventTypes(CartItemRemoved::class) } }
    fork("add again") { whenCommand(AddCartItem("apple")) { expectEventTypes(CartQuantityChanged::class) } }
}
```

Forks and test trees (decision D245):

| 9.x | V10 |
|---|---|
| `ref("name")` on a verified stage | `ref("name")` in the spec: marks the current point |
| `fork("name") { … }` after `expect…` (9.x `VerifiedStage.fork`) | `fork("name") { … }` inside the `whenCommand(…) { … }` checks, or `fork(name, from = "ref") { … }` in the spec |
| A fork replayed the steps before it | A fork starts on a copy of everything the spec holds (log, given state, timers, reservations, ids, clock): nothing before it runs again, so a mocked domain service is not called again for the prefix and its call counts and state stay as the prefix left them. Rewrite assertions that counted the replayed calls |
| `AggregateSpec` as a JUnit test factory, one test per step and fork | `@TestFactory fun x() = aggregateSpecTests(model) { … }` returns `Stream<DynamicNode>`: each step a test, each fork a container, a failing fork leaves the others running. `aggregateSpec(model) { … }` stays one test. `sagaSpecTests`, `projectionSpecTests` and `reactorSpecTests` do the same for processors, which fork too (9.x's `SagaSpec` could not) |

```kotlin
class CartSpec {
    private val model = cartModel(ExampleContext.NAME)

    @Test
    fun `a customer should not add to another customer's cart`() = aggregateSpec(model, id = CUSTOMER) {
        whenCommand(AddCartItem("apple"), scope = Scope("mallory")) { expectRejected(FrameworkCodes.AGGREGATE_FORBIDDEN) }
    }
}
```

## Queries

9.x queried snapshots through `SnapshotQueryService`/`SnapshotQueryGateway<S>` (`wow-query`), with Reactor results and typed `MaterializedSnapshot<S>`. V10 has one query engine for every model (decisions D126, D235):

| 9.x | V10 |
|---|---|
| `me.ahoo.wow.query.dsl.singleQuery { condition { … } }`, `listQuery`, `pagedQuery`, `condition { }` | `me.ahoo.wow.api.query.dsl.singleQuery { filter { … } }`, `listQuery`, `pagedQuery`, `cursorQuery`, `countQuery`, `aggregation` in `wow-query-api`; the 9.x `Condition` protocol is gone (decision D125): every `condition { }` becomes `filter { }` (`FilterExpression`) |
| `.query(snapshotQueryGateway)` returning `Mono<MaterializedSnapshot<S>>`, `.toState()` | The aggregate type's `QueryGateway` (`me.ahoo.wow.query`, in `wow-query`): `suspend single(query, caller)`, `list(…)` as a `Flow`, `paged`, `cursor`, `count`, `aggregate`. Records are `ObjectNode`s (the state under `state`); read a typed state with `WowJson.mapper.treeToValue(record["state"], OrderState::class.java)` |
| A gateway bean injected per aggregate | The host's `HttpQueries` bean (`wow-http`): `snapshots(type)` and `events(type)` give the type's gateway, null when no backend serves it. Look it up lazily (`ObjectProvider<HttpQueries>`), as the V10 view store does (`platform/view-store`, `QueriedBoardReferences`) |
| The Reactor `ContextView` that carried the caller | An explicit `QueryCaller`: the HTTP edge passes the request's; in-process code passes `QueryCaller.IN_PROCESS`, which is not scoped to a tenant, so filter the tenant (`tenantId(…)`) and owner yourself |
| A snapshot on the memory backend | Queried, from the in-memory reference backend, unless `wow.query.memory.enabled` is `false`; PostgreSQL and MongoDB snapshots are queried in place |

Snapshot record fields (`SnapshotProperties`): `tenantId`, `aggregateId`, `version`, `ownerId`, `spaceId` (null when unset, 9.x wrote empty strings), `deleted`, `tags`, `firstEventTime`, `lastEventTime` (9.x `eventTime`), `operator` and `firstOperator` as `{id, tenantId, crossScope}` (9.x strings: filter `operator.id`), `schedules` and `state`. 9.x `eventId`, `snapshotTime`, `contextName` and `aggregateName` have no record field; a client that read them changes. The compiler generates a `<State>Properties` object (`OrderStateProperties.STATE__STATUS`) for each state when the module has `wow-query-api` on its class path.

A `SnapshotQueryBackend`, `QueryService` or query SPI implementation of 9.x has no one-to-one port: V10 backends implement `QueryBackend` of `wow-query` and hand out each aggregate type's binding through `QueryBindings` (a store's `StoreQueries` facet); ask before porting one.

## Request headers

V10 takes a command's request id and space from `Wow-Request-Id` and `Wow-Space` alone ([REST clients](#rest-clients)). The 9.x request-header aliases (`IdentityHeaderAliases`: CoSec's `CoSec-Request-Id` and `CoSec-Space-Id`, read when `Command-Request-Id` or `Wow-Space-Id` was absent) are not carried over (09 §17 G-13): a gateway in front of the service rewrites an alias to `Wow-Request-Id` or `Wow-Space`. Report every alias a client sends.

## Compensation and failed executions

The 9.x compensation service (`wow-compensation-*` and its dashboard) is retired, not ported: a V10 node keeps its own failure ledger (decisions D14, D141). A subscription handler that fails retries in process (`@Retry`, `wow.subscription.retry.*`), then parks its aggregate; a parked entry is retried on the long horizon by `wow.subscription.parked.*` (per subscription `wow.subscriptions.[<context>.<processor.function>].parked.max-retries`, `initial-backoff`, `timeout`, the 9.x retry spec's `maxRetries`, `minBackoff`, `executionTimeout`), and an operator acts on it:

- HTTP, with `wow.http.ops.enabled: true`: `GET /wow/ops/parked?subscription=&after=&limit=` (`subscription` as `<context>.<processor.function>`), one entry at `GET /wow/ops/parked/{subscription}/{context}/{aggregate}/{tenant}/{id}`, and `POST` of `{entry}/resume` (the body's `{"failedAt": …}` optional), `{entry}/skip` (`{"failedAt": …}` required, the entry's `failedAt`; a parking replaced since is 409 `wow.ledger.stale`), `{entry}/mark-recoverable` and `{entry}/apply-retry-spec` (9.x `MarkRecoverable` and `ApplyRetrySpec`).
- Authorization: each action is authorized by the host's `CommandAuthorizer` (CoSec's, with `wow.cosec.authorizer.enabled`) as a system command on the parked aggregate, and reads as `wow.ledger.Read`; without an authorizer every action and read is refused.
- Notifications: `wow.ops.webhook.url`, `format` (`generic` or `wecom`), `events` (`parked`, `exhausted`) replace the 9.x compensation notifications.
- A console across services: the V10 ops service (`platform/ops`) reads each service's ledger, which a service publishes by itself once the ops service is deployed on the same Kafka (`wow.ops.publish.enabled`, `auto` by default).

The 9.x executions still unresolved are imported by `wow-migrate-data` through the map's `subscriptions` (see [Preconditions and gates](#preconditions-and-gates)); a 9.x execution of a processor the map does not list is not imported, so list every processor that still has one, or record the decision to drop them with the user.

## Peer contexts

A context that sends commands to, or reads the published streams of, another context needs that context's contract. In V10 (decision D234) the contract travels in the other context's **api jar** (`META-INF/wow/contract.json`, which the Gradle plugin assembles from the domain module that declares the api module in `api(project(…))`): depend on the peer's `*-api` artifact and nothing else. There is no `wowPeers` configuration, peer Manifest file or service name to configure; a target older than D234 needs it, so check. A 9.x service that called another service's commands through `wow-apiclient` or a hand-written client keeps its transport choice ([Clients of other services](#clients-of-other-services)). Commands to a peer's aggregates go through Kafka (`wow.<target>.commands`): from a saga as derived commands, from any other code through the `CommandClient` (review M-1, see [REST clients](#rest-clients)); a saga may also call synchronously with `SagaContext.call` (decision D193), declaring each called class in `@OnEvent(calls = [SubmitOrder::class])` (decision D259): the Manifest lists the call, `call` refuses an undeclared class, and the node does not start when a called command is its own context's or no Manifest decides it, or when its context has no URL: the call goes to `wow.peers.<context>.url` (or `wow.peers.<context>.url`), which must be set.

## Clients of other services

`wow-apiclient` (CoApi interfaces of a service's routes) is replaced by `wow-client` (`me.ahoo.wow.client`, decisions D257, D259): the same `CommandClient` and `QueryApi` as in process, over HTTP to a peer, named from the contract its `*-api` jar carries (decision D234): depend on the peer's `*-api` and write no path. With the Spring Boot host, list the peer under `wow.peers` and the host's `CommandClient` and `QueryApi` beans reach it over HTTP; without it, build one with `WowClient.builder()`. TypeScript clients are generated from the V10 Manifest (`wow-generator`, rewritten with the `wow-client` Skill). Report each 9.x `@CoApi` interface and what replaces it.

| 9.x `wow-apiclient` | V10 |
|---|---|
| `ReactiveRestCommandGateway`, `SyncRestCommandGateway` (`@CoApi @LoadBalanced`) | The host's `CommandClient` bean, the peer listed in `wow.peers.<context>`; Java: `JavaCommandClient.of(commandClient)` |
| `RestCommandGateway.send(CommandRequest(body, …))` | `commandClient.of(caller).wait(command, id)`, the type from the peer's contract; `send(…)` answers at ACCEPTED, `stream(…)` per hop |
| `CommandRequest.waitStage` / `waitContext` / `waitProcessor` / `waitTimeout` | `CommandRequest.of(command, id, wait = WaitPlan.…, timeout = …)` (`Wow-Wait`, `Wow-Wait-Timeout`); Java: `CommandRequest.builder(command).wait(…).timeout(Duration)` |
| `CommandRequest.tenantId`, `ownerId`, `spaceId` | The caller (`of(caller)`): in service mode its tenant and scope are asserted as the root context (`Wow-Call`); in user mode the end user's forwarded credential decides them; `targetTenant`/`targetScope` for a cross-scope caller |
| `CommandRequest.aggregateId`, `aggregateVersion` | `CommandRequest.id`, `expectedVersion` (`If-Match`) |
| `CommandRequest.requestId` | `CommandRequest.requestId`; a new one otherwise, kept by every retry |
| `CommandRequest.localFirst`, `context`, `aggregate`, `type` | Removed: the command's class names the route, its type derived from the peer's contract unless `CommandRequest.type` sets it |
| `CommandRequest.serviceUri`, `@LoadBalanced` | `wow.peers.<context>.public-url` (public routes, user mode) or `.url` (internal port, service mode; `wow.peers.<context>.url` too), or an `EndpointResolver` bean that discovers the service |
| `RestCommandGatewayException(errorCode, errorMsg, bindingErrors)` | `CommandRejectedException` (`code`, `result`) or `WowProblemException` (`code`, `status`, `retryHint`, `result`) from problem+json; 429 and codes that keep the request id are retried, never a 504 (`wow.peer.retry.*`) |
| `ReactiveSnapshotQueryApi<S>` / `SyncSnapshotQueryApi<S>` per aggregate (`@CoApi(baseUrl) @HttpExchange("<agg>")`) | `queryApi.of(caller).snapshots<<State>View>()`: rows typed by the state view the peer's api generates (`snapshots<OrderStateView>()`); a query with a projection reads through `dynamic<ObjectNode>()`, its paths the contract's field constants; `snapshots(PeerContract.Aggregate.TYPE)` reads rows of `ObjectNode`; Java: `JavaQueryApi.of(queryApi)` |
| `single`, `list`, `paged`, `count`, `aggregate` returning `MaterializedSnapshot<S>` | The same queries returning `Snapshot<S>`; `cursor`, and `stream` that resumes on its own (`Last-Event-ID`, decision D253) |
| `*State` methods returning `S` | `Snapshot<S>.state`; one aggregate's latest state: `state(id)` (null when missing or not visible) |
| `dynamic*` methods returning `Map` | `dynamic<Map<String, Any?>>()` or `dynamic<ObjectNode>()` |
| `IListQuery.queryState(api)` | `api.list(query).map { it.state }`, `query` a `ListQuery` |
| `ISingleQuery`, `IListQuery`, `IPagedQuery`, `ICursorQuery`, `ICountQuery`, `IPagedList` as parameter, return or implemented types | The classes `SingleQuery`, `ListQuery`, `PagedQuery`, `CursorQuery`, `CountQuery`, `PagedList` (built with `of` or the DSL); code generic over queries takes `Queryable<Q>` or `FilterCapable<Q>`. There is nothing to implement: the interfaces are gone |
| `ListQuery(limit = 0)`, every match on the query gateway | `limit` left out takes the entry's default list size, and `0` is refused; every match is `queryApi.trusted().unbounded()` or `stream` |
| An aggregation cut at its `limit` in key order | A `sort` for a top-N; without one, more groups than `limit` fail with `TOO_MANY_GROUPS` |
| `Pagination.offset()` as an `Int` | A `Long` |
| `@EnableCoApi(clients = […])` | Nothing to declare: the host creates the clients; `wow.peer.mode` (`service`, default, or `user`) and `wow.peer.forwarded-headers` |

Service mode, the default, calls the peer's internal port as this service (its `wow.calls.token`), asserting the caller; the peer must trust the service (`wow.callers.<service>.tokens`) and may narrow what it reads (`.reads`). User mode calls the peer's public routes with the end user's credential (the request's `Authorization` by default). A saga's synchronous call is `SagaContext.call` over the same service mode.

## State caches (9.x `wow-cocache`)

9.x `wow-cocache` gave each reader the parts to build its own cache of another context's state: a CoCache cache it declared, a refresher it registered on the state events, and a source it loaded misses from. V10's `wow-cocache` (decision D364) keeps one shared cache per aggregate type, written by the context that owns the aggregate and read by every service with nothing declared: the docs page Guide › Share State through CoCache. The cache of each reader is replaced, not ported; report each one and what replaces it.

| 9.x `wow-cocache` | V10 |
|---|---|
| `SetStateCacheRefresher` / `EvictStateCacheRefresher` registered on an aggregate's state events, in the reader | The owner lists the aggregate: `wow.cocache.publish: [<context>.<aggregate>]`; its sink subscription writes each commit's state at its version, only over an older one. Delete the refreshers |
| An evicted key on a deleted aggregate (`evict`) | A tombstone at the deletion's version, which no older state undoes |
| `StateToCacheDataConverter` (state → cached data) | The generated `<State>View` of the owner's api is the cached value; there is no converter. A reader that needs fewer fields reads them off the view |
| `QueryApiCacheSource`, `QueryGatewayCacheSource`, `StateCacheSource` (load on a miss) | Nothing to write: a miss reads the owner through the node's typed queries (`snapshots<V>()`), in process or over HTTP as this service, a batch as one `IN` query per tenant |
| `LoadCacheSourceConfiguration.timeout` | CoCache's load policy: `cocache.caches.[wow.<context>.<aggregate>].load-async-timeout` |
| `ttl` / `ttlAmplitude` (`TtlConfiguration`) | `cocache.caches.[wow.<context>.<aggregate>].ttl` and `ttl-jitter` on each side; `wow.cocache.ttl` for what the owner writes |
| A `@CoCache` interface or `CacheSpec` per reader, often in a `<context>-cache` module | Inject `StateViewCache<OrderStateView>` (from `wow-cocache-api`, no dependency); delete the declaration and the module |
| A cache keyed by the aggregate id (`String`) | `StateKey(tenantId, id)`: a key holds the tenant |
| Cache names and Redis keys of the 9.x caches | New names, `wow.<context>.<aggregate>`: the V10 caches start empty and fill from the owner's writes and the readers' misses; nothing in Redis is migrated |
| `refreshWindow` on a state cache | None: the caches are versioned, and CoCache refuses an early refresh on them |

A reader that used the cached state to decide a command now asks the owner instead: the cache is a trusted read, and a check that must be consistent belongs to the aggregate it is about ([Peer contexts](#peer-contexts)).

## JSON

9.x `JsonSerializer` (`me.ahoo.wow.serialization`) became `WowJson.mapper` (`me.ahoo.wow.spi.json.WowJson`, in `wow-spi`, which the kernel brings): `toJsonString()`, `toObject()` and `toJsonNode()` become `WowJson.mapper.writeValueAsString`, `readValue`, `readTree`, `treeToValue`. It is configured differently, and stored and sent data depends on it (decisions D48, D122):

- It registers the Kotlin module only and never discovers modules on the class path: a 9.x Jackson module registered by SPI (joda-money, a custom `Module`) is not applied. Give such a type its own `@JsonSerialize`/`@JsonDeserialize` or `@JsonValue`/`@JsonCreator`, or a plain field type, and cover it with a round-trip test; how the Manifest schema describes such a type was not verified here.
- 9.x read and wrote fields of any visibility; V10 uses Jackson's default visibility, so a property without a public getter (a Java field with neither getter nor annotation) is left out of events, state and snapshots. Check each event and state class.
- Unknown properties are ignored, and decimals read into untyped values stay `BigDecimal`.

## Datasets

9.x `me.ahoo.wow.query.schema.QueryDataset` is `me.ahoo.wow.query.dataset.QueryDataset` in V10 `wow-query`, an `@ExperimentalWowApi` (opt in with `@OptIn(ExperimentalWowApi::class)`): named by an `AggregateType` (`QueryDataset.nameOf("<context>.<dataset>")`) instead of a `MaterializedNamedAggregate`, declared with `QueryDataset.of(name) { … }` or a dataset file (`QueryDatasetFiles`: `<config directory>/wow/dataset/`, then `META-INF/wow/dataset/` on the class path). Its scope names the tenant column or declares the dataset platform-level. Datasets are served under `/wow/dataset` ([Routing](#routing)); a 9.x dataset backend other than ClickHouse needs a `QueryBindings<QueryDataset>` bean.

## Custom message functions

A 9.x `MessageFunction` written by hand or registered at runtime (a function registrar entry, an `EventProcessor` built without annotations) has no V10 counterpart: subscriptions come from annotated processors, which the compiler turns into `ProcessorBinding`s and the Manifest's `subscriptions`. Rewrite each as a `@Projection`, `@Reactor` or `@Saga` class ([Sagas, projections, reactors](#sagas-projections-reactors)); a handler set only known at runtime is a question for the user.

## Java domains

A domain written in Java migrates like a Kotlin one, within these limits (decision D229 G-8). Code outside the domain calls commands and queries from Java through the facade of `wow-client`: `JavaCommandClient.of(commandClient)` and `JavaQueryApi.of(queryApi)`, each call blocking or `…Async` with a `CompletableFuture`, lists as `List`, streams as `java.util.stream.Stream`, timeouts as `java.time.Duration` (decisions D257, D260; never call the blocking forms on a Netty or WebFlux thread).

- The module applies `kotlin("jvm")` beside `id("me.ahoo.wow")`, even with Java sources only: KSP runs in the Kotlin compilation and the generated models are Kotlin. The plugin fails a module that applies it without the Kotlin JVM plugin.
- Decide functions are plain Java methods returning an event or a `Decision` built with the statics `Decision.events(…)`, `Decision.reject(code, message)` and `Decision.NoOp.INSTANCE`; `@OnCommand(produces = …)` names the events of a `Decision`.
- `@ErrorCode` goes on a `public static final String` field initialized with a string literal.
- The `suspend` functions of the API cannot be called from Java: `DecideScope.reserve` (a decide function that reserves keys is Kotlin) and `SagaContext.call` (a Java saga derives commands instead).
- The test DSL is Kotlin only: the domain's tests are Kotlin (`aggregateSpec`, `sagaSpec`) over the generated models. 9.x Java tests on `AggregateVerifier` are rewritten in Kotlin.
- State and event classes need public getters, or record components, to be serialized ([JSON](#json)).

## Known gaps

9.x constructs the example port could not carry over. Report each one the application uses, with the decision taken; never emulate one with a compatibility shim.

| 9.x | Status on the V10 target |
|---|---|
| `@AggregateRoute(resourceName)`, custom `@CommandRoute` actions, methods, path and header variables | No equivalent; routes are fixed. Clients and gateway rules change. |
| `@VoidCommand` | No equivalent; dropped. |
| `@AggregateOwner(OwnerPolicy.*)`, `@Spaced` | `Ownership.OWNER` or `TENANT` per aggregate, `idSource = AggregateIdSource.OWNER` for `AGGREGATE_ID`; scope from the principal plus explicit checks. |
| An event implementing `OwnerTransferred`, `AggregateDeleted`, `AggregateRecovered`, `SpaceTransferred` or `ResourceTagsApplied`; a decide function for a lifecycle command | A command of the aggregate that produces the domain event and the lifecycle event in one commit; a decide function never takes a lifecycle command, which the kernel decides ([Ownership and owner moves](#ownership-and-owner-moves)). |
| A starter's own routes (derived ids, replay, "unchanged" answers, path-scoped reserved owners) | Transformers, snapshot queries, `NoOp`, request-id deduplication and a `CallerResolver` ([Routes and handlers of a 9.x starter](#routes-and-handlers-of-a-9x-starter)). |
| `bindingErrors` of a domain exception | No place in a rejection; said in its message. |
| `CommandValidator.validate()` | No V10 counterpart in the domain API; Jakarta constraints on the command, checked at admission, or a decide rejection. The example's China-only address rule was dropped. |
| `@Order`, swagger `@Tag`/`@Schema` | No equivalent; dropped. |
| `@EntityObject`, `@ValueObject`, `Identifier` | Removed. |
| `ErrorInfo` on events | Plain data. |
| Private `onSourcing`; events without one; `lateinit` state | Must be rewritten (see [State classes](#state-classes)). |
| Test DSL artifact | `wow-test` (`:test-dsl`), the V10 test DSL (`aggregateSpec`, `sagaSpec`); the eShop's domain tests use it. |
| A Maven build | Not supported (decision D239 ②): move the build to Gradle or stay on 9.x. |
| Java tests (`AggregateVerifier`), Java calls of `suspend` APIs (`DecideScope.reserve`, `SagaContext.call`) | Kotlin only until 10.x ([Java domains](#java-domains)). |
| Request-header aliases (`CoSec-Request-Id`, `CoSec-Space-Id`) | Dropped (G-13); a gateway rewrites them ([Request headers](#request-headers)). |
| `wow-apiclient` | Dropped (D187); TypeScript clients are generated, JVM callers write their own ([Clients of other services](#clients-of-other-services)). |
| Jackson modules discovered on the class path, any-visibility fields | Not applied by `WowJson` ([JSON](#json)). |
| State-event resend and per-version re-dispatch | None until 10.x (G-9); parked entries are resumed instead. |
| A `MessageFunction` registered at runtime | No counterpart; an annotated processor ([Custom message functions](#custom-message-functions)). |

Gaps the port reported that later V10 work closed, so do not report them as missing: qualifying an injected service (`@Name` on the parameter), declaring `produces` for functions returning `Decision` (`@OnCommand(produces)`), declaring rejection codes in the Manifest (`@ErrorCode`), mounted commands (`@MountedCommand` with a `CommandTransformer`, D182; the 9.x `CommandBuilderRewriter` maps to the transformer too), ids in decide (`DecideScope.ids`, D171), `wow-models` (ported, with `Tree`, D187), tags derived from state (`@Tags`, D220), deleted events (`retiredEvents`, D219), `@Blocking` on command functions (D227), saga, projection and reactor bindings (generated, D149), `PrepareKey` data (imported by `wow-migrate-data`, D227), the query field annotations (`wow-query-api`), the query, events, dataset and trace routes (D127, D235), the application id as a request fact (D232, D236), the failure ledger with its operator routes and the import of unresolved 9.x executions (D141, D229 G-1), peer contracts in api jars (D234), and forks and test trees in the test DSL (D245). Verify each against the pinned target.

## Completion

Report, besides the general completion items: the pinned 9.x source and V10 target, every changed file, every `TODO(wow-migrate)` and question left for the user, every `migration-map.json` entry, every reserved key with its namespace and tenancy and whether the user confirmed it, every retired event, every dropped construct with its replacement or none, every route and header change clients must follow, the compile and domain-test results under the V10 compiler, and the data migration state, which this rewrite alone never proves.
