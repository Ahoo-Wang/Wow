# eShop DX gaps

This file lists the developer-experience gaps the eShop runs into (decision D159). The eShop is written as a Wow user would write it. That user reads only the public documentation (wow.ahoo.me, the `README`s) and the Skills (`skills/`). They do not read Wow's sources, design records or tests. Anything that user would find unclear, missing or awkward goes here. The gaps marked as blocking M7 must be closed before M7 starts (roadmap, M6e). This file is a ledger: delete an entry once its gap is closed.

## Format

Each entry gets a heading `### G<n>. <short title>`, followed by these fields:

- **Work package**: the work package that ran into the gap (E.0 to E.6).
- **Area**: build, compiler, kernel, host, edge, backends, testing, docs or Skills.
- **What happened**: what the user tried, and what they saw or could not find.
- **Where they looked**: the public pages and Skills they read.
- **Workaround**: what the eShop does instead, if anything.
- **Proposed fix**: what would close the gap. This can be documentation, an API change or a default.
- **Blocks M7**: `yes` or `no`, and why.

## Entries

### G1. No V10 guide for writing a new service (resolved)

- **Work package**: E.0
- **Area**: docs, Skills
- **What happened**: Nothing public describes how to lay out and build a V10 service from scratch.
  - **The site.** wow.ahoo.me describes 9.x: `wow-spring-boot-starter`, `AggregateSpec<A, S>`, the 9.x annotations.
  - **The `wow-develop` Skill.** It resolves V9 rules and names no V10 module.
  - **The only V10 material.** That is `wow-migrate`'s `references/v9-to-v10.md`. It is framed as a 9.x rewrite, calls itself a first draft, and says to treat the shop's build scripts as the reference.
  - **The result.** The project layout, the dependencies of each module and the server's wiring were copied from `example/shop`. They were not learned from a guide.
- **Where they looked**: wow.ahoo.me guide; `skills/wow-develop/SKILL.md`; `skills/wow-migrate/references/v9-to-v10.md`.
- **Workaround**: Follow the shop's build scripts.
- **Proposed fix**: Add a V10 getting-started page (en and zh) for a new service. It should cover:
  - the module split (`-api`, `-domain`, `-server`) and what each one depends on;
  - the Gradle plugin;
  - the aggregate test DSL;
  - the host's configuration keys.

  Then teach `wow-develop` (or a V10 Skill) the V10 surface.
- **Blocks M7**: yes. M7 puts V10 in front of users, and a new user cannot start without this page.
- **Resolved**: by #4379 (decision D170). The docs site has a V10 section in English and Chinese (`guide/v10/`): a quick start for a new service (the module split and its dependencies, the Gradle plugin or KSP by hand, the context name rules), the programming model, the generated code, testing, the HTTP API, every `wow.*` key with its default, and deployment. `wow-develop` loads `references/v10-service.md` for a V10 target.

### G2. `application.yaml` fails on the host alone: SnakeYAML is missing (resolved)

- **Work package**: E.0
- **Area**: host
- **What happened**: A server that depends only on the host (`wow-spring-boot`) and has an `application.yaml`, as the shop does, fails at startup with "Attempted to load Config resource 'class path resource [application.yaml]' … but snakeyaml was not found on the classpath". The shop starts only because one of its other dependencies, `spring-boot-starter-validation`, brings SnakeYAML along. Nothing documents that the host needs a Spring Boot starter or SnakeYAML beside it.
- **Where they looked**: `v9-to-v10.md` (Build and dependencies), which says the host is "wiring only" plus one module per technology.
- **Workaround**: Each eShop server declared `runtimeOnly("org.yaml:snakeyaml")`; #4346 removed it.
- **Proposed fix**: Make the host depend on `spring-boot-starter`, as every Spring Boot starter does, or state the requirement where the host's dependencies are documented.
- **Blocks M7**: yes. The first server a user writes fails to start.
- **Resolved**: by #4346. The host depends on `spring-boot-starter`, which brings SnakeYAML.

### G3. The rules for a context name are not documented (resolved)

- **Work package**: E.0
- **Area**: compiler, docs
- **What happened**: The eShop needs context names that do not clash with the shop's `example`. A dotted name such as `eshop.catalog` reads naturally, but nothing says whether a name may contain a dot. Several places write a type as `<context>.<aggregate>`: the cross-context references (`@OnEvent(aggregates = ["ctx.agg"])`, `derives = ["ctx.agg.Command"]`), the topics (`wow.<context>.<aggregate>.state`) and the host's `wow.aggregates.[<context>.<aggregate>]` keys. With a dot inside the context name, these become ambiguous. The compiler does not reject such a name, so the mistake would surface late, if at all.
- **Where they looked**: `v9-to-v10.md` (Annotation mapping, `@BoundedContext`).
- **Workaround**: Use hyphenated names: `eshop-catalog`, `eshop-basket`, `eshop-ordering`, `eshop-inventory`, `eshop-payment`.
- **Proposed fix**:
  - Document the allowed characters of context and aggregate names.
  - Make the compiler reject an invalid name on `@BoundedContext` (and `@Name`) with a message that says why.
- **Blocks M7**: no. It has a workaround, but every new user meets the question.
- **Resolved**: by #4380 (decision D169). A context name is `[a-z][a-z0-9-]*`, without dots. The compiler fails on a `@BoundedContext` or a `wow.context` option that breaks the rule, with a message that says why, and reading a Manifest (a module's fragment or a peer's) refuses such a context. Documenting the rule in the guide is left to E.D.

