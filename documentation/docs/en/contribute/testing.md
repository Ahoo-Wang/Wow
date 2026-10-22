---
title: Test Layers
description: The layers of Wow's own tests, unit, container, contract and end to end, which a change needs, and the CI gates a pull request passes.
---

# Test Layers

Every change to Wow comes with tests, at the narrowest layer that covers what changed. The JVM tests use JUnit Jupiter, MockK and FluentAssert (`.assert()`, not AssertJ's `assertThat()`).

## The layers

| Layer | Source set | Runs with | Needs |
| --- | --- | --- | --- |
| Unit | `src/test` | `<module>:test`, and `:check` for every module | Nothing outside the JVM |
| Container | `src/integrationTest` | `<module>:integrationTest`, and `:integrationTest` for every module that has the source set | Docker, through Testcontainers |
| Contract | A backend's container tests extending the TCK's specs | That backend's `integrationTest` | Docker |
| End to end | `example/eshop/e2e` | `:eshop-e2e:integrationTest` | Docker: PostgreSQL, Kafka, ClickHouse |

`:check` runs every unit test, detekt, the ABI checks and `:architecture:test`, but no container test. A change to a storage adapter or a broker runs its module's container tests explicitly:

```bash
./gradlew :kernel:test
./gradlew :postgres:integrationTest --stacktrace
./gradlew :eshop-e2e:integrationTest --stacktrace
```

Only the layers a module registers in `build.gradle.kts` exist for it.

## Domain tests

A domain is tested with the test DSL, `:test-dsl` (artifact `wow-test`): `aggregateSpec`, `sagaSpec` and the specs of projections and reactors run the generated models on in-memory stores, given events, a command, then the expected events, rejection or state ([Testing](../guide/testing)). The eShop's domain modules are the reference, and domain modules enforce a coverage minimum with Jacoco.

## The contract suites

A backend implements the ports of `:spi`, and its correctness is the contract, not its own tests. The TCK holds one abstract spec per port: `EventLogSpec`, `SnapshotStoreSpec`, `CheckpointStoreSpec`, `LeaseStoreSpec`, `FailureLedgerSpec`, `ReservationStoreSpec`, `TimerStoreSpec`, `SinkSpec` and `TopicTransportSpec` in `:tck`, and `QueryBackendSpec`, `EventQueryBackendSpec` and `DatasetQueryBackendSpec` in `:query-tck`. A backend's container test extends each spec it implements and supplies a fresh store; the PostgreSQL and MongoDB backends run every one. `:tck-containers` starts the shared containers, with pinned images. [Write a Backend](./backends) shows how.

## Documentation and scripts

- The documentation's checks run with Node from the repository root: `node .github/scripts/docs-samples.mjs`, which holds every sample to its source, `docs-layout.mjs`, `docs-consistency.mjs` and `repo-hygiene.mjs`.
- `cd documentation && pnpm docs:build` builds the site and fails on a dead link.
- `node --test documentation/test/*.test.mjs` runs the site's tests, which compile the TypeScript samples, among others.
- `pnpm test:ci-scripts` tests the CI scripts themselves.
- `pnpm docs:reference` regenerates the reference pages that come from the code, and a change to configuration metadata, error codes or annotations commits its output.

## The CI gates

`ci.yml` runs on every pull request, scoped by the paths it changes: a documentation-only or TypeScript-only change skips the JVM jobs.

- **JVM:** `:check` in three shards, one of them with the publication check, and `:integrationTest` in four shards.
- **TypeScript:** static checks, the unit matrix, the package checks, the Storybook interactions, the eShop web app and the consoles.
- **Documentation:** the site, its layout and repository hygiene.
- **Coverage:** Codecov requires 90% of changed main code covered, and no module's coverage may drop by more than 1%.

`ci-gate` is the one required check: it passes when every job that ran passed, and, where the run uploaded coverage, when Codecov's patch status passes. In CI a failed test runs again up to twice, to show whether it is flaky, but a test that passes only on a retry still fails the build: a flaky test is fixed, not tolerated.
