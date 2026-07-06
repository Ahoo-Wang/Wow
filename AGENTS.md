# Wow - Agent Instructions

## Build & Run

```bash
./gradlew <module>:check
./gradlew <module>:test
./gradlew <module>:clean <module>:check --stacktrace
./gradlew detekt
./gradlew detekt --auto-correct
./gradlew build
```

Use Gradle module paths from `settings.gradle.kts`, for example `:wow-api`, `:wow-core`, `:wow-spring-boot-starter`, `:wow-compensation-domain`, `:example-domain`, and `:wow-test`.

For runnable sample services:

```bash
./gradlew :example-server:run
./gradlew :example-transfer-server:run
./gradlew :wow-compensation-server:run
```

Compensation dashboard:

```bash
cd compensation/dashboard
pnpm install
pnpm dev
pnpm test
pnpm lint
pnpm build
pnpm coverage
```

Documentation site:

```bash
cd documentation
pnpm install
pnpm docs:dev
pnpm docs:build
pnpm docs:preview
```

## Testing

The JVM code uses JUnit Jupiter through JUnit 6, MockK, Reactor test support, and `me.ahoo.test:fluent-assert-core`. Prefer the FluentAssert `.assert()` extension in Kotlin tests instead of AssertJ `assertThat()`.

```bash
./gradlew :wow-core:test
./gradlew :wow-core:test --tests "me.ahoo.wow.command.CommandGatewayTest"
./gradlew :example-domain:test --tests "me.ahoo.wow.example.domain.order.OrderSpec"
./gradlew :wow-compensation-domain:check
./gradlew :wow-it:check --stacktrace
```

Domain tests usually use the Wow test DSL:

```kotlin
class CartSpec : AggregateSpec<Cart, CartState>({
    on {
        whenCommand(AddCartItem(productId = "productId", quantity = 1)) {
            expectNoError()
            expectEventType(CartItemAdded::class)
            expectState {
                items.assert().hasSize(1)
            }
        }
    }
})
```

CI sets `CI=GITHUB_ACTIONS`; Gradle test retry is enabled only in CI with up to 2 retries and 20 max failures. Domain modules enforce Jacoco coverage, commonly 80% minimum.

## Project Structure

```text
wow-api/                    Pure API contracts: commands, events, naming, queries, modeling
wow-core/                   Core CQRS, event sourcing, messaging, projection, saga runtime
wow-compiler/               KSP processor for command/event metadata and OpenAPI generation
wow-spring/                 Spring integration primitives
wow-spring-boot-starter/    Auto-configuration plus optional feature capabilities
wow-query/                  Query model support
wow-kafka/                  Kafka command/event bus integration
wow-mongo/                  MongoDB event store and snapshot store
wow-redis/                  Redis event store and snapshot store
wow-elasticsearch/          Elasticsearch projection support
wow-webflux/                Spring WebFlux command endpoint integration
wow-opentelemetry/          Tracing and metrics integration
wow-cosec/                  CoSec authorization integration
wow-cocache/                CoCache projection caching
wow-apiclient/              REST API client using CoApi
wow-openapi/                OpenAPI support
wow-schema/                 JSON Schema generation
wow-bi/                     BI sync script generation
wow-models/                 Shared model helpers
wow-bom/, wow-dependencies/ BOM and centralized dependency versions
test/                       wow-test DSL, TCK, mocks, integration tests, coverage report
compensation/               Compensation domain, API, core, server, and React dashboard
example/                    Kotlin order/cart sample and Java transfer sample
documentation/              VitePress documentation site
document/                   Design docs, diagrams, and static assets
skills/                     Project-local Codex skills and agent definitions
```

Core dependency flow:

```text
wow-api -> wow-core -> wow-spring -> wow-spring-boot-starter
                    -> infrastructure modules: kafka, mongo, redis, elasticsearch
                    -> integration modules: webflux, opentelemetry, cosec, cocache, apiclient
```

