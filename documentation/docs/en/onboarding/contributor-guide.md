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

Local Test reduces pull-request latency without changing tests, coverage, or the required-check name. It uses two Gradle shards with one fixed side and an automatic complement; it does not introduce a third shard, a larger runner, a new dependency, or a custom scheduler.

#### Decision evidence

The [original slow job](https://github.com/Ahoo-Wang/Wow/actions/runs/33380885897/job/99452754237?pr=3115) spent 9m29s in Gradle and executed 178 of 184 actionable tasks, with only 6 restored from cache. After correcting the Gradle cache topology, a [representative pull request](https://github.com/Ahoo-Wang/Wow/actions/runs/33451289472) still spent 9m29s in Gradle, but executed tasks fell to 135 and cached tasks rose to 49; the matching [`main` job](https://github.com/Ahoo-Wang/Wow/actions/runs/33455593795) took 6m09s. The cache works, but cold test and compilation work remains the critical path.

The [forced full profile](https://github.com/Ahoo-Wang/Wow/actions/runs/33460101373/job/99708267226) ran all 184 tasks at commit `8651f2bbcb05fb48e23e24283dfa83e667df9516`: Gradle wall time was 8m57s and cumulative task time was 26m59s. Tests accounted for 17m18s, about 64%; compilation, KSP, and KAPT accounted for 9m27s, about 35%; effective parallelism was about 3.36. The profile came from closed, unmerged [PR #3134](https://github.com/Ahoo-Wang/Wow/pull/3134) and is measurement evidence only.

#### Gradle shards

`test/code-coverage-report/build.gradle.kts` defines `localCoverageReportShard1` and `localCoverageReportShard2`. Each task directly depends only on its shard's `test` tasks and emits one JaCoCo XML report. Both reports retain the full source/class scope of the existing `localCoverageReport`, but each uses execution data only from shard projects that intersect that scope. `wow-compensation-server` still runs its tests but, as today, does not enter the coverage report. The existing unsharded task remains the local regression baseline.

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

#### Workflow and coverage

`.github/workflows/local-test.yml` first runs a two-entry matrix. Each entry executes its Gradle coverage task and uploads a uniquely named XML artifact with `if-no-files-found: error`. The downstream job remains named `Local Test` to preserve the required check used by branch protection. It downloads both XML files and invokes the Codecov uploader once, retaining the `local` flag, OIDC, `disable_search: true`, and `fail_ci_if_error: true`. Codecov documents that one flag can accept multiple reports and merge them into that flag's total coverage; see [Flags](https://docs.codecov.com/docs/flags).

The downstream job uses `if: always()` to inspect the aggregate matrix result, then requires that result to equal `success`. A test failure or missing XML fails its shard. If any shard fails or is cancelled, the guard fails before artifact download. If a successfully uploaded artifact later cannot be downloaded or is corrupt, the download step fails. No failure path reaches Codecov, so partial coverage is never uploaded. Both shards keep `gradle/actions/setup-gradle` and the existing `main` push, pull-request trigger, and cache-seeding semantics.

#### Verification and acceptance

Implementation must provide these results in order:

1. Gradle's configuration-time partition guards pass, and each shard's `--dry-run` contains only the expected test tasks.
2. `actionlint`, complete runs of both shards, and the original `allLocalTest :code-coverage-report:localCoverageReport` regression pass.
3. At one commit, compare the unsharded XML with the two reports merged by Codecov under `local`. Line, branch, and per-file coverage must not drift. If Codecov merging changes coverage, stop rollout and use raw JaCoCo exec aggregation; do not weaken the coverage threshold.
4. Temporary `--rerun-tasks` is allowed for one real-runner measurement only and must be absent from the final diff.
5. Candidate workflow wall time is at most 7 minutes, shard wall times differ by no more than 15% of the slower shard, and cumulative runner-minutes stay at or below 125% of the former 8m51s Local Test median: 11m04s.

After merge, observe the next 10 code pull requests that trigger Local Test. Use the ninth value after sorting durations in ascending order as the nearest-rank P90 and require it to stay at or below 7 minutes. If coverage drifts, the required check becomes unreliable, or runner cost exceeds the limit, revert the workflow and shard tasks in one commit. Revisit a third shard only if P90 still misses the target and a new profile proves it can improve the critical path within budget; this design adds no third-shard abstraction in advance.

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
