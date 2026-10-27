---
title: Migrate the Source
description: Rewrite a 9.x service's Kotlin source for V10 with the wow-migrate Skill, what each 9.x construct becomes, what the migration map records, and what the Skill cannot do for you.
---

# Migrate the Source

A 9.x service's source is rewritten for V10, not upgraded: annotations lose their 9.x attributes, command bodies lose their identity, exceptions become rejections, and processors become subscriptions. The `wow-migrate` agent Skill does the rewrite with you and records every change of name and shape in `migration-map.json`, which the data tool reads next ([Migrate the Data](./data)). This page is what the rewrite does; the Skill's reference, `skills/wow-migrate/references/v9-to-v10.md`, is the complete map it follows.

## Before you start

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
| `ksp("me.ahoo.wow:wow-compiler")` by hand | The Gradle plugin `id("me.ahoo.wow")`, which applies KSP and the Manifest tasks |
| `wow-query` in domain code | `wow-query-api`: the query types, the Kotlin DSL and the field annotations |
| `ISingleQuery`, `IListQuery`, `IPagedQuery`, `ICursorQuery`, `ICountQuery`, `IPagedList` | The classes `SingleQuery`, `ListQuery`, `PagedQuery`, `CursorQuery`, `CountQuery`, `PagedList`, built with their `of` factories or the DSL; code generic over queries takes `Queryable<Q>` or `FilterCapable<Q>` |
| `ListQuery(limit = 0)`, every match on the query gateway | `limit` left out takes the entry's default list size, and `0` is refused; every match is `queryApi.trusted().unbounded()` or `stream` |
| An aggregation cut at its `limit` in key order | A `sort` for a top-N; without one, more groups than `limit` fail with `TOO_MANY_GROUPS` |
| `Pagination.offset()` as an `Int` | A `Long` |
| `wow-test` (`AggregateSpec`) | `wow-test`, with the functional `aggregateSpec` and `sagaSpec` |
| `wow-redis`, `wow-bi`, `wow-apiclient`, `wow-cocache`, `wow-compensation-*` | Removed: no Redis backend, the ClickHouse sink, the `CommandClient` and `QueryApi` clients, each node's failure ledger |
| Handlers returning `Mono` or `Flux` | `suspend` functions, or Reactor through `wow-reactor` |

[9.x Capabilities in V10](./capabilities) says where every 9.x capability went.

## Annotations

The package stays `me.ahoo.wow.api.annotation`, so kept names need no new import. The V10 compiler reports a removed annotation as an error that names its replacement.

| 9.x | V10 |
| --- | --- |
| `@BoundedContext(name, alias, …, aggregates)` | `@BoundedContext(name, description)`; the context name is part of every command, event and route name, so choose it on purpose |
| `@AggregateRoot(commands = [...])` | `@AggregateRoot(lifecycle, published, publishState, topic, ownership, retiredEvents)`; a mounted command is `@MountedCommand` with a `CommandTransformer` |
| `@OnCommand(returns = [...])` | `@OnCommand(produces = [...])`, needed only for a function returning `Decision` |
| `@Event(revision = "0.0.1")` | `@Revision(n)`; a migrated 9.x shape is revision 1 |
| `EventUpgrader` | `@Upgrade(event, from)` on a function from `ObjectNode` to `ObjectNode` |
| `DroppedEvent` | `@AggregateRoot(retiredEvents = ["<stored name>"])` |
| `@StatelessSaga`, `@ProjectionProcessor`, `@EventProcessor` | `@Saga`, `@Projection`, `@Reactor` |
| `@OnEvent(vararg aggregates)`, `@OnStateEvent`, `@OnMessage` | `@OnEvent`, one handler kind taking a `StateEvent` |
| `@Retry(maxRetries, minBackoff, executionTimeout)` | `@Retry(maxAttempts, initialBackoff, maxBackoff, multiplier, timeout)` for the in-process budget; the 9.x values become the parked entry's retries, `wow.subscriptions.[<processor.function>].parked.*` (`executionTimeout` as `timeout`) |
| `@OnError` | Removed: return a rejection, compensate in a `@Saga` |
| `@PreparableKey` | `DecideScope.reserve(namespace, key, tenancy)` |
| `@AggregateId`, `@TenantId`, `@OwnerId`, `@AggregateVersion` on command fields | Removed: the identity is the command's `ref`, the scope the caller's |
| `@StaticTenantId`, `@StaticAggregateId` | Host configuration: `wow.aggregates.[<context>.<aggregate>].tenant` |
| `@AggregateRoute`, `@CommandRoute` | Removed: routes come from the Manifest |
| `@AggregateOwner(OwnerPolicy.*)`, `@Spaced` | `@AggregateRoot(ownership = Ownership.OWNER \| TENANT)`; owner and space come from the caller |
| `@VoidCommand`, `@Order`, `@EntityObject`, `@ValueObject` | Removed |
| none | `@ErrorCode` on a rejection code, `@Alias` for a later rename, `@QueryIndex`, `@Tags` |

