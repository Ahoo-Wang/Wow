---
title: Framework Tests and Benchmarks
description: How Wow framework contributors select local, contract, integration, coverage, and JMH tasks and interpret their evidence.
outline: deep
---

# Framework Tests and Benchmarks

This page applies only to the Wow framework repository. Use these tasks when changing framework source, TCKs, adapters, build logic, or benchmarks. For a business-application release, use [Testing Wow Applications](./application-testing.md) instead of copying this repository's root tasks, Codecov flags, or JMH conclusions.

::: tip Completion signal
A framework change is complete when the affected modules' `check` and relevant test layers pass. If benchmark entry points changed, smoke also passes. Any performance claim additionally requires baseline/confirmation results with the same workload, comparable environment, and complete provenance. The next layer is code review and CI, not turning historical figures into product promises.
:::

## Choose the Test Layer by Dependency

| Layer | Source set | Root task | Runtime condition | Evidence scope |
| --- | --- | --- | --- | --- |
| Local | `src/test` | `allLocalTest` | No containers | Local-safe unit, domain, and component behavior |
| Contract | `src/contractTest` | `allContractTest` | No containers | Registered TCK implementations satisfy shared contracts |
| Integration | `src/integrationTest` | `allIntegrationTest` | Docker/Testcontainers required | Middleware adapters and end-to-end integration |

The root build currently registers `contractTest` only for `:wow-core`, `:wow-opentelemetry`, and `:wow-mock`. It registers `integrationTest` only for `:wow-bi`, `:wow-mongo`, `:wow-redis`, `:wow-kafka`, `:wow-elasticsearch`, and `:wow-it`. Do not guess task names for modules that do not register them.

`check` runs standard `test` tasks and includes `contractTest` in configured modules; it does not automatically run container-backed `integrationTest`. Therefore, a green `check` does not prove every store and broker integration was verified.

## Narrowest Local Feedback

Run directly affected modules first:

```bash
./gradlew :wow-core:check
./gradlew :wow-test:check :example-domain:check
```

Expand only when the entire local-safe layer is needed:

```bash
./gradlew allLocalTest
./gradlew allContractTest
./gradlew check
```

Domain specifications still use `AggregateSpec` and `SagaSpec` from the [Domain Test Suite](./test-suite.md). They live in the owning module's `src/test` and belong to the Local layer; they are not a separate application-release proof.

## Architecture Guardrails

Two kinds of test guard the module boundaries and the decorators. Both run in the Local layer:

- `DependencyRulesTest` (`:wow-core:test`) scans production sources. `wow-core` and `wow-query` reference no HTTP, Spring or storage-driver types. No backend module (`wow-mongo`, `wow-redis`, `wow-kafka`, `wow-elasticsearch`) references another backend or its driver. Outside their `query` packages, `wow-mongo` and `wow-elasticsearch` reference no query packages. `me.ahoo.wow.api.query` protocol types are allowed. The `test` task declares the scanned source trees as inputs, so a change in any of those modules re-runs it.
- `MetricDecoratorContractTest` (`:wow-core:test`) and `TracingDecoratorContractTest` (`:wow-opentelemetry:test`) require every `Metric*` and `Tracing*` decorator to override each SPI member that has a default body. Otherwise a call falls back to the interface default and skips the delegate, for example `EventStore.existsRequestId`, which loads the whole stream. `ElasticsearchEventStoreContractTest` applies the same rule to `existsRequestId` on the Elasticsearch event store. Each test keeps a `KNOWN_GAPS` list, empty since 9.2.3. A new gap fails the test, and so does a fixed gap that is still listed; an entry may only be added with the work item that removes it.

## Wire-Format Golden Samples

The v9 wire is frozen: during a rolling upgrade, 9.2.x and later 9.x nodes share topics, streams, stores and the command wait endpoint, so each must read what the other writes. Golden tests lock those bytes. Each one serializes a fixed sample (`me.ahoo.wow.tck.wire.WireSamples`) through the production code and compares the result byte for byte with a committed file under the module's `src/test/resources/wire/v9/`, then decodes the committed file through the production reader.

| Module | Test | What it freezes |
| --- | --- | --- |
| `wow-core` | `WireFormatGoldenTest` | Command message JSON (stage and chain wait headers), domain event stream JSON, state event JSON |
| `wow-kafka` | `KafkaWireFormatGoldenTest` | Kafka record topic, partition, timestamp, key, record headers and value for the command, event and state buses |
| `wow-redis` | `RedisWireFormatGoldenTest` | Redis stream key and entry fields for the command, event and state buses |
| `wow-spring-boot-starter` | `WaitSignalWireFormatGoldenTest` | The `WaitSignal` body posted to a remote command wait endpoint |

