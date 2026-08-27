# Wow Documentation Content Rewrite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite the remaining 154 user-facing Wow documents so the complete 160-document scope follows the approved user journey, page responsibilities, factual evidence rules, and bilingual contract.

**Architecture:** Keep all existing paths and rewrite content in coherent domain batches. Each task starts from current source/config/test evidence, writes Chinese as the content baseline, mirrors the English semantics where a pair exists, runs the narrowest relevant checks, builds the complete VitePress site, and commits only its declared files.

**Tech Stack:** Markdown, VitePress 1.6.4, Kotlin/JVM 17, Gradle, pnpm, existing repository tests and examples

**Spec:** `document/design/2026-08-27-documentation-rewrite.md`

## Global Constraints

- The target set is exactly 160 existing Markdown documents: 148 bilingual site pages, two root READMEs, and ten user-facing module READMEs. Batch 1 already rewrote six site entry pages; this plan rewrites the remaining 154.
- Every declared target file must receive a meaningful content rewrite. Do not manufacture wording-only churn: improve page responsibility, factual accuracy, task flow, evidence, or bilingual parity in every changed file.
- Preserve every existing Markdown path and public URL. Do not rename, move, delete, or replace pages with empty redirects.
- Chinese is the content baseline for every bilingual pair. English must preserve the same page responsibility, section intent, examples, routes, completion semantics, limitations, and technical facts while reading naturally in English.
- `documentation/docs/zh/guide/introduction.md` is the canonical value narrative. Preserve its six approved claims: business value first, Domain Model as a Service, command → event → state, explicit completion stages, engineering/enterprise value, and honest adoption costs.
- Use this fact-source order: current public/generated contracts; Gradle/configuration metadata; runtime implementation; tests/examples/CI; existing docs. Existing prose never overrides current code.
- Page types follow the approved model: tutorials end in observable success; how-to guides solve one task and include verified failure/recovery behavior; explanations cover mechanism and trade-offs; references record exact contracts/defaults/limits; READMEs remain concise entry points.
- Reuse current Demo, order/cart, compensation, and Java transfer examples. Do not add a documentation-only sample or new dependency.
- Keep source, binary, and wire compatibility claims separate. Do not promise performance, availability, consistency, security, compliance, or compatibility without current evidence and explicit scope.
- Preserve or update all internal links and anchor references affected by heading changes. VitePress must report no broken internal links.
- Do not modify framework implementation, Gradle module structure, generated contracts, CI/CD, release, deployment, credentials, or generated client code. Implementation defects discovered during fact checking are reported separately and not fixed in these tasks.
- The pre-existing Rollup warning for chunks larger than 500 kB is outside this content rewrite unless a task changes build configuration or demonstrably worsens bundle output.
- Every implementation task runs its declared focused checks, `pnpm --dir documentation docs:build`, and `git diff --check` before committing.
- Commit only the files declared by the current task. Never stage `.superpowers/`, `node_modules`, build output, local preview files, or unrelated user changes.

## Shared Rewrite Procedure

Every Task 1-14 follows these steps in addition to its task-specific requirements:

1. Run the task's branch-diff count contract before editing and record the expected failure because none of its target files have been rewritten yet.
2. Read every target page completely and classify it as tutorial, how-to, explanation, reference, or README.
3. Trace every public type, configuration key, route, default, code example, task name, version statement, and operational claim to current repository evidence.
4. Rewrite the Chinese pages first. Remove duplicated facts, unsupported promises, obsolete instructions, and competing entry paths while preserving the approved value or teaching intent.
5. Rewrite the English mirrors from the approved Chinese semantics. Do not use literal line-by-line translation when natural English needs different phrasing.
6. Run the focused verification, complete VitePress build, branch-diff count contract, and `git diff --check`.
7. Self-review for missing targets, broken anchors, duplicated authority, version drift, unsupported claims, and bilingual semantic divergence.
8. Commit only the declared files with the exact task commit subject.

---

### Task 1: Rewrite the Value and First-Success Path

**Files:**
- Modify: `README.md`
- Modify: `README.zh-CN.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/introduction.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/core-concepts.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/getting-started.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/existing-project.md`

