---
title: Test Runtime
description: How to run Wow's unit, domain, contract, integration, coverage, and benchmark smoke test layers.
---

# Test Runtime

Wow separates tests by runtime dependency so local checks stay fast while container-backed scenarios remain explicit.

## Test Layers

| Layer | Source set | Root task | Runtime dependency |
| --- | --- | --- | --- |
| Unit | `src/test` | `allUnitTest` | Local-safe framework, extension, and server tests outside the domain-module signal. |
| Domain | `src/test` | `allDomainTest` | Domain-module behavior tests using the existing `AggregateSpec` and `SagaSpec` DSL. |
| Contract | `src/contractTest` | `allContractTest` | Local-safe TCK implementor tests. |
| Integration | `src/integrationTest` | `allIntegrationTest` | Testcontainers-backed middleware and end-to-end tests. |

`check` runs local-safe verification: standard `test` tasks plus contract tests where configured. It does not start Docker containers.

Unit and domain tests both use standard `src/test`, but their root tasks are separate semantic aggregates. `allUnitTest` excludes the domain-module subset, and `allDomainTest` runs those domain modules explicitly.

## Local Fast Checks

```bash
./gradlew allUnitTest
./gradlew allDomainTest
./gradlew allContractTest
./gradlew check
```

Use these commands for normal development and pull-request feedback when Docker is not required.

## Domain Tests

Domain tests still use the inheritance-style `AggregateSpec` and `SagaSpec` APIs documented in [Test Suite](./test-suite.md). They live in the standard `src/test` source set; `allDomainTest` is a semantic aggregate task for the domain modules.

```bash
./gradlew allDomainTest
./gradlew :example-domain:test
./gradlew :example-transfer-domain:test
./gradlew :wow-compensation-domain:test
```

The function-style DSL is planned as a later migration stage, so runtime test layering does not require changing existing domain specs.

## Integration Tests

```bash
./gradlew allIntegrationTest
./gradlew :wow-mongo:integrationTest
./gradlew :wow-redis:integrationTest
./gradlew :wow-r2dbc:integrationTest
./gradlew :wow-kafka:integrationTest
./gradlew :wow-elasticsearch:integrationTest
./gradlew :wow-it:integrationTest
```

Integration tests use Testcontainers and require Docker. They are intentionally not wired into `check`.

## Coverage

```bash
./gradlew codeCoverageReport
./gradlew :code-coverage-report:unitCoverageReport
./gradlew :code-coverage-report:domainCoverageReport
./gradlew :code-coverage-report:contractCoverageReport
./gradlew :code-coverage-report:integrationCoverageReport
```

The aggregate report includes unit, domain, contract, and integration execution data. The XML report is written to:

```text
test/code-coverage-report/build/reports/jacoco/codeCoverageReport/codeCoverageReport.xml
```

Layer reports are written under the matching `unitCoverageReport`, `domainCoverageReport`, `contractCoverageReport`, and `integrationCoverageReport` directories. Pull-request workflows upload those XML reports to Codecov with the `unit`, `domain`, `contract`, and `integration` flags. The main-branch Codecov workflow uploads the aggregate report as the `full` baseline flag.

Domain modules also enforce their existing coverage threshold through `jacocoTestCoverageVerification`, using standard `test` execution data.

## Benchmark Smoke

```bash
./gradlew benchmarkSmoke
```

Benchmark smoke checks that selected JMH paths still compile and execute. It is a pull-request safety check, not a stable performance report.

## Full Benchmarks

```bash
./gradlew :wow-benchmarks:jmh
```

Full JMH runs are intended for manual or scheduled performance analysis.

## CI Workflows

Pull requests run separate workflows for `Unit Test`, `Domain Test`, `Contract Test`, `Integration Test`, `Benchmark Smoke`, and `Static Analysis`. The `Unit Test`, `Domain Test`, `Contract Test`, and `Integration Test` workflows each publish a layer-specific Codecov flag. The main `Codecov` workflow builds `codeCoverageReport` for the full baseline on `main` or manual dispatch.