### G4. A host serves every domain module on its class path (resolved)

- **Work package**: E.0
- **Area**: host
- **What happened**: The host finds the models through `ServiceLoader` and serves every binding index it finds. There is no setting to choose the contexts a node serves. Two services cannot share a class path: each would serve both contexts. This rules out starting two eShop servers in one test JVM, and a modular monolith that switches contexts by configuration. Nothing documents this behaviour.
- **Where they looked**: `v9-to-v10.md`; the host's configuration keys in the shop's `application.yaml`.
- **Workaround**: `:eshop-e2e` starts each service in a JVM of its own. Each JVM gets the runtime class path of that service's server project, which the build passes to the test (`example/eshop/e2e/build.gradle.kts`).
- **Proposed fix**: Add a host property that names the contexts a node serves, defaulting to all. At minimum, document that a service's class path defines its contexts.
- **Blocks M7**: no, for the eShop. It is worth deciding before users build multi-service test harnesses.
- **Resolved**: by #4380 (decision D169). `wow.contexts.include` and `wow.contexts.exclude` select the contexts a node serves, all of those on the class path by default; a name that is no context on the class path fails the startup. `:eshop-e2e` also runs the catalog and basket services in one JVM, on one class path, each including its own context (`EshopSharedJvmTest`).

### G5. Where the test DSL lives, and what the compiler generates, is not documented (resolved)

- **Work package**: E.0
- **Area**: testing, compiler, docs
- **What happened**: Writing the first aggregate test took knowledge that no public page or Skill provides:
  - **Where the DSL lives.** `aggregateSpec` comes from the kernel's test fixtures (`me.ahoo.wow.kernel.test`). `v9-to-v10.md` only says to "verify which artifact ships it".
  - **The model factory.** The compiler generates a model factory named `<aggregate root>Model`. It takes the context name as a parameter, even when the module declares a single `@BoundedContext`.
  - **The binding index.** The generated class is named after the Gradle project (`EshopCatalogDomainModelProvider`). It lives in the deepest package the module's models share. Its fragment is `META-INF/wow/manifest/<project>.json`.
  - **The routes.** Each command gets the route `/<context>/<aggregate>/{id}/<kebab-case command>`. A creating command also gets a route without `{id}`, and the server generates the id.
- **Where they looked**: `v9-to-v10.md` (Build and dependencies, Annotation mapping); `skills/wow-develop/references/aggregate-sourcing.md`.
- **Workaround**: Copy the shop's specs and tests.
- **Proposed fix**:
  - Publish the test DSL as a named artifact, such as `wow-test` for V10.
  - Document what the compiler generates: factories, binding index, fragment and routes.
  - Let the generated factory default to the module's single context.
- **Blocks M7**: yes, for the documentation. The default context is not blocking.
- **Resolved**: by #4379, for the documentation. `guide/v10/testing` says where `aggregateSpec` lives (the kernel's test fixtures, `me.ahoo.wow.kernel.test`) and lists its expectations; `guide/v10/generated-code` describes the model factories (the context a parameter), the processor bindings, the binding index's name and package, the fragment and the routes. The test DSL still has no artifact of its own, and the factory still takes the context: neither blocks M7.

### G6. `wowPeers` cannot be used in the build that produces the Gradle plugin

- **Work package**: 6.3e (the shop's inventory, found while wiring the example's two services)
- **Area**: build
- **What happened**: The shop and the inventory are each other's peers (decision D139): the shop sends the inventory's commands, and the inventory reads the shop's order stream. The Gradle plugin's `wowPeers(project(":inventory-server"))` is the documented way to put a peer's Manifest on the class path. The plugin cannot be applied to a project of the build that produces it, so neither server could call `wowPeers`. Without the peer Manifest, the shop's saga fails on the inventory's command type, and the inventory does not start.
- **Where they looked**: `v9-to-v10.md`; the plugin's sources (`CollectPeerManifests`).
- **Workaround**: `example/wow-peers.gradle.kts` does what the plugin does for both servers:
  - merges the server's fragments into its Manifest and offers it as `wowManifestElements`;
  - collects peers into `META-INF/wow/peers`.
- **Proposed fix**: Share that script, or a `build-logic` convention, with the eShop. The eShop's ordering and inventory services hit the same wall. Alternatively, build the plugin as an included build so the repository's own examples can apply it.
- **Blocks M7**: no. Users apply the published plugin. It blocks the eShop's cross-context work packages until a shared workaround exists.

### G7. Events from another context's stream are untyped payloads

- **Work package**: 6.3e
- **Area**: kernel, docs
- **What happened**: A handler of another context's stream (`@OnEvent(aggregates = ["example.order"])`) gets `StateEventPayload`s in `event.events`: a name, a revision and a JSON payload. It gets these even when the producer's API classes are on the class path. The inventory's saga matches `"example.order.OrderShipped"` by string. The state is decoded into the handler's type, but the events are not.
- **Where they looked**: `v9-to-v10.md` (event processors); the KDoc of `StateEvent` and `@OnEvent`.
- **Workaround**: Match by event name constants; read what the handler needs from the published state.
- **Proposed fix**: Decode a published event into its class when the peer Manifest's schema reference resolves on the class path, or offer a typed helper (`event.payload<OrderShipped>()`). Document the shape in either case.
- **Blocks M7**: no, but it should be settled before users write cross-context handlers.

### G8. D144 calls the inventory's handler a reactor, but only a saga can send a command

