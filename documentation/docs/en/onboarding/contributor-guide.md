---
title: Contributor Guide
description: Decide whether a Wow contribution is ready for review using the repository's actual modules, Gradle tasks, and CI workflows.
---

# Contributor Guide

This page answers one question: **is my change ready for pull-request review?**

The answer is yes only when the scope is explicit, the owning module is correct, behavior has regression evidence, relevant checks pass, and the diff contains no unrelated files. A local pass does not prove remote CI, review approval, or merge status.

## Decision inputs

Establish four verifiable inputs before editing:

1. **Problem and scope**: record the behavior to change, compatibility that must not change, and an observable completion condition. Discuss public APIs, generated contracts, new dependencies, module-boundary moves, and breaking changes first.
2. **Owning module**: use [`settings.gradle.kts`](https://github.com/Ahoo-Wang/Wow/blob/main/settings.gradle.kts) as the project inventory. Contracts normally belong in `wow-api`, runtime behavior in `wow-core`, Spring wiring in `wow-spring*`, and storage or transport in the corresponding extension module.
3. **Existing callers and tests**: find the definition, callers, implementations, tests, and generated consumers. A behavior change needs the smallest evidence that fails before the fix and passes afterward.
4. **Worktree baseline**: inspect `git status --short` and the target diff. Preserve existing user changes and keep `.gradle/`, `node_modules/`, build output, and IDE state out of the commit.

See [`CONTRIBUTING.md`](https://github.com/Ahoo-Wang/Wow/blob/main/CONTRIBUTING.md) for the complete collaboration contract. [Core Concepts](../guide/core-concepts.md) and [Architecture](../guide/advanced/architecture.md) own framework and runtime explanations; this page does not duplicate them.

## The shortest contribution path

### 1. Start with one vertical slice

Domain behavior usually traces through API contract → aggregate decision → state sourcing → specification. The cart and order examples live in `example/example-api` and `example/example-domain`; tests use the Wow DSL and FluentAssert `.assert()` convention.

Run the narrowest real test first, for example:

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.command.DefaultCommandGatewayTest"
./gradlew :example-domain:test --tests "me.ahoo.wow.example.domain.order.OrderSpec"
```

For a behavior change, preserve RED, implement the smallest fix, then run the same command for GREEN. For documentation-only work, preserve current source, configuration, workflow, or runnable-example evidence instead.

### 2. Expand to the owning module

```bash
./gradlew <module>:check
```

Use actual paths from [`settings.gradle.kts`](https://github.com/Ahoo-Wang/Wow/blob/main/settings.gradle.kts), such as `:wow-api`, `:wow-core`, `:wow-spring-boot-starter`, `:wow-compensation-domain`, `:example-domain`, or `:wow-test`.

Do not substitute a neighboring module's success for the owning module check. For a cross-module contract, check both its producer and affected consumers.

### 3. Match the affected CI layer

Pull-request workflows are the source of truth for CI:

| Change surface | Local equivalent | Workflow |
|---|---|---|
| JVM local tests | `./gradlew allLocalTest :code-coverage-report:localCoverageReport --stacktrace` | [`local-test.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/local-test.yml) |
| Contract tests | `./gradlew allContractTest :code-coverage-report:contractCoverageReport --stacktrace` | [`contract-test.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/contract-test.yml) |
| Integration tests | `./gradlew allIntegrationTest :code-coverage-report:integrationCoverageReport --stacktrace` | [`integration-test.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/integration-test.yml) |
| Kotlin static analysis | `./gradlew detekt --stacktrace` | [`static-analysis.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/static-analysis.yml) |
| Compensation modules | `./gradlew :wow-compensation-core:check :wow-compensation-domain:check --stacktrace` | [`compensation-test.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/compensation-test.yml) |
| Java example | `./gradlew :example-transfer-api:build :example-transfer-domain:build :example-transfer-server:build --stacktrace` | [`example-java-test.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/example-java-test.yml) |
| Benchmark smoke | `./gradlew :wow-benchmarks:test :wow-benchmarks:benchmarkSmoke --stacktrace` | [`benchmark-smoke.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/benchmark-smoke.yml) |

### Local Test two-shard contract

Local Test reduces pull-request latency without changing tests, coverage, or the required-check name. It uses two test-only Gradle shards, transfers raw JaCoCo execution data and compiled main classes, then emits one XML in a downstream job. It does not introduce a third shard, a larger runner, a new dependency, or a custom scheduler.

#### Decision evidence

The [original slow job](https://github.com/Ahoo-Wang/Wow/actions/runs/33380885897/job/99452754237?pr=3115) spent 9m29s in Gradle and executed 178 of 184 actionable tasks, with only 6 restored from cache. After correcting the Gradle cache topology, a [representative pull request](https://github.com/Ahoo-Wang/Wow/actions/runs/33451289472) still spent 9m29s in Gradle, but executed tasks fell to 135 and cached tasks rose to 49; the matching [`main` job](https://github.com/Ahoo-Wang/Wow/actions/runs/33455593795) took 6m09s. The cache works, but cold test and compilation work remains the critical path.

The [forced full profile](https://github.com/Ahoo-Wang/Wow/actions/runs/33460101373/job/99708267226) ran all 184 tasks at commit `8651f2bbcb05fb48e23e24283dfa83e667df9516`: Gradle wall time was 8m57s and cumulative task time was 26m59s. Tests accounted for 17m18s, about 64%; compilation, KSP, and KAPT accounted for 9m27s, about 35%; effective parallelism was about 3.36. The profile came from closed, unmerged [PR #3134](https://github.com/Ahoo-Wang/Wow/pull/3134) and is measurement evidence only.

The first two-XML candidate was force-measured in closed, unmerged [PR #3138](https://github.com/Ahoo-Wang/Wow/pull/3138) and its [diagnostic run](https://github.com/Ahoo-Wang/Wow/actions/runs/33467569094). The production path took 382 seconds and the shard gap was 7.65%, but cumulative runner time reached 714 seconds against a 664-second limit. Codecov coverage fell from the unsharded baseline's 88.20% to 88.15%. After excluding Codecov's zeroed complexity representation, 14 files and 15 line states still drifted. Separate XML files retain only aggregate branch/instruction counters, not complementary JaCoCo probe identity, so that architecture was rejected. This design merges raw execution data before emitting one XML.

#### Gradle shards

`test/code-coverage-report/build.gradle.kts` defines `localTestShard1` and `localTestShard2`. They depend only on their shard's `test` tasks, configure no JaCoCo report, and do not pull the other shard's compilation tasks through a full classDirectories collection. `wow-compensation-server:test` remains in shard 2 but, as today, does not enter the coverage report.

Shard 1 is fixed to these 15 projects selected from the measured workload:

```text
:wow-core
:wow-openapi
:wow-bi
:wow-elasticsearch
:wow-query
:wow-kafka
:wow-opentelemetry
:wow-cocache
:wow-compensation-core
:example-transfer-domain
:wow-tck
:wow-spring
:wow-compensation-api
:example-api
:example-transfer-api
```

Shard 2 is `localTestTaskProjects - shard1`. Its initial set is:

```text
:wow-spring-boot-starter
:wow-compiler
:wow-mongo
:wow-webflux
:wow-schema
:wow-redis
:wow-compensation-server
:example-domain
:wow-models
:wow-api
:wow-compensation-domain
:wow-cosec
:wow-apiclient
:wow-test
:wow-mock
:wow-it
```

Configuration-time guards require every shard-1 path to exist, both shards to be nonempty, no overlap, and an exact union with `localTestTaskProjects`. New Local Test projects therefore enter shard 2 automatically. If measurements later show imbalance, move one existing path instead of adding a configuration layer.

The same build script also defines `verifyLocalCoverageArtifacts` and `localCoverageReportFromArtifacts`. The verification task uses current source sets to require the raw `build/jacoco/test.exec` and main class files that should exist. The report task reads only explicit downloaded file paths, has no compilation or test dependencies, and reuses the complete source/class project scope of the existing `localCoverageReport`. The original `localCoverageReport` remains the unsharded baseline.

#### Workflow and coverage

`.github/workflows/local-test.yml` first runs a two-entry matrix. Each entry executes its test-only Gradle task and uses `actions/upload-artifact@v4` to upload the runner's existing `**/build/classes/kotlin/main/**`, `**/build/classes/java/main/**`, and `**/build/jacoco/test.exec`. A multi-path artifact keeps paths relative to the workspace's common ancestor, and `if-no-files-found: error` rejects an empty artifact. Current local outputs estimate 16.5 MiB and 14.2 MiB uncompressed by shard ownership; the hosted runner measurement remains authoritative.

The downstream job remains named `Local Test` to preserve the required branch-protection check. It uses `if: always()` to inspect the aggregate matrix result and first requires `success`. It then downloads both artifacts by exact name into the workspace root, verifies the coverage inputs, runs the detached JaCoCo report, and uploads only that XML to Codecov while retaining the `local` flag, OIDC, `disable_search: true`, and `fail_ci_if_error: true`. A failed or cancelled shard, missing artifact, incomplete coverage input, report failure, or Codecov failure makes `Local Test` fail; partial coverage is never uploaded. Both shards retain `gradle/actions/setup-gradle` and the existing `main` push, pull-request trigger, and cache-seeding semantics.

#### Verification and acceptance

Implementation must provide these results in order:

1. Gradle's configuration-time partition guards pass. Each test-only shard's `--dry-run` contains only its expected test tasks. The artifact report's `--dry-run` may contain only verification and report tasks—never compile, KSP, KAPT, or test tasks.
2. `actionlint`, complete runs of both shards, raw artifact restoration, single-XML generation, and the original `allLocalTest :code-coverage-report:localCoverageReport` regression pass.
3. A diagnostic PR runs the forced sharded production path and a separate forced unsharded baseline at the same SHA. A comparison job downloads both XML files, removes only non-semantic JaCoCo `sessioninfo`, then compares every package, class, method, source line, branch, and counter. Any drift fails. The unsharded baseline and comparison exist only in diagnostics, not the final workflow.
4. Temporary `--rerun-tasks`, the baseline job, comparison job, and diagnostic XML artifacts are allowed only for one real-runner measurement and must be absent from the final diff.
5. The production path from workflow `createdAt` through the final `Local Test` `completedAt` is at most 7 minutes. Shard wall times differ by no more than 15% of the slower shard. The two shards plus final `Local Test` consume at most 125% of the former 8m51s Local Test median: 11m04s. Diagnostic baseline and comparison jobs do not count toward production metrics.
6. The final candidate has exactly one Codecov `local` session, whose only XML comes from raw execution-data aggregation.

After merge, observe the next 10 code pull requests that trigger Local Test. Use the ninth duration after ascending sort as the nearest-rank P90 and require it to stay at or below 7 minutes. If the raw design still drifts coverage, breaks the required check, or exceeds runner cost before merge, abandon sharding and restore the single job. If a post-merge continuous gate fails, revert the workflow, Gradle tasks, and this documentation section in one commit. This design adds no third-shard abstraction in advance.

Run only the layers the change requires. Expand to aggregate tasks for shared runtime, TCK, or multi-backend changes; never report an unrun task as passing.

The dashboard and documentation use their native commands:

```bash
pnpm --dir compensation/dashboard test
pnpm --dir compensation/dashboard lint
pnpm --dir compensation/dashboard build
pnpm --dir documentation docs:build
```

Dashboard CI also runs coverage and browser tests; use [`dashboard-test.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/dashboard-test.yml) for the exact commands. Do not make `compensation/dashboard/src/generated/` the primary fix when OpenAPI or generator input can be corrected.

## Completion evidence

Before opening a PR, provide evidence rather than “looks good”:

- one sentence naming the owning boundary and compatibility scope;
- RED → GREEN for behavior, or current fact sources for documentation;
- exact narrow-test and owning-module `check` commands, exit results, and failure counts;
- local results for affected CI layers, with unrun layers named explicitly;
- a passing `git diff --check`;
- `git status --short` and a final diff containing only intended files;
- a PR description with verification, risk, rollback or migration boundary, and remaining environment evidence.

Only the corresponding remote result can prove remote CI, review, or merge status.

## Prioritized next path

1. **Prepare a first contribution**: read [Test Suite](../guide/test-suite.md), then start from an existing specification and owning-module check.
2. **Change a public or runtime boundary**: use the [Staff Engineer Guide](./staff-engineer-guide.md) to separate source, binary, wire, and operational risk first.
3. **Change documentation only**: use current source as authority and run the complete VitePress build.