**Content contract:** Root READMEs become concise project entry points: value summary, fit boundary, 30-minute CTA, capability evidence, compatibility baseline, and canonical links. Introduction preserves the six approved claims and adoption costs. Core Concepts supplies the stable vocabulary. Getting Started proves template version, domain test, server, real HTTP command, requested wait stage, and versioned sourced state. Existing Project proves dependency/capability selection, KSP metadata, runtime route wiring, command/state flow, failure checkpoints, and rollback boundary.

**Fact sources:** `gradle.properties`, `gradle/libs.versions.toml`, `wow-project-template`, `example/`, `wow-api`, `wow-core`, `wow-webflux`, `wow-spring-boot-starter`, and their tests.

- [ ] **Step 1: Verify the ten-file change contract fails before editing**

```bash
test "$(git diff --name-only main...HEAD -- README.md README.zh-CN.md documentation/docs/{en,zh}/guide/{introduction,core-concepts,getting-started,existing-project}.md | wc -l | tr -d ' ')" = 10
```

Expected before editing: exit `1`.

- [ ] **Step 2: Validate the current project-template tutorial path**

Use a temporary directory outside the repository, inspect the current template version and route generation, run its `:domain:check`, start its server with the documented in-memory configuration, send the documented command, and read the versioned state. Record exact commands and observable results in the task report; if the template differs, update the tutorial to what actually works.

- [ ] **Step 3: Rewrite all ten files under the content contract**

Keep README badges and verified award attribution, but remove unsupported current performance or defect-rate promises. Preserve existing URLs and update all changed anchors and links.

- [ ] **Step 4: Run focused and complete verification**

```bash
./gradlew :example-domain:check :wow-core:check :wow-webflux:check :wow-spring-boot-starter:check
pnpm --dir documentation docs:build
test "$(git diff --name-only main...HEAD -- README.md README.zh-CN.md documentation/docs/{en,zh}/guide/{introduction,core-concepts,getting-started,existing-project}.md | wc -l | tr -d ' ')" = 10
git diff --check
```

- [ ] **Step 5: Commit**

```bash
git add -- README.md README.zh-CN.md documentation/docs/{en,zh}/guide/{introduction,core-concepts,getting-started,existing-project}.md
git commit -m "docs: rewrite value and first-success path"
```

---

### Task 2: Rewrite Modeling and Testing Guides

**Files:**
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/modeling.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/test-suite.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/application-testing.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/test-runtime.md`

**Content contract:** Explain supported aggregate patterns and invariants, then turn those behaviors into Given → When → Expect tests. Separate framework DSL tests, application integration gates, and framework benchmark/test-runtime evidence. Use FluentAssert `.assert()` examples and current task names; historical coverage or benchmark figures remain qualified evidence, not universal promises.

**Fact sources:** `example/example-domain`, `test/wow-test`, root Gradle test/coverage configuration, `wow-benchmarks`, and CI workflows.

- [ ] **Step 1: Verify the eight-file contract fails**

```bash
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/guide/{modeling,test-suite,application-testing,test-runtime}.md | wc -l | tr -d ' ')" = 8
```

Expected: exit `1` before editing.

- [ ] **Step 2: Rewrite and mirror all four page pairs**

Each page states its completion signal and next testing layer. Keep runnable examples aligned with current Cart/Order specs and distinguish application tests from framework repository tests.

- [ ] **Step 3: Verify and commit**

```bash
./gradlew :wow-test:check :example-domain:check :wow-benchmarks:check
pnpm --dir documentation docs:build
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/guide/{modeling,test-suite,application-testing,test-runtime}.md | wc -l | tr -d ' ')" = 8
git diff --check
git add -- documentation/docs/{en,zh}/guide/{modeling,test-suite,application-testing,test-runtime}.md
git commit -m "docs: rewrite modeling and testing guides"
```

---

### Task 3: Rewrite Command and Event-Storage Guides

**Files:**
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/command-gateway.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/eventstore.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/snapshot.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/event-processor.md`

**Content contract:** Trace one command through validation, idempotency, wait stages, aggregate restoration, event append, snapshot handling, and downstream processing. Distinguish authoritative event history from acceleration snapshots and derived processors. State observable failure stages and retry/idempotency responsibilities without inventing backend validation.

