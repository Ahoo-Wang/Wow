# Wow V10 Services

Load this reference instead of the 9.x domain references when the target builds against Wow V10. V10 is a rewrite, not a 9.x minor: none of the 9.x references, symbols, configuration keys or test classes apply to it, and none of this page applies to a 9.x target. A 9.x application moving to V10 is a cross-major migration and belongs to `wow-migrate`.

Status: V10 is developed on the Wow repository's `v10` branch and has no release, not even a pre-release; its modules are not published to any repository. Treat a target pinned to a `v10` commit as a source baseline, and verify every symbol, default, key and route below against that commit before relying on it. Record artifact and plugin availability as unverified until a 10.x tag exists.

## Recognize a V10 target

V10 when the build or sources show any of:

- dependencies `me.ahoo.wow:wow-kernel`, `wow-spring-boot`, `wow-runtime`, `wow-http`, `wow-postgres`, `wow-manifest`; the Gradle plugin `id("me.ahoo.wow")`; or, inside the Wow repository's examples, `project(":kernel")`, `project(":spring-boot")`, `ksp(project(":compiler"))`;
- imports of `me.ahoo.wow.api.decide.Decision`, `me.ahoo.wow.api.decide.DecideScope`, `me.ahoo.wow.api.subscribe.StateEvent`, `me.ahoo.wow.api.subscribe.SagaContext`, `me.ahoo.wow.kernel.*`, `me.ahoo.wow.kernel.test.aggregateSpec`;
- annotations `@Saga`, `@Reactor`, `@Projection`, `@OnRejected`, `@ErrorCode`, `@Revision`, `@Upgrade`, `@Alias`;
- resources `META-INF/wow/manifest/<module>.json` and a generated `<Module>ModelProvider`.

9.x when it shows `wow-spring-boot-starter` with capability variants, `wow-core`, `wow-test`, `AggregateSpec<A, S>` subclasses, `@StatelessSaga`, `@EventProcessor`, `@OnStateEvent`, `CommandGateway.sendAndWait`, `CommandMessage`, `wow-metadata.json`. Never mix the two in one module; a V10 module that depends on a 9.x one is a defect.

Requirements: JDK 21, Kotlin with KSP (Java sources need the Kotlin Gradle plugin too), Spring Boot 4 on the server, Jackson 3 (`tools.jackson`) in domain code that touches JSON.

## Module layout and build

| Module | Holds | Depends on |
| --- | --- | --- |
| `<svc>-api` | Commands, events, value types | `wow-api` |
| `<svc>-domain` | Roots, states, `@ErrorCode` constants, processors | `<svc>-api`, `api("wow-kernel")`; plugin `me.ahoo.wow`; tests `testFixtures("me.ahoo.wow:wow-kernel")` |
| `<svc>-server` | Spring Boot application, service beans | domain, `wow-spring-boot`, a backend (`wow-postgres` or `wow-mongo`), optionally `wow-kafka`, `wow-clickhouse`, `spring-boot-starter-validation` |

- The plugin applies KSP with `wow-compiler` of its own version in `generate` mode, names the fragment after the project, and registers `generateWowManifestFragment` (schemas into the fragment), `mergeWowManifest` (`build/wow/manifest.json`, the `wow-manifest` variant) and `collectWowPeers` with the `wowPeers` configuration. Its `wow { }` block: `context`, `addCompilerDependency`, `schemaOverrides`, `serviceName`, `serviceVersion`.
- Without the plugin (only inside the Wow repository): KSP by hand with `ksp { arg("wow.model", "generate"); arg("wow.manifest.module", project.name) }`, `ksp(project(":compiler"))`, then `apply(from = rootProject.file("example/wow-manifest-fragment.gradle.kts"))`; servers set `extra["wowService"]` and apply `example/wow-peers.gradle.kts`. KSP option `wow.context` names the context when the module has no single `@BoundedContext`.
- There is no V10 BOM: every `wow-*` dependency names its version.
- Reactor return types in handlers need `wow-reactor` on the class path, or compilation fails.

## Names

- Context: `@BoundedContext(name, description)` on an object of the domain module; names match `[a-z][a-z0-9-]*` (decision D169: no dot, it separates type names). Verify whether the target's compiler enforces it; the current `v10` compiler refuses only blank names, so check names by hand.
- Aggregate: `@Name` or the root's simple name in `snake_case`. Types: `<context>.<aggregate>.<Name>`. Subscriptions: `<processor>.<function>`.

## Aggregates

