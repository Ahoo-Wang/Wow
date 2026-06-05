# Wow Test System Runtime Refactor Design

## Status

Approved on 2026-06-04 and revised before merge after PR review.

This stage refactors test execution, CI visibility, container-backed integration tests, coverage aggregation, and benchmark smoke checks. It does not redesign the domain testing DSL. `AggregateSpec` and `SagaSpec` remain the compatibility layer for this stage; the function-style `aggregateSpec` and `sagaSpec` DSL is deferred to the next stage.

## Goals

- Preserve standard Gradle/JVM `src/test` for ordinary unit tests.
- Preserve standard `src/test` for domain behavior tests in domain modules.
- Keep semantic root tasks for each test intent: `allUnitTest`, `allDomainTest`, `allContractTest`, `allIntegrationTest`, and `benchmarkSmoke`.
- Move local-safe TCK implementor tests into `src/contractTest`.
- Move container-backed or real-middleware tests into `src/integrationTest`.
- Keep local `check` fast and free from Docker startup.
- Make PR CI run unit, domain, contract, integration, benchmark smoke, coverage, and static analysis workflows.
- Keep coverage reporting aligned with the final task layout.

## Non-Goals

- Do not redesign `AggregateSpec` or `SagaSpec` in this stage.
- Do not introduce `aggregateSpec`, `sagaSpec`, `branch`, `checkpoint`, or `branchFrom` yet.
- Do not redesign the public TCK API into function-style contract suites yet.
- Do not require local `check` to run Docker or external middleware.
- Do not add performance-regression thresholds to PR checks in this stage.
- Do not change Maven publishing, release automation, or published artifact coordinates.

## Test Layers

### Unit Test

Unit tests use the standard Gradle source set and task:

```text
src/test/kotlin
src/test/resources
test
```

The root task `allUnitTest` is a semantic aggregate over non-domain standard module `test` tasks. This keeps module-level behavior familiar, makes CI intent explicit, and leaves the domain-module signal separate. `:wow-compensation-server:test` is included in the aggregate even though the server module is not a published library module.

### Domain Test

Domain behavior tests also use standard `src/test` in the domain modules:

```text
src/test/kotlin
src/test/resources
test
```

The root task `allDomainTest` is a semantic aggregate over the standard `test` tasks for:

- `:example-domain`
- `:example-transfer-domain`
- `:wow-compensation-domain`

This preserves the existing domain-module testing convention and avoids a custom `domainTest` source set at the module level. Domain coverage verification continues to depend on the standard `test` task and keeps the existing 80% minimum where configured.

The domain modules are excluded from `allUnitTest` and `unitCoverageReport` so Pull Request feedback can show a distinct `domain` signal even though the source set remains `src/test`.

### Contract Test

`contractTest` is for local-safe TCK-based contract verification.

This stage keeps the existing inheritance-style TCK API, such as `EventStoreSpec`, `SnapshotRepositorySpec`, `CommandBusSpec`, `DomainEventBusSpec`, `StateEventBusSpec`, `EventStreamQueryServiceSpec`, `SnapshotQueryServiceSpec`, and `CommandDispatcherSpec`.

The refactor moves TCK implementor tests that do not require Docker or external middleware into:

```text
src/contractTest/kotlin
src/contractTest/resources
contractTest
```

The root aggregate task is `allContractTest`.

Container-backed TCK implementations are contract tests by intent, but they execute under `integrationTest` because their runtime dependency is middleware.

### Integration Test

`integrationTest` is for container-backed or real-middleware tests, including TCK suites whose providers require middleware.

Each eligible module uses:

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

- CI runs `allIntegrationTest`.
- CI should use the shared Testcontainers strategy for Mongo, Kafka, Redis, and Elasticsearch instead of developer-local services. MariaDB remains module-local in `wow-r2dbc` because its initialization script and module dependency boundary are specific to that provider.

### Benchmark Smoke

`benchmarkSmoke` is a PR-safe JMH smoke layer.

It verifies that benchmark classes compile and selected benchmark paths execute with a short configuration. It must not claim stable performance numbers or fail because a metric changes by a small amount.

Full JMH remains available through `:wow-benchmarks:jmh` and should run manually or on a scheduled workflow.

## Gradle Design

Root aggregate tasks:

```bash
./gradlew allUnitTest
./gradlew allDomainTest
./gradlew allContractTest
./gradlew allIntegrationTest
./gradlew benchmarkSmoke
./gradlew codeCoverageReport
```

Module-level tasks:

```bash
./gradlew :wow-core:test
./gradlew :example-domain:test
./gradlew :wow-core:contractTest
./gradlew :wow-kafka:integrationTest
./gradlew :wow-benchmarks:benchmarkSmoke
```

`check` should depend on fast, local-safe checks:

- compilation,
- detekt,
- standard `test`,
- local-safe `contractTest` where configured.

`check` must not depend on container-backed `integrationTest`.

## Container Test Infrastructure

Use a shared test container utility for common middleware containers:

- Mongo,
- Kafka,
- Redis,
- Elasticsearch.

MariaDB stays in the `wow-r2dbc` integration-test launcher in this stage.

Responsibilities:

- centralize image names and versions,
- start containers lazily for the task that needs them,
- expose connection properties through typed accessors,
- support unique database, topic, collection, index, and schema names per test suite,
- disable reliance on reusable containers in CI,
- surface startup diagnostics with image, task, mapped ports, and recent logs.

The target behavior is one shared strategy for common middleware across local and CI runs without widening TCK dependencies only to host the MariaDB launcher.

## CI Design

Split CI by test intent:

- `unit-test.yml` runs `allUnitTest`.
- `domain-test.yml` runs `allDomainTest`.
- `contract-test.yml` runs `allContractTest`.
- `integration-test.yml` runs `allIntegrationTest`.
- `benchmark-smoke.yml` runs `benchmarkSmoke`.
- PR test workflows run their layer task and upload the matching Codecov flag.
- `codecov.yml` runs `codeCoverageReport` on `main` or manual dispatch and uploads the aggregate `full` baseline report.
- `static-analysis.yml` runs PR-level Detekt.

All CI jobs use JDK 17 until a separate JVM baseline decision changes that.

## Coverage Design

The full aggregate coverage report includes:

- unit `test` execution data from non-domain library modules,
- domain `test` execution data from domain modules,
- local-safe `contractTest` execution data,
- container-backed `integrationTest` execution data where configured.

It excludes:

- JMH benchmark execution,
- generated build output,
- generated dashboard clients,
- non-JVM frontend coverage.

Existing per-domain 80% verification remains for:

- `:example-domain`
- `:example-transfer-domain`
- `:wow-compensation-domain`

Coverage tasks keep the full Codecov XML report path stable while adding layer-specific XML reports:

- `unitCoverageReport`,
- `domainCoverageReport`,
- `contractCoverageReport`,
- `integrationCoverageReport`.

Pull Request workflows upload the layer reports with `unit`, `domain`, `contract`, and `integration` flags. The `Codecov` workflow uploads the full aggregate report with the `full` flag.

## Migration Strategy

Move tests by behavior:

- ordinary local JVM tests stay or return to `src/test`,
- domain behavior tests stay or return to `src/test` in domain modules,
- local-safe TCK implementor tests move to `src/contractTest`,
- container-backed or real-middleware tests move to `src/integrationTest`,
- JMH benchmarks stay under `wow-benchmarks`.

When a test is ambiguous, classify by runtime dependency:

- no external runtime dependency means standard `test` or local-safe `contractTest`,
- domain product behavior means standard domain-module `test`,
- Docker or middleware means `integrationTest`,
- performance harness means `benchmarkSmoke` or `jmh`.

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

## Acceptance Criteria

- Root aggregate tasks exist for unit, domain, contract, integration, benchmark smoke, and coverage.
- Local `check` remains fast and does not start containers by default.
- CI runs unit, domain, contract, integration, benchmark smoke, coverage, and static analysis workflows.
- Mongo, Kafka, Redis, and Elasticsearch tests use the shared Testcontainers strategy.
- MariaDB remains in the `wow-r2dbc` integration-test launcher for this stage.
- Redis no longer depends on a separate GitHub Actions service while other middleware uses Testcontainers.
- Existing `AggregateSpec` and `SagaSpec` tests still run from standard `src/test`.
- Existing TCK inheritance-style tests still run.
- Domain DSL public API is not changed in this stage.
- Full JMH remains available separately from PR benchmark smoke.
- Documentation explains how to run each test layer locally.