## Commands carry no identity

A command body holds business data only. The aggregate is `command.ref`, taken from the route; the owner and space are `command.scope`, taken from the authenticated caller. Delete the identity fields and the route annotations; what is left is the business data. The eShop's order is created by a command that carries only its items, the id coming from the route (a command left with no fields becomes an `object`):

<!-- sample: example/eshop/ordering/ordering-api/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/api/order/OrderCommands.kt#submit-order -->

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

In decide, `CommandMessage<X>` is `Command<X>`, `command.aggregateId.id` is `command.ref.id`, `command.ownerId` is `command.scope.ownerId`, a 9.x `CommandResultAccessor` is `DecideScope.putResult`, and an id generated inside decide comes from `scope.ids.next()`.

## Exceptions become rejections

A `require`, a `check` or a thrown domain exception becomes `Decision.reject(code, message)`, with the code declared once by `@ErrorCode` beside the aggregate. Its HTTP status defaults to 422; a 9.x code whose status clients rely on keeps it with `status`:

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#rejections -->

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

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/Order.kt#submit -->

```kotlin
fun onCommand(command: SubmitOrder): OrderSubmitted = OrderSubmitted(command.items)
```

A rejection carries a code and a message: 9.x `bindingErrors` go into the message, and the clients that read them change. A 9.x `CommandValidator`, or a `require` on the command's own fields, becomes a Jakarta constraint on the command class, as `SubmitOrder`'s `@field:NotEmpty @field:Valid` items above: the gateway checks it at admission and answers `wow.command.invalid` (400) with the violations. A rule that needs the state stays a rejection.

## Events and state

- **Names.** A V10 event is `<context>.<aggregate>.<Name>`; 9.x used snake case (`order_created`). Do not keep the 9.x spelling with `@Name`: map it in the migration map.
- **Revisions.** An event whose V10 shape differs from what 9.x stored (an identity field removed, a computed property dropped) gets a higher `@Revision` and an `@Upgrade` function from revision 1, which a test covers with a real 9.x payload.
- **State.** `onSourcing` functions are not private, every event a command produces has one, even an empty one, and every property has a value before creation (no `lateinit`). A 9.x `StateAggregateTagsExtractor` is a `@Tags` member of the state.
- **Deleted events** are retired: delete the class and its sourcing function, list the stored name in `retiredEvents`, and map it with `"retire": true`.

## Processors

A saga, a projection or a reactor carries exactly one of `@Saga`, `@Projection`, `@Reactor`; each handler is its own subscription named `processor.function`. A handler takes the commit's `StateEvent` (or one event of it, `StateTypedEvent<S, E>`), and a saga returns the commands it derives, built with `context.command(…)` so their ids are deterministic:

<!-- sample: example/eshop/ordering/ordering-domain/src/main/kotlin/me/ahoo/wow/example/eshop/ordering/domain/order/OrderProcessSaga.kt#derive -->

