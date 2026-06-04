# Wow Test System Runtime Refactor Design

## Status

Approved on 2026-06-04.

This design covers the current refactor stage: test execution architecture, Gradle source sets, CI workflows, container-backed integration tests, coverage aggregation, and benchmark smoke checks.

The domain testing DSL refactor is explicitly deferred to the next stage. `AggregateSpec` and `SagaSpec` remain unchanged in this stage.

## Goals

- Replace the current mixed `test` / `check` execution model with explicit semantic test layers.
- Keep local feedback fast while making CI run the complete required suite.
- Move container-backed tests behind explicit integration-test tasks.
- Make TCK execution visible as contract testing without redesigning the public TCK API yet.
- Add PR-safe benchmark smoke coverage without turning performance numbers into a hard PR gate.
- Keep coverage reporting aligned with the new test layers.
- Prepare the codebase for a later DSL refactor without changing `AggregateSpec` / `SagaSpec` now.

## Non-Goals

- Do not redesign `AggregateSpec` or `SagaSpec` in this stage.
- Do not introduce `aggregateSpec`, `sagaSpec`, `branch`, `checkpoint`, or `branchFrom` yet.
- Do not redesign the public TCK API into function-style contract suites yet.
- Do not require local `check` to run Docker or external middleware.
- Do not add performance-regression thresholds to PR checks in this stage.
- Do not change Maven publishing, release automation, or published artifact coordinates.

## Current State

The repository currently has hundreds of JVM tests under module-level `src/test/kotlin`. The largest concentration is in `wow-core`, followed by `wow-webflux`, `wow-spring-boot-starter`, `wow-query`, infrastructure modules, examples, and compensation modules.

The current execution model uses each module's `test` and `check` tasks as the primary entrypoint. That makes fast unit tests, TCK-style contract tests, Spring context tests, container-backed infrastructure tests, and coverage checks share the same broad task semantics.

`wow-tck` already provides reusable abstract specifications such as event store, snapshot repository, message bus, query service, and command dispatcher contracts. Implementations in core, Mongo, Redis, R2DBC, Kafka, Elasticsearch, OpenTelemetry, mock, and integration modules inherit those specs.

Container behavior is inconsistent:

- Mongo and MariaDB launchers use local services in some local paths and containers in CI paths.
- Kafka and Elasticsearch launchers eagerly start Testcontainers from shared launcher objects.
- Redis is provided as a GitHub Actions service in CI rather than through the same test container model as the other middleware.
- Some container setup lives in `test/wow-tck`, while MariaDB setup lives in `wow-r2dbc`.

`wow-benchmarks` uses JMH and currently has a narrow default include. Full benchmark execution is not appropriate for every PR, but the benchmark code should compile and a short smoke path should run in CI.

## Test Layers

The refactor introduces explicit test layers.

### Unit Test

`unitTest` is for pure JVM fast tests. These tests must not require Spring context startup, Testcontainers, Docker, network services, databases, Kafka, Redis, Elasticsearch, or long sleeps.

Each eligible module gets:

```text
src/unitTest/kotlin
src/unitTest/resources
unitTest
```

Existing pure tests can either be moved into `src/unitTest` or kept in `src/test` during migration if Gradle maps `test` to the unit-test layer. The final public task should be `unitTest`, and the root aggregate task should be `allUnitTest`.

### Domain Test

`domainTest` is for tests that exercise domain behavior through the existing `AggregateSpec` and `SagaSpec` APIs.

This stage does not change those APIs. The purpose of this layer is execution isolation, not DSL redesign.

Domain modules include:

- `:example-domain`
- `:example-transfer-domain`
- `:wow-compensation-domain`
- any future module that uses Wow domain specs as product-facing domain tests

Each eligible module gets:

```text
src/domainTest/kotlin
src/domainTest/resources
domainTest
```

The root aggregate task is `allDomainTest`.

Coverage verification for domain modules remains meaningful and should continue to enforce the current 80% minimum where it already exists.

### Contract Test

`contractTest` is for local-safe TCK-based contract verification.

