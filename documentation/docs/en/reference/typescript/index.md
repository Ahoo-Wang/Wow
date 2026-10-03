---
title: TypeScript Reference
description: The reference of each Wow TypeScript package, with its status and entry points.
---

# TypeScript Reference

One reference per package. Start with the [TypeScript guide](../../guide/typescript/) for the task-oriented pages, and the [compatibility matrix](../../guide/typescript/compatibility.md) for supported servers and runtimes.

| Package | Status | Entry points | Reference |
|---|---|---|---|
| `@ahoo-wang/wow-client` | On npm since Wow 9.2.0 | `@ahoo-wang/wow-client`, `/dsl` (the query DSL without HTTP), `/legacy` (the Condition API for Wow 8.10, removed in v10) | [wow-client](./wow-client/) |
| `@ahoo-wang/wow-generator` | On npm since Wow 9.2.0 | CLI `wow-generator`; `CodeGenerator` | [wow-generator](./wow-generator/) |
| `@ahoo-wang/wow-react` | On npm since Wow 9.2.0 | `@ahoo-wang/wow-react` | [wow-react](./wow-react/) |
| `@ahoo-wang/wow-view-engine` | On npm since Wow 9.2.0 | `@ahoo-wang/wow-view-engine`, `/react`, `/ui`, `/testing`, `/react-router`, the stylesheets; CLI `wow-view-engine theme-check` | [wow-view-engine](./wow-view-engine/) |
| `@ahoo-wang/wow-view-store` | On npm since Wow 9.2.0 | `@ahoo-wang/wow-view-store` | [wow-view-store](./wow-view-store/) |

All five are published on npm with provenance; Wow 9.2.0 (2026-10-03) is their first release there.