The samples carry the headers the runtime stamps: `command_operator`, `user_agent`, `remote_ip`, `local_first`, `trace_id`, `upstream_*` and the `command_wait_*` keys from a real wait plan. The goldens were generated from 9.2.2. A failing golden test means the wire changed; do not re-generate the file to make it pass. Changing a golden needs a design decision that covers mixed-version clusters; once that is decided, rerun the test with `WOW_GOLDEN_UPDATE=true` and review the diff.

## Container-Backed Integration Tests

Run all registered integration tasks with:

```bash
./gradlew allIntegrationTest --stacktrace
```

Or run only an affected adapter:

```bash
./gradlew :wow-mongo:integrationTest --stacktrace
./gradlew :wow-redis:integrationTest --stacktrace
./gradlew :wow-kafka:integrationTest --stacktrace
./gradlew :wow-elasticsearch:integrationTest --stacktrace
./gradlew :wow-it:integrationTest --stacktrace
```

These tasks require Docker/Testcontainers and intentionally are not attached to `check`. `:wow-it` validates integration combinations inside the Wow repository; it cannot replace a business application's configuration, protocol, recovery, and security gates.

### Mixed-Version Cluster Test

`MixedVersionClusterTest` in `:wow-it` checks a rolling upgrade: the released example server image and the example server built from the current source share one Kafka and one MongoDB, join the same consumer groups, and each must process what the other sends. Local-first routing is off, and the test chooses aggregate ids by partition owner, so in both directions:

- a command sent to one version is processed by the other, and its `PROCESSED` wait returns to the sender;
- a state event appended by one version is snapshotted by the other, and the `SNAPSHOT` wait returns across versions;
- a saga on one version (`CartSaga`) handles the domain events the other version appends;
- both versions read the same aggregate state and event stream.

The test runs only when its environment names both nodes, so `allIntegrationTest` skips it. The `Mixed-Version` workflow runs it on pull requests that touch the wire modules (`wow-api`, `wow-core`, `wow-kafka`, `wow-redis`, `wow-mongo`, `wow-webflux`, `wow-spring`, `wow-spring-boot-starter`), the example server, dependency versions or the harness, pulling the released image (`PREVIOUS_IMAGE` in the workflow). To check the harness locally without pulling that image, run it against two copies of the current build:

```bash
./gradlew :example-server:installDist
home=$PWD/example/example-server/build/install/example-server
WOW_MIXED_CURRENT_HOME=$home WOW_MIXED_PREVIOUS_HOME=$home \
  ./gradlew :wow-it:integrationTest --tests 'me.ahoo.wow.it.mixed.MixedVersionClusterTest'
```

Set `WOW_MIXED_PREVIOUS_IMAGE` instead of `WOW_MIXED_PREVIOUS_HOME` to run the released image, as CI does. The example server has no Redis bus, so Redis is covered by wire-format tests, not by this cluster.

## Coverage Is Layered Evidence

The current aggregate and layer report tasks are:

```bash
./gradlew codeCoverageReport
./gradlew :code-coverage-report:localCoverageReport
./gradlew :code-coverage-report:contractCoverageReport
./gradlew :code-coverage-report:integrationCoverageReport
./gradlew :example-domain:jacocoTestCoverageVerification
```

The aggregate XML is written to:

```text
test/code-coverage-report/build/reports/jacoco/codeCoverageReport/codeCoverageReport.xml
```

Layer reports are written under the matching `localCoverageReport`, `contractCoverageReport`, and `integrationCoverageReport` directories. Pull-request workflows upload separate `local`, `contract`, and `integration` flags. The `Codecov` workflow on `main` or manual dispatch uses `codeCoverageReport` to upload the `full` flag.

`:example-domain`, `:example-transfer-domain`, and `:wow-compensation-domain` currently configure a `0.8` Jacoco verification minimum. The threshold runs only when the corresponding `jacocoTestCoverageVerification` task is invoked explicitly; these modules' `check` tasks and the current CI workflows do not attach a verification task automatically. It is an optional repository gate, not a Wow coverage guarantee for business applications. Coverage shows executed code and cannot replace assertions about events, state, rejection, and recovery.

## Benchmarks Have Three Uses

| Use | Entry point | Supported conclusion |
| --- | --- | --- |
| Smoke | `benchmarkSmoke` | Selected JMH jar and paths compile, start, and finish |
| Quick | `benchmarkQuick*` | Bounded regression clues on the current machine |
| Baseline / confirmation | `benchmarkBaseline*`, `benchmarkConfirm*` | Comparable evidence under matching methods, parameters, forks, and environment |