```kotlin
@OnEvent
fun onOrderSubmitted(
    event: StateTypedEvent<OrderState, OrderSubmitted>,
    context: SagaContext,
): Command<AwaitStockValidation> = context.command(event.ref.id, AwaitStockValidation)
```

A saga that handled a 9.x `onError` gains an `@OnRejected` handler for a rejected derived command; without one, the rejection parks the aggregate. A processor of another context's events reads that context's published stream, through its `*-api` jar ([Published Streams](../guide/published-streams)).

## Reserved keys

A 9.x `PrepareKey` becomes a reservation taken in the decide function; the kernel confirms it once the events are appended. A 9.x key was global, a V10 key is unique per aggregate type and tenant unless reserved with `ReservationTenancy.SYSTEM`, so decide the tenancy of every key, and write the same namespace and tenancy into the map's `reservations`. A 9.x key that aggregates of several types shared becomes an aggregate of its own that holds the keys ([Unique Keys](../guide/unique-keys#a-key-unique-across-aggregate-types)). A brand's name, unique within its tenant:

<!-- sample: example/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/brand/Brand.kt#take -->

```kotlin
/** Takes [name] for this brand, or rejects the command when another brand holds it. */
private suspend fun take(name: String, scope: DecideScope) {
    if (!scope.reserve(CatalogNames.BRAND_NAMES, CatalogNames.key(name))) {
        reject(Rejections.NAME_TAKEN, "Another brand is named \"${name.trim()}\".")
    }
}
```

[Unique Keys](../guide/unique-keys) explains reservations.

## Routes, tenancy and ownership

Routes are derived from the Manifest: `POST /<context>/<aggregate>/<id>/<command>`, no tenant or owner prefixes, no custom actions or methods. A fixed tenant is host configuration, and a 9.x resource kept under one shared owner for a whole tenant is `Ownership.TENANT`. Only `wow.lifecycle.TransferOwner` moves an owner: a 9.x event that implemented `OwnerTransferred` becomes a domain event and a saga that derives the transfer. A 9.x starter's own routes are rewritten by what they did, with transformers, snapshot queries and `Decision.NoOp` ([HTTP and TypeScript Clients](./http-and-clients) lists every route and header).

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
| `aggregateVerifier<A, S>()`, the Java entry | Removed: the test DSL is Kotlin only |
| `inject { register(service) }` | The services the generated model factory takes (`orderModel(name, inventory)`); `fork(name, model = …)` for a branch whose service answers otherwise |
| `givenOwnerId(…)`, `givenSpaceId(…)` | `scope = Scope(owner, space)` on `given`, `givenState` or `whenCommand` |
| `givenEvent(event) { … }`, `givenEvent(arrayOf(…)) { … }` | `given(vararg events)`, a step of its own; `givenState(state, version)` stays |
| `whenCommand(command, header, ownerId, spaceId)` | `whenCommand(command, requestId, expectedVersion, scope, headers, operator) { … }` |
| Sibling `whenCommand` calls on one given | `fork(…)` branches (above) |
| `ref("name")`, `fork("name") { … }` on a verified stage | `ref("name")` in the spec, `fork(name, from = "name") { … }`; or `fork { … }` inside the checks |
| Command validation through `TestValidator` | Removed: validation is the admission's `CommandValidator`, which the kernel and the spec do not pass through; test it at the HTTP layer |
| `TestGlobalIdGeneratorFactory` | Nothing to set: `DecideScope.ids` gives sequential ids in a spec |

### Aggregate expectations {#aggregate-expectations}