- **Work package**: 6.3e
- **Area**: docs
- **What happened**: The decision describes the inventory's handler as a reactor on the order stream that sends `CommitReservation`. In V10 a `@Reactor` handler returns nothing. Only a `@Saga` derives commands, through `SagaContext.command`.
- **Where they looked**: decisions D144 and D67; the KDoc of `@Reactor` and `@Saga`.
- **Workaround**: `OrderStockSaga` is a `@Saga`.
- **Proposed fix**: Word the decision and the guide as "a saga". State plainly in the programming model page that sending a command makes the processor a saga.
- **Blocks M7**: no.
- **Met again**: in E.5. The guide now says it ("A reactor cannot send a command"), but the roadmap still calls the payment "a reactor over a simulated payment gateway". The gateway's answer becomes a command on the payment, so `PaymentGatewaySaga` is a saga that does the outside call itself.

### G9. A saga's command to another context fails only at run time without Kafka (resolved)

- **Work package**: 6.3e
- **Area**: host
- **What happened**: The shop runs without `wow.kafka.bootstrap-servers` by default. Its `OrderSaga` sends to another context, so every order placed then retries and parks `OrderSaga.onOrderCreated` in the failure ledger. The node starts without a warning.
- **Where they looked**: the host's configuration keys; `KafkaProperties`.
- **Workaround**: The shop's README says so. The `kafka` profile turns Kafka on.
- **Proposed fix**: At startup, check sagas whose Manifest `derives` or `rejected` name a context the node does not host. When the node has no `SourceTopics` producer, fail, or at least warn, naming the saga and the context.
- **Blocks M7**: yes. A misconfigured service otherwise looks healthy while it parks every saga execution.
- **Resolved**: by #4380. A node without a command transport is not built when a saga of its Manifest derives, schedules or handles the rejections of commands of a context it does not serve; the message names each saga and context and says to set `wow.kafka.bootstrap-servers`. The shop now needs its `kafka` profile to start.

### G10. Where a subscription of another context's stream starts is host configuration only

- **Work package**: 6.3e
- **Area**: compiler, host
- **What happened**: A saga starts at `END` by default. A subscription of another context's stream that joins the topic's partitions after records were published silently skips them. For the inventory that means losing shipped orders. The only fix is `wow.subscriptions.[OrderStockSaga.onOrderShipped].start: beginning` in `application.yaml`, once per handler. The annotation cannot say it.
- **Where they looked**: the KDoc of `@OnEvent`; the host's `wow.subscriptions.*` keys.
- **Workaround**: The inventory's `application.yaml` sets `beginning` for both handlers.
- **Proposed fix**: Let the annotation declare the start (`@OnEvent(start = ...)`), with host configuration overriding it. Or default subscriptions of another context's stream to `beginning`.
- **Blocks M7**: yes. The default loses data silently for a new service.

### G11. The command topic must be created by hand with exactly the log's partitions (resolved)

- **Work package**: 6.3e
- **Area**: host, backends, docs
- **What happened**: With `wow.kafka.command-intake`, the node refuses to start unless `wow.<context>.commands` exists with exactly `wow.log.partitions` partitions, 64 by default. Running the inventory locally takes a `kafka-topics --create ... --partitions 64` first, and each test creates the topic itself.
- **Where they looked**: `KafkaProperties`; decision D135.
- **Workaround**: The README gives the command; the tests create the topic with the Kafka admin client.
- **Proposed fix**: An opt-in `wow.kafka.create-topics` for development that creates the command topic with the right count. Document the topic DDL (decision D168).
- **Blocks M7**: no, once the DDL is documented.
- **Resolved**: by 6.3g (decision D168). `wow.kafka.topics.ddl=auto` creates the topics a service owns at startup, the command topic with exactly `wow.log.partitions` partitions; the default, `validate`, refuses to start and names each topic that is missing or has the wrong count. The eShop's `kafka` profiles use `auto`; the deployment guide documents the topic DDL.

### G12. The test DSL's `expectState` cannot follow `expectUnchanged` (resolved)

- **Work package**: 6.3e
- **Area**: testing
- **What happened**: A spec that checks a `NoOp` command left the state as it was fails in `expectState` with "Expected committed events but got Unchanged". There is no way to check the state after a command that changed nothing.
- **Where they looked**: the kernel's test fixtures (`AggregateSpec`).
- **Workaround**: Assert `expectUnchanged()` only.
- **Proposed fix**: Let `expectState` read the current state after an `Unchanged` outcome.
- **Blocks M7**: no.
- **Resolved**: by #4380. After a command that changed nothing, `expectState` checks the state the aggregate is in.

### G13. Tenants across services match only by convention

- **Work package**: 6.3e
- **Area**: host, docs
- **What happened**: The shop's saga addresses the inventory's stock as `stock.ref(order's tenant, productId)`. It works because both services serve anonymous callers in the default tenant `(0)`. Nothing documents that a cross-context reference carries the sender's tenant into the target, or what happens when the target has a fixed tenant policy for the aggregate.
- **Where they looked**: the host's `wow.aggregates.*.tenant` keys; decision D116.
- **Workaround**: Both services run with the default tenant.
- **Proposed fix**: Document how a tenant crosses contexts. Have the target's admission check a cross-context command's tenant against its tenant policy, with a clear rejection.
- **Blocks M7**: no, but it needs settling before multi-tenant users go cross-context.

### G14. The in-memory query backend on the test class path does not serve the snapshot queries