**Fact sources:** public contracts and implementations in `wow-api` and `wow-core`, command/event/snapshot tests, and example HTTP routes.

- [ ] **Step 1: Verify the eight-file contract fails**

```bash
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/guide/{command-gateway,eventstore,snapshot,event-processor}.md | wc -l | tr -d ' ')" = 8
```

Expected: exit `1` before editing.

- [ ] **Step 2: Rewrite the complete command-to-processing flow**

Use one terminology source and identify whether examples prove `SENT`, `PROCESSED`, `SNAPSHOT`, or `PROJECTED`.

- [ ] **Step 3: Verify and commit**

```bash
./gradlew :wow-api:check :wow-core:check
pnpm --dir documentation docs:build
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/guide/{command-gateway,eventstore,snapshot,event-processor}.md | wc -l | tr -d ' ')" = 8
git diff --check
git add -- documentation/docs/{en,zh}/guide/{command-gateway,eventstore,snapshot,event-processor}.md
git commit -m "docs: rewrite command and event storage guides"
```

---

### Task 4: Rewrite Saga and Compensation Guides

**Files:**
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/saga.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/event-compensation.md`

**Content contract:** Separate saga orchestration from event-handler retry/compensation. Document normal, retryable, unrecoverable, idempotent, and operator-driven paths with current APIs and dashboard behavior. State that compensation is not a database rollback.

**Fact sources:** `example-domain` saga specs, `wow-compensation-*`, dashboard screens, compensation tests, and runtime contracts.

- [ ] **Step 1: Verify the four-file contract fails**

```bash
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/guide/{saga,event-compensation}.md | wc -l | tr -d ' ')" = 4
```

Expected: exit `1` before editing.

- [ ] **Step 2: Rewrite both pairs and verify**

```bash
./gradlew :example-domain:check :wow-compensation-domain:check :wow-compensation-core:check
pnpm --dir documentation docs:build
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/guide/{saga,event-compensation}.md | wc -l | tr -d ' ')" = 4
git diff --check
```

- [ ] **Step 3: Commit**

```bash
git add -- documentation/docs/{en,zh}/guide/{saga,event-compensation}.md
git commit -m "docs: rewrite saga and compensation guides"
```

---

### Task 5: Rewrite Query and Interface Guides

**Files:**
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/projection.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/query.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/data-access.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/open-api.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/advanced/schema.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/extensions/apiclient.md`

**Content contract:** Present the read path as event → projection → query model → guarded query/API client. Preserve current aggregation, path relativity, wait-stage, data-access, schema, OpenAPI, and API-client contracts. Separate generated metadata, runtime WebFlux routes, query model schema, and client generation responsibilities.

**Fact sources:** `wow-query`, `wow-openapi`, `wow-schema`, `wow-apiclient`, projection/query tests, KSP metadata, and existing examples.

- [ ] **Step 1: Verify the twelve-file contract fails**

```bash
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/guide/{projection,query,data-access,open-api}.md documentation/docs/{en,zh}/guide/advanced/schema.md documentation/docs/{en,zh}/guide/extensions/apiclient.md | wc -l | tr -d ' ')" = 12
```

Expected: exit `1` before editing.

- [ ] **Step 2: Rewrite all six pairs**

Keep query examples executable and contract-accurate. Do not change aggregation field meaning or claim query security beyond the public data-access contract.

- [ ] **Step 3: Verify and commit**

```bash
./gradlew :wow-query:check :wow-openapi:check :wow-schema:check :wow-apiclient:check
pnpm --dir documentation docs:build
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/guide/{projection,query,data-access,open-api}.md documentation/docs/{en,zh}/guide/advanced/schema.md documentation/docs/{en,zh}/guide/extensions/apiclient.md | wc -l | tr -d ' ')" = 12
git diff --check
git add -- documentation/docs/{en,zh}/guide/{projection,query,data-access,open-api}.md documentation/docs/{en,zh}/guide/advanced/schema.md documentation/docs/{en,zh}/guide/extensions/apiclient.md
git commit -m "docs: rewrite query and interface guides"
```

---

### Task 6: Rewrite Reference Examples

