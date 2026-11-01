---
title: Agent Skills
description: Install the six Wow Agent Skills, pick the one for the job at hand, and know what each delivers and leaves to the others.
---

# Agent Skills

Six Agent Skills help an AI agent build applications on Wow 10. Each matches one job a reader has, delivers one kind of result, and owns the task from gathering evidence to verifying it is done. They add workflows, architectural rules, authorization boundaries and completion evidence; they do not copy the API documentation, and they confirm every API, key and default against the exact Wow version the application uses.

## Install

The plugin `ahoo-wow-skills` holds all six. Either:

- **install the plugin** `ahoo-wow-skills` from the Ahoo Skills Hub with your agent's plugin command; or
- **copy the Skills**: copy the six Skill directories under [`skills/`](https://github.com/Ahoo-Wang/Wow/tree/main/skills) of an exact Wow tag into your agent's Skills directory, such as a project's `.claude/skills/<skill>/`. Each holds a `SKILL.md`, `agents/` and `references/`, and some `assets/` and `scripts/`; leave out `evals/`, which are for maintaining them.

An installed Skill reads only its own directory. The plugin is versioned apart from the framework: after an upgrade, refresh or reinstall it and check that all six are found.

A 9.x application installs the plugin of the `v9` branch, which teaches 9.x; this one teaches Wow 10 only.

## Pick by what you deliver

| You are | Skill | It delivers | It leaves to others |
| --- | --- | --- | --- |
| Developing, reviewing or diagnosing a Wow 10 service in Kotlin or Java: aggregates, rejections, sagas, projections, queries, tests, the Spring Boot host; first adoption too | `wow-develop` | Code and tests; review findings and merge readiness; a root cause, fixed when authorized | 9.x migration (`wow-migrate`), TypeScript |
| Writing TypeScript that calls a Wow service: clients generated from the Manifest, commands with waits, queries and streams, React hooks, CoSec | `wow-client` | Generated or hand-written client code, and the CI check that regenerates it | Service code, view-engine definitions and hosts, 9.x client migration |
| Answering a business question from a running service's live data | `wow-data-query` | The answer, the read-only queries it ran, and its caveats | Query code (`wow-client`), diagnosing a failing query (`wow-develop`) |
| Writing view-engine definitions: fields, words, narrowing, system views, boards | `wow-view-definition` | `defineView` definitions, the committed descriptor snapshot, words per language, `admit` tests | Host wiring (`wow-view-host`), client code |
| Wiring the view engine into a host application: sources, datasets, the view store, `ViewHost`, routes, commands as actions | `wow-view-host` | Host wiring, with `actionHarness`, `resolveNavigation` and `admit` tests | What a definition declares (`wow-view-definition`) |
| Moving a 9.x service to Wow 10: source, configuration, HTTP and TypeScript clients, data import, cut-over and rollback | `wow-migrate` | The rewritten source and `migration-map.json`, the data rehearsal's and cut-over's evidence, the rollback plan | First adoption (`wow-develop`), upgrades within 10 |

Pick by the deliverable, not by the components a task mentions. Numbers from live data are `wow-data-query`; a view definition, a system view or a board is `wow-view-definition`; the engine's wiring in a host is `wow-view-host`; TypeScript in an application is `wow-client`. In a Kotlin or Java service, work that starts from 9.x source, configuration, clients or data is `wow-migrate`; everything else is `wow-develop`.

## When they apply

A Skill applies only to an application that uses Wow, or a task that clearly means to adopt, use, explain or migrate to it: `me.ahoo.wow` imports or `wow-*` dependencies (the Gradle plugin `me.ahoo.wow`, the BOM `me.ahoo.wow:wow-bom`), or the `@ahoo-wang/wow-*` packages on the TypeScript side. General words such as DDD, CQRS, event sourcing, aggregate or saga do not trigger them, and neither does the Wow framework repository itself, except its own view definitions and hosts (the Storybook, the ops console) and the porting of its 9.x platform modules.

## How they work

Each Skill keeps its entry contract and core flow in `SKILL.md`, and loads its `references/` only when the task needs them: the 9.x-to-10 map for a migration, the cut-over evidence for a switch. A review or a diagnosis stays read-only unless you authorize a fix, and for a migration, writing code, writing data and releasing to production are three different permissions: one never implies another. What they cannot verify, because a store, a log or a deployment is out of reach, they report as missing evidence rather than assume.