- **Work package**: E.1
- **Area**: testing, host, docs
- **What happened**: The catalog's query tests run on the memory backend. `guide/v10/testing` says a test that queries the snapshots "puts the in-memory reference backend on its class path: `testImplementation(testFixtures("me.ahoo.wow:wow-query:$wowVersion"))`". With it on the class path, `GET` and `POST /eshop-catalog/product/snapshot/query` still answer 404 ("No route"). The shop's tests make them work with a configuration of their own, `MemorySnapshotQueries`. It replaces the host's backends with `HostBackends.memory(partitions) { … }` and wraps the snapshot store so each saved snapshot is also written to an `InMemoryQueryStorage`. None of these types (`HostBackends`, `InMemorySnapshotStore`, `InMemoryQueryStorage`, `SnapshotStore`, `SnapshotRecord`) appears in the guide or the Skill.
  - **A trap.** Put in the server's package, such a `@Configuration` is picked up by `@SpringBootApplication`'s component scan, so every test of the module runs with it, and a test of the plain memory backend no longer sees the 404 the guide describes.
- **Where they looked**: `guide/v10/testing` (Testing the server); `guide/v10/http-api` (Queries); `v10-service.md` (Tests); the shop's tests, which the guide links.
- **Workaround**: `catalog-server`'s tests copy the shop's `MemorySnapshotQueries` into a package of their own (`me.ahoo.wow.example.eshop.catalog.testing`). `CatalogQueryMemoryTest` passes it as a source, and `CatalogPostgresTest` in `:eshop-e2e` runs the storefront queries on PostgreSQL.
- **Proposed fix**: When the reference backend is on the class path and the backend is `memory`, have the host wire it behind the snapshot queries, so the guide's sentence holds. Failing that, document the wiring with public types.
- **Blocks M7**: no. There is a workaround, but every service that tests its queries without a container meets it.

### G15. The query language has no reference: filters, combinators, value shapes, results

- **Work package**: E.1
- **Area**: edge, docs, Skills
- **What happened**: The storefront needs "the published products of a category, cheapest first", paged. The guide's only filters are `{"op":"MATCH_ALL"}` and `{"op":"ID","value":…}`. The capability descriptor (`GET …/snapshot/query`) lists each field's operators (`EQ`, `GTE`, `IN`, `BETWEEN`, …) and the root operators, but it does not give the shape of a filter node. It names no combinator (`AND`, `OR`, `NOT`) and no value shape. Each of these was found by trial and error against the `wow.query.malformed` messages:
  - **A field.** A field filter is `{"op":"EQ","field":"state.categoryId","value":"…"}`.
  - **Combinators.** `AND` takes `operands`. A guessed `children`, `filters` or `conditions` is answered "Invalid or missing value at [filter.operands]".
  - **Value lists.** `IN` takes `values`, not `value`.
  - **`BETWEEN`.** No shape for it was found. `value: [1, 5]` and `from`/`to` are both "Comparison filter value cannot be null", so the catalog uses `GTE` with `LTE`.
  - **Results.** The guide does not describe the answers. `list` is a JSON array, `paged` is `{list, total}`, `cursor` is `{list, nextCursor}`, and `single` is one record.
- **Where they looked**: `guide/v10/http-api` (Queries); `v10-service.md`; the descriptor of the product's snapshot model.
- **Workaround**: The shapes above, which `CatalogQueryContract` exercises.
- **Proposed fix**:
  - Add a query reference to the HTTP API page: the filter node per operator with its value shape, the combinators, `sort`, `pagination`, `size` and `cursor`, and each kind's answer.
  - List the combinators in the descriptor's `record`.
  - Name the expected shape in the `wow.query.malformed` message for a known `op`.
- **Blocks M7**: yes. A client cannot write anything beyond `MATCH_ALL` without guessing, and generated clients (5a.3) need the same reference.

### G16. `@QueryIndex` and the query annotations are named in the guide but not described

- **Work package**: E.1
- **Area**: docs, Skills
- **What happened**: The guide gives `@QueryIndex` one sentence: its package, its module and "declares an index the backend creates". Writing the product's indexes took the annotation's KDoc, as an IDE shows it. That is where these facts are:
  - **The attributes.** `@QueryIndex` has `name`, `on` and `with`.
  - **Where it goes.** It goes on a Kotlin property with the `@field:` use-site target. Placed on the class, it needs `on`.
  - **Keys.** Keys are full logical paths, such as `state.status`.
  - **Implicit keys.** Every index starts with the tenant and the aggregate.
  - **Dependency.** The domain module needs `wow-query-api` (`:query-api` in the repository) beside the kernel. Neither the quick start's dependency lists nor `v10-service.md` mentions it.
  - **Other annotations.** The same package holds `@QueryDecimal`, `@QueryMoney`, `@QueryTemporal`, `@QueryReference`, `@QueryAlias` and `@Sensitive`, which the guide does not mention at all.
  - **Index names.** The completed fragment names each index (`ix_product_state_brand_id_state_status_state_price`), but PostgreSQL's index is named `wow_snapshot__eshop_catalog__product_ix_product_state_<hash>`. So an operator cannot match a Manifest index to the database by name.
- **Where they looked**: `guide/v10/http-api` (Queries); `guide/v10/configuration` (`wow.query.index.ddl`); `v10-service.md`; the KDoc of `QueryIndex` and `QueryDecimal`.
- **Workaround**: Follow the KDoc. `CatalogPostgresTest` checks the PostgreSQL indexes by their definitions.
- **Proposed fix**: Add a "Query model" section to the programming model page: the query annotations with an example on a state, the `wow-query-api` dependency, what the backends build, and how the index names map.
- **Blocks M7**: no.

