---
title: Migrate the Source
description: Rewrite a 9.x service's Kotlin source for V10 with the wow-migrate Skill, what each 9.x construct becomes, what the migration map records, and what the Skill cannot do for you.
---

# Migrate the Source

A 9.x service's source is rewritten for V10, not upgraded: annotations lose their 9.x attributes, command bodies lose their identity, exceptions become rejections, and processors become subscriptions. The `wow-migrate` agent Skill does the rewrite with you and records every change of name and shape in `migration-map.json`, which the data tool reads next ([Migrate the Data](./data)). This page is what the rewrite does; the Skill's reference, `skills/wow-migrate/references/v9-to-v10.md`, is the complete map it follows.

## Before you start

- **The estate first.** List the estate and its API dependency graph, cycles included, before any code, and rewrite in its order: shared libraries, then api modules, then domains, then servers, a service others depend on before its consumers ([Cut Over](./cutover#find-the-estate)).
- **The last 9.x minor.** V10 migrates from it only. Upgrade within 9.x first.
- **Gradle, JDK 21, Kotlin with KSP.** V10 binds models and the Manifest at build time, in Gradle. A 9.x service built with Maven moves to Gradle as part of the migration, or stays on 9.x.
- **Pin the target.** Name the exact V10 version, and verify every annotation, signature, route and key against it.
- **Source is rewritten, data is converted.** Never edit stored events to fit the new source: the map and the data tool do that.

## Run the Skill

Install the `ahoo-wow-skills` plugin from the Ahoo Skills Hub, or copy `skills/wow-migrate` of the pinned Wow tag into your agent's Skills directory, then ask the agent to migrate the service, naming the 9.x and V10 versions. It works in stages, each with its own gate: the build, the source, the map, then the runtime against real HTTP requests. Where it cannot decide a rewrite, the uniqueness of a key or the owner of a resource, it asks, or leaves a `TODO(wow-migrate)` in code that does not compile; it never guesses a rewrite into code that compiles.

The rewrite is accepted when the service compiles under the V10 compiler and its domain tests pass. That proves the source, not the data: the data is proved when every rebuilt aggregate matches its 9.x snapshot.

## The build

| 9.x | V10 |
| --- | --- |
| `wow-spring-boot-starter` with capability variants | `wow-spring-boot` plus one module per technology: `wow-postgres`, `wow-mongo`, `wow-kafka`, `wow-http`, `wow-cosec` |
| `wow-core`, `wow-spring`, `wow-webflux`, `wow-metadata`, `wow-schema` | `wow-kernel` and `wow-runtime`, `wow-http`, `wow-manifest` |
| `ksp("me.ahoo.wow:wow-compiler")` by hand | The Gradle plugins by role: `id("me.ahoo.wow")` on a domain module (KSP), `id("me.ahoo.wow.api")` on its api module, `id("me.ahoo.wow.service")` on the server (the Manifest tasks) |
| `wow-query` in domain code | `wow-query-api`: the query types, the Kotlin DSL and the field annotations |
| `ISingleQuery`, `IListQuery`, `IPagedQuery`, `ICursorQuery`, `ICountQuery`, `IPagedList` | The classes `SingleQuery`, `ListQuery`, `PagedQuery`, `CursorQuery`, `CountQuery`, `PagedList`, built with their `of` factories or the DSL; code generic over queries takes `Queryable<Q>` or `FilterCapable<Q>` |
| A query built with a DSL class's constructor, `ListQueryDsl().apply { … }.build()` | The entry function, `listQuery { … }` (and `pagedQuery`, `singleQuery`, `cursorQuery`, `countQuery`, `aggregation`, `filter`, `projection`, `sort`, `pagination`): a DSL class's constructor and `build()` are internal. An extension function on a DSL class, `fun ListQueryDsl.mine()`, still works |
| `ListQuery(limit = 0)`, every match on the query gateway | `limit` left out takes the entry's default list size, and `0` is refused; every match is `queryApi.trusted().unbounded()` or `stream` |
| `CursorQuery` without a `size`, a page of `CursorQuery.DEFAULT_SIZE` (10) | `size` left out takes the entry's default list size (`default-list-size`, 100), at most `max-page-size` (100); `DEFAULT_SIZE` is gone, so name `size = 10` to keep pages of 10 |
| A custom `MaskStrategy`, `mask(value: String)` | `mask(value: SensitiveValue)`: the raw string is `value.value`, beside the field's declared `level`; a lambda reads `it.value` |
| `limit(Int.MAX_VALUE)` for every match | Refused: an in-process list returns at most `max-list-size` (1,000), and a larger limit fails (`size-out-of-range`). A page is capped too, at `max-page-size` (100): a 9.x job that read "the first page of 200" fails on its first run (`In-process page size[200] must be between 1 and 100.`), is retried, and then parks, so it pages by 100 or reads with `unbounded()` ([Queries](../guide/queries#bounds)). Code that must see every match, such as a job, a backfill or a migration step, reads them with `NodeQueryApi.trusted().unbounded()` or streams them; a read for a caller pages instead |
| An aggregation cut at its `limit` in key order | A `sort` for a top-N; without one, more groups than `limit` fail with `wow.query.too-many-groups` (400) |
| `Pagination.offset()` as an `Int` | A `Long` |
| `wow-test` (`AggregateSpec`) | `wow-test`, with the functional `aggregateSpec` and `sagaSpec` |
| `wow-redis`, `wow-bi`, `wow-apiclient`, `wow-compensation-*` | Removed: no Redis backend, the ClickHouse sink, the `CommandClient` and `QueryApi` clients, each node's failure ledger |
| `wow-cocache` | `wow-cocache` of V10: the owning context writes one shared cache per aggregate type, and readers inject `StateViewCache<V>`; a reader's own index and join caches have no counterpart |
| Handlers returning `Mono` or `Flux` | `suspend` functions, or Reactor through `wow-reactor` |

The plugins need Gradle 9 or later and the Kotlin JVM plugin 2.3 or later: upgrade a Gradle 8 wrapper first. KSP reads whole source directories and ignores a source set's `exclude`, so a migration that compiles part of a domain at a time syncs those files into a directory of their own. Such a part is an aggregate with its closure, not an aggregate alone: a state's typed queries (`<State>Properties`) exist only where its root is compiled. Find the closure from the part's imports: every other state's `<State>Properties` it queries by and, in a saga, every command class it sends; add their aggregates to the part, and repeat until nothing is added. When the domain compiles whole again and its source sets point back at `src/`, build it clean once (`./gradlew :<module>:clean build`): Gradle compares sources by their paths under their root, so the switch can leave the Kotlin compile and KSP up to date while their incremental caches still describe the synced directory, and the next change can then hand the processor too few files. Under Wow's plugins neither happens: they make the source roots an input of both tasks, which reruns them in full after a switch. A module that runs KSP without them fails that next build with the compiler's "No Manifest fragment and no model provider" error ([A build fails over contracts](../operate/troubleshooting#a-build-fails-over-contracts)), rather than going green with a model missing; a clean build fixes it.

Each domain module whose api module carries its contract sets `wow { contractPackage = "…" }`, the package of the generated `<Context>Contract` other services import; a staged migration sets it from the first aggregate on, so the package does not move as the others arrive. Without it the module's `generateWowContract` fails and prints the line, with the package the context's command and event classes share then ([The contract code of the api module](../guide/generated-code#the-contract-code-of-the-api-module)). A context's commands and events are all in that one api module: a 9.x project that kept some of them in a second api module, which the first depends on, moves them into it, whether the build fails naming the lines to add or the classes that are not in the module.

A migration that moves the api module first may declare the api plugin on the root build, `id("me.ahoo.wow.api") version "…" apply false`: it brings no KSP, so the 9.x modules keep their own `id("com.google.devtools.ksp") version "…"`. Declaring `me.ahoo.wow` or `me.ahoo.wow.service` there brings the plugin's KSP onto every module's class path, and a 9.x module that still names a KSP version then fails in Gradle, before any Wow code runs:

```text
Error resolving plugin [id: 'com.google.devtools.ksp', version: '2.3.6']
> The request for this plugin could not be satisfied because the plugin is already on the classpath with an unknown version, so compatibility cannot be checked.
```

Drop the version from those modules' line, `id("com.google.devtools.ksp")`, and they run the plugin's KSP; or keep the Wow plugins' versions in `settings.gradle.kts`, as [Add Wow to an Existing Service](../start/existing-project) does, so no module's class path holds a KSP it did not ask for. A domain module that applies `me.ahoo.wow` from a root build declaring the api plugin alone finds no KSP, and fails naming the line to add to it: `id("com.google.devtools.ksp") version` the plugin's KSP version.

[9.x Capabilities in V10](./capabilities) says where every 9.x capability went.

## Annotations

The package stays `me.ahoo.wow.api.annotation`, so kept names need no new import. The V10 compiler reports a removed annotation as an error that names its replacement.

| 9.x | V10 |
| --- | --- |
| `@BoundedContext(name, alias, …, aggregates)` | `@BoundedContext(name, description)`; the context name is part of every command, event and route name, so choose it on purpose |
| `@AggregateRoot(commands = [...])` | `@AggregateRoot(lifecycle, published, publishState, topic, ownership, idSource, snapshot, stateRevision, retiredEvents)`; a mounted command is `@MountedCommand` with a `CommandTransformer`, its class in the domain module of its aggregate, where the compiler finds it |
| `@OnCommand(returns = [...])` | `@OnCommand(produces = [...])`, needed only for a function returning `Decision` |
| `@Event(revision = "0.0.1")` | `@Revision(n)`; a migrated 9.x shape is revision 1 |
| `EventUpgrader` | `@Upgrade(event, from)` on a function from `ObjectNode` to `ObjectNode` |
| `DroppedEvent` | `@AggregateRoot(retiredEvents = ["<stored name>"])` |
| `@StatelessSaga`, `@ProjectionProcessor`, `@EventProcessor` | `@Saga`, `@Projection`, `@Reactor` |
| `@OnEvent(vararg aggregates)`, `@OnStateEvent`, `@OnMessage` | `@OnEvent`, one handler kind taking a `StateEvent` |
| `@Retry(maxRetries, minBackoff, executionTimeout)` | `@Retry(maxAttempts, initialBackoff, maxBackoff, multiplier, timeout)` for the in-process budget; the 9.x values become the parked entry's retries, `wow.subscriptions.[<context>.<processor.function>].parked.*` (`executionTimeout` as `claim-ttl`) |
| `@OnError` | Removed: return a rejection, with the events to record (`reject(code, message, events)`), each declared in the function's `@OnCommand(produces = […])` ([Rejections](../guide/rejections#reject-with-events)), and compensate in a `@Saga`'s `@OnRejected` handler. One that turned the framework's duplicate-id error into a domain message has no V10 form: a creation of an existing aggregate is answered `wow.aggregate.exists` before decide runs, and clients match that code |
| `@PreparableKey` | `DecideScope.reserve(namespace, key, tenancy)` |
| `@AggregateId` on a command property | Kept, with the same meaning: the body names the aggregate, and the command's route has no `{id}`. A field 9.x bound from the path only repeated the route: delete it ([What a command body holds](#what-a-command-body-holds)) |
| `@TenantId`, `@OwnerId`, `@AggregateVersion` on command fields | Removed: the tenant and owner are the caller's, the expected version is `If-Match` |
| `@StaticTenantId` | Host configuration: `wow.aggregates.[<context>.<aggregate>].tenant`; the same in `migrate-data.json`'s `tenants` for the data's check ([Check the map](./data#_2-check-the-map)) |
| `@StaticAggregateId` | Removed: no fixed aggregate id ([9.x Capabilities in V10](./capabilities)) |
| `@AggregateRoute`, `@CommandRoute` | Removed: routes come from the Manifest |
| `@CommandRoute(enabled = false)` | It depends on who sent the command. Only the platform (sagas, timers, the in-process gateway, other services on the internal port): `@InternalCommand` on the command class, and the public port serves no route of it; a service on the internal port that has no entry under `wow.callers` may still send it, since the list narrows only the services it names ([Keep a command internal](../guide/aggregates#keep-a-command-internal)). A hand-written user route of the 9.x service: the command stays public on its generated route ([Routes, tenancy and ownership](#routes-tenancy-and-ownership)) |
| `@AggregateOwner(OwnerPolicy.*)`, `@Spaced` | `@AggregateRoot(ownership = Ownership.OWNER \| TENANT)`; owner and space come from the caller. `OwnerPolicy.AGGREGATE_ID` is `@AggregateRoot(idSource = AggregateIdSource.OWNER)`. A `TENANT` aggregate has no owner to transfer, so its default lifecycle has no `wow.lifecycle.TransferOwner`, and neither does one with `idSource = OWNER` ([Lifecycle commands](../guide/aggregates#lifecycle-commands)) |
| `@VoidCommand`, `@Order`, `@EntityObject`, `@ValueObject` | Removed |
| none | `@ErrorCode` on a rejection code, `@Alias` for a later rename, `@QueryIndex`, `@Access` |

A 9.x read model interface often keeps `@Sensitive` and the `@Query…` annotations on its getters (`@get:Sensitive`), shared by the state and its DTOs. V10 reads them from the members a property overrides, so the state that overrides such a getter carries them: [Annotations a property inherits](../guide/queries#annotations-a-property-inherits). `@Sensitive` cannot be dropped by the override, so a field that was served in clear text because the 9.x state did not repeat it is now masked: read the merged Manifest's `x-wow-sensitive` once.

## What a command body holds

A command body holds business data. The tenant is the caller's, the owner and space are `command.scope`, taken from the authenticated caller, and the expected version comes from `If-Match`: delete the tenant, owner and version fields and the route annotations. The aggregate is `command.ref`, taken from the route, unless the body names it.

A 9.x id field bound from the path (`@CommandRoute.PathVariable` with `@AggregateId`) only repeated the route: delete it and read `command.ref.id`, and the command keeps its route with `{id}`. The eShop's order is created by a command that carries only its items, the id coming from the route (a command left with no fields becomes an `object`):

<!-- sample: examples/eshop/ordering/ordering-api/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/api/order/OrderCommands.kt#submit-order -->

```kotlin
/**
 * Submits an order of [items]. The basket context sends it when its buyer checks out, with the order's id fixed by the
 * basket. The order belongs to the buyer: a saga's command carries the scope of the basket it is derived from, whose
 * owner is its buyer, and the order takes its owner from the command that creates it. An order has
 * items, each valid ([OrderItem]'s constraints): the gateway checks that before the order decides.
 */
@CreateAggregate
@Summary("Submit an order")
data class SubmitOrder(
    @field:NotEmpty @field:Valid val items: List<OrderItem>,
)
```

A 9.x id the body took from its own business data, a field such as `productId` or a getter such as `"$warehouseId-$code"`, keeps `@AggregateId`, with the same name, package and meaning ([Name the aggregate in the body](../guide/aggregates#name-the-aggregate-in-the-body)). The command's route then has no `{id}`, a saga or a client sends it without one, and it needs no `CommandTransformer`. A computed id stays a getter, which the service computes from the fields. The mark goes on the property, `@AggregateId`, not on its getter: a 9.x `@get:AggregateId` does not compile, and a `@get:JsonIgnore` beside it fails the build, since Wow's JSON writes the id:

<!-- sample: fragment -->

```kotlin
data class AdjustStock(val warehouseId: String, val code: String, val delta: Int) {
    @AggregateId
    val stockId: String get() = "$warehouseId-$code"
}
```

A client that sent such a command to a route with the id sends it to the route without one ([HTTP and TypeScript Clients](./http-and-clients#routes)).

A 9.x command with no `@AggregateId` whose `id`, often a 9.x `Identifier.id`, named its aggregate was routed by that id. V10 infers nothing from a property's name, so mark that `id` `@AggregateId`; the compiler's `UnmarkedAggregateId` lists every command that has such an `id` and no mark, wherever the command is compiled, the context's api module included: an error for a command that creates its aggregate, a warning for any other ([Name the aggregate in the body](../guide/aggregates#name-the-aggregate-in-the-body)). When the id is an interface's default getter that the command cannot mark there, an interface of a peer's jar, or one that commands of other aggregates implement too, override it in the command:

<!-- sample: fragment -->

```kotlin
data class CreateShelf(override val warehouseId: String, override val code: String) : ShelfIdSpec {
    @AggregateId
    override val id: String
        get() = super<ShelfIdSpec>.id
}
```

Left unmarked, a creating command sent without an id, by a saga's `body.toCommandRequest()` or by a client, gets a generated id: another aggregate than the one 9.x addressed, and a second creation with the same business key is accepted instead of answered `wow.aggregate.exists`.

In decide, `CommandMessage<X>` is `Command<X>`, `command.aggregateId.id` is `command.ref.id`, `command.ownerId` is `command.scope.ownerId`, a 9.x `CommandResultAccessor` is `DecideScope.putResult`, and an id generated inside decide comes from `scope.ids.next()`.

A query in decide reads the query side, whose snapshots are written after each commit, not with it, as 9.x's were. A write the same flow made a moment before, through a saga, may not be visible yet: a decide function that refuses because a row is missing can be refusing too early. Let the caller retry such a refusal, or have the saga carry the fact the check needs in its command.

## Exceptions become rejections

A `require`, a `check` or a thrown domain exception becomes `Decision.reject(code, message)`, with the code declared once by `@ErrorCode` beside the aggregate. Its HTTP status defaults to 422; a 9.x code whose status clients rely on keeps it with `status`:

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#rejections -->

```kotlin
/**
 * The codes the aggregate rejects a command with, by their local names: the compiler and the kernel name them
 * `<context>.order.<code>`, as its peers read them from the api's generated contract.
 */
object Rejections {
    @ErrorCode(description = "The order's status does not allow the command.", status = 409)
    const val WRONG_STATUS = "wrong-status"

    @ErrorCode(description = "A payment pays the order's total amount.")
    const val WRONG_AMOUNT = "wrong-amount"
}
```

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#submit -->

```kotlin
fun onCommand(command: SubmitOrder): OrderSubmitted = OrderSubmitted(command.items)
```

A rejection carries a code and a message: 9.x `bindingErrors` go into the message, and the clients that read them change. A 9.x `CommandValidator`, or a `require` on the command's own fields, becomes a Jakarta constraint on the command class, as `SubmitOrder`'s `@field:NotEmpty @field:Valid` items above: the gateway checks it at admission and answers `wow.command.invalid` (400) with the violations. A rule that needs the state stays a rejection.

## Events and state

- **Names.** A V10 event is `<context>.<aggregate>.<Name>`; 9.x used snake case (`order_created`). Do not keep the 9.x spelling with `@Name`: map it in the migration map.
- **Revisions.** An event whose V10 shape differs from what 9.x stored (an identity field removed, a computed property dropped) gets a higher `@Revision` and an `@Upgrade` function from revision 1, which a test covers with a real 9.x payload.
- **State.** `onSourcing` functions are not private, every event a command produces has one, even an empty one, and every property has a value once the creation event is applied: a `lateinit` its `onSourcing` assigns is fine. A handler 9.x found while private, an `@OnCommand`, `@OnSourcing` or `@OnEvent` function, is a compile error in V10, as a Kotlin `protected` one is: make it `internal`. Only a private function bound by its name alone, without the annotation, is ignored. A 9.x `StateAggregateTagsExtractor`, and the tags 9.x applied, become access attributes of the state ([Tags and attribute-based access](#tags-and-attribute-based-access)).
- **Properties with no setter.** A 9.x state, event or command that holds a collection or map in a `val` it fills as it goes (`val items = sortedSetOf<…>()`, a `MutableList` or `MutableMap` `val`), or a value in a `val` of its body, reads back as 9.x read it: Wow's JSON writes such a property's field (`ALLOW_FINAL_FIELDS_AS_MUTATORS`, which 9.x's JSON turned on too and Jackson 3 leaves off). A computed property, a getter with no field behind it, is written and not read.
- **Date-times with an offset.** An `OffsetDateTime` or a `ZonedDateTime` that Wow's JSON reads (a state rebuilt from its log or its snapshot, an event, a query result) keeps the offset it was written with: `"2026-01-02T03:04:05+08:00"` reads as `+08:00`. 9.x read it at UTC, `2026-01-01T19:04:05Z`, the same instant. Code that compares such a value with `==` against one at another offset now sees them differ: compare instants with `isEqual` or `toInstant()`. A `ZonedDateTime` is written with its offset, not its region's name, so it reads back in that fixed offset.
- **A sourcing function that stamps the clock.** A 9.x `onSourcing` that set a time from the clock (`System.currentTimeMillis()`, `LocalDateTime.now()`) takes the event's time instead: a field of the event, or its commit's `commit.commitTime`. A spec fails one that reads the clock, every run ([Testing](../guide/testing#expect)).
- **Deleted events** are retired: delete the class and its sourcing function, list the stored name in `retiredEvents`, and map it with `"retire": true`.
- **Wow types in events and state.** A stored `Condition`, `Sort` or `Projection` (a saved filter, say) has no V10 form: the `Condition` protocol is gone, the kernel's JSON has no codec for the query protocol, and its enumerations are lower case now. Store a type of your own, and upgrade the 9.x JSON into it with an `@Upgrade` function and a test. The build refuses an event or a state that still holds a type of the query protocol (`me.ahoo.wow.api.query`), naming the path to each.
- **The event's envelope.** A 9.x sourcing function that took `DomainEvent<E>` to read `createTime` or the operator takes the event and, second, the commit it belongs to (`commit: EventStream`): `createTime` is `commit.commitTime`, `header.operator` is `commit.operator?.id`, `version` is `commit.version` ([Read the commit](../guide/aggregates#read-the-commit)). The data migration imports each 9.x stream's create time and operator as its commit's, so states rebuilt from 9.x history read what 9.x recorded. A command function that read the system clock, or took a `Clock` to read it, reads `scope.now` instead: the decision's time in epoch milliseconds (`LocalDateTime.ofInstant(Instant.ofEpochMilli(scope.now), zone)`). A shared helper whose parameter defaults to `LocalDate.now()` or `LocalDateTime.now()` still reads the system clock, so pass it the decision's date. A test sets the time with `aggregateSpec(…, start = instant)` and `advanceTime` instead of injecting a clock.

## Processors

A saga, a projection or a reactor carries exactly one of `@Saga`, `@Projection`, `@Reactor`; each handler is its own subscription named `processor.function`. A handler takes the commit's `StateEvent` (or one event of it, `StateTypedEvent<S, E>`), and a saga returns the commands it derives as `CommandRequest`s, which the runtime turns into commands with deterministic ids:

<!-- sample: examples/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#derive -->

```kotlin
@OnEvent
fun onOrderSubmitted(
    event: StateTypedEvent<OrderState, OrderSubmitted>
): CommandRequest<AwaitStockValidation> = AwaitStockValidation.toCommandRequest(event.ref.id)
```

A saga that handled a 9.x `onError` gains an `@OnRejected` handler for a rejected derived command. One that did nothing about it, since 9.x logged a failed derived command and went on, is ported one of two ways, decided per command. An `@OnRejected` handler that accepts the rejection by returning `Unit` keeps 9.x's behaviour. Leaving the handler out is V10's default: the rejection of a command to the saga's own context parks the saga's source aggregate until it is resumed ([Handle a rejection](../guide/sagas#handle-a-rejection)). A compensation is not compensated: a command that an `@OnRejected` or `@OnCommitted` handler derives and that is rejected in turn parks the aggregate too, and is handed to no handler, so a multi-step handover derives each step that needs a compensation from an `@OnEvent` handler. A request id the 9.x saga set on a derived command for deduplication is not the command's: a derived command's request id comes from the execution, its target and type, and the request's `requestId` only as a business key within that execution ([Build the commands](../guide/sagas#build-the-commands)). A saga that read the tags of 9.x's read-only state aggregate reads the access attributes of `event.state`, the state after the commit, not the snapshot's. A processor of another context's events reads that context's published stream, through its `*-api` jar ([Published Streams](../guide/published-streams)).

A periodic job a 9.x service ran in Simba or `@Scheduled` is an `@OnSchedule` handler of a saga, on a cron rule, when it derives commands from what is due; a job that waits for each command's outcome before the next stays outside, sending through the `CommandClient` ([Scheduled Commands](../guide/scheduling)). A 9.x backfill that re-dispatched stored events to one handler is a handler of a new name with `@OnEvent(start = OnEvent.Start.BEGINNING)`, which reads the stream once from its start ([Rebuild and Replay](../operate/replay)).

### Scheduled jobs {#scheduled-jobs}

Four differences come with every 9.x job ported to `@OnSchedule`, three of them silent:

- **The time zone.** A 9.x scheduler read its cron in the server's zone. A V10 job reads it in UTC unless it names a zone, `@OnSchedule(timeZone = "Asia/Shanghai")` or `wow.schedules.<name>.time-zone`, and never in the server's. A daily job ported as it stood runs at the same hour in UTC, moved by the zone's offset, with no message.
- **Every tenant.** A 9.x job that looped over the tenants has no one-line port. A `SYSTEM` job, the default, runs once, in the default tenant: `context.queries` read that tenant, `context.queries.trusted()` reads every tenant, and a command for another tenant names it with `targetTenant`. A `TENANT` job runs once per tenant, but only for a tenant that has enabled it, with `wow.schedule.EnableJob` in that tenant (`POST /<context>/wow.schedule/<job>/enable-job`): the host enables no tenant's instance, so each existing tenant is enabled when the job is ported, and each new one when it is created.
- **No run-now.** A job cannot be run by hand: its instances take enable, reschedule and disable, and nothing ticks one but its rule. A 9.x endpoint that ran a job on demand stays code of the service's own, which sends the commands the job would.
- **A rate from configuration.** A V10 job runs on a cron rule alone, with no fixed rate or delay. A 9.x rate held in a property becomes the job's cron under `wow.schedules.<name>.cron`, such as `0 */5 * * * *` for every five minutes.

A 9.x scheduler that sent a command and waited for its processing before the next has no form as a job. A job derives its commands and waits for none: a derived request that names a wait or a timeout fails the handler for good, and the job's instance parks. The supported shapes:

- **Commands to another context:** the job calls each in turn, `context.call(request)`, which returns once that context has committed it, declared in `@Sends(calls = [...])`. A call to the job's own context refuses the start.
- **Commands to its own context, or a wait beyond the commit:** the scheduler stays outside the service, in one process that sends each command with a `CommandClient`'s `wait` and sends the next after its result. Run it in one place: Wow gives such a scheduler no lock, so one inside the service's nodes runs on every node.


## Reserved keys

A 9.x `PrepareKey` becomes a reservation taken in the decide function; the kernel confirms it once the events are appended. A 9.x key was global, a V10 key is unique per aggregate type and tenant unless reserved with `ReservationTenancy.SYSTEM`, so decide the tenancy of every key, and write the same namespace and tenancy into the map's `reservations`. A 9.x key that aggregates of several types shared becomes an aggregate of its own that holds the keys ([Unique Keys](../guide/unique-keys#a-key-unique-across-aggregate-types)). A brand's name, unique within its tenant:

<!-- sample: examples/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/brand/Brand.kt#take -->

```kotlin
/** Takes [name] for this brand, or rejects the command when another brand holds it. */
private suspend fun take(name: String, scope: DecideScope) {
    if (!scope.reserve(CatalogNames.BRAND_NAMES, CatalogNames.key(name))) {
        reject(Rejections.NAME_TAKEN, "Another brand is named \"${name.trim()}\".")
    }
}
```

A 9.x `reprepare` that renamed a key of one aggregate is one decision of that aggregate: reserve the new key and release the old one. One that moved a key from one aggregate to another has no single call in V10, since a reservation belongs to the aggregate that decides and commits with its events. It becomes one of two patterns ([Move a key to another aggregate](../guide/unique-keys#move-a-key-to-another-aggregate)): a [handover through a saga](../guide/unique-keys#handover-through-a-saga), eventually consistent, when the change may lose to a concurrent request; or the key held as [an aggregate of its own](../guide/unique-keys#key-held-as-an-aggregate), a slot whose one command changes the holder in one commit, as 9.x `reprepare` did, when the key must have a holder at every moment.

[Unique Keys](../guide/unique-keys) explains reservations.

## Routes, tenancy and ownership

Routes are derived from the Manifest: `POST /<context>/<aggregate>/<id>/<command>`, no tenant or owner prefixes, no custom actions or methods. A fixed tenant is host configuration, and a 9.x resource kept under one shared owner for a whole tenant is `Ownership.TENANT`, and one whose id is its owner's id (`OwnerPolicy.AGGREGATE_ID`, a user's cart) is `idSource = AggregateIdSource.OWNER`, served by no id as the caller's own, with no ownership check left in its command functions ([One aggregate per owner](../guide/ownership#one-aggregate-per-owner)). Only `wow.lifecycle.TransferOwner` moves an owner: a 9.x event that implemented `OwnerTransferred` becomes a domain event and a saga that derives the transfer. A 9.x starter's own routes are rewritten by what they did, with transformers, snapshot queries and `Decision.NoOp` ([HTTP and TypeScript Clients](./http-and-clients) lists every route and header).

An aggregate id is held to one rule ([Aggregate ids](../reference/http#aggregate-ids)), which 9.x did not have: V10 refuses an id holding `/`, `;` or `\`, or longer than 256 characters percent-encoded as UTF-8, beside a control character, white space at either end, `.` and `..`. A 9.x service that builds an id with such a separator, a computed `"$warehouseId/$sku"` say, builds it with another (`-`) before the cut-over, and a creation's generator gives none of them. The instances already stored with such an id are the data's to decide: `migrate-data check` lists each, and the team corrects the id in the 9.x store or leaves the instance out of the import ([Migrate the Data](./data#_2-check-the-map)).

A command with `@CommandRoute(enabled = false)` had no generated route in 9.x, for one of two reasons. Find out which before you port it. If only the service's own processes or other services sent it, mark it `@InternalCommand`. If a hand-written route of the 9.x service sent it on a user's request, it is a user's command, and it stays public: its generated route replaces the hand-written one. A `CommandTransformer` does what the hand-written route worked out, such as an id derived from the caller and the body, and the `Wow-Scope` header names another scope the caller may act in. The 9.x view store's `SetViewPreferences`, `SetDatasetWording`, `ClearDatasetWording` and `ClaimView` are of the second kind. Hand-written routes sent them, so they stay public in 10. The first three go on their generated routes, and transformers derive their ids; `ClaimView` goes with `Wow-Scope` naming the shared owner.

## Tags and attribute-based access

V10 has no tags. What an aggregate's tags said about who may read it becomes its **access attributes**, members of its state marked [`@Access`](../reference/annotations#access), and the service's **access policy** compares them with the caller's claims ([Ownership and Access](../guide/ownership#declare-access-attributes)).

- **Tags the state derived.** A 9.x `StateAggregateTagsExtractor` becomes one `@Access` member per key: a property, or a function of the state without parameters, of the key's type (a `String`, a number, a `Boolean` or an enum, or a `Set` of them for a key of several values). V10 derives each on every commit, as 9.x derived the tags.
- **Tags a command applied.** What 9.x's `ApplyResourceTags` set is data: a field of the state marked `@Access`, which a domain command and event of the aggregate's own set (`TagOrder`, `OrderTagged`), so the domain checks who may change it and the log records it. The event's payload names each field as its attribute, and its sourcing function replaces all of them, an absent one with none, as 9.x replaced the whole map. The 9.x route `PUT …/{id}/tags` is that command's route now.
- **A key both applied and derived** had in 9.x what the extractor returned after each commit, which had the last word: it is an `@Access` function, and the values the tags event set are left out.
- **A key no policy reads** is left out, with the reason in the map: leaving it out changes who may read the data.
- **ABAC rules.** 9.x's `abac.*` matching becomes the aggregate's access policy, a bean of its state's type. Each tag key a rule matched is a comparison of its attribute with the caller's claim of that name, and the keys are all of them (`allOf`):

<!-- sample: fragment -->

```kotlin
@Bean
fun orderAccess(): AccessPolicy<OrderState> = accessPolicy {
    read { caller ->
        allOf(
            OrderStateAccess.REGION isIn caller.claims("region"),
            OrderStateAccess.TEAMS containsAny caller.claims("teams"),
        )
    }
    write { caller -> ownerAndSpace(caller) }
}
```

A claim is the caller's principal attribute of the same name, prefix included: an attribute 9.x matched as `abac.region` is the claim `abac.region`, unless the identity provider names it `region`, as this sample assumes. A policy replaces the owner and space check for its aggregate, for reads and commands alike, so restate it where 9.x relied on it, with `ownerAndSpace(caller)` as `write` does here ([Write an access policy](../guide/ownership#write-an-access-policy)). Three things 9.x allowed by default are denied in V10:

- A caller without `abac.*` attributes read every aggregate. A comparison with a claim the caller does not hold denies: give such callers a rule of their own (`allowIf(caller.hasRole("auditor"))`).
- An aggregate without a key was public to that key's rule. One without a value of the attribute matches no comparison: say in the policy what is public (`OrderStateAccess.REGION.isNull()` in an `anyOf`, or `allowAll()`).
- A stream written before 9.x recorded tags was read by every caller. A stream not yet stamped with the access attributes, as every imported stream is until the access walk completes, shows to no caller under a policy ([Migrate the Data](./data#_7-the-access-attributes)).

The migration map maps the 9.x tags event to the domain event with `"tags": true`, and each tag key to its attribute ([The map](../reference/migrate-data#tags)).

## The migration map

Every change of a command's or event's name, context, revision or shape is an entry of `migration-map.json`, format 1:

<!-- sample: fragment -->

```json
{
  "format": 1,
  "headers": { "app_id": "app_id" },
  "aggregates": [
    {
      "from": "example-service.order",
      "to": "example.order",
      "commands": [{ "from": "create_order", "to": "CreateOrder" }],
      "events": [
        { "from": "order_created", "fromRevision": "0.0.1", "to": "OrderCreated", "toRevision": 1, "upgrade": "upgradeOrderCreatedFrom1" },
        { "from": "order_archived", "to": "OrderArchived", "retire": true }
      ],
      "reservations": [{ "from": "order_no", "namespace": "order.number", "tenancy": "TENANT" }]
    }
  ]
}
```

Map every event and command the 9.x code can produce, not only those its store holds today: 9.x keeps writing until the cut-over, and an event it first stores then stops the import. A renamed state property goes under the aggregate's `"state": {"fields": …}`, so the comparison reads it. The data tool checks the map against the 9.x store and the V10 Manifest before it writes anything ([Migrate the Data](./data)).

## Tests {#tests}

`AggregateSpec<A, S>` becomes the functional `aggregateSpec` over the generated model: `given` events, `whenCommand`, then `expectEvents`, `expectRejected(code)` and `expectState`. Keep every 9.x scenario, and add one for each new rejection code, each upgrade function, and the scope guard where an ownership policy was removed ([Testing](../guide/testing)).

::: warning Consecutive `whenCommand` calls chain
In 9.x, sibling `whenCommand` calls inside one `on { … }` or `givenEvent(…) { … }` block each started from that given: they were independent scenarios. In V10 a spec is one sequence, and each `whenCommand` runs on the aggregate the previous one left. A 9.x spec rewritten call for call compiles, then runs its later scenarios on the wrong state. Rewrite each group of siblings into `fork(…) { whenCommand(…) { … } }` branches after the given, one per sibling ([Fork scenarios](../guide/testing#fork-scenarios-that-share-a-beginning)).
:::

### Aggregate specs {#aggregate-specs}

| 9.x | V10 |
| --- | --- |
| `class XSpec : AggregateSpec<A, S>({ on { … } })`, a `@TestFactory` | `@Test fun x() = aggregateSpec(model) { … }`, one test; `@TestFactory fun x() = aggregateSpecTests(model) { … }`, a test per step and fork |
| `aggregateVerifier<A, S>()`, its chained stages (from Kotlin or Java) | One `aggregateSpec`: `.whenCommand(c).expect…().verify()` is `whenCommand(c) { expect… }`; `.then()` and `.then(false)` are the next `whenCommand`, since a spec chains and a rejection changes nothing; `.fork { … }` is `fork(name) { … }` inside the checks. The test DSL is Kotlin only |
| `inject { register(service) }` | The services the generated model factory takes (`orderModel(name, inventory)`, the context name first, with a default, so services passed alone go by the factory's parameter names: each is named after its service's type, `inventoryService` for an `InventoryService`, not after a 9.x registration's name or the command function's parameter, and two services of one type are told apart by `@DependencyName` ([Test Domain Code](../guide/testing#arrange-and-act))); `fork(name, model = …)` for a branch whose service answers otherwise |
| `inject { register(clock) }`, a `Clock` the command function takes | `aggregateSpec(model, start = instant)`: `DecideScope.now` reads the spec's clock, which starts at `start`, and `advanceTime(duration)` moves it |
| `name("…")` | The test's name: the `@Test` function's, and in `aggregateSpecTests` each step's and fork's |
| `givenOwnerId(…)`, `givenSpaceId(…)` | `scope = Scope(owner, space)` on `given`, `givenState` or `whenCommand` |
| `givenEvent(event) { … }`, `givenEvent(arrayOf(…)) { … }` | `given(vararg events)`, a step of its own; `givenState(state, version)` stays, and `givenState(stateAggregate)` is `givenState(state, version, scope)` |
| `whenCommand(command, header, ownerId, spaceId)` | `whenCommand(command, requestId, expectedVersion, scope, headers, operator) { … }` |
| Sibling `whenCommand` calls on one given | `fork(…)` branches (above) |
| `ref("name")`, `fork("name") { … }` on a verified stage | `ref("name")` in the spec, `fork(name, from = "name") { … }`; or `fork { … }` inside the checks |
| `.verify()`, `.verify(false)` | Nothing to call: the checks run as their block ends, and the first that fails ends it. 9.x's collected failures have no form; in `aggregateSpecTests` each step fails its own test while the others run |
| A verified stage's `stateRoot`, `stateAggregate`, `verifiedResult` | `expectState { … }` for the state; `expectVersion`, `expectScope`, `expectDeleted` for the aggregate; `error` and `results` in the checks for the outcome |
| Command validation through `TestValidator` | Removed: validation is the admission's `CommandValidator`, which the kernel and the spec do not pass through; test it at the HTTP layer, or validate the command in a domain test with a Bean Validation provider added to the tests and configured as 9.x's was ([Command constraints](../guide/testing#command-constraints)) |
| `TestGlobalIdGeneratorFactory` | Nothing to set: `DecideScope.ids` gives sequential ids in a spec |

### Aggregate expectations {#aggregate-expectations}

| 9.x | V10 |
| --- | --- |
| `expectNoError()` | `expectNoError()` |
| `expect { … }` over the 9.x `ExpectedResult` | The expectations of this table; `error` and `results` for what they do not cover |
| `expectError()`, `expectError { … }`, `expectErrorType(X::class)` | `expectError<X> { … }`, for an exception the decide function throws; `expectRejected(code)` for a rejection it returns or throws (`reject`, `require`, `check`) |
| `expectEventType(A::class, B::class)` | `expectEventTypes(A::class, B::class)` |
| `expectEventCount(n)` | `expectEventCount(n)` |
| `expectEvent<E> { … }`, `expectEventBody<E> { … }`: the first event of that type | `expectEvent<E> { … }`: exactly one event, an `E`; `expectEvents(…)` for several, by value |
| `expectEventStream { … }`, `expectEventIterator { … }` | `expectEvents(…)` or `expectEventTypes(…)` for the whole stream; `expectEventEnvelope<E> { … }` for one event with its envelope |
| An event's `aggregateId`, `version`, `commandId`, `requestId`, `header` | `expectEventEnvelope<E> { aggregateId; version; commandId; requestId; headers }` |
| An event's operator and create time | `expectEventEnvelope<E> { operator; commitTime }`, for a command that committed one event |
| `expectState { … }`, `expectState(Consumer)` | `expectState { … }` or `expectState(expected)` |
| Every `Consumer` and `Class` overload | Removed: the test DSL is Kotlin only, and a Java domain is tested with it ([Write the Domain in Java](../guide/java#test-it)). A `Consumer` overload beside each lambda would make Kotlin's calls ambiguous |
| `expectStateAggregate { aggregateId }` | The spec's `id` |
| `expectStateAggregate { version }` | `expectVersion(n)`: the committed version, or the version the aggregate stayed at when the command appended nothing |
| `expectStateAggregate { deleted }` | `expectDeleted()` |
| `expectStateAggregate { ownerId; spaceId }`, the tags | `expectScope(scope)`, `expectAccess(attributes)`: the access attributes, by name |

Smaller differences: `expectEvent` and the saga's `expectCommand` now require exactly one, where 9.x took the first match; a command that commits nothing is `expectUnchanged()`.

### Saga specs {#saga-specs}

| 9.x | V10 |
| --- | --- |
| `class XSpec : SagaSpec<T>({ on { … } })`, `sagaVerifier<T>()` | `sagaSpec(<saga>Handlers(…)) { … }`, or `sagaSpecTests` for a tree |
| `inject { … }` | The handlers' dependencies, given to the generated handlers factory; `givenQueries(queries)` for the reads |
| The command gateway and message factory | Removed: `answerCalls { … }` answers the synchronous calls |
| `functionFilter { … }`, `functionName("onX")` | `whenEvent(event, handler = "onX")` |
| `whenEvent(event)`, `whenEvent(event, state, ownerId, spaceId)` | `givenAggregate(model, id, history…)`, then `whenEvent(events, by = …)` for the saga's own context; `whenEvent(stateEvent(ref, state, events…, scope = …))` for a state no model of the module sources; a peer context's event: `publishedEvent(name, body)` |
| `expectCommandType(X::class)` | `expectCommandTypes(X::class)` |
| `expectCommandCount(n)` | `expectCommandCount(n)` |
| `expectCommandBody<C> { … }`: the first command's body | `expectCommand<C> { … }` or `expectCommand(body, to = ref)`: exactly one command, and its body; `expectCommands(…)` for several |
| `expectCommand<C> { … }`: the first command's message, its aggregate id and header | `expectCommandEnvelope<C> { … }`: exactly one command, with its `ref`, ids, scope, request facts and operator beside its `body` |
| `expectCommandStream`, `expectCommandIterator` | `commands`, `expectCommands(…)`, `expectCommandTypes(…)` |
| `expectNoCommand()`, `expectNoError()`, `expectError…` | The same names; `expectError<X>()` as on the aggregate |
| `expect { … }`, `.verify()` | The expectations of this table; `commands`, `calls`, `handled` and `error` for the rest. A step's checks are optional |

New in V10, with no 9.x counterpart:

- `whenRejected` and `whenCommitted` for the receipts a saga handles, and `answerCalls` and `expectCalls` for its synchronous calls;
- `whenRedelivered` and `whenDeliveredLate`, which hand a commit over again or late, as a subscription may;
- `expectRetried` and `expectParked`, which say what the runtime does with a handling that failed;
- `expectDeterministic`, a saga's only, and `expectHandledBy`;
- forks and test trees for processors, which 9.x's `SagaSpec` had neither of;
- `givenQueries` over snapshots in memory (`wow-test-query`'s `givenSnapshots`), the schedule expectations, and `projectionSpec`, `reactorSpec` and `scheduleSpec`, each with its `Tests` twin.

[Test Domain Code](../guide/testing) describes each.

## What the Skill cannot do

Some 9.x constructs have no V10 form. The Skill reports each one the service uses, with the decision taken; it never emulates one:

- custom routes, methods and path or header variables: clients and gateway rules change;
- `bindingErrors`, `@VoidCommand`, `@Order`, swagger annotations;
- a 9.x Redis event store: the data tool reads MongoDB only;
- Java tests on `AggregateVerifier`, and Java calls of `suspend` APIs (`DecideScope.reserve`, `SagaContext.call`): Kotlin only;
- Jackson modules found on the class path, and fields without public getters: `WowJson` applies neither;
- the host's Jackson settings: Wow's JSON is its own mapper, so `spring.jackson.*`, a Jackson customizer bean and an `ObjectMapper` or `JsonMapper` bean reach none of what Wow reads and writes, a command's body included. A 9.x service that read enum values case-insensitively through its Spring configuration refuses `"paid"` for `PAID`; a property that should accept any case says so itself, `@field:JsonFormat(with = [JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_VALUES])` (on the enum class it has no effect);
- a client that sent a value of another JSON kind than its property's, `"7"` for a number, `1` for a `String`, `1.5` for an integer: 9.x's JSON coerced it, Wow's refuses it as `wow.command.malformed`, naming the property ([JSON Values](../reference/json-values)). The client sends the form the Manifest's schema gives;
- a client that sent a property the command does not have, a form object posted whole, say, or a misspelled optional property: 9.x's JSON ignored it, and a typo took the property's default; Wow refuses it as `wow.command.malformed`, violation `unknown-property` naming its path. Send the command's own members, which the Manifest's schema lists; a computed property Wow writes of a command is read as known. So the nodes of a service are upgraded before the clients that send a property they do not know. Events, snapshots and cluster messages keep ignoring one, so an older payload still reads;
- state-event resend and per-version re-dispatch: none until 10.x; a parked entry is retried instead;
- a `MessageFunction` registered at run time: rewrite it as an annotated processor.

Next: the [configuration](./configuration), the [HTTP and TypeScript clients](./http-and-clients), then the [data](./data) and the [cut-over](./cutover).
