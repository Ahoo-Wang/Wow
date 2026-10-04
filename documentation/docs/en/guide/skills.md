---
title: Agent Skills
description: Select, install, and verify the six Wow Agent Skills for downstream applications.
---

# Agent Skills

This page answers: **which Skill covers a downstream Wow task, and how is completion proved?**

The Wow repository owns Skill source and validation fixtures; the distribution repository and client own installation and discovery. Skills provide workflows, architectural invariants, authorization boundaries, and evidence gates. They do not replace target-version APIs, configuration, or generated contracts.

V9 is the current maintenance baseline and default terminology. `wow-develop` still supports V8 downstream tasks, but it must first resolve the actual Wow version from the target build and dependency graph. Only `wow-migrate` keeps V8-to-V9 type, configuration, and behavior mappings; version-specific conclusions remain unverified when the version cannot be confirmed.

## The six Skills

Each Skill is chosen by the outcome the user asks for; the client picks it from its description, and the Skill owns the whole task it is chosen for:

| Skill | Covers | Not for |
|---|---|---|
| `wow-develop` | Design, implement, test, refactor, explain, review, or debug downstream Wow behavior: first adoption, routine same-major upgrades, findings and merge readiness (fixes only when authorized), and the root cause of a failure, hang, or bad state (fixes only when authorized) | Cross-major or breaking migrations and data cutover (`wow-migrate`), non-Wow code |
| `wow-migrate` | Cross-major or known breaking source/config/generated/runtime change, or a Wow-managed store/history cutover, including reviewing or debugging one | First adoption without history conversion; routine same-major non-breaking upgrade |
| `wow-client` | TypeScript code that calls Wow: commands, queries and React query hooks with `@ahoo-wang/wow-client` and `@ahoo-wang/wow-react`, and clients generated from an OpenAPI document with the `wow-generator` CLI; moving from `@ahoo-wang/fetcher-wow` or `@ahoo-wang/fetcher-generator` | Kotlin/Java service work, view-engine definitions and hosts |
| `wow-data-query` | Answer a business data question from a running service: read its query capability descriptor and run read-only queries; the deliverable is the answer, not code | Writing query code (`wow-client`), diagnosing a rejected query or a wrong result (`wow-develop`) |
| `wow-view-definition` | Decide and write `@ahoo-wang/wow-view-engine` view definitions with `defineView` over the committed query capability descriptor: which fields, in what words, what is narrowed, system record and analysis views, boards; self-checked with the engine's `admit` | Integrating the host (`wow-view-host`), runtime client code (`wow-client`), answering data questions (`wow-data-query`), changing the view engine itself |
| `wow-view-host` | Integrate the view engine into a host: one engine with its resources and store (`MemoryViewStore`, `localStorageSnapshot`, `WowViewStore` behind the CoSec gateway), `ViewHost`, `bind` and routes, and Wow commands declared as actions; self-checked with `actionHarness`, `resolveNavigation` and `admit` | What a definition declares (`wow-view-definition`), client code outside the engine (`wow-client`), changing the view engine itself |

**Renamed in plugin 0.2.0.** `wow-review` and `wow-debug` are now part of `wow-develop`, and `wow-generator` is part of `wow-client`. The old names are not kept as aliases: refresh or reinstall `ahoo-wow-skills` to pick up the six Skills.