- `@AggregateRoot class Root(private val state: State)` (or a root that is its own state). Attributes: `lifecycle` (default all five lifecycle commands), `published`, `publishState`, `topic`.
- Command functions: non-private, `@OnCommand` or named `onCommand`. First parameter the body `X` or `Command<X>` (`ref`, `scope`, `headers`, `causation`); then services (factory parameters bound once from beans; `@Name` qualifies by bean name) and `DecideScope`. Returns `Decision.events(...)`, `Decision.NoOp`, `Decision.reject(code, message)`, an event, a list of events, or nothing. `@OnCommand(produces = [...])` is required for a `Decision` return so the Manifest knows the events, and forbidden when the return type names them; `@OnCommand(schedules = ["ctx.agg.Command"])` declares scheduled commands.
- Creation: `@CreateAggregate` (must not exist, else `wow.aggregate.exists`), `@AllowCreate`, neither (must exist, else `wow.aggregate.not-found`).
- No identity in command bodies: id from the route or the saga's `ref`, tenant/owner/space from the caller, version from `If-Match`.
- `DecideScope`: `command`, `putResult`, `reserve(namespace, key)`, `onAppendFailure`, `afterAppend`, `schedule(key, at|after, command, target)`, `cancelSchedule(key)`.
- `@AfterCommand(include, exclude)` or `afterCommand`: returns extra events in the same stream; cannot reject.
- Sourcing: on the state, `@OnSourcing` or `onSourcing`, one per produced event (even a no-op); mutable in place by default, returning the state type for immutable. No services, no I/O. Every property initialized: the state is serialized as is.
- Decide must never mutate state; the test DSL fails a spec that does.
- Rejection codes: `@ErrorCode(description, status = 422)` on a `const val String` in the domain module; never the `wow.` prefix; each once per module. Kernel codes: `KernelRejections`.
- Evolution: `@Revision(n)`, `@Upgrade(event = X::class, from = n)` on a top-level or `object` function taking and returning `JsonNode`/`ObjectNode`, a complete chain 1..n-1; `@Alias`.

## Processors

- One of `@Saga`, `@Projection`, `@Reactor` per class; handlers `@OnEvent` or `onEvent`, each a subscription `<processor>.<function>`; constructor parameters are beans.
- Handler input `StateEvent<S>` (`ref`, `version`, `events`, `state` copy, `commandId`, `requestId`, `commandType`, `commitTime`, `scope`, `causation`, `headers`, `id`, `correlationId`) and an optional `ProjectContext`/`ReactContext`/`SagaContext` whose `executionId` is the idempotency key.
- Projections and reactors return nothing; only a saga sends commands (`SagaContext.command(ref, body)`, deterministic ids). `@OnEvent(derives = [...])` when the return type does not name the commands.
- `@OnRejected` handler taking `CommandRejected<C>` (or `<*>`) and optionally `SagaContext`, returning compensating commands or `null`. Without one, a rejected derived command parks the aggregate for the saga.
- Start without a checkpoint: projections from the beginning, reactors and sagas from the end; override with `wow.subscriptions.[<name>].start` (`beginning`, `end`, ISO-8601). The current `v10` annotations have no `start` attribute (decision D179 is not implemented yet); verify on the target.
- Failure: retried by `wow.subscription.retry`, then the aggregate is parked in the failure ledger for that subscription; resume and skip exist in the runtime but no route exposes them.
- Scheduled commands: `DecideScope.schedule` / `cancelSchedule`, `SagaContext.schedule(key, at|after, target, command)` / `cancel`; at least once, never early; a rejection when due is a normal guard, not `@OnRejected`.

## Other contexts

- Reading: `@OnEvent(aggregates = ["ctx.agg"])` with the handler's own view type; events arrive as `StateEventPayload` (`me.ahoo.wow.spi.state`: `name`, `revision`, JSON), matched by name (decision D178, typed decoding, is not implemented on the current `v10`; verify). Nullable state (`StateEvent<View?>`) when the upstream sets `publishState = false`. Needs `wow-kafka`, `wow.kafka.bootstrap-servers`, the upstream's Manifest as a peer, and usually `start: beginning`.
- Sending: the target's `-api` module on the class path and its Manifest as a peer (`wowPeers(project(":x-server"))` or coordinates); the command goes to `wow.<target>.commands`; the target sets `wow.kafka.command-intake: true` and its command topic must exist with exactly `wow.log.partitions` partitions; rejections return on `wow.<sender>.receipts`. Without Kafka the saga fails at run time and parks; nothing warns at startup.
- One tenant space across contexts (decision D180): tenant ids cross as they are.

## What the compiler generates