| 9.x | V10 |
| --- | --- |
| `expectNoError()` | `expectNoError()` |
| `expectError()`, `expectErrorType(X::class)` | `expectError<X>()`, for an exception the decide function throws; `expectRejected(code)` for a rejection it returns |
| `expectEventType(A::class, B::class)` | `expectEventTypes(A::class, B::class)` |
| `expectEventCount(n)` | `expectEventCount(n)` |
| `expectEvent<E> { … }`, `expectEventBody<E> { … }`: the first event of that type | `expectEvent<E> { … }`: exactly one event, an `E`; `expectEvents(…)` for several, by value |
| `expectEventStream { … }`, `expectEventIterator { … }` | `expectEvents(…)` or `expectEventTypes(…)` for the whole stream; `expectEventEnvelope<E> { … }` for one event with its envelope |
| An event's `aggregateId`, `version`, `commandId`, `requestId`, `header` | `expectEventEnvelope<E> { aggregateId; version; commandId; requestId; headers }` |
| An event's operator and create time | `expectEventEnvelope<E> { operator; commitTime }`, for a command that committed one event |
| `expectState { … }`, `expectState(Consumer)` | `expectState { … }` or `expectState(expected)`; the `Consumer` overloads are removed |
| `expectStateAggregate { version }` | `expectVersion(n)`: the committed version, or the version the aggregate stayed at when the command appended nothing |
| `expectStateAggregate { deleted }` | `expectDeleted()` |
| `expectStateAggregate { ownerId; spaceId }`, the tags | `expectScope(scope)`, `expectTags(tags)` |

Smaller differences: `expectEvent` and the saga's `expectCommand` now require exactly one, where 9.x took the first match; a command that commits nothing is `expectUnchanged()`.

### Saga specs {#saga-specs}

| 9.x | V10 |
| --- | --- |
| `class XSpec : SagaSpec<T>({ on { … } })` | `sagaSpec(<saga>Handlers(…)) { … }`, or `sagaSpecTests` for a tree |
| `inject { … }` | The handlers' dependencies, given to the generated handlers factory; `givenQueries(queries)` for the reads |
| The command gateway and message factory | Removed: `answerCalls { … }` answers the synchronous calls |
| `functionFilter { … }`, `functionName("onX")` | `whenEvent(event, handler = "onX")` |
| `whenEvent(event, state, ownerId, spaceId)` | `whenEvent(stateEvent(ref, state, events…, scope = …))`; a peer context's event: `publishedEvent(name, body)` |
| `expectCommandType(X::class)` | `expectCommandTypes(X::class)` |
| `expectCommandCount(n)` | `expectCommandCount(n)` |
| `expectCommand<C> { … }`, `expectCommandBody<C> { … }`: the first command | `expectCommand<C> { … }` or `expectCommand(body, to = ref)`: exactly one command; `expectCommands(…)` for several |
| `expectCommandStream`, `expectCommandIterator` | `commands`, `expectCommands(…)`, `expectCommandTypes(…)` |
| `expectNoCommand()`, `expectNoError()`, `expectError…` | The same names; `expectError<X>()` as on the aggregate |

New in V10, with no 9.x counterpart: `whenRejected` and `whenCommitted` for the receipts a saga handles, `expectDeterministic`, `expectHandledBy`, `expectCalls`, the schedule expectations, and `projectionSpec`, `reactorSpec` and `scheduleSpec`.

## What the Skill cannot do

Some 9.x constructs have no V10 form. The Skill reports each one the service uses, with the decision taken; it never emulates one:

- custom routes, methods and path or header variables: clients and gateway rules change;
- `bindingErrors`, `@VoidCommand`, `@Order`, swagger annotations;
- a 9.x Redis event store: the data tool reads MongoDB only;
- Java tests on `AggregateVerifier`, and Java calls of `suspend` APIs (`DecideScope.reserve`, `SagaContext.call`): Kotlin only;
- Jackson modules found on the class path, and fields without public getters: `WowJson` applies neither;
- state-event resend and per-version re-dispatch: none until 10.x; a parked entry is resumed instead;
- a `MessageFunction` registered at run time: rewrite it as an annotated processor.

Next: the [configuration](./configuration), the [HTTP and TypeScript clients](./http-and-clients), then the [data](./data) and the [cut-over](./cutover).