### G17. The guide sends container tests to an `integrationTest` source set it does not show how to declare

- **Work package**: E.1
- **Area**: build, testing, docs
- **What happened**: `guide/v10/testing` says that tests against PostgreSQL "run in containers (Testcontainers), in the module's `integrationTest` source set". Neither the quick start's build scripts nor the Testing page declares that source set. Inside the repository it comes from the root build's `v10IntegrationTestProjects` list, which a context's own build script cannot join. `catalog-server` has no `integrationTest`, so its query contract cannot run there on PostgreSQL.
- **Where they looked**: `guide/v10/index` (Set up Gradle); `guide/v10/testing`; the shop server's build script.
- **Workaround**: The contract runs on the memory backend in `catalog-server`'s `test` (G14). The PostgreSQL case is `CatalogPostgresTest` in `:eshop-e2e`, whose `integrationTest` exists.
- **Proposed fix**: Show an `integrationTest` suite in the guide, with Gradle's JVM Test Suite plugin and the Testcontainers dependency. Inside the repository, register the eShop servers in `v10IntegrationTestProjects`, so E.2 to E.5 can test their own servers on PostgreSQL.
- **Blocks M7**: no.

### G18. `expectEventTypes` takes Java classes (resolved)

- **Work package**: E.1
- **Area**: testing, docs
- **What happened**: The guide's table says `expectEventTypes(vararg types)` passes when the command "committed events of exactly these classes". The natural Kotlin call, `expectEventTypes(ProductUpdated::class)`, does not compile ("actual type is 'KClass<ProductUpdated>', but 'Class<*>' was expected"). The repository's `AGENTS.md` example passes a `KClass` too.
- **Where they looked**: `guide/v10/testing` (Expectations); `v10-service.md` (Tests).
- **Workaround**: `expectEventTypes(ProductUpdated::class.java)`.
- **Proposed fix**: Add a `KClass` overload, or a reified `expectEventType<E>()`. At least, show the parameter type in the guide.
- **Blocks M7**: no.
- **Resolved**: by #4392. `expectEventTypes` takes `KClass` too (`expectEventTypes(ProductUpdated::class)`), `expectEvent<E> { … }` checks a single event, and the guide shows the parameter types.

### G19. A rename cannot give back the unique name it leaves

- **Work package**: E.1
- **Area**: kernel, docs
- **What happened**: Brand and category names are unique. `DecideScope.reserve(namespace, key)` reserves the name as a brand takes it, and a second brand of the same name is refused. When a brand is renamed it reserves the new name, but the old one stays reserved for it forever: no other brand can take "Contoso" after the brand called Contoso is renamed. The KDoc of `reserve` says "releasing or moving a confirmed key, as a rename does, is a saga's work", but neither the guide nor the Skill names a way to release a key, from a decide function or a saga.
- **Where they looked**: `guide/v10/programming-model` (What `DecideScope` offers); `v10-service.md`; the KDoc of `DecideScope.reserve`.
- **Workaround**: None. A renamed brand keeps its old name reserved. The catalog's README says so.
- **Proposed fix**: Add `DecideScope.release(namespace, key)`, which releases the key once the events are appended, as `reserve` confirms one. Or document the saga that moves a key, with the API it calls.
- **Blocks M7**: no.

### G20. Nothing says how to serve aggregates every caller of a tenant may read

- **Work package**: E.1
- **Area**: host, edge, docs
- **What happened**: Every shopper reads the catalog. The guide says that a query sees "only its own aggregates: its tenant, and its owner and space when it has them", and that an aggregate takes its owner from its first command's caller. With a `CallerResolver` that gives each user an owner, as the shop's does:
  - **Queries.** A shopper would see only the products they created.
  - **Commands.** Only the administrator who created a product could change it; another one gets `wow.aggregate.forbidden`.
  The guide offers no setting for an aggregate whose read scope is the tenant, nor a pattern for a resolver that leaves the owner unset on some routes.
- **Where they looked**: `guide/v10/http-api` (Callers, Queries); `guide/v10/programming-model` (Owners, spaces and tenants); `guide/v10/configuration` (`wow.query.*`).
- **Workaround**: The catalog declares no `CallerResolver`. Every request is the anonymous caller of the default tenant, with no owner, so every product belongs to the tenant and every caller sees it. The host warns about the anonymous caller at startup.
- **Proposed fix**: Document the pattern for tenant-wide aggregates: a resolver that leaves the owner unset, and how command authorization (CoSec) then protects the writes. Or add a per-aggregate scope setting that makes an aggregate's owner not apply to its reads.
- **Blocks M7**: no for the eShop. Yes before M7 puts CoSec in front of users, since a reference catalog behind CoSec needs the answer.

### G21. Services on one class path read each other's peer Manifests (resolved)