Do not activate these Skills for generic Kotlin, Gradle, dashboard, documentation, or DDD/CQRS work without scoped `me.ahoo.wow` imports, `wow-*` dependencies, or an explicit downstream Wow request. The Wow framework repository itself, including development of the packages under `typescript/`, is also outside every Skill's target scope, with one exception: the view definitions in this repository (the Storybook scenarios and stories under `typescript/storybook/stories/view-engine/`, and the compensation console's `compensation/dashboard/src/views/`) may activate `wow-view-definition`, and their host wiring (the engine and resources, `ViewHost`, routes, the store and declared actions, such as the console's `src/views/engine.ts`, `routes.ts`, `executionActions.ts`, `viewStore.ts` and `src/features/App/ConsoleHost.tsx`) may activate `wow-view-host`. Changing the view engine itself, or the `@ahoo-wang/wow-view-store` package, activates none.

`wow-develop` and `wow-migrate` cover Kotlin/Java services. `wow-client` covers downstream TypeScript applications that call those services, whether the clients are generated from OpenAPI or written by hand. `wow-data-query` delivers answers from a running service's data: it reads the descriptor first and queries only within it, uses a development or staging service by default, and queries production only with the user's explicit consent. `wow-view-definition` delivers view definitions and teaches only the choices: the facts come from the committed descriptor through `defineView`, the definition lists and words what its audience needs and narrows only for that audience, every word is a key said at the leaf, and the engine's `admit` over the committed descriptor is the self-check. `wow-view-host` delivers the integration: one engine, the store, `ViewHost` with its routes, and each command a person issues on a record declared as an action whose `run` resolves once the read model shows it.

Source contracts: [`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md), [`wow-develop`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-develop), [`wow-migrate`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-migrate), [`wow-client`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-client), [`wow-data-query`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-data-query), [`wow-view-definition`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-view-definition), and [`wow-view-host`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-view-host).

## Ownership and installation boundary

| Boundary | Owner | Usage |
|---|---|---|
| Skill behavior and references | Wow repository `skills/` | Edit here and run the local validator; do not edit generated copies in the aggregation repository |
| Distributable plugin manifest | Wow repository [`skills/plugins.json`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/plugins.json) | The current manifest includes the six Skills; `agents/openai.yaml` supplies client display metadata and default prompts |
| Aggregation and distribution | [Ahoo-Wang/skills](https://github.com/Ahoo-Wang/skills) | Install or refresh `ahoo-wow-skills` from the aggregate marketplace; do not treat it as the source-content edit point |
| Current installation instructions | [Ahoo Skills](https://skills.ahoo.me/) | Follow the page for the relevant client; commands and publication state may evolve independently |
| Generic format | [Agent Skills specification](https://agentskills.io/) | Defines the generic Skill format; it does not prove Wow Skill behavior |

This repository does not install Agent Skills through an application build. Successful installation proves only that a client discovered the plugin, not that a task selected the right Skill or produced a reliable result.

## Usage request

Provide at least four inputs:

```text
Goal: add cancellation behavior to Order
Scope: change only the downstream order-domain module
Authorization: code and test edits allowed; release not allowed
Evidence: run :order-domain:test and report compatibility plus missing runtime evidence
```

The Skill should then establish facts from the target checkout: read definitions, consumers, tests, configuration, and generated contracts; write only within authorization; run the narrowest valid check; and report results plus missing evidence accurately.

Rediscover complete annotation parameters, DSL methods, configuration keys, defaults, and backend lists from the target version. References provide stable decisions and discovery methods, not a frozen API manual.

## Completion evidence

A Skill task is complete only when its final report includes:

- actual target version, scope, and authorization boundary;
- behavior read or changed and its fact sources;
- exact commands, exit results, and failure counts;
- public, generated, data, or runtime compatibility impact;
- unexecuted external, production, data, release, or rollback validation marked as missing evidence.

`wow-develop` keeps reviews and diagnoses read-only without authorization and reproduces and locates a failure before fixing it; `wow-migrate` treats code, data, cutover, and release authority separately.

## Maintainer validation

After changing Skills in this repository, run:

```bash
python3 -S scripts/validate_wow_skills.py
python3 -S -m unittest scripts.test_validate_wow_skills
```

These commands validate metadata, agent manifests, plugin includes, local resource paths, and the shape of each Skill's `claude plugin eval` suite (`evals/<case>/prompt.md` plus `graders/*.md`). They do not run the cases. To measure activation and answers, run the suites locally; each run is a real agent session billed to your Claude login, so CI never runs them:

```bash
node scripts/eval-skills.mjs [skill…]
```

`SKILLS_EVAL_RUNS`, `SKILLS_EVAL_MAX_COST` (USD per Skill), `SKILLS_EVAL_CONCURRENCY` and `CLAUDE_BIN` tune the run; reports land in `skills/<name>/evals/results/`, which is not committed.

## Prioritized next path

1. State the outcome you want and include scope, authorization, and evidence in the request.
2. For first adoption, establish a runnable baseline with [Getting Started](./getting-started.md).
3. For breaking contracts or historical data, read [Migration](./migration.md) and pin exact source and target versions first.