**Files:**
- Modify bilingual pairs: `documentation/docs/{en,zh}/reference/example/order.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/reference/example/transfer.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/reference/example/compensation.md`

**Content contract:** Each example becomes a traceable reference walkthrough with module map, domain decisions, commands/events/state, runnable commands, expected outputs, tests, failure behavior, and exact source links. Do not infer HTTP routes from bounded-context naming; verify runtime/generated routes.

**Fact sources:** `example/`, Java transfer modules, compensation modules/dashboard, generated OpenAPI where available, and tests.

- [ ] **Step 1: Verify the six-file contract fails**

```bash
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/reference/example/{order,transfer,compensation}.md | wc -l | tr -d ' ')" = 6
```

Expected: exit `1` before editing.

- [ ] **Step 2: Rewrite and verify all example pairs**

```bash
./gradlew :example-domain:check :example-transfer-domain:check :wow-compensation-domain:check
pnpm --dir documentation docs:build
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/reference/example/{order,transfer,compensation}.md | wc -l | tr -d ' ')" = 6
git diff --check
```

- [ ] **Step 3: Commit**

```bash
git add -- documentation/docs/{en,zh}/reference/example/{order,transfer,compensation}.md
git commit -m "docs: rewrite reference examples"
```

---

### Task 7: Rewrite Configuration and Production Guides

**Files:**
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/configuration.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/best-practices.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/recovery.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/troubleshooting.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/advanced/module-dependencies.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/reference/config/core.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/reference/config/infrastructure.md`

**Content contract:** Separate task-oriented production guidance from exact configuration reference. Document capability selection, defaults, ownership, backup/replay prerequisites, failure-stage diagnosis, rollback boundaries, and evidence required before production claims. Avoid generic checklists that do not map to Wow runtime stages.

**Fact sources:** Spring configuration metadata, starter feature variants, module Gradle files, core runtime, storage/message configuration, recovery code/tests, and operational examples.

- [ ] **Step 1: Verify the fourteen-file contract fails**

```bash
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/guide/{configuration,best-practices,recovery,troubleshooting}.md documentation/docs/{en,zh}/guide/advanced/module-dependencies.md documentation/docs/{en,zh}/reference/config/{core,infrastructure}.md | wc -l | tr -d ' ')" = 14
```

Expected: exit `1` before editing.

- [ ] **Step 2: Rewrite, verify, and commit**

```bash
./gradlew :wow-core:check :wow-spring-boot-starter:check :wow-mongo:check :wow-redis:check :wow-kafka:check
pnpm --dir documentation docs:build
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/guide/{configuration,best-practices,recovery,troubleshooting}.md documentation/docs/{en,zh}/guide/advanced/module-dependencies.md documentation/docs/{en,zh}/reference/config/{core,infrastructure}.md | wc -l | tr -d ' ')" = 14
git diff --check
git add -- documentation/docs/{en,zh}/guide/{configuration,best-practices,recovery,troubleshooting}.md documentation/docs/{en,zh}/guide/advanced/module-dependencies.md documentation/docs/{en,zh}/reference/config/{core,infrastructure}.md
git commit -m "docs: rewrite configuration and production guides"
```

---

### Task 8: Rewrite Observability, BI, and Migration Guides

**Files:**
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/advanced/metrics.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/advanced/observability.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/bi.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/bi-operations.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/migration.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/migration/traditional-architecture.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/migration/v6-to-v8.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/guide/migration/runtime-orchestration.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/reference/config/observability.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/reference/config/compensation.md`

**Content contract:** Connect runtime stages to metrics/traces, BI data ownership, operational recovery, and migration/cutover evidence. Migrations distinguish source compatibility, runtime/storage contracts, reconciliation, rollback, and exact pinned versions. Local checks do not prove production admission.

**Fact sources:** OpenTelemetry instrumentation/tests, BI generators, configuration metadata, v6/v8 tagged Gradle contracts, runtime orchestration implementation, and migration examples.

- [ ] **Step 1: Verify the twenty-file contract fails**

```bash
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/guide/advanced/{metrics,observability}.md documentation/docs/{en,zh}/guide/{bi,bi-operations,migration}.md documentation/docs/{en,zh}/guide/migration/{traditional-architecture,v6-to-v8,runtime-orchestration}.md documentation/docs/{en,zh}/reference/config/{observability,compensation}.md | wc -l | tr -d ' ')" = 20
```

