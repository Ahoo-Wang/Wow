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

### G3. The rules for a context name are not documented

- **Work package**: E.0
- **Area**: compiler, docs
- **What happened**: The eShop needs context names that do not clash with the shop's `example`. A dotted name such as `eshop.catalog` reads naturally, but nothing says whether a name may contain a dot. Several places write a type as `<context>.<aggregate>`: the cross-context references (`@OnEvent(aggregates = ["ctx.agg"])`, `derives = ["ctx.agg.Command"]`), the topics (`wow.<context>.<aggregate>.state`) and the host's `wow.aggregates.[<context>.<aggregate>]` keys. With a dot inside the context name, these become ambiguous. The compiler does not reject such a name, so the mistake would surface late, if at all.
- **Where they looked**: `v9-to-v10.md` (Annotation mapping, `@BoundedContext`).
- **Workaround**: Use hyphenated names: `eshop-catalog`, `eshop-basket`, `eshop-ordering`, `eshop-inventory`, `eshop-payment`.
- **Proposed fix**:
  - Document the allowed characters of context and aggregate names.
  - Make the compiler reject an invalid name on `@BoundedContext` (and `@Name`) with a message that says why.
- **Blocks M7**: no. It has a workaround, but every new user meets the question.

### G4. A host serves every domain module on its class path

- **Work package**: E.0
- **Area**: host
- **What happened**: The host finds the models through `ServiceLoader` and serves every binding index it finds. There is no setting to choose the contexts a node serves. Two services cannot share a class path: each would serve both contexts. This rules out starting two eShop servers in one test JVM, and a modular monolith that switches contexts by configuration. Nothing documents this behaviour.
- **Where they looked**: `v9-to-v10.md`; the host's configuration keys in the shop's `application.yaml`.
- **Workaround**: `:eshop-e2e` starts each service in a JVM of its own. Each JVM gets the runtime class path of that service's server project, which the build passes to the test (`example/eshop/e2e/build.gradle.kts`).
- **Proposed fix**: Add a host property that names the contexts a node serves, defaulting to all. At minimum, document that a service's class path defines its contexts.
- **Blocks M7**: no, for the eShop. It is worth deciding before users build multi-service test harnesses.

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
