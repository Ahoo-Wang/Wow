# Wow Test Rewrite Design

Date: 2026-06-05

## Context

Wow currently has a large JVM test surface outside `test/wow-test`: hundreds of
test files across local tests, contract tests, and container-backed integration
tests. The existing test runtime split introduced local, domain, contract, and
integration aggregate tasks, but the domain split adds a global layer for tests
that should belong to the domain modules themselves.

The current test code also mixes several styles and responsibilities:

- Some tests only execute code without asserting observable behavior.
- Some asynchronous tests rely on fixed sleeps or blocking calls.
- Domain examples contain both the newer `AggregateSpec` / `SagaSpec` style and
  older verifier-style tests.
- Container-backed integration tests use a mix of centralized launchers,
  module-local launchers, and per-test initializers.
- Some Spring Boot auto-configuration tests check bean presence but do not
  systematically cover conditions, user overrides, and fallback behavior.

This design treats production code as the only source of truth. Existing tests
may be used as discovery hints, but rewritten tests must be derived from the
current production behavior and module boundaries.

## Goals

- Rewrite tests outside `test/wow-test` from production-code behavior.
- Keep `test/wow-test` intact and available as a test DSL dependency.
- Reduce the global test taxonomy to local, contract, and integration tests.
- Keep domain behavior tests inside their owning domain modules.
- Centralize container-backed test infrastructure in `test/wow-tck`.
- Make async and reactive tests deterministic.
- Keep each implementation stage independently verifiable.

## Non-Goals

- Do not change production behavior as part of the test rewrite.
- Do not introduce `.devcontainer/` or Docker Compose development environments.
- Do not make tests depend on externally pre-started services.
- Do not rewrite or reorganize `test/wow-test`.
- Do not preserve old tests merely for historical coverage.

## Test Layers

The top-level test model should have three layers.

### Local Tests

Local tests live in `src/test`. They include ordinary unit tests, domain module
behavior tests, Spring Boot auto-configuration context tests, WebFlux handler
tests, compiler tests, schema tests, and other tests that do not require
external middleware.

Domain behavior tests are not a separate global layer. They belong to domain
modules such as `:example-domain`, `:example-transfer-domain`, and
`:wow-compensation-domain`. These modules can continue to use `AggregateSpec`
and `SagaSpec`.

The root local aggregate should be named `allLocalTest` and replace the current
unit/domain split. It should include all local `src/test` tasks that are safe to
run without Docker. During the rewrite, `allUnitTest` should remain only as a
deprecated compatibility alias that delegates to `allLocalTest`; `allLocalTest`
is the canonical task and documentation target.

### Contract Tests

Contract tests live in `src/contractTest`. They validate implementations against
TCK contracts and should remain local-safe. Examples include command bus,
domain event bus, state event bus, event store, snapshot repository, command
gateway, command dispatcher, and tracing wrapper contracts.

Contract tests should focus on the observable contract. They should not
duplicate implementation-specific local tests.

### Integration Tests

Integration tests live in `src/integrationTest`. They are the only tests that
depend on Testcontainers-managed middleware such as MongoDB, Redis, MariaDB,
Kafka, and Elasticsearch.

`check` should not start containers implicitly. `allIntegrationTest` should be
the explicit full container-backed test entrypoint.

## `wow-tck` Container Fixture Design

Container-backed tests should depend on fixtures in `test/wow-tck` instead of
module-local launchers or ad hoc setup. The fixtures should provide a single
place for container images, startup, connection metadata, initialization, and
resource cleanup.

Recommended fixture set:

- `MongoTestFixture`
- `KafkaTestFixture`
- `RedisTestFixture`
- `MariaDbTestFixture`
- `ElasticsearchTestFixture`

Each fixture should handle:

- Starting the container.
- Exposing connection parameters and client factories.
- Initializing required schemas, templates, or connection factories.
- Providing unique database, topic, index, table, or key-prefix helpers where
  isolation is needed.
- Closing clients and connection factories created by the fixture.
- Reporting useful container state and connection information on failure.

The preferred usage style is `@RegisterExtension` because it makes lifecycle and
dependencies explicit at the test class level:

```kotlin
class MongoEventStoreTest : EventStoreSpec() {
    @RegisterExtension
    val mongo = MongoTestFixture()

    override fun createEventStore(): EventStore {
        return MongoEventStore(mongo.database(namedAggregate)).metrizable()
    }
}
```

Fixture migration targets:

- Move the R2DBC MariaDB launcher into `test/wow-tck`.
- Remove repeated `@BeforeAll { Launcher.isRunning }` checks from Kafka and
  Elasticsearch integration tests.
- Replace Redis per-test `RedisInitializer` usage with a Redis fixture.
- Keep Elasticsearch authentication waiting logic, but hide it inside the
  Elasticsearch fixture.
- Centralize MongoDB, Redis, MariaDB, Kafka, and Elasticsearch image versions in
  `test/wow-tck`.

## Rewrite Phases

### Phase 1: Test Infrastructure

Rewrite `test/wow-tck` support code, except `test/wow-test`. Establish the
container fixtures, contract fixture naming, and lifecycle conventions.

Primary verification:

```bash
./gradlew :wow-tck:test :wow-tck:check
```

### Phase 2: Core Local Behavior

Rewrite local tests for:

- `:wow-api`
- `:wow-core`
- `:wow-query`
- `:wow-models`
- `:wow-schema`
- `:wow-openapi`

Focus areas include commands, events, modeling, query DSL, serialization,
schema generation, OpenAPI metadata, naming, idempotency, waiting, dispatching,
and Reactor behavior.

Primary verification:

```bash
./gradlew :wow-api:test :wow-core:test :wow-query:test :wow-models:test :wow-schema:test :wow-openapi:test
```

### Phase 3: Contract Behavior

Rewrite contract tests for:

- `:wow-core`
- `:wow-opentelemetry`
- `:wow-mock`

These tests should use TCK contracts as the behavior source and avoid
implementation-detail assertions.

Primary verification:

```bash
./gradlew :wow-core:contractTest :wow-opentelemetry:contractTest :wow-mock:contractTest allContractTest
```

### Phase 4: Spring and WebFlux Local Behavior

Rewrite local tests for:

- `:wow-spring`
- `:wow-spring-boot-starter`
- `:wow-webflux`
- `:wow-cosec`
- `:wow-cocache`
- `:wow-opentelemetry`

Focus areas include auto-configuration conditions, user bean overrides,
fallback behavior, WebFlux request extraction, responses, exception mapping,
headers, and local tracing behavior.

Primary verification:

```bash
./gradlew :wow-spring:test :wow-spring-boot-starter:test :wow-webflux:test :wow-cosec:test :wow-cocache:test :wow-opentelemetry:test
```

### Phase 5: Infrastructure Integration

Rewrite container-backed integration tests for:

- `:wow-mongo`
- `:wow-redis`
- `:wow-r2dbc`
- `:wow-kafka`
- `:wow-elasticsearch`
- `:wow-it`

All container dependencies should go through `test/wow-tck` fixtures.

Primary verification:

```bash
./gradlew :wow-mongo:integrationTest :wow-redis:integrationTest :wow-r2dbc:integrationTest :wow-kafka:integrationTest :wow-elasticsearch:integrationTest :wow-it:integrationTest allIntegrationTest
```

### Phase 6: Domain and Example Modules

Rewrite local tests for:

- `:example-domain`
- `:example-transfer-domain`
- `:wow-compensation-api`
- `:wow-compensation-core`
- `:wow-compensation-domain`
- `:wow-compensation-server`

Domain behavior tests should stay in their owning modules and use
`AggregateSpec` / `SagaSpec` where appropriate. Old verifier-style `tradition`
tests should be deleted instead of preserved as parallel coverage.

Primary verification:

```bash
./gradlew :example-domain:test :example-transfer-domain:test :wow-compensation-domain:test :wow-compensation-core:test :wow-compensation-api:test :wow-compensation-server:test
```

### Phase 7: Miscellaneous, Coverage, and CI

Rewrite remaining tests and update build orchestration for:

- `:wow-bi`
- `:wow-compiler`
- `test/wow-tck/src/test`
- coverage aggregation
- test runtime documentation
- GitHub Actions workflows

Remove the independent domain aggregate and workflow. Local coverage should
include domain module tests through the local layer.

Final verification:

```bash
./gradlew allLocalTest allContractTest allIntegrationTest detekt build
```

## Old Test Deletion Policy

- At the start of each phase, delete old tests in that phase's scope.
- Preserve test resources only when production-code-derived rewritten tests
  still need them.
- Do not migrate `@Disabled` tests. Rewrite the behavior only if it is still a
  production-code requirement.
- Delete or rewrite tests that execute code without assertions.
- Replace fixed sleeps with Reactor verification, explicit await conditions,
  injected clocks or sleep functions, or fixture hooks.
- Do not recreate large test files by old filename. Split tests by behavior
  theme and production boundary.
- Do not use old tests as the source of truth for expected behavior.

## Build and CI Changes

- Remove the separate global domain aggregate task.
- Introduce `allLocalTest` for all local `src/test` tasks, including domain
  modules.
- Keep `allUnitTest` only as a deprecated compatibility alias that delegates to
  `allLocalTest`.
- Keep `allContractTest` as the local-safe TCK aggregate.
- Keep `allIntegrationTest` as the explicit container-backed aggregate.
- Remove or merge the domain coverage report into local coverage.
- Remove the independent domain GitHub Actions workflow.
- Update test runtime documentation in both English and Chinese.

## Error Handling and Determinism

- Reactive tests should verify signals directly where possible.
- Time-dependent code should use injected time sources or controllable wait
  hooks when feasible.
- Container fixture failures should surface container name, image, mapped
  endpoints, and relevant readiness failure context.
- Integration tests should avoid shared mutable middleware state unless the
  fixture intentionally manages isolation.
- Production-code bugs discovered during the rewrite should be fixed in
  separate commits from test rewrite commits.

## Acceptance Criteria

- `test/wow-test` remains unchanged except for dependency compatibility changes
  that are explicitly required and reviewed separately.
- Domain tests live only as module-local `src/test` behavior tests.
- No global domain test or coverage layer remains.
- Container-backed tests use `test/wow-tck` fixtures instead of module-local
  launchers.
- `check` does not start middleware containers.
- `allIntegrationTest` is the explicit full container-backed entrypoint.
- Each phase has a passing narrow verification command before moving on.
- Documentation and CI names match the final local, contract, and integration
  model.