Expected: exit `1` before editing.

- [ ] **Step 2: Rewrite, verify, and commit**

```bash
./gradlew :wow-opentelemetry:check :wow-bi:check :wow-compensation-core:check
pnpm --dir documentation docs:build
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/guide/advanced/{metrics,observability}.md documentation/docs/{en,zh}/guide/{bi,bi-operations,migration}.md documentation/docs/{en,zh}/guide/migration/{traditional-architecture,v6-to-v8,runtime-orchestration}.md documentation/docs/{en,zh}/reference/config/{observability,compensation}.md | wc -l | tr -d ' ')" = 20
git diff --check
git add -- documentation/docs/{en,zh}/guide/advanced/{metrics,observability}.md documentation/docs/{en,zh}/guide/{bi,bi-operations,migration}.md documentation/docs/{en,zh}/guide/migration/{traditional-architecture,v6-to-v8,runtime-orchestration}.md documentation/docs/{en,zh}/reference/config/{observability,compensation}.md
git commit -m "docs: rewrite observability bi and migration guides"
```

---

### Task 9: Rewrite Extension Guides

**Files:**
- Modify bilingual pairs under `documentation/docs/{en,zh}/guide/extensions/`: `kafka.md`, `mongo.md`, `redis.md`, `elasticsearch.md`, `opentelemetry.md`, `webflux.md`, `cocache.md`, `cosec.md`, `spring-boot-starter.md`, `tck.md`.

**Content contract:** Every extension page states module purpose, when to use it, dependency/capability selection, exact minimum configuration, runtime ownership, backend-native semantics, verified failure modes, focused test command, and next reference. Do not duplicate core concepts or add validation the backend/framework already owns.

**Fact sources:** extension Gradle files, public contracts, Spring auto-configuration/metadata, TCKs, integration tests, and example configuration.

- [ ] **Step 1: Verify the twenty-file contract fails**

```bash
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/guide/extensions/{kafka,mongo,redis,elasticsearch,opentelemetry,webflux,cocache,cosec,spring-boot-starter,tck}.md | wc -l | tr -d ' ')" = 20
```

Expected: exit `1` before editing.

- [ ] **Step 2: Rewrite all extension pairs and run module checks**

```bash
./gradlew :wow-kafka:check :wow-mongo:check :wow-redis:check :wow-elasticsearch:check :wow-opentelemetry:check :wow-webflux:check :wow-cocache:check :wow-cosec:check :wow-spring-boot-starter:check :wow-tck:check
pnpm --dir documentation docs:build
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/guide/extensions/{kafka,mongo,redis,elasticsearch,opentelemetry,webflux,cocache,cosec,spring-boot-starter,tck}.md | wc -l | tr -d ' ')" = 20
git diff --check
```

- [ ] **Step 3: Commit**

```bash
git add -- documentation/docs/{en,zh}/guide/extensions/{kafka,mongo,redis,elasticsearch,opentelemetry,webflux,cocache,cosec,spring-boot-starter,tck}.md
git commit -m "docs: rewrite extension guides"
```

---

### Task 10: Rewrite Advanced Runtime Guides

**Files:**
- Modify bilingual pairs under `documentation/docs/{en,zh}/guide/advanced/`: `architecture.md`, `runtime-lifecycle.md`, `aggregate-lifecycle.md`, `event-bus.md`, `event-evolution.md`, `serialization.md`, `data-flow.md`, `id-generator.md`, `compiler.md`, `prepare-key.md`, `aggregate-scheduler.md`.

**Content contract:** Define component boundaries, lifecycle/data flow, ordering/concurrency invariants, persisted-event evolution, serialization ownership, compiler outputs, ID/key allocation, and scheduler semantics. Link task execution back to how-to guides instead of repeating setup.

**Fact sources:** `wow-core`, `wow-api`, `wow-compiler`, `wow-models`, scheduler/serialization/event-evolution tests, and architecture diagrams.

- [ ] **Step 1: Verify the twenty-two-file contract fails**