- **Work package**: E.2 to E.4
- **Area**: host
- **What happened**: The order flow's end-to-end test first ran the basket, ordering and inventory services in the test's JVM, as `EshopSharedJvmTest` runs two, each selecting its context with `wow.contexts.include`. The inventory node did not start: "peer …/eshop-ordering-server.jar!/META-INF/wow/peers/eshop-inventory-service.json is of service eshop-inventory-service itself", and its context "is declared by service eshop-inventory-service and by its peer". The host reads every `META-INF/wow/peers/` on the class path, so the ordering server's copy of the inventory's Manifest becomes a peer of the inventory itself. `wow.contexts.include` narrows the models, fragments, routes and processors, but not the peers. A second, related trap: with several servers on one class path, Spring Boot reads only the first `application.yaml` on it, whichever server's that is, so each service's own configuration (profiles, `wow.subscriptions.*`, `wow.kafka.command-intake`) has to be passed again as arguments.
- **Minimal reproduction**: one class path with `:eshop-ordering-server` (whose peer is the inventory) and `:eshop-inventory-server`; start `InventoryServer` with `--wow.contexts.include=eshop-inventory`.
- **Where they looked**: `guide/v10/configuration` (`wow.contexts.include`, which names shared-JVM end-to-end tests as its use); `guide/v10/programming-model` (Peers); `guide/v10/testing`.
- **Workaround**: `EshopOrderFlowTest` runs each service in a JVM of its own, on its server's runtime class path, as `EshopE2eTest` does. That also lets each service read its own `application.yaml`.
- **Proposed fix**: Ignore a peer Manifest of the node's own service, and a peer whose contexts the node serves, or scope the peers by `wow.contexts.include`. Say in the guide that services sharing a class path share one `application.yaml`.
- **Blocks M7**: no, but E.6 runs five services and will meet it again; the guide presents shared-JVM tests as supported.
- **Resolved**: by #4385 (work package E.5). A node reads its peers without the contexts it serves (`PeerManifests.outside`): another server's copy of its own Manifest is no peer of those contexts, and a copy left with no context is dropped. Two peers of different services that claim one context still fail the startup. `EshopSharedJvmTest` starts the ordering and inventory services on one class path. The second trap stays as it is: services on one class path share the first `application.yaml` on it, so a shared-JVM test passes each service's configuration as arguments, and `EshopOrderFlowTest` keeps a JVM per service so that each reads its own.

### G22. A saga's command cannot give the aggregate it creates an owner

- **Work package**: E.2
- **Area**: kernel, docs
- **What happened**: The basket, whose owner is its buyer, submits an order to the ordering context on checkout. The order should belong to the buyer too, but "a derived command carries no caller scope" (`guide/v10/programming-model`, Sagas), and `SagaContext.command(ref, body)` takes no scope. The order the saga creates has no owner, so the kernel's scope guard does not keep it to the buyer, and nothing can query the buyer's orders by owner.
- **Where they looked**: `guide/v10/programming-model` (Owners, spaces and tenants; Sagas); the KDoc of `SagaContext`.
- **Workaround**: `SubmitOrder` carries `buyerId` in its body, against the rule that a command carries no identity field, and the order keeps it in its state.
- **Proposed fix**: Let a saga give a derived command a scope (`context.command(ref, body, scope = event.scope)`), at least for a creating command, or document the intended way (a `TransferOwner` after creation, a business field).
- **Blocks M7**: no, but every checkout-like flow across contexts meets it.

### G23. An accepted command to another context has no receipt