`wow-spring-boot-starter` declares Gradle feature variants for `mongo-support`, `redis-support`, `mock-support`, `kafka-support`, `webflux-support`, `elasticsearch-support`, `opentelemetry-support`, `openapi-support`, and `cosec-support`.


## Code Style

- Kotlin 2.3.20, JVM toolchain 17, `kotlin.code.style=official`, and KSP 2 are configured in Gradle.
- Spring Boot dependency management is centralized through `wow-dependencies`; current Spring Boot is 4.0.6.
- All JVM packages live under `me.ahoo.wow`; examples use `me.ahoo.wow.example`.
- Source files use the Apache 2.0 copyright header already present in the repository.
- Keep command/event paths reactive with Reactor `Mono`/`Flux`; do not introduce blocking calls into core dispatch, event store, projection, saga, or transport flows.
- Prefer focused interfaces and module boundaries: API contracts in `wow-api`, runtime behavior in `wow-core`, Spring wiring in `wow-spring*`, and storage or transport concerns in their dedicated modules.
- Serialization uses Jackson; framework code uses the existing Jackson stack and Spring compatibility modules where already present.
- ID generation uses CosId and existing Wow ID helpers.
- Logging uses kotlin-logging, SLF4J, and Logback.
- Detekt uses `config/detekt/detekt.yml`; line-length and several style rules are intentionally relaxed.

Typical Kotlin API style:

```kotlin
interface CommandBus :
    MessageBus<CommandMessage<*>, ServerCommandExchange<*>>,
    TopicKindCapable {
    override val topicKind: TopicKind
        get() = TopicKind.COMMAND
}
```

Dashboard code uses React 19, TypeScript 6, Vite 8, Ant Design 6, React Router 7, Vitest, ESLint, and generated Fetcher clients under `compensation/dashboard/src/generated/`. Do not hand-edit generated client files unless the generator input is unavailable and the user accepts that tradeoff.

## Version Management

The project version is the `version` property in `gradle.properties` and is currently `8.6.0`. Keep dependent documentation, examples, package metadata, and release notes in sync when bumping it. Third-party versions are centralized in `gradle/libs.versions.toml` and the `wow-dependencies` module.

## CI And Release Workflows

GitHub Actions run module-level checks from `.github/workflows/`:

- `integration-test.yml` runs Gradle checks for core modules.
- `compensation-test.yml` checks compensation core and domain modules.
- `example-java-test.yml` builds the Java transfer example modules.
- `codecov.yml` publishes coverage.
- `documentation-deploy.yml`, `example-deploy.yml`, and `compensation-deploy.yml` deploy docs and sample apps.
- `package-deploy.yml` publishes to GitHub Packages and Maven Central when a GitHub Release is created or the workflow is manually dispatched.

Before changing release or publish behavior, inspect the workflow and Gradle publishing configuration together.

## Boundaries

- Always run the narrowest relevant Gradle, pnpm, or docs command before reporting a change as complete.
- Always add or update tests when changing command handling, event sourcing, projections, sagas, compensation behavior, serialization, schema generation, or generated metadata.
- Always preserve public API compatibility unless the user explicitly asks for a breaking change.
- Ask first before changing Gradle module structure, feature capabilities, generated OpenAPI/schema contracts, CI/CD workflows, publishing credentials, or release automation.
- Ask first before adding dependencies or moving responsibilities across module boundaries.
- Never commit secrets, signing keys, Maven credentials, GitHub tokens, generated build output, `node_modules/`, `.gradle/`, or IDE-local state.
- Never edit generated dashboard clients in `compensation/dashboard/src/generated/` as the primary fix when the OpenAPI source or generator can be fixed instead.
- Never bypass Reactor with blocking code in core runtime paths.

## Documentation

- Root README: `README.md`
- Chinese README: `README.zh-CN.md`
- VitePress docs: `documentation/docs/`
- Design assets and diagrams: `document/design/`
- Project-local skills: `skills/`
