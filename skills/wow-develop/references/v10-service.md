# Wow V10 Services

Load this reference instead of the 9.x domain references when the target builds against Wow V10. V10 is a rewrite, not a 9.x minor: none of the 9.x references, symbols, configuration keys or test classes apply to it, and none of this page applies to a 9.x target. A 9.x application moving to V10 is a cross-major migration and belongs to `wow-migrate`.

Status: V10 is developed on the Wow repository's `main` branch and ships pre-releases only (`v10.0.0-alpha.<n>` tags, without a compatibility promise; for `10.0.0-alpha.3`, `wow-bom`, `wow-kernel`, `wow-gradle-plugin` and the plugin marker of `me.ahoo.wow` are on Maven Central). Pin the exact pre-release, or treat a target pinned to a `v10` commit as a source baseline, and verify every symbol, default, key and route below against it before relying on it. Check that each artifact the build names exists for that version; record what does not resolve as unverified.

## Recognize a V10 target

V10 when the build or sources show any of:

- dependencies `me.ahoo.wow:wow-kernel`, `wow-spring-boot`, `wow-runtime`, `wow-http`, `wow-postgres`, `wow-manifest`; the Gradle plugin `id("me.ahoo.wow")`; or, inside the Wow repository's examples, `project(":kernel")`, `project(":spring-boot")`, `ksp(project(":compiler"))`;
- imports of `me.ahoo.wow.api.decide.Decision`, `me.ahoo.wow.api.decide.DecideScope`, `me.ahoo.wow.api.subscribe.StateEvent`, `me.ahoo.wow.api.subscribe.SagaContext`, `me.ahoo.wow.kernel.*`, `me.ahoo.wow.test.aggregateSpec`;
- annotations `@Saga`, `@Reactor`, `@Projection`, `@OnRejected`, `@ErrorCode`, `@Revision`, `@Upgrade`, `@Alias`;
- resources `META-INF/wow/manifest/<module>.json` and a generated `<Module>ModelProvider`.

