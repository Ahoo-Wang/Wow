# Hosting: build, modules, Spring Boot, backends

The job: turn a domain into a running service. Wiring lives in the host only; the domain module knows no Spring and no store. Read the keys and defaults from the target's configuration metadata (`META-INF/spring-configuration-metadata.json` of `wow-spring-boot`) or its generated reference, never from memory: a `wow.*` key no property names is reported at startup.

Requirements: Gradle (there is no Maven build), JDK 21, Kotlin with KSP, Spring Boot 4 on the server, Jackson 3 (`tools.jackson`) in domain code that touches JSON.

## Module layout and build

| Module | Holds | Depends on |
| --- | --- | --- |
| `<svc>-api` | Commands, events, value types: what other contexts compile against | `wow-api` |
| `<svc>-domain` | Roots, states, `@ErrorCode` constants, processors | `<svc>-api`, `api("wow-kernel")`; plugin `me.ahoo.wow`; `wow-query-api` when it uses the query field annotations; tests `testImplementation("me.ahoo.wow:wow-test")` |
| `<svc>-server` | Spring Boot application, service beans | domain, `wow-spring-boot`, a backend (`wow-postgres` or `wow-mongo`), optionally `wow-kafka`, `wow-clickhouse`, `wow-cosec` |

- Import the BOM `platform("me.ahoo.wow:wow-bom:<version>")` and apply the plugin `id("me.ahoo.wow")` of the same version to the domain module. The plugin applies KSP with `wow-compiler`, assembles the module's Manifest fragment and the service's Manifest, checks the Manifest against `manifest/baseline.json` in `check` (`updateManifestBaseline` rewrites it), and puts the peer contract into the api jar. Its `wow { }` block: `context`, `addCompilerDependency`, `schemaOverrides`, `serviceName`, `serviceVersion`, `manifestBaseline`.
- The plugin fails a module without the Kotlin JVM plugin (a Java domain applies `kotlin("jvm")` too), and is not compatible with Gradle's isolated projects.
- A Jackson type that is not written as an object (a custom serializer, `@JsonValue`) needs its schema in `schemaOverrides`, or the build fails rather than guess.
- Reactor return types in handlers need `wow-reactor` on the class path, or compilation fails.

## What the compiler generates

- `<root>Model(context, services…, configure = {})` beside each root (`Account` gives `accountModel`); the context is always a parameter.
- `<processor>Handlers(dependencies)` per processor.
- One `ModelProvider` per module, named after the module (`eshop-catalog-domain` gives `EshopCatalogDomainModelProvider`), registered in `META-INF/services/me.ahoo.wow.kernel.ModelProvider`. The host serves every provider on its class path unless `wow.contexts.include` / `exclude` select some; an unknown name fails the startup.
- `META-INF/wow/manifest/<module>.json`: the module's Manifest fragment (`reads.md`).

## The host

`wow-spring-boot` wires everything from the class path: add the server module's dependencies, a `@SpringBootApplication`, and configuration. Common keys (verify each):

- `wow.service` (or `spring.application.name`): the service's name.
- `wow.backend`: `auto` (the default: the one backend on the class path), `memory`, `postgres`, `mongo`. PostgreSQL needs a `DataSource` bean; MongoDB a sync `MongoClient` bean on a replica set; memory is one node and serves no snapshot queries unless `wow.query.memory.enabled`.
- `wow.internal.port` (9090), `wow.internal.host`, `wow.internal.token` (the cluster token; a node without one starts only in a development profile or on loopback).
- `wow.log.partitions` (64; a power of two dividing 4096): set once, before the log holds entries.
- `wow.cluster.router`, `wow.wait.default-timeout`, `wow.subscriptions.[<name>].start`, `wow.subscription.retry.*`, `wow.subscription.parked.*`, `wow.aggregates.[<context>.<aggregate>].tenant`.
- `wow.kafka.bootstrap-servers`, `wow.kafka.command-intake.enabled` (`cross-context.md`); `wow.topics.in-memory` for local runs without Kafka.
- `wow.ops.enabled`, `wow.ops.webhook.*`, `wow.ops.publish.enabled` (`processes.md`).
- `wow.health.*`: the node's health under Actuator's `wow`, `readiness` and `liveness` groups.

## Callers and request facts

- A `CallerResolver` bean (`me.ahoo.wow.http.caller`) returns the `Caller` (`me.ahoo.wow.api.caller`) of each request; without one, every request is anonymous in tenant `(0)`. With `wow-cosec`, CoSec's resolver reads the caller from the token; `Caller.app` is the application CoSec authorized (`wow.cosec.trust-injected-app` only behind a gateway that authorizes `CoSec-App-Id`).
- Authorization of commands: a `CommandAuthorizer` bean decides per caller and command.
- Request facts: the edge records `app_id`, `remote_ip` and `user_agent` itself (`wow.http.client-facts.enabled: false` records neither: they are personal data that cannot be erased once written); other headers become facts only when listed in `wow.http.request-facts`. A derived command inherits `app_id` only.

## Safety of a deployment

- The internal port carries the cluster: private network only, never exposed.
- Events are permanent: keep personal data out of events and request facts.
- One tenant space across contexts: tenant ids cross as they are.