```bash
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/guide/advanced/{architecture,runtime-lifecycle,aggregate-lifecycle,event-bus,event-evolution,serialization,data-flow,id-generator,compiler,prepare-key,aggregate-scheduler}.md | wc -l | tr -d ' ')" = 22
```

Expected: exit `1` before editing.

- [ ] **Step 2: Rewrite all eleven pairs and verify**

```bash
./gradlew :wow-api:check :wow-core:check :wow-compiler:check :wow-models:check
pnpm --dir documentation docs:build
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/guide/advanced/{architecture,runtime-lifecycle,aggregate-lifecycle,event-bus,event-evolution,serialization,data-flow,id-generator,compiler,prepare-key,aggregate-scheduler}.md | wc -l | tr -d ' ')" = 22
git diff --check
```

- [ ] **Step 3: Commit**

```bash
git add -- documentation/docs/{en,zh}/guide/advanced/{architecture,runtime-lifecycle,aggregate-lifecycle,event-bus,event-evolution,serialization,data-flow,id-generator,compiler,prepare-key,aggregate-scheduler}.md
git commit -m "docs: rewrite advanced runtime guides"
```

---

### Task 11: Rewrite Architecture Articles

**Files:**
- Modify bilingual pairs: `documentation/docs/{en,zh}/articles/index.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/articles/command-success-is-not-complete.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/articles/traditional-vs-wow-architecture.md`
- Modify bilingual pairs: `documentation/docs/{en,zh}/articles/why-ddd-fits-ai-era.md`

**Content contract:** Preserve each article's thesis while separating opinion, framework behavior, current repository evidence, and external research. Articles may persuade, but must link canonical guides for contracts and avoid unsupported market, productivity, quality, or AI claims. The article index routes by reader question rather than chronology.

**Fact sources:** canonical rewritten guides, current repository evidence, and authoritative primary external sources for retained external claims.

- [ ] **Step 1: Verify the eight-file contract fails**

```bash
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/articles/{index,command-success-is-not-complete,traditional-vs-wow-architecture,why-ddd-fits-ai-era}.md | wc -l | tr -d ' ')" = 8
```

Expected: exit `1` before editing.

- [ ] **Step 2: Rewrite all article pairs and verify retained external claims**

```bash
./gradlew :example-domain:check
pnpm --dir documentation docs:build
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/articles/{index,command-success-is-not-complete,traditional-vs-wow-architecture,why-ddd-fits-ai-era}.md | wc -l | tr -d ' ')" = 8
git diff --check
```

- [ ] **Step 3: Commit**

```bash
git add -- documentation/docs/{en,zh}/articles/{index,command-success-is-not-complete,traditional-vs-wow-architecture,why-ddd-fits-ai-era}.md
git commit -m "docs: rewrite architecture articles"
```

---

### Task 12: Rewrite Role and Resource Guides

**Files:**
- Modify bilingual role pairs: `documentation/docs/{en,zh}/onboarding/contributor-guide.md`, `staff-engineer-guide.md`, `executive-guide.md`, `product-manager-guide.md`.
- Modify bilingual ecosystem pair: `documentation/docs/{en,zh}/reference/ecosystem.md`.
- Modify bilingual Skills pair: `documentation/docs/{en,zh}/guide/skills.md`.

**Content contract:** Each role guide answers one decision with verified inputs, completion evidence, and a prioritized next path. Contributor guidance uses actual Gradle/CI commands; Staff guidance names architecture/operating trade-offs; executive/product guidance avoids invented organizational metrics. Ecosystem and Skills pages state ownership, installation/use boundary, and stable links without duplicating other projects' documentation.

**Fact sources:** repository structure, Gradle/CI, project-local Skills, plugin manifests, and current ecosystem repositories/links.

- [ ] **Step 1: Verify the twelve-file contract fails**

```bash
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/onboarding/{contributor-guide,staff-engineer-guide,executive-guide,product-manager-guide}.md documentation/docs/{en,zh}/reference/ecosystem.md documentation/docs/{en,zh}/guide/skills.md | wc -l | tr -d ' ')" = 12
```

Expected: exit `1` before editing.

- [ ] **Step 2: Rewrite, verify, and commit**