9.x when it shows `wow-spring-boot-starter` with capability variants, `wow-core`, `wow-test` with `AggregateSpec<A, S>` subclasses (V10's test DSL keeps the artifact `wow-test`), `@StatelessSaga`, `@EventProcessor`, `@OnStateEvent`, `CommandGateway.sendAndWait`, `CommandMessage`, `wow-metadata.json`. Never mix the two in one module; a V10 module that depends on a 9.x one is a defect.

Requirements: Gradle (decision D239 ②: there is no Maven build), JDK 21, Kotlin with KSP, Spring Boot 4 on the server, Jackson 3 (`tools.jackson`) in domain code that touches JSON.

Java domains (decision D229 G-8): a module of Java sources applies `kotlin("jvm")` beside `id("me.ahoo.wow")` (the plugin fails a module without it: KSP runs in the Kotlin compilation and the generated models are Kotlin). Java decide functions return an event or a `Decision` from the statics `Decision.events(…)`, `Decision.reject(code, message)`, `Decision.NoOp.INSTANCE`; `@ErrorCode` goes on a `public static final String` field. Java cannot call the `suspend` APIs (`DecideScope.reserve`, `SagaContext.call`), and the test DSL is Kotlin only; a Java facade and a blocking bridge are planned for 10.x. `WowJson` serializes public getters and record components only, so state and event classes need them.

## Module layout and build

| Module | Holds | Depends on |
| --- | --- | --- |
| `<svc>-api` | Commands, events, value types | `wow-api` |
| `<svc>-domain` | Roots, states, `@ErrorCode` constants, processors | `<svc>-api`, `api("wow-kernel")`; plugin `me.ahoo.wow`; `wow-query-api` when it uses the query field annotations or `<State>Properties`; tests `testImplementation("me.ahoo.wow:wow-test")` |
| `<svc>-server` | Spring Boot application, service beans | domain, `wow-spring-boot`, a backend (`wow-postgres` or `wow-mongo`), optionally `wow-kafka`, `wow-clickhouse`, `spring-boot-starter-validation` |

- The plugin applies KSP with `wow-compiler` of its own version in `generate` mode, names the fragment after the project, and registers `generateWowManifestFragment` (schemas into the fragment), `mergeWowManifest` (`build/wow/manifest.json`, the `wow-manifest` variant), `generateWowOpenApi` (`build/wow/openapi.json`), `checkManifestCompatibility` (part of `check`, against `manifest/baseline.json`) with `updateManifestBaseline`, and the peer contract (decision D234): `generateWowContract`, and, in each project the domain module declares in `api(project(…))`, `assembleWowContract`, which puts `META-INF/wow/contract.json` into that api jar and fails when a context's command and event classes are not all in it. Its `wow { }` block: `context`, `addCompilerDependency`, `schemaOverrides`, `serviceName`, `serviceVersion`, `manifestBaseline`. It fails a module without the Kotlin JVM plugin, and is not compatible with Gradle's isolated projects.
- Without the plugin (only inside the Wow repository): KSP by hand with `ksp { arg("wow.model", "generate"); arg("wow.manifest.module", project.name) }`, `ksp(project(":compiler"))`, then `apply(from = rootProject.file("example/wow-manifest-fragment.gradle.kts"))`, which also applies `example/wow-contract.gradle.kts`; eShop servers set `extra["wowService"]` and apply `example/eshop/wow-manifest-compatibility.gradle.kts`. KSP option `wow.context` names the context when the module has no single `@BoundedContext`.
- The V10 BOM is `me.ahoo.wow:wow-bom` of the V10 version (`platform(…)`); check that the pinned pre-release lists each module you use.
- Reactor return types in handlers need `wow-reactor` on the class path, or compilation fails.

## Names

- Context: `@BoundedContext(name, description)` on an object of the domain module; names match `[a-z][a-z0-9-]*` (decision D169: no dot, it separates type names). The compiler fails on a `@BoundedContext` or `wow.context` that breaks it (`context name "…" is not valid: …`, `me.ahoo.wow.manifest.ContextName`), and reading a Manifest refuses one; a target older than that rule needs the names checked by hand.
- Aggregate: `@Name` or the root's simple name in `snake_case`. Types: `<context>.<aggregate>.<Name>`. Subscriptions: `<processor>.<function>`.

## Aggregates

- `@AggregateRoot class Root(private val state: State)` (or a root that is its own state). Attributes: `lifecycle` (default all five lifecycle commands), `published`, `publishState`, `topic`.
- Command functions: non-private, `@OnCommand` or named `onCommand`. First parameter the body `X` or `Command<X>` (`ref`, `scope`, `headers`, `causation`); then services (factory parameters bound once from beans; `@Name` qualifies by bean name) and `DecideScope`. Returns `Decision.events(...)`, `Decision.NoOp`, `Decision.reject(code, message)`, an event, a list of events, or nothing. `@OnCommand(produces = [...])` is required for a `Decision` return so the Manifest knows the events, and forbidden when the return type names them; `@OnCommand(schedules = ["ctx.agg.Command"])` declares scheduled commands.
- Creation: `@CreateAggregate` (must not exist, else `wow.aggregate.exists`), `@AllowCreate`, neither (must exist, else `wow.aggregate.not-found`).
- `@Unaddressed`: a command whose request may name no aggregate because a `CommandTransformer` of the service finds it (a derived id); the Manifest then lists `POST /{context}/{aggregate}/{command}` beside the route with the id, as for a creation command (D182, D210).
- No identity in command bodies: id from the route or the saga's `ref`, tenant/owner/space from the caller, version from `If-Match`.
- `DecideScope`: `command`, `ids`, `putResult`, `reserve(namespace, key[, tenancy])` (`suspend`), `release(namespace, key[, tenancy])`, `onAppendFailure`, `afterAppend`, `schedule(key, rule, command, target)`, `cancelSchedule(key)`.
- `@AfterCommand(include, exclude)` or `afterCommand`: returns extra events in the same stream; cannot reject.
- Sourcing: on the state, `@OnSourcing` or `onSourcing`, one per produced event (even a no-op); mutable in place by default, returning the state type for immutable. No services, no I/O. Every property initialized: the state is serialized as is.
- Decide must never mutate state; the test DSL fails a spec that does.
- Rejection codes: `@ErrorCode(description, status = 422)` on a `const val String` (Java: a `static final String` field) in the domain module; never the `wow.` prefix; each once per module. Write local names in an `object` nested in the `@AggregateRoot` class (`const val EMPTY = "empty"`, decision D259): the compiler and the kernel name them `context.aggregate.empty`, `expectRejected` takes either form, and peers compare with the api's generated `<Context>Contract.<Aggregate>.Errors.EMPTY`; a code with a dot is full (`context.code` for a context-wide one). Kernel codes: `KernelRejections`.
- Evolution: `@Revision(n)`, `@Upgrade(event = X::class, from = n)` on a top-level or `object` function taking and returning `JsonNode`/`ObjectNode`, a complete chain 1..n-1; `@Alias`.

## Processors

- One of `@Saga`, `@Projection`, `@Reactor` per class; handlers `@OnEvent` or `onEvent`, each a subscription `<processor>.<function>`; constructor parameters are beans.
- Handler input `StateEvent<S>` (`ref`, `version`, `events`, `state` copy, `commandId`, `requestId`, `commandType`, `commitTime`, `scope`, `causation`, `headers`, `id`, `correlationId`), or for one kind of event `StateTypedEvent<S, E>` (adds `event`, `eventIndex`) or `E` itself, called once per `E` of the stream with the state after the whole commit (decision D264; the compiler infers the aggregate from `E` and the node skips streams without one), and an optional `ProjectContext`/`ReactContext`/`SagaContext` whose `invocationKey` is the idempotency key of an outside call (`executionId` is per stream). Prefer the typed form to `event.events.filterIsInstance<E>()`.
- Projections and reactors return nothing; only a saga sends commands (`SagaContext.command(ref, body)`, deterministic ids). In an `@OnEvent`, `@OnRejected` or `@OnCommitted` handler the target type may be left out (decision D234): `command(id, body)` and, for a creation without an id, `command(body)` go to the aggregate the local Manifest or a peer api jar's contract assigns to the body's class, in the handled commit's tenant; an `@OnSchedule` job, a command to another tenant and a class that several aggregates decide write the full ref, and a class no contract names fails (add its context's `*-api`). `@OnEvent(derives = [ConfirmPayment::class, FailPayment::class])` (command classes, or a sealed type for its subclasses) when the return type does not name the commands; a returned `Command<S>` of a sealed `S` derives its subclasses, and the service's build fails on a class neither the service nor a peer contract decides (decision D259).
- Java code outside a domain calls commands and queries through `wow-client`'s facade, `JavaCommandClient.of(commandClient)` and `JavaQueryApi.of(queryApi)` (`me.ahoo.wow.client.java`): blocking or `…Async` (`CompletableFuture`), `List` and `java.util.stream.Stream` results, `java.time.Duration` timeouts; never call the blocking forms on a Netty or WebFlux thread.
- A synchronous call to another context (`SagaContext.call`, decision D193) is declared with `@OnEvent(calls = [SubmitOrder::class])` (decision D259): the Manifest lists it as the subscription's `calls`, waits may follow it, an undeclared class is refused, and the node does not start when a called command is its own context's or no Manifest decides it, or when its context has no URL: the call goes to `wow.calls.contexts.<context>.url` (or `wow.clients.contexts.<context>.internal-url`), which must be set. A command of the saga's own context is derived, never called.
- `@OnRejected` handler taking `CommandRejected<C>` (or `<*>`) and optionally `SagaContext`, returning compensating commands, or `Unit` (or `null`) to accept the rejection (decision D259; `@OnCommitted` and a saga's `@OnEvent` may return `Unit` too, deriving nothing). Without one, a rejected derived command parks the aggregate for the saga.
- Start without a checkpoint: projections, and handlers of another context's stream, from the beginning; reactors and sagas of the own log from the end. `@OnEvent(start = …)` declares it (decision D194) and the host overrides it with `wow.subscriptions.[<name>].start` (`beginning`, `end`, ISO-8601).
- Failure: retried in process (`@Retry`, `wow.subscription.retry.*`), then the aggregate is parked in the failure ledger for that subscription and retried on the long horizon (`wow.subscription.parked.*`, per subscription `wow.subscriptions.[<name>].parked.*`). With `wow.ops.enabled: true` the operator lists and acts on entries under `/wow/ops/parked` (`resume`, `skip`, `mark-recoverable`, `apply-retry-spec`), authorized by the host's `CommandAuthorizer`; `wow.ops.webhook.*` notifies, and `wow.ops.publish.enabled` publishes the ledger to the ops service.
- Scheduled commands: `DecideScope.schedule` / `cancelSchedule`, `SagaContext.schedule(key, at|after, target, command)` / `cancel`; at least once, never early; a rejection when due is a normal guard, not `@OnRejected`.

## Other contexts

- Reading: `@OnEvent(aggregates = ["ctx.agg"])` with the handler's own view type; events arrive as the upstream api's classes when their revision is the contract's, else as `PublishedEvent` (`me.ahoo.wow.api.subscribe`: `name`, `revision`, JSON text) (DX-1c); prefer a handler of each event, `StateTypedEvent<View, E>` or `E` with `@OnEvent(aggregates)`, which is not called for a `PublishedEvent`. Nullable state (`StateEvent<View?>`) when the upstream sets `publishState = false`. Needs `wow-kafka`, `wow.kafka.bootstrap-servers`, and the upstream's api module on the class path, whose jar carries its contract (`META-INF/wow/contract.json`, decision D234).
- Sending: the target's `-api` module on the class path, which carries its contract (decision D234; there is no `wowPeers` configuration or peer Manifest); the command goes to `wow.<target>.commands`; the target sets `wow.kafka.command-intake: true` and its command topic must exist with exactly `wow.log.partitions` partitions; rejections return on `wow.<sender>.receipts`. A node without a command transport refuses to start when a saga sends, schedules or handles rejections of another context's commands (`Node … has no command transport …`): production needs Kafka; tests give an `InMemoryTopics` bean and `SourceTopics(topics, producer = topics)`.
- Over HTTP (decisions D257, D259): a context listed in `wow.clients.contexts.<context>` (`url` for the public routes, `internal-url` for the internal port, `mode: service|user`, default `wow.clients.mode`) is reached by the `CommandClient` and `QueryApi` beans over HTTP; its api jar must be on the class path. A console, gateway or tool that calls a peer without its jar says `contract: manifest` there and sends `NamedCommand("ctx.agg.Command", body)` (`me.ahoo.wow.client`, a `JsonNode` body): the peer's `GET /wow/manifest` must declare it, or the send fails before any request; a command object to such a peer is a configuration error. `WowClient.manifestOf(context, caller)` reads that Manifest (kept a minute), what the caller may be offered.
- One tenant space across contexts (decision D180): tenant ids cross as they are.

## What the compiler generates

- `<root>Model(context, services…, configure = {})` beside each root (`Order` gives `orderModel`); the context is always a parameter.
- `<processor>Handlers(dependencies)` per processor in `<Processor>Subscriptions.kt`.
- One `ModelProvider` per module, named after the module (`eshop-catalog-domain` gives `EshopCatalogDomainModelProvider`), in the deepest common package, registered in `META-INF/services/me.ahoo.wow.kernel.ModelProvider`; the host serves every provider on its class path unless `wow.contexts.include` / `exclude` select some (several contexts on one class path, shared-JVM end-to-end tests); an unknown name fails the startup.
- `META-INF/wow/manifest/<module>.json`: aggregates (commands with `creation`, `produces`, `routes`; events with `revision`; `lifecycle`; `queries`; `schedules`), `subscriptions`, `published`, `errors`; schemas completed into `$defs` by `generateWowManifestFragment`. Without completion, snapshot queries cannot filter `state.*`.
- Routes, all from the Manifest:
  - commands: `POST /<ctx>/<agg>/{id}/<kebab-command>`, plus `POST /<ctx>/<agg>/<kebab-command>` for creating commands;
  - lifecycle: `DeleteAggregate` at the `DELETE` of `/<ctx>/<agg>/{id}`, the others at `POST /<ctx>/<agg>/{id}/wow.lifecycle.<kebab>`;
  - state: `GET /<ctx>/<agg>/{id}/state`;
  - state history: `GET /<ctx>/<agg>/{id}/state?version=` or `?at=`, and `GET …/{id}/state/trace`;
  - read your writes: `GET /<ctx>/<agg>/{id}/state?minVersion=<the command result's version>` (or `QueryApi` `state(id, minVersion)`): 409 `wow.query.min-version-not-reached` with the current `version` when it is not committed within the wait; paged totals are counted with their page (`wow.query.count-cache.paged-totals=false`), so they agree with the rows;
  - queries: `POST` and `GET` (the descriptor) of `/<ctx>/<agg>/{model}/query`, the model `snapshot` or `events` (one record per committed event stream, decision D235); the body's `kind` is `single`, `list`, `paged`, `cursor`, `count` or `aggregate`;
  - snapshot rebuild: `POST /<ctx>/snapshot/<agg>/wow.snapshot.rebuild` (decision D172);
  - datasets under `/wow/dataset`, the parked entries under `/wow/ops/parked` (`wow.ops.enabled`), the Manifest at `GET /wow/manifest`, the OpenAPI document at `GET /wow/openapi`.

## Tests

- `aggregateSpec(model, id, tenantId, start) { given(...) or givenState(state, version) / givenState(version) { … }; whenCommand(cmd, scope = …) { expectEvents / expectEventTypes(X::class) / expectEvent<E> { } / expectUnchanged / expectRejected(code) { } / expectError<T> { } / expectNoError / expectState / expectScope / expectScheduled / expectScheduleCancelled }; advanceTime(d) { … }; expectNothingDue(within = d) }` from `me.ahoo.wow.test`, the artifact `wow-test`. Build the model with the generated factory and stub services.
- `givenState` is the first step: the aggregate starts in that state at that version (copied through JSON), and later streams continue from it.
- Shared prefixes (decision D245): `ref("name")` marks a point; `fork("name") { … }`, `fork(from = "name") { … }`, or `fork("name") { … }` inside a `whenCommand(…) { … }` block, runs on a copy of everything the spec holds (log, given state, timers, reservations, ids, clock): nothing before the fork runs again, so mocked services are not called again for the prefix. `@TestFactory fun x() = aggregateSpecTests(model) { … }` makes each step a test and each fork a container (`sagaSpecTests`, `projectionSpecTests`, `reactorSpecTests` likewise; processor specs fork too).
- After a command that committed nothing (`expectUnchanged`, `expectRejected`, `expectError`), `expectState` checks the current state. A rejection is a code (`expectRejected`); an exception the decide function throws is checked by type (`expectError<T>`), and fails the spec when unchecked.
- `advanceTime(d) { … }` fails when nothing was due; without a block it never fails. Show that a cancelled schedule does not fire with `expectNothingDue(within = d)`.
- JUnit test functions must return `Unit`; one that returns a value is skipped silently. Count executed tests in `build/test-results` XML when it matters.
- Processor tests: `sagaSpec(<processor>Handlers(Dependencies.of(…)))`, `projectionSpec(…)`, `reactorSpec(…)`. The compiler generates `<processor>Handlers(dependencies)` per processor (`orderSagaHandlers`), which constructs that processor only, with its own services; `ModelProvider.processors(...)` builds every processor of the module and needs all their services.
- `sagaSpec(handlers) { whenEvent(stateEvent(ref, state, *events, version = …), handler = null) { expectCommands / expectCommandTypes / expectCommand(body, to) / expectCommand<C> { } / expectNoCommand / expectScheduled / expectScheduleCancelled / expectNoSchedule / expectHandledBy / expectDeterministic / expectError<T>; whenRejected(commands.single(), code, message) { … } } }`. `whenEvent` calls every handler reading the event (its `aggregates`, or its state type); events of another context's stream are `publishedEvent(name, body)`. Each call gets a fresh `ExecutionSagaContext`, so derived ids are stable per commit; a hand-built context must be new for each call.
- Projections and reactors write to a port: pass a fake through the handlers' dependencies, `whenEvent(...)`, then assert on the fake.
- Server tests start the application with `server.port=0`, `wow.internal.port=0`, a test `CallerResolver`; the memory backend serves no snapshot queries unless `wow.query.memory.enabled` is on.

## Host and HTTP

- Configuration is `wow.*` from `WowProperties`, `QueryProperties`, `KafkaProperties`, `ClickHouseProperties` in `wow-spring-boot`; read them on the target rather than recalling keys. Common ones: `wow.service` (or `spring.application.name`), `wow.backend` (`auto`, `memory`, `postgres`, `mongo`), `wow.internal.port` (9090), `wow.internal.host`, `wow.log.partitions` (64), `wow.cluster.router`, `wow.wait.default-timeout`, `wow.subscriptions.[…].start`, `wow.aggregates.[ctx.agg].tenant`, `wow.http.request-facts`, `wow.ops.enabled`, `wow.query.memory.enabled`, `wow.kafka.bootstrap-servers`, `wow.kafka.command-intake`, `wow.clickhouse.endpoint`.
- Backends: PostgreSQL needs a `DataSource` bean, MongoDB a sync `MongoClient` bean on a replica set; memory is one node and serves no snapshot queries.
- Callers: a `CallerResolver` bean (`me.ahoo.wow.http.caller`) returning `Caller` (`me.ahoo.wow.api.caller`); without one, every request is anonymous in tenant `(0)`. `Caller.app` is the calling application the resolver authenticated (decision D232); CoSec's resolver sets it only when CoSec authorized the request by that application's roles (D236; `wow.cosec.trust-injected-app` for a service behind a gateway in inject mode, default false).
- Request facts: the edge records the standard facts `app_id` (from `Caller.app`), `remote_ip` and `user_agent` (public port; `wow.http.client-facts: false` records neither, personal data that cannot be erased once written) itself (`RequestFacts`); none can be listed or set by a request header. Other request headers become facts only when listed in `wow.http.request-facts`. Derived commands inherit `app_id` only (`RequestFacts.PROPAGATED`).
- Headers: `Wow-Wait` (`accepted`, `committed`, `snapshot`, `handled([ctx/]sub)`, chained with `>`; default `snapshot`), `Wow-Wait-Timeout`, `Wow-Request-Id`, `If-Match`, `Accept: text/event-stream`, `Wow-Scope`, `Wow-Space`. Errors are RFC 9457 problems, `type` `urn:wow:error:<code>`.
- The internal port is unauthenticated (decision D173): private network only, never exposed; it binds every interface unless `wow.internal.host` is set.
- Events are permanent (decision D175): keep personal data out of events and request facts.

## Review and diagnosis checks specific to V10

- A command function returning `Decision` without `@OnCommand(produces)`; an event without a sourcing function; decide code assigning state.
- An identity field in a command body; a 9.x annotation or type in a V10 module.
- A reactor that tries to send commands; a saga whose expected rejections have no `@OnRejected` (aggregates park).
- A cross-context saga on a node without Kafka (it does not start), a target without `command-intake`, a command topic with the wrong partition count, or a peer's api module missing from the class path (no contract for its context).
- A cross-context subscription that must not miss history but starts at `end`.
- A context name with a dot or upper case; `wow.log.partitions` changed after the log holds entries.
- A snapshot query on `state.*` failing because the fragment was not completed (no plugin, no `wow-manifest-fragment` step).
- Personal data in events or in `wow.http.request-facts`; the internal port reachable from outside; an application id read from a raw request header instead of `Caller.app` or the `app_id` fact.
- A Java domain module without `kotlin("jvm")`, or a Java decide function that needs `reserve`.