- **Work package**: E.3, E.4
- **Area**: kernel, docs
- **What happened**: The ordering saga reserves stock in the inventory and must learn when every reservation is accepted, to move the order on. A rejection comes back as a receipt to `@OnRejected`; an accepted command has nothing. The only way is to read the inventory's published stock stream, match `eshop-inventory.stock.StockReserved` by name and read `orderId` out of the payload's JSON (G7), and to set that subscription to `start: beginning` (G10). The guide shows reading another context's stream and sending to it, but not this round trip.
- **Where they looked**: `guide/v10/programming-model` (Other contexts, `@OnRejected`); `guide/v10/http-api` (`Wow-Wait`, which follows the hop for a client but not for a saga).
- **Workaround**: `StockConfirmationSaga` reads the stock stream and sends `ConfirmOrderStock` to the order; the inventory's `StockReserved` carries the order's id as part of the contract.
- **Proposed fix**: Document the pattern (accepted outcomes come back through the target's stream), or offer an `@OnAccepted` (or `@OnCommitted`) counterpart of `@OnRejected` for commands a saga derives.
- **Blocks M7**: no.

### G24. The testing guide's saga example derives other ids the second time (resolved)

- **Work package**: E.3
- **Area**: docs, testing
- **What happened**: `guide/v10/testing` (Testing sagas) calls the handler twice with one `ExecutionSagaContext` to show that "handling the commit again derives the same requests". Written that way, the test fails: the context numbers the commands it derives, so the second call continues the numbering and derives other ids. A fresh context per call, as the runtime gives each handling, derives the same ones.
- **Where they looked**: `guide/v10/testing`; `skills/wow-develop/references/v10-service.md` (Tests).
- **Workaround**: The eShop's saga tests build a fresh context for each call.
- **Proposed fix**: Build the context twice in the example, and say why.
- **Blocks M7**: no, but the first saga test a user copies fails.
- **Resolved**: by #4392. `sagaSpec` gives each call of a handler a fresh `ExecutionSagaContext`, and `expectDeterministic()` handles the commit again with a new one; the guide's example uses it and says why a hand-built context must be new for each call.

### G25. Smaller gaps of the test DSL (besides G18) (resolved)

- **Work package**: E.2, E.3
- **Area**: testing, docs
- **What happened**:
  - `expectState` after `expectRejected` fails with "Expected committed events but got Rejected": G12 lets it follow `expectUnchanged` only, and the guide's sentence covers only that case.
  - `advanceTime(d) { … }` fails with "No command the aggregate scheduled was due by …" when nothing is due, so a spec cannot show that a cancelled schedule does not fire; the guide does not mention it.
- **Where they looked**: `guide/v10/testing`.
- **Workaround**: No state check after a rejection; the cancelled schedule is checked with `expectScheduleCancelled`, and the late command is sent by hand to show the order rejects it.
- **Proposed fix**: Let `expectState` read the current state after a rejection; let `advanceTime` take a block that expects nothing due (or document the failure).
- **Blocks M7**: no.
- **Resolved**: by #4392. `expectState` reads the current state after a rejection, and after an exception `expectError<T>()` checks; `advanceTime` without a block never fails, its block still fails when nothing was due (documented), and `expectNothingDue(within)` shows that a cancelled schedule does not fire.

### G26. An `@OnRejected` handler that only accepts a rejection must still name a command

- **Work package**: E.3
- **Area**: compiler, docs
- **What happened**: A release of a reservation the inventory rejects (for a product it has no stock of) needs no compensation, but without a handler the order parks. The guide says to "return `null` from the handler to accept a rejection". The handler then needs a command return type all the same (`Command<CancelOrder>?`), so the Manifest lists `CancelOrder` among its `derives`, and detekt flags the function (`FunctionOnlyReturningConstant`).
- **Where they looked**: `guide/v10/programming-model` (`@OnRejected`).
- **Workaround**: `OrderProcessSaga.onReleaseRejected` returns `Command<CancelOrder>?` and is always null, with the detekt rule suppressed.
- **Proposed fix**: Allow `Unit` (or `Nothing?`) as the return type of an `@OnRejected` handler that never compensates, or a declarative way to accept the rejections of a command type (`@OnRejected(accept = [...])`).
- **Blocks M7**: no.

### G27. A service whose saga sends to another context cannot run locally without Kafka

- **Work package**: E.2, E.3, E.5
- **Area**: host, docs
- **What happened**: The quick start runs a new service on the memory backend with `./gradlew :<svc>-server:run`. Since G9's check, the basket, ordering and payment services refuse to start that way: their sagas send other contexts' commands. The guide gives an in-memory transport only for tests (`InMemoryTopics` and `SourceTopics` beans), not a switch for trying a service out.
- **Where they looked**: `guide/v10/index` (Run it and send a command); `guide/v10/programming-model` (Sending a command to another context).
- **Workaround**: The servers keep Kafka in a `kafka` profile, like the shop; the README says to start Kafka and create the topics first.
- **Proposed fix**: A host property for in-memory topics on the memory backend (`wow.kafka.in-memory`, say), or a note in the quick start.
- **Blocks M7**: no.

### G28. Testing an `@OnRejected` handler needs the services of every processor of the module (resolved)

- **Work package**: E.5
- **Area**: testing, docs
- **What happened**: `guide/v10/testing` takes an `@OnRejected` handler's binding from `ModelProvider.load().single().processors(Dependencies.NONE)`. In the payment module, that fails before it reaches the handler: "MissingDependencyException: No dependency of …PaymentGateway to build the aggregate models with". `processors` builds every processor of the module, and `PaymentGatewaySaga` takes the gateway, though the handler under test (`OrderPaymentSaga.onPayRejected`) does not. The message also speaks of aggregate models, not processors.
- **Where they looked**: `guide/v10/testing` (Testing sagas).
- **Workaround**: `OrderPaymentSagaTest` passes `Dependencies.of(SimulatedPaymentGateway(…))`, a service the saga under test does not use.
- **Proposed fix**: Build processors lazily or one at a time (`processor("OrderPaymentSaga", dependencies)`), or give the test fixtures a way to bind one `@OnRejected` handler of a processor instance; say in the guide that `Dependencies.NONE` works only for a module whose processors take no services. Name processors, not models, in the message.
- **Blocks M7**: no.
- **Resolved**: by #4392. A test takes one processor's handlers from its generated `<processor>Handlers(dependencies)` function, which constructs that processor only; `sagaSpec(...).whenRejected(command, code, message)` hands a rejection to its `@OnRejected` handler. The guide says that `ModelProvider.processors` needs the services of every processor. The `MissingDependencyException` message still speaks of aggregate models (it is the kernel's, shared with `models`).

### G29. A saga that derives one of several commands names them as strings

- **Work package**: E.5
- **Area**: compiler, docs
- **What happened**: `PaymentGatewaySaga` sends `ConfirmPayment` or `FailPayment`, whichever the gateway answers. Its handler returns `Command<*>?`, so the guide asks for `@OnEvent(derives = ["eshop-payment.payment.ConfirmPayment", "eshop-payment.payment.FailPayment"])`: full type names as strings, where `@OnCommand(produces = [...])` takes classes. The guide does not say whether the compiler checks the names, or whether a sealed command type in the return type would let it infer them.
- **Where they looked**: `guide/v10/programming-model` (Sagas).
- **Workaround**: The names as constants in the saga.
- **Proposed fix**: Accept classes in `derives` (`derives = [ConfirmPayment::class]`), or infer the commands from a sealed type in `Command<…>`; document whether a name that decides no command fails the build.
- **Blocks M7**: no.

### G30. A wait chain names only contexts the origin node has Manifests of, in 4 hops

- **Work package**: E.6
- **Area**: runtime, host, docs
- **What happened**: The checkout's `Wow-Wait` should follow the order to its payment. The basket refused the chain with `wow.wait.invalid`, "Hop committed(eshop-inventory) names an unknown context": the basket knows only its own Manifest and its peers', and its only peer was the ordering, the one context it sends to. With the inventory's and the payment's Manifests as peers too, the chain is accepted and followed: 15 hops, until the payment's `PayOrder` is committed. It also needs `wow.wait.max-hops` raised from its default of 4, since a saga adds two hops (its `handled` and the next `committed`) to every command it derives.
- **Where they looked**: `guide/v10/http-api` (`Wow-Wait`: "a context the directory knows", `wow.wait.max-hops`); `guide/v10/configuration`.
- **Workaround**: The basket server takes `:eshop-inventory-server` and `:eshop-payment-server` as `wowPeers`, though it sends them nothing, and the test runs it with `--wow.wait.max-hops=16`.
- **Proposed fix**: Say in the guide that a chain may name only contexts whose Manifests the origin node has, and that peers are the way to add them; or let the origin accept a hop of a context it does not know and leave the check to the node that reaches it. Say how many hops a saga adds, and whether 4 is meant as the practical limit.
- **Blocks M7**: no.

### G31. A dataset query warns that the Manifest has no state schema of the dataset

- **Work package**: E.6
- **Area**: host
- **What happened**: The first query of the sales dataset logs "The Manifest carries no state schema of eshop-ordering.sales: only its system fields are queried. Build its module with the Wow Gradle plugin, which writes the schemas." The dataset is not an aggregate and has no state schema; its fields come from its dataset file, and the query of `status` and `totalAmount` works.
- **Where they looked**: `guide/v10/http-api` (Datasets).
- **Workaround**: None; the warning is ignored.
- **Proposed fix**: Do not ask the Manifest's state schemas about a dataset's name, or leave the warning out for one.
- **Blocks M7**: no.

### G32. With two nodes, a saga's command on another node can leave the owner deciding on a stale state (resolved)

- **Work package**: E.6
- **Area**: runtime
- **What happened**: With the ordering on two nodes on one database, the full chain's order was never paid: the payment saga's `PayOrder` was rejected, "The order alice-1 is AWAITING_VALIDATION: only a STOCK_CONFIRMED order can be paid", while the log held the order at version 4, `STOCK_CONFIRMED`; the payment was then refunded. `StockConfirmationSaga` reads the inventory's stock stream, whose partitions are not the order's, and the commands a saga derives in its own context run on the node that runs the subscription (`DerivedCommands` through `WaitCoordinator.forward`, not the `CommandRouter`). The node that does not own the order's partition committed its `ConfirmOrderStock` (versions 3 and 4); the owner's cached state stayed at version 2. A command that appends would conflict on the version and be decided again (D45, D71), but a rejection appends nothing, so the owner rejected `PayOrder` against version 2.
- **Where they looked**: `guide/v10/deployment` (several nodes of a service); the test's traces of the order and the payment from both nodes.
- **Workaround**: `EshopFullChainTest` runs the ordering on one node. `EshopStack(orderingNodes = 2)` reproduces it: the first test fails with the order `STOCK_CONFIRMED` and its payment `REFUNDED`.
- **Proposed fix**: Route a saga's derived commands of its own context like any other (to the partition's owner, degrading to local as D71 says), and have a rejection or an unchanged outcome decided on a cached state confirm the state's version against the log before it answers.
- **Blocks M7**: yes: a service with a saga on another context's stream cannot run on two nodes.
- **Resolved**: by #4390. The commands a saga derives in its own context, the compensations its `@OnRejected` handler derives, a timer's command and the command intake's go through the node's router like an admitted command: to the owner of their partition, degrading to the local node when the forward fails or the owner is unknown (D6, D71). The second defect is closed in the kernel: without fencing (D45) any node may still append in the degraded case, and only an append meets the version-unique key, so a rejection or an unchanged outcome decided on a cached state stands only once the log holds nothing after that state's version (one read of the next version slot, only on those outcomes); otherwise the state is reloaded and the command decided again. `EshopFullChainTest` runs the ordering on two nodes.

