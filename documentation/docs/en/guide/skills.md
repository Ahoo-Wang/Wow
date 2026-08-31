---
title: Agent Skills
description: Select, install, and verify the four Wow Agent Skills for downstream applications.
---

# Agent Skills

This page answers: **which Primary Skill should a downstream Wow task use, and how is completion proved?**

The Wow repository owns Skill source and validation fixtures; the distribution repository and client own installation and discovery. Skills provide workflows, architectural invariants, authorization boundaries, and evidence gates. They do not replace target-version APIs, configuration, or generated contracts.

V9 is the current maintenance baseline and default terminology. The three day-to-day Skills still support V8 downstream tasks, but they must first resolve the actual Wow version from the target build and dependency graph. Only `wow-migrate` keeps V8-to-V9 type, configuration, and behavior mappings; version-specific conclusions remain unverified when the version cannot be confirmed.

## Select one Primary Skill

Choose once from the user's primary requested outcome, then let that Skill own the complete task:

| Skill | Select when | Do not select when |
|---|---|---|
| `wow-migrate` | Cross-major or known breaking source/config/generated/runtime change, or a Wow-managed store/history cutover | First adoption without history conversion; routine same-major non-breaking upgrade |
| `wow-debug` | There is a failure, hang, bad state, or reproducer and the outcome is root cause; fix only after authorization | Proactive development, ordinary diff review, or data cutover |
| `wow-review` | The outcome is findings, merge readiness, or review-and-fix | Symptom-driven diagnosis, proactive feature work, or a breaking migration review |
| `wow-develop` | Design, implement, test, refactor, or explain downstream Wow behavior, including first adoption | Existing-diff review, existing-failure diagnosis, or breaking migration |

Do not activate these Skills for generic Kotlin, Gradle, dashboard, documentation, or DDD/CQRS work without scoped `me.ahoo.wow` imports, `wow-*` dependencies, or an explicit downstream Wow request. The Wow framework repository itself is also outside all four Skills' target scope.

Source contracts: [`skills/README.md`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/README.md), [`wow-develop`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-develop), [`wow-review`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-review), [`wow-debug`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-debug), and [`wow-migrate`](https://github.com/Ahoo-Wang/Wow/tree/main/skills/wow-migrate).

## Ownership and installation boundary

| Boundary | Owner | Usage |
|---|---|---|
| Skill behavior and references | Wow repository `skills/` | Edit here and run the local validator; do not edit generated copies in the aggregation repository |
| Distributable plugin manifest | Wow repository [`skills/plugins.json`](https://github.com/Ahoo-Wang/Wow/blob/main/skills/plugins.json) | The current manifest includes only the four Primary Skills; `agents/openai.yaml` supplies client display metadata and default prompts |
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

`wow-review` remains read-only without authorization; `wow-debug` reproduces and locates before fixing; `wow-migrate` treats code, data, cutover, and release authority separately.

## Maintainer validation

After changing Skills in this repository, run:

```bash
python3 -S scripts/validate_wow_skills.py
python3 -S -m unittest scripts.test_validate_wow_skills
```

These commands validate metadata, agent manifests, plugin includes, local resource paths, and eval JSONL structure. They do not execute behavior cases or prove natural-language activation, target APIs, or production migration. Evaluate behavior in fresh tasks against real diffs and command results.

## Prioritized next path

1. Select one Primary Skill and include scope, authorization, and evidence in the request.
2. For first adoption, establish a runnable baseline with [Getting Started](./getting-started.md).
3. For breaking contracts or historical data, read [Migration](./migration.md) and pin exact source and target versions first.
