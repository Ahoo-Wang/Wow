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

### G1. No V10 guide for writing a new service

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

### G5. Where the test DSL lives, and what the compiler generates, is not documented

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

### G11. The command topic must be created by hand with exactly the log's partitions

- **Work package**: 6.3e
- **Area**: host, backends, docs
- **What happened**: With `wow.kafka.command-intake`, the node refuses to start unless `wow.<context>.commands` exists with exactly `wow.log.partitions` partitions, 64 by default. Running the inventory locally takes a `kafka-topics --create ... --partitions 64` first, and each test creates the topic itself.
- **Where they looked**: `KafkaProperties`; decision D135.
- **Workaround**: The README gives the command; the tests create the topic with the Kafka admin client.
- **Proposed fix**: An opt-in `wow.kafka.create-topics` for development that creates the command topic with the right count. Document the topic DDL (decision D168).
- **Blocks M7**: no, once the DDL is documented.

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