```bash
./gradlew :wow-core:check :example-domain:check
pnpm --dir documentation docs:build
test "$(git diff --name-only main...HEAD -- documentation/docs/{en,zh}/onboarding/{contributor-guide,staff-engineer-guide,executive-guide,product-manager-guide}.md documentation/docs/{en,zh}/reference/ecosystem.md documentation/docs/{en,zh}/guide/skills.md | wc -l | tr -d ' ')" = 12
git diff --check
git add -- documentation/docs/{en,zh}/onboarding/{contributor-guide,staff-engineer-guide,executive-guide,product-manager-guide}.md documentation/docs/{en,zh}/reference/ecosystem.md documentation/docs/{en,zh}/guide/skills.md
git commit -m "docs: rewrite role and resource guides"
```

---

### Task 13: Rewrite Core Module READMEs

**Files:**
- Modify: `wow-api/README.md`
- Modify: `wow-api/README_CN.md`
- Modify: `wow-core/README.md`
- Modify: `wow-core/README_CN.md`
- Modify: `test/wow-test/README.md`
- Modify: `test/wow-test/README.zh-CN.md`

**Content contract:** Each README is a concise module entry point with purpose, when to use it, dependency coordinate, public boundary, minimal verified example, focused check, and canonical guide links. Remove stale hard-coded version badges and duplicated long tutorials. Preserve existing filenames.

**Fact sources:** module Gradle files, public packages/contracts, tests, Maven coordinates, and rewritten canonical guides.

- [ ] **Step 1: Verify the six-file contract fails**

```bash
test "$(git diff --name-only main...HEAD -- wow-api/README.md wow-api/README_CN.md wow-core/README.md wow-core/README_CN.md test/wow-test/README.md test/wow-test/README.zh-CN.md | wc -l | tr -d ' ')" = 6
```

Expected: exit `1` before editing.

- [ ] **Step 2: Rewrite, verify, and commit**

```bash
./gradlew :wow-api:check :wow-core:check :wow-test:check
pnpm --dir documentation docs:build
test "$(git diff --name-only main...HEAD -- wow-api/README.md wow-api/README_CN.md wow-core/README.md wow-core/README_CN.md test/wow-test/README.md test/wow-test/README.zh-CN.md | wc -l | tr -d ' ')" = 6
git diff --check
git add -- wow-api/README.md wow-api/README_CN.md wow-core/README.md wow-core/README_CN.md test/wow-test/README.md test/wow-test/README.zh-CN.md
git commit -m "docs: rewrite core module readmes"
```

---

### Task 14: Rewrite Supporting Module READMEs

**Files:**
- Modify: `compensation/README.md`
- Modify: `compensation/dashboard/README.md`
- Modify: `example/transfer/README.md`
- Modify: `wow-benchmarks/README.md`

**Content contract:** Replace the generic dashboard template README and turn all four files into current local entry points. Compensation explains domain/server/dashboard responsibilities and links canonical recovery docs; dashboard documents actual pnpm commands and generated-client boundary; transfer documents the runnable Java saga example; benchmarks distinguishes smoke, directional quick evidence, reproducible baseline evidence, and production-capacity non-goals.

**Fact sources:** module package/Gradle files, dashboard scripts/tests, transfer source/tests, benchmark tasks/results policy, and canonical pages.

- [ ] **Step 1: Verify the four-file contract fails**

```bash
test "$(git diff --name-only main...HEAD -- compensation/README.md compensation/dashboard/README.md example/transfer/README.md wow-benchmarks/README.md | wc -l | tr -d ' ')" = 4
```

Expected: exit `1` before editing.

- [ ] **Step 2: Rewrite and verify all four local entry points**

```bash
./gradlew :wow-compensation-domain:check :example-transfer-domain:check :wow-benchmarks:check :wow-benchmarks:benchmarkSmoke
test -x compensation/dashboard/node_modules/.bin/vite || CI=true pnpm --dir compensation/dashboard install --frozen-lockfile
pnpm --dir compensation/dashboard build
pnpm --dir compensation/dashboard test
pnpm --dir documentation docs:build
test "$(git diff --name-only main...HEAD -- compensation/README.md compensation/dashboard/README.md example/transfer/README.md wow-benchmarks/README.md | wc -l | tr -d ' ')" = 4
git diff --check
```

