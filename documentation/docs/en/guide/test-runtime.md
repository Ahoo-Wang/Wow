---
title: Test Runtime
description: How to run Wow's local, contract, integration, coverage, and benchmark smoke test layers.
---

# Test Runtime

Wow separates tests by runtime dependency so local checks stay fast while container-backed scenarios remain explicit.

## Test Layers

| Layer | Source set | Root task | Runtime dependency |
| --- | --- | --- | --- |
| Local | `src/test` | `allLocalTest` | Local-safe framework, extension, domain, and server tests. |
| Contract | `src/contractTest` | `allContractTest` | Local-safe TCK implementor tests. |
| Integration | `src/integrationTest` | `allIntegrationTest` | Testcontainers-backed middleware and end-to-end tests. |

`check` runs local-safe verification: standard `test` tasks plus contract tests where configured. It does not start Docker containers.

Use `allLocalTest` as the root task for standard `src/test` execution.

## Local Fast Checks

```bash
./gradlew allLocalTest
./gradlew allContractTest
./gradlew check
```

Use these commands for normal development and pull-request feedback when Docker is not required.

## Domain Behavior Tests

Domain behavior tests still use the inheritance-style `AggregateSpec` and `SagaSpec` APIs documented in [Test Suite](./test-suite.md). They live in each owning domain module's standard `src/test` source set and are part of the local test layer.

```bash
./gradlew allLocalTest
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
./gradlew :wow-kafka:integrationTest
./gradlew :wow-elasticsearch:integrationTest
./gradlew :wow-it:integrationTest
```

Integration tests use Testcontainers and require Docker. They are intentionally not wired into `check`.

## Coverage

```bash
./gradlew codeCoverageReport
./gradlew :code-coverage-report:localCoverageReport
./gradlew :code-coverage-report:contractCoverageReport
./gradlew :code-coverage-report:integrationCoverageReport
```

The aggregate report includes local, contract, and integration execution data. The XML report is written to:

```text
test/code-coverage-report/build/reports/jacoco/codeCoverageReport/codeCoverageReport.xml
```

Layer reports are written under the matching `localCoverageReport`, `contractCoverageReport`, and `integrationCoverageReport` directories. Pull-request workflows upload those XML reports to Codecov with the `local`, `contract`, and `integration` flags. The main-branch Codecov workflow uploads the aggregate report as the `full` baseline flag.

Domain modules also enforce their existing coverage threshold through `jacocoTestCoverageVerification`, using standard `test` execution data.

## Benchmark Smoke

```bash
./gradlew :wow-benchmarks:benchmarkSmoke
```

Benchmark smoke checks that selected JMH paths still compile and execute. It is a pull-request safety check, not a performance report.

## Quick Benchmarks

```bash
./gradlew :wow-benchmarks:benchmarkQuickE2E
./gradlew :wow-benchmarks:benchmarkQuickComponent
./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E
./gradlew :wow-benchmarks:generateQuickBenchmarkReport
```

Quick benchmarks use bounded representative catalogs and short JMH settings. They are useful for local regression feedback, but Baseline E2E remains the source for formal throughput and allocation conclusions.
Infrastructure benchmarks require local Redis and MongoDB services.

## Baseline And Diagnostic Benchmarks

```bash
./gradlew :wow-benchmarks:benchmarkBaselineE2E
./gradlew :wow-benchmarks:benchmarkLatencyE2E
./gradlew :wow-benchmarks:benchmarkDiagnosticComponent \
  -PbenchmarkDiagnosticComponentIncludes=me.ahoo.wow.benchmark.component.CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent
./gradlew :wow-benchmarks:benchmarkExhaustiveComponent
./gradlew :wow-benchmarks:benchmarkBaselineInfrastructureE2E
./gradlew :wow-benchmarks:generateBaselineBenchmarkReport
```

Baseline E2E is a bounded, three-fork throughput and allocation run used for formal framework comparisons. Latency E2E is optional and isolated from the default baseline cost. Diagnostic Component accepts exact benchmark includes for focused investigation; Exhaustive Component retains the complete catalog as a rare escape hatch. Generic aliases are intentionally absent; callers must select the purpose-specific task.
Component results explain bottlenecks and should not be reported as standalone framework performance goals.
Infrastructure E2E results expose storage-path bottlenecks when Redis and MongoDB are available.
`updateBenchmarkBaseline` accepts only clean manifests produced from the current clean `HEAD`. Schema v2 records source, run specification, runtime, and artifact hashes so stale or incomplete evidence fails closed.

## CI Workflows

Pull requests run separate workflows for `Local Test`, `Contract Test`, `Integration Test`, `Benchmark Smoke`, and `Static Analysis`. The `Local Test`, `Contract Test`, and `Integration Test` workflows each publish a layer-specific Codecov flag. The main `Codecov` workflow builds `codeCoverageReport` for the full baseline on `main` or manual dispatch.