- `<root>Model(context, services…, configure = {})` beside each root (`Order` gives `orderModel`); the context is always a parameter.
- `<processor>Handlers(dependencies)` per processor in `<Processor>Subscriptions.kt`.
- One `ModelProvider` per module, named after the module (`shop-domain` gives `ShopDomainModelProvider`), in the deepest common package, registered in `META-INF/services/me.ahoo.wow.kernel.ModelProvider`; the host serves every provider on its class path.
- `META-INF/wow/manifest/<module>.json`: aggregates (commands with `creation`, `produces`, `routes`; events with `revision`; `lifecycle`; `queries`; `schedules`), `subscriptions`, `published`, `errors`; schemas completed into `$defs` by `generateWowManifestFragment`. Without completion, snapshot queries cannot filter `state.*`.
- Routes, all from the Manifest:
  - commands: `POST /<ctx>/<agg>/{id}/<kebab-command>`, plus `POST /<ctx>/<agg>/<kebab-command>` for creating commands;
  - lifecycle: `DeleteAggregate` at the `DELETE` of `/<ctx>/<agg>/{id}`, the others at `POST /<ctx>/<agg>/{id}/wow.lifecycle.<kebab>`;
  - state: `GET /<ctx>/<agg>/{id}/state`;
  - queries: `POST` and `GET` of `/<ctx>/<agg>/{model}/query`, the model `snapshot` or `events`;
  - datasets under `/wow/dataset`, and the Manifest at `GET /wow/manifest`.

## Tests

- `aggregateSpec(model, id, tenantId, start) { given(...); whenCommand(cmd, scope = …) { expectEvents / expectEventTypes / expectUnchanged / expectRejected / expectState / expectScope / expectScheduled / expectScheduleCancelled }; advanceTime(d) { … } }` from `me.ahoo.wow.kernel.test`, in the kernel's test fixtures. Build the model with the generated factory and stub services.
- `expectState` needs committed events; it cannot follow `expectUnchanged`.
- JUnit test functions must return `Unit`; one that returns a value is skipped silently. Count executed tests in `build/test-results` XML when it matters.
- Processor tests call the handler with a `StateEvent` and `ExecutionSagaContext`/`ExecutionProjectContext`/`ExecutionReactContext` (`me.ahoo.wow.kernel`); `@OnRejected` through `ModelProvider.load().single().processors(...)`, `rejectionHandlerOf`, `rejectionContextOf`.
- Server tests start the application with `server.port=0`, `wow.internal.port=0`, a test `CallerResolver`; the memory backend serves no snapshot queries unless `testFixtures("…:wow-query")` is on the test class path.

## Host and HTTP

- Configuration is `wow.*` from `WowProperties`, `QueryProperties`, `KafkaProperties`, `ClickHouseProperties` in `wow-spring-boot`; read them on the target rather than recalling keys. Common ones: `wow.service` (or `spring.application.name`), `wow.backend` (`auto`, `memory`, `postgres`, `mongo`), `wow.internal.port` (9090), `wow.internal.host`, `wow.log.partitions` (64), `wow.cluster.router`, `wow.wait.default-timeout`, `wow.subscriptions.[…].start`, `wow.aggregates.[ctx.agg].tenant`, `wow.http.request-facts`, `wow.kafka.bootstrap-servers`, `wow.kafka.command-intake`, `wow.clickhouse.endpoint`.
- Backends: PostgreSQL needs a `DataSource` bean, MongoDB a sync `MongoClient` bean on a replica set; memory is one node and serves no snapshot queries.
- Callers: a `CallerResolver` bean (`me.ahoo.wow.http.caller`) returning `Caller` (`me.ahoo.wow.runtime.admission` on the current `v10`; decision D165 moves it to `me.ahoo.wow.api.caller`, so check the package); without one, every request is anonymous in tenant `(0)`.
- Headers: `Wow-Wait` (`accepted`, `committed`, `snapshot`, `handled([ctx/]sub)`, chained with `>`; default `snapshot`), `Wow-Wait-Timeout`, `Wow-Request-Id`, `If-Match`, `Accept: text/event-stream`, `Wow-Scope`, `Wow-Space`. Errors are RFC 9457 problems, `type` `urn:wow:error:<code>`.
- The internal port is unauthenticated (decision D173): private network only, never exposed; it binds every interface unless `wow.internal.host` is set.
- Events are permanent (decision D175): keep personal data out of events and request facts.

## Review and diagnosis checks specific to V10

- A command function returning `Decision` without `@OnCommand(produces)`; an event without a sourcing function; decide code assigning state.
- An identity field in a command body; a 9.x annotation or type in a V10 module.
- A reactor that tries to send commands; a saga whose expected rejections have no `@OnRejected` (aggregates park).
- A cross-context saga on a node without Kafka, a target without `command-intake`, a command topic with the wrong partition count, or a missing peer Manifest.
- A cross-context subscription that must not miss history but starts at `end`.
- A context name with a dot or upper case; `wow.log.partitions` changed after the log holds entries.
- A snapshot query on `state.*` failing because the fragment was not completed (no plugin, no `wow-manifest-fragment` step).
- Personal data in events or in `wow.http.request-facts`; the internal port reachable from outside.