- [ ] **Step 3: Commit**

```bash
git add -- compensation/README.md compensation/dashboard/README.md example/transfer/README.md wow-benchmarks/README.md
git commit -m "docs: rewrite supporting module readmes"
```

---

### Task 15: Full-Scope Documentation Acceptance

**Files:**
- Verify all 160 target Markdown documents and four VitePress navigation configuration files.
- Modify only a target document or navigation file when acceptance exposes a concrete defect.

**Acceptance contract:** All 160 targets differ meaningfully from `main` and remain at original paths; 148 site pages form 74 exact bilingual pairs; both sidebars expose 73 links with no orphan; paired responsibilities/examples/limits/facts match; VitePress renders every page without broken internal links or anchors; root/module links resolve; no stale current-version badge, unsupported promise, incomplete marker, generated output, or local file enters the branch; project/docs/dashboard/browser verification passes on final HEAD.

- [ ] **Step 1: Verify complete target coverage**

Create a temporary target manifest from the 148 site files, two root READMEs, and ten module READMEs. For every entry, require `git diff --quiet main...HEAD -- "$target_doc"` to return non-zero. Require exactly 160 unique paths and report any missing target by name.

- [ ] **Step 2: Verify bilingual structure and navigation coverage**

```bash
test "$(rg --files documentation/docs/en -g '*.md' | wc -l | tr -d ' ')" = 74
test "$(rg --files documentation/docs/zh -g '*.md' | wc -l | tr -d ' ')" = 74
test -z "$(comm -3 <(rg --files documentation/docs/en -g '*.md' | sed 's#documentation/docs/en/##' | sort) <(rg --files documentation/docs/zh -g '*.md' | sed 's#documentation/docs/zh/##' | sort))"
test "$(rg -o 'link:' documentation/docs/.vitepress/configs/sidebar.en.ts | wc -l | tr -d ' ')" = 73
test "$(rg -o 'link:' documentation/docs/.vitepress/configs/sidebar.zh.ts | wc -l | tr -d ' ')" = 73
```

- [ ] **Step 3: Scan content boundaries**

Scan targets for incomplete markers, obsolete hard-coded Wow version badges, local filesystem paths, generated output references, and performance/quality claims without a nearby evidence link or explicit historical qualifier. Resolve every hit or record why it is a valid literal/example.

- [ ] **Step 4: Run complete local verification**

```bash
./gradlew build
test -x compensation/dashboard/node_modules/.bin/vite || CI=true pnpm --dir compensation/dashboard install --frozen-lockfile
pnpm --dir compensation/dashboard build
pnpm --dir compensation/dashboard test
pnpm --dir compensation/dashboard lint
pnpm --dir documentation docs:build
git diff --check main...HEAD
```

- [ ] **Step 5: Run browser QA on final site**

Preview the built site. Verify both locale homes; Start, Development, Production, Reference, API, and Resources navigation; all eight sidebar groups; the 30-minute path; representative pages from every content task; role routing; Reference/Articles URLs; and locale switching. Record routes and visible evidence.

- [ ] **Step 6: Commit only acceptance fixes when required**

If acceptance required target-file corrections, commit them together:

```bash
git add -- README.md README.zh-CN.md documentation/docs compensation/README.md compensation/dashboard/README.md example/transfer/README.md wow-api/README.md wow-api/README_CN.md wow-core/README.md wow-core/README_CN.md test/wow-test/README.md test/wow-test/README.zh-CN.md wow-benchmarks/README.md
git commit -m "docs: fix final documentation acceptance findings"
```

If acceptance is clean, create no commit.

## Final Review and PR Update

After Task 15 is clean:

1. Dispatch a most-capable whole-branch reviewer against `main...HEAD`, the design, this plan, all task reports, and the final acceptance report.
2. Fix Critical or Important findings in one scoped fix wave, rerun affected checks, and perform one scoped re-review.
3. Run fresh final verification on the resulting HEAD.
4. Push the branch once, updating PR #3058. Do not merge.
5. Wait for PR checks to reach terminal state and report local evidence, remote CI evidence, unresolved findings, and missing evidence to the user for the single requested final review.