### G33. A saga starting at the end loses the first entries of a partition when its node is killed before its first checkpoint (resolved)

- **Work package**: E.6
- **Area**: runtime
- **What happened**: The ordering node was killed right after a checkout committed the first order of a partition, and a node of another id took the partition over. The order stayed `SUBMITTED`: none of the order process's subscriptions handled it, and their checkpoints in that partition stood at 2, past the order (sequence 1) and the next one (sequence 2). A saga starts at the log's end by default (`StartFrom.END`), and a subscription saves its starting checkpoint at once only when it is not 0 (`SubscriptionRuntime.worker`: "Saved at once, so a restart before the first entry does not move the start to a later end", `if (it > 0)`). In an empty partition the start, 0, was not saved; the killed node had not saved a checkpoint since, so its successor started again at the end, by then 2.
- **Where they looked**: `guide/v10/programming-model` (where a subscription starts); the ordering's `wow_checkpoint` and log tables.
- **Workaround**: `EshopFullChainTest` first places and waits out an order of its own in the partition, so the order process's checkpoints exist there before the kill. Setting the sagas' `start` to `beginning` would avoid it too.
- **Proposed fix**: Save the starting checkpoint at once whatever it is, 0 included.
- **Blocks M7**: yes: a node killed in the first moments of a partition loses its saga effects.
- **Resolved**: by #4390. A subscription saves its starting checkpoint as it starts, 0 included. `EshopFullChainTest` no longer warms the partition up with an order of its own before the kill.