This stage keeps the existing inheritance-style TCK API, such as `EventStoreSpec`, `SnapshotRepositorySpec`, `CommandBusSpec`, `DomainEventBusSpec`, `StateEventBusSpec`, `EventStreamQueryServiceSpec`, `SnapshotQueryServiceSpec`, and `CommandDispatcherSpec`.

The refactor moves TCK implementor tests that do not require Docker or external middleware into a dedicated source set:

```text
src/contractTest/kotlin
src/contractTest/resources
contractTest
```

The root aggregate task is `allContractTest`.

Modules likely to participate include local-safe implementations and wrappers:

- `:wow-core`
- `:wow-opentelemetry`
- `:wow-mock`

Public TCK API redesign is deferred. This stage may add internal helpers for task wiring, fixture reuse, and container setup, but it must not require external extension authors to rewrite their TCK usage yet.

Container-backed TCK implementations are still contract compliance tests by intent, but they execute under `integrationTest` because their runtime dependency is middleware. Examples include Mongo, Redis, R2DBC, Kafka, Elasticsearch, and cross-middleware command dispatcher tests.

### Integration Test

`integrationTest` is for container-backed or real-middleware tests, including TCK suites whose providers require middleware.

Each eligible module gets:

```text
src/integrationTest/kotlin
src/integrationTest/resources
integrationTest
```

The root aggregate task is `allIntegrationTest`.

Local behavior:

- `check` does not depend on `integrationTest`.
- Developers run `./gradlew allIntegrationTest` or a module-specific `:module:integrationTest` when they want the full middleware-backed suite.

CI behavior:

- CI must run `allIntegrationTest`.
- CI must not rely on developer-local services.
- CI should use Testcontainers for Mongo, Kafka, MariaDB, Redis, and Elasticsearch.

### Benchmark Smoke

`benchmarkSmoke` is a PR-safe JMH smoke layer.

It should verify that:

- benchmark classes compile,
- benchmark fixture setup works,
- selected core benchmark paths execute,
- middleware-backed benchmark fixtures can start when selected.

It should not claim stable performance numbers.

Full JMH remains available through `:wow-benchmarks:jmh` and should run manually or on a scheduled workflow.

## Gradle Design

Add centralized Gradle conventions for test source sets and tasks. The conventions may live in build logic if the repository already supports it, or in root Gradle script helpers if that is the least disruptive starting point.

The root project exposes aggregate tasks:

```bash
./gradlew allUnitTest
./gradlew allDomainTest
./gradlew allContractTest
./gradlew allIntegrationTest
./gradlew benchmarkSmoke
./gradlew codeCoverageReport
```

Module-level tasks follow the same naming:

```bash
./gradlew :wow-core:unitTest
./gradlew :wow-mongo:contractTest
./gradlew :wow-kafka:integrationTest
./gradlew :example-domain:domainTest
```

`check` should depend on fast, local-safe checks:

- compilation,
- detekt,
- unit tests,
- domain tests where configured,
- contract tests that do not require containers.

`check` should not depend on container-backed `integrationTest`.

Container-backed contract tests need a clear classification. If a TCK implementation requires external middleware, it should either live under `integrationTest` or be tagged/configured so local `check` does not start containers implicitly.

## Container Test Infrastructure

Create a unified test container utility for Mongo, Kafka, MariaDB, Redis, and Elasticsearch.

Responsibilities:

- centralize image names and versions,
- start containers lazily for the task that needs them,
- expose connection properties through typed accessors,
- support unique database, topic, collection, index, and schema names per test suite,
- support local reuse only through explicit Testcontainers configuration,
- disable reliance on reuse in CI,
- surface startup diagnostics with image, task, mapped ports, and recent logs.

The existing launchers should be migrated into this utility:

- `MongoLauncher`
- `KafkaLauncher`
- `ElasticsearchLauncher`
- `MariadbLauncher`
- Redis GitHub Actions service usage

The target behavior is one container strategy across local and CI runs.

## CI Design

Split CI by test intent:

- `unit-test.yml` runs `allUnitTest`.
- `domain-test.yml` runs `allDomainTest`.
- `contract-test.yml` runs `allContractTest`.
- `integration-test.yml` runs `allIntegrationTest`.
- `benchmark-smoke.yml` runs `benchmarkSmoke`.
- `codecov.yml` runs the required coverage-producing test layers and uploads the aggregate report.

The existing workflow named `integration-test.yml` currently runs many module-level `check` tasks. After this refactor, that workflow name should either be repurposed for real integration tests or replaced with clearer workflow names.

All CI jobs use JDK 17 until a separate JVM baseline decision changes that.

CI should avoid duplicating the same setup block across many jobs where matrix jobs can express the module list safely.

## Coverage Design

The aggregate coverage report should include:

- unit tests,
- domain tests,
- contract tests,
- integration tests where practical and stable.

It should exclude:

- JMH benchmark execution,
- generated build output,
- generated dashboard clients,
- non-JVM frontend coverage.

Existing per-domain 80% verification remains for modules that already enforce it:

- `:example-domain`
- `:example-transfer-domain`
- `:wow-compensation-domain`

Coverage tasks should be updated so the XML report path remains stable for Codecov.

## Benchmark Design

Add two benchmark profiles:

- smoke: short PR-safe includes, minimal forks and iterations, intended only to verify benchmark health.
- full: existing or expanded JMH configuration, manual or scheduled, intended for real performance analysis.

`benchmarkSmoke` should not fail because a metric changed by a small amount. It may fail if the benchmark does not compile, cannot start, throws errors, or violates a broad sanity condition.

Full benchmark JSON output should remain available for later reporting and baseline work.

## Migration Strategy

This stage is a test execution migration.

Move tests by behavior, not by module alone:

- pure fast tests to `unitTest`,
- existing `AggregateSpec` / `SagaSpec` tests to `domainTest`,
- existing TCK implementor tests to `contractTest` when they do not require middleware,
- container-backed or real-middleware tests to `integrationTest`,
- JMH stays under `wow-benchmarks`.

When a test is ambiguous, classify by runtime dependency:

- no external runtime dependency means `unitTest`, `domainTest`, or `contractTest`,
- Spring context only means usually `unitTest` or `contractTest` depending on what is being verified,
- Docker or middleware means `integrationTest`,
- performance harness means `benchmarkSmoke` or `jmh`.

The current `AggregateSpec` and `SagaSpec` APIs stay intact. Their redesign is a separate next-stage design.

The current public TCK inheritance model stays intact. A future stage may redesign TCK into function-style contract suites.

## Next Stage: DSL Refactor

The next stage should have its own design and implementation plan.

Expected direction from brainstorming:

- introduce `aggregateSpec` and `sagaSpec` as function-style DSL entries,
- keep inheritance-style `AggregateSpec` and `SagaSpec` as compatibility layers,
- use `branch`, `checkpoint`, and `branchFrom` for scenario branching,
- provide before/after migration examples,
- avoid expanding old APIs with new capabilities.

This stage only prepares the execution layers needed to validate that later DSL work cleanly.

## Risks

- Moving tests between source sets can break classpath assumptions.
- TCK tests that also require containers may be misclassified unless each module is audited.
- CI workflow splitting can accidentally reduce coverage if aggregate tasks are incomplete.
- Testcontainers startup may increase CI time unless jobs are grouped carefully.
- Coverage aggregation across multiple source sets can produce unstable paths if not configured centrally.
- Detached local worktree commits are valid but need later branch handling before pushing.

## Acceptance Criteria

- Root aggregate tasks exist for unit, domain, contract, integration, benchmark smoke, and coverage.
- Local `check` remains fast and does not start containers by default.
- CI runs unit, domain, contract, integration, benchmark smoke, and coverage workflows.
- Container-backed tests use a unified Testcontainers strategy.
- Redis no longer depends on a separate GitHub Actions service while other middleware uses Testcontainers.
- Existing `AggregateSpec` and `SagaSpec` tests still run.
- Existing TCK inheritance-style tests still run.
- Domain DSL public API is not changed in this stage.
- Full JMH remains available separately from PR benchmark smoke.
- Documentation explains how to run each test layer locally.