Smoke is not a performance report, Quick is not a production-capacity model, and isolated component results are not framework end-to-end throughput promises.

### Pull-Request Safety

```bash
./gradlew :wow-benchmarks:test :wow-benchmarks:benchmarkSmoke --stacktrace
```

This matches the current `Benchmark Smoke` CI workflow. The root alias is also available:

```bash
./gradlew benchmarkSmoke
```

The completion signal is that selected paths execute successfully, not that a performance baseline was produced or updated.

### Quick Regression and Diagnosis

Generate a quick Framework E2E report:

```bash
./gradlew :wow-benchmarks:benchmarkQuickE2E \
  :wow-benchmarks:generateBenchmarkReport
```

Run the paired batch-command-write workload with:

```bash
./gradlew :wow-benchmarks:benchmarkQuickBatchE2E \
  :wow-benchmarks:generateBatchBenchmarkReport
```

When locating a bottleneck, select a layer instead of running the complete catalog:

```bash
./gradlew :wow-benchmarks:benchmarkQuickComponent
./gradlew :wow-benchmarks:benchmarkQuickWebFlux -PbenchmarkQuickWebFluxThreads=1
./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E
```

The WebFlux suite does not start a real Netty server. The current `benchmarkQuickInfrastructureE2E` includes both Redis and Mongo workloads, so local Redis and MongoDB are both required services; either one missing leaves the suite's runtime requirements unmet. Reports must retain workload, thread, JVM, service, and source provenance; do not interpret numbers across layers as directly comparable.

Work that changes a framework hot path compares two labelled runs of the refactor catalog on the same machine: one before the change and one after it. `benchmarkQuickRefactorHotPaths` needs no services and covers event dispatch (1 and 8 processors, metrics on and off, in-memory and local-first buses), local-first command send, the command HTTP edge (header appenders, full router dispatch), per-command aggregate processing and event-stream query delivery. `benchmarkQuickRefactorTransport` measures Redis Streams receive and acknowledge and needs the benchmark Redis. Kafka receive and acknowledge (`KafkaEventReceiveAckBenchmark`) is not in a Gradle suite because the benchmarks have no Kafka service; run it from the JMH jar against a broker.

```bash
./gradlew :wow-benchmarks:benchmarkQuickRefactorHotPaths \
  :wow-benchmarks:generateQuickRefactorHotPathsBenchmarkReport \
  -PbenchmarkRefactorReportLabel=9.2.2-baseline
```

### Formal Regression Evidence

Collect comparable evidence for exact Framework E2E workloads with:

```bash
./gradlew :wow-benchmarks:benchmarkBaselineE2E --no-parallel
./gradlew :wow-benchmarks:benchmarkCompare
```

A threshold crossing from `benchmarkCompare` is only a regression or improvement candidate. Run `benchmarkConfirmE2E` for the affected method with the same JVM, threads, parameters, forks, warmup, measurement, and profiler before forming a confirmed conclusion.

`updateBenchmarkBaseline` accepts only a clean manifest produced from the current clean `HEAD`. Do not update a baseline from a dirty worktree, different service configuration, or missing manifest.

## Read Historical Reports Correctly

Reports under `wow-benchmarks/results/reports/` are bound to the source, run specification, machine, JVM, and service configuration that produced them. They are qualified historical evidence or investigation starting points, not universal promises across versions, machines, or stores.

Follow three rules:

1. do not hand-edit report rows or frontier JSON; use the corresponding generation task;
2. do not use Quick point estimates to claim a formal throughput change;
3. do not use component or simulated-I/O results to claim production end-to-end capacity.

## CI-to-Local Evidence Map

| Workflow | Current command |
| --- | --- |
| `Local Test` | `allLocalTest` + `localCoverageReport` |
| `Contract Test` | `allContractTest` + `contractCoverageReport` |
| `Integration Test` | `allIntegrationTest` + `integrationCoverageReport` |
| `Mixed-Version` | `:example-server:installDist` + `:wow-it:integrationTest --tests 'me.ahoo.wow.it.mixed.MixedVersionClusterTest'` with the released image |
| `Benchmark Smoke` | `:wow-benchmarks:test` + `:wow-benchmarks:benchmarkSmoke` |
| `Codecov` | `codeCoverageReport` |

Choose these layers locally according to change risk. CI is fresh evidence in another environment; local validation, CI validation, application release, and production verification remain separate completion conditions.
