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
