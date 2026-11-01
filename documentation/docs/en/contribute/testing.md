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
| End to end | `examples/eshop/e2e` | `:eshop-e2e:integrationTest` | Docker: PostgreSQL, Kafka, ClickHouse |

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

A backend implements the ports of `:spi`, and its correctness is the contract, not its own tests. The TCK holds one abstract spec per port: `EventLogSpec`, `SnapshotStoreSpec`, `CheckpointStoreSpec`, `LeaseStoreSpec`, `FailureLedgerSpec`, `ReservationStoreSpec`, `TimerStoreSpec`, `SinkSpec` and `TopicTransportSpec` in `:tck`, and `QueryBackendSpec`, `EventQueryBackendSpec` and `DatasetQueryBackendSpec` in `:query-tck`. `StoreTck` runs them all against a whole store, opened through its provider as the host opens it. A backend's container test runs `StoreTck` for each store type it provides, and extends each spec it implements to supply a fresh store of its own: PostgreSQL and MongoDB run the seven store specs and the snapshot and event query specs. `:tck-containers` starts the shared containers, with pinned images. [Write a Backend](./backends) shows how.

## Documentation and scripts

- The documentation's checks run with Node from the repository root: `node .github/scripts/docs-samples.mjs`, which holds every sample to its source, `docs-layout.mjs`, `docs-consistency.mjs` and `repo-hygiene.mjs`.
- `cd documentation && pnpm docs:build` builds the site and fails on a dead link.
- `node --test documentation/test/*.test.mjs` runs the site's tests, which compile the TypeScript samples, among others.
- `pnpm test:ci-scripts` tests the CI scripts themselves.
- `pnpm docs:reference` regenerates the reference pages that come from the code, and a change to configuration metadata, error codes or annotations commits its output.

## The CI gates

`ci.yml` runs on every pull request, scoped by the paths it changes: a documentation-only or TypeScript-only change skips the JVM jobs.

- **JVM:** `:check` in four shards (`host`, `apps`, `tooling` and `rest`, the last with the publication check), and `:integrationTest` in four shards.
- **TypeScript:** static checks, the unit matrix, the package checks, the Storybook interactions, the eShop web app and the consoles.
- **Documentation:** the site, its layout and repository hygiene.
- **Coverage:** the coverage job requires at least 89% of the changed main-source lines covered (`.github/scripts/patch-coverage.mjs`, Codecov's 90% target less its 1% threshold), judged from the run's own reports; Codecov's statuses are informational. The nightly run on `main` fails on a drop of more than 0.5 point overall or 1 point on a flag (`coverage-trend.mjs`).

`ci-gate` is the merge signal, which branch protection requires with PR Safety: it passes when every job that ran passed, and, on a run with coverage, when the coverage job's patch verdict does not fail. In CI a failed test runs again up to twice, to show whether it is flaky, but a test that passes only on a retry still fails the build: a flaky test is fixed, not tolerated.
